package com.lifeos.domain.person;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmailTest {

    @Test
    void of_acceptsValidAddress() {
        assertThat(Email.of("user@example.com").value()).isEqualTo("user@example.com");
    }

    @Test
    void of_trimsSurroundingWhitespace() {
        assertThat(Email.of("  user@example.com  ").value()).isEqualTo("user@example.com");
    }

    @Test
    void of_rejectsNull() {
        assertThatThrownBy(() -> Email.of(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void of_rejectsBlank() {
        assertThatThrownBy(() -> Email.of("   ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void of_rejectsMalformedAddress() {
        assertThatThrownBy(() -> Email.of("not-an-email")).isInstanceOf(IllegalArgumentException.class);
    }
}
