# 01 — Specification: Audit Remediation (Notion Adapter + CLI Wiring)

Source material: `docs/audit/notion-adapter/05-audit-report.md`,
`docs/audit/notion-adapter/findings.yml`, `docs/audit/cli-wiring/05-audit-report.md`.
This spec covers **remediation only** — closing findings from two completed, standalone
audits of already-merged code. It defines no new product capability.

## 1. Summary

Two independent code audits (Notion adapter: `backend/.../infrastructure/adapter/notion/**`;
CLI wiring: `backend/.../infrastructure/adapter/cli/**`) identified one blocking High-severity
secret-exposure defect, two Medium-severity robustness/test-discipline gaps in each audit
(four total), and a set of Low-severity and non-binding design observations. This feature
fixes the blocking and in-scope Medium/Low findings so the Notion provisioning adapter and
the `workspace create` CLI command meet the security, robustness, and test-discipline bar the
audits measured against, without changing observable product behavior for a correctly
responding Notion API and a correctly invoked CLI command.

## 2. Actors & stakeholders

- **CLI operator** — runs `workspace create` against a real Notion workspace; consumes
  console output and exit codes. Must never see a raw API token in any output.
- **Backend maintainer** — owns `infrastructure/adapter/notion` and
  `infrastructure/adapter/cli`; consumes the fixed code and the regression tests that guard it.
- **Auditor (spring-auditor)** — raised the findings this spec closes; re-verifies on the next
  audit pass that AUD-IDs are actually resolved, not just marked closed.
- **Notion API** — external system whose response shape (nullable/absent fields, error
  statuses) is untrusted input at the adapter boundary.

## 3. Functional requirements

### FR-1 — Redact the Notion token from `NotionProperties` string representation
`NotionProperties` (a record with `token` as its first component) must never emit the raw
token via its string representation. Any interpolation, log statement, binding-error message,
or exception referencing the `NotionProperties` instance must show a masked/redacted form of
`token`, never the literal value.
*Closes: AUD-001 (High).*

### FR-2 — Guard Notion database/data-source response dereferencing
`NotionProvisioningAdapter` must not dereference `response.dataSources().get(0)` or a fetched
data-source's `properties()` without first validating that the collection is non-null/non-empty
and the data-source lookup result is non-null. When the Notion API returns an absent/empty
`data_sources` array, or a data-source lookup 404s (client returns `null`), the adapter must
raise a `NotionApiException` carrying a description of what boundary data was missing/invalid,
never let an `NullPointerException`/`IndexOutOfBoundsException` propagate uncaught.
*Closes: AUD-002 (Medium).*

### FR-3 — Real Spring Shell test coverage for `workspace create`
The CLI test suite must include at least one test that boots the actual `LifeOsApplication`
composition root (or an equivalent that reaches the production `@CommandScan`) and drives
`workspace create` through Spring Shell's real command-line parsing (`spring-shell-test`
`ShellTestClient`/`@ShellTest`), not direct Java method invocation on a hand-constructed
`WorkspaceCommands`. This coverage must exercise, through the shell:
  a. required-option enforcement (omitting `--name` or `--person-id` is rejected before the
     use case is invoked);
  b. the `--sample-data` default (omitting the flag on a shell-parsed invocation resolves to
     `sampleData = false`, verified via the captured/executed command, not a literal Java
     argument);
  c. a non-zero process/command exit on a `FAILED` `ProvisioningReport`.
*Closes: AUD-002 (Medium, cli-wiring) — the un-exercised shell registration/parsing/exit-code
contract.*

### FR-4 — Remove the tautological registration test / un-exercised default-value test
The existing `applicationComposesCliViaCommandScan` reflective annotation-presence check (in
`WorkspaceCommandsRegistrationTest`) and the misleadingly named
`create_defaultsSampleDataToFalseWhenOmitted` test (in `WorkspaceCommandsTest`, which calls
`commands.create("Personal", personId, false)` and therefore never exercises `@Option`
default resolution) must be replaced. Either remove them outright or restrict them to
asserting only what they can honestly prove (e.g., that the `@Command`-scanned class exists),
while FR-3's shell-level test becomes the source of truth for parsing/default/exit-code
behavior. No test in the suite may claim (by name or docstring) to verify shell-parsed
behavior it does not actually invoke through the shell.
*Closes: AUD-001 (Medium, cli-wiring) and the tautology portion of AUD-002 (cli-wiring).*

