# 05 - Audit Report: Create Workspace

Stage: Auditor (6/6). Lens: security (OWASP), design principles (SOLID / Hexagonal / DDD), best practices (Spring Boot 3.x / Java 21 / JPA). Scope: `backend/src/**`. QA already passed; this audit is orthogonal to "does it work".

Accepted context excluded from findings: the 12 provisioning-step stubs and `NotionProvisioningAdapter` throwing `UnsupportedOperationException` (tech-spec §12); Testcontainers/Podman + `@MockBean` on Boot 3.3.2; deferred REST authn/authz + per-Person token storage (v0 out of scope).

> **Remediation status (updated 2026-08-04).** All findings except the informational, out-of-scope L6 have been fixed and the build re-verified green (`TESTCONTAINERS_RYUK_DISABLED=true ./mvnw verify` → BUILD SUCCESS, **82 tests**, 0 failures — up from 67, the delta being regression tests for these fixes). Each finding below carries a **Resolution** note. Post-remediation open counts: Critical 0 · High 0 · Medium 0 · Low 0 open (5 fixed, L6 deferred by design).

## 1. Executive summary

The implementation is clean, layering is respected, and several controls are correctly in place: parameterized access via Spring Data (no injection surface), `@Version` optimistic locking, `open-in-view: false`, the Notion token sourced from an env var and never logged, and no hardcoded secrets. The orchestrator correctly omits `@Transactional` per ADR-0001.

One High issue must be resolved before merge: internal exception messages are passed through to HTTP clients via the error handler. The remainder are Medium/Low design and best-practice items. **All of these have since been remediated (see per-finding Resolution notes); only the out-of-scope informational L6 remains, deferred with REST authn.**

### Severity counts
| Severity | At audit | Open after remediation |
|----------|----------|------------------------|
| Critical | 0 | 0 |
| High     | 1 | 0 |
| Medium   | 2 | 0 |
| Low      | 6 | 0 (L6 deferred by design) |

Blocking issues (Critical + High) at audit: **1** (H1). Open blocking issues after remediation: **0**.

## 2. Findings

### H1 — Internal error details leaked to HTTP clients
- Severity: High
- Category: Security / Sensitive data exposure (OWASP A09-adjacent, error handling)
- Location: `infrastructure/adapter/web/ApiExceptionHandler.java:36`; source of the tainted data `application/usecase/workspace/CreateWorkspaceService.java:92`
- Description: `handleProvisioningFailed` sets `pd.setProperty("steps", ex.getReport().steps())`, serializing the full application-layer step list to the client. Each `ProvisioningStepResult.detail` for a `FAILED` step is populated from raw `e.getMessage()` (CreateWorkspaceService:92). Once the steps make real Notion/JPA calls, that message can carry Notion API payloads, SQL/constraint text, or other internal state, returned verbatim in a 502 body to an unauthenticated caller. The same passthrough of `ex.getMessage()` occurs in `handleIllegalArgument` (ApiExceptionHandler.java:25).
- Authority: OWASP Error Handling Cheat Sheet — "when an unexpected error occurs then a generic response is returned by the application but the error details are logged server side ... and not returned to the user."
- Remediation: Map steps to a sanitized response shape (type + outcome + a curated, non-sensitive detail); log full detail server-side only.
- **Resolution (FIXED):** `ProvisioningStepResultResponse` no longer carries any free-text `detail` (dropped from the record entirely, on both success and failure paths). `handleProvisioningFailed` now emits only `{type, outcome}` per step and logs the full report (with details) server-side via SLF4J; `handleIllegalArgument` no longer echoes `ex.getMessage()`. Regression test `WorkspaceControllerTest.create_failureResponseNeverLeaksInternalStepDetail` asserts a simulated `PSQLException` detail never appears in the body.

