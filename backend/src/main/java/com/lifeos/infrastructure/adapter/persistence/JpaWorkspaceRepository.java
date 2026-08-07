package com.lifeos.infrastructure.adapter.persistence;

import com.lifeos.domain.workspace.ProvisionedResource;
import com.lifeos.domain.workspace.ProvisionedResourceType;
import com.lifeos.domain.workspace.Workspace;
import com.lifeos.domain.workspace.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
@RequiredArgsConstructor
class JpaWorkspaceRepository implements WorkspaceRepository {

    private final SpringDataWorkspaceRepository springData;

    @Override
    @Transactional
    public Workspace save(Workspace workspace) {
        WorkspaceJpaEntity entity = springData.findById(workspace.getId())
                .orElseGet(() -> {
                    WorkspaceJpaEntity fresh = new WorkspaceJpaEntity();
                    fresh.setId(workspace.getId());
                    return fresh;
                });
        entity.setPersonId(workspace.getPersonId());
        entity.setName(workspace.getName());

        Map<ProvisionedResourceType, ProvisionedResourceJpaEntity> existingByType = new HashMap<>();
        for (ProvisionedResourceJpaEntity resource : entity.getResources()) {
            existingByType.put(resource.getType(), resource);
        }

        Set<ProvisionedResourceType> desiredTypes = workspace.getResources().stream()
                .map(ProvisionedResource::type)
                .collect(Collectors.toSet());
        entity.getResources().removeIf(resource -> !desiredTypes.contains(resource.getType()));

        for (ProvisionedResource resource : workspace.getResources()) {
            ProvisionedResourceJpaEntity resourceEntity = existingByType.get(resource.type());
            if (resourceEntity == null) {
                resourceEntity = new ProvisionedResourceJpaEntity();
                resourceEntity.setWorkspace(entity);
                resourceEntity.setType(resource.type());
                entity.getResources().add(resourceEntity);
            }
            resourceEntity.setNotionId(resource.notionId());
            resourceEntity.setProvisionedAt(resource.provisionedAt());
        }

        WorkspaceJpaEntity saved = springData.save(entity);
        return toDomain(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Workspace> findById(UUID id) {
        return springData.findById(id).map(JpaWorkspaceRepository::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Workspace> findByPersonIdAndName(UUID personId, String name) {
        return springData.findByPersonIdAndName(personId, name).map(JpaWorkspaceRepository::toDomain);
    }

    private static Workspace toDomain(WorkspaceJpaEntity entity) {
        List<ProvisionedResource> resources = entity.getResources().stream()
                .map(r -> new ProvisionedResource(r.getType(), r.getNotionId(), r.getProvisionedAt()))
                .toList();
        return Workspace.reconstitute(entity.getId(), entity.getPersonId(), entity.getName(), resources);
    }
}
