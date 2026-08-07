package com.lifeos.application.port;

import java.util.List;

public record PropertyDefinition(String name, NotionPropertyType type, List<String> options) {

    public PropertyDefinition {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("property name must not be null or blank");
        }
        if (type == null) {
            throw new IllegalArgumentException("property type must not be null");
        }
        options = options == null ? List.of() : List.copyOf(options);
        if (type != NotionPropertyType.SELECT && !options.isEmpty()) {
            throw new IllegalArgumentException("options are only valid for SELECT properties");
        }
    }

    public static PropertyDefinition of(String name, NotionPropertyType type) {
        return new PropertyDefinition(name, type, List.of());
    }
}
