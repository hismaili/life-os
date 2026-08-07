package com.lifeos.domain.workspace;

import java.util.Optional;
import java.util.UUID;

public interface WorkspaceRepository {
    Workspace save(Workspace workspace);
    Optional<Workspace> findById(UUID id);
    Optional<Workspace> findByPersonIdAndName(UUID personId, String name);
}
