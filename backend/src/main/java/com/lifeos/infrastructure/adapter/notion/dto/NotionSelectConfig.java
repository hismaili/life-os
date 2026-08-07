package com.lifeos.infrastructure.adapter.notion.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record NotionSelectConfig(List<NotionSelectOption> options) {}
