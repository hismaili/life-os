# 05 — Audit Report: Create Dashboard (Phase A — root Notion page)

Stage: Auditor (6/6) · Scope: first real Notion HTTP integration under `backend/src/`
Verdict: **Changes requested — 1 High (blocking) finding.** QA passed on spec conformance; this audit is on engineering correctness/safety and finds one availability defect that must be fixed before merge, plus two Medium hardening items and two Low items.

> **Remediation status (updated 2026-08-05).** All five actionable findings (AUD-01..05) are FIXED and the informational AUD-06 (failsafe) is now wired, so the Testcontainers ITs run under `verify`. Build re-verified: `TESTCONTAINERS_RYUK_DISABLED=true ./mvnw verify` → BUILD SUCCESS, **133 unit tests + 5 integration tests (`*IT`)**, 0 failures (unit was 126; +7 regression tests). Each finding below carries a **Resolution** note. Post-remediation open counts: Critical 0 · High 0 · Medium 0 · Low 0.

## 1. Executive summary

The Dashboard slice is well-layered: `PageShape`/`ParentConstraint` are technology-neutral, the concrete `rootParentPageId` never crosses into `application`/`domain`, the verify→adopt/repair/create→record idempotency algorithm matches the decision table, the Bearer token is confined to a single `Authorization` default header and is provably kept out of exception messages and logs (test `client_neverLeaksTokenInExceptionMessage`), and `NotionApiException` messages are built only from Notion's own status/`code`/`message`. Secrets come from env vars with fail-fast `@NotBlank` validation. No hardcoded production secrets, no noise/AI comments, no injection into the JSON request bodies (Jackson-serialized).

The one material defect is an **availability** gap, not a confidentiality one: the outbound `RestClient` has **no connect/read timeout**, so a hung Notion socket blocks the calling provisioning thread indefinitely — and the bounded-retry loop can compound the hang. A related Medium is the **unbounded `Retry-After` sleep**. Neither is caught by the current tests because `MockRestServiceServer` responds instantly.

### Severity counts

| Severity | At audit | Open after remediation | IDs |
|---|---|---|---|
| Critical | 0 | 0 | — |
| High | 1 | 0 | AUD-01 (fixed) |
| Medium | 2 | 0 | AUD-02, AUD-03 (fixed) |
| Low | 2 | 0 | AUD-04, AUD-05 (fixed) |
| Informational | 1 | 0 | AUD-06 (failsafe wired) |

**Blocking at audit:** AUD-01. **Open blocking after remediation:** 0.

## 2. Findings

### AUD-01 — High — Security/Availability — No connect/read timeout on the Notion `RestClient`
`backend/src/main/java/com/lifeos/infrastructure/adapter/notion/NotionClient.java:22-29`

The `RestClient` is built from the injected builder with only `baseUrl`, headers and a status handler; no `ClientHttpRequestFactory` timeout settings are applied. Spring Boot does **not** configure any default connect/read timeout on the auto-configured `RestClient.Builder`, and the JDK `HttpClient` request factory has no request timeout by default — so a stalled or half-open connection to `api.notion.com` blocks the calling thread forever. Every Dashboard run is synchronous and blocking (`Thread.sleep` retry loop, `executeWithRetry`), and `CreateDashboardService.execute` runs on the request/CLI thread; a single hung call ties up that thread indefinitely with no recovery. This is a real liveness/availability defect and is untested (the `MockRestServiceServer` cases all respond instantly).

Authoritative citation: Spring Boot Reference — *REST Clients* (docs.spring.io/spring-boot/reference/io/rest-client.html): Spring Boot applies no default connect/read timeout; timeouts must be set explicitly on the request factory. OWASP ASVS v4 §V13 (API resource management) — remote calls must bound their wait to prevent resource exhaustion.

Remediation: build the client with an explicit request factory carrying finite connect and read timeouts (e.g. `ClientHttpRequestFactorySettings`/`ClientHttpRequestFactoryBuilder` with a few-second connect and a bounded read timeout), and add a test that a non-responding server surfaces a timeout rather than hanging.

