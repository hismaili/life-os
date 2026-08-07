package com.lifeos.domain.goal;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Value;

import java.util.UUID;

@Value
@Builder(access = AccessLevel.PRIVATE)
public class Goal {
    UUID id;
    String title;
    String description;
    UUID areaId;
    UUID workspaceId;

    public static Goal create(String title,
                              String description,
                              UUID areaId,
                              UUID workspaceId) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Goal title must not be null or blank");
        }
        if (workspaceId == null) {
            throw new IllegalArgumentException("Goal workspaceId must not be null");
        }
        return Goal.builder()
                .id(UUID.randomUUID())
                .title(title)
                .description(description)
                .areaId(areaId)
                .workspaceId(workspaceId)
                .build();
    }
}
