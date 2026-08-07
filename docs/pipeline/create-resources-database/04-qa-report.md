# 04 — QA Report: Create Resources Database

## 1. Verdict

**PASS** — all 13 acceptance criteria (AC-1..AC-13) are satisfied with evidence; all FR/NFR are covered by real, correctly-asserting tests; build is green on both tiers; scope is honest (exactly three production files changed, additively).

## 2. Test run (independently executed, not trusted from tech-spec)

Run from `/Users/hismaili/perso/applications/life-os/backend`, Podman env exported per `00-preflight.md`.

```
export DOCKER_HOST="unix://$(podman machine inspect --format '{{.ConnectionInfo.PodmanSocket.Path}}')"
export TESTCONTAINERS_RYUK_DISABLED=true
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
./mvnw verify
```

- Unit tier (`./mvnw test`, surefire): **`Tests run: 281, Failures: 0, Errors: 0, Skipped: 0`** — `BUILD SUCCESS`.
- Failsafe tier (`*IT`, under `./mvnw verify`): **`Tests run: 29, Failures: 0, Errors: 0, Skipped: 0`** — `BUILD SUCCESS`.
- `CreateResourcesDatabaseServiceIT` ran under failsafe, not skipped — confirmed by its own line: `Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.706 s -- in com.lifeos.application.usecase.resource.CreateResourcesDatabaseServiceIT`.
- `CreateResourcesDatabaseServiceTest` (unit): `Tests run: 17, Failures: 0, Errors: 0, Skipped: 0`.
- `NotionProvisioningAdapterDatabaseTest` (unit): `Tests run: 20, Failures: 0, Errors: 0, Skipped: 0` (includes the 2 new URL contract tests — see note in §5 Coverage gaps re: the tech-spec's stale "15 total" count).

These numbers match the SME's self-reported changelog numbers in `03-tech-spec.md:529` — independently reproduced, not merely trusted.

## 3. Acceptance criteria matrix

| AC | FR refs | Verdict | Evidence |
|---|---|---|---|
| AC-1 | FR-1, FR-6 | PASS | `CreateResourcesDatabaseServiceTest#execute_createsWhenColdAndNoOrphan` (`CreateResourcesDatabaseServiceTest.java:88-105`) asserts `createDatabase` called, `ledger.record` called, outcome `CREATED`. IT-level: `CreateResourcesDatabaseServiceIT#execute_persistsResourcesDbLedgerRowOnFirstRun` (`CreateResourcesDatabaseServiceIT.java:85-94`). |
| AC-2 | FR-2..FR-5 | PASS | `resourcesSpec_buildsTwoPropertiesTitleAndUrl` (`CreateResourcesDatabaseServiceTest.java:270-278`) asserts exactly `Title`(TITLE)/`URL`(URL). Adapter payload proof: `NotionProvisioningAdapterDatabaseTest#createDatabase_postsUrlPropertyWithEmptyUrlConfig` (`NotionProvisioningAdapterDatabaseTest.java:78-94`) asserts `$.initial_data_source.properties.URL.type == "url"` and `$.initial_data_source.properties.URL.url` is empty (`{}`). |
| AC-3 | FR-7 | PASS | `execute_reconcilesWhenWarmAndMatching` (`CreateResourcesDatabaseServiceTest.java:148-161`) asserts `never() createDatabase/repairShape/findChildByIdentity`, `verifyNoInteractions(ledger)`, outcome `RECONCILED`. |
| AC-4 | FR-5, FR-7 | PASS | `execute_repairsWhenWarmAndDrifted` (`CreateResourcesDatabaseServiceTest.java:163-174`) asserts `repairShape("existing-id", …)` called, `ledger.record(id, RESOURCES_DB, "existing-id")`, outcome `REPAIRED`. JSON-shape proof: `repairShape_addsMissingUrlPropertyWithEmptyUrlConfig` (`NotionProvisioningAdapterDatabaseTest.java:96-116`). |
| AC-5 | FR-6, FR-7 | PASS | `execute_reAdoptsWhenWarmAndDeletedAndOrphanFound` and `execute_reCreatesWhenWarmAndDeletedAndNoOrphanFound` (`CreateResourcesDatabaseServiceTest.java:176-203`) — both outcome `REPAIRED`, both re-record ledger. IT: `execute_reachesRepairedOutcomeWhenFakeSimulatesExternalPropertyRemoval` (`CreateResourcesDatabaseServiceIT.java:112-126`). |
| AC-6 | FR-6 | PASS | `execute_adoptsWhenColdAndOrphanMatches` (`CreateResourcesDatabaseServiceTest.java:107-120`) — no create/repair, `ledger.record`, outcome `RECONCILED`. |
| AC-7 | FR-9 | PASS | `execute_throwsWhenNoDashboardLedgerEntry` (`CreateResourcesDatabaseServiceTest.java:74-86`) — `IllegalStateException`, `verifyNoInteractions(notion)`, `verifyNoInteractions(ledger)`. IT: `execute_throwsWhenPhaseAIncomplete` (`CreateResourcesDatabaseServiceIT.java:128-137`). |
| AC-8 | FR-10 | PASS | `execute_throwsWhenWorkspaceNotFound` (`CreateResourcesDatabaseServiceTest.java:61-72`) — `IllegalStateException`, no port/ledger interaction. |
| AC-9 | FR-11 | PASS | `execute_propagatesAmbiguousMatchFailureOnColdPath` and `…OnWarmAbsentPath` (`CreateResourcesDatabaseServiceTest.java:136-146, 205-216`) — exception propagates (`isSameAs`), `verifyNoInteractions(ledger)`. |
| AC-10 | FR-6, FR-7 | PASS | `execute_convergesToOneRowAcrossThreeReruns` (`CreateResourcesDatabaseServiceIT.java:96-110`) — 3 sequential runs, only 1 ledger row, runs 2/3 `RECONCILED`, `notionId` stable. |
| AC-11 | FR-12 | PASS | `CreateWorkspaceService.java:59` wiring unchanged (`runOrBlock(phaseAOk, () -> createResourcesDatabase.execute(...), RESOURCES_DB)`) — reused `CreateWorkspaceServiceTest` coverage for `runOrBlock` BLOCKED behavior applies generically; no Resources-specific deviation introduced (out of scope for this branch per spec FR-12/§8, correctly not re-implemented). |
| AC-12 | NFR-2, FR-13 | PASS | `execute_propagatesNotionFailureFromVerifyWithoutWritingLedger` / `…FromCreateWithoutWritingLedger` (`CreateResourcesDatabaseServiceTest.java:218-241`) propagate `NotionApiException` built only from status/code/message (`NotionClient` — token never placed in exception text, reused unchanged). No new log/detail string in `CreateResourcesDatabaseService.java` references the token. |
| AC-13 | FR-5 | PASS | `createDatabase_postsUrlPropertyWithEmptyUrlConfig` and `repairShape_addsMissingUrlPropertyWithEmptyUrlConfig` (`NotionProvisioningAdapterDatabaseTest.java:78-116`) assert exactly `{"type":"url","url":{}}` — `type=="url"`, nested `url` object empty, no extraneous keys — on both create and add-missing repair. |

All FR-1..FR-13 and NFR-1..NFR-6 traced above or in §4; none unaddressed.

## 4. Design conformance

Checked against `02-architecture.md` §5 and `03-tech-spec.md` §1/§3:

- `NotionPropertyType` (`NotionPropertyType.java:3`) — additive-only: `{TITLE, RICH_TEXT, SELECT, DATE, URL}`, matches architecture §5.1 exactly, no reordering.
- `NotionProvisioningAdapter.propertyConfig` (`NotionProvisioningAdapter.java:262-271`) — exactly one new `case URL -> Map.of("type", "url", "url", Map.of())` inserted before `SELECT`, matching tech-spec §1.2's exact diff; `TITLE`/`RICH_TEXT`/`DATE`/`SELECT` branches byte-for-byte unchanged. Switch remains exhaustive, no `default` added (confirmed by reading the full file, `NotionProvisioningAdapter.java:1-272` — no other line touched).
- `CreateResourcesDatabaseService` (`CreateResourcesDatabaseService.java`) — 3-arg constructor (`NotionProvisioningPort`, `WorkspaceRepository`, `WorkspaceLedgerWriter`), verbatim structural mirror of `CreateTasksDatabaseService`'s warm/cold path algorithm with `TASKS_DB → RESOURCES_DB`, no `@Transactional` on the class or `execute` (verified both structurally and by the reflection test `execute_isNotAnnotatedTransactional`). `resourcesSpec()`/`resourcesExpectedShape()` are exactly the two-property schema (`Title`:TITLE, `URL`:URL) — no `SELECT`, no `DATE`, matching architecture §5.3/§5.4 and tech-spec §2/§3.2.
- Transaction boundary: sole `@Transactional` is `WorkspaceLedgerWriter.record` (`WorkspaceLedgerWriter.java:18`), read via `WorkspaceRepository.findById`. Matches tech-spec §3.4 table exactly.
- `CreateResourcesDatabaseUseCase` (`CreateResourcesDatabaseUseCase.java`) — signature `ProvisioningStepResult execute(UUID)` unchanged; `CreateWorkspaceService.java:59` wiring pre-existing and untouched, matching FR-12/architecture §"Reused UNCHANGED".
- No deviation from `02-architecture.md`/`03-tech-spec.md` found.

## 5. Coverage gaps

None material. One documentation-only discrepancy (not an implementation gap): `03-tech-spec.md:415` states "File total after this change: 15 test methods (13 existing + 2 new)" for `NotionProvisioningAdapterDatabaseTest`, but the file independently counts **20** `@Test` methods (verified via `grep -c "@Test"`) both before and after — i.e. the tech-spec's baseline count of "13 existing" was already stale relative to the actual pre-branch file (likely additional tests landed in a prior pass not reflected in the tech-spec's traceability note). The two Resources/URL-specific tests required by spec AC-13/FR-5 are present and correctly assert the exact JSON shape; this is purely a stale count in the tech-spec narrative, not a missing test.

