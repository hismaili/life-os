package com.lifeos.infrastructure.adapter.persistence;

import com.lifeos.domain.workspace.ProvisionedResourceType;
import com.lifeos.domain.workspace.Workspace;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaWorkspaceRepository.class)
@Testcontainers
class JpaWorkspaceRepositoryTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private JpaWorkspaceRepository repository;

    @Autowired
    private SpringDataWorkspaceRepository springDataRepository;

    @Autowired
    private jakarta.persistence.EntityManagerFactory entityManagerFactory;

    private final UUID personId = UUID.randomUUID();

    @BeforeEach
    void enableStatistics() {
        entityManagerFactory.unwrap(SessionFactory.class).getStatistics().setStatisticsEnabled(true);
    }

    @AfterEach
    void resetStatistics() {
        entityManagerFactory.unwrap(SessionFactory.class).getStatistics().clear();
    }

    @Test
    void save_persistsNewWorkspaceWithEmptyLedger() {
        Workspace workspace = Workspace.create("Personal", personId);

        repository.save(workspace);

        Optional<Workspace> found = repository.findById(workspace.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getResources()).isEmpty();
    }

    @Test
    void save_persistsWorkspaceWithLedgerEntries() {
        Workspace workspace = Workspace.create("Personal", personId)
                .record(ProvisionedResourceType.DASHBOARD, "dash-1")
                .record(ProvisionedResourceType.TASKS_DB, "tasks-1");

        repository.save(workspace);

        Workspace reloaded = repository.findById(workspace.getId()).orElseThrow();
        assertThat(reloaded.getResources()).hasSize(2);
        assertThat(reloaded.resource(ProvisionedResourceType.DASHBOARD)).isPresent()
                .get().extracting(r -> r.notionId()).isEqualTo("dash-1");
        assertThat(reloaded.resource(ProvisionedResourceType.TASKS_DB)).isPresent()
                .get().extracting(r -> r.notionId()).isEqualTo("tasks-1");
    }

    @Test
    void save_upsertsResourceOfSameTypeOnReRecord() {
        Workspace workspace = Workspace.create("Personal", personId)
                .record(ProvisionedResourceType.TASKS_DB, "first-id");
        repository.save(workspace);

        Workspace updated = workspace.record(ProvisionedResourceType.TASKS_DB, "new-id");
        repository.save(updated);

        Workspace reloaded = repository.findById(workspace.getId()).orElseThrow();
        assertThat(reloaded.getResources())
                .filteredOn(r -> r.type() == ProvisionedResourceType.TASKS_DB)
                .hasSize(1)
                .extracting(r -> r.notionId())
                .containsExactly("new-id");
    }

    @Test
    void findById_returnsEmptyForUnknownId() {
        assertThat(repository.findById(UUID.randomUUID())).isEmpty();
    }

    @Test
    void findByPersonIdAndName_returnsMatchingWorkspace() {
        Workspace workspace = Workspace.create("Personal", personId);
        repository.save(workspace);

        Optional<Workspace> found = repository.findByPersonIdAndName(personId, "Personal");

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(workspace.getId());
    }

    @Test
    void findByPersonIdAndName_returnsEmptyWhenNameDiffers() {
        Workspace workspace = Workspace.create("Personal", personId);
        repository.save(workspace);

        assertThat(repository.findByPersonIdAndName(personId, "Work")).isEmpty();
    }

    @Test
    void findByPersonIdAndName_doesNotNPlusOneOnLedger() {
        Workspace workspace = Workspace.create("Personal", personId)
                .record(ProvisionedResourceType.DASHBOARD, "dash-1")
                .record(ProvisionedResourceType.TASKS_DB, "tasks-1")
                .record(ProvisionedResourceType.PROJECTS_DB, "projects-1");
        repository.save(workspace);
        springDataRepository.flush();

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        repository.findByPersonIdAndName(personId, "Personal");

        assertThat(statistics.getPrepareStatementCount()).isLessThanOrEqualTo(2);
    }

    @Test
    void save_rejectsDuplicatePersonIdAndName() {
        repository.save(Workspace.create("Personal", personId));
        springDataRepository.flush();

        assertThatThrownBy(() -> {
            repository.save(Workspace.create("Personal", personId));
            springDataRepository.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }
}
