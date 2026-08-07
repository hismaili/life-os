# 04 — QA Report: Create Tasks Database

Owner (QA stage): spring-qa
Input: `01-spec.md`, `02-architecture.md` (+ ADR-0009; ADR-0005..0008 by reference), `03-tech-spec.md`, `00-preflight.md`, code under `backend/src/`.

## 1. Verdict

**PASS.** All 14 FRs and 10 NFRs are satisfied with directly-asserting tests; the build is green on both tiers; the scope guard (only `CreateTasksDatabaseService.java` + its two test files) is honored; zero violations, zero coverage gaps.

## 2. Test run (independently executed, not trusted from the tech-spec's self-reported numbers)

Command (from `backend/`, Podman env exported per `00-preflight.md`):

```bash
export DOCKER_HOST="unix://$(podman machine inspect --format '{{.ConnectionInfo.PodmanSocket.Path}}')"
export TESTCONTAINERS_RYUK_DISABLED=true
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
./mvnw verify
```

Result: `BUILD SUCCESS`.

- **Unit tier (surefire):** `Tests run: 207, Failures: 0, Errors: 0, Skipped: 0` — includes `com.lifeos.application.usecase.task.CreateTasksDatabaseServiceTest` at `Tests run: 17, Failures: 0, Errors: 0, Skipped: 0`.
- **Failsafe tier (`*IT`, under `verify`):** `Tests run: 13, Failures: 0, Errors: 0, Skipped: 0` — includes `com.lifeos.application.usecase.task.CreateTasksDatabaseServiceIT` at `Tests run: 4, Failures: 0, Errors: 0, Skipped: 0`, confirmed running under Failsafe (its own `[INFO] Running com.lifeos.application.usecase.task.CreateTasksDatabaseServiceIT` line, Testcontainers Postgres container spin-up logged, not skipped).

No failing tests anywhere in either tier. Numbers match the Implementer's self-reported figures in `03-tech-spec.md` "Implementation notes" (207 unit / 13 failsafe) — independently reproduced, not merely trusted.

## 3. Acceptance criteria matrix

| Criterion | Verdict | Evidence |
|---|---|---|
| FR-1 (input contract) | PASS | `CreateTasksDatabaseUseCase.execute(UUID)` unchanged signature; exercised by every test in `CreateTasksDatabaseServiceTest` with only `workspaceId` supplied |
| FR-2 (workspace not found) | PASS | `CreateTasksDatabaseServiceTest#execute_throwsWhenWorkspaceNotFound` (line 61-72): `IllegalStateException("Workspace not found: " + id)`, `verifyNoInteractions(notion)`/`(ledger)` |
| FR-3 (missing Dashboard precondition) | PASS | `CreateTasksDatabaseServiceTest#execute_throwsWhenNoDashboardLedgerEntry` (line 74-86); IT `execute_throwsWhenPhaseAIncomplete` (`CreateTasksDatabaseServiceIT.java:128-137`) |
| FR-4 (first-time creation) | PASS | `CreateTasksDatabaseServiceTest#execute_createsWhenColdAndNoOrphan` (line 88-106, asserts captured `DatabaseSpec` and `CREATED`); IT `execute_persistsTasksDbLedgerRowOnFirstRun` (`:85-94`) |
| FR-5 (pure reconcile) | PASS | `#execute_reconcilesWhenWarmAndMatching` (line 149-162): asserts no write, no `findChildByIdentity`, `RECONCILED` |
| FR-6a (repair after deletion) | PASS | `#execute_reAdoptsWhenWarmAndDeletedAndOrphanFound` (line 177-189), `#execute_reCreatesWhenWarmAndDeletedAndNoOrphanFound` (line 191-204) |
| FR-6b (repair after drift) | PASS | `#execute_repairsWhenWarmAndDrifted` (line 164-175); IT `execute_reachesRepairedOutcomeWhenFakeSimulatesExternalPropertyRemoval` (`:112-126`) |
| FR-7 (verify-before-trust) | PASS | Warm path always calls `notion.verify` before returning; asserted by tests 5.1-7/8/9/10 (never returns `RECONCILED` on ledger presence alone) |
| FR-8 (orphan adoption) | PASS | `#execute_adoptsWhenColdAndOrphanMatches` (line 108-121), `#execute_adoptsAndRepairsWhenColdAndOrphanDrifted` (line 123-135) |
| FR-9 (fail loudly, >1 match) | PASS | `#execute_propagatesAmbiguousMatchFailureOnColdPath` (line 137-147), `#execute_propagatesAmbiguousMatchFailureOnWarmAbsentPath` (line 206-217) |
| FR-10 (ledger write path) | PASS | Every write-path test asserts `verify(ledger).record(id, TASKS_DB, notionId)`; `WorkspaceLedgerWriter` unmodified (own `@Transactional`, reused) |
| FR-11 (result contract) | PASS | All outcome assertions (`CREATED`/`RECONCILED`/`REPAIRED`); failure paths assert propagated exception via `isSameAs` (no fabricated result) |
| FR-12 (no partial ledger write on external failure) | PASS | `#execute_propagatesNotionFailureFromVerifyWithoutWritingLedger` (line 219-229), `#execute_propagatesNotionFailureFromCreateWithoutWritingLedger` (line 231-242) — both assert `verifyNoInteractions(ledger)` |
| FR-13 (idempotent convergence, 3 runs) | PASS | IT `execute_convergesToOneRowAcrossThreeReruns` (`CreateTasksDatabaseServiceIT.java:96-110`): 3 sequential `execute()` calls, exactly one `TASKS_DB` row, same `notionId`, 2nd/3rd outcome `RECONCILED` |
| FR-14 (scope boundary — no relation/rollup/formula/sample/page calls) | PASS | `#execute_neverInvokesRelationRollupFormulaOrSampleOrPageMethods` (line 244-262): `verify(notion, never())` on 9 unrelated port methods; schema assertions (test 3, 16) confirm no relation-typed property |
| NFR-1 (strict idempotency) | PASS | Warm/cold live-verify branching in every path; IT convergence test |
| NFR-2 (resilience) | PASS | FR-12 tests + IT repair-after-out-of-band-mutation test |
| NFR-3 (testability) | PASS | Pure Mockito unit tests, zero real Notion calls; fake-port IT, zero real Notion calls |
| NFR-4 (no silent no-op) | PASS | Stub `UnsupportedOperationException` fully removed; real algorithm present (`CreateTasksDatabaseService.java:39-59`) |
| NFR-5 (observability) | PASS | `log.info` lines at `CreateTasksDatabaseService.java:56-57, 64, 87` include workspaceId/dashboardId/priorLedgerId/notionId/outcome; visible in the `verify` run log capture |
| NFR-6 (no token leakage) | PASS | `CreateTasksDatabaseService` never references `NotionClient`/token; `NotionApiException` construction reused unchanged from Projects (already covered) |
| NFR-7 (failure isolation) | PASS | Service reads only `DASHBOARD`, writes only `TASKS_DB` (`CreateTasksDatabaseService.java:44,50`); no shared mutable state introduced |
| NFR-8 (ledger recording — exactly one entry, upsert) | PASS | IT `execute_convergesToOneRowAcrossThreeReruns` asserts `hasSize(1)` after 3 runs; relies on unmodified `Workspace.record` upsert semantics |
| NFR-9 (performance / bounded calls) | PASS (inherited) | Reused adapter, unchanged call budget; no polling/retry loop in `CreateTasksDatabaseService` |
| NFR-10 (rate-limit awareness) | PASS (inherited) | Reused `NotionClient` `429`/`529` handling, unchanged; no new throughput assumption introduced |

