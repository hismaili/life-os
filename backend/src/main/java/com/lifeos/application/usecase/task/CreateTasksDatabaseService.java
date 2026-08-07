package com.lifeos.application.usecase.task;

import com.lifeos.application.dto.workspace.ProvisioningOutcome;
import com.lifeos.application.dto.workspace.ProvisioningStepResult;
import com.lifeos.application.port.DatabaseSpec;
import com.lifeos.application.port.ExpectedShape;
import com.lifeos.application.port.NotionPropertyType;
import com.lifeos.application.port.NotionProvisioningPort;
import com.lifeos.application.port.PropertyDefinition;
import com.lifeos.application.port.VerificationResult;
import com.lifeos.application.usecase.workspace.WorkspaceLedgerWriter;
import com.lifeos.domain.task.TaskStatus;
import com.lifeos.domain.workspace.ProvisionedResource;
import com.lifeos.domain.workspace.Workspace;
import com.lifeos.domain.workspace.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.lifeos.domain.workspace.ProvisionedResourceType.DASHBOARD;
import static com.lifeos.domain.workspace.ProvisionedResourceType.TASKS_DB;

@Slf4j
@Service
@RequiredArgsConstructor
public class CreateTasksDatabaseService implements CreateTasksDatabaseUseCase {

    private static final String TITLE = "Tasks";

    private final NotionProvisioningPort notion;
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceLedgerWriter ledger;

    @Override
    public ProvisioningStepResult execute(UUID workspaceId) {
        Workspace workspace = workspaceRepository.findById(workspaceId)
                .orElseThrow(() -> new IllegalStateException("Workspace not found: " + workspaceId));

        String dashboardId = workspace.resource(DASHBOARD)
                .map(ProvisionedResource::notionId)
                .orElseThrow(() -> new IllegalStateException("No confirmed Dashboard for workspace " + workspaceId));

        DatabaseSpec spec = tasksSpec();
        ExpectedShape expected = tasksExpectedShape();
        Optional<String> ledgerId = workspace.resource(TASKS_DB).map(ProvisionedResource::notionId);

        ProvisioningStepResult result = ledgerId.isPresent()
                ? executeWarmPath(workspaceId, ledgerId.get(), dashboardId, spec, expected)
                : executeColdPath(workspaceId, dashboardId, spec, expected);

        log.info("Tasks database step for workspace {}: dashboardId={}, priorLedgerId={}, outcome={}",
                workspaceId, dashboardId, ledgerId.orElse("none"), result.outcome());
        return result;
    }

    private ProvisioningStepResult executeWarmPath(UUID workspaceId, String existingId, String dashboardId,
                                                     DatabaseSpec spec, ExpectedShape expected) {
        VerificationResult result = notion.verify(existingId, TASKS_DB, expected);
        log.info("Tasks database verify for workspace {}: notionId={}, result={}", workspaceId, existingId, result);
        return switch (result) {
            case PRESENT_MATCHING -> new ProvisioningStepResult(TASKS_DB, ProvisioningOutcome.RECONCILED, null);
            case PRESENT_DRIFTED -> {
                notion.repairShape(existingId, expected);
                ledger.record(workspaceId, TASKS_DB, existingId);
                yield new ProvisioningStepResult(TASKS_DB, ProvisioningOutcome.REPAIRED, "database drifted; shape repaired");
            }
            case ABSENT -> {
                Optional<String> found = notion.findChildByIdentity(dashboardId, TASKS_DB, expected);
                if (found.isPresent()) {
                    ledger.record(workspaceId, TASKS_DB, found.get());
                    yield new ProvisioningStepResult(TASKS_DB, ProvisioningOutcome.REPAIRED, "ledger id was stale; re-adopted existing database");
                }
                String newId = notion.createDatabase(dashboardId, spec);
                ledger.record(workspaceId, TASKS_DB, newId);
                yield new ProvisioningStepResult(TASKS_DB, ProvisioningOutcome.REPAIRED, "ledger id was stale; database recreated");
            }
        };
    }

    private ProvisioningStepResult executeColdPath(UUID workspaceId, String dashboardId, DatabaseSpec spec, ExpectedShape expected) {
        Optional<String> found = notion.findChildByIdentity(dashboardId, TASKS_DB, expected);
        log.info("Tasks database findChildByIdentity for workspace {}: found={}", workspaceId, found.isPresent());
        if (found.isEmpty()) {
            String newId = notion.createDatabase(dashboardId, spec);
            ledger.record(workspaceId, TASKS_DB, newId);
            return new ProvisioningStepResult(TASKS_DB, ProvisioningOutcome.CREATED, null);
        }

        String orphanId = found.get();
        VerificationResult orphanVerify = notion.verify(orphanId, TASKS_DB, expected);
        return switch (orphanVerify) {
            case PRESENT_MATCHING -> {
                ledger.record(workspaceId, TASKS_DB, orphanId);
                yield new ProvisioningStepResult(TASKS_DB, ProvisioningOutcome.RECONCILED, null);
            }
            case PRESENT_DRIFTED -> {
                notion.repairShape(orphanId, expected);
                ledger.record(workspaceId, TASKS_DB, orphanId);
                yield new ProvisioningStepResult(TASKS_DB, ProvisioningOutcome.REPAIRED, "adopted orphan database was drifted; shape repaired");
            }
            case ABSENT -> {
                String newId = notion.createDatabase(dashboardId, spec);
                ledger.record(workspaceId, TASKS_DB, newId);
                yield new ProvisioningStepResult(TASKS_DB, ProvisioningOutcome.CREATED, null);
            }
        };
    }

    static DatabaseSpec tasksSpec() {
        List<String> statusOptions = Arrays.stream(TaskStatus.values())
                .map(Enum::name)
                .toList();
        return new DatabaseSpec(TITLE, List.of(
                PropertyDefinition.of("Title", NotionPropertyType.TITLE),
                PropertyDefinition.of("Description", NotionPropertyType.RICH_TEXT),
                new PropertyDefinition("Status", NotionPropertyType.SELECT, statusOptions),
                PropertyDefinition.of("Due Date", NotionPropertyType.DATE)));
    }

    static ExpectedShape tasksExpectedShape() {
        return new ExpectedShape(TITLE, tasksSpec().properties());
    }
}
