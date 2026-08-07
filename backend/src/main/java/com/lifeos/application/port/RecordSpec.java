package com.lifeos.application.port;

import com.lifeos.domain.workspace.ProvisionedResourceType;

import java.util.Map;

public record RecordSpec(ProvisionedResourceType targetDatabase, Map<String, Object> properties) {}
