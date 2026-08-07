package com.lifeos.application.usecase.knowledge;

import com.lifeos.application.dto.workspace.ProvisioningOutcome;
import com.lifeos.application.dto.workspace.ProvisioningStepResult;
import com.lifeos.application.port.DatabaseSpec;
import com.lifeos.application.port.ExpectedShape;
import com.lifeos.application.port.NotionPropertyType;
import com.lifeos.application.port.NotionProvisioningPort;
import com.lifeos.application.port.PropertyDefinition;
import com.lifeos.application.port.VerificationResult;
import com.lifeos.application.usecase.workspace.WorkspaceLedgerWriter;
import com.lifeos.domain.workspace.ProvisionedResource;
import com.lifeos.domain.workspace.Workspace;
import com.lifeos.domain.workspace.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.lifeos.domain.workspace.ProvisionedResourceType.DASHBOARD;
import static com.lifeos.domain.workspace.ProvisionedResourceType.KNOWLEDGE_DB;

@Slf4j
@Service
@RequiredArgsConstructor
public class CreateKnowledgeDatabaseService implements CreateKnowledgeDatabaseUseCase {

    private static final String TITLE = "Knowledge";

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

        DatabaseSpec spec = knowledgeSpec();
        ExpectedShape expected = knowledgeExpectedShape();
        Optional<String> ledgerId = workspace.resource(KNOWLEDGE_DB).map(ProvisionedResource::notionId);

        ProvisioningStepResult result = ledgerId.isPresent()
                ? executeWarmPath(workspaceId, ledgerId.get(), dashboardId, spec, expected)
                : executeColdPath(workspaceId, dashboardId, spec, expected);

        log.info("Knowledge database step for workspace {}: dashboardId={}, priorLedgerId={}, outcome={}",
                workspaceId, dashboardId, ledgerId.orElse("none"), result.outcome());
        return result;
    }

    private ProvisioningStepResult executeWarmPath(UUID workspaceId, String existingId, String dashboardId,
                                                     DatabaseSpec spec, ExpectedShape expected) {
        VerificationResult result = notion.verify(existingId, KNOWLEDGE_DB, expected);
        log.info("Knowledge database verify for workspace {}: notionId={}, result={}", workspaceId, existingId, result);
        return switch (result) {
            case PRESENT_MATCHING -> new ProvisioningStepResult(KNOWLEDGE_DB, ProvisioningOutcome.RECONCILED, null);
            case PRESENT_DRIFTED -> {
                notion.repairShape(existingId, expected);
                ledger.record(workspaceId, KNOWLEDGE_DB, existingId);
                yield new ProvisioningStepResult(KNOWLEDGE_DB, ProvisioningOutcome.REPAIRED, "database drifted; shape repaired");
            }
            case ABSENT -> {
                Optional<String> found = notion.findChildByIdentity(dashboardId, KNOWLEDGE_DB, expected);
                if (found.isPresent()) {
                    ledger.record(workspaceId, KNOWLEDGE_DB, found.get());
                    yield new ProvisioningStepResult(KNOWLEDGE_DB, ProvisioningOutcome.REPAIRED, "ledger id was stale; re-adopted existing database");
                }
                String newId = notion.createDatabase(dashboardId, spec);
                ledger.record(workspaceId, KNOWLEDGE_DB, newId);
                yield new ProvisioningStepResult(KNOWLEDGE_DB, ProvisioningOutcome.REPAIRED, "ledger id was stale; database recreated");
            }
        };
    }

    private ProvisioningStepResult executeColdPath(UUID workspaceId, String dashboardId, DatabaseSpec spec, ExpectedShape expected) {
        Optional<String> found = notion.findChildByIdentity(dashboardId, KNOWLEDGE_DB, expected);
        log.info("Knowledge database findChildByIdentity for workspace {}: found={}", workspaceId, found.isPresent());
        if (found.isEmpty()) {
            String newId = notion.createDatabase(dashboardId, spec);
            ledger.record(workspaceId, KNOWLEDGE_DB, newId);
            return new ProvisioningStepResult(KNOWLEDGE_DB, ProvisioningOutcome.CREATED, null);
        }

        String orphanId = found.get();
        VerificationResult orphanVerify = notion.verify(orphanId, KNOWLEDGE_DB, expected);
        return switch (orphanVerify) {
            case PRESENT_MATCHING -> {
                ledger.record(workspaceId, KNOWLEDGE_DB, orphanId);
                yield new ProvisioningStepResult(KNOWLEDGE_DB, ProvisioningOutcome.RECONCILED, null);
            }
            case PRESENT_DRIFTED -> {
                notion.repairShape(orphanId, expected);
                ledger.record(workspaceId, KNOWLEDGE_DB, orphanId);
                yield new ProvisioningStepResult(KNOWLEDGE_DB, ProvisioningOutcome.REPAIRED, "adopted orphan database was drifted; shape repaired");
            }
            case ABSENT -> {
                String newId = notion.createDatabase(dashboardId, spec);
                ledger.record(workspaceId, KNOWLEDGE_DB, newId);
                yield new ProvisioningStepResult(KNOWLEDGE_DB, ProvisioningOutcome.CREATED, null);
            }
        };
    }

    static DatabaseSpec knowledgeSpec() {
        return new DatabaseSpec(TITLE, List.of(
                PropertyDefinition.of("Title", NotionPropertyType.TITLE),
                PropertyDefinition.of("Content", NotionPropertyType.RICH_TEXT)));
    }

    static ExpectedShape knowledgeExpectedShape() {
        return new ExpectedShape(TITLE, knowledgeSpec().properties());
    }
}