### FR-5 — Bound Notion pagination loops against malformed responses
`findRootByIdentity` and `findChildByIdentity` in `NotionProvisioningAdapter` must not loop
unboundedly on the Notion search API's `has_more`/`next_cursor` fields, and must not
dereference a null `results()` list. The adapter must apply an explicit cap (page count and/or
total matches accumulated) and default a null `results()` to an empty list, so that a
repeating cursor or a malformed `results: null` response cannot cause unbounded memory growth
or an NPE.
*Closes: AUD-003 (Low, notion-adapter) — in scope, see rationale below.*

### FR-6 — Rename the shadowing local variable in `createDatabase`
The local `Map<String, Object> properties` in `NotionProvisioningAdapter#createDatabase` must
be renamed so it no longer shadows the `private final NotionProperties properties` field.
*Closes: AUD-006 (Low, notion-adapter) — in scope, see rationale below.*

### FR-7 — Replace magic HTTP status literals with named constants
`NotionClient` must reference `HttpStatus.NOT_FOUND.value()` / `HttpStatus.TOO_MANY_REQUESTS.value()`
instead of the bare literals `404`/`429`, and the non-standard `529` must be a named,
commented constant local to `NotionClient`.
*Closes: AUD-007 (Low, notion-adapter) — in scope, see rationale below.*

### FR-8 — Give `CommandFailedException` a concise failure message distinct from the report body
`WorkspaceCommands#create` must no longer pass the full multi-line `renderReport` output as the
`CommandFailedException` detail message. The exception's detail message must concisely
describe the failure (e.g., which step(s) failed and why); the full report must still be made
available to the operator (e.g., written to shell output) on both the success and failure
paths.
*Closes: AUD-003 (Low, cli-wiring) — in scope, see rationale below.*

## 4. Acceptance criteria

**FR-1**
- Given a `NotionProperties` instance constructed with a non-blank `token`,
  When `toString()` is invoked (directly, via string interpolation, via a logging call, or via
  a `@ConfigurationProperties` binding-validation error message),
  Then the raw token value must not appear anywhere in the resulting string; the token must be
  masked (e.g., fully redacted or truncated with a fixed non-reversible marker).
- Given the redaction is in place,
  When `version` and `rootParentPageId` (non-secret fields) are inspected via the string
  representation,
  Then they may still be shown as-is (only the token is a secret).

**FR-2**
- Given a Notion `GET /v1/databases/{id}` response whose `data_sources` array is empty or
  absent,
  When the adapter attempts to resolve the database's data source,
  Then it throws a `NotionApiException` describing the missing data source, and no
  `NPE`/`IndexOutOfBoundsException` is thrown.
- Given a subsequent data-source lookup (`GET /v1/data_sources/{id}`) returns `null` (e.g. a
  404 mapped by `NotionClient`),
  When the adapter dereferences the data source's `properties()`,
  Then it throws a `NotionApiException` instead of an `NPE`.
- Given both response shapes are valid/non-null,
  When the adapter proceeds through the same code path,
  Then behavior is unchanged from current passing-case behavior (no regression).

**FR-3**
- Given the real Spring Boot application context is booted with `spring-shell-test`,
  When `workspace create --name "Personal" --person-id <uuid>` is run through the shell (no
  `--sample-data`),
  Then the executed command resolves `sampleData = false` and the use case is invoked
  accordingly.
- Given the same booted context,
  When `workspace create` is run omitting `--name` or `--person-id`,
  Then the shell rejects the invocation before `CreateWorkspaceUseCase.execute` is called
  (verified via a non-invocation assertion or an equivalent parse-failure signal).
- Given the underlying use case returns a `ProvisioningReport` with `failed() == true`,
  When the command is run through the shell,
  Then the shell-level invocation result signals a non-zero exit / failure outcome consistent
  with Spring Shell's documented exit-code mapping.

**FR-4**
- Given the remediated test suite,
  When it is inspected,
  Then no test named or documented as verifying shell-parsed option defaulting invokes
  `WorkspaceCommands` via direct Java method call instead of the shell; the tautological
  reflective `@CommandScan`-presence-only assertion is removed or narrowed to a claim it
  actually proves.
- Given `mvn test` is run,
  When the full suite executes,
  Then all remediated/replacement tests pass and overall CLI-wiring coverage includes the
  shell-level scenarios from FR-3.

**FR-5**
- Given a Notion search response with a repeating `next_cursor` (simulating a misbehaving
  API),
  When `findRootByIdentity`/`findChildByIdentity` paginate,
  Then the loop terminates at a defined page/match cap rather than continuing indefinitely.
- Given a Notion search response with `results: null`,
  When the adapter processes it,
  Then it is treated as an empty page (no NPE), and pagination proceeds/terminates per the cap
  logic.

