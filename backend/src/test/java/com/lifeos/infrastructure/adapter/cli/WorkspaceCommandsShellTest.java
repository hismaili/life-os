package com.lifeos.infrastructure.adapter.cli;

import com.lifeos.application.dto.workspace.CreateWorkspaceCommand;
import com.lifeos.application.dto.workspace.ProvisioningOutcome;
import com.lifeos.application.dto.workspace.ProvisioningReport;
import com.lifeos.application.dto.workspace.ProvisioningStepResult;
import com.lifeos.application.usecase.workspace.CreateWorkspaceUseCase;
import com.lifeos.domain.workspace.ProvisionedResourceType;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.shell.Shell;
import org.springframework.shell.command.CommandExecution;
import org.springframework.shell.boot.CommandCatalogAutoConfiguration;
import org.springframework.shell.boot.CompleterAutoConfiguration;
import org.springframework.shell.boot.ExitCodeAutoConfiguration;
import org.springframework.shell.boot.JLineAutoConfiguration;
import org.springframework.shell.boot.JLineShellAutoConfiguration;
import org.springframework.shell.boot.ParameterResolverAutoConfiguration;
import org.springframework.shell.boot.ShellContextAutoConfiguration;
import org.springframework.shell.boot.SpringShellAutoConfiguration;
import org.springframework.shell.boot.StandardAPIAutoConfiguration;
import org.springframework.shell.boot.UserConfigAutoConfiguration;
import org.springframework.shell.command.annotation.CommandScan;
import org.springframework.shell.context.DefaultShellContext;
import org.springframework.shell.jline.NonInteractiveShellRunner;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Exercises {@link WorkspaceCommands} through the real Spring Shell command-dispatch pipeline
 * (argument binding, {@code required} option enforcement, {@code defaultValue} resolution).
 *
 * <p>The tech spec called for {@code @ShellTest} (spring-shell-test), but that annotation does not
 * exist in {@code spring-shell-test:3.3.2} — the version pinned by this project (verified: the jar
 * contains only {@code ShellTestClient}/{@code ShellScreen}/{@code ShellAssertions}, no annotation
 * class, and no auto-configuration wiring them as beans). This test is the faithful equivalent: it
 * boots the same {@code @CommandScan}-discovered composition through a narrow, explicit import of
 * the individual {@code spring-shell-autoconfigure} classes (mirroring what {@code @ShellTest} would
 * wire) and runs commands through the real {@link Shell} + {@link NonInteractiveShellRunner}, the
 * same classes {@code ShellTestClient.nonInterative(...)} delegates to internally. Output is
 * asserted against a captured dumb {@link Terminal} buffer in place of {@code ShellScreen}.
 *
 * <p>This composition (like the real application) registers no
 * {@code CommandExceptionResolver}, so parser and command exceptions propagate uncaught out of
 * {@code NonInteractiveShellRunner.run(...)} — assertions below catch them directly rather than
 * reading them off a rendered screen.
 */
class WorkspaceCommandsShellTest {

    @Configuration
    @CommandScan(basePackageClasses = WorkspaceCommands.class)
    static class ShellTestConfig {
    }

    private static ApplicationContextRunner contextRunner(Terminal terminal, CreateWorkspaceUseCase createWorkspace) {
        return new ApplicationContextRunner()
                .withUserConfiguration(ShellTestConfig.class)
                .withBean("testTerminal", Terminal.class, () -> terminal, bd -> bd.setPrimary(true))
                .withBean("testCreateWorkspace", CreateWorkspaceUseCase.class, () -> createWorkspace)
                .withConfiguration(AutoConfigurations.of(
                        SpringShellAutoConfiguration.class,
                        JLineAutoConfiguration.class,
                        JLineShellAutoConfiguration.class,
                        CompleterAutoConfiguration.class,
                        CommandCatalogAutoConfiguration.class,
                        ShellContextAutoConfiguration.class,
                        ExitCodeAutoConfiguration.class,
                        ParameterResolverAutoConfiguration.class,
                        StandardAPIAutoConfiguration.class,
                        UserConfigAutoConfiguration.class));
    }

