package com.lifeos.application.port;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotionCredentialsTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "  "})
    void constructor_rejectsBlankToken(String token) {
        assertThatThrownBy(() -> new NotionCredentials(token, "root-id"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "  "})
    void constructor_rejectsBlankRootParentPageId(String rootParentPageId) {
        assertThatThrownBy(() -> new NotionCredentials("secret-token", rootParentPageId))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_acceptsValidInputs() {
        NotionCredentials credentials = new NotionCredentials("secret-token", "root-id");

        assertThat(credentials.token()).isEqualTo("secret-token");
        assertThat(credentials.rootParentPageId()).isEqualTo("root-id");
    }

    @Test
    void toString_redactsTokenToFixedMarker() {
        String rendering = new NotionCredentials("secret-token", "root-id").toString();

        assertThat(rendering).contains("****");
        assertThat(rendering).doesNotContain("secret-token");
    }

    @Test
    void toString_stillRendersRootParentPageId() {
        String rendering = new NotionCredentials("secret-token", "root-id").toString();

        assertThat(rendering).contains("root-id");
    }
}
