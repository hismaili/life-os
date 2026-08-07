package com.lifeos.domain.project;

public enum ProjectStatus {
    PLANNED("Planned"),
    ACTIVE("Active"),
    ON_HOLD("On hold"),
    DONE("Done");

    private final String displayName;

    ProjectStatus(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
