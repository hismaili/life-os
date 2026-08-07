# 04 — QA Report: Create Projects Database

Verdict owner: QA stage
Input: `01-spec.md`, `02-architecture.md` (+ ADR-0005..0008), `03-tech-spec.md`, `00-preflight.md`, code under `backend/src/main/java/com/lifeos/` and `backend/src/test/java/com/lifeos/`.

## 1. Verdict

**PASS.** Every FR (1–14) and NFR (1–10) is implemented and covered by a correctly-asserting test. The independently-run build is green at both tiers, the four adapter database-slice methods are genuinely implemented, every other `NotionProvisioningAdapter` stub still throws `UnsupportedOperationException`, and no §10 "do-NOT-build" item was built. Zero violations.

## 2. Test run (independently executed, not trusted from tech-spec §"Verification")

Commands run verbatim from `00-preflight.md`, from `backend/`:

```bash
./mvnw test
```
```bash
export DOCKER_HOST="unix://$(podman machine inspect --format '{{.ConnectionInfo.PodmanSocket.Path}}')"
export TESTCONTAINERS_RYUK_DISABLED=true
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
./mvnw verify
```

- Unit + slice tier (`./mvnw test`): **`Tests run: 190, Failures: 0, Errors: 0, Skipped: 0`** — BUILD SUCCESS.
- Full `./mvnw verify` (unit tier + Failsafe `*IT` tier, Podman-backed Testcontainers):
  - Unit tier (surefire, inside the `verify` run): `Tests run: 190, Failures: 0, Errors: 0, Skipped: 0`.
  - Failsafe `*IT` tier: **`Tests run: 9, Failures: 0, Errors: 0, Skipped: 0`** — `CreateDashboardServiceIT` (3), `CreateProjectsDatabaseServiceIT` (4), `CreateWorkspaceIT` (2).
  - `[INFO] Running com.lifeos.application.usecase.project.CreateProjectsDatabaseServiceIT` / `[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0` confirmed in the Failsafe output — the class genuinely ran under `verify` (not skipped, not silently absorbed into the unit tier).
  - Overall: BUILD SUCCESS.

These numbers match the Implementer's self-reported numbers in `03-tech-spec.md` §"Verification" (190 unit, 9 IT) — independently reproduced, not merely trusted.

## 3. Acceptance criteria matrix

