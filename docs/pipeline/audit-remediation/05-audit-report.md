# 05 — Audit Report: Audit-Remediation (final gate re-audit)

Scope: the changed-surface re-audit of the remediation described in `01-spec.md` / `02-architecture.md`.
Two jobs performed: (1) **verify closure** of every targeted original finding against the code (not
against a checkbox), and (2) **regression-audit** the changed surface for newly introduced defects.
No code modified, nothing staged or committed.

Files audited (production):
`infrastructure/adapter/notion/{NotionProperties,NotionApiException,NotionClient,NotionProvisioningAdapter}.java`,
`application/usecase/workspace/{SafeToSurfaceException,CreateWorkspaceService}.java`,
`infrastructure/adapter/cli/{ResourceTypeLabel,WorkspaceCommands,CommandFailedException}.java`,
plus the changed test surface under `.../adapter/notion/**`, `.../adapter/cli/**`,
`.../usecase/workspace/**`.

---

## 1. Summary (severity counts)

| Severity | Count |
|---|---|
| Critical | 0 |
| High | 0 |
| Medium | 0 |
| Low | 2 |

**Blocking (Critical + High): 0.** No blocking issues. Both new findings are Low
(quality/defense-in-depth) and do not block merge.

### Targeted-finding closure verdict

| Original finding | Verdict | Evidence |
|---|---|---|
| AUD-001 notion (High — token leak) | **CONFIRMED CLOSED** | `NotionProperties.java:15-22`; `NotionPropertiesTest.toString_redactsTokenToFixedMarker`; `NotionProvisioningAdapterTest.client_neverLeaksTokenInExceptionMessage` / `...DatabaseTest.client_neverLeaksTokenInDatabaseSliceExceptionMessage` |
| AUD-002 notion (Medium — data-source NPE guards) | **CONFIRMED CLOSED** | `NotionProvisioningAdapter.java:50-63,177-179,232-234`; `...DatabaseTest.verify_throwsNotionApiExceptionWhenDataSources{Empty,Absent}`, `verify_throwsNotionApiExceptionWhenDataSourceLookupReturns404`, `repairShape_*` |
| AUD-003 notion (Low — bounded pagination / null results) | **CONFIRMED CLOSED** | `NotionProvisioningAdapter.java:40,65-67,122-124,195-197`; `NotionProvisioningAdapterTest.findRootByIdentity_{throwsWhenSearchExceedsPageCap,treatsNullResultsAsEmptyPage}`, `...DatabaseTest.findChildByIdentity_{throwsWhenSearchExceedsPageCap,treatsNullResultsAsEmptyPage}` |
| AUD-006 notion (Low — shadowing local) | **CONFIRMED CLOSED** | `NotionProvisioningAdapter.java:155` (`propertyConfigs`, no longer shadows the `properties` field) |
| AUD-007 notion (Low — magic HTTP literals) | **CONFIRMED CLOSED** | `NotionClient.java:23,53,86` (`HttpStatus.NOT_FOUND.value()`, `HttpStatus.TOO_MANY_REQUESTS.value()`, `NOTION_OVERLOADED`) |
| cli AUD-001 (Medium — tautological registration test) | **CONFIRMED CLOSED** | `WorkspaceCommandsRegistrationTest.commandScan_discoversAndDependencyInjectsWorkspaceCommands` now boots a real `@CommandScan` context and asserts bean registration/DI, not annotation presence |
| cli AUD-002 (Medium — no real shell-level test) | **CONFIRMED CLOSED** | `WorkspaceCommandsShellTest` drives the real `Shell` + `NonInteractiveShellRunner`: required-option rejection, `--sample-data` default resolution via captured command, failure propagation; mislabelled `create_defaultsSampleDataToFalseWhenOmitted` removed |
| cli AUD-003 (Low — full report as exception message) | **CONFIRMED CLOSED** | `WorkspaceCommands.java:35,54-63` (concise `conciseFailureSummary`); full report still surfaced at `:33-34`; `WorkspaceCommandsTest.create_{signalsFailureWhenReportFailed,writesFullReportToTerminalOnFailureBeforeThrowing}` |
| AUD-004 (stakeholder — app-layer sanitization boundary) | **CONFIRMED CLOSED** | `CreateWorkspaceService.java:92-106`; `CreateWorkspaceServiceTest.execute_{collapsesUnexpectedExceptionToGenericSafeDetail,logsRawCauseAtErrorForFailedStep,surfacesNotionApiExceptionMessageVerbatimOnStepFailure}` |
| AUD-005 (stakeholder — CLI display-label mapping) | **CONFIRMED CLOSED** | `ResourceTypeLabel.java:10-27` (exhaustive over all 14 enum constants); domain `ProvisionedResourceType` unchanged/presentation-free; `WorkspaceCommandsTest.create_rendersHumanReadableLabelsNotRawEnumConstants` |

