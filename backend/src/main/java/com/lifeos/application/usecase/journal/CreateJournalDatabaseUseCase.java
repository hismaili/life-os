package com.lifeos.application.usecase.journal;

import com.lifeos.application.dto.workspace.ProvisioningStepResult;

import java.util.UUID;

public interface CreateJournalDatabaseUseCase {
    ProvisioningStepResult execute(UUID workspaceId);
}
