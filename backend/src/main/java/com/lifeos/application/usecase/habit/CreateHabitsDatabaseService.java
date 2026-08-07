package com.lifeos.application.usecase.habit;

import com.lifeos.application.dto.workspace.ProvisioningOutcome;
import com.lifeos.application.dto.workspace.ProvisioningStepResult;
import com.lifeos.application.port.DatabaseSpec;
import com.lifeos.application.port.ExpectedShape;
import com.lifeos.application.port.NotionPropertyType;
import com.lifeos.application.port.NotionProvisioningPort;
import com.lifeos.application.port.PropertyDefinition;
import com.lifeos.application.port.VerificationResult;
import com.lifeos.application.usecase.workspace.WorkspaceLedgerWriter;
import com.lifeos.domain.habit.Frequency;
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
import static com.lifeos.domain.workspace.ProvisionedResourceType.HABITS_DB;

@Slf4j
@Service
@RequiredArgsConstructor
public class CreateHabitsDatabaseService implements CreateHabitsDatabaseUseCase {

    private static final String TITLE = "Habits";

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

        DatabaseSpec spec = habitsSpec();
        ExpectedShape expected = habitsExpectedShape();
        Optional<String> ledgerId = workspace.resource(HABITS_DB).map(ProvisionedResource::notionId);

        ProvisioningStepResult result = ledgerId.isPresent()
                ? executeWarmPath(workspaceId, ledgerId.get(), dashboardId, spec, expected)
                : executeColdPath(workspaceId, dashboardId, spec, expected);

        log.info("Habits database step for workspace {}: dashboardId={}, priorLedgerId={}, outcome={}",
                workspaceId, dashboardId, ledgerId.orElse("none"), result.outcome());
        return result;
    }

    private ProvisioningStepResult executeWarmPath(UUID workspaceId, String existingId, String dashboardId,
                                                     DatabaseSpec spec, ExpectedShape expected) {
        VerificationResult result = notion.verify(existingId, HABITS_DB, expected);
        log.info("Habits database verify for workspace {}: notionId={}, result={}", workspaceId, existingId, result);
        return switch (result) {
            case PRESENT_MATCHING -> new ProvisioningStepResult(HABITS_DB, ProvisioningOutcome.RECONCILED, null);
            case PRESENT_DRIFTED -> {
                notion.repairShape(existingId, expected);
                ledger.record(workspaceId, HABITS_DB, existingId);
                yield new ProvisioningStepResult(HABITS_DB, ProvisioningOutcome.REPAIRED, "database drifted; shape repaired");
            }
            case ABSENT -> {
                Optional<String> found = notion.findChildByIdentity(dashboardId, HABITS_DB, expected);
                if (found.isPresent()) {
                    ledger.record(workspaceId, HABITS_DB, found.get());
                    yield new ProvisioningStepResult(HABITS_DB, ProvisioningOutcome.REPAIRED, "ledger id was stale; re-adopted existing database");
                }
                String newId = notion.createDatabase(dashboardId, spec);
                ledger.record(workspaceId, HABITS_DB, newId);
                yield new ProvisioningStepResult(HABITS_DB, ProvisioningOutcome.REPAIRED, "ledger id was stale; database recreated");
            }
        };
    }

    private ProvisioningStepResult executeColdPath(UUID workspaceId, String dashboardId, DatabaseSpec spec, ExpectedShape expected) {
        Optional<String> found = notion.findChildByIdentity(dashboardId, HABITS_DB, expected);
        log.info("Habits database findChildByIdentity for workspace {}: found={}", workspaceId, found.isPresent());
        if (found.isEmpty()) {
            String newId = notion.createDatabase(dashboardId, spec);
            ledger.record(workspaceId, HABITS_DB, newId);
            return new ProvisioningStepResult(HABITS_DB, ProvisioningOutcome.CREATED, null);
        }

        String orphanId = found.get();
        VerificationResult orphanVerify = notion.verify(orphanId, HABITS_DB, expected);
        return switch (orphanVerify) {
            case PRESENT_MATCHING -> {
                ledger.record(workspaceId, HABITS_DB, orphanId);
                yield new ProvisioningStepResult(HABITS_DB, ProvisioningOutcome.RECONCILED, null);
            }
            case PRESENT_DRIFTED -> {
                notion.repairShape(orphanId, expected);
                ledger.record(workspaceId, HABITS_DB, orphanId);
                yield new ProvisioningStepResult(HABITS_DB, ProvisioningOutcome.REPAIRED, "adopted orphan database was drifted; shape repaired");
            }
            case ABSENT -> {
                String newId = notion.createDatabase(dashboardId, spec);
                ledger.record(workspaceId, HABITS_DB, newId);
                yield new ProvisioningStepResult(HABITS_DB, ProvisioningOutcome.CREATED, null);
            }
        };
    }

    static DatabaseSpec habitsSpec() {
        List<String> frequencyOptions = Arrays.stream(Frequency.values())
                .map(Enum::name)
                .toList();
        return new DatabaseSpec(TITLE, List.of(
                PropertyDefinition.of("Name", NotionPropertyType.TITLE),
                new PropertyDefinition("Frequency", NotionPropertyType.SELECT, frequencyOptions)));
    }

    static ExpectedShape habitsExpectedShape() {
        return new ExpectedShape(TITLE, habitsSpec().properties());
    }
}
