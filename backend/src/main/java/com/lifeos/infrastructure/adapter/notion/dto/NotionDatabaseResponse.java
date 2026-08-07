package com.lifeos.infrastructure.adapter.notion.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record NotionDatabaseResponse(
        String id,
        List<NotionRichText> title,
        boolean archived,
        @JsonProperty("in_trash") boolean inTrash,
        @JsonProperty("data_sources") List<NotionDataSourceSummary> dataSources
) {}
