package com.lifeos.application.usecase.knowledge;

import com.lifeos.application.dto.workspace.ProvisioningStepResult;

import java.util.UUID;

public interface CreateKnowledgeDatabaseUseCase {
    ProvisioningStepResult execute(UUID workspaceId);
}
