package com.lifeos.application.usecase.habit;

import com.lifeos.application.dto.workspace.ProvisioningStepResult;

import java.util.UUID;

public interface CreateHabitsDatabaseUseCase {
    ProvisioningStepResult execute(UUID workspaceId);
}
