package com.lifeos.application.usecase.workspace;

import com.lifeos.application.dto.workspace.ProvisioningStepResult;

import java.util.UUID;

public interface CreateRelationsUseCase {
    ProvisioningStepResult execute(UUID workspaceId);
}