### M1 — No catch-all exception handler; inconsistent/unguarded error contract
- Severity: Medium
- Category: Security / Best practice (error handling, availability)
- Location: `infrastructure/adapter/web/ApiExceptionHandler.java` (no `@ExceptionHandler(Exception.class)`); reachable via `application/usecase/workspace/CreateWorkspaceService.java:44-45`
- Description: Exceptions thrown outside `runStep` are not translated. The find-or-create race at CreateWorkspaceService:44-45 (two concurrent requests for the same `person_id`+`name`) resolves to a `DataIntegrityViolationException` from the unique constraint; `IllegalStateException` from ledger writes is likewise uncovered. These fall through to Boot's default handler, yielding an untyped 500 and an error contract that varies by exception. Whether internals leak then depends entirely on `server.error.include-message`/`include-stacktrace` defaults rather than explicit control.
- Authority: OWASP Error Handling Cheat Sheet — "Unhandled errors can assist an attacker" and responses must "not provide implementation details"; Spring Framework Reference — `@RestControllerAdvice` for centralized handling.
- Remediation: Add a fallback `@ExceptionHandler(Exception.class)` returning a generic `ProblemDetail` (500) with no internals, and translate the duplicate-key race to a deterministic 409/idempotent outcome.
- **Resolution (FIXED):** Added `@ExceptionHandler(Exception.class)` → generic 500 `ProblemDetail` and `@ExceptionHandler(DataIntegrityViolationException.class)` → 409 `ProblemDetail`, both logging server-side and exposing no internals. Covered by `WorkspaceControllerTest.create_returns500ProblemDetailOnUnexpectedError` and `create_returns409ProblemDetailOnDataIntegrityViolation` (each asserts the internal message is absent from the body).

### M2 — Public Lombok `@Builder` bypasses self-validating factory invariants
- Severity: Medium
- Category: Design / DDD (aggregate integrity), Effective Java
- Location: `domain/workspace/Workspace.java:14`; also `domain/task/Task.java:11`, `domain/goal/Goal.java:11`, `domain/project/Project.java:11`, `domain/person/Person.java:10`
- Description: `@Builder` generates a public builder, so callers can do `Workspace.builder().id(null).name("").build()` and mint IDs / assemble invalid state, sidestepping `create(...)` and its invariant checks. CLAUDE.md states the all-args builder is "reserved for repository reconstitution of already-valid state" and "the application layer must never mint IDs or assemble an entity in an invalid state." The generated builder makes that rule unenforceable at compile time.
- Authority: Effective Java (3rd ed.) Item 17 "Minimize mutability" / Item 1 "Consider static factory methods"; CLAUDE.md — Self-validating domain entities.
- Remediation: Restrict builder access (`@Builder(access = AccessLevel.PACKAGE)` or a package-private reconstitution factory) so external construction goes only through `create(...)`.
- **Resolution (FIXED):** All five aggregates now use `@Builder(access = AccessLevel.PRIVATE)`, so the generated builder is unreachable outside the class. `Workspace` adds a public `reconstitute(UUID, UUID, String, List<ProvisionedResource>)` factory (validating non-null id/personId and non-blank name) for repository rehydration; `JpaWorkspaceRepository.toDomain` now calls it instead of the builder. Regression test `WorkspaceTest.builder_isNotPubliclyAccessible` (reflection) plus `reconstitute_*` tests.

### L1 — `@Transactional recordLedger` will be silently bypassed by self-invocation
- Severity: Low
- Category: Best practice / Spring transactions (latent)
- Location: `application/usecase/workspace/CreateDashboardService.java:27`, `application/usecase/task/CreateTasksDatabaseService.java:27` (and the sibling step services)
- Description: `recordLedger(...)` is `@Transactional` and package-private. Package-visible `@Transactional` is honored for class-based proxies since Spring 6, but the intended caller is the same class's `execute()`. When wired, that is self-invocation, which the proxy does not intercept — the ledger write would run with no transaction.
- Authority: Spring Framework Reference — Declarative transactions: "self-invocation ... does not lead to an actual transaction at runtime even if the invoked method is marked with `@Transactional`."
- Remediation: Move the ledger write to a separate injected bean (or call through a self-reference/`AspectJ` mode) so the boundary is proxied.
- **Resolution (FIXED):** Introduced a dedicated `@Component WorkspaceLedgerWriter` with a `@Transactional public void record(UUID, ProvisionedResourceType, String)` method. The 12 duplicated per-service `recordLedger(...)` methods were removed; each step service now depends on `WorkspaceLedgerWriter` and calls it across a proxied bean boundary (also a DRY improvement). The step services no longer inject `WorkspaceRepository` directly.

