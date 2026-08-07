package com.lifeos.application.dto.workspace;

import java.util.UUID;

public record CreateWorkspaceCommand(String name, UUID personId, boolean sampleData,
                                      String notionToken, String notionRootParentPageId) {

    private static final String REDACTED = "****";

    public CreateWorkspaceCommand {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Workspace name must not be null or blank");
        }
        if (personId == null) {
            throw new IllegalArgumentException("Workspace personId must not be null");
        }
        if (notionToken == null || notionToken.isBlank()) {
            throw new IllegalArgumentException("Notion token must not be null or blank");
        }
        if (notionRootParentPageId == null || notionRootParentPageId.isBlank()) {
            throw new IllegalArgumentException("Notion rootParentPageId must not be null or blank");
        }
    }

    @Override
    public String toString() {
        return "CreateWorkspaceCommand[name=" + name + ", personId=" + personId
                + ", sampleData=" + sampleData + ", notionToken=" + REDACTED
                + ", notionRootParentPageId=" + notionRootParentPageId + "]";
    }
}
