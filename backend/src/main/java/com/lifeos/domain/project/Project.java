package com.lifeos.domain.project;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Value;

import java.time.LocalDate;
import java.util.UUID;

@Value
@Builder(access = AccessLevel.PRIVATE)
public class Project {
    UUID id;
    String name;
    String description;
    ProjectStatus status;
    LocalDate dueDate;
    UUID areaId;
    UUID workspaceId;
    UUID goalId;

    public static Project create(String name,
                                 String description,
                                 ProjectStatus status,
                                 LocalDate dueDate,
                                 UUID areaId,
                                 UUID workspaceId,
                                 UUID goalId) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Project name must not be null or blank");
        }
        if (workspaceId == null) {
            throw new IllegalArgumentException("Project workspaceId must not be null");
        }
        return Project.builder()
                .id(UUID.randomUUID())
                .name(name)
                .description(description)
                .status(status == null ? ProjectStatus.PLANNED : status)
                .dueDate(dueDate)
                .areaId(areaId)
                .workspaceId(workspaceId)
                .goalId(goalId)
                .build();
    }
}