    private static Terminal dumbTerminal(ByteArrayOutputStream captured) throws Exception {
        return TerminalBuilder.builder()
                .streams(new ByteArrayInputStream(new byte[0]), captured)
                .system(false)
                .dumb(true)
                .build();
    }

    private static String captured(ByteArrayOutputStream out, Terminal terminal) {
        terminal.writer().flush();
        terminal.flush();
        return out.toString(StandardCharsets.UTF_8);
    }

    private static void run(Shell shell, String... args) throws Exception {
        new NonInteractiveShellRunner(shell, new DefaultShellContext()).run(args);
    }

    @Test
    void create_rejectsMissingRequiredNameOption() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Terminal terminal = dumbTerminal(out);
        CreateWorkspaceUseCase createWorkspace = Mockito.mock(CreateWorkspaceUseCase.class);

        contextRunner(terminal, createWorkspace).run(context -> {
            Shell shell = context.getBean(Shell.class);

            assertThatThrownBy(() -> run(shell, "workspace", "create", "--person-id", UUID.randomUUID().toString()))
                    .isInstanceOf(CommandExecution.CommandParserExceptionsException.class)
                    .satisfies(e -> assertThat(((CommandExecution.CommandParserExceptionsException) e).getParserExceptions())
                            .anySatisfy(parserException -> assertThat(parserException.getMessage()).containsIgnoringCase("name")));

            verifyNoInteractions(createWorkspace);
        });
    }

    @Test
    void create_rejectsMissingRequiredPersonIdOption() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Terminal terminal = dumbTerminal(out);
        CreateWorkspaceUseCase createWorkspace = Mockito.mock(CreateWorkspaceUseCase.class);

        contextRunner(terminal, createWorkspace).run(context -> {
            Shell shell = context.getBean(Shell.class);

            assertThatThrownBy(() -> run(shell, "workspace", "create", "--name", "Personal"))
                    .isInstanceOf(CommandExecution.CommandParserExceptionsException.class)
                    .satisfies(e -> assertThat(((CommandExecution.CommandParserExceptionsException) e).getParserExceptions())
                            .anySatisfy(parserException -> assertThat(parserException.getMessage()).containsIgnoringCase("person-id")));

            verifyNoInteractions(createWorkspace);
        });
    }

    @Test
    void create_resolvesSampleDataDefaultToFalseWhenOptionOmitted() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Terminal terminal = dumbTerminal(out);
        CreateWorkspaceUseCase createWorkspace = Mockito.mock(CreateWorkspaceUseCase.class);
        UUID personId = UUID.randomUUID();
        when(createWorkspace.execute(any())).thenReturn(new ProvisioningReport(UUID.randomUUID(),
                List.of(new ProvisioningStepResult(ProvisionedResourceType.DASHBOARD, ProvisioningOutcome.CREATED, null))));

        contextRunner(terminal, createWorkspace).run(context -> {
            Shell shell = context.getBean(Shell.class);

            run(shell, "workspace", "create", "--name", "Personal", "--person-id", personId.toString());

            ArgumentCaptor<CreateWorkspaceCommand> captor = ArgumentCaptor.forClass(CreateWorkspaceCommand.class);
            verify(createWorkspace).execute(captor.capture());
            assertThat(captor.getValue().sampleData()).isFalse();
        });
    }

    @Test
    void create_signalsFailureExitWhenReportFailed() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Terminal terminal = dumbTerminal(out);
        CreateWorkspaceUseCase createWorkspace = Mockito.mock(CreateWorkspaceUseCase.class);
        UUID personId = UUID.randomUUID();
        when(createWorkspace.execute(any())).thenReturn(new ProvisioningReport(UUID.randomUUID(),
                List.of(new ProvisioningStepResult(ProvisionedResourceType.DASHBOARD, ProvisioningOutcome.FAILED, "boom"))));

        contextRunner(terminal, createWorkspace).run(context -> {
            Shell shell = context.getBean(Shell.class);

            assertThatThrownBy(() -> run(shell, "workspace", "create", "--name", "Personal", "--person-id", personId.toString()))
                    .isInstanceOf(CommandFailedException.class)
                    .hasMessage("1 of 1 provisioning steps failed: Dashboard");

            verify(createWorkspace).execute(any());
            assertThat(captured(out, terminal)).contains("Dashboard: FAILED (boom)");
        });
    }
}
