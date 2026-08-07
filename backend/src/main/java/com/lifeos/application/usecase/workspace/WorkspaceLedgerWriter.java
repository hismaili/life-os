package com.lifeos.application.usecase.workspace;

import com.lifeos.domain.workspace.ProvisionedResourceType;
import com.lifeos.domain.workspace.Workspace;
import com.lifeos.domain.workspace.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class WorkspaceLedgerWriter {

    private final WorkspaceRepository workspaceRepository;

    @Transactional
    public void record(UUID workspaceId, ProvisionedResourceType type, String notionId) {
        Workspace workspace = workspaceRepository.findById(workspaceId)
                .orElseThrow(() -> new IllegalStateException("Workspace not found: " + workspaceId));
        workspaceRepository.save(workspace.record(type, notionId));
    }
}
