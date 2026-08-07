package com.lifeos.domain.area;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Value;

import java.util.UUID;

@Value
@Builder(access = AccessLevel.PRIVATE)
public class Area {
    UUID id;
    String name;
    UUID workspaceId;

    public static Area create(String name, UUID workspaceId) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Area name must not be null or blank");
        }
        if (workspaceId == null) {
            throw new IllegalArgumentException("Area workspaceId must not be null");
        }
        return Area.builder()
                .id(UUID.randomUUID())
                .name(name)
                .workspaceId(workspaceId)
                .build();
    }
}
