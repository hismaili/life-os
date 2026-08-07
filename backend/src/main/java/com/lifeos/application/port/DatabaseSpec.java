package com.lifeos.application.port;

import java.util.List;

public record DatabaseSpec(String title, List<PropertyDefinition> properties) {

    public DatabaseSpec {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be null or blank");
        }
        if (properties == null || properties.isEmpty()) {
            throw new IllegalArgumentException("properties must not be null or empty");
        }
        properties = List.copyOf(properties);
        long titleCount = properties.stream().filter(p -> p.type() == NotionPropertyType.TITLE).count();
        if (titleCount != 1) {
            throw new IllegalArgumentException("exactly one TITLE property is required, found " + titleCount);
        }
    }
}
