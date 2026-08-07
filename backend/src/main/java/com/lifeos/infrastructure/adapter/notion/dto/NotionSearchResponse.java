package com.lifeos.infrastructure.adapter.notion.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record NotionSearchResponse(
        List<NotionPageResponse> results,
        @JsonProperty("has_more") boolean hasMore,
        @JsonProperty("next_cursor") String nextCursor
) {}