## 4. Scope-honesty check (tech-spec §6 "do-NOT-build" / §1 confirmed-unmodified list)

Verified by file mtimes (no git repo available in this environment) rather than by trusting the tech-spec's claim:

| File | mtime | Verdict |
|---|---|---|
| `application/usecase/task/CreateTasksDatabaseService.java` | Aug 5 23:16 | Modified — in scope (the one production file) |
| `test/.../task/CreateTasksDatabaseServiceTest.java` | Aug 5 23:15 | New/rewritten — in scope |
| `test/.../task/CreateTasksDatabaseServiceIT.java` | Aug 5 23:16 | New — in scope |
| `infrastructure/adapter/notion/NotionProvisioningAdapter.java` | Aug 5 22:27 | Predates the Tasks-step edits — untouched by this branch |
| `application/port/NotionProvisioningPort.java` | Aug 5 16:41 | Predates — untouched |
| `application/usecase/workspace/WorkspaceLedgerWriter.java` | Aug 4 18:35 | Predates — untouched |
| `domain/task/Task.java` | Aug 4 18:32 | Predates — untouched (no domain change, per spec §7 constraint) |
| `domain/task/TaskStatus.java` | Aug 4 12:48 | Predates — untouched, still a plain 5-constant enum with no `displayName()` |

No `ensureRelation`/`ensureRollup`/`ensureFormula`/`insertSampleRecords`/other-`*_DB` port methods are invoked by the new code (`CreateTasksDatabaseServiceTest#execute_neverInvokesRelationRollupFormulaOrSampleOrPageMethods`). `tasksSpec()` schema has exactly 4 properties, no relation-typed property pointing at `PROJECTS_DB` — confirmed by direct assertion (`tasksSpec_buildsFourPropertiesWithStatusOptionsFromEnum`, line 271-282). Scope guard: **honored**.

## 5. Design conformance (vs. `02-architecture.md` / `03-tech-spec.md`)

- 3-arg constructor (`NotionProvisioningPort`, `WorkspaceRepository`, `WorkspaceLedgerWriter`) — matches architecture §4.1 exactly.
- Warm/cold path algorithm, outcome mapping (`CREATED` only on true first-time create; `REPAIRED` ⇔ a Notion write happened; `RECONCILED` ⇔ none) — verbatim mirror of `CreateProjectsDatabaseService`, matches architecture §3 sequence diagram and outcome table by inspection.
- `execute` carries no `@Transactional`; sole transactional unit remains `WorkspaceLedgerWriter.record` — asserted by reflection test `execute_isNotAnnotatedTransactional` (line 264-269) and confirmed structurally (no annotation on the class/method in `CreateTasksDatabaseService.java`).
- Title property named `"Title"` (not `"Name"`), Status options from `TaskStatus.values()` via `Enum::name` per ADR-0009 — matches tech-spec §2 exactly; asserted directly (test 16).
- Package location `application.usecase.task` — matches tech-spec §1 package layout.
- No deviations found.

## 6. Coverage gaps

None. Every FR-1..14 and NFR-1..10 maps to at least one directly-asserting unit or integration test (§3 above); the tech-spec's own traceability table (§7) is corroborated by inspection of the actual test bodies, not merely trusted.

## 7. Violations

None. **Violation count: 0.**

## 8. Summary

The implementation is a faithful, verbatim-mirror pattern-application of the already-shipped `CreateProjectsDatabaseService`, exactly as the architecture and tech-spec prescribed. The build is green end-to-end on both tiers (independently re-run), the scope guard holds (only the one production class + its two test files touched), and every acceptance criterion has direct test evidence. No findings routed to any other stage.
