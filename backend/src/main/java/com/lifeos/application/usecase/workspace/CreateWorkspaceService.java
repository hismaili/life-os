package com.lifeos.application.usecase.workspace;

import com.lifeos.application.dto.workspace.CreateWorkspaceCommand;
import com.lifeos.application.dto.workspace.ProvisioningOutcome;
import com.lifeos.application.dto.workspace.ProvisioningReport;
import com.lifeos.application.dto.workspace.ProvisioningStepResult;
import com.lifeos.application.port.NotionCredentials;
import com.lifeos.application.port.NotionCredentialsHolder;
import com.lifeos.application.usecase.knowledge.CreateKnowledgeDatabaseUseCase;
import com.lifeos.application.usecase.habit.CreateHabitsDatabaseUseCase;
import com.lifeos.application.usecase.journal.CreateJournalDatabaseUseCase;
import com.lifeos.application.usecase.person.CreatePeopleDatabaseUseCase;
import com.lifeos.application.usecase.project.CreateProjectsDatabaseUseCase;
import com.lifeos.application.usecase.resource.CreateResourcesDatabaseUseCase;
import com.lifeos.application.usecase.task.CreateTasksDatabaseUseCase;
import com.lifeos.domain.workspace.ProvisionedResourceType;
import com.lifeos.domain.workspace.Workspace;
import com.lifeos.domain.workspace.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
public class CreateWorkspaceService implements CreateWorkspaceUseCase {

    private static final Logger log = LoggerFactory.getLogger(CreateWorkspaceService.class);

    private final WorkspaceRepository workspaceRepository;
    private final CreateDashboardUseCase createDashboard;
    private final CreateProjectsDatabaseUseCase createProjectsDatabase;
    private final CreateTasksDatabaseUseCase createTasksDatabase;
    private final CreateKnowledgeDatabaseUseCase createKnowledgeDatabase;
    private final CreateHabitsDatabaseUseCase createHabitsDatabase;
    private final CreateJournalDatabaseUseCase createJournalDatabase;
    private final CreateResourcesDatabaseUseCase createResourcesDatabase;
    private final CreatePeopleDatabaseUseCase createPeopleDatabase;
    private final CreateRelationsUseCase createRelations;
    private final CreateRollupsUseCase createRollups;
    private final CreateFormulasUseCase createFormulas;
    private final PopulateSampleDataUseCase populateSampleData;

    @Override
    public ProvisioningReport execute(CreateWorkspaceCommand command) {
        NotionCredentialsHolder.set(new NotionCredentials(command.notionToken(), command.notionRootParentPageId()));
        try {
            return doExecute(command);
        } finally {
            NotionCredentialsHolder.clear();
        }
    }

    private ProvisioningReport doExecute(CreateWorkspaceCommand command) {
        Workspace workspace = workspaceRepository.findByPersonIdAndName(command.personId(), command.name())
                .orElseGet(() -> workspaceRepository.save(Workspace.create(command.name(), command.personId())));

        List<ProvisioningStepResult> results = new ArrayList<>();

        ProvisioningStepResult dashboard = runStep(() -> createDashboard.execute(workspace.getId()), ProvisionedResourceType.DASHBOARD);
        results.add(dashboard);
        boolean phaseAOk = isOk(dashboard);

        List<ProvisioningStepResult> dbResults = new ArrayList<>();
        dbResults.add(runOrBlock(phaseAOk, () -> createProjectsDatabase.execute(workspace.getId()), ProvisionedResourceType.PROJECTS_DB));
        dbResults.add(runOrBlock(phaseAOk, () -> createTasksDatabase.execute(workspace.getId()), ProvisionedResourceType.TASKS_DB));
        dbResults.add(runOrBlock(phaseAOk, () -> createKnowledgeDatabase.execute(workspace.getId()), ProvisionedResourceType.KNOWLEDGE_DB));
        dbResults.add(runOrBlock(phaseAOk, () -> createHabitsDatabase.execute(workspace.getId()), ProvisionedResourceType.HABITS_DB));
        dbResults.add(runOrBlock(phaseAOk, () -> createJournalDatabase.execute(workspace.getId()), ProvisionedResourceType.JOURNAL_DB));
        dbResults.add(runOrBlock(phaseAOk, () -> createResourcesDatabase.execute(workspace.getId()), ProvisionedResourceType.RESOURCES_DB));
        dbResults.add(runOrBlock(phaseAOk, () -> createPeopleDatabase.execute(workspace.getId()), ProvisionedResourceType.PEOPLE_DB));
        results.addAll(dbResults);
        boolean phaseBOk = dbResults.stream().allMatch(CreateWorkspaceService::isOk);

        ProvisioningStepResult relations = runOrBlock(phaseAOk && phaseBOk, () -> createRelations.execute(workspace.getId()), ProvisionedResourceType.RELATIONS);
        results.add(relations);
        boolean phaseCOk = isOk(relations);

        ProvisioningStepResult rollups = runOrBlock(phaseCOk, () -> createRollups.execute(workspace.getId()), ProvisionedResourceType.ROLLUPS);
        results.add(rollups);
        boolean phaseDOk = isOk(rollups);

        ProvisioningStepResult formulas = runOrBlock(phaseDOk, () -> createFormulas.execute(workspace.getId()), ProvisionedResourceType.FORMULAS);
        results.add(formulas);
        boolean phaseEOk = isOk(formulas);

        boolean allStructuralOk = phaseAOk && phaseBOk && phaseCOk && phaseDOk && phaseEOk;
        if (command.sampleData()) {
            results.add(runOrBlock(allStructuralOk, () -> populateSampleData.execute(workspace.getId()), ProvisionedResourceType.SAMPLE_DATA));
        }

        return new ProvisioningReport(workspace.getId(), results);
    }

    private static boolean isOk(ProvisioningStepResult r) {
        return r.outcome() != ProvisioningOutcome.FAILED && r.outcome() != ProvisioningOutcome.BLOCKED;
    }

    private ProvisioningStepResult runStep(Supplier<ProvisioningStepResult> step, ProvisionedResourceType type) {
        try {
            return step.get();
        } catch (Exception e) {
            log.error("Provisioning step {} failed", type, e);
            return new ProvisioningStepResult(type, ProvisioningOutcome.FAILED, safeDetail(e));
        }
    }

    private static final String GENERIC_FAILURE_DETAIL = "internal error during provisioning (see server logs)";

    private static String safeDetail(Exception e) {
        if (e instanceof SafeToSurfaceException || e instanceof UnsupportedOperationException) {
            String message = e.getMessage();
            return (message == null || message.isBlank()) ? GENERIC_FAILURE_DETAIL : message;
        }
        return GENERIC_FAILURE_DETAIL;
    }

    private ProvisioningStepResult runOrBlock(boolean prerequisiteOk, Supplier<ProvisioningStepResult> step, ProvisionedResourceType type) {
        if (!prerequisiteOk) {
            return new ProvisioningStepResult(type, ProvisioningOutcome.BLOCKED, "prerequisite step failed or was blocked");
        }
        return runStep(step, type);
    }
}
