package com.lifeos.application.usecase.workspace;

import com.lifeos.application.dto.workspace.CreateWorkspaceCommand;
import com.lifeos.application.dto.workspace.ProvisioningReport;

public interface CreateWorkspaceUseCase {
    ProvisioningReport execute(CreateWorkspaceCommand command);
}
