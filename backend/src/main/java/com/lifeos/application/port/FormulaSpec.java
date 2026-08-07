package com.lifeos.application.port;

import com.lifeos.domain.workspace.ProvisionedResourceType;

public record FormulaSpec(String name, ProvisionedResourceType onDatabase, String expression) {}
