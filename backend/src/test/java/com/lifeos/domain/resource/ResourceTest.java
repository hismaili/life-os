package com.lifeos.domain.resource;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResourceTest {

    @Test
    void create_withValidUrl_yieldsUrlTypedField() {
        UUID workspaceId = UUID.randomUUID();
        Resource resource = Resource.create("Some Title", "https://example.com", workspaceId, null);

        assertThat(resource.getUrl()).isEqualTo(Url.of("https://example.com"));
    }

    @Test
    void create_withNullUrl_yieldsNullUrl() {
        UUID workspaceId = UUID.randomUUID();
        Resource resource = Resource.create("Some Title", null, workspaceId, null);

        assertThat(resource.getUrl()).isNull();
    }

    @Test
    void create_withMalformedUrl_throws() {
        UUID workspaceId = UUID.randomUUID();

        assertThatThrownBy(() -> Resource.create("Some Title", "not a url", workspaceId, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void create_rejectsBlankTitle() {
        UUID workspaceId = UUID.randomUUID();

        assertThatThrownBy(() -> Resource.create("   ", "https://example.com", workspaceId, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void create_rejectsNullWorkspaceId() {
        assertThatThrownBy(() -> Resource.create("Some Title", "https://example.com", null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
