package com.lifeos.application.usecase.person;

import com.lifeos.application.dto.workspace.ProvisioningStepResult;

import java.util.UUID;

public interface CreatePeopleDatabaseUseCase {
    ProvisioningStepResult execute(UUID workspaceId);
}
