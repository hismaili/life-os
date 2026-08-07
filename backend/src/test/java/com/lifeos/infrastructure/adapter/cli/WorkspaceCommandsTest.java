package com.lifeos.infrastructure.adapter.cli;

import com.lifeos.application.dto.workspace.CreateWorkspaceCommand;
import com.lifeos.application.dto.workspace.ProvisioningOutcome;
import com.lifeos.application.dto.workspace.ProvisioningReport;
import com.lifeos.application.dto.workspace.ProvisioningStepResult;
import com.lifeos.application.usecase.workspace.CreateWorkspaceUseCase;
import com.lifeos.domain.workspace.ProvisionedResourceType;
import org.jline.terminal.Terminal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkspaceCommandsTest {

    @Mock
    private CreateWorkspaceUseCase createWorkspace;

    @Mock
    private Terminal terminal;

    private WorkspaceCommands commands;

    private final UUID personId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();

    @Test
    void create_invokesUseCaseWithParsedArguments() {
        commands = new WorkspaceCommands(createWorkspace, terminal);
        when(createWorkspace.execute(any())).thenReturn(new ProvisioningReport(workspaceId, List.of(
                new ProvisioningStepResult(ProvisionedResourceType.DASHBOARD, ProvisioningOutcome.CREATED, null))));

        commands.create("Personal", personId, true, "notion-token", "root-id");

        ArgumentCaptor<CreateWorkspaceCommand> captor = ArgumentCaptor.forClass(CreateWorkspaceCommand.class);
        verify(createWorkspace).execute(captor.capture());
        assertThat(captor.getValue()).isEqualTo(new CreateWorkspaceCommand("Personal", personId, true, "notion-token", "root-id"));
    }

    @Test
    void create_rendersAllStepsOnSuccess() {
        commands = new WorkspaceCommands(createWorkspace, terminal);
        when(createWorkspace.execute(any())).thenReturn(new ProvisioningReport(workspaceId, List.of(
                new ProvisioningStepResult(ProvisionedResourceType.DASHBOARD, ProvisioningOutcome.CREATED, null),
                new ProvisioningStepResult(ProvisionedResourceType.TASKS_DB, ProvisioningOutcome.RECONCILED, null))));

        String rendering = commands.create("Personal", personId, false, "notion-token", "root-id");

        assertThat(rendering).contains("Dashboard").contains("CREATED");
        assertThat(rendering).contains("Tasks").contains("RECONCILED");
    }

    @Test
    void create_rendersHumanReadableLabelsNotRawEnumConstants() {
        commands = new WorkspaceCommands(createWorkspace, terminal);
        when(createWorkspace.execute(any())).thenReturn(new ProvisioningReport(workspaceId, List.of(
                new ProvisioningStepResult(ProvisionedResourceType.TASKS_DB, ProvisioningOutcome.CREATED, null))));

        String rendering = commands.create("Personal", personId, false, "notion-token", "root-id");

        assertThat(rendering).contains("Tasks");
        assertThat(rendering).doesNotContain("TASKS_DB");
    }

    @Test
    void create_signalsFailureWhenReportFailed() {
        commands = new WorkspaceCommands(createWorkspace, terminal);
        when(createWorkspace.execute(any())).thenReturn(new ProvisioningReport(workspaceId, List.of(
                new ProvisioningStepResult(ProvisionedResourceType.DASHBOARD, ProvisioningOutcome.FAILED, "boom"))));
        when(terminal.writer()).thenReturn(new PrintWriter(new StringWriter()));

        assertThatThrownBy(() -> commands.create("Personal", personId, false, "notion-token", "root-id"))
                .isInstanceOf(CommandFailedException.class)
                .hasMessage("1 of 1 provisioning steps failed: Dashboard");
    }

    @Test
    void create_writesFullReportToTerminalOnFailureBeforeThrowing() {
        commands = new WorkspaceCommands(createWorkspace, terminal);
        when(createWorkspace.execute(any())).thenReturn(new ProvisioningReport(workspaceId, List.of(
                new ProvisioningStepResult(ProvisionedResourceType.DASHBOARD, ProvisioningOutcome.FAILED, "boom"))));
        StringWriter buffer = new StringWriter();
        when(terminal.writer()).thenReturn(new PrintWriter(buffer));

        assertThatThrownBy(() -> commands.create("Personal", personId, false, "notion-token", "root-id"))
                .isInstanceOf(CommandFailedException.class);

        assertThat(buffer.toString()).contains("Dashboard: FAILED (boom)");
    }
}
