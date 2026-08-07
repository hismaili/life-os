package com.lifeos.infrastructure.adapter.notion;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "notion")
@Validated
public record NotionProperties(@NotBlank String version) {
}