**FR-6**
- Given `NotionProvisioningAdapter#createDatabase`,
  When the source is inspected,
  Then the local `Map<String, Object>` variable previously named `properties` has a distinct
  name, and no reference inside the method or its neighbors ambiguously resolves to the field
  vs. the local.

**FR-7**
- Given `NotionClient`,
  When the source is inspected,
  Then `404` and `429` numeric literals are replaced by `HttpStatus.NOT_FOUND.value()` /
  `HttpStatus.TOO_MANY_REQUESTS.value()`, and `529` is a named constant with an explanatory
  comment.
- Given the existing `NotionClientTest` status-mapping tests,
  When run after the change,
  Then they continue to pass unmodified in behavior (constant substitution must be
  behavior-preserving).

**FR-8**
- Given `CreateWorkspaceService` returns a `FAILED` `ProvisioningReport`,
  When `workspace create` is invoked,
  Then the thrown `CommandFailedException`'s message is a concise failure summary (not the
  full multi-line report), and the full report text is still emitted to the shell's output for
  the operator to inspect.
- Given a caller catches `CommandFailedException` and logs only `getMessage()`,
  When compared to current behavior,
  Then the logged text no longer contains the full step-by-step report body.

## 5. Non-functional requirements

- **NFR-1 (Security — secret handling).** No component under `infrastructure/adapter/notion`
  may render the raw Notion token in any string output (logs, exceptions, `toString()`,
  binding-error messages) after this feature ships. [Traces AUD-001.]
- **NFR-2 (Robustness — untrusted external data).** All Notion API response fields consumed by
  `NotionProvisioningAdapter` that can legally be null/empty per the Notion API (data sources,
  search results) must be validated before dereference; failures surface as
  `NotionApiException`, never unchecked runtime exceptions escaping the adapter boundary.
  [Traces AUD-002, AUD-003.]
- **NFR-3 (Test discipline).** Every test whose name or docstring claims to verify Spring
  Shell option parsing, defaulting, required-option enforcement, or exit-code behavior must
  actually exercise that behavior through the shell runtime (`spring-shell-test`), not through
  direct object construction. [Traces cli-wiring AUD-001, AUD-002.]
- **NFR-4 (Backward compatibility).** No change in this feature may alter the successful-path
  observable behavior of `workspace create` or the Notion provisioning adapter for a
  well-formed Notion API response and a correctly-invoked CLI command — this is remediation,
  not a behavior change to the happy path.
- **NFR-5 (No regression).** All currently-passing tests in
  `backend/src/test/java/com/lifeos/infrastructure/adapter/notion/**` and
  `backend/src/test/java/com/lifeos/infrastructure/adapter/cli/**` must continue to pass after
  remediation, except tests explicitly replaced per FR-4 (whose replacements must cover the
  same or greater ground per FR-3).

## 6. Data & entities (conceptual only)

No new domain entities. Concepts touched, all pre-existing:
- **NotionProperties** — configuration value object holding the Notion integration token,
  API version, and root parent page identity; its string representation is the subject of FR-1.
- **ProvisioningReport / ProvisioningStepResult** — application-layer report of a workspace
  provisioning run, with per-step outcome/detail; its rendering and failure-signalling in the
  CLI adapter is the subject of FR-8.
- **NotionDatabaseResponse / NotionDataSourceResponse** — Notion API response DTOs; their
  nullable/absent fields are the untrusted boundary data guarded by FR-2/FR-5.

## 7. Constraints & assumptions

- `[ASSUMPTION]` The masking strategy for FR-1 (e.g., full redaction vs. partial truncation) is
  an implementation choice left to the Architect/SME; this spec requires only that the raw
  token never appears, not a specific mask format.
- `[ASSUMPTION]` The pagination cap value in FR-5 (page count and/or match count ceiling) is an
  implementation choice; this spec requires only that a bound exists and is enforced.
- `[ASSUMPTION]` "Concise failure message" in FR-8 does not require a specific message format;
  it only requires that the exception detail message not be the entire multi-line report.
- This is a remediation feature against existing, already-merged code — no new REST/CLI
  surface, no new configuration keys beyond what FR-1 may require for masking (if any), no new
  external dependencies expected.
- The audits are standalone (no code was modified by the auditor); this spec is the first
  step toward closing them, per the workspace's findings-protocol lifecycle.

### Findings classified in-scope vs. deferred (owner disposition)

