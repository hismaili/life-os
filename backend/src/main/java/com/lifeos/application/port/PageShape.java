package com.lifeos.application.port;

public record PageShape(String title, ParentConstraint parent) {
    public PageShape {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be null or blank");
        }
        if (parent == null) {
            throw new IllegalArgumentException("parent must not be null");
        }
    }
}
