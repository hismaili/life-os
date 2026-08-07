package com.lifeos.application.dto.workspace;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CreateWorkspaceCommandTest {

    @Test
    void constructor_rejectsBlankName() {
        UUID personId = UUID.randomUUID();

        assertThatThrownBy(() -> new CreateWorkspaceCommand("", personId, false, "token", "root-id"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CreateWorkspaceCommand(null, personId, false, "token", "root-id"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_rejectsNullPersonId() {
        assertThatThrownBy(() -> new CreateWorkspaceCommand("Personal", null, false, "token", "root-id"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_rejectsBlankNotionToken() {
        UUID personId = UUID.randomUUID();

        assertThatThrownBy(() -> new CreateWorkspaceCommand("Personal", personId, false, "", "root-id"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CreateWorkspaceCommand("Personal", personId, false, null, "root-id"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_rejectsBlankNotionRootParentPageId() {
        UUID personId = UUID.randomUUID();

        assertThatThrownBy(() -> new CreateWorkspaceCommand("Personal", personId, false, "token", ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CreateWorkspaceCommand("Personal", personId, false, "token", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_acceptsValidInputsWithSampleDataFlag() {
        UUID personId = UUID.randomUUID();

        CreateWorkspaceCommand command = new CreateWorkspaceCommand("Personal", personId, true, "token", "root-id");

        assertThat(command.name()).isEqualTo("Personal");
        assertThat(command.personId()).isEqualTo(personId);
        assertThat(command.sampleData()).isTrue();
        assertThat(command.notionToken()).isEqualTo("token");
        assertThat(command.notionRootParentPageId()).isEqualTo("root-id");
    }

    @Test
    void toString_redactsNotionToken() {
        CreateWorkspaceCommand command = new CreateWorkspaceCommand("Personal", UUID.randomUUID(), false, "secret-token", "root-id");

        String rendering = command.toString();

        assertThat(rendering).contains("****");
        assertThat(rendering).doesNotContain("secret-token");
    }
}
