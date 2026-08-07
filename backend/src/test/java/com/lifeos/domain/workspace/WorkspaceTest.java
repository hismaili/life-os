package com.lifeos.domain.workspace;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkspaceTest {

    private final UUID personId = UUID.randomUUID();

    @Test
    void create_rejectsBlankName() {
        assertThatThrownBy(() -> Workspace.create("", personId))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Workspace.create(null, personId))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Workspace.create("   ", personId))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void create_rejectsNullPersonId() {
        assertThatThrownBy(() -> Workspace.create("Personal", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void create_mintsIdAndEmptyLedger() {
        Workspace workspace = Workspace.create("Personal", personId);

        assertThat(workspace.getId()).isNotNull();
        assertThat(workspace.getName()).isEqualTo("Personal");
        assertThat(workspace.getPersonId()).isEqualTo(personId);
        assertThat(workspace.getResources()).isEmpty();
    }

    @Test
    void record_appendsNewResourceType() {
        Workspace workspace = Workspace.create("Personal", personId);

        Workspace updated = workspace.record(ProvisionedResourceType.DASHBOARD, "notion-1");

        assertThat(updated).isNotSameAs(workspace);
        assertThat(updated.resource(ProvisionedResourceType.DASHBOARD))
                .isPresent()
                .get()
                .extracting(ProvisionedResource::notionId)
                .isEqualTo("notion-1");
    }

    @Test
    void record_replacesExistingResourceOfSameType() {
        Workspace workspace = Workspace.create("Personal", personId)
                .record(ProvisionedResourceType.TASKS_DB, "first-id")
                .record(ProvisionedResourceType.TASKS_DB, "second-id");

        assertThat(workspace.getResources())
                .filteredOn(r -> r.type() == ProvisionedResourceType.TASKS_DB)
                .hasSize(1)
                .extracting(ProvisionedResource::notionId)
                .containsExactly("second-id");
    }

    @Test
    void record_doesNotMutateOriginalInstance() {
        Workspace original = Workspace.create("Personal", personId);

        original.record(ProvisionedResourceType.DASHBOARD, "notion-1");

        assertThat(original.getResources()).isEmpty();
    }

    @Test
    void resource_returnsEmptyForUnrecordedType() {
        Workspace workspace = Workspace.create("Personal", personId);

        assertThat(workspace.resource(ProvisionedResourceType.PROJECTS_DB)).isEmpty();
    }

    @Test
    void reconstitute_rebuildsAlreadyValidStateWithLedger() {
        UUID id = UUID.randomUUID();
        List<ProvisionedResource> ledger = List.of(
                new ProvisionedResource(ProvisionedResourceType.DASHBOARD, "dash-1", java.time.Instant.now()));

        Workspace workspace = Workspace.reconstitute(id, personId, "Personal", ledger);

        assertThat(workspace.getId()).isEqualTo(id);
        assertThat(workspace.getPersonId()).isEqualTo(personId);
        assertThat(workspace.getName()).isEqualTo("Personal");
        assertThat(workspace.resource(ProvisionedResourceType.DASHBOARD)).isPresent();
    }

    @Test
    void reconstitute_rejectsInvalidState() {
        assertThatThrownBy(() -> Workspace.reconstitute(null, personId, "Personal", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Workspace.reconstitute(UUID.randomUUID(), null, "Personal", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Workspace.reconstitute(UUID.randomUUID(), personId, " ", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void builder_isNotPubliclyAccessible() {
        Method builder = null;
        for (Method m : Workspace.class.getDeclaredMethods()) {
            if (m.getName().equals("builder")) {
                builder = m;
            }
        }
        assertThat(builder).isNotNull();
        assertThat(Modifier.isPublic(builder.getModifiers())).isFalse();
    }
}
