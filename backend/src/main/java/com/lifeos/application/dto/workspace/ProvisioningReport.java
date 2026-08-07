package com.lifeos.application.dto.workspace;

import java.util.List;
import java.util.UUID;

public record ProvisioningReport(UUID workspaceId, List<ProvisioningStepResult> steps) {
    public ProvisioningReport {
        if (workspaceId == null) throw new IllegalArgumentException("workspaceId must not be null");
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    public boolean failed() {
        return steps.stream().anyMatch(s -> s.outcome() == ProvisioningOutcome.FAILED
                || s.outcome() == ProvisioningOutcome.BLOCKED);
    }
}
