package com.lifeos.infrastructure.adapter.notion.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record NotionDataSourceSummary(String id, String name) {}
