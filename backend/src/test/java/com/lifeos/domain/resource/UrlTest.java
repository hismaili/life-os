package com.lifeos.domain.resource;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UrlTest {

    @Test
    void of_acceptsValidHttpsUrl() {
        assertThat(Url.of("https://example.com").value()).isEqualTo("https://example.com");
    }

    @Test
    void of_acceptsValidHttpUrl() {
        assertThat(Url.of("http://example.com/path").value()).isEqualTo("http://example.com/path");
    }

    @Test
    void of_trimsSurroundingWhitespace() {
        assertThat(Url.of("  https://example.com  ").value()).isEqualTo("https://example.com");
    }

    @Test
    void of_rejectsNull() {
        assertThatThrownBy(() -> Url.of(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void of_rejectsBlank() {
        assertThatThrownBy(() -> Url.of("   ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void of_rejectsMalformedUrl() {
        assertThatThrownBy(() -> Url.of("not a url")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void of_rejectsUrlWithoutScheme() {
        assertThatThrownBy(() -> Url.of("example.com")).isInstanceOf(IllegalArgumentException.class);
    }
}