All ten targeted findings are **CONFIRMED CLOSED**. The two new Low findings below were introduced
by the remediation and are routed back, not treated as reopenings.

---

## 2. Findings

### AUD-101 (Low) — Design/consistency: raw enum constant leaks into operator-facing detail text
- **Category:** Quality / presentation consistency (undermines AUD-005's intent)
- **Evidence:** `application/usecase/workspace/CreateWorkspaceService.java:105` builds
  `"internal error during " + type + " provisioning (see server logs)"` where `type` is a
  `ProvisionedResourceType`; its default `toString()` yields the constant name (e.g. `TASKS_DB`).
  That string becomes `ProvisioningStepResult.detail()` and is rendered verbatim by
  `infrastructure/adapter/cli/WorkspaceCommands.java:46-48`, so the operator sees
  `Tasks: FAILED (internal error during TASKS_DB provisioning (see server logs))`. Asserted as-is by
  `CreateWorkspaceServiceTest.execute_collapsesUnexpectedExceptionToGenericSafeDetail:341`.
- **Why it matters:** AUD-005 / ADR-0014 introduced `ResourceTypeLabel` precisely so the raw enum
  identifier (`TASKS_DB`) never appears in CLI text; the label prefix now reads `Tasks`, but the
  sanitized detail re-introduces `TASKS_DB` into the same line, partially defeating that decision.
  The enum constant name is an internal identifier, not display text.
- **Citation:** *Effective Java* (Bloch), Item 34: *Use enums instead of int constants* — an enum's
  constant name / default `toString` is an identifier; human-readable presentation should come from
  an explicit mapping (which the CLI already provides via `ResourceTypeLabel`).
- **Recommended fix (one line):** In `safeDetail`, format with a stable non-identifier token (e.g.
  `type.name().toLowerCase()` is still leaky — prefer a neutral phrasing that omits the constant, or
  have the CLI substitute `ResourceTypeLabel.of(type)` when rendering a generic detail).

### AUD-102 (Low) — Robustness: the step-failure sanitizer can itself throw, escaping `execute()`
- **Category:** Error handling / fail-closed contract (defense-in-depth)
- **Evidence:** `application/usecase/workspace/CreateWorkspaceService.java:102-103` — for a
  `SafeToSurfaceException` (or `UnsupportedOperationException`), `safeDetail` returns
  `e.getMessage()` unguarded. `ProvisioningStepResult`'s compact constructor
  (`application/dto/workspace/ProvisioningStepResult.java:9-12`) throws `IllegalArgumentException`
  when a `FAILED` result has a `null`/blank detail. If a curated exception ever carries a
  null/blank message (e.g. a future `new NotionApiException(cause)` or an empty-string message),
  the `IllegalArgumentException` is thrown *inside* `runStep`'s `catch` block
  (`:95-98`) and propagates uncaught out of `execute()` — converting a contained per-step failure
  into a whole-run abort, breaking the "every step fails closed to a FAILED result" contract.
- **Why it matters:** The catch-all handler is the last line of the fail-closed design; a handler
  that can throw has a hole. Currently latent (all in-repo curated exceptions carry non-blank
  authored messages), and ADR-0013 asserts the invariant "still holds" — but that rests on caller
  discipline, not a guard in the sanitizer.
- **Citation:** *Effective Java* (Bloch), Item 77: *Don't ignore exceptions* / Item 54: *Return
  empty collections or arrays, not nulls* (defensive handling of possibly-null returns) — the
  handler must not assume `getMessage()` is non-blank when the downstream invariant forbids blank.
- **Recommended fix (one line):** Coalesce a null/blank curated message to a non-blank fallback in
  `safeDetail` (e.g. `Optional.ofNullable(e.getMessage()).filter(s -> !s.isBlank()).orElse("internal error during " + ... )`).

---

## 3. Category coverage (explicit clean/again statements)

- **Secret handling (AUD-001 / NFR-1):** CLEAN. Token redacted at its single source
  (`NotionProperties.toString`, `NotionProperties.java:17-22`). All string paths covered:
  `toString`/interpolation (override returns `****`), exception messages (adapter tests assert
  `hasMessageNotContaining(TOKEN)` and `hasMessageNotContaining("Bearer")`), and the
  `@ConfigurationProperties` binding-error path — the only validation failure for `token` is
  `@NotBlank` on a *blank* value, so no secret exists to leak, and binding-error messages do not
  invoke the target's `toString`. `token()` still returns the raw value for the `Bearer` header
  only, which is correct. No leak vector remains.
- **Injection:** CLEAN. Notion path/query values (ids, cursors) are passed as URI-template variables
  (`NotionClient.get/post/patch(..., uriVariables)`), so they are encoded, not concatenated into the
  URL. Verified by `NotionProvisioningAdapterTest.verifyPage_encodesPageIdAsUriVariable`.
- **AuthN/Z, password storage, CSRF/CORS, mass assignment:** N/A — no auth surface, no HTTP inbound
  endpoint, no persistence entity binding in the changed set. Nothing to raise.
- **Sensitive-data exposure / improper error handling (AUD-004):** CLEAN as designed. Unexpected
  exceptions collapse to a generic detail; raw cause (message + stack) is logged server-side only
  (`CreateWorkspaceService.java:96`). No over-swallowing: full debugging detail is preserved in the
  ERROR log (`execute_logsRawCauseAtErrorForFailedStep`). See AUD-102 for the one latent edge.
- **Untrusted-data validation (AUD-002/003 / NFR-2):** CLEAN. Fail-closed guards normalize
  null/empty `data_sources`, null data-source lookups, and null `results()` to `NotionApiException`;
  pagination is bounded at `MAX_SEARCH_PAGES = 50`. No `NPE`/`IndexOutOfBoundsException` escapes the
  adapter boundary. No infinite-loop vector (a `has_more:true` + `next_cursor:null` response
  terminates cleanly).
- **Dependency-direction / layering:** CLEAN. The `SafeToSurfaceException` marker lives in the
  application layer and is implemented by the adapter's `NotionApiException` (outer → inner), the
  ADR-0013-sanctioned alternative — this is *better* than importing the adapter type into the
  application layer. The domain enum gains no presentation responsibility; the label mapping lives
  in the CLI adapter.
- **Exhaustiveness / SOLID:** CLEAN. `ResourceTypeLabel.of` is an exhaustive `switch` with no
  `default` over all 14 `ProvisionedResourceType` constants, so adding a constant is a compile-time
  error (regression-safe). `NotionClient` HTTP-status substitution is behavior-preserving.
- **Comment pollution / naming:** CLEAN. No noise comments and no "AI-generated" comments. The
  explanatory comments present (`NotionClient.java:23` overloaded-status; the `WorkspaceCommandsShellTest`
  javadoc explaining why `@ShellTest` is unavailable in `spring-shell-test:3.3.2`; the
  `SafeToSurfaceException` javadoc documenting the layering rationale) are load-bearing and accurate.
- **Test discipline (NFR-3):** CLEAN. The shell-level test drives the real `Shell` runtime; no test
  claims shell-parsed behavior it does not exercise. Note (informational, not a finding): FR-3(c)'s
  non-zero-exit assertion is verified via `CommandFailedException` propagating uncaught out of
  `NonInteractiveShellRunner.run(...)` — which is the exact mechanism that drives Spring Boot's
  non-zero exit — rather than by asserting the numeric exit code. This is a faithful equivalent
  given the `@ShellTest` annotation's absence in the pinned version, and is honestly documented in
  the test's javadoc.

---

## 4. Principle scorecard

| Principle | Rating | One-line justification |
|---|---|---|
| **SOLID** | Strong | SRP respected: secret redaction in the type owning the secret, sanitization in the app layer, labelling in the CLI; exhaustive switch keeps OCP compile-safe. |
| **Clean Architecture** | Strong | Marker interface keeps the app→adapter dependency pointing inward; domain enum stays presentation-free; CLI is a thin renderer. |
| **DDD** | Adequate | No domain change; `ProvisionedResourceType` correctly stays framework/presentation-free; presentation concerns pushed to the adapter ring. |
| **Security** | Strong | Token fully redacted at source across all string paths; untrusted Notion responses validated fail-closed; error detail sanitized with server-side raw logging; allowlist (not denylist) classification. |
| **DRY / YAGNI** | Strong | Single redaction point, single label map, single sanitizer; no speculative abstraction. Minor DRY nit: the enum-name phrasing in `safeDetail` duplicates presentation concern already owned by `ResourceTypeLabel` (AUD-101). |

---

## 5. Blocking issues

**None.** No Critical or High findings. AUD-101 and AUD-102 are Low (quality / latent
defense-in-depth) and are non-blocking for merge; they are routed to the Implementer (impl layer)
via `findings.yml`.

---

## 6. Remediation re-audit (scoped, 2026-08-07)

Scope: a single changed file, `application/usecase/workspace/CreateWorkspaceService.java` (the new
`safeDetail(Exception)` helper + `GENERIC_FAILURE_DETAIL` constant), its updated test
`CreateWorkspaceServiceTest.java`, and `ProvisioningStepResult.java` for invariant context. Verifies
closure of AUD-101 and AUD-102 only. No whole-codebase re-audit. Report only — nothing modified,
staged, or committed.

### Closure verdict

| Finding | Verdict | Evidence |
|---|---|---|
| AUD-101 (Low — enum constant in detail) | **CONFIRMED CLOSED** | `CreateWorkspaceService.java:101,108`: `safeDetail` returns the constant `GENERIC_FAILURE_DETAIL = "internal error during provisioning (see server logs)"` with no `type` interpolation, and the `type` parameter was removed from the `safeDetail` signature entirely. The enum name can no longer reach `detail()`. |
| AUD-102 (Low — sanitizer can throw) | **CONFIRMED CLOSED** | `CreateWorkspaceService.java:104-107`: for a `SafeToSurfaceException`/`UnsupportedOperationException`, a `null`/blank message is coalesced to the non-blank `GENERIC_FAILURE_DETAIL`, so a `FAILED` `ProvisioningStepResult` can never receive a null/blank detail and the compact-constructor invariant (`ProvisioningStepResult.java:9-12`) cannot trip inside the catch. |

### Do the added tests genuinely exercise the fix (not just assert on names)?

- **AUD-101** — `execute_collapsesUnexpectedExceptionToGenericSafeDetail` (`:325-345`) throws an
  unexpected `IllegalStateException` carrying `jdbc://internal-host:5432 …`, then asserts the detail
  equals the constant AND negatively asserts `doesNotContain("TASKS_DB")`, `doesNotContain("jdbc")`,
  `doesNotContain("internal-host")` (`:341-344`). This exercises the real collapse path and pins the
  enum-name absence, not just a happy string — genuine.
- **AUD-102** — `execute_fallsBackToGenericDetailWhenSafeToSurfaceExceptionHasNoMessage`
  (`:347-366`) throws `new NotionApiException((String) null)`. `NotionApiException implements
  SafeToSurfaceException` (verified in source), so the input hits the curated branch with a *null*
  message — the exact trip condition. The test asserts `execute()` returns normally with a `FAILED`
  outcome and the fallback detail, i.e. no `IllegalArgumentException` escaped `execute()`. Genuine:
  it drives the previously-latent null path end-to-end.

### Regression check on this change only

CLEAN — no new security/design/quality defect introduced by the change.

- **Information loss for debugging:** none. The raw cause (message + stack) and the resource `type`
  are still logged server-side at ERROR in `runStep` (`:96`, `log.error("Provisioning step {} failed",
  type, e)`), and `CreateWorkspaceServiceTest.execute_logsRawCauseAtErrorForFailedStep` still asserts
  the ERROR event carries `TASKS_DB` and the full `IllegalStateException` message. The operator also
  still gets the human-readable step name via `ProvisioningStepResult.type()` → `ResourceTypeLabel`
  in the CLI, so dropping `type` from the *detail* string loses nothing operator- or debug-side.
- **Removed `type` parameter → dead/awkward signature:** none. `safeDetail(Exception e)` is now
  single-arg and every call site (`:97`) matches; `type` is still used where it belongs (the
  `ProvisioningStepResult` constructor and the ERROR log). No unused parameter or dead code.
- **New blank/null path:** none. `GENERIC_FAILURE_DETAIL` is a non-blank literal, and both branches
  of `safeDetail` now return non-blank, so the `ProvisioningStepResult` FAILED-detail invariant is
  always satisfiable — the change *removes* a throw path rather than adding one.
- **Field placement nit (not a finding):** `GENERIC_FAILURE_DETAIL` is declared mid-class between two
  methods (`:101`) rather than with the other fields; legal and harmless, below the bar to raise.

### Result

- AUD-101: **CONFIRMED CLOSED** — non-regression test present and genuine.
- AUD-102: **CONFIRMED CLOSED** — non-regression test present and genuine.
- New findings from this change: **none**.
- Blocking (Critical + High): **0**.