| Criterion | Verdict | Evidence |
|---|---|---|
| FR-1 (input contract, no extra param) | PASS | `CreateProjectsDatabaseUseCase.execute(UUID)` unchanged; `CreateProjectsDatabaseServiceTest#execute_createsWhenColdAndNoOrphan` (`CreateProjectsDatabaseServiceTest.java:88-106`) invokes with only `workspaceId`. |
| FR-2 (workspace not found) | PASS | `CreateProjectsDatabaseService.java:41-42`; test `execute_throwsWhenWorkspaceNotFound` (`CreateProjectsDatabaseServiceTest.java:61-72`) — asserts message + `verifyNoInteractions(notion)`/`verifyNoInteractions(ledger)`. |
| FR-3 (missing Dashboard precondition) | PASS | `CreateProjectsDatabaseService.java:44-46`; test `execute_throwsWhenNoDashboardLedgerEntry` (`CreateProjectsDatabaseServiceTest.java:74-86`) — no Notion call. |
| FR-4 (first-time creation) | PASS | `CreateProjectsDatabaseService.java:85-92` (cold path, empty `findChildByIdentity` → `createDatabase` → `record` → `CREATED`); test `execute_createsWhenColdAndNoOrphan` (`CreateProjectsDatabaseServiceTest.java:88-106`) asserts the 4-property spec (Name/Description/Status/Due Date) and Status options = enum display names, `outcome=CREATED`. IT: `CreateProjectsDatabaseServiceIT#execute_persistsProjectsDbLedgerRowOnFirstRun` (`CreateProjectsDatabaseServiceIT.java:85-94`). |
| FR-5 (pure reconcile) | PASS | `CreateProjectsDatabaseService.java:66` (`PRESENT_MATCHING → RECONCILED`, no write); test `execute_reconcilesWhenWarmAndMatching` (`CreateProjectsDatabaseServiceTest.java:149-162`) asserts `never()` on `createDatabase`/`repairShape`/`findChildByIdentity` and `verifyNoInteractions(ledger)`. |
| FR-6a (repair after out-of-band deletion) | PASS | `CreateProjectsDatabaseService.java:72-81` (`ABSENT` → adopt-or-create → `REPAIRED`); tests `execute_reAdoptsWhenWarmAndDeletedAndOrphanFound`/`execute_reCreatesWhenWarmAndDeletedAndNoOrphanFound` (`CreateProjectsDatabaseServiceTest.java:177-204`). |
| FR-6b (repair after drift) | PASS | `CreateProjectsDatabaseService.java:67-71`; test `execute_repairsWhenWarmAndDrifted` (`CreateProjectsDatabaseServiceTest.java:164-175`); non-destructive repair proven at adapter level by `NotionProvisioningAdapterDatabaseTest#repairShape_addsMissingPropertyOnDataSource_neverSendsNull` (lines 262-282) and `#repairShape_batchesMultipleMissingPropertiesInOnePatch` (lines 285-303). |
| FR-7 (verify-before-trust) | PASS | Every warm-path branch calls `notion.verify(...)` before any `RECONCILED`/`REPAIRED` decision (`CreateProjectsDatabaseService.java:63`); test `execute_reconcilesWhenWarmAndMatching` confirms `verify` was the sole interaction driving the outcome. |
| FR-8 (orphan adoption) | PASS | `CreateProjectsDatabaseService.java:85-111` (cold path calls `findChildByIdentity` before any `createDatabase`); tests `execute_adoptsWhenColdAndOrphanMatches` (RECONCILED) / `execute_adoptsAndRepairsWhenColdAndOrphanDrifted` (REPAIRED) — never `CREATED` on the adoption path (`CreateProjectsDatabaseServiceTest.java:108-135`). |
| FR-9 (fail loudly on >1 match) | PASS | Adapter: `NotionProvisioningAdapter.java:175-183` (`findChildByIdentity`, `matches.size() > 1 → NotionApiException`), covered by `NotionProvisioningAdapterDatabaseTest#findChildByIdentity_throwsOnMoreThanOneMatch` (lines 205-220, asserts message contains "2" and not the token). Service-level propagation: `execute_propagatesAmbiguousMatchFailureOnColdPath` / `execute_propagatesAmbiguousMatchFailureOnWarmAbsentPath` (`CreateProjectsDatabaseServiceTest.java:137-147`, `206-217`) — asserts `verifyNoInteractions(ledger)`. |
| FR-10 (ledger write path) | PASS | All write branches call `ledger.record(workspaceId, PROJECTS_DB, id)` (`CreateProjectsDatabaseService.java:69,75,79,90,98,103,108`); `WorkspaceLedgerWriter` unchanged, its own `@Transactional` unit (unmodified in this branch). |
| FR-11 (result contract) | PASS | Every success branch returns `ProvisioningStepResult(PROJECTS_DB, {CREATED,RECONCILED,REPAIRED}, detail)`; failures are propagated raw, never caught (no `catch` block anywhere in `CreateProjectsDatabaseService.java`). Tests `execute_propagatesNotionFailureFromVerifyWithoutWritingLedger` / `...FromCreateWithoutWritingLedger` (`CreateProjectsDatabaseServiceTest.java:219-242`) assert `isSameAs(failure)` (exception not wrapped/swallowed). |
| FR-12 (no partial ledger write on failure) | PASS | Notion-write-before-ledger-write ordering throughout `CreateProjectsDatabaseService.java`; tests above (12/13 in tech-spec §9.1 numbering) assert `verifyNoInteractions(ledger)` on every Notion-failure path. |
| FR-13 (idempotent convergence, 3 runs) | PASS | `CreateProjectsDatabaseServiceIT#execute_convergesToOneRowAcrossThreeReruns` (`CreateProjectsDatabaseServiceIT.java:96-110`) — runs `execute` 3×, asserts exactly one `PROJECTS_DB` row and the same `notionId` across all three; independently re-run and green (§2). |
| FR-14 (scope boundary — no relation/rollup/formula/sample/page calls; no relation property in schema) | PASS | Test `execute_neverInvokesRelationRollupFormulaOrSampleOrPageMethods` (`CreateProjectsDatabaseServiceTest.java:244-262`) — `verify(notion, never())` on all 9 excluded port methods. Schema (`CreateProjectsDatabaseService.projectsSpec()`, lines 114-123) has exactly 4 properties, none relation-typed (`NotionPropertyType` has no `RELATION` value at all). |
| §3 Status/Due Date + OQ-A domain backing | PASS | `ProjectStatus.java` (4 values, `displayName()`); `Project.java:16-17,39-40` (`status` non-null default `PLANNED`, `dueDate` nullable). Tests: `ProjectTest.java` (8 tests), `ProjectStatusTest.java` (2 tests), `CreateProjectsDatabaseServiceTest#projectsSpec_buildsFourPropertiesWithStatusOptionsFromEnum` (lines 271-282). |
| NFR-1 (strict idempotency) | PASS | Live `verify`/`findChildByIdentity` consulted on every path before any terminal outcome (§4.1 traced above); IT convergence test. |
| NFR-2 (resilience to external failure) | PASS | Notion-before-ledger ordering + non-destructive repair (adapter never sends `null`, `NotionProvisioningAdapter.java:196-205`); next-run reconciliation proven by IT's `execute_reachesRepairedOutcomeWhenFakeSimulatesExternalPropertyRemoval` (`CreateProjectsDatabaseServiceIT.java:112-126`). |
| NFR-3 (testability) | PASS | Pure Mockito unit test with zero Notion calls (`CreateProjectsDatabaseServiceTest`); `MockRestServiceServer` adapter contract tests (`NotionProvisioningAdapterDatabaseTest`, 17 tests); IT uses an in-memory fake port, no live Notion anywhere. |
| NFR-4 (no silent no-op) | PASS | `CreateProjectsDatabaseService.execute` no longer throws `UnsupportedOperationException` (real algorithm implemented) **and** the four adapter DB methods are real (`NotionProvisioningAdapter.java:126-206`) — cutover is coupled, matching the spec's requirement that the stub only be removed once the adapter is genuinely implemented. |
| NFR-5 (observability) | PASS | `CreateProjectsDatabaseService.java:56-57,64,87` — SLF4J logs `workspaceId`, `dashboardId`, prior ledger id, `VerificationResult`, and final `outcome`; no token, no raw Notion body logged. |
| NFR-6 (no token leakage) | PASS | `NotionApiException` messages built only from status + Notion `code`/`message` (unchanged `NotionClient`); this step's own `>1`-match message interpolates only match count + `expected.title()` (a constant, `"Projects"`) — `NotionProvisioningAdapter.java:179-180`. Test `NotionProvisioningAdapterDatabaseTest#client_neverLeaksTokenInDatabaseSliceExceptionMessage` (lines 329-342) and `#findChildByIdentity_throwsOnMoreThanOneMatch` (`.hasMessageNotContaining(TOKEN)`, line 218) directly assert this. |
| NFR-7 (failure isolation) | PASS | Only `PROJECTS_DB` ledger entry is ever written (`ledger.record(workspaceId, PROJECTS_DB, ...)` — no other resource type referenced); no shared mutable state introduced. |
| NFR-8 (ledger recording — exactly one entry) | PASS | `WorkspaceLedgerWriter.record` upsert semantics unchanged (`Workspace.record`); IT's `execute_convergesToOneRowAcrossThreeReruns` explicitly asserts exactly one `PROJECTS_DB` row after 3 runs. |
| NFR-9 (bounded call count) | PASS | No polling loop in the service or adapter; each adapter method makes 1–2 bounded calls (plus bounded pagination in `findChildByIdentity`), verified by request-count assertions (`server.verify()`) in `NotionProvisioningAdapterDatabaseTest`. |
| NFR-10 (rate-limit awareness) | PASS | Entirely delegated to the reused, unmodified `NotionClient` (429/529 `Retry-After` clamp) — this step adds no new HTTP client code, confirmed by `NotionProvisioningAdapter`'s adapter methods all routing through `client.get/post/patch`. |

