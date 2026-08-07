package com.lifeos.domain.knowledge;

import com.lifeos.domain.resource.Resource;
import java.util.List;
import java.util.UUID;

public class KnowledgeDiscoveryService {

    public List<Resource> findRelatedResources(Knowledge knowledge, List<Resource> allResources) {
        return allResources.stream()
                .filter(resource -> resource.getKnowledgeId() != null && 
                                   resource.getKnowledgeId().equals(knowledge.getId()))
                .toList();
    }
}