| Finding | Severity | Report | Disposition | Rationale |
|---|---|---|---|---|
| AUD-001 | High | notion-adapter | **In scope** (FR-1) | Blocking; owner directive. |
| AUD-002 | Medium | notion-adapter | **In scope** (FR-2) | Owner directive: NPE guards on data-source dereference. |
| AUD-003 | Low | notion-adapter | **In scope** (FR-5) | Cheap, well-scoped DoS/NPE hardening directly adjacent to AUD-002's guard work; low cost to fix alongside it. |
| AUD-004 | Low | notion-adapter | **Deferred** | Defense-in-depth only; SSRF is already mitigated by fixed `baseUrl` + URI-template encoding per the audit itself — no exploitable gap today, and ID-format validation is a design choice better sized as its own follow-up. |
| AUD-005 | Low | notion-adapter | **Deferred** | Pure internal design/coupling concern (global `RestClientCustomizer` scope); no user-facing or security impact until a second `RestClient` bean is added to the app, which does not exist yet. |
| AUD-006 | Low | notion-adapter | **In scope** (FR-6) | Trivial rename, zero risk, prevents a future silent bug. |
| AUD-007 | Low | notion-adapter | **In scope** (FR-7) | Trivial, zero-risk, behavior-preserving constant substitution. |
| cli-wiring AUD-001 | Medium | cli-wiring | **In scope** (FR-4) | Owner directive: replace the tautological/un-exercised test. |
| cli-wiring AUD-002 | Medium | cli-wiring | **In scope** (FR-3, FR-4) | Owner directive: real shell-level integration test. |
| cli-wiring AUD-003 | Low | cli-wiring | **In scope** (FR-8) | Small, directly enables cleaner failure semantics without behavior risk; natural pairing with the exit-code work in FR-3. |
| cli-wiring AUD-004 | Low | cli-wiring | **Deferred** | Root cause (`CreateWorkspaceService.java:92`) is outside the audited target set and outside `infrastructure/adapter/cli`; sanitizing exception details requires an application-layer decision (what is "internal" vs. safe to surface) that this remediation pass should not make unilaterally. Flagged as an open question below. |
| cli-wiring AUD-005 | Low | cli-wiring | **Deferred** | Presentation-mapping nicety (domain enum name leaking into CLI text) with no security/robustness impact; batching it with unrelated rendering changes risks scope creep in a remediation-only pass. |
| Retry-scope `[ASSUMPTION]` (5xx/network not retried) | non-binding | notion-adapter | **Deferred** | Auditor explicitly flags this as a design preference, not an authority-backed requirement; the existing test (`client_mapsGenericErrorStatusToNotionApiExceptionWithCodeAndMessage`) shows the current behavior is intentional. |
| Non-idempotent POST retry note | non-binding | notion-adapter | **Deferred** | Auditor notes this is already safe (429 retry is pre-processing, plus app-layer idempotency); no defect to remediate. |
| Worst-case blocking (~60s backoff) `[ASSUMPTION]` | non-binding | notion-adapter | **Deferred** | Acceptable for current CLI-only usage per the auditor; only relevant if this adapter is later invoked from a web request thread, which is out of scope for this remediation. |

## 8. Out of scope

- Any change to `CreateWorkspaceService`, `ProvisioningReport`, `ProvisioningStepResult`, or
  other application-layer types beyond what FR-8 requires for the CLI-side message change.
- Sanitizing/redesigning how downstream exception messages populate `ProvisioningStepResult.detail()`
  (cli-wiring AUD-004) — deferred pending an application-layer decision (see open questions).
  is best sized as its own follow-up.
- Retry/backoff redesign (5xx retry, jitter) — explicitly non-binding auditor observations, not
  findings.
- Any new product feature, CLI command, or Notion API capability.
- `NotionProvisioningPort`'s 14-method interface segregation (noted as a SOLID observation, not
  a finding) — no ISP refactor in this pass.

## 9. Open questions for the stakeholder

1. **cli-wiring AUD-004** (verbatim downstream exception text surfaced to the CLI) has its root
   cause in `CreateWorkspaceService` (application layer, outside both audits' primary target
   sets). Should this remediation pass include an application-layer sanitization boundary (log
   raw, surface sanitized), or should it be raised as a separate finding/spec scoped to
   `application.usecase.workspace`? This spec defers it; confirm before the Architect scopes
   FR-8's neighboring work.
2. **cli-wiring AUD-005** (domain enum `ProvisionedResourceType` rendered verbatim in CLI
   output) — confirm deferral is acceptable, or should a minimal display-label mapping be
   pulled into this pass since FR-8 already touches `renderReport`?
3. Is a specific token-masking format required for FR-1 (e.g., "last 4 chars visible" vs. full
   redaction), or is any non-reversible masking acceptable, per the `[ASSUMPTION]` above?
