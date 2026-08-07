package com.lifeos.infrastructure.adapter.web;

import java.util.List;
import java.util.UUID;

public record ProvisioningReportResponse(UUID workspaceId, List<ProvisioningStepResultResponse> steps, boolean failed) {}
