package com.lifeos.application.usecase.workspace;

import com.lifeos.application.dto.workspace.ProvisioningOutcome;
import com.lifeos.application.dto.workspace.ProvisioningStepResult;
import com.lifeos.application.port.DatabaseSpec;
import com.lifeos.application.port.ExpectedShape;
import com.lifeos.application.port.FormulaSpec;
import com.lifeos.application.port.NotionProvisioningPort;
import com.lifeos.application.port.PageShape;
import com.lifeos.application.port.RecordSpec;
import com.lifeos.application.port.RelationSpec;
import com.lifeos.application.port.RollupSpec;
import com.lifeos.application.port.VerificationResult;
import com.lifeos.domain.workspace.ProvisionedResourceType;
import com.lifeos.domain.workspace.Workspace;
import com.lifeos.domain.workspace.WorkspaceRepository;
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

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static com.lifeos.domain.workspace.ProvisionedResourceType.DASHBOARD;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class CreateDashboardServiceIT {

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
            return new InMemoryPageOnlyNotionPort();
        }
    }

    @Autowired
    private CreateDashboardUseCase createDashboard;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    private Workspace persistFreshWorkspace() {
        return workspaceRepository.save(Workspace.create("Personal-" + UUID.randomUUID(), UUID.randomUUID()));
    }

    @Test
    void execute_persistsDashboardLedgerRowOnFirstRun() {
        Workspace workspace = persistFreshWorkspace();

        ProvisioningStepResult result = createDashboard.execute(workspace.getId());

        assertThat(result.outcome()).isEqualTo(ProvisioningOutcome.CREATED);
        Workspace reloaded = workspaceRepository.findById(workspace.getId()).orElseThrow();
        assertThat(reloaded.resource(DASHBOARD)).isPresent();
    }

    @Test
    void execute_convergesToOneRowAcrossReruns() {
        Workspace workspace = persistFreshWorkspace();

        createDashboard.execute(workspace.getId());
        ProvisioningStepResult second = createDashboard.execute(workspace.getId());

        assertThat(second.outcome()).isEqualTo(ProvisioningOutcome.RECONCILED);
        Workspace reloaded = workspaceRepository.findById(workspace.getId()).orElseThrow();
        assertThat(reloaded.getResources().stream().filter(r -> r.type() == DASHBOARD)).hasSize(1);
    }

    @Test
    void execute_reachesRepairedOutcomeWhenFakeSimulatesExternalRename() {
        Workspace workspace = persistFreshWorkspace();
        createDashboard.execute(workspace.getId());
        Workspace afterFirst = workspaceRepository.findById(workspace.getId()).orElseThrow();
        String notionId = afterFirst.resource(DASHBOARD).orElseThrow().notionId();

        InMemoryPageOnlyNotionPort.renameOutOfBand(notionId, "Renamed Externally");

        ProvisioningStepResult second = createDashboard.execute(workspace.getId());

        assertThat(second.outcome()).isEqualTo(ProvisioningOutcome.REPAIRED);
        Workspace afterSecond = workspaceRepository.findById(workspace.getId()).orElseThrow();
        assertThat(afterSecond.resource(DASHBOARD).orElseThrow().notionId()).isEqualTo(notionId);
    }

    private static final class InMemoryPageOnlyNotionPort implements NotionProvisioningPort {

        private static final Map<String, PageRecord> PAGES = new ConcurrentHashMap<>();

        private record PageRecord(String title, String parentId) {}

        private static void renameOutOfBand(String pageId, String newTitle) {
            PageRecord current = PAGES.get(pageId);
            PAGES.put(pageId, new PageRecord(newTitle, current.parentId()));
        }

        @Override
        public String createRootPage(PageShape expected) {
            String id = UUID.randomUUID().toString();
            PAGES.put(id, new PageRecord(expected.title(), rootParentId()));
            return id;
        }

        @Override
        public VerificationResult verifyPage(String pageId, PageShape expected) {
            PageRecord page = PAGES.get(pageId);
            if (page == null) {
                return VerificationResult.ABSENT;
            }
            if (!page.parentId().equals(rootParentId()) || !page.title().equals(expected.title())) {
                return VerificationResult.PRESENT_DRIFTED;
            }
            return VerificationResult.PRESENT_MATCHING;
        }

        @Override
        public void repairPage(String pageId, PageShape expected) {
            PAGES.put(pageId, new PageRecord(expected.title(), rootParentId()));
        }

        @Override
        public Optional<String> findRootByIdentity(PageShape expected) {
            return PAGES.entrySet().stream()
                    .filter(e -> e.getValue().title().equals(expected.title()) && e.getValue().parentId().equals(rootParentId()))
                    .map(Map.Entry::getKey)
                    .findFirst();
        }

        private static String rootParentId() {
            return "root-parent";
        }

        @Override
        public VerificationResult verify(String rootPageId, ProvisionedResourceType type, ExpectedShape expected) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<String> findChildByIdentity(String rootPageId, ProvisionedResourceType type, ExpectedShape expected) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String createDatabase(String rootPageId, DatabaseSpec spec) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void repairShape(String notionId, ExpectedShape expected) {
            throw new UnsupportedOperationException();
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
