package com.lifeos.application.port;

public record NotionCredentials(String token, String rootParentPageId) {

    private static final String REDACTED = "****";

    public NotionCredentials {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Notion token must not be null or blank");
        }
        if (rootParentPageId == null || rootParentPageId.isBlank()) {
            throw new IllegalArgumentException("Notion rootParentPageId must not be null or blank");
        }
    }

    @Override
    public String toString() {
        return "NotionCredentials[token=" + REDACTED + ", rootParentPageId=" + rootParentPageId + "]";
    }
}
