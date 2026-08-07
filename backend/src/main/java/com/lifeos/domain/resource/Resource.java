package com.lifeos.domain.resource;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Value;

import java.util.UUID;

@Value
@Builder(access = AccessLevel.PRIVATE)
public class Resource {
    UUID id;
    String title;
    Url url;
    UUID workspaceId;
    UUID knowledgeId;

    public static Resource create(String title,
                                  String url,
                                  UUID workspaceId,
                                  UUID knowledgeId) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Resource title must not be null or blank");
        }
        if (workspaceId == null) {
            throw new IllegalArgumentException("Resource workspaceId must not be null");
        }
        return Resource.builder()
                .id(UUID.randomUUID())
                .title(title)
                .url(url == null ? null : Url.of(url))
                .workspaceId(workspaceId)
                .knowledgeId(knowledgeId)
                .build();
    }
}
