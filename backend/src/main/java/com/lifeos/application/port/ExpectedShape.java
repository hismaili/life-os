package com.lifeos.application.port;

import java.util.List;

public record ExpectedShape(String title, List<PropertyDefinition> requiredProperties) {

    public ExpectedShape {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be null or blank");
        }
        if (requiredProperties == null || requiredProperties.isEmpty()) {
            throw new IllegalArgumentException("requiredProperties must not be null or empty");
        }
        requiredProperties = List.copyOf(requiredProperties);
    }
}
