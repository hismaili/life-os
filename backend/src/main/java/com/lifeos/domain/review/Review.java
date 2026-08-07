package com.lifeos.domain.review;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Value;

import java.time.LocalDate;
import java.util.UUID;

@Value
@Builder(access = AccessLevel.PRIVATE)
public class Review {
    UUID id;
    String title;
    String content;
    LocalDate date;
    UUID workspaceId;
    UUID personId;

    public static Review create(String title,
                                String content,
                                LocalDate date,
                                UUID workspaceId,
                                UUID personId) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Review title must not be null or blank");
        }
        if (workspaceId == null) {
            throw new IllegalArgumentException("Review workspaceId must not be null");
        }
        return Review.builder()
                .id(UUID.randomUUID())
                .title(title)
                .content(content)
                .date(date != null ? date : LocalDate.now())
                .workspaceId(workspaceId)
                .personId(personId)
                .build();
    }
}
