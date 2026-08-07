package com.lifeos.infrastructure.adapter.notion;

import com.lifeos.application.usecase.workspace.SafeToSurfaceException;

public class NotionApiException extends RuntimeException implements SafeToSurfaceException {
    public NotionApiException(String message) {
        super(message);
    }

    public NotionApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