- **Resolution (FIXED):** A `ClientHttpRequestFactory` with finite connect (5s) and read (20s) timeouts is now applied to the Notion `RestClient` via a `RestClientCustomizer` bean (`NotionClientConfiguration`) using the static helper `NotionClient.requestFactory(Duration, Duration)`. (The factory is applied via a customizer rather than inside `NotionClient` so the `MockRestServiceServer`-bound adapter tests still install their mock factory.) New test `NotionClientTest.requestFactory_enforcesReadTimeoutRatherThanHangingForever` points the factory (300ms timeouts) at a black-hole `ServerSocket` and asserts a `ResourceAccessException` within 5s instead of an unbounded hang.

### AUD-02 — Medium — Availability — Unbounded `Retry-After` sleep; only delay-seconds handled
`backend/src/main/java/com/lifeos/infrastructure/adapter/notion/NotionClient.java:88-106`

`parseRetryAfter` accepts any `Long` from the upstream `Retry-After` header with **no upper cap**, and `sleep` then blocks the thread for exactly that many seconds. A misbehaving upstream, proxy, or spoofed edge response returning e.g. `Retry-After: 86400` on a 429/529 makes the provisioning thread sleep for hours — an availability/self-DoS vector layered on top of AUD-01. Separately, per RFC 9110 §10.2.3 `Retry-After` may be an HTTP-date, not only delay-seconds; the HTTP-date form is silently coerced to the 1s fallback (a correctness gap, though the safer of the two failure modes).

Authoritative citation: RFC 9110 §10.2.3 (*Retry-After*) — value is delay-seconds **or** HTTP-date; OWASP ASVS v4 §V13 — bound wait times on remote interactions.

Remediation: clamp the parsed delay to a small maximum (e.g. `min(parsed, MAX_BACKOFF_SECONDS)`) and treat out-of-range/date-form values as the capped default.

- **Resolution (FIXED):** `NotionClient.parseRetryAfter` now clamps to `MAX_BACKOFF_SECONDS = 30`, floors non-positive values to 1s, and returns the 1s fallback for non-numeric/HTTP-date forms. Covered by `NotionClientTest.parseRetryAfter_clampsLargeValuesToMax` / `_keepsSmallValues` / `_fallsBackToOneOnMissingNonNumericOrNonPositive` (the method was made package-private for direct assertion without sleeping).

### AUD-03 — Medium — Security/Robustness — Externally-influenced ids concatenated into the URI template
`backend/src/main/java/com/lifeos/infrastructure/adapter/notion/NotionProvisioningAdapter.java:49,64,76,81` via `NotionClient.get/patch/post` (`backend/.../NotionClient.java:31-49`)

Page ids are concatenated into the path string (`"/pages/" + pageId`, `"/pages/" + pageId + "/move"`) which is then passed to `restClient.get().uri(String)`. Spring treats the single-arg `uri(String)` as a **URI template**: a `{`/`}` in the value throws during expansion, and reserved/space characters are not percent-encoded, so an unexpected `pageId` (sourced from the persisted ledger and, in `findRootByIdentity` results, from Notion) can alter or break the request line. Blast radius is constrained (the fixed `baseUrl` keeps the host as `api.notion.com`, so this is not full SSRF), but untrusted-origin identifiers should never flow unencoded into the request line.

Authoritative citation: Spring Framework Reference — *RestClient / URI handling* (docs.spring.io/spring-framework/reference/integration/rest-clients.html) — pass path values as URI variables so they are encoded; OWASP ASVS v4 §V5.1 (input validation / output encoding for interpreted contexts).

Remediation: use `uri("/pages/{id}", pageId)` / `uri(b -> b.path("/pages/{id}/move").build(pageId))` so Spring percent-encodes the path variable.

