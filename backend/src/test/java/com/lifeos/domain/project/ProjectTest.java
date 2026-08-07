package com.lifeos.domain.project;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectTest {

    @Test
    void create_defaultsStatusToPlannedWhenNull() {
        Project project = Project.create("P", "d", null, null, null, UUID.randomUUID(), null);

        assertThat(project.getStatus()).isEqualTo(ProjectStatus.PLANNED);
    }

    @Test
    void create_keepsProvidedStatus() {
        Project project = Project.create("P", "d", ProjectStatus.ACTIVE, null, null, UUID.randomUUID(), null);

        assertThat(project.getStatus()).isEqualTo(ProjectStatus.ACTIVE);
    }

    @Test
    void create_allowsNullDueDate() {
        Project project = Project.create("P", "d", null, null, null, UUID.randomUUID(), null);

        assertThat(project.getDueDate()).isNull();
    }

    @Test
    void create_keepsProvidedDueDate() {
        LocalDate dueDate = LocalDate.of(2026, 12, 31);
        Project project = Project.create("P", "d", null, dueDate, null, UUID.randomUUID(), null);

        assertThat(project.getDueDate()).isEqualTo(dueDate);
    }

    @Test
    void create_rejectsBlankName() {
        UUID workspaceId = UUID.randomUUID();

        assertThatThrownBy(() -> Project.create(null, "d", null, null, null, workspaceId, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Project name must not be null or blank");
        assertThatThrownBy(() -> Project.create("", "d", null, null, null, workspaceId, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Project name must not be null or blank");
        assertThatThrownBy(() -> Project.create("  ", "d", null, null, null, workspaceId, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Project name must not be null or blank");
    }

    @Test
    void create_rejectsNullWorkspaceId() {
        assertThatThrownBy(() -> Project.create("P", "d", null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Project workspaceId must not be null");
    }

    @Test
    void create_generatesNonNullUniqueId() {
        UUID workspaceId = UUID.randomUUID();

        Project first = Project.create("P", "d", null, null, null, workspaceId, null);
        Project second = Project.create("P", "d", null, null, null, workspaceId, null);

        assertThat(first.getId()).isNotNull();
        assertThat(second.getId()).isNotNull();
        assertThat(first.getId()).isNotEqualTo(second.getId());
    }

    @Test
    void create_isImmutable() {
        assertThat(Project.class.getMethods())
                .noneMatch(method -> method.getName().startsWith("set"));
    }
}
