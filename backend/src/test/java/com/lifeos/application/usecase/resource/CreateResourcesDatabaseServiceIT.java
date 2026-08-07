package com.lifeos.application.usecase.resource;

import com.lifeos.application.dto.workspace.ProvisioningOutcome;
import com.lifeos.application.dto.workspace.ProvisioningStepResult;
import com.lifeos.application.port.DatabaseSpec;
import com.lifeos.application.port.ExpectedShape;
import com.lifeos.application.port.FormulaSpec;
import com.lifeos.application.port.NotionProvisioningPort;
import com.lifeos.application.port.PageShape;
import com.lifeos.application.port.PropertyDefinition;
import com.lifeos.application.port.RecordSpec;
import com.lifeos.application.port.RelationSpec;
import com.lifeos.application.port.RollupSpec;
import com.lifeos.application.port.VerificationResult;
import com.lifeos.domain.workspace.ProvisionedResourceType;
import com.lifeos.domain.workspace.Workspace;
import com.lifeos.domain.workspace.WorkspaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static com.lifeos.domain.workspace.ProvisionedResourceType.DASHBOARD;
import static com.lifeos.domain.workspace.ProvisionedResourceType.RESOURCES_DB;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
class CreateResourcesDatabaseServiceIT {

    private static final String FIXED_DASHBOARD_ID = "dashboard-page-id";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @TestConfiguration
    static class FakeNotionPortConfiguration {
        @Bean
        @Primary
        NotionProvisioningPort notionProvisioningPort() {
            return new InMemoryDatabaseOnlyNotionPort();
        }
    }

    @Autowired
    private CreateResourcesDatabaseUseCase createResourcesDatabase;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @BeforeEach
    void clearFakeNotionState() {
        InMemoryDatabaseOnlyNotionPort.DATABASES.clear();
    }

    private Workspace persistWorkspaceWithDashboard() {
        Workspace workspace = workspaceRepository.save(Workspace.create("Personal-" + UUID.randomUUID(), UUID.randomUUID()));
        return workspaceRepository.save(workspace.record(DASHBOARD, FIXED_DASHBOARD_ID));
    }

    @Test
    void execute_persistsResourcesDbLedgerRowOnFirstRun() {
        Workspace workspace = persistWorkspaceWithDashboard();

        ProvisioningStepResult result = createResourcesDatabase.execute(workspace.getId());

        assertThat(result.outcome()).isEqualTo(ProvisioningOutcome.CREATED);
        Workspace reloaded = workspaceRepository.findById(workspace.getId()).orElseThrow();
        assertThat(reloaded.getResources().stream().filter(r -> r.type() == RESOURCES_DB)).hasSize(1);
    }

    @Test
    void execute_convergesToOneRowAcrossThreeReruns() {
        Workspace workspace = persistWorkspaceWithDashboard();

        createResourcesDatabase.execute(workspace.getId());
        ProvisioningStepResult second = createResourcesDatabase.execute(workspace.getId());
        ProvisioningStepResult third = createResourcesDatabase.execute(workspace.getId());

        assertThat(second.outcome()).isEqualTo(ProvisioningOutcome.RECONCILED);
        assertThat(third.outcome()).isEqualTo(ProvisioningOutcome.RECONCILED);
        Workspace reloaded = workspaceRepository.findById(workspace.getId()).orElseThrow();
        assertThat(reloaded.getResources().stream().filter(r -> r.type() == RESOURCES_DB)).hasSize(1);
        String notionId = reloaded.resource(RESOURCES_DB).orElseThrow().notionId();
        assertThat(notionId).isNotBlank();
    }

    @Test
    void execute_reachesRepairedOutcomeWhenFakeSimulatesExternalPropertyRemoval() {
        Workspace workspace = persistWorkspaceWithDashboard();
        createResourcesDatabase.execute(workspace.getId());
        Workspace afterFirst = workspaceRepository.findById(workspace.getId()).orElseThrow();
        String notionId = afterFirst.resource(RESOURCES_DB).orElseThrow().notionId();

        InMemoryDatabaseOnlyNotionPort.removePropertyOutOfBand(notionId, "URL");

        ProvisioningStepResult second = createResourcesDatabase.execute(workspace.getId());

        assertThat(second.outcome()).isEqualTo(ProvisioningOutcome.REPAIRED);
        Workspace afterSecond = workspaceRepository.findById(workspace.getId()).orElseThrow();
        assertThat(afterSecond.resource(RESOURCES_DB).orElseThrow().notionId()).isEqualTo(notionId);
    }

