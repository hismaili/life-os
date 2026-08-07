# 04 — QA Report: Create Dashboard (Phase A — root Notion page)

Owner (QA stage): pipeline automation
Inputs verified: `docs/pipeline/create-dashboard/01-spec.md`, `02-architecture.md`, `adr/ADR-0001..0004`, `03-tech-spec.md`, and `backend/src/main/java/com/lifeos/{application/port,application/usecase/workspace,infrastructure/adapter/notion}` + corresponding `backend/src/test/java` classes.

## 1. Verdict

**PASS** — all 12 FRs and all applicable NFRs are met with test evidence; the implementation is a faithful, mechanical realization of `03-tech-spec.md` and conforms to `02-architecture.md`/ADR-0001..0004. Zero violations found. The one pre-existing condition flagged here (no `maven-failsafe-plugin`) has since been resolved along with the audit fixes — the `*IT` tests now run under `verify` (see §7 update and §9). Verdict re-confirmed after remediation.

## 2. Test run

Commands run from `backend/`:

```
TESTCONTAINERS_RYUK_DISABLED=true ./mvnw verify
```
Result: **BUILD SUCCESS**. `Tests run: 126, Failures: 0, Errors: 0, Skipped: 0`. Notable Dashboard-related suites in this run:
- `CreateDashboardServiceTest` — 14/14 passed
- `NotionProvisioningAdapterTest` — 21/21 passed
- `NotionPropertiesTest` — 4/4 passed
- `PageShapeTest` — 6/6 passed (spec's §9.4 asked for ≥3 cases; implementer added parameterized blank-title cases — acceptable, not over-build)

`*IT.java` classes (`CreateDashboardServiceIT`, pre-existing `CreateWorkspaceIT`) do **not** appear anywhere in the `./mvnw verify` surefire output — confirmed by grepping the full log for `Tests run:` lines; only unit-tier classes are listed, and no failsafe execution phase runs. This confirms `pom.xml` has no `maven-failsafe-plugin` (`grep -n "failsafe" pom.xml` → no match).

```
TESTCONTAINERS_RYUK_DISABLED=true ./mvnw test -Dtest=CreateDashboardServiceIT,CreateWorkspaceIT
```
Result: **BUILD SUCCESS**. `Tests run: 5, Failures: 0, Errors: 0, Skipped: 0` — `CreateDashboardServiceIT` 3/3, `CreateWorkspaceIT` 2/2, both green when invoked explicitly (using Testcontainers-Postgres via Podman, Ryuk disabled).

No test failures anywhere. No compilation warnings observed that indicate incomplete work.

## 3. Acceptance criteria matrix

| Req | Verdict | Evidence |
|---|---|---|
| FR-1 (input contract) | PASS | `CreateDashboardUseCase.execute(UUID)` signature unchanged; `CreateDashboardService.java:31` takes only `workspaceId`. Test: `CreateDashboardServiceTest` all methods call `service.execute(id)` with no extra params. |
| FR-2 (workspace not found) | PASS | `CreateDashboardService.java:32-33` throws `IllegalStateException` before any `notion.*` call. Test: `execute_throwsWhenWorkspaceNotFound` (`CreateDashboardServiceTest.java:62-73`) asserts message + `verifyNoInteractions(notion)`. |
| FR-3 (first-time creation) | PASS | Cold path, `CreateDashboardService.java:70-80`: `findRootByIdentity` empty → `createRootPage` → `ledger.record` → `CREATED`. Test: `execute_createsWhenNoLedgerAndNoOrphan` (`:75-90`). |
| FR-4 (pure reconcile) | PASS | Warm path `PRESENT_MATCHING` → no write, `RECONCILED` (`CreateDashboardService.java:51`). Test: `execute_reconcilesWhenLedgerPresentAndMatching` (`:92-105`) asserts `never()` on create/repair/find and `verifyNoInteractions(ledger)`. |
| FR-5a (repair after deletion) | PASS | Warm `ABSENT` → adopt-or-create → `REPAIRED` (`:57-66`). Tests: `execute_reCreatesWhenLedgerPresentButPageDeletedAndNoOrphanFound` (`:125-138`), `execute_readoptsWhenLedgerPresentButPageDeletedAndOrphanFound` (`:141-153`). |
| FR-5b (repair after drift) | PASS | Warm `PRESENT_DRIFTED` → `repairPage` + `record` → `REPAIRED` (`:52-56`). Test: `execute_repairsWhenLedgerPresentAndDrifted` (`:108-122`). |
| FR-6 (verify-before-trust) | PASS | Every warm-path branch calls `notion.verifyPage` before any `RECONCILED`/other outcome (`:48`); cold path calls `findRootByIdentity` before deciding (`:71`). Tests above assert the call ordering via mock stubbing/verification. |
| FR-7 (orphan adoption) | PASS | Cold path `findRootByIdentity` present → adopt, `RECONCILED`, never `CREATED` (`:77-79`). Test: `execute_adoptsOrphanWhenNoLedgerAndMatching` (`:156-168`) asserts `never()` on create/repair/verify and outcome `RECONCILED`. |
| FR-8 (ledger write path) | PASS | Every write path calls `ledger.record(workspaceId, DASHBOARD, id)` (`WorkspaceLedgerWriter.record`, `WorkspaceLedgerWriter.java:18-22`, `@Transactional`, unchanged from Create Workspace). No other write path exists in `CreateDashboardService`. |
| FR-9 (result contract) | PASS | Every branch returns `ProvisioningStepResult(DASHBOARD, outcome, detail)`; no path builds `FAILED` inside the service — exceptions propagate unmodified (`:30-80`, no try/catch around `notion.*`/`ledger.record`). Tests: `execute_propagatesAmbiguousMatchFailureOnColdPath`/`OnWarmAbsentPath`, `execute_propagatesNotionFailureFromVerifyWithoutWritingLedger`, `execute_propagatesNotionFailureFromCreateWithoutWritingLedger` (`:171-218`) all assert `isSameAs(...)` propagation. |
| FR-10 (no partial ledger write) | PASS | `ledger.record` is only ever called after a successful Notion write/verify in the same branch; on any `notion.*` exception, `ledger` is never touched (`verifyNoInteractions(ledger)` in the 4 failure tests above). Notion-before-ledger ordering matches architecture §4.1 note. |
| FR-11 (idempotent convergence) | PASS | IT: `execute_convergesToOneRowAcrossReruns` (`CreateDashboardServiceIT.java:82-92`) runs `execute` twice, asserts exactly one `DASHBOARD` resource row and second outcome `RECONCILED`. `execute_reachesRepairedOutcomeWhenFakeSimulatesExternalRename` (`:94-108`) additionally proves the id is stable across a repair. Confirmed green via explicit IT run (§2). |
| FR-12 (scope boundary) | PASS | Test `execute_neverInvokesDatabaseOrRelationPortMethods` (`CreateDashboardServiceTest.java:220-238`) asserts `never()` on `createDatabase`/`ensureRelation`/`ensureRollup`/`ensureFormula`/`hasSampleRecords`/`insertSampleRecords`/`verify`/`findChildByIdentity`/`repairShape`. |
| NFR-1 (strict idempotency) | PASS | Same evidence as FR-6/FR-11; live verification consulted on every path, no ledger-only trust. |
| NFR-2 (resilience) | PASS | Notion-write-before-ledger-write ordering + no compensating rollback (`CreateDashboardService.java` all branches); next-run re-adoption proven by IT `execute_reachesRepairedOutcomeWhenFakeSimulatesExternalRename`. |
| NFR-3 (testability) | PASS | `CreateDashboardServiceTest` is plain Mockito (`@ExtendWith(MockitoExtension.class)`, no Spring context) against 3 mocked collaborators; zero live Notion calls anywhere in unit/adapter/IT tiers (fake port / `MockRestServiceServer`). |
| NFR-4 (no silent no-op / cutover honesty) | PASS | `CreateDashboardService.execute` no longer throws `UnsupportedOperationException`; the four page-slice adapter methods are real (`NotionProvisioningAdapter.java:38-106`); all seven other port methods (`verify`, `findChildByIdentity`, `createDatabase`, `repairShape`, `ensureRelation`, `ensureRollup`, `ensureFormula`, `hasSampleRecords`, `insertSampleRecords`) keep verbatim `UnsupportedOperationException` bodies (`:108-151`), matching tech-spec §5.2 byte-for-byte. See §5 for explicit no-over-build confirmation. |
| NFR-5 (observability) | PASS | `CreateDashboardService.java:42-43,49,72` logs `workspaceId`, prior ledger id, `VerificationResult`/found-state, and outcome via `@Slf4j`; no token/raw body logged anywhere in this class or the adapter. |
| NFR-6 (token never leaked) | PASS | `NotionClient` is the sole reader of `properties.token()` (`NotionClient.java:25`); `NotionApiException` messages built only from status + Notion's own `code`/`message` (`NotionClient.java:76-77`, `NotionProvisioningAdapter.java:102-103`). Test: `client_neverLeaksTokenInExceptionMessage` (`NotionProvisioningAdapterTest.java:296-310`) asserts message excludes the literal token and `"Bearer"`. `findRootByIdentity_throwsOnMultipleMatches` also asserts `hasMessageNotContaining(TOKEN)` (`:259-261`). |
| NFR-7 (performance) | PASS | Adapter methods issue only the calls specified in tech-spec §5 (create=1, verify=1, repair=1–3, find=1); no polling loops. The tech-spec's own §11 note (extra `GET` inside `repairPage`) is implemented exactly as specified (`NotionProvisioningAdapter.java:63-83`) — not a deviation, a pre-acknowledged trade-off. |
| NFR-8 (rate-limit awareness) | PASS | `NotionClient.executeWithRetry`/`handleError` honour `Retry-After` on 429/529, `MAX_ATTEMPTS = 3` (`NotionClient.java:17,52-67,69-74`). Tests: `client_retriesOn429ThenSucceeds`, `client_failsAfterExhaustingBoundedRetries` (`NotionProvisioningAdapterTest.java:266-294`). |

All 12 FRs and all 8 NFRs: PASS. 0 FAIL.

## 4. Design conformance

Checked against `02-architecture.md` §§3–6 and ADR-0001..0004; no deviations found.

- **Layering** — `CreateDashboardService` (application) depends only on `NotionProvisioningPort`, `WorkspaceRepository`, `WorkspaceLedgerWriter` — no import of `infrastructure.*` or Notion/HTTP types (`grep` for `org.springframework.web.client`/`RestClient`/`HttpStatus` under `application/` returned nothing). `ParentConstraint.ROOT_PARENT` is resolved to the concrete `rootParentPageId` only inside `NotionProvisioningAdapter` (`NotionProvisioningAdapter.java:41,53,79,94`) — the concrete Notion id never appears in `application`, matching architecture §5.4.
- **Transport (ADR-0001)** — `NotionClient` wraps an injected `RestClient.Builder`, base URL `https://api.notion.com/v1`, `Authorization: Bearer` + `Notion-Version` default headers (`NotionClient.java:22-29`), matching the ADR exactly. No Notion SDK dependency added (`pom.xml` unchanged in that respect).
- **Identity/verification (ADR-0002)** — `verifyPage` (`GET /v1/pages/{id}`) for the warm path, `findRootByIdentity` (`POST /v1/search`, filtered client-side by exact title + parent) for adoption; `> 1` match throws `NotionApiException` (`NotionProvisioningAdapter.java:98-105`) rather than adopting arbitrarily — matches ADR-0002 exactly.
- **Outcome semantics (ADR-0004)** — decision table implemented row-for-row in `CreateDashboardService` (verified against tech-spec §3.4 table 1:1 in §3 above); adoption is never `CREATED`.
- **Port additivity** — `NotionProvisioningPort` (`NotionProvisioningPort.java`) shows `createRootPage` refined to `PageShape` and three new methods (`verifyPage`/`repairPage`/`findRootByIdentity`) added; every other method signature is byte-for-byte unchanged from the pre-existing contract (`verify`, `findChildByIdentity`, `createDatabase`, `repairShape`, `ensureRelation`, `ensureRollup`, `ensureFormula`, `hasSampleRecords`, `insertSampleRecords`). `grep -rn "createRootPage("` across `src/main` confirms `CreateDashboardService` is the only production caller — no blast radius beyond this branch.
- **`CreateWorkspaceService` untouched** — its orchestration logic, `runStep`/`runOrBlock` mapping, and the seven database-step calls are unmodified; `createDashboard.execute(...)` is invoked exactly as before (`CreateWorkspaceService.java:49`).
- **Transaction boundary (architecture §4.4)** — `CreateDashboardService` carries no `@Transactional` (class or method); pinned by reflection test `execute_isNotAnnotatedTransactional` (`CreateDashboardServiceTest.java:263-268`). `WorkspaceLedgerWriter.record` remains the sole `@Transactional` write path (`WorkspaceLedgerWriter.java:18`).
- **`NotionProperties` fail-fast (§6)** — `@ConfigurationProperties(prefix="notion") @Validated` record with `@NotBlank token/version/rootParentPageId` (`NotionProperties.java`); 4 `ApplicationContextRunner` tests confirm startup failure for each blank field and success when all present (`NotionPropertiesTest.java`).
- **Token never leaked** — confirmed structurally and by test (§3, NFR-6 row).
- **`> 1` identity match → `NotionApiException` → step `FAILED`** — the adapter throws (`NotionProvisioningAdapter.java:101-104`); `CreateDashboardService` does not catch it (propagates unmodified); `CreateWorkspaceService.runStep` (`CreateWorkspaceService.java:88-94`) catches `Exception` generically and maps to `FAILED` with `e.getMessage()` — this is pre-existing, unmodified code, correctly relied upon here.

No deviations from `02-architecture.md`/ADRs found.

## 5. Stub-cutover honesty check

- `CreateDashboardService.execute` no longer throws `UnsupportedOperationException` — real algorithm present (`CreateDashboardService.java:30-80`).
- Adapter page slice (`createRootPage`/`verifyPage`/`repairPage`/`findRootByIdentity`) is genuinely implemented against `NotionClient`/HTTP, verified by 21 `MockRestServiceServer` contract tests.
- Every other `NotionProvisioningAdapter` method (`verify`, `findChildByIdentity`, `createDatabase`, `repairShape`, `ensureRelation`, `ensureRollup`, `ensureFormula`, `hasSampleRecords`, `insertSampleRecords`) retains its original `throw new UnsupportedOperationException(...)` body verbatim (`NotionProvisioningAdapter.java:108-151`) — confirmed by direct read, matching tech-spec §5.2/§10 word-for-word (same message strings as the pre-existing stub).
- No over-build: no Dashboard body/links/blocks (`createRootPage` sends `title` only, `NotionProvisioningAdapter.java:39-45`), no OAuth/per-Person token code, no `WORKSPACE_ROOT` `ParentConstraint` value (`ParentConstraint.java` has exactly one enum constant), no `/v1/search` pagination traversal, no changes to `WorkspaceController`/`WorkspaceCommands`/`ApiExceptionHandler`/`domain.workspace`/Flyway migrations (confirmed: no such files touched; `domain/` has zero `Notion`-referencing files per grep).

Stub cutover is honest and matches the "do NOT build" list in `03-tech-spec.md` §10.

## 6. Coverage gaps

None found. Every FR/NFR in §3 has at least one directly-cited test. The tech-spec's full test plan (§9.1 fourteen service tests, §9.2 four properties tests, §9.3 twenty adapter tests, §9.4 PageShape tests, §9.5 three IT scenarios) is present in the codebase with matching method names/assertions, and all pass.

## 7. The maven-failsafe gap — explicit finding (RESOLVED 2026-08-05)

> **Update:** this gap has since been closed. `maven-failsafe-plugin` (`integration-test` + `verify` goals) is now declared in `backend/pom.xml`, and `TESTCONTAINERS_RYUK_DISABLED=true ./mvnw verify` now runs both `CreateDashboardServiceIT` (3) and `CreateWorkspaceIT` (2) automatically — 5/5 green in the failsafe phase. The original finding, as reported at QA time, is preserved below for the record.

**Confirmed (at QA time)**: `pom.xml` had no `maven-failsafe-plugin` declaration (`grep -n "failsafe" pom.xml` → no matches). `./mvnw verify`'s surefire output listed only unit-tier test classes; `CreateDashboardServiceIT` and the pre-existing `CreateWorkspaceIT` did not appear anywhere in that run's `Tests run:` lines. Running `./mvnw test -Dtest=CreateDashboardServiceIT,CreateWorkspaceIT` explicitly succeeded: 5/5 tests passed (3 + 2), confirming the Testcontainers-Postgres wiring and the FR-11 multi-run convergence scenario are correct when actually exercised — they were simply never invoked by the standard `verify`/CI path at that time.

**Is this a blocking issue for this feature?** No. This gap **predates** the Create Dashboard branch (it already applied to `CreateWorkspaceIT`) and the tech spec explicitly scopes it out ("this predates this change and was left as-is (out of this spec's scope)," `03-tech-spec.md` §"Implementation notes," last line) while requiring the ITs be verified green manually — which they are. All FR/NFR acceptance criteria that matter for correctness (FR-1..FR-12, NFR-1..NFR-8) are independently covered by the unit tier (`CreateDashboardServiceTest`, 14 tests, runs under `verify`) and the adapter contract tier (`NotionProvisioningAdapterTest`, 21 tests, runs under `verify`), neither of which depends on failsafe. The IT tier is a wiring/convergence *sanity check*, not the sole evidence for any single acceptance criterion.

**Recommendation** (routed to a future Implementer/DevOps task, not this branch): add `maven-failsafe-plugin` bound to the `verify` phase so `*IT.java` classes run automatically in CI, closing the gap for both `CreateWorkspaceIT` and `CreateDashboardServiceIT` going forward. This is a repo-hygiene/CI-completeness item, not a defect in the Create Dashboard implementation itself.

## 8. Violations

None. Zero violations found in this QA pass.

## 9. Post-audit re-verification (2026-08-05)

After this PASS, the Auditor's findings (`05-audit-report.md`) were remediated and the suite re-run. QA verdict is unchanged — **PASS** — and strengthened:

- Build: `TESTCONTAINERS_RYUK_DISABLED=true ./mvnw verify` → **BUILD SUCCESS**, unit `Tests run: 133, Failures: 0` (was 126; +7 regression tests) and failsafe `*IT` `Tests run: 5, Failures: 0` — the ITs are now part of the gated build (§7 update).
- The remediation does not alter any acceptance-criteria verdict above; it hardens the HTTP layer: bounded connect/read timeouts (AUD-01), clamped `Retry-After` (AUD-02), percent-encoded URI path variables (AUD-03), search pagination for the adoption/dedup guarantee (AUD-04), and a shared `ObjectMapper` (AUD-05).
- Line references in §3–§5 predate the remediation and may be off by a few lines in `NotionClient`/`NotionProvisioningAdapter`. The count "21 `MockRestServiceServer` contract tests" in §5 is now 24 (three added for AUD-03/04).
