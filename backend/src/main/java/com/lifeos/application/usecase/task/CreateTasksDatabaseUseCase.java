package com.lifeos.application.usecase.task;

import com.lifeos.application.dto.workspace.ProvisioningStepResult;

import java.util.UUID;

public interface CreateTasksDatabaseUseCase {
    ProvisioningStepResult execute(UUID workspaceId);
}
