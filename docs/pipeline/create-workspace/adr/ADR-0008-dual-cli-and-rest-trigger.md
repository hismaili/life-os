# ADR-0008: Expose Create Workspace through both the CLI and a REST endpoint

## Status
Accepted (Architect stage). Resolves OQ-4 (v0 exposes both a Spring Shell CLI command and a REST endpoint).

## Context
Create Workspace must be triggerable by an operator. `docs/architecture/03-Domain-Model.md` anticipates a future web adapter alongside the CLI. The resolved decision (OQ-4) is that **v0 ships both** a Spring Shell CLI command and a REST endpoint, so the orchestrator is exercised from two driving adapters. Both are *driving* (primary) adapters over the same `CreateWorkspaceUseCase`; neither may leak framework or transport concerns into the application core (`CLAUDE.md`, hexagonal layering).

## Options considered
1. **CLI only for v0**, add REST later.
   - (+) Smaller surface. (−) Rejected by OQ-4: a REST trigger is wanted now for programmatic/remote provisioning.
2. **REST only**, no CLI.
   - (−) Loses the existing Spring Shell entry point and the local operator workflow the project already assumes. Rejected.
3. **Both CLI and REST as thin driving adapters** over the same `CreateWorkspaceUseCase`, each translating its own transport into a `CreateWorkspaceCommand` and rendering the returned `ProvisioningReport`.
   - (+) One use case, two adapters — the canonical Ports & Adapters shape. (+) No duplicated orchestration. (−) Two error-rendering paths to keep consistent.

## Decision
Adopt option 3.

- **CLI** — `infrastructure.adapter.cli.WorkspaceCommands`, a Spring Shell `@Command` component mapping `workspace create --name <n> --person-id <id> [--sample-data]` to the orchestrator, rendering the `ProvisioningReport` and returning a non-zero result when `report.failed()` (Spring Shell Reference — Command Registration, docs.spring.io/spring-shell).
- **REST** — `infrastructure.adapter.web.WorkspaceController`, a `@RestController` exposing `POST /api/workspaces`. The request body is a record validated with Jakarta Bean Validation (`@Valid`); it maps to `CreateWorkspaceCommand` and returns the report. A `201 Created` (or `200 OK` on a pure reconcile) carries the report; a failed run maps to an error.
- **Error model** — `infrastructure.adapter.web.ApiExceptionHandler`, a `@RestControllerAdvice` that returns **RFC 9457** `ProblemDetail` (`application/problem+json`) for validation failures, domain rule violations, and provisioning failures. `ProblemDetail`/`ErrorResponse` are Spring's first-class RFC 9457 support (Spring Framework Reference — Error Responses / `ProblemDetail`, docs.spring.io/spring-framework).

## Consequences
- The application core (`CreateWorkspaceUseCase`, `CreateWorkspaceCommand`, `ProvisioningReport`) is transport-agnostic; both adapters depend inward on it, never the reverse.
- Two adapters must translate `ProvisioningReport.failed()` consistently — CLI to a non-zero result, REST to a `ProblemDetail`.
- REST authentication/authorization is **out of scope** for single-tenant v0 (spec §8); the controller still validates input and emits RFC 9457 errors. Securing the endpoint is tracked future work.
- A new `infrastructure.adapter.web` package is introduced (did not previously exist); flagged for the SME/Implementer.

## Post-audit refinement (2026-08-04)

Audit findings H1/M1 (`05-audit-report.md`) hardened this ADR's error model:
- **No internal detail crosses the REST boundary.** `ProvisioningStepResultResponse` drops its free-text `detail`; failure responses carry only per-step `{type, outcome}` plus `workspaceId`. The full report (with details) is logged server-side via SLF4J, satisfying the OWASP Error Handling guidance to log internally and return a generic response. `handleIllegalArgument` no longer echoes the exception message.
- **Complete handler coverage.** Added a fallback `@ExceptionHandler(Exception.class)` → generic 500 `ProblemDetail`, and `@ExceptionHandler(DataIntegrityViolationException.class)` → 409 `ProblemDetail` for the `(personId, name)` find-or-create race — so every path returns a controlled RFC 9457 body rather than a Boot default.

The CLI rendering is unchanged: it still shows per-step `detail` locally, which is appropriate for a local operator surface and is not a network boundary.
