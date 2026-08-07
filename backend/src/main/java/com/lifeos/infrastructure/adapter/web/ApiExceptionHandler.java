package com.lifeos.infrastructure.adapter.web;

import com.lifeos.application.dto.workspace.ProvisioningStepResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.Map;

@Slf4j
@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        pd.setTitle("Validation failed");
        pd.setProperty("errors", ex.getBindingResult().getFieldErrors().stream()
                .map(e -> Map.of("field", e.getField(), "message", e.getDefaultMessage())).toList());
        return pd;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Rejected invalid workspace request", ex);
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        pd.setTitle("Invalid request");
        return pd;
    }

    @ExceptionHandler(WorkspaceProvisioningFailedException.class)
    ProblemDetail handleProvisioningFailed(WorkspaceProvisioningFailedException ex) {
        log.error("Workspace provisioning incomplete for workspace {}: {}",
                ex.getReport().workspaceId(), ex.getReport().steps());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY,
                "One or more provisioning steps failed or were blocked");
        pd.setTitle("Workspace provisioning incomplete");
        pd.setProperty("workspaceId", ex.getReport().workspaceId());
        pd.setProperty("steps", ex.getReport().steps().stream()
                .map(ApiExceptionHandler::toClientView).toList());
        return pd;
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        log.warn("Data integrity violation handling workspace request", ex);
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        pd.setTitle("Conflicting workspace state");
        return pd;
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unexpected error handling workspace request", ex);
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        pd.setTitle("Internal error");
        return pd;
    }

    private static Map<String, String> toClientView(ProvisioningStepResult step) {
        return Map.of("type", step.type().name(), "outcome", step.outcome().name());
    }
}