- **Resolution (FIXED):** `NotionClient`'s `get`/`post`/`patch` now accept `Object... uriVariables`, and `NotionProvisioningAdapter` passes page ids as URI variables (`"/pages/{id}"`, `"/pages/{id}/move"`) instead of string concatenation, so Spring percent-encodes them. New test `NotionProvisioningAdapterTest.verifyPage_encodesPageIdAsUriVariable` asserts a page id containing a space is requested as `/pages/a%20b`.

### AUD-04 — Low — Persistence/Correctness — Search pagination ignored weakens the idempotency dedup guarantee
`backend/src/main/java/com/lifeos/infrastructure/adapter/notion/NotionProvisioningAdapter.java:86-106`

`findRootByIdentity` reads only the first page of `/v1/search` results and ignores `has_more`/`next_cursor` (present on `NotionSearchResponse` but unused). Both the adoption path and the ">1 match → throw" ambiguity guard therefore reason over a partial result set: an existing matching root page on a later result page would be missed, causing a duplicate `createRootPage` and violating the idempotency invariant ("re-running must reconcile, not duplicate"). This is an explicitly **documented, accepted v0 scope boundary** (tech-spec §5.6 point 6, §10), so it is filed Low/informational — flagged only so the idempotency risk is tracked when Notion scale grows.

Authoritative citation: `CLAUDE.md` — *Idempotency* ("re-running must verify and reconcile … rather than duplicate"); Notion API — *Search* pagination (`has_more`/`next_cursor`).

Remediation: when a future change lifts the single-page limit, traverse `next_cursor` before concluding "not found" / "unique".

- **Resolution (FIXED):** `findRootByIdentity` now traverses `next_cursor`/`has_more`, accumulating matches across all `/v1/search` pages before deciding empty / unique / ambiguous. New tests `findRootByIdentity_followsPaginationAcrossPages` (match on a later page is found) and `findRootByIdentity_throwsOnMatchesSpreadAcrossPages` (duplicates split across pages still trip the `>1 → FAILED` guard).

### AUD-05 — Low — Best practice — Hand-rolled `new ObjectMapper()` instead of the Spring-configured mapper
`backend/src/main/java/com/lifeos/infrastructure/adapter/notion/NotionClient.java:20`

The error-body parser instantiates its own `ObjectMapper`, diverging from the Boot-autoconfigured mapper that deserializes every other Notion DTO on the `retrieve()`/`exchange()` path. Two mappers with potentially different settings deserialize the same API's payloads; the private one also silently swallows parse failures into a synthetic error (`readError`, lines 80-86). Low risk here (the DTOs are `@JsonIgnoreProperties(ignoreUnknown=true)` and simple), but it is an avoidable inconsistency.

Authoritative citation: Spring Boot Reference — *JSON / Jackson* (docs.spring.io/spring-boot/reference/features/json.html) — reuse the auto-configured `ObjectMapper`. Effective Java, Item 17 (favor a single configured instance over ad-hoc construction).

Remediation: inject the Spring-managed `ObjectMapper` into `NotionClient` and reuse it.

- **Resolution (FIXED):** `NotionClient` now takes the Spring-managed `ObjectMapper` as a constructor argument (threaded through `NotionProvisioningAdapter`, which Spring injects with the auto-configured mapper); the `new ObjectMapper()` is removed. Error-body parsing and DTO deserialization now share one configured mapper.

### AUD-06 — Informational — Quality/CI — No `maven-failsafe-plugin`; `*IT` tests never run under `verify`
`backend/pom.xml:114-151`

Already identified by QA (04-qa-report). Restated here only for its quality implication: `CreateDashboardServiceIT` and `CreateWorkspaceIT` are green only when run by explicit `-Dtest=`; the idempotency-convergence and repair-on-rename integration guarantees are therefore not enforced by the default build. Not a new finding.

Authoritative citation: Spring Boot Reference — *Testing / Failsafe* integration-test conventions (docs.spring.io/spring-boot/reference/testing/).

