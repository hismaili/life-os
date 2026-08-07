package com.lifeos.domain.resource;

import java.util.regex.Pattern;

public record Url(String value) {

    private static final Pattern PATTERN = Pattern.compile("^https?://[^\\s]+\\.[^\\s]+$");

    public Url {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("URL must not be null or blank");
        }
        value = value.trim();
        if (!PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("URL is not a valid address");
        }
    }

    public static Url of(String value) {
        return new Url(value);
    }
}
