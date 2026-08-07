package com.lifeos.application.usecase.workspace;

/**
 * Marker for exceptions whose {@link Throwable#getMessage()} is authored by our own code and is
 * therefore safe to surface verbatim to the CLI operator (ADR-0013). Adapter exceptions implement
 * this application-owned interface — the application layer never references an adapter type
 * directly (CLAUDE.md: inner layers never depend on outer ones).
 */
public interface SafeToSurfaceException {
}
