package com.lifeos.application.usecase.workspace;

import com.lifeos.application.dto.workspace.ProvisioningStepResult;
import com.lifeos.application.port.NotionProvisioningPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PopulateSampleDataService implements PopulateSampleDataUseCase {

    private final NotionProvisioningPort notion;
    private final WorkspaceLedgerWriter ledger;

    @Override
    public ProvisioningStepResult execute(UUID workspaceId) {
        throw new UnsupportedOperationException(
                "Sample data population not yet implemented: requires the Notion adapter");
    }
}
