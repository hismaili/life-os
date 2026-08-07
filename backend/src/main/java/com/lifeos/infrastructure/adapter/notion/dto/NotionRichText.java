package com.lifeos.infrastructure.adapter.notion.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record NotionRichText(@JsonProperty("plain_text") String plainText) {}
