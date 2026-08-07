package com.lifeos.infrastructure.adapter.cli;

import com.lifeos.application.dto.workspace.CreateWorkspaceCommand;
import com.lifeos.application.dto.workspace.ProvisioningOutcome;
import com.lifeos.application.dto.workspace.ProvisioningReport;
import com.lifeos.application.dto.workspace.ProvisioningStepResult;
import com.lifeos.application.usecase.workspace.CreateWorkspaceUseCase;
import lombok.RequiredArgsConstructor;
import org.jline.terminal.Terminal;
import org.springframework.shell.command.annotation.Command;
import org.springframework.shell.command.annotation.Option;

import java.util.UUID;
import java.util.stream.Collectors;

@Command(group = "Workspace")
@RequiredArgsConstructor
public class WorkspaceCommands {

    private final CreateWorkspaceUseCase createWorkspace;
    private final Terminal terminal;

    @Command(command = "workspace create", description = "Create or reconcile a LifeOS workspace in Notion")
    public String create(
            @Option(longNames = "name", required = true) String name,
            @Option(longNames = "person-id", required = true) UUID personId,
            @Option(longNames = "sample-data", defaultValue = "false") boolean sampleData,
            @Option(longNames = "notion-token", required = true,
                    description = "Notion integration token (BYOK — supplied per invocation, never read from environment or persisted)") String notionToken,
            @Option(longNames = "notion-root-parent-page-id", required = true,
                    description = "Notion page id the workspace root is created under") String notionRootParentPageId) {

        ProvisioningReport report = createWorkspace.execute(
                new CreateWorkspaceCommand(name, personId, sampleData, notionToken, notionRootParentPageId));
        String rendering = renderReport(report);

        if (report.failed()) {
            terminal.writer().println(rendering);
            terminal.writer().flush();
            throw new CommandFailedException(conciseFailureSummary(report));
        }
        return rendering;
    }

    private String renderReport(ProvisioningReport report) {
        StringBuilder builder = new StringBuilder();
        for (ProvisioningStepResult step : report.steps()) {
            builder.append(ResourceTypeLabel.of(step.type()))
                    .append(": ")
                    .append(step.outcome());
            if (step.detail() != null && !step.detail().isBlank()) {
                builder.append(" (").append(step.detail()).append(")");
            }
            builder.append(System.lineSeparator());
        }
        return builder.toString();
    }

    private String conciseFailureSummary(ProvisioningReport report) {
        var failedOrBlocked = report.steps().stream()
                .filter(WorkspaceCommands::failedOrBlocked)
                .toList();
        String labels = failedOrBlocked.stream()
                .map(step -> ResourceTypeLabel.of(step.type()))
                .collect(Collectors.joining(", "));
        return failedOrBlocked.size() + " of " + report.steps().size()
                + " provisioning steps failed: " + labels;
    }

    private static boolean failedOrBlocked(ProvisioningStepResult step) {
        return step.outcome() == ProvisioningOutcome.FAILED || step.outcome() == ProvisioningOutcome.BLOCKED;
    }
}
