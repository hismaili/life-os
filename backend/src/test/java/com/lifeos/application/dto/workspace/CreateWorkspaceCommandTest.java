package com.lifeos.application.dto.workspace;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CreateWorkspaceCommandTest {

    @Test
    void constructor_rejectsBlankName() {
        UUID personId = UUID.randomUUID();

        assertThatThrownBy(() -> new CreateWorkspaceCommand("", personId, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CreateWorkspaceCommand(null, personId, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_rejectsNullPersonId() {
        assertThatThrownBy(() -> new CreateWorkspaceCommand("Personal", null, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_acceptsValidInputsWithSampleDataFlag() {
        UUID personId = UUID.randomUUID();

        CreateWorkspaceCommand command = new CreateWorkspaceCommand("Personal", personId, true);

        assertThat(command.name()).isEqualTo("Personal");
        assertThat(command.personId()).isEqualTo(personId);
        assertThat(command.sampleData()).isTrue();
    }
}
