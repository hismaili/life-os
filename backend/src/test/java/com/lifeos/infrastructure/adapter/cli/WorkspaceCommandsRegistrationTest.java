package com.lifeos.infrastructure.adapter.cli;

import com.lifeos.application.usecase.workspace.CreateWorkspaceUseCase;
import org.jline.terminal.Terminal;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.shell.command.annotation.CommandScan;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the CLI wiring: the {@code workspace create} command is only reachable at runtime if the
 * composition root scans for {@link org.springframework.shell.command.annotation.Command @Command}
 * classes. {@link WorkspaceCommands} is intentionally not a {@code @Component}, so without
 * {@code @CommandScan} it is never registered and the command silently disappears from the app.
 */
class WorkspaceCommandsRegistrationTest {

    @Configuration
    @CommandScan(basePackageClasses = WorkspaceCommands.class)
    static class ScanConfig {
        @Bean
        CreateWorkspaceUseCase createWorkspace() {
            return Mockito.mock(CreateWorkspaceUseCase.class);
        }

        @Bean
        Terminal terminal() {
            return Mockito.mock(Terminal.class);
        }
    }

    @Test
    void commandScan_discoversAndDependencyInjectsWorkspaceCommands() {
        try (var context = new AnnotationConfigApplicationContext(ScanConfig.class)) {
            assertThat(context.getBeanNamesForType(WorkspaceCommands.class))
                    .as("@CommandScan must register the @Command class as a bean")
                    .isNotEmpty();
            assertThat(context.getBean(WorkspaceCommands.class))
                    .as("the command bean must be constructor-injected with its use case")
                    .isNotNull();
        }
    }
}