    @Test
    void execute_throwsWhenPhaseAIncomplete() {
        Workspace workspace = workspaceRepository.save(Workspace.create("Personal-" + UUID.randomUUID(), UUID.randomUUID()));

        assertThatThrownBy(() -> createResourcesDatabase.execute(workspace.getId()))
                .isInstanceOf(IllegalStateException.class);

        Workspace reloaded = workspaceRepository.findById(workspace.getId()).orElseThrow();
        assertThat(reloaded.getResources().stream().filter(r -> r.type() == RESOURCES_DB)).isEmpty();
    }

    private static final class InMemoryDatabaseOnlyNotionPort implements NotionProvisioningPort {

        private static final Map<String, DbRecord> DATABASES = new ConcurrentHashMap<>();

        private record DbRecord(String title, Set<String> propertyNames) {}

        private static void removePropertyOutOfBand(String databaseId, String propertyName) {
            DbRecord current = DATABASES.get(databaseId);
            Set<String> updated = new HashSet<>(current.propertyNames());
            updated.remove(propertyName);
            DATABASES.put(databaseId, new DbRecord(current.title(), updated));
        }

        @Override
        public String createDatabase(String parentPageId, DatabaseSpec spec) {
            String id = UUID.randomUUID().toString();
            Set<String> propertyNames = new HashSet<>();
            spec.properties().forEach(p -> propertyNames.add(p.name()));
            DATABASES.put(id, new DbRecord(spec.title(), propertyNames));
            return id;
        }

        @Override
        public VerificationResult verify(String databaseId, ProvisionedResourceType type, ExpectedShape expected) {
            DbRecord record = DATABASES.get(databaseId);
            if (record == null) {
                return VerificationResult.ABSENT;
            }
            if (!record.title().equals(expected.title())) {
                return VerificationResult.PRESENT_DRIFTED;
            }
            for (PropertyDefinition required : expected.requiredProperties()) {
                if (!record.propertyNames().contains(required.name())) {
                    return VerificationResult.PRESENT_DRIFTED;
                }
            }
            return VerificationResult.PRESENT_MATCHING;
        }

        @Override
        public Optional<String> findChildByIdentity(String parentPageId, ProvisionedResourceType type, ExpectedShape expected) {
            return DATABASES.entrySet().stream()
                    .filter(e -> e.getValue().title().equals(expected.title()))
                    .map(Map.Entry::getKey)
                    .findFirst();
        }

        @Override
        public void repairShape(String databaseId, ExpectedShape expected) {
            DbRecord current = DATABASES.get(databaseId);
            Set<String> propertyNames = new HashSet<>(current.propertyNames());
            expected.requiredProperties().forEach(p -> propertyNames.add(p.name()));
            DATABASES.put(databaseId, new DbRecord(expected.title(), propertyNames));
        }

        @Override
        public String createRootPage(PageShape expected) {
            return FIXED_DASHBOARD_ID;
        }

        @Override
        public VerificationResult verifyPage(String pageId, PageShape expected) {
            return VerificationResult.PRESENT_MATCHING;
        }

        @Override
        public void repairPage(String pageId, PageShape expected) {
        }

        @Override
        public Optional<String> findRootByIdentity(PageShape expected) {
            return Optional.of(FIXED_DASHBOARD_ID);
        }

        @Override
        public void ensureRelation(RelationSpec spec) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void ensureRollup(RollupSpec spec) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void ensureFormula(FormulaSpec spec) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean hasSampleRecords(String databaseId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void insertSampleRecords(String databaseId, List<RecordSpec> records) {
            throw new UnsupportedOperationException();
        }
    }
}
