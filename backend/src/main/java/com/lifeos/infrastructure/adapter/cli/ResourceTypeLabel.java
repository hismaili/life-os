package com.lifeos.infrastructure.adapter.cli;

import com.lifeos.domain.workspace.ProvisionedResourceType;

final class ResourceTypeLabel {

    private ResourceTypeLabel() {
    }

    static String of(ProvisionedResourceType type) {
        return switch (type) {
            case DASHBOARD -> "Dashboard";
            case PROJECTS_DB -> "Projects";
            case TASKS_DB -> "Tasks";
            case KNOWLEDGE_DB -> "Knowledge";
            case HABITS_DB -> "Habits";
            case JOURNAL_DB -> "Journal";
            case RESOURCES_DB -> "Resources";
            case PEOPLE_DB -> "People";
            case GOALS_DB -> "Goals";
            case REVIEWS_DB -> "Reviews";
            case RELATIONS -> "Relations";
            case ROLLUPS -> "Rollups";
            case FORMULAS -> "Formulas";
            case SAMPLE_DATA -> "Sample data";
        };
    }
}
