package com.lifeos.application.usecase.project;

import com.lifeos.application.dto.workspace.ProvisioningStepResult;

import java.util.UUID;

public interface CreateProjectsDatabaseUseCase {
    ProvisioningStepResult execute(UUID workspaceId);
}
