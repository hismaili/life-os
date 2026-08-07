package com.lifeos.application.port;

import com.lifeos.domain.workspace.ProvisionedResourceType;

public record RelationSpec(String name, ProvisionedResourceType fromDatabase, ProvisionedResourceType toDatabase, boolean bidirectional) {}