### L2 — Dead computation on the failure path in the controller
- Severity: Low
- Category: Best practice / dead code
- Location: `infrastructure/adapter/web/WorkspaceController.java:28-31`
- Description: `ProvisioningReportResponse body = toResponse(report)` is computed before the `report.failed()` check; on the failure branch `body` is discarded when `WorkspaceProvisioningFailedException` is thrown. Wasted work and misleading intent.
- Authority: Effective Java (3rd ed.) Item 57 "Minimize the scope of local variables."
- Remediation: Move the `failed()` check above `toResponse(...)` so the body is built only when returned.
- **Resolution (FIXED):** `WorkspaceController.create` now performs the `report.failed()` check first (throwing before any response is built) and calls `toResponse(report)` only on the success branch.

### L3 — Primitive-obsession: `Person.email` is an unvalidated `String`
- Severity: Low
- Category: Design / DDD (primitive obsession)
- Location: `domain/person/Person.java:12` and factory `Person.java:23` (email accepted, never validated)
- Description: CLAUDE.md requires "meaningful strings that carry rules (email, URL)" to be modeled as value objects. `email` is a bare `String` with no format enforcement anywhere.
- Authority: CLAUDE.md — "No primitive obsession"; Effective Java Item 17.
- Remediation: Introduce an `Email` value object validating format in its constructor.
- **Resolution (FIXED):** Added `domain/person/Email` — a self-validating record (canonical constructor rejects null/blank and format-invalid values, trims whitespace) with a static `of(String)` factory. `Person.email` is now typed `Email`; `Person.create` wraps a provided address (null remains allowed) via `Email.of`. Covered by new `EmailTest` and `PersonTest`.

### L4 — Collection `clear()` + re-add with `orphanRemoval=true` (reconciliation pitfall)
- Severity: Low
- Category: Best practice / JPA
- Location: `infrastructure/adapter/persistence/JpaWorkspaceRepository.java:40-48` (with `WorkspaceJpaEntity.java:38`)
- Description: `save` clears the managed `resources` collection and re-adds instances. With `orphanRemoval=true`, clearing schedules orphan deletion; re-adding reused instances relies on Hibernate reconciling delete-then-reinsert within one flush, which is fragile and can produce redundant DELETE/INSERT or "deleted instance passed to merge" edge cases.
- Authority: Jakarta Persistence 3.1 spec §3.2.2 (orphan removal semantics); Spring Data JPA reference — aggregate persistence.
- Remediation: Reconcile in place — update matched rows, remove only the difference, add only new ones — instead of clear-and-repopulate.
- **Resolution (FIXED):** `JpaWorkspaceRepository.save` no longer clears the collection. It removes only entries whose type is absent from the desired set (`removeIf`), updates matched rows in place, and adds only genuinely new ones. The `save_upsertsResourceOfSameTypeOnReRecord` test continues to pass under this reconciliation.

### L5 — `GenerationType.IDENTITY` disables JDBC batch inserts
- Severity: Low
- Category: Best practice / JPA performance
- Location: `infrastructure/adapter/persistence/ProvisionedResourceJpaEntity.java:32`
- Description: `IDENTITY` forces Hibernate to execute each INSERT immediately to obtain the key, disabling insert batching for the up-to-~14 ledger rows written per provisioning run.
- Authority: Hibernate ORM User Guide — identifier generation / batching implications.
- Remediation: Prefer a `SEQUENCE`-backed generator (Postgres) if batching becomes relevant.
- **Resolution (FIXED):** `ProvisionedResourceJpaEntity.id` now uses `GenerationType.SEQUENCE` with a `@SequenceGenerator` (`provisioned_resource_id_seq`, `allocationSize = 50`); the Flyway `V1` migration creates a matching `CREATE SEQUENCE ... INCREMENT BY 50` and the column is plain `BIGINT`. Note: switching away from `IDENTITY` defers inserts to flush time; the `findByPersonIdAndName_doesNotNPlusOneOnLedger` test was made timing-independent by flushing before clearing Hibernate statistics, so it now isolates the `find` query as intended.

