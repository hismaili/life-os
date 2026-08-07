package com.lifeos.domain.project;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectStatusTest {

    @Test
    void values_hasExactlyFourInDeclarationOrder() {
        assertThat(ProjectStatus.values())
                .containsExactly(ProjectStatus.PLANNED, ProjectStatus.ACTIVE, ProjectStatus.ON_HOLD, ProjectStatus.DONE);
    }

    @Test
    void displayName_returnsExpectedLabelsForEachValue() {
        assertThat(ProjectStatus.PLANNED.displayName()).isEqualTo("Planned");
        assertThat(ProjectStatus.ACTIVE.displayName()).isEqualTo("Active");
        assertThat(ProjectStatus.ON_HOLD.displayName()).isEqualTo("On hold");
        assertThat(ProjectStatus.DONE.displayName()).isEqualTo("Done");
    }
}