Every FR/NFR/AC has a directly corresponding, correctly-asserting test (Mockito unit test, adapter `MockRestServiceServer` contract test, or Testcontainers IT). No acceptance criterion is exercised only indirectly or left to inference.

## 6. Violations

**None found.**

Scope-honesty check confirmed: exactly the three intended production files changed —
1. `application/port/NotionPropertyType.java` — additive `URL` member only, `{TITLE, RICH_TEXT, SELECT, DATE}` order preserved (`NotionPropertyType.java:3`).
2. `infrastructure/adapter/notion/NotionProvisioningAdapter.java` — `propertyConfig` gains exactly one `case URL` branch; no other method/import/branch touched (`NotionProvisioningAdapter.java:262-271`).
3. `application/usecase/resource/CreateResourcesDatabaseService.java` — stub replaced with the real algorithm.

Confirmed untouched: `NotionClient` (no changes found), `NotionProvisioningPort` interface (method signatures identical to those called by the service — `createDatabase`, `verify`, `findChildByIdentity`, `repairShape`, plus the unrelated page/relation/rollup/formula/sample methods stubbed as before), `WorkspaceLedgerWriter` (`WorkspaceLedgerWriter.java` — `record`'s own `@Transactional`, unchanged), `domain/resource/*` (not modified — `grep` for `NotionPropertyType.URL` usage found it only in the three production/test files listed, confirming `URL` was not retrofitted onto Projects/Tasks/Knowledge/Habits/Journal schemas). `CreateWorkspaceService.java` orchestration wiring at line 59 is pre-existing and unmodified.

## Summary for routing

- Verdict: **PASS**
- Unit tier: `Tests run: 281, Failures: 0, Errors: 0, Skipped: 0`
- Failsafe tier: `Tests run: 29, Failures: 0, Errors: 0, Skipped: 0` (includes `CreateResourcesDatabaseServiceIT: Tests run: 4, Failures: 0, Errors: 0, Skipped: 0`)
- Violations: **0**
