package com.lifeos.application.dto.workspace;

import java.util.UUID;

public record CreateWorkspaceCommand(String name, UUID personId, boolean sampleData) {
    public CreateWorkspaceCommand {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Workspace name must not be null or blank");
        }
        if (personId == null) {
            throw new IllegalArgumentException("Workspace personId must not be null");
        }
    }
}
