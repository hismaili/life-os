package com.lifeos.infrastructure.adapter.notion.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record NotionPropertyConfig(String type, NotionSelectConfig select) {}
