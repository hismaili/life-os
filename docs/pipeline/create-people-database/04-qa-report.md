# 04 — QA Report: Create People Database

## 1. Verdict

**PASS.**

## 2. Test run (independently executed by QA, not trusted from Implementer report)

Command (exact, from `00-preflight.md`, run from `/Users/hismaili/perso/applications/life-os/backend`):

```bash
export DOCKER_HOST="unix://$(podman machine inspect --format '{{.ConnectionInfo.PodmanSocket.Path}}')"
export TESTCONTAINERS_RYUK_DISABLED=true
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
./mvnw verify
```

Result: **BUILD SUCCESS**.

- Unit/slice tier (Surefire): `Tests run: 299, Failures: 0, Errors: 0, Skipped: 0`
- Failsafe (`*IT`) tier: `Tests run: 33, Failures: 0, Errors: 0, Skipped: 0`

`CreatePeopleDatabaseServiceIT` confirmed **ran under Failsafe (not skipped)**:
```
[INFO] Running com.lifeos.application.usecase.person.CreatePeopleDatabaseServiceIT
...
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.933 s -- in com.lifeos.application.usecase.person.CreatePeopleDatabaseServiceIT
```

`CreatePeopleDatabaseServiceTest` (unit, Surefire) — 17/17 passing:
```
[INFO] Tests run: 17, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.023 s -- in com.lifeos.application.usecase.person.CreatePeopleDatabaseServiceTest
```

`NotionProvisioningAdapterDatabaseTest` — 22/22 passing (20 pre-existing + 2 new EMAIL contract tests):
```
[INFO] Tests run: 22, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.085 s -- in com.lifeos.infrastructure.adapter.notion.NotionProvisioningAdapterDatabaseTest
```

No test failures anywhere in the run. The Implementer's orphaned report's claimed numbers (299 unit / 33 failsafe) are confirmed independently.

## 3. Scope-honesty check

Confirmed exactly the three production files claimed by `03-tech-spec.md` §1 were changed, each additively/narrowly as specified:

| File | Change verified | Evidence |
|---|---|---|
| `application/port/NotionPropertyType.java` | Additive-only: `{TITLE, RICH_TEXT, SELECT, DATE, URL, EMAIL}` — no reorder, no removal | `backend/src/main/java/com/lifeos/application/port/NotionPropertyType.java:3` |
| `infrastructure/adapter/notion/NotionProvisioningAdapter.java` | One added branch `case EMAIL -> Map.of("type", "email", "email", Map.of());` inserted before the pre-existing `SELECT` branch; `TITLE`/`RICH_TEXT`/`DATE`/`URL`/`SELECT` branches byte-identical to before | `NotionProvisioningAdapter.java:262-272` |
| `application/usecase/person/CreatePeopleDatabaseService.java` | Stub replaced with full 3-arg-constructor real implementation, verbatim mirror of `CreateResourcesDatabaseService` per tech-spec §3.2 | `CreatePeopleDatabaseService.java:1-121` |

Confirmed **untouched** (read and diffed against tech-spec §1.4 "confirmed NOT modified" list):
- `NotionClient.java` — token header construction unchanged (`Authorization`/`Bearer` at line 32 only); no new endpoint/header.
- `NotionProvisioningPort.java` — all method signatures unchanged (read in full; matches pre-existing 4 DB-slice methods + page/relation/rollup/formula/sample methods).
- `WorkspaceLedgerWriter.java` — unchanged; sole `@Transactional record(...)` method, reused as-is.
- `domain/person/Person.java`, `domain/person/Email.java` — read in full; zero modification, `name`+`email` fields and `Email`'s regex VO exactly as spec described; no new field, no new validation.
- `CreateWorkspaceService.java` — read in full; `createPeopleDatabase` already wired at the constructor and invoked at the Phase-B `dbResults.add(runOrBlock(...))` call (line 60); zero change to ordering/`phaseBOk`/`runOrBlock` logic.
- No `EMAIL`/`case EMAIL` reference found anywhere outside `CreatePeopleDatabaseService.java`, `NotionProvisioningAdapter.java`, and their test counterparts (`grep -rln` confirms exactly 4 files: 2 production + 2 test) — no retrofit onto Projects/Tasks/Knowledge/Habits/Journal/Resources schemas.

## 4. Acceptance criteria matrix

