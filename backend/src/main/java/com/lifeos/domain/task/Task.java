package com.lifeos.domain.task;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Value;

import java.time.LocalDate;
import java.util.UUID;

@Value
@Builder(access = AccessLevel.PRIVATE)
public class Task {
    UUID id;
    String title;
    String description;
    TaskStatus status;
    LocalDate dueDate;
    UUID projectId;
    UUID workspaceId;

    public static Task create(String title,
                              String description,
                              TaskStatus status,
                              LocalDate dueDate,
                              UUID projectId,
                              UUID workspaceId) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Task title must not be null or blank");
        }
        if (status == null) {
            throw new IllegalArgumentException("Task status must not be null");
        }
        if (workspaceId == null) {
            throw new IllegalArgumentException("Task workspaceId must not be null");
        }
        return Task.builder()
                .id(UUID.randomUUID())
                .title(title)
                .description(description)
                .status(status)
                .dueDate(dueDate)
                .projectId(projectId)
                .workspaceId(workspaceId)
                .build();
    }
}
