package com.lifeos.infrastructure.adapter.notion.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record NotionPageResponse(
        String id,
        boolean archived,
        @JsonProperty("in_trash") boolean inTrash,
        NotionParent parent,
        Map<String, NotionTitleProperty> properties
) {}
