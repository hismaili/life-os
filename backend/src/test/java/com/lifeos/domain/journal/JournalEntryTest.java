package com.lifeos.domain.journal;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class JournalEntryTest {

    @Test
    void create_generatesId() {
        JournalEntry entry = JournalEntry.create("My headline", "body text", null, UUID.randomUUID(), null);

        assertThat(entry.getId()).isNotNull();
    }

    @Test
    void create_defaultsTimestampToNowWhenNull() {
        JournalEntry entry = JournalEntry.create(null, "content", null, UUID.randomUUID(), null);

        assertThat(entry.getTimestamp()).isNotNull();
        assertThat(entry.getTimestamp()).isCloseTo(LocalDateTime.now(), within(2, ChronoUnit.SECONDS));
    }

    @Test
    void create_keepsProvidedTimestamp() {
        LocalDateTime timestamp = LocalDateTime.of(2026, 1, 1, 9, 30);

        JournalEntry entry = JournalEntry.create("title", "content", timestamp, UUID.randomUUID(), null);

        assertThat(entry.getTimestamp()).isEqualTo(timestamp);
    }

    @Test
    void create_allowsNullTitle() {
        JournalEntry entry = JournalEntry.create(null, "content", null, UUID.randomUUID(), null);

        assertThat(entry.getTitle()).isNull();
    }

    @Test
    void create_keepsProvidedTitle() {
        JournalEntry entry = JournalEntry.create("My headline", "content", null, UUID.randomUUID(), null);

        assertThat(entry.getTitle()).isEqualTo("My headline");
    }

    @Test
    void create_rejectsBlankContent() {
        UUID workspaceId = UUID.randomUUID();

        assertThatThrownBy(() -> JournalEntry.create("title", "", null, workspaceId, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("JournalEntry content must not be null or blank");
        assertThatThrownBy(() -> JournalEntry.create("title", null, null, workspaceId, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("JournalEntry content must not be null or blank");
    }

    @Test
    void create_rejectsNullWorkspaceId() {
        assertThatThrownBy(() -> JournalEntry.create("title", "content", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("JournalEntry workspaceId must not be null");
    }

    @Test
    void create_isImmutable() {
        assertThat(JournalEntry.class.getMethods())
                .noneMatch(method -> method.getName().startsWith("set"));
    }
}
