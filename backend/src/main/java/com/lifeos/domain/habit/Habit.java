package com.lifeos.domain.habit;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Value;

import java.util.UUID;

@Value
@Builder(access = AccessLevel.PRIVATE)
public class Habit {
    UUID id;
    String name;
    Frequency frequency;
    UUID workspaceId;
    UUID areaId;

    public static Habit create(String name,
                               Frequency frequency,
                               UUID workspaceId,
                               UUID areaId) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Habit name must not be null or blank");
        }
        if (frequency == null) {
            throw new IllegalArgumentException("Habit frequency must not be null");
        }
        if (workspaceId == null) {
            throw new IllegalArgumentException("Habit workspaceId must not be null");
        }
        return Habit.builder()
                .id(UUID.randomUUID())
                .name(name)
                .frequency(frequency)
                .workspaceId(workspaceId)
                .areaId(areaId)
                .build();
    }
}
