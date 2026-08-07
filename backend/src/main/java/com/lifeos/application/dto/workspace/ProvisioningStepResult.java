package com.lifeos.application.dto.workspace;

import com.lifeos.domain.workspace.ProvisionedResourceType;

public record ProvisioningStepResult(ProvisionedResourceType type, ProvisioningOutcome outcome, String detail) {
    public ProvisioningStepResult {
        if (type == null) throw new IllegalArgumentException("type must not be null");
        if (outcome == null) throw new IllegalArgumentException("outcome must not be null");
        if ((outcome == ProvisioningOutcome.FAILED || outcome == ProvisioningOutcome.BLOCKED)
                && (detail == null || detail.isBlank())) {
            throw new IllegalArgumentException("detail is required when outcome is FAILED or BLOCKED");
        }
    }
}