| AC | Req | Verdict | Evidence |
|---|---|---|---|
| AC-1 | FR-1, FR-6 | PASS | `execute_createsWhenColdAndNoOrphan` — `CreatePeopleDatabaseServiceTest.java:88-105`; asserts `CREATED` outcome + `ledger.record(id, PEOPLE_DB, "new-db-id")`. IT: `execute_persistsPeopleDbLedgerRowOnFirstRun` — `CreatePeopleDatabaseServiceIT.java:85-94`. |
| AC-2 | FR-2, FR-3, FR-4, FR-5 | PASS | `peopleSpec_buildsTwoPropertiesNameAndEmail` — `CreatePeopleDatabaseServiceTest.java:270-278` (exactly Name/TITLE + Email/EMAIL, no other property). Adapter payload proof: `createDatabase_postsEmailPropertyWithEmptyEmailConfig` — `NotionProvisioningAdapterDatabaseTest.java:121-137`, asserts `$.initial_data_source.properties.Email.type == "email"` and `.email` is empty object. |
| AC-3 | FR-7 | PASS | `execute_reconcilesWhenWarmAndMatching` — `CreatePeopleDatabaseServiceTest.java:148-161`; asserts no create/repair/find calls, `verifyNoInteractions(ledger)`, outcome `RECONCILED`. |
| AC-4 | FR-5, FR-7 | PASS | `execute_repairsWhenWarmAndDrifted` — `CreatePeopleDatabaseServiceTest.java:163-174`; `repairShape_addsMissingEmailPropertyWithEmptyEmailConfig` — `NotionProvisioningAdapterDatabaseTest.java:139-159` proves the exact `{"type":"email","email":{}}` PATCH body and `Name` untouched. |
| AC-5 | FR-6, FR-7 | PASS | `execute_reAdoptsWhenWarmAndDeletedAndOrphanFound` / `execute_reCreatesWhenWarmAndDeletedAndNoOrphanFound` — `CreatePeopleDatabaseServiceTest.java:176-203`. IT: `execute_reachesRepairedOutcomeWhenFakeSimulatesExternalPropertyRemoval` — `CreatePeopleDatabaseServiceIT.java:112-126`. |
| AC-6 | FR-6 | PASS | `execute_adoptsWhenColdAndOrphanMatches` — `CreatePeopleDatabaseServiceTest.java:107-120`; asserts no create/repair, `ledger.record`, outcome `RECONCILED`. |
| AC-7 | FR-9 | PASS | `execute_throwsWhenNoDashboardLedgerEntry` — `CreatePeopleDatabaseServiceTest.java:74-86`; `verifyNoInteractions(notion)`. |
| AC-8 | FR-10 | PASS | `execute_throwsWhenWorkspaceNotFound` — `CreatePeopleDatabaseServiceTest.java:61-72`; `verifyNoInteractions(notion)`. |
| AC-9 | FR-11 | PASS | `execute_propagatesAmbiguousMatchFailureOnColdPath` / `...OnWarmAbsentPath` — `CreatePeopleDatabaseServiceTest.java:136-146, 205-216`; asserts exception propagation + `verifyNoInteractions(ledger)`. Orchestrator-level mapping to `FAILED` is via reused, unchanged `CreateWorkspaceService.runStep` (`CreateWorkspaceService.java:88-94`) — not re-tested per-feature (correctly relies on existing coverage). |
| AC-10 | FR-6, FR-7, AC-3 | PASS | `execute_convergesToOneRowAcrossThreeReruns` — `CreatePeopleDatabaseServiceIT.java:96-110`; 3 sequential runs, 2nd/3rd `RECONCILED`, exactly one ledger row, stable `notionId`. |
| AC-11 | FR-12 | PASS (by inherited/unchanged wiring) | `CreateWorkspaceService.java:60,96-101` — `runOrBlock` gates `PEOPLE_DB` on `phaseAOk`; `CreateWorkspaceIT` (existing, unrelated to this feature) exercises the `BLOCKED` path end-to-end including `PEOPLE_DB` in its assertion list (verify.log, `CreateWorkspaceIT` output shows `PEOPLE_DB, outcome=BLOCKED, detail=prerequisite step failed or was blocked`). No new People-specific test needed since orchestrator wiring is unchanged (correctly out of scope per FR-12). |
| AC-12 | NFR-2, FR-13 | PASS | Structural: `NotionClient.java:32` is the only site reading `properties.token()`; `NotionApiException` construction sites (`NotionClient.java:73,89,121`) build messages from status/code/message only, never from the token. `CreatePeopleDatabaseService` never touches the token. No People-specific token-leakage test exists, but none is needed — this is inherited, unchanged token-handling machinery already covered by `NotionClientTest`. |
| AC-13 | FR-5 | PASS | `createDatabase_postsEmailPropertyWithEmptyEmailConfig` + `repairShape_addsMissingEmailPropertyWithEmptyEmailConfig` — `NotionProvisioningAdapterDatabaseTest.java:121-159`; both assert exactly `{"type":"email","email":{}}` (`.email` `isEmpty()`, no `options` key). |
| AC-14 | FR-12 | PASS | `CreateWorkspaceService.java:54-62` wires all 7 DB steps into `dbResults`/`phaseBOk`. `CreatePeopleDatabaseServiceIT` proves `PEOPLE_DB` reaches non-`FAILED`/non-`BLOCKED` outcomes independently; sanity check in §6 below confirms zero remaining stub sibling services. |