### L6 — Broken-access-control surface: client-supplied `personId`, no ownership check (informational)
- Severity: Low (informational — tied to accepted deferral of REST authn)
- Category: Security / OWASP A01 Broken Access Control
- Location: `infrastructure/adapter/web/CreateWorkspaceRequest.java:10`, consumed at `WorkspaceController.java:26`
- Description: `personId` arrives in the request body and is used as-is; any caller can provision a workspace for any person. Noted informationally because REST authn/authz is explicitly out of v0 scope; it becomes a real finding the moment the endpoint is exposed.
- Authority: OWASP Top 10 A01:2021 Broken Access Control.
- Remediation: When authn is added, derive the owner from the authenticated principal, not the request body (or enforce an ownership check).
- **Resolution (DEFERRED — by design):** Not fixed in this pass. REST authn/authz is explicitly out of v0 scope (resolved OQ-4/OQ-7; see `02-open-questions.md` deferred item 2). Tracked so that when the endpoint is secured, `personId` is derived from the authenticated principal rather than the request body. No code change made.

## 3. Principle scorecard

| Principle | Rating | Justification |
|-----------|--------|---------------|
| SOLID | Good | Use case interface + `@Service` impl throughout; deps on ports not adapters. Minor: orchestrator hard-wires 12 concrete use cases (acceptable composition root). |
| Clean / Hexagonal Architecture | Good | Domain has zero framework imports; ports in application/domain; adapters implement them; dependency direction respected. |
| DDD | Good (post-remediation) | `Workspace` is a rich, immutable aggregate root referencing others by `UUID`; ledger integrity enforced in `record(...)`. Builders locked to private + `reconstitute(...)` factory (M2 fixed) and `Person.email` is now an `Email` VO (L3 fixed). |
| Security | Good (post-remediation) | No injection, no hardcoded secrets, token via env var and never logged, `@Version` present. Error responses sanitized and logged server-side; catch-all + 409 handlers added (H1, M1 fixed). Remaining item L6 (endpoint authn) is deferred by design. |
| DRY / YAGNI | Good | Little duplication; step services share a consistent shape. Minor dead computation (L2). |

## 4. Blocking issues (must fix before merge)

- **H1** — Stop returning internal `ProvisioningStepResult`/`getMessage()` detail to HTTP clients (`ApiExceptionHandler.java:36`, `:25`; source `CreateWorkspaceService.java:92`). Map to a sanitized response and log internals server-side only. — **RESOLVED** (see H1 Resolution).

Recommended (non-blocking but advised alongside H1): M1 (catch-all handler + duplicate-key race) and M2 (lock down the domain builders). — **both RESOLVED.**

## 5. Remediation verification (2026-08-04)

All findings except the out-of-scope informational L6 were remediated in place and the full suite re-run:

- Build: `TESTCONTAINERS_RYUK_DISABLED=true ./mvnw verify` → **BUILD SUCCESS**, `Tests run: 82, Failures: 0, Errors: 0, Skipped: 0` (67 → 82; +15 regression tests: web leak/409/500, `Workspace.reconstitute`/builder-access, `Email`/`Person`).
- No comment pollution introduced (grep of `src/main` for `//` and block comments returns none).
- Fixed: H1, M1, M2, L1, L2, L3, L4, L5. Deferred by design: L6 (REST authn/authz, out of v0 scope).
- New/changed types: `WorkspaceLedgerWriter` (new), `domain/person/Email` (new), `Workspace.reconstitute` (new); private builders on `Workspace`/`Task`/`Goal`/`Project`/`Person`; sanitized `ProvisioningStepResultResponse` (dropped `detail`); expanded `ApiExceptionHandler`; in-place ledger reconciliation and `SEQUENCE` id generation in persistence.
