# ADR-0013: Application-layer sanitization boundary for provisioning step-failure details

- **Status:** Accepted (implements resolved stakeholder decision #2 — AUD-004 now in scope)
- **Feature:** audit-remediation — AUD-004 (cli-wiring, Low)
- **Owner:** spring-architect → SME (`application.usecase.workspace`)

## Context
`CreateWorkspaceService.runStep` (line 88–94) catches any `Exception` from a provisioning step and
maps it to `new ProvisioningStepResult(type, FAILED, e.getMessage())`. That raw
`Exception.getMessage()` then flows into `ProvisioningStepResult.detail()`, into
`WorkspaceCommands#renderReport`, and out to the operator's console and any log of the CLI output.
Surfacing arbitrary downstream exception text verbatim is *improper error handling*: it can expose
internal implementation detail (stack-adjacent messages, host/URI fragments, driver internals) to
the CLI surface. The stakeholder has resolved this into scope with the intended shape: **log the
raw cause server-side; surface a sanitized, safe `detail`.** This is an application-layer policy
decision — "what is internal vs. safe to surface" — so it belongs here, not in the CLI.

## Options considered
1. **Allowlist by exception type: curated application exceptions pass their message through; all
   other exceptions collapse to a generic, non-leaking detail. Always log the raw throwable
   (message + stack) at ERROR.**
   - + Operators keep the actionable, intentionally-authored messages (`NotionApiException`,
     `UnsupportedOperationException` for not-yet-implemented steps) that were designed to be shown;
     unexpected exceptions (NPE, driver/IO errors) are hidden behind a generic string.
   - + Fail-safe default (unknown → generic); the full detail is never lost (it is logged).
   - − Requires a maintained notion of "curated" types (a short allowlist, or a marker interface).
2. **Always generic detail for every failure ("provisioning step X failed; see server logs").**
   - + Simplest; leaks nothing.
   - − Throws away useful, already-safe messages (e.g. "no data source on Tasks database"), making
     the CLI far less actionable for the exact operator-facing errors the adapter authored on
     purpose. Poorer UX for no additional security benefit over option 1.
3. **Sanitize by string-scrubbing (regex-strip URLs, tokens, stack frames from any message).**
   - + Keeps some message content generically.
   - − Fragile denylist; easy to miss a leak vector; scrubbing logic is itself a maintenance/security
     liability. Rejected in favor of a type allowlist (allowlists beat denylists for security).

## Decision
Adopt **Option 1**. Define the **safe-to-surface** set as *curated application/adapter exceptions
whose messages are authored by our own code*: `NotionApiException` and `UnsupportedOperationException`.
For any other `Exception`, surface a generic detail (`"internal error during <type> provisioning
(see server logs)"`). In all cases, log the raw throwable via SLF4J at ERROR
(`log.error("Provisioning step {} failed", type, e)`).

- **Safe-to-surface (verbatim `getMessage()`):** `NotionApiException` (constructed by the adapter/
  `NotionClient` with controlled text describing status/code/boundary state; never contains the
  token — see ADR-0010), and `UnsupportedOperationException` (fixed "not yet implemented" strings).
- **Internal (never surfaced):** everything else — `NullPointerException`, `RestClient`/IO/connection
  exceptions, JDBC/persistence exceptions, and any exception whose message we do not author.

**Coupling note:** classifying by `instanceof NotionApiException` introduces an application→adapter
*type* reference. If the team prefers zero such coupling, an equivalent allowlist can be expressed
by a marker interface (e.g. `SafeToSurfaceException`) that `NotionApiException` implements; this ADR
records that as an accepted alternative for the SME.

## Consequences
- Raw downstream exception text no longer reaches `ProvisioningStepResult.detail()` for unexpected
  failures; those become a generic message while the full cause is preserved in server logs.
- The intentionally operator-facing adapter messages remain visible → no UX regression on the known
  failure modes (empty data source, unimplemented step).
- `ProvisioningStepResult`'s invariant (non-blank detail on FAILED/BLOCKED) still holds — both
  branches produce non-blank text.
- Adds one SLF4J logger to `CreateWorkspaceService`; no new transactional resource, no happy-path
  change (NFR-4). The `BLOCKED` path (line 98) already uses a fixed safe string and is unaffected.
- Testable at the seam: a non-`NotionApiException` cause yields the generic detail; a
  `NotionApiException` cause passes through; the raw cause is logged.

## References
- OWASP ASVS v4.0 — V7.4.1 (generic error messages to the user; detailed error information logged
  server-side).
- OWASP Cheat Sheet Series — *Error Handling* (do not leak internal detail; log server-side, return
  sanitized messages) and *Improper Error Handling* guidance.
- Allowlist-over-denylist principle — OWASP Input Validation Cheat Sheet.
