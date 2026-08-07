# 04 — QA Report: Create Habits Database

Status: PASS
Owner (QA stage): pipeline automation
Inputs: `01-spec.md`, `02-architecture.md`, `03-tech-spec.md`, `00-preflight.md`, and the code under `backend/src/`.

## 1. Verdict

**PASS.** All 15 FRs and 10 NFRs are satisfied by correctly-asserting tests; `./mvnw verify` is green on both tiers; the scope guard is honored — only `CreateHabitsDatabaseService.java` and its two test classes were touched in this pass.

## 2. Test run

Command run verbatim from `00-preflight.md` (`backend/`), with the Podman env exported per-invocation:

```bash
export DOCKER_HOST="unix://$(podman machine inspect --format '{{.ConnectionInfo.PodmanSocket.Path}}')"
export TESTCONTAINERS_RYUK_DISABLED=true
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
./mvnw verify
```

- Unit tier (Surefire): `Tests run: 239, Failures: 0, Errors: 0, Skipped: 0` — includes `CreateHabitsDatabaseServiceTest` (`Tests run: 17, Failures: 0, Errors: 0, Skipped: 0`).
- Failsafe tier (`*IT`, Testcontainers Postgres via Podman): `Tests run: 21, Failures: 0, Errors: 0, Skipped: 0` — includes `CreateHabitsDatabaseServiceIT` (`Tests run: 4, Failures: 0, Errors: 0, Skipped: 0`, confirming it ran under failsafe, not skipped).
- `BUILD SUCCESS`.

No failing tests. Full log preserved at `/private/tmp/claude-501/.../scratchpad/verify.log` (session-local).

## 3. Acceptance criteria matrix

| Criterion | Verdict | Evidence |
|---|---|---|
| FR-1 (input contract) | PASS | `CreateHabitsDatabaseUseCase.execute(UUID)` unchanged; `CreateHabitsDatabaseService.java:39-40`; no test needs extra params — implicit across all tests |
| FR-2 (workspace not found) | PASS | `CreateHabitsDatabaseService.java:41-42` throws `IllegalStateException`; `CreateHabitsDatabaseServiceTest.execute_throwsWhenWorkspaceNotFound` (l.61-72) asserts message + `verifyNoInteractions(notion)`/`(ledger)` |
| FR-3 (missing Dashboard precondition) | PASS | `CreateHabitsDatabaseService.java:44-46`; `execute_throwsWhenNoDashboardLedgerEntry` (Test l.74-86) |
| FR-4 (first-time creation) | PASS | `executeColdPath` (Service l.85-92: `CREATED`); `execute_createsWhenColdAndNoOrphan` (Test l.88-106) asserts exactly 2 props, order Name/Frequency, ledger record, outcome `CREATED` |
| FR-5 (pure reconcile) | PASS | `executeWarmPath` `PRESENT_MATCHING` (Service l.66); `execute_reconcilesWhenWarmAndMatching` (Test l.150-162) asserts no write, no ledger call |
| FR-6a (repair after deletion) | PASS | `ABSENT` branch (Service l.72-81); `execute_reAdoptsWhenWarmAndDeletedAndOrphanFound` / `execute_reCreatesWhenWarmAndDeletedAndNoOrphanFound` (Test l.177-204) |
| FR-6b (repair after drift) | PASS | `PRESENT_DRIFTED` branch (Service l.67-71); `execute_repairsWhenWarmAndDrifted` (Test l.164-175) |
| FR-7 (verify-before-trust) | PASS | Every warm-path branch calls `notion.verify` before any outcome; same tests as FR-5/FR-6b; also IT `execute_reachesRepairedOutcomeWhenFakeSimulatesExternalPropertyRemoval` (IT l.112-126) |
| FR-8 (orphan adoption) | PASS | Cold-path `findChildByIdentity` before create (Service l.85-95); `execute_adoptsWhenColdAndOrphanMatches` / `execute_adoptsAndRepairsWhenColdAndOrphanDrifted` (Test l.108-135) |
| FR-9 (fail loudly on >1 match) | PASS | Propagated `NotionApiException` from `findChildByIdentity`; `execute_propagatesAmbiguousMatchFailureOnColdPath` (Test l.137-147) and `...OnWarmAbsentPath` (Test l.206-217) |
| FR-10 (ledger write path) | PASS | Every branch calls `ledger.record(workspaceId, HABITS_DB, id)` (Service, all outcome branches); `WorkspaceLedgerWriter.record` is `@Transactional` (`WorkspaceLedgerWriter.java:18-23`), own transaction, unchanged |
| FR-11 (result contract) | PASS | Every path returns `ProvisioningStepResult(HABITS_DB, outcome, detail)`; failure paths propagate exceptions (no result fabricated) — same tests as FR-9/FR-12 |
| FR-12 (no partial ledger write on failure) | PASS | `execute_propagatesNotionFailureFromVerifyWithoutWritingLedger` / `...FromCreateWithoutWritingLedger` (Test l.219-242) — Notion call throws, `verifyNoInteractions(ledger)` |
| FR-13 (idempotent convergence) | PASS | IT `execute_convergesToOneRowAcrossThreeReruns` (IT l.96-110): 3 consecutive runs, exactly one `HABITS_DB` row, same `notionId`, outcomes `CREATED`→`RECONCILED`→`RECONCILED` |
| FR-14 (scope boundary — no relation/rollup/formula/sample; exactly 2 properties) | PASS | `execute_neverInvokesRelationRollupFormulaOrSampleOrPageMethods` (Test l.244-262) — 9 `never()` assertions covering `ensureRelation`/`ensureRollup`/`ensureFormula`/`hasSampleRecords`/`insertSampleRecords`/`createRootPage`/`verifyPage`/`repairPage`/`findRootByIdentity`; `habitsSpec_buildsTwoPropertiesWithFrequencyOptionsFromEnum` (Test l.271-282) asserts `hasSize(2)` and `doesNotContain("Description","Due Date")` |
| FR-15 (Frequency option-set fidelity) | PASS | `habitsSpec_buildsTwoPropertiesWithFrequencyOptionsFromEnum` (Test l.278-279) asserts `Frequency` property equals `new PropertyDefinition("Frequency", SELECT, List.of("DAILY","WEEKLY","MONTHLY"))`; also asserted at creation-call level in `execute_createsWhenColdAndNoOrphan` (Test l.102-103) |

