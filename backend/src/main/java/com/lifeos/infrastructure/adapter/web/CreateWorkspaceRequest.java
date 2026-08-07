package com.lifeos.infrastructure.adapter.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CreateWorkspaceRequest(
        @NotBlank String name,
        @NotNull UUID personId,
        boolean sampleData,
        @NotBlank String notionToken,
        @NotBlank String notionRootParentPageId
) {

    private static final String REDACTED = "****";

    @Override
    public String toString() {
        return "CreateWorkspaceRequest[name=" + name + ", personId=" + personId
                + ", sampleData=" + sampleData + ", notionToken=" + REDACTED
                + ", notionRootParentPageId=" + notionRootParentPageId + "]";
    }
}
