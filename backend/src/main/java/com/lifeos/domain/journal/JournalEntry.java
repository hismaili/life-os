package com.lifeos.domain.journal;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;
import java.util.UUID;

@Value
@Builder(access = AccessLevel.PRIVATE)
public class JournalEntry {
    UUID id;
    String title;
    String content;
    LocalDateTime timestamp;
    UUID workspaceId;
    UUID personId;

    public static JournalEntry create(String title,
                                      String content,
                                      LocalDateTime timestamp,
                                      UUID workspaceId,
                                      UUID personId) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("JournalEntry content must not be null or blank");
        }
        if (workspaceId == null) {
            throw new IllegalArgumentException("JournalEntry workspaceId must not be null");
        }
        return JournalEntry.builder()
                .id(UUID.randomUUID())
                .title(title)
                .content(content)
                .timestamp(timestamp != null ? timestamp : LocalDateTime.now())
                .workspaceId(workspaceId)
                .personId(personId)
                .build();
    }
}
