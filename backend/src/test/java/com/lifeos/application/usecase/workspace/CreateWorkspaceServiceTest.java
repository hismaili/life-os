package com.lifeos.application.usecase.workspace;

import com.lifeos.application.dto.workspace.CreateWorkspaceCommand;
import com.lifeos.application.dto.workspace.ProvisioningOutcome;
import com.lifeos.application.dto.workspace.ProvisioningReport;
import com.lifeos.application.dto.workspace.ProvisioningStepResult;
import com.lifeos.application.port.NotionCredentialsHolder;
import com.lifeos.application.usecase.habit.CreateHabitsDatabaseUseCase;
import com.lifeos.application.usecase.journal.CreateJournalDatabaseUseCase;
import com.lifeos.application.usecase.knowledge.CreateKnowledgeDatabaseUseCase;
import com.lifeos.application.usecase.person.CreatePeopleDatabaseUseCase;
import com.lifeos.application.usecase.project.CreateProjectsDatabaseUseCase;
import com.lifeos.application.usecase.resource.CreateResourcesDatabaseUseCase;
import com.lifeos.application.usecase.task.CreateTasksDatabaseUseCase;
import com.lifeos.domain.workspace.ProvisionedResourceType;
import com.lifeos.domain.workspace.Workspace;
import com.lifeos.domain.workspace.WorkspaceRepository;
import com.lifeos.infrastructure.adapter.notion.NotionApiException;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CreateWorkspaceServiceTest {

    @Mock private WorkspaceRepository workspaceRepository;
    @Mock private CreateDashboardUseCase createDashboard;
    @Mock private CreateProjectsDatabaseUseCase createProjectsDatabase;
    @Mock private CreateTasksDatabaseUseCase createTasksDatabase;
    @Mock private CreateKnowledgeDatabaseUseCase createKnowledgeDatabase;
    @Mock private CreateHabitsDatabaseUseCase createHabitsDatabase;
    @Mock private CreateJournalDatabaseUseCase createJournalDatabase;
    @Mock private CreateResourcesDatabaseUseCase createResourcesDatabase;
    @Mock private CreatePeopleDatabaseUseCase createPeopleDatabase;
    @Mock private CreateRelationsUseCase createRelations;
    @Mock private CreateRollupsUseCase createRollups;
    @Mock private CreateFormulasUseCase createFormulas;
    @Mock private PopulateSampleDataUseCase populateSampleData;

    private CreateWorkspaceService service;

    private final UUID personId = UUID.randomUUID();
    private Workspace workspace;

    @BeforeEach
    void setUp() {
        service = new CreateWorkspaceService(
                workspaceRepository, createDashboard, createProjectsDatabase, createTasksDatabase,
                createKnowledgeDatabase, createHabitsDatabase, createJournalDatabase, createResourcesDatabase,
                createPeopleDatabase, createRelations, createRollups, createFormulas, populateSampleData);
        workspace = Workspace.create("Personal", personId);
    }

    private CreateWorkspaceCommand command(boolean sampleData) {
        return new CreateWorkspaceCommand("Personal", personId, sampleData, "notion-token", "root-parent-id");
    }

    private void stubAllHappy() {
        when(createDashboard.execute(any())).thenReturn(created(ProvisionedResourceType.DASHBOARD));
        when(createProjectsDatabase.execute(any())).thenReturn(created(ProvisionedResourceType.PROJECTS_DB));
        when(createTasksDatabase.execute(any())).thenReturn(created(ProvisionedResourceType.TASKS_DB));
        when(createKnowledgeDatabase.execute(any())).thenReturn(created(ProvisionedResourceType.KNOWLEDGE_DB));
        when(createHabitsDatabase.execute(any())).thenReturn(created(ProvisionedResourceType.HABITS_DB));
        when(createJournalDatabase.execute(any())).thenReturn(created(ProvisionedResourceType.JOURNAL_DB));
        when(createResourcesDatabase.execute(any())).thenReturn(created(ProvisionedResourceType.RESOURCES_DB));
        when(createPeopleDatabase.execute(any())).thenReturn(created(ProvisionedResourceType.PEOPLE_DB));
        when(createRelations.execute(any())).thenReturn(created(ProvisionedResourceType.RELATIONS));
        when(createRollups.execute(any())).thenReturn(created(ProvisionedResourceType.ROLLUPS));
        when(createFormulas.execute(any())).thenReturn(created(ProvisionedResourceType.FORMULAS));
    }

    private static ProvisioningStepResult created(ProvisionedResourceType type) {
        return new ProvisioningStepResult(type, ProvisioningOutcome.CREATED, null);
    }

    @Test
    void execute_createsNewWorkspaceWhenNoneExists() {
        when(workspaceRepository.findByPersonIdAndName(personId, "Personal")).thenReturn(Optional.empty());
        when(workspaceRepository.save(any())).thenReturn(workspace);
        stubAllHappy();

        service.execute(command(false));

        verify(workspaceRepository, times(1)).save(any());
    }

    @Test
    void execute_reusesExistingWorkspaceWhenPresent() {
        when(workspaceRepository.findByPersonIdAndName(personId, "Personal")).thenReturn(Optional.of(workspace));
        stubAllHappy();

        service.execute(command(false));

        verify(workspaceRepository, never()).save(any());
    }

    @Test
    void execute_runsAllPhasesInOrderOnHappyPath() {
        when(workspaceRepository.findByPersonIdAndName(personId, "Personal")).thenReturn(Optional.of(workspace));
        stubAllHappy();

        ProvisioningReport report = service.execute(command(false));

        InOrder order = inOrder(createDashboard, createProjectsDatabase, createTasksDatabase, createKnowledgeDatabase,
                createHabitsDatabase, createJournalDatabase, createResourcesDatabase, createPeopleDatabase,
                createRelations, createRollups, createFormulas);
        order.verify(createDashboard).execute(workspace.getId());
        order.verify(createProjectsDatabase).execute(workspace.getId());
        order.verify(createTasksDatabase).execute(workspace.getId());
        order.verify(createKnowledgeDatabase).execute(workspace.getId());
        order.verify(createHabitsDatabase).execute(workspace.getId());
        order.verify(createJournalDatabase).execute(workspace.getId());
        order.verify(createResourcesDatabase).execute(workspace.getId());
        order.verify(createPeopleDatabase).execute(workspace.getId());
        order.verify(createRelations).execute(workspace.getId());
        order.verify(createRollups).execute(workspace.getId());
        order.verify(createFormulas).execute(workspace.getId());

        assertThat(report.steps()).hasSize(11);
    }

    @Test
    void execute_mapsThrownExceptionFromAStepToFailedResult() {
        when(workspaceRepository.findByPersonIdAndName(personId, "Personal")).thenReturn(Optional.of(workspace));
        when(createDashboard.execute(any())).thenReturn(created(ProvisionedResourceType.DASHBOARD));
        when(createProjectsDatabase.execute(any())).thenReturn(created(ProvisionedResourceType.PROJECTS_DB));
        when(createTasksDatabase.execute(any())).thenThrow(new UnsupportedOperationException("x"));
        when(createKnowledgeDatabase.execute(any())).thenReturn(created(ProvisionedResourceType.KNOWLEDGE_DB));
        when(createHabitsDatabase.execute(any())).thenReturn(created(ProvisionedResourceType.HABITS_DB));
        when(createJournalDatabase.execute(any())).thenReturn(created(ProvisionedResourceType.JOURNAL_DB));
        when(createResourcesDatabase.execute(any())).thenReturn(created(ProvisionedResourceType.RESOURCES_DB));
        when(createPeopleDatabase.execute(any())).thenReturn(created(ProvisionedResourceType.PEOPLE_DB));

        ProvisioningReport report = service.execute(command(false));

        ProvisioningStepResult tasksResult = report.steps().stream()
                .filter(s -> s.type() == ProvisionedResourceType.TASKS_DB)
                .findFirst().orElseThrow();
        assertThat(tasksResult.outcome()).isEqualTo(ProvisioningOutcome.FAILED);
        assertThat(tasksResult.detail()).contains("x");
    }

    @Test
    void execute_blocksDependentDatabaseStepsWhenDashboardFails() {
        when(workspaceRepository.findByPersonIdAndName(personId, "Personal")).thenReturn(Optional.of(workspace));
        when(createDashboard.execute(any())).thenThrow(new UnsupportedOperationException("dashboard failed"));

        ProvisioningReport report = service.execute(command(false));

        verifyNoInteractions(createProjectsDatabase, createTasksDatabase, createKnowledgeDatabase,
                createHabitsDatabase, createJournalDatabase, createResourcesDatabase, createPeopleDatabase,
                createRelations, createRollups, createFormulas);

        List<ProvisioningStepResult> dbResults = report.steps().stream()
                .filter(s -> s.type() != ProvisionedResourceType.DASHBOARD)
                .toList();
        assertThat(dbResults).allMatch(s -> s.outcome() == ProvisioningOutcome.BLOCKED);
    }

    @Test
    void execute_blocksRelationsWhenAnyDatabaseStepFails() {
        when(workspaceRepository.findByPersonIdAndName(personId, "Personal")).thenReturn(Optional.of(workspace));
        when(createDashboard.execute(any())).thenReturn(created(ProvisionedResourceType.DASHBOARD));
        when(createProjectsDatabase.execute(any())).thenReturn(created(ProvisionedResourceType.PROJECTS_DB));
        when(createTasksDatabase.execute(any())).thenReturn(new ProvisioningStepResult(
                ProvisionedResourceType.TASKS_DB, ProvisioningOutcome.FAILED, "boom"));
        when(createKnowledgeDatabase.execute(any())).thenReturn(created(ProvisionedResourceType.KNOWLEDGE_DB));
        when(createHabitsDatabase.execute(any())).thenReturn(created(ProvisionedResourceType.HABITS_DB));
        when(createJournalDatabase.execute(any())).thenReturn(created(ProvisionedResourceType.JOURNAL_DB));
        when(createResourcesDatabase.execute(any())).thenReturn(created(ProvisionedResourceType.RESOURCES_DB));
        when(createPeopleDatabase.execute(any())).thenReturn(created(ProvisionedResourceType.PEOPLE_DB));

        ProvisioningReport report = service.execute(command(false));

        verifyNoInteractions(createRelations);
        ProvisioningStepResult relationsResult = report.steps().stream()
                .filter(s -> s.type() == ProvisionedResourceType.RELATIONS)
                .findFirst().orElseThrow();
        assertThat(relationsResult.outcome()).isEqualTo(ProvisioningOutcome.BLOCKED);
    }

    @Test
    void execute_blocksRollupsWhenRelationsBlocked() {
        when(workspaceRepository.findByPersonIdAndName(personId, "Personal")).thenReturn(Optional.of(workspace));
        when(createDashboard.execute(any())).thenThrow(new UnsupportedOperationException("dashboard failed"));

        ProvisioningReport report = service.execute(command(false));

        verifyNoInteractions(createRollups);
        assertThat(report.steps().stream().filter(s -> s.type() == ProvisionedResourceType.ROLLUPS).findFirst()
                .orElseThrow().outcome()).isEqualTo(ProvisioningOutcome.BLOCKED);
    }

    @Test
    void execute_blocksFormulasWhenRollupsBlocked() {
        when(workspaceRepository.findByPersonIdAndName(personId, "Personal")).thenReturn(Optional.of(workspace));
        when(createDashboard.execute(any())).thenThrow(new UnsupportedOperationException("dashboard failed"));

        ProvisioningReport report = service.execute(command(false));

        verifyNoInteractions(createFormulas);
        assertThat(report.steps().stream().filter(s -> s.type() == ProvisionedResourceType.FORMULAS).findFirst()
                .orElseThrow().outcome()).isEqualTo(ProvisioningOutcome.BLOCKED);
    }

    @Test
    void execute_runsIndependentDatabaseStepsEvenWhenAnotherDatabaseStepFails() {
        when(workspaceRepository.findByPersonIdAndName(personId, "Personal")).thenReturn(Optional.of(workspace));
        when(createDashboard.execute(any())).thenReturn(created(ProvisionedResourceType.DASHBOARD));
        when(createProjectsDatabase.execute(any())).thenReturn(created(ProvisionedResourceType.PROJECTS_DB));
        when(createTasksDatabase.execute(any())).thenThrow(new UnsupportedOperationException("boom"));
        when(createKnowledgeDatabase.execute(any())).thenReturn(created(ProvisionedResourceType.KNOWLEDGE_DB));
        when(createHabitsDatabase.execute(any())).thenReturn(created(ProvisionedResourceType.HABITS_DB));
        when(createJournalDatabase.execute(any())).thenReturn(created(ProvisionedResourceType.JOURNAL_DB));
        when(createResourcesDatabase.execute(any())).thenReturn(created(ProvisionedResourceType.RESOURCES_DB));
        when(createPeopleDatabase.execute(any())).thenReturn(created(ProvisionedResourceType.PEOPLE_DB));

        ProvisioningReport report = service.execute(command(false));

        verify(createProjectsDatabase).execute(workspace.getId());
        verify(createKnowledgeDatabase).execute(workspace.getId());
        verify(createHabitsDatabase).execute(workspace.getId());
        verify(createJournalDatabase).execute(workspace.getId());
        verify(createResourcesDatabase).execute(workspace.getId());
        verify(createPeopleDatabase).execute(workspace.getId());

        assertThat(report.steps().stream().filter(s -> s.type() == ProvisionedResourceType.PROJECTS_DB)
                .findFirst().orElseThrow().outcome()).isEqualTo(ProvisioningOutcome.CREATED);
    }

    @Test
    void execute_skipsSampleDataStepWhenFlagFalse() {
        when(workspaceRepository.findByPersonIdAndName(personId, "Personal")).thenReturn(Optional.of(workspace));
        stubAllHappy();

        ProvisioningReport report = service.execute(command(false));

        verifyNoInteractions(populateSampleData);
        assertThat(report.steps()).noneMatch(s -> s.type() == ProvisionedResourceType.SAMPLE_DATA);
    }

    @Test
    void execute_runsSampleDataStepWhenFlagTrueAndAllStructuralPhasesSucceed() {
        when(workspaceRepository.findByPersonIdAndName(personId, "Personal")).thenReturn(Optional.of(workspace));
        stubAllHappy();
        when(populateSampleData.execute(any())).thenReturn(created(ProvisionedResourceType.SAMPLE_DATA));

        service.execute(command(true));

        verify(populateSampleData, times(1)).execute(workspace.getId());
    }

    @Test
    void execute_blocksSampleDataStepWhenFlagTrueButAStructuralPhaseFailed() {
        when(workspaceRepository.findByPersonIdAndName(personId, "Personal")).thenReturn(Optional.of(workspace));
        when(createDashboard.execute(any())).thenThrow(new UnsupportedOperationException("dashboard failed"));

        ProvisioningReport report = service.execute(command(true));

        verifyNoInteractions(populateSampleData);
        assertThat(report.steps().stream().filter(s -> s.type() == ProvisionedResourceType.SAMPLE_DATA)
                .findFirst().orElseThrow().outcome()).isEqualTo(ProvisioningOutcome.BLOCKED);
    }

    @Test
    void execute_reportFailedTrueWhenAnyStepFailedOrBlocked() {
        when(workspaceRepository.findByPersonIdAndName(personId, "Personal")).thenReturn(Optional.of(workspace));
        when(createDashboard.execute(any())).thenThrow(new UnsupportedOperationException("dashboard failed"));

        ProvisioningReport report = service.execute(command(false));

        assertThat(report.failed()).isTrue();
    }

    @Test
    void execute_reportFailedFalseOnFullHappyPath() {
        when(workspaceRepository.findByPersonIdAndName(personId, "Personal")).thenReturn(Optional.of(workspace));
        stubAllHappy();

        ProvisioningReport report = service.execute(command(false));

        assertThat(report.failed()).isFalse();
    }

    @Test
    void execute_surfacesNotionApiExceptionMessageVerbatimOnStepFailure() {
        when(workspaceRepository.findByPersonIdAndName(personId, "Personal")).thenReturn(Optional.of(workspace));
        when(createDashboard.execute(any())).thenReturn(created(ProvisionedResourceType.DASHBOARD));
        when(createProjectsDatabase.execute(any())).thenReturn(created(ProvisionedResourceType.PROJECTS_DB));
        when(createTasksDatabase.execute(any())).thenThrow(new NotionApiException("no data source on Tasks"));
        when(createKnowledgeDatabase.execute(any())).thenReturn(created(ProvisionedResourceType.KNOWLEDGE_DB));
        when(createHabitsDatabase.execute(any())).thenReturn(created(ProvisionedResourceType.HABITS_DB));
        when(createJournalDatabase.execute(any())).thenReturn(created(ProvisionedResourceType.JOURNAL_DB));
        when(createResourcesDatabase.execute(any())).thenReturn(created(ProvisionedResourceType.RESOURCES_DB));
        when(createPeopleDatabase.execute(any())).thenReturn(created(ProvisionedResourceType.PEOPLE_DB));

        ProvisioningReport report = service.execute(command(false));

        ProvisioningStepResult tasksResult = report.steps().stream()
                .filter(s -> s.type() == ProvisionedResourceType.TASKS_DB)
                .findFirst().orElseThrow();
        assertThat(tasksResult.detail()).isEqualTo("no data source on Tasks");
    }

    @Test
    void execute_collapsesUnexpectedExceptionToGenericSafeDetail() {
        when(workspaceRepository.findByPersonIdAndName(personId, "Personal")).thenReturn(Optional.of(workspace));
        when(createDashboard.execute(any())).thenReturn(created(ProvisionedResourceType.DASHBOARD));
        when(createProjectsDatabase.execute(any())).thenReturn(created(ProvisionedResourceType.PROJECTS_DB));
        when(createTasksDatabase.execute(any())).thenThrow(new IllegalStateException("jdbc://internal-host:5432 connection refused"));
        when(createKnowledgeDatabase.execute(any())).thenReturn(created(ProvisionedResourceType.KNOWLEDGE_DB));
        when(createHabitsDatabase.execute(any())).thenReturn(created(ProvisionedResourceType.HABITS_DB));
        when(createJournalDatabase.execute(any())).thenReturn(created(ProvisionedResourceType.JOURNAL_DB));
        when(createResourcesDatabase.execute(any())).thenReturn(created(ProvisionedResourceType.RESOURCES_DB));
        when(createPeopleDatabase.execute(any())).thenReturn(created(ProvisionedResourceType.PEOPLE_DB));

        ProvisioningReport report = service.execute(command(false));

        ProvisioningStepResult tasksResult = report.steps().stream()
                .filter(s -> s.type() == ProvisionedResourceType.TASKS_DB)
                .findFirst().orElseThrow();
        assertThat(tasksResult.detail()).isEqualTo("internal error during provisioning (see server logs)");
        assertThat(tasksResult.detail()).doesNotContain("TASKS_DB");
        assertThat(tasksResult.detail()).doesNotContain("jdbc");
        assertThat(tasksResult.detail()).doesNotContain("internal-host");
    }

    @Test
    void execute_fallsBackToGenericDetailWhenSafeToSurfaceExceptionHasNoMessage() {
        when(workspaceRepository.findByPersonIdAndName(personId, "Personal")).thenReturn(Optional.of(workspace));
        when(createDashboard.execute(any())).thenReturn(created(ProvisionedResourceType.DASHBOARD));
        when(createProjectsDatabase.execute(any())).thenReturn(created(ProvisionedResourceType.PROJECTS_DB));
        when(createTasksDatabase.execute(any())).thenThrow(new NotionApiException((String) null));
        when(createKnowledgeDatabase.execute(any())).thenReturn(created(ProvisionedResourceType.KNOWLEDGE_DB));
        when(createHabitsDatabase.execute(any())).thenReturn(created(ProvisionedResourceType.HABITS_DB));
        when(createJournalDatabase.execute(any())).thenReturn(created(ProvisionedResourceType.JOURNAL_DB));
        when(createResourcesDatabase.execute(any())).thenReturn(created(ProvisionedResourceType.RESOURCES_DB));
        when(createPeopleDatabase.execute(any())).thenReturn(created(ProvisionedResourceType.PEOPLE_DB));

        ProvisioningReport report = service.execute(command(false));

        ProvisioningStepResult tasksResult = report.steps().stream()
                .filter(s -> s.type() == ProvisionedResourceType.TASKS_DB)
                .findFirst().orElseThrow();
        assertThat(tasksResult.outcome()).isEqualTo(ProvisioningOutcome.FAILED);
        assertThat(tasksResult.detail()).isEqualTo("internal error during provisioning (see server logs)");
    }

    @Test
    void execute_logsRawCauseAtErrorForFailedStep() {
        Logger logger = (Logger) LoggerFactory.getLogger(CreateWorkspaceService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            when(workspaceRepository.findByPersonIdAndName(personId, "Personal")).thenReturn(Optional.of(workspace));
            when(createDashboard.execute(any())).thenReturn(created(ProvisionedResourceType.DASHBOARD));
            when(createProjectsDatabase.execute(any())).thenReturn(created(ProvisionedResourceType.PROJECTS_DB));
            IllegalStateException cause = new IllegalStateException("jdbc://internal-host:5432 connection refused");
            when(createTasksDatabase.execute(any())).thenThrow(cause);
            when(createKnowledgeDatabase.execute(any())).thenReturn(created(ProvisionedResourceType.KNOWLEDGE_DB));
            when(createHabitsDatabase.execute(any())).thenReturn(created(ProvisionedResourceType.HABITS_DB));
            when(createJournalDatabase.execute(any())).thenReturn(created(ProvisionedResourceType.JOURNAL_DB));
            when(createResourcesDatabase.execute(any())).thenReturn(created(ProvisionedResourceType.RESOURCES_DB));
            when(createPeopleDatabase.execute(any())).thenReturn(created(ProvisionedResourceType.PEOPLE_DB));

            service.execute(command(false));

            assertThat(appender.list)
                    .anySatisfy(event -> {
                        assertThat(event.getLevel()).isEqualTo(ch.qos.logback.classic.Level.ERROR);
                        assertThat(event.getFormattedMessage()).contains("TASKS_DB");
                        assertThat(event.getThrowableProxy().getClassName()).isEqualTo(IllegalStateException.class.getName());
                        assertThat(event.getThrowableProxy().getMessage()).isEqualTo("jdbc://internal-host:5432 connection refused");
                    });
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void execute_clearsNotionCredentialsHolderAfterSuccess() {
        when(workspaceRepository.findByPersonIdAndName(personId, "Personal")).thenReturn(Optional.of(workspace));
        stubAllHappy();

        service.execute(command(false));

        assertThatThrownBy(NotionCredentialsHolder::require).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void execute_clearsNotionCredentialsHolderEvenWhenAStepThrows() {
        when(workspaceRepository.findByPersonIdAndName(personId, "Personal")).thenReturn(Optional.of(workspace));
        when(createDashboard.execute(any())).thenThrow(new RuntimeException("boom"));

        service.execute(command(false));

        assertThatThrownBy(NotionCredentialsHolder::require).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void execute_isNotAnnotatedTransactional() throws NoSuchMethodException {
        assertThat(CreateWorkspaceService.class.getMethod("execute", CreateWorkspaceCommand.class)
                .isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class)).isFalse();
        assertThat(CreateWorkspaceService.class
                .isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class)).isFalse();
    }
}
