package com.lifeos.domain.workspace;

import java.time.Instant;

public record ProvisionedResource(
        ProvisionedResourceType type,
        String notionId,
        Instant provisionedAt
) {
    public ProvisionedResource {
        if (type == null) throw new IllegalArgumentException("type must not be null");
        if (notionId == null || notionId.isBlank()) throw new IllegalArgumentException("notionId must not be null or blank");
        if (provisionedAt == null) throw new IllegalArgumentException("provisionedAt must not be null");
    }
}
