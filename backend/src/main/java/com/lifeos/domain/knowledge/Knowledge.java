package com.lifeos.domain.knowledge;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Value;

import java.util.UUID;

@Value
@Builder(access = AccessLevel.PRIVATE)
public class Knowledge {
    UUID id;
    String title;
    String content;
    UUID workspaceId;
    UUID areaId;

    public static Knowledge create(String title,
                                   String content,
                                   UUID workspaceId,
                                   UUID areaId) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Knowledge title must not be null or blank");
        }
        if (workspaceId == null) {
            throw new IllegalArgumentException("Knowledge workspaceId must not be null");
        }
        return Knowledge.builder()
                .id(UUID.randomUUID())
                .title(title)
                .content(content)
                .workspaceId(workspaceId)
                .areaId(areaId)
                .build();
    }
}
