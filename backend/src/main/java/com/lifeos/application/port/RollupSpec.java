package com.lifeos.application.port;

import com.lifeos.domain.workspace.ProvisionedResourceType;

public record RollupSpec(String name, ProvisionedResourceType onDatabase, RelationSpec viaRelation, String rollupProperty) {}
