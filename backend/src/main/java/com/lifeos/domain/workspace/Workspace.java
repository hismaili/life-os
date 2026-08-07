package com.lifeos.domain.workspace;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Value
@Builder(toBuilder = true, access = AccessLevel.PRIVATE)
public class Workspace {
    UUID id;
    UUID personId;
    String name;
    @Builder.Default
    List<ProvisionedResource> resources = List.of();

    public static Workspace create(String name, UUID personId) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Workspace name must not be null or blank");
        }
        if (personId == null) {
            throw new IllegalArgumentException("Workspace personId must not be null");
        }
        return Workspace.builder()
                .id(UUID.randomUUID())
                .personId(personId)
                .name(name)
                .resources(List.of())
                .build();
    }

    public static Workspace reconstitute(UUID id, UUID personId, String name, List<ProvisionedResource> resources) {
        if (id == null) {
            throw new IllegalArgumentException("Workspace id must not be null");
        }
        if (personId == null) {
            throw new IllegalArgumentException("Workspace personId must not be null");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Workspace name must not be null or blank");
        }
        return Workspace.builder()
                .id(id)
                .personId(personId)
                .name(name)
                .resources(resources == null ? List.of() : List.copyOf(resources))
                .build();
    }

    public Optional<ProvisionedResource> resource(ProvisionedResourceType type) {
        return resources.stream().filter(r -> r.type() == type).findFirst();
    }

    public Workspace record(ProvisionedResourceType type, String notionId) {
        if (type == null) throw new IllegalArgumentException("type must not be null");
        if (notionId == null || notionId.isBlank()) throw new IllegalArgumentException("notionId must not be null or blank");
        List<ProvisionedResource> next = resources.stream()
                .filter(r -> r.type() != type)
                .collect(Collectors.toCollection(ArrayList::new));
        next.add(new ProvisionedResource(type, notionId, Instant.now()));
        return this.toBuilder().resources(List.copyOf(next)).build();
    }
}
