package com.lifeos.domain.workspace;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProvisionedResourceTest {

    @Test
    void constructor_rejectsNullType() {
        assertThatThrownBy(() -> new ProvisionedResource(null, "notion-1", Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_rejectsBlankNotionId() {
        assertThatThrownBy(() -> new ProvisionedResource(ProvisionedResourceType.DASHBOARD, "", Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProvisionedResource(ProvisionedResourceType.DASHBOARD, null, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_rejectsNullProvisionedAt() {
        assertThatThrownBy(() -> new ProvisionedResource(ProvisionedResourceType.DASHBOARD, "notion-1", null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