Remediation: add `maven-failsafe-plugin` with `integration-test`/`verify` goals (tracked by QA).

- **Resolution (WIRED):** `maven-failsafe-plugin` is now declared in `backend/pom.xml` with `integration-test` + `verify` goals. `TESTCONTAINERS_RYUK_DISABLED=true ./mvnw verify` now executes both `CreateDashboardServiceIT` (3) and `CreateWorkspaceIT` (2) — confirmed in the failsafe output — so the idempotency-convergence and wiring guarantees are enforced by the default build.

## 3. Principle scorecard

| Principle | Rating | Justification |
|---|---|---|
| SOLID | Good, one caveat | DIP clean (service depends on the `NotionProvisioningPort` interface; adapter constructs `NotionClient` internally). ISP caveat: `NotionProvisioningPort` bundles 15 methods, 11 still `UnsupportedOperationException` — an accepted scope-guard, but the fat port is a latent ISP smell to split as slices land. |
| Clean/Hexagonal architecture | Excellent | No Notion/HTTP type and no concrete `rootParentPageId` leaks past the adapter; `ParentConstraint.ROOT_PARENT` resolved only in `NotionProvisioningAdapter`; DTOs confined to `adapter.notion.dto`. |
| DDD | Good | `PageShape` is a self-validating value object; identity modeled as title+parent; ledger write isolated in `WorkspaceLedgerWriter` with its own `@Transactional`, correctly kept off the HTTP-bound `execute`. |
| Security | Good (post-remediation) | Token confined to one header, never logged/serialized/in exceptions (test-proven); fail-fast `@NotBlank` config; no hardcoded prod secret. The availability gaps (AUD-01/02) and URI hardening (AUD-03) are now fixed: bounded connect/read timeouts, clamped `Retry-After`, and percent-encoded path variables. |
| DRY / YAGNI | Good | `dashboardTitle` is the single source of the identity string reused across all port calls; retry/error mapping centralized in `NotionClient`; no speculative abstraction. |

## 4. Blocking issues

- **AUD-01 (High)** — add finite connect/read timeouts to the Notion `RestClient`; an unbounded outbound call can hang a provisioning thread indefinitely. Must be fixed before merge. — **RESOLVED** (see AUD-01 Resolution).

Recommended (non-blocking) to bundle in the same change: **AUD-02** (cap the `Retry-After` sleep) and **AUD-03** (encode path ids via URI variables) — both **RESOLVED** in the same change.

## 5. Remediation verification (2026-08-05)

All findings remediated in place and the full suite re-run with the ITs now gated by failsafe:

- Build: `TESTCONTAINERS_RYUK_DISABLED=true ./mvnw verify` → **BUILD SUCCESS**, unit `Tests run: 133, Failures: 0` (was 126; +7 regression tests across `NotionClientTest` and `NotionProvisioningAdapterTest`) and failsafe `*IT` `Tests run: 5, Failures: 0` (`CreateDashboardServiceIT` 3 + `CreateWorkspaceIT` 2).
- Fixed: AUD-01 (RestClient timeouts via `RestClientCustomizer`), AUD-02 (clamped `Retry-After`), AUD-03 (URI-variable page ids), AUD-04 (search pagination), AUD-05 (shared `ObjectMapper`). Wired: AUD-06 (`maven-failsafe-plugin`).
- New/changed files: `NotionClient` (timeouts helper, clamp, uriVariables, injected mapper), `NotionClientConfiguration` (new — timeout customizer), `NotionProvisioningAdapter` (URI variables, pagination, mapper param), `NotionClientTest` (new), `NotionProvisioningAdapterTest` (+3 tests), `backend/pom.xml` (failsafe).
- No comment pollution introduced (grep of `infrastructure/adapter/notion` for `//`/block comments returns none).

---
Routing (all closed): AUD-01/02/03/04/05 → Implementer (`NotionClient`/adapter/config) — fixed. AUD-06 → build config — wired. No source files were modified by the audit itself.
