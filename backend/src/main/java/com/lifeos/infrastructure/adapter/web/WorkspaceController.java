package com.lifeos.infrastructure.adapter.web;

import com.lifeos.application.dto.workspace.CreateWorkspaceCommand;
import com.lifeos.application.dto.workspace.ProvisioningOutcome;
import com.lifeos.application.dto.workspace.ProvisioningReport;
import com.lifeos.application.usecase.workspace.CreateWorkspaceUseCase;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workspaces")
@RequiredArgsConstructor
public class WorkspaceController {

    private final CreateWorkspaceUseCase createWorkspace;

    @PostMapping
    public ResponseEntity<ProvisioningReportResponse> create(@Valid @RequestBody CreateWorkspaceRequest request) {
        ProvisioningReport report = createWorkspace.execute(
                new CreateWorkspaceCommand(request.name(), request.personId(), request.sampleData(),
                        request.notionToken(), request.notionRootParentPageId()));

        if (report.failed()) {
            throw new WorkspaceProvisioningFailedException(report);
        }

        HttpStatus status = report.steps().stream()
                .allMatch(s -> s.outcome() == ProvisioningOutcome.RECONCILED)
                ? HttpStatus.OK
                : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(toResponse(report));
    }

    private ProvisioningReportResponse toResponse(ProvisioningReport report) {
        return new ProvisioningReportResponse(
                report.workspaceId(),
                report.steps().stream()
                        .map(s -> new ProvisioningStepResultResponse(s.type().name(), s.outcome().name()))
                        .toList(),
                report.failed());
    }
}