All 14 acceptance criteria: **PASS**.

## 5. Design conformance

No deviations found against `02-architecture.md` / `03-tech-spec.md`:

- **Layering** — `CreatePeopleDatabaseService` (application) depends only on ports (`NotionProvisioningPort`, `WorkspaceRepository`) and `WorkspaceLedgerWriter`; no infrastructure import. `NotionProvisioningAdapter` (infrastructure) implements the port; no leakage of Notion DTOs into the application layer. Matches `CLAUDE.md`'s hexagonal boundary.
- **Transaction boundary** — `execute` carries **no** `@Transactional` (confirmed by test `execute_isNotAnnotatedTransactional`, `CreatePeopleDatabaseServiceTest.java:263-268`, and by direct source read — no annotation present on the class or method, `CreatePeopleDatabaseService.java:37-38`). `WorkspaceLedgerWriter.record` remains the sole `@Transactional` write (`WorkspaceLedgerWriter.java:18`). Matches tech-spec §3.4.
- **Algorithm mirror** — `executeWarmPath`/`executeColdPath` control flow, log statement shapes, and outcome/detail strings are structurally identical to `CreateResourcesDatabaseService`, with only `RESOURCES_DB→PEOPLE_DB`/`"Resources"→"People"`/`Title→Name`/`URL→Email` token substitutions, exactly as tech-spec §3.2's line-level delta prescribes.
- **`propertyConfig` exhaustive switch** — `case EMAIL` added with no `default` clause preserved, confirming the JLS §14.11.2 compile-time exhaustiveness guarantee cited in the architecture is intact (`NotionProvisioningAdapter.java:263-271`).
- **Schema** — `peopleSpec()` produces exactly 2 properties, `Name`(TITLE)/`Email`(EMAIL), matching tech-spec §2 exactly; no `SELECT`/`DATE`/`URL`/`RICH_TEXT`/relation present.
- **Adapter contract test placement** — added to the existing `NotionProvisioningAdapterDatabaseTest` file as prescribed (additive, existing fixtures/tests untouched — `EXPECTED_SHAPE`/`URL_EXPECTED_SHAPE` fixtures at lines 33-40 remain byte-identical, new `EMAIL_EXPECTED_SHAPE` fixture added alongside at line 41).

## 6. Seventh/final-database sanity check

Confirmed via `grep -rn "UnsupportedOperationException" src/main/java/com/lifeos/application/usecase/{project,task,knowledge,habit,journal,resource,person}/*.java` — **zero matches**. All seven Phase-B child-database services (Projects, Tasks, Knowledge, Habits, Journal, Resources, People) are real implementations; no remaining stub among them. This is reported as informational per the task instructions; no code was modified to arrive at this conclusion.

(Note: `ensureRelation`/`ensureRollup`/`ensureFormula`/`hasSampleRecords`/`insertSampleRecords` on `NotionProvisioningAdapter` remain intentional `UnsupportedOperationException` stubs — these are Phase C/D/sample-data concerns explicitly out of scope for this and all seven database-provisioning features, per spec §8/tech-spec §6 "Do NOT build".)

## 7. Coverage gaps

None found. Every FR (FR-1 through FR-13) and every AC (AC-1 through AC-14) has at least one directly-asserting test, matching the tech-spec's own test-plan mapping (§7 traceability table) test-for-test. The 17-test unit suite, 22-test adapter contract suite, and 4-test IT suite match the tech-spec's prescribed counts exactly (§5.1 "22 total", §5.2 "17 tests", §5.3 "4 tests").

## 8. Violations

**None.**

## 9. Summary

- Verdict: **PASS**
- Unit tests: `299/299` passing (Surefire)
- Failsafe (`*IT`) tests: `33/33` passing, `CreatePeopleDatabaseServiceIT` confirmed run (not skipped)
- Violations: **0**
- Coverage gaps: **0**
- Scope: exactly the three claimed production files changed, additively; no scope creep; all seven Phase-B database services are now real (no stubs remain)
