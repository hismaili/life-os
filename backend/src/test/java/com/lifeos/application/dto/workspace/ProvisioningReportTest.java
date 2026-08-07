package com.lifeos.application.dto.workspace;

import com.lifeos.domain.workspace.ProvisionedResourceType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ProvisioningReportTest {

    private final UUID workspaceId = UUID.randomUUID();

    @Test
    void failed_trueWhenAnyStepFailed() {
        ProvisioningReport report = new ProvisioningReport(workspaceId, List.of(
                new ProvisioningStepResult(ProvisionedResourceType.DASHBOARD, ProvisioningOutcome.CREATED, null),
                new ProvisioningStepResult(ProvisionedResourceType.TASKS_DB, ProvisioningOutcome.FAILED, "boom")));

        assertThat(report.failed()).isTrue();
    }

    @Test
    void failed_trueWhenAnyStepBlocked() {
        ProvisioningReport report = new ProvisioningReport(workspaceId, List.of(
                new ProvisioningStepResult(ProvisionedResourceType.TASKS_DB, ProvisioningOutcome.BLOCKED, "blocked")));

        assertThat(report.failed()).isTrue();
    }

    @Test
    void failed_falseWhenAllStepsSucceed() {
        ProvisioningReport report = new ProvisioningReport(workspaceId, List.of(
                new ProvisioningStepResult(ProvisionedResourceType.DASHBOARD, ProvisioningOutcome.CREATED, null),
                new ProvisioningStepResult(ProvisionedResourceType.TASKS_DB, ProvisioningOutcome.RECONCILED, null),
                new ProvisioningStepResult(ProvisionedResourceType.PROJECTS_DB, ProvisioningOutcome.REPAIRED, null)));

        assertThat(report.failed()).isFalse();
    }

    @Test
    void failed_falseForEmptySteps() {
        ProvisioningReport report = new ProvisioningReport(workspaceId, List.of());

        assertThat(report.failed()).isFalse();
    }
}
