package com.lifeos.infrastructure.adapter.web;

import com.lifeos.application.dto.workspace.ProvisioningReport;
import lombok.Getter;

@Getter
class WorkspaceProvisioningFailedException extends RuntimeException {

    private final ProvisioningReport report;

    WorkspaceProvisioningFailedException(ProvisioningReport report) {
        super("One or more provisioning steps failed or were blocked");
        this.report = report;
    }
}
