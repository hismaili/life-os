# Remediation plan — audit-remediation

**Generated:** 2026-08-07 · **Open findings:** 2 (AUD-101, AUD-102) · **Iteration:** 2 (final-gate re-audit)

The ten originally targeted findings (NOTION-AUD-001/002/003/006/007, CLI-AUD-001..005) are
**CONFIRMED CLOSED** per `05-audit-report.md` and are not re-routed here.

## Needs a human decision (gate — resolve before cascading)

None. Both new findings are mechanical (`proposes_decision: false`), localized to
`CreateWorkspaceService.safeDetail`, and require no architectural/security choice —
`ResourceTypeLabel` already establishes where human-label vocabulary lives (the CLI adapter);
neither fix asks the application layer to acquire new label vocabulary, only to stop leaking the
raw enum identifier and to guard a possibly-blank message. Triage read validated: no gate needed.

## Auto-cascade (mechanical — no decision needed)

Ordered top-down; execute in this order. Sequenced within the shared method so AUD-102's
null/blank fallback text is chosen *after* AUD-101's neutral phrasing exists, so the guard's
fallback does not reintroduce the enum-name leak AUD-101 removes.

| # | Finding(s) | Root layer | Proposed owner | Artifacts to update | Severity | Cascade |
|---|---|---|---|---|---|---|
| 1 | AUD-101 | impl | spring-implementer | `backend/src/main/java/com/lifeos/application/usecase/workspace/CreateWorkspaceService.java` (+ test: `CreateWorkspaceServiceTest.java:341`) | Low | impl → QA (regression, changed surface) → re-audit |
| 2 | AUD-102 | impl | spring-implementer | `backend/src/main/java/com/lifeos/application/usecase/workspace/CreateWorkspaceService.java` (+ possibly `ProvisioningStepResult.java` if a shared fallback constant is introduced) | Low | impl → QA (regression, changed surface) → re-audit |

Detail per item:

1. **AUD-101** — `safeDetail`'s generic-failure branch interpolates `ProvisionedResourceType`'s raw
   `toString()` (e.g. `TASKS_DB`) into operator-facing text, undermining `ResourceTypeLabel`
   (AUD-005/ADR-0014). Fix: rephrase the generic detail to omit the enum identifier entirely (e.g.
   drop the resource-type token from the sanitized string, since `WorkspaceCommands` already
   prefixes the line with the human label) — no new label vocabulary needed in the application
   layer. Non-regression: update/extend
   `CreateWorkspaceServiceTest.execute_collapsesUnexpectedExceptionToGenericSafeDetail`.
2. **AUD-102** — `safeDetail` returns `e.getMessage()` unguarded for `SafeToSurfaceException`/
   `UnsupportedOperationException`; a null/blank message would trip
   `ProvisioningStepResult`'s non-blank-detail invariant and throw `IllegalArgumentException`
   *inside* the catch block, escaping `execute()` and breaking the per-step fail-closed contract.
   Fix: coalesce a null/blank curated message to the (post-AUD-101) non-blank generic fallback.
   Non-regression: new test asserting a `SafeToSurfaceException`/`UnsupportedOperationException`
   with a null or blank message still yields a `FAILED` `ProvisioningStepResult` (not a thrown
   `IllegalArgumentException`) from `execute()`.

## Escalated to human (caps hit / recurring)

None. Neither finding has any prior up-hops or a `verified` recurrence; both are freshly opened by
this audit pass.

## Notes

- Changed-surface only: QA re-runs `CreateWorkspaceServiceTest` (and any `WorkspaceCommandsTest`
  cases asserting the rendered detail string); Auditor re-audits only
  `CreateWorkspaceService.java` (and `ProvisioningStepResult.java` if touched).
- Both items are proposed to the same owner (`spring-implementer`) touching the same method;
  the implementer may combine them into a single change, but should land AUD-101's phrasing before
  wiring AUD-102's fallback to it, per the sequencing above.
</content>