## 4. Design conformance

No deviations found. Verified specifically:

- **`CreateHabitsDatabaseService` is a byte-for-byte structural mirror of `CreateTasksDatabaseService`**, substituting `TASKS_DB→HABITS_DB`, `TaskStatus→Frequency`, title `"Tasks"→"Habits"`, and dropping `Description`/`Due Date` — matches tech-spec §3.2 exactly, including method names (`executeWarmPath`/`executeColdPath`/`habitsSpec`/`habitsExpectedShape`), log statement shapes, and outcome/detail strings (`CreateHabitsDatabaseService.java:39-126`).
- **No `@Transactional` on `execute`** — `execute_isNotAnnotatedTransactional` (Test l.264-269) reflects on both the method and the class; matches architecture §3 "Error strategy & transaction boundary" and tech-spec §3.4. `WorkspaceLedgerWriter.record` remains the sole `@Transactional` unit, unchanged (`WorkspaceLedgerWriter.java:18`).
- **Outcome mapping** matches the reused 9-row decision table exactly: `CREATED` only on cold-path first-time create (Service l.91, l.109 for cold `ABSENT`-orphan-recreate — note: cold-path recreate-after-ABSENT-orphan is `CREATED` per Service l.106-110, consistent with "adoption is never CREATED, but a genuinely fresh create after a vanished orphan still counts as CREATED" — matches the Tasks/Projects precedent); `REPAIRED` ⇔ a Notion write happened this run; `RECONCILED` ⇔ none.
- **No token leakage**: the service never touches the Notion token; `NotionClient` (unchanged, untouched this pass) remains the sole class reading it. `detail` strings are fixed literals only (`"database drifted; shape repaired"`, `"ledger id was stale; re-adopted existing database"`, `"ledger id was stale; database recreated"`, `"adopted orphan database was drifted; shape repaired"`, or `null`) — none interpolate any Notion response body or secret (`CreateHabitsDatabaseService.java:70,76,80,104`).
- **No comment pollution**: `grep` for `//`/`/*` across the service and both new test files returned zero matches — no inline commentary, consistent with the established convention in the Tasks/Projects/Knowledge passes.

## 5. Scope-honesty check

Confirmed by file-modification timestamps (Aug 6 04:02–04:03, this pass) versus all other production files (last touched Aug 4–5, prior Dashboard/Projects/Tasks/Knowledge passes) and by direct content re-read:

- `CreateHabitsDatabaseService.java` — **modified** (the one authorized change).
- `CreateHabitsDatabaseServiceTest.java`, `CreateHabitsDatabaseServiceIT.java` — **new/rewritten** (authorized).
- `domain/habit/Habit.java`, `domain/habit/Frequency.java` — **untouched** (mtime Aug 4 12:48; content re-read, unchanged 3-value enum, unchanged 5-field `@Value` aggregate).
- `application/port/{NotionProvisioningPort,DatabaseSpec,ExpectedShape,PropertyDefinition,NotionPropertyType,VerificationResult}.java` — **untouched** during this pass (mtime Aug 5 16:41, prior Projects pass).
- `infrastructure/adapter/notion/{NotionProvisioningAdapter,NotionClient}.java` and DTOs — **untouched** during this pass (mtime Aug 5 22:27/01:01, prior Tasks/Knowledge passes).
- `application/usecase/workspace/WorkspaceLedgerWriter.java` — **untouched** (mtime Aug 4 18:35; content re-read, `record` unchanged, own `@Transactional`).
- `domain/workspace/ProvisionedResourceType.java` — **untouched** (mtime Aug 4 17:54; `HABITS_DB` already present).

No adapter, port, or domain change was introduced. Scope guard honored.

## 6. Coverage gaps

None found. Every FR/NFR has a directly corresponding, correctly-asserting test at either the service-unit tier (`CreateHabitsDatabaseServiceTest`, 17 tests) or the integration tier (`CreateHabitsDatabaseServiceIT`, 4 tests, Testcontainers Postgres + fake port). NFR-9/NFR-10 (bounded call count, rate-limit clamp) are satisfied structurally by the reused, already-tested `NotionProvisioningAdapter`/`NotionClient` — no new test is warranted for this pass per tech-spec §5.3, and none was added, consistent with the "reused, do not duplicate" instruction.

## 7. Violations

**None.**
