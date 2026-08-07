package com.lifeos.application.usecase.workspace;

import com.lifeos.application.dto.workspace.ProvisioningStepResult;
import com.lifeos.application.port.NotionProvisioningPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CreateRelationsService implements CreateRelationsUseCase {

    private final NotionProvisioningPort notion;
    private final WorkspaceLedgerWriter ledger;

    @Override
    public ProvisioningStepResult execute(UUID workspaceId) {
        throw new UnsupportedOperationException(
                "Relations creation not yet implemented: requires the Notion adapter");
    }
}
