package com.lifeos.infrastructure.adapter.persistence;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

interface SpringDataWorkspaceRepository extends JpaRepository<WorkspaceJpaEntity, UUID> {

    @EntityGraph(attributePaths = "resources")
    Optional<WorkspaceJpaEntity> findById(UUID id);

    @EntityGraph(attributePaths = "resources")
    Optional<WorkspaceJpaEntity> findByPersonIdAndName(UUID personId, String name);
}
