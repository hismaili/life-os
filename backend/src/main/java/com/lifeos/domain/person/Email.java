package com.lifeos.domain.person;

import java.util.regex.Pattern;

public record Email(String value) {

    private static final Pattern PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    public Email {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Email must not be null or blank");
        }
        value = value.trim();
        if (!PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("Email is not a valid address");
        }
    }

    public static Email of(String value) {
        return new Email(value);
    }
}
