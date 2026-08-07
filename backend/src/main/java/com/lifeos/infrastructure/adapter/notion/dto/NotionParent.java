package com.lifeos.infrastructure.adapter.notion.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record NotionParent(String type, @JsonProperty("page_id") String pageId) {}