## 4. Design conformance

No deviations found from `02-architecture.md` / `03-tech-spec.md`:

- **Layering.** `CreateProjectsDatabaseService` (`application.usecase.project`) depends only on `NotionProvisioningPort`, `WorkspaceRepository`, `WorkspaceLedgerWriter`, and `domain.project.ProjectStatus` — no adapter import, matching hexagonal layering (`CreateProjectsDatabaseService.java:1-18`).
- **Transaction boundary.** `execute` carries no `@Transactional` (class or method) — confirmed by reflection test `execute_isNotAnnotatedTransactional` (`CreateProjectsDatabaseServiceTest.java:264-269`) and by direct inspection of the source (no annotation present). `WorkspaceLedgerWriter.record` remains the sole transactional write path, unchanged.
- **Port surface.** `NotionProvisioningPort.java:8-35` matches tech-spec §3.5 exactly: `findChildByIdentity` gained the third `ExpectedShape` param; `verify`/`createDatabase`/`repairShape` parameter names align with the spec's clarified semantics; every other method (`ensureRelation`/`ensureRollup`/`ensureFormula`/`hasSampleRecords`/`insertSampleRecords`/page-slice methods) is untouched.
- **Value types.** `PropertyDefinition`/`DatabaseSpec`/`ExpectedShape` match §3.2–3.4 exactly, including compact-constructor validation (non-blank names/titles, non-null type, options-only-for-SELECT, exactly-one-TITLE-property). Confirmed by `PropertyDefinitionTest`/`DatabaseSpecTest`/`ExpectedShapeTest` (6/5/3 tests respectively, all green).
- **Adapter DB slice.** `createDatabase`/`verify`/`findChildByIdentity`/`repairShape` implementations (`NotionProvisioningAdapter.java:126-206`) match tech-spec §5.1–5.4 endpoint-by-endpoint (`POST /v1/databases` with `initial_data_source.properties`; `GET /v1/databases/{id}` + `GET /v1/data_sources/{id}` for verify; `GET /v1/blocks/{id}/children` with pagination for identity; title-PATCH + batched property-PATCH for repair). Non-destructive repair confirmed (never sends `null`, only genuinely-missing properties, re-added Status carries enum-seeded options).
- **Domain change (OQ-A).** `Project`/`ProjectStatus` match architecture §5.6 / tech-spec §2 exactly: parameter order, `@Value`/`@Builder(PRIVATE)` preserved, `status` defaults to `PLANNED` when `null` (not rejected), `dueDate` nullable passthrough, static-factory validation for `name`/`workspaceId` unchanged.
- **Dashboard ripple neutrality.** `CreateDashboardServiceIT`'s `InMemoryPageOnlyNotionPort.findChildByIdentity`/`createDatabase` (lines 163, 168) gained only the parameter per the arity change and kept their `throw new UnsupportedOperationException()` bodies — a genuinely compile-only, behavior-neutral ripple. `CreateDashboardServiceTest.java:236` was updated to `never()).findChildByIdentity(any(), any(), any())` (3-arg) — matches tech-spec §1 exactly; line 229 (`createDatabase(any(), any())`) is unchanged as specified. `CreateDashboardServiceIT` remains green (3/3) confirming zero behavior regression on the Dashboard step.
- **Scope-honesty check against §10 (tech-spec "Explicitly NOT built").** Verified by direct inspection of `NotionProvisioningAdapter.java:208-231`: `ensureRelation`, `ensureRollup`, `ensureFormula`, `hasSampleRecords`, `insertSampleRecords` all still throw `UnsupportedOperationException` verbatim — untouched. No relation/rollup/formula/sample-record code was added anywhere in this branch. No `ProjectRepository`, no JPA entity, no Flyway migration for `Project` was added (only in-memory domain fields). No other database step (Tasks/Knowledge/Habits/Journal/Resources/People) or `GOALS_DB`/`REVIEWS_DB` schema-building code exists — `grep` over the codebase shows `projectsSpec()`/`projectsExpectedShape()` are the only schema-authoring methods for this feature, not generalized into a shared builder. No Notion `status`-type property used (`NotionPropertyType` enum has no `STATUS` value — only `TITLE`/`RICH_TEXT`/`SELECT`/`DATE`). No semantic Notion-Version range check added (only the existing `@NotBlank` fail-fast, plus a documenting code comment `NotionProvisioningAdapter.java:124,139,159,185` — matches the tech-spec's "optional, not required" framing). `WorkspaceController`/`WorkspaceCommands`/`ApiExceptionHandler` untouched (confirmed no test changes needed there and no source diff).

## 5. Coverage gaps

None found. Every FR/NFR has at least one directly-asserting test; the decision table (architecture §4.2 / tech-spec §4.5, 9 rows) is covered 1:1 by named `CreateProjectsDatabaseServiceTest` methods, and every adapter endpoint/branch (§5.1–5.4) has a corresponding `MockRestServiceServer` contract test including the non-obvious ones (extra-user-options ignored, pagination, batched PATCH, never-sends-null).

## 6. Violations

**None.** Zero violations raised.

## 7. Summary for the pipeline

- Verdict: **PASS**
- Unit tier: `Tests run: 190, Failures: 0, Errors: 0, Skipped: 0`
- Failsafe `*IT` tier: `Tests run: 9, Failures: 0, Errors: 0, Skipped: 0` (`CreateProjectsDatabaseServiceIT` confirmed running under Failsafe, 4/4 green)
- Violation count: **0**
