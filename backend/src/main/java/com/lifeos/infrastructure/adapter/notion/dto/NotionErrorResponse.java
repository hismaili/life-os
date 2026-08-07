package com.lifeos.infrastructure.adapter.notion.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record NotionErrorResponse(String object, int status, String code, String message) {}
