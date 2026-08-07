package com.lifeos.application.usecase.workspace;

import com.lifeos.application.dto.workspace.ProvisioningOutcome;
import com.lifeos.application.dto.workspace.ProvisioningStepResult;
import com.lifeos.application.port.NotionProvisioningPort;
import com.lifeos.application.port.PageShape;
import com.lifeos.application.port.ParentConstraint;
import com.lifeos.application.port.VerificationResult;
import com.lifeos.domain.workspace.ProvisionedResource;
import com.lifeos.domain.workspace.Workspace;
import com.lifeos.domain.workspace.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

import static com.lifeos.domain.workspace.ProvisionedResourceType.DASHBOARD;

@Slf4j
@Service
@RequiredArgsConstructor
public class CreateDashboardService implements CreateDashboardUseCase {

    private final NotionProvisioningPort notion;
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceLedgerWriter ledger;

    @Override
    public ProvisioningStepResult execute(UUID workspaceId) {
        Workspace workspace = workspaceRepository.findById(workspaceId)
                .orElseThrow(() -> new IllegalStateException("Workspace not found: " + workspaceId));

        PageShape shape = new PageShape(dashboardTitle(workspace), ParentConstraint.ROOT_PARENT);
        Optional<String> ledgerId = workspace.resource(DASHBOARD).map(ProvisionedResource::notionId);

        ProvisioningStepResult result = ledgerId.isPresent()
                ? executeWarmPath(workspaceId, ledgerId.get(), shape)
                : executeColdPath(workspaceId, shape);

        log.info("Dashboard step for workspace {}: priorLedgerId={}, outcome={}",
                workspaceId, ledgerId.orElse("none"), result.outcome());
        return result;
    }

    private ProvisioningStepResult executeWarmPath(UUID workspaceId, String existingId, PageShape shape) {
        VerificationResult result = notion.verifyPage(existingId, shape);
        log.info("Dashboard verifyPage for workspace {}: notionId={}, result={}", workspaceId, existingId, result);
        return switch (result) {
            case PRESENT_MATCHING -> new ProvisioningStepResult(DASHBOARD, ProvisioningOutcome.RECONCILED, null);
            case PRESENT_DRIFTED -> {
                notion.repairPage(existingId, shape);
                ledger.record(workspaceId, DASHBOARD, existingId);
                yield new ProvisioningStepResult(DASHBOARD, ProvisioningOutcome.REPAIRED, "renamed/moved page repaired");
            }
            case ABSENT -> {
                Optional<String> found = notion.findRootByIdentity(shape);
                if (found.isPresent()) {
                    ledger.record(workspaceId, DASHBOARD, found.get());
                    yield new ProvisioningStepResult(DASHBOARD, ProvisioningOutcome.REPAIRED, "ledger id was stale; re-adopted existing page");
                }
                String newId = notion.createRootPage(shape);
                ledger.record(workspaceId, DASHBOARD, newId);
                yield new ProvisioningStepResult(DASHBOARD, ProvisioningOutcome.REPAIRED, "ledger id was stale; page recreated");
            }
        };
    }

    private ProvisioningStepResult executeColdPath(UUID workspaceId, PageShape shape) {
        Optional<String> found = notion.findRootByIdentity(shape);
        log.info("Dashboard findRootByIdentity for workspace {}: found={}", workspaceId, found.isPresent());
        if (found.isEmpty()) {
            String newId = notion.createRootPage(shape);
            ledger.record(workspaceId, DASHBOARD, newId);
            return new ProvisioningStepResult(DASHBOARD, ProvisioningOutcome.CREATED, null);
        }
        ledger.record(workspaceId, DASHBOARD, found.get());
        return new ProvisioningStepResult(DASHBOARD, ProvisioningOutcome.RECONCILED, null);
    }

    static String dashboardTitle(Workspace w) {
        return "LifeOS — " + w.getName();
    }
}
