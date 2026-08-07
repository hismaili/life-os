package com.lifeos.application.usecase.resource;

import com.lifeos.application.dto.workspace.ProvisioningStepResult;

import java.util.UUID;

public interface CreateResourcesDatabaseUseCase {
    ProvisioningStepResult execute(UUID workspaceId);
}
