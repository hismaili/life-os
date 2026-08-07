# 03 — Technical Specification: Create Habits Database (Phase B — fourth child database)

Status: Ready for Implementer
Owner (SME stage): pipeline automation
Input: `docs/pipeline/create-habits-database/02-architecture.md` (FINAL, no open questions) + reused by reference `../create-tasks-database/adr/ADR-0009-taskstatus-select-option-labels.md` (Accepted, applied verbatim to `Frequency`) + `../create-projects-database/adr/ADR-0005..0008` (not restated) + existing code under `backend/src/main/java/com/lifeos/`.
Grounding skills applied: `spring-boot-conventions`, `spring-data-jpa` (N/A — no schema/migration change; no `HabitRepository`/table exists), `spring-security` (N/A — no new auth surface, token handling reused verbatim), `spring-testing`.

> This is a **lean, pattern-application spec** — leaner than Tasks. The entire production delta is one class, `CreateHabitsDatabaseService`, mirrored verbatim off the already-shipped `CreateTasksDatabaseService` (`backend/src/main/java/com/lifeos/application/usecase/task/CreateTasksDatabaseService.java`) with `TASKS_DB → HABITS_DB`, `TaskStatus → Frequency`, and a Habits-specific schema that has **one fewer property than Tasks** (no `Description`, no `Due Date` — `Habit` has neither field). No new ADR; ADR-0009 (select-option labels from `Enum::name()`) applies verbatim to `Frequency`. No port, adapter, or domain change — confirmed in §1.

---

## 1. Package layout (file-by-file change list)

```
com.lifeos
 ├─ domain.habit/
 │    Habit.java                                (UNCHANGED — backend/src/main/java/com/lifeos/domain/habit/Habit.java)
 │    Frequency.java                             (UNCHANGED — backend/src/main/java/com/lifeos/domain/habit/Frequency.java;
 │                                                             plain enum {DAILY, WEEKLY, MONTHLY}, no displayName())
 │
 ├─ application
 │   ├─ port/                                   (ALL UNCHANGED — NotionProvisioningPort, DatabaseSpec, ExpectedShape,
 │   │                                            PropertyDefinition, NotionPropertyType, VerificationResult)
 │   └─ usecase.habit/
 │        CreateHabitsDatabaseService.java       [MODIFIED] stub removed; 3-arg constructor (+WorkspaceRepository);
 │                                                          real verify/create/adopt/repair algorithm targeting HABITS_DB;
 │                                                          adds habitsSpec()/habitsExpectedShape() private-static helpers
 │        CreateHabitsDatabaseUseCase.java        (unchanged — ProvisioningStepResult execute(UUID))
 │
 └─ infrastructure.adapter.notion/               (ALL UNCHANGED — NotionProvisioningAdapter, NotionClient,
                                                    NotionApiException, NotionProperties, dto/*)
```

**Confirmed NOT modified (per architecture "Reused UNCHANGED" table and §8 findings):**
- `application.port.NotionProvisioningPort` and every record/enum in `application.port` (`DatabaseSpec`, `ExpectedShape`, `PropertyDefinition`, `NotionPropertyType`, `VerificationResult`) — already generic; Habits passes a `HABITS_DB` `ProvisionedResourceType` and its own `DatabaseSpec`/`ExpectedShape` instances, same as Tasks does today.
- `infrastructure.adapter.notion.NotionProvisioningAdapter` — the four database methods (`createDatabase`/`verify`/`findChildByIdentity`/`repairShape`) are already implemented and generic over `ProvisionedResourceType` + the typed schema value types; zero code changes needed for Habits to use them.
- `infrastructure.adapter.notion.NotionClient` and its DTOs — reused verbatim, no new endpoint, no new transport concern.
- `application.usecase.workspace.WorkspaceLedgerWriter` — reused verbatim (`record(workspaceId, type, notionId)`, its own `@Transactional`).
- `domain.habit.Habit` / `domain.habit.Frequency` — **no domain change**. `Habit` already carries `id`, `name`, `frequency`, `workspaceId`, `areaId` (`Habit.java` l.11–15) — every field the §2 schema needs already exists. `Frequency` is a plain 3-value enum with no `displayName()`, mirroring `TaskStatus`.
- `domain.workspace.ProvisionedResourceType.HABITS_DB` — already defined (`ProvisionedResourceType.java` l.5), no enum change.

If any of the above proves insufficient during implementation, that is an **Architect-level finding** (`findings.yml`, `raised_by: spring-sme`, `suspected_layer: architecture`) — do not redesign silently (architecture "Reused UNCHANGED" note, §8 finding 5).

**Ripple:** none. The Habits pass makes **zero** signature changes to any shared type — every touched interface/record already has the shape Habits needs. No other test file requires editing to compile.

No change to `domain.workspace`, `infrastructure.adapter.persistence`, `infrastructure.adapter.web`/`adapter.cli`, any Flyway migration, or `application.yml`. Package-by-feature preserved (`spring-boot-conventions`).

---

## 2. Exact schema (title + properties, ADR-0006 mapping, ADR-0009 labels)

Grounded in the complete `Habit` aggregate (`domain/habit/Habit.java` l.11–15); both schema fields already exist — no domain change. **Exactly two properties** — one fewer than Tasks (no `Description`, no `Due Date`; `Habit` has neither field).

| §2 property | Field grounding | `NotionPropertyType` | Notion config | Options |
|---|---|---|---|---|
| **Name** (db title property) | `Habit.name` (`Habit.java` l.12, non-blank via `Habit.create`) | `TITLE` | `{ "type": "title", "title": {} }` | — |
| **Frequency** | `Habit.frequency` / `Frequency` (l.13) | `SELECT` | `{ "type": "select", "select": { "options": [...] } }` | `Frequency.values()` mapped via `Enum::name` (ADR-0009): `["DAILY", "WEEKLY", "MONTHLY"]`, in enum declaration order |

Database (page) title: `"Habits"` (fixed constant, matches architecture §3/§4.1; not derived from the workspace name — identity is already scoped by the unique Dashboard parent, same rule as Tasks/Projects).

**Naming note:** the title *property* is named `"Name"` (matching `Habit.name`), as Projects used `"Name"` (matching `Project.name`) and Tasks used `"Title"` (matching `Task.title`) — each database names its title property after its own aggregate's field. No structural difference; both are the single `TITLE`-typed property (architecture §4.2).

**Excluded from this schema** (architecture §4.2, FR-14): `Habit.areaId → Areas` relation (deferred to Phase C — Create Relations; requires the Areas database to exist first) and any rollup/formula/row. `Habit.workspaceId` is expressed structurally (child of the Dashboard page), not as a column — same convention as Tasks/Projects. **No `Description` and no date property** — hard boundary, `Habit` has neither field (unlike an analogy to Tasks' `Description`/`Due Date`).

**Verify is name-only** (ADR-0008, inherited unchanged): a user renaming/adding Frequency options or extra columns in Notion never triggers repair; the enum-seeded option labels apply at **creation** only. ADR-0009's choice to use `Frequency.name()` (not a humanized label) can therefore never cause spurious drift or repair — it is a cosmetic decision only (ADR-0009 Consequences, applied by reference).

---

## 3. `application.usecase.habit.CreateHabitsDatabaseService` [MODIFIED]

### 3.1 Current state (to be replaced)

```java
package com.lifeos.application.usecase.habit;

import com.lifeos.application.dto.workspace.ProvisioningStepResult;
import com.lifeos.application.port.NotionProvisioningPort;
import com.lifeos.application.usecase.workspace.WorkspaceLedgerWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CreateHabitsDatabaseService implements CreateHabitsDatabaseUseCase {

    private final NotionProvisioningPort notion;
    private final WorkspaceLedgerWriter ledger;

    @Override
    public ProvisioningStepResult execute(UUID workspaceId) {
        throw new UnsupportedOperationException(
                "Habits database creation not yet implemented: requires the Notion adapter");
    }
}
```

(`backend/src/main/java/com/lifeos/application/usecase/habit/CreateHabitsDatabaseService.java`, current 2-arg constructor + stub throw — NFR-4/"no silent no-op" is satisfied today only by the explicit throw; it is removed as this real implementation lands.)

### 3.2 Full intended source (verbatim mirror of `CreateTasksDatabaseService`, `TASKS_DB → HABITS_DB`)

```java
package com.lifeos.application.usecase.habit;

import com.lifeos.application.dto.workspace.ProvisioningOutcome;
import com.lifeos.application.dto.workspace.ProvisioningStepResult;
import com.lifeos.application.port.DatabaseSpec;
import com.lifeos.application.port.ExpectedShape;
import com.lifeos.application.port.NotionPropertyType;
import com.lifeos.application.port.NotionProvisioningPort;
import com.lifeos.application.port.PropertyDefinition;
import com.lifeos.application.port.VerificationResult;
import com.lifeos.application.usecase.workspace.WorkspaceLedgerWriter;
import com.lifeos.domain.habit.Frequency;
import com.lifeos.domain.workspace.ProvisionedResource;
import com.lifeos.domain.workspace.Workspace;
import com.lifeos.domain.workspace.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.lifeos.domain.workspace.ProvisionedResourceType.DASHBOARD;
import static com.lifeos.domain.workspace.ProvisionedResourceType.HABITS_DB;

@Slf4j
@Service
@RequiredArgsConstructor
public class CreateHabitsDatabaseService implements CreateHabitsDatabaseUseCase {

    private static final String TITLE = "Habits";

    private final NotionProvisioningPort notion;
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceLedgerWriter ledger;

    @Override
    public ProvisioningStepResult execute(UUID workspaceId) {
        Workspace workspace = workspaceRepository.findById(workspaceId)
                .orElseThrow(() -> new IllegalStateException("Workspace not found: " + workspaceId));

        String dashboardId = workspace.resource(DASHBOARD)
                .map(ProvisionedResource::notionId)
                .orElseThrow(() -> new IllegalStateException("No confirmed Dashboard for workspace " + workspaceId));

        DatabaseSpec spec = habitsSpec();
        ExpectedShape expected = habitsExpectedShape();
        Optional<String> ledgerId = workspace.resource(HABITS_DB).map(ProvisionedResource::notionId);

        ProvisioningStepResult result = ledgerId.isPresent()
                ? executeWarmPath(workspaceId, ledgerId.get(), dashboardId, spec, expected)
                : executeColdPath(workspaceId, dashboardId, spec, expected);

        log.info("Habits database step for workspace {}: dashboardId={}, priorLedgerId={}, outcome={}",
                workspaceId, dashboardId, ledgerId.orElse("none"), result.outcome());
        return result;
    }

    private ProvisioningStepResult executeWarmPath(UUID workspaceId, String existingId, String dashboardId,
                                                     DatabaseSpec spec, ExpectedShape expected) {
        VerificationResult result = notion.verify(existingId, HABITS_DB, expected);
        log.info("Habits database verify for workspace {}: notionId={}, result={}", workspaceId, existingId, result);
        return switch (result) {
            case PRESENT_MATCHING -> new ProvisioningStepResult(HABITS_DB, ProvisioningOutcome.RECONCILED, null);
            case PRESENT_DRIFTED -> {
                notion.repairShape(existingId, expected);
                ledger.record(workspaceId, HABITS_DB, existingId);
                yield new ProvisioningStepResult(HABITS_DB, ProvisioningOutcome.REPAIRED, "database drifted; shape repaired");
            }
            case ABSENT -> {
                Optional<String> found = notion.findChildByIdentity(dashboardId, HABITS_DB, expected);
                if (found.isPresent()) {
                    ledger.record(workspaceId, HABITS_DB, found.get());
                    yield new ProvisioningStepResult(HABITS_DB, ProvisioningOutcome.REPAIRED, "ledger id was stale; re-adopted existing database");
                }
                String newId = notion.createDatabase(dashboardId, spec);
                ledger.record(workspaceId, HABITS_DB, newId);
                yield new ProvisioningStepResult(HABITS_DB, ProvisioningOutcome.REPAIRED, "ledger id was stale; database recreated");
            }
        };
    }

    private ProvisioningStepResult executeColdPath(UUID workspaceId, String dashboardId, DatabaseSpec spec, ExpectedShape expected) {
        Optional<String> found = notion.findChildByIdentity(dashboardId, HABITS_DB, expected);
        log.info("Habits database findChildByIdentity for workspace {}: found={}", workspaceId, found.isPresent());
        if (found.isEmpty()) {
            String newId = notion.createDatabase(dashboardId, spec);
            ledger.record(workspaceId, HABITS_DB, newId);
            return new ProvisioningStepResult(HABITS_DB, ProvisioningOutcome.CREATED, null);
        }

        String orphanId = found.get();
        VerificationResult orphanVerify = notion.verify(orphanId, HABITS_DB, expected);
        return switch (orphanVerify) {
            case PRESENT_MATCHING -> {
                ledger.record(workspaceId, HABITS_DB, orphanId);
                yield new ProvisioningStepResult(HABITS_DB, ProvisioningOutcome.RECONCILED, null);
            }
            case PRESENT_DRIFTED -> {
                notion.repairShape(orphanId, expected);
                ledger.record(workspaceId, HABITS_DB, orphanId);
                yield new ProvisioningStepResult(HABITS_DB, ProvisioningOutcome.REPAIRED, "adopted orphan database was drifted; shape repaired");
            }
            case ABSENT -> {
                String newId = notion.createDatabase(dashboardId, spec);
                ledger.record(workspaceId, HABITS_DB, newId);
                yield new ProvisioningStepResult(HABITS_DB, ProvisioningOutcome.CREATED, null);
            }
        };
    }

    static DatabaseSpec habitsSpec() {
        List<String> frequencyOptions = Arrays.stream(Frequency.values())
                .map(Enum::name)
                .toList();
        return new DatabaseSpec(TITLE, List.of(
                PropertyDefinition.of("Name", NotionPropertyType.TITLE),
                new PropertyDefinition("Frequency", NotionPropertyType.SELECT, frequencyOptions)));
    }

    static ExpectedShape habitsExpectedShape() {
        return new ExpectedShape(TITLE, habitsSpec().properties());
    }
}
```

**Line-level delta from `CreateTasksDatabaseService.java` (for reference — the Implementer may diff against it directly):**
1. Package `com.lifeos.application.usecase.task` → `com.lifeos.application.usecase.habit`.
2. Import `com.lifeos.domain.task.TaskStatus` → `com.lifeos.domain.habit.Frequency`.
3. Static import `TASKS_DB` → `HABITS_DB`.
4. `TITLE = "Tasks"` → `TITLE = "Habits"`.
5. Every `TASKS_DB` token (method bodies, log messages' semantic meaning stays the same "Habits database …" phrasing) → `HABITS_DB`.
6. `tasksSpec()`/`tasksExpectedShape()` → `habitsSpec()`/`habitsExpectedShape()`.
7. In `habitsSpec()`: `TaskStatus.values()` → `Frequency.values()` (still `Enum::name` per ADR-0009 — `Frequency`, like `TaskStatus`, has no `displayName()`); property list becomes exactly `"Name"`(TITLE)/`"Frequency"`(SELECT) — **`"Description"`(RICH_TEXT) and `"Due Date"`(DATE) are dropped entirely**, not merely renamed. This is the one substantive structural difference from the Tasks mirror.
8. Constructor, `execute`, `executeWarmPath`, `executeColdPath` bodies are otherwise **byte-for-byte structurally identical** (same branching, same log statement shape, same outcome/detail strings) — the algorithm does not vary with property count.

### 3.3 Outcome decision table

Reused verbatim from Tasks/Projects (architecture §3 "Outcome decision table"), substituting `HABITS_DB`. Not restated as a new table — the 9-row shape (`CREATED` only on first-time create with no prior ledger record; adoption is never `CREATED`; `REPAIRED` ⇔ a Notion write happened this run; `RECONCILED` ⇔ none; `>1` identity match ⇒ `FAILED` via propagated `NotionApiException`) applies identically; see §5 test plan for the per-row test mapping.

### 3.4 Transaction boundary — summary

| Class | Transaction | Notes |
|---|---|---|
| `CreateHabitsDatabaseService.execute` | **none** | Pure port orchestration; mirrors `CreateTasksDatabaseService` — several Notion HTTP calls would hold a DB connection across slow remote work if annotated. |
| `WorkspaceLedgerWriter.record` | `@Transactional` (existing, unchanged) | The **only** transactional write in this step. |
| `JpaWorkspaceRepository.findById` | `@Transactional(readOnly = true)` (existing, unchanged) | Read path for `workspaceRepository.findById`. |
| `NotionProvisioningAdapter.*` / `NotionClient.*` | none | Not a transactional resource; unchanged. |

No new `@Service`/`@Component`/`@Repository` bean.

---

## 4. Security

- **Token handling**: unchanged. `NotionClient` remains the only class reading the token; `CreateHabitsDatabaseService` never touches it.
- **Token-never-leaked (NFR-6)**: enforced structurally exactly as Tasks — `NotionApiException` messages built only from status + Notion's `code`/`message`, plus the reused `>1`-match message which interpolates only the match count and `expected.title()` (constant `"Habits"`, not a secret).
- **No REST/CLI authn change, no OAuth, no per-Person token, no new config property.**

---

## 5. Test plan (write these first — TDD)

Narrowest-sufficient tier per class (`spring-testing`). Build/verify order: service (mocked port) → optional wiring IT. **No new value-type unit tests** (`PropertyDefinition`/`DatabaseSpec`/`ExpectedShape` are unchanged and already covered by the Projects pass's `PropertyDefinitionTest`/`DatabaseSpecTest`/`ExpectedShapeTest`), **no new domain unit tests** (`Habit`/`Frequency` unchanged, no domain change), **no new adapter contract tests** (see §5.3). No live Notion, no token, no network egress anywhere.

### 5.1 `application/usecase/habit/CreateHabitsDatabaseServiceTest.java` [NEW — full rewrite of the existing stub test]

Delete the existing single test (`execute_throwsUnsupportedOperationExceptionUntilNotionAdapterExists`, 2-arg constructor — `backend/src/test/java/com/lifeos/application/usecase/habit/CreateHabitsDatabaseServiceTest.java`) — the constructor becomes 3-arg, a breaking change. Mirror `CreateTasksDatabaseServiceTest` (`backend/src/test/java/com/lifeos/application/usecase/task/CreateTasksDatabaseServiceTest.java`) exactly, substituting `HABITS_DB`/`Frequency`/`habitsSpec`/`habitsExpectedShape` and the Habits property names/options. Mocks: `NotionProvisioningPort notion`, `WorkspaceRepository workspaceRepository`, `WorkspaceLedgerWriter ledger`. Fixture helper: `Workspace.reconstitute(id, UUID.randomUUID(), "Personal", List.of()).record(DASHBOARD, "dash-id")` (and `.record(HABITS_DB, notionId)` for warm-path fixtures).

**Expected count: 17 tests** (same count as Tasks §9.1 — property-count reduction does not remove any outcome/precondition/failure-propagation test, only shrinks the assertion payload of tests 3 and 16) — one per outcome-table row (9) + preconditions (2) + ambiguous-match propagation (2, cold and warm-ABSENT) + Notion-failure propagation without ledger write (2) + never-invokes-unrelated-port-methods (1) + not-`@Transactional` reflection (1) + `habitsSpec()`/`habitsExpectedShape()` direct assertions (2). Full method list:

1. `execute_throwsWhenWorkspaceNotFound` — `workspaceRepository.findById` empty → `IllegalStateException("Workspace not found: " + id)`; `verifyNoInteractions(notion)`, `verifyNoInteractions(ledger)` (FR-2).
2. `execute_throwsWhenNoDashboardLedgerEntry` — workspace present, no `DASHBOARD` resource → `IllegalStateException("No confirmed Dashboard for workspace " + id)`; `verifyNoInteractions(notion)`, `verifyNoInteractions(ledger)` (FR-3).
3. `execute_createsWhenColdAndNoOrphan` — no `HABITS_DB` resource; `findChildByIdentity(dashId, HABITS_DB, expected)` → `Optional.empty()`; `createDatabase(dashId, spec)` → `"new-db-id"`; capture the `DatabaseSpec` passed to `createDatabase` and assert `properties()` has exactly **2** entries in order `Name`(TITLE)/`Frequency`(SELECT), and the `Frequency` property's `options()` equals `["DAILY", "WEEKLY", "MONTHLY"]`; `ledger.record(workspaceId, HABITS_DB, "new-db-id")` called once; outcome `CREATED`, `detail` null (row 1).
4. `execute_adoptsWhenColdAndOrphanMatches` — `findChildByIdentity` → `Optional.of("orphan-id")`; `verify("orphan-id", HABITS_DB, expected)` → `PRESENT_MATCHING`; assert **no** `createDatabase`/`repairShape`; `ledger.record(workspaceId, HABITS_DB, "orphan-id")`; outcome `RECONCILED` (row 2).
5. `execute_adoptsAndRepairsWhenColdAndOrphanDrifted` — `findChildByIdentity` → present; `verify` → `PRESENT_DRIFTED`; assert `repairShape("orphan-id", expected)` called once, `ledger.record`; outcome `REPAIRED` (row 3).
6. `execute_propagatesAmbiguousMatchFailureOnColdPath` — `findChildByIdentity` throws `NotionApiException`; assert propagation (`isSameAs`), `verifyNoInteractions(ledger)` (row 4).
7. `execute_reconcilesWhenWarmAndMatching` — `HABITS_DB` resource present (`"existing-id"`); `verify` → `PRESENT_MATCHING`; assert **no** write, **no** `ledger.record`, **no** `findChildByIdentity` call; outcome `RECONCILED` (row 5).
8. `execute_repairsWhenWarmAndDrifted` — `verify` → `PRESENT_DRIFTED`; assert `repairShape("existing-id", expected)`, `ledger.record(workspaceId, HABITS_DB, "existing-id")`; outcome `REPAIRED` (row 6).
9. `execute_reAdoptsWhenWarmAndDeletedAndOrphanFound` — `verify` → `ABSENT`; `findChildByIdentity` → `Optional.of("orphan-id")`; assert **no** `createDatabase`, `ledger.record(workspaceId, HABITS_DB, "orphan-id")`; outcome `REPAIRED` (row 7).
10. `execute_reCreatesWhenWarmAndDeletedAndNoOrphanFound` — `verify` → `ABSENT`; `findChildByIdentity` → `Optional.empty()`; assert `createDatabase` called, `ledger.record`; outcome `REPAIRED` (row 8).
11. `execute_propagatesAmbiguousMatchFailureOnWarmAbsentPath` — `verify` → `ABSENT`; `findChildByIdentity` throws `NotionApiException`; assert propagation, `verifyNoInteractions(ledger)` (row 9).
12. `execute_propagatesNotionFailureFromVerifyWithoutWritingLedger` — `verify` throws `NotionApiException` directly (transport failure, not `ABSENT`); assert propagation, `verifyNoInteractions(ledger)` (FR-12).
13. `execute_propagatesNotionFailureFromCreateWithoutWritingLedger` — cold path, `findChildByIdentity` empty, `createDatabase` throws; assert propagation, `verifyNoInteractions(ledger)` (Notion-write-before-ledger-write ordering).
14. `execute_neverInvokesRelationRollupFormulaOrSampleOrPageMethods` — on a happy-path execution, `verify(notion, never())` for `ensureRelation`/`ensureRollup`/`ensureFormula`/`hasSampleRecords`/`insertSampleRecords`/`createRootPage`/`verifyPage`/`repairPage`/`findRootByIdentity` (FR-14).
15. `execute_isNotAnnotatedTransactional` — reflection: `CreateHabitsDatabaseService.class.getMethod("execute", UUID.class).isAnnotationPresent(Transactional.class) == false`, and the class itself is not `@Transactional`.
16. `habitsSpec_buildsTwoPropertiesWithFrequencyOptionsFromEnum` — direct unit test of the package-private static method: asserts title `"Habits"`, exactly **2** properties in order — `Name`(TITLE)/`Frequency`(SELECT, options = `Frequency.values()` names in enum declaration order = `["DAILY","WEEKLY","MONTHLY"]`). Compare each `PropertyDefinition` via `.isEqualTo(...)` the same way Tasks' `tasksSpec_buildsFourPropertiesWithStatusOptionsFromEnum` does; also assert `properties()` does **not** contain a `Description` or `Due Date`/date-typed property (FR-14 negative assertion, specific to Habits' narrower schema).
17. `habitsExpectedShape_matchesSpecProperties` — `habitsExpectedShape().requiredProperties()` equals `habitsSpec().properties()`; `habitsExpectedShape().title()` equals `"Habits"`.

### 5.2 `application/usecase/habit/CreateHabitsDatabaseServiceIT.java` [NEW]

Mirror `CreateTasksDatabaseServiceIT` (`backend/src/test/java/com/lifeos/application/usecase/task/CreateTasksDatabaseServiceIT.java`) exactly: `@SpringBootTest` + `@Testcontainers` Postgres container, a `@TestConfiguration`-supplied `@Primary` `InMemoryDatabaseOnlyNotionPort` fake implementing only the four database methods realistically (create assigns a UUID string id, stores title + property-name set; verify/find/repair read/mutate the map) plus the four page methods delegating to a fixed pre-adopted Dashboard id, and every other port method throwing `UnsupportedOperationException`. `@BeforeEach` clears the fake's static map. This fake class already exists (shipped for Tasks) — reuse it directly rather than duplicating it, unless the Implementer finds it package-private to `usecase.task`, in which case relocate it to a shared test-support package (a build-mechanics decision, not a design one).

4 tests, substituting `HABITS_DB`/`createHabitsDatabase`:

1. `execute_persistsHabitsDbLedgerRowOnFirstRun` — a fresh workspace with a pre-seeded `DASHBOARD` resource → `execute` → outcome `CREATED`; a direct repository read shows exactly one `HABITS_DB` `ProvisionedResource` row.
2. `execute_convergesToOneRowAcrossThreeReruns` — run `execute` three times in sequence → still exactly one `HABITS_DB` row after all three; second/third outcomes are `RECONCILED` (FR-13); asserts the same `notionId` across all three reads. **This is the multi-run convergence case explicitly called for by the orchestration prompt** — no separate class needed, it is this test method.
3. `execute_reachesRepairedOutcomeWhenFakeSimulatesExternalPropertyRemoval` — after the first run, mutate the fake's stored properties map directly (simulating an out-of-band Notion property deletion, e.g. remove `"Frequency"`) → second `execute` → `PRESENT_DRIFTED` → `REPAIRED`, ledger row's `notionId` unchanged.
4. `execute_throwsWhenPhaseAIncomplete` — a workspace with **no** `DASHBOARD` resource → `execute` throws `IllegalStateException`, and the repository shows **no** `HABITS_DB` row.

Class name ends in `IT` so Failsafe runs it under `./mvnw verify` (already configured). Zero real Notion calls; no `MockRestServiceServer` in this class.

### 5.3 No new adapter contract tests — explicit note

`NotionProvisioningAdapter`'s database slice (`createDatabase`/`verify`/`findChildByIdentity`/`repairShape`) is **generic over `ProvisionedResourceType` + the typed `DatabaseSpec`/`ExpectedShape`/`PropertyDefinition`** and is already fully contract-tested end-to-end by `NotionProvisioningAdapterDatabaseTest` (shipped under the Projects pass — see `docs/pipeline/create-projects-database/03-tech-spec.md` §9.2). Passing it a `HABITS_DB` type with a Habits `DatabaseSpec`/`ExpectedShape` exercises the exact same request-building and response-parsing code paths already proven for `PROJECTS_DB`/`TASKS_DB`. **Do not duplicate those adapter tests for Habits.** The correctness obligation that *is* new — that `CreateHabitsDatabaseService` builds and passes the *right* (two-property, `Frequency`-backed) `DatabaseSpec`/`ExpectedShape` to the (mocked) port — is covered instead at the service-unit level by §5.1 tests 3 and 16 (which assert the exact captured/returned `DatabaseSpec`/`ExpectedShape` contents, including the absence of `Description`/`Due Date`), and at the IT level by §5.2 (which exercises the real algorithm against a fake port).

---

## 6. Explicitly NOT built in this pass (scope guard for the Implementer)

- **Any `Habits ↔ Areas` relation**, or any relation/rollup/formula of any kind. `ensureRelation`/`ensureRollup`/`ensureFormula` are untouched, unchanged stubs. `Habit.areaId` remains a UUID-only reference — do not add a Notion relation column for it (deferred to Phase C — Create Relations; FR-14).
- **Rows or sample data (check-ins).** `hasSampleRecords`/`insertSampleRecords` untouched. This step provisions the container only — zero Notion database rows are written, and no habit-completion/check-in tracking concept is introduced.
- **A `Description` or any date property.** Unlike Tasks, do not add either by analogy — `Habit` has neither field; this is a hard scope boundary, not an oversight.
- **The other databases** (Projects, Tasks, Knowledge already shipped; Journal, Resources, People remain). Only `HABITS_DB` is exercised. Do not generalize `habitsSpec()`/`tasksSpec()` into a shared multi-database schema builder in this pass (YAGNI — each sibling step authors its own schema when it is built, per architecture framing).
- **Any change to `Habit` or `Frequency`.** No new field, no `HabitRepository`, no JPA entity, no Flyway migration, no reconstitution factory.
- **`Frequency.displayName()`.** Not authorized by this pass, mirroring ADR-0009's "Tracked follow-up" treatment of `TaskStatus`. `habitsSpec()` uses `Enum::name` verbatim, not a humanized label.
- **Any change to the adapter, `NotionClient`, the port interface, or any typed schema value type** (`DatabaseSpec`/`ExpectedShape`/`PropertyDefinition`/`NotionPropertyType`) — all reused byte-for-byte.
- **A new ADR.** ADR-0009 is cited and applied by reference; no `ADR-0011` (or any other number) is authored for this step (architecture "New decision this branch makes" = None).
- **`docs/productivity/*` population** or any other documentation content change.
- **A semantic Notion-Version range/comparison check, new config property, or profile change.** Nothing here needs one.

---

## 7. Traceability (FR/NFR → spec section)

| Req | Satisfied by (this spec) |
|---|---|
| FR-1 | §3.2 unchanged `CreateHabitsDatabaseUseCase.execute(UUID)` signature |
| FR-2 | §3.2 `workspaceRepository.findById` → `IllegalStateException` before any Notion call; test 5.1-1 |
| FR-3 | §3.2 `resource(DASHBOARD)` empty → `IllegalStateException`; test 5.1-2 |
| FR-4 | Cold path `findChildByIdentity` empty → `createDatabase` → `record` → `CREATED`; §3.3 row 1; test 5.1-3 |
| FR-5 | Warm `verify` `PRESENT_MATCHING` → `RECONCILED`, no write; §3.3 row 5; test 5.1-7 |
| FR-6a | Warm `ABSENT` → adopt-or-`createDatabase` → `REPAIRED`; §3.3 rows 7/8; tests 5.1-9, 5.1-10 |
| FR-6b | Warm `PRESENT_DRIFTED` → `repairShape` (non-destructive, reused adapter) → `REPAIRED`; test 5.1-8 |
| FR-7 | `verify`/`findChildByIdentity` on every path before any `RECONCILED`; §3.2; ADR-0008 (reused) |
| FR-8 | Cold `findChildByIdentity` (parent-scoped enumeration) → adopt; §3.2; ADR-0008 |
| FR-9 | `>1` child_database match → `NotionApiException` → `FAILED`; tests 5.1-6, 5.1-11 |
| FR-10 | `WorkspaceLedgerWriter.record` — own tx (reused); §3.4 |
| FR-11 | Returns `ProvisioningStepResult(HABITS_DB, …)`; failures propagate unmodified; §3.2 |
| FR-12 | Notion-before-ledger ordering + own-tx write + next-run adoption; tests 5.1-12, 5.1-13 |
| FR-13 | Adoption-before-create both paths; upsert `record`; IT convergence test 5.2-2 |
| FR-14 | Only the four DB port methods invoked; test 5.1-14; schema has no relation/Description/date property (§2); negative assertion in test 5.1-16 |
| FR-15 | Frequency `SELECT` options = `Frequency.values()` names, one per constant; test 5.1-3 and 5.1-16 (ADR-0009) |
| §3 schema + domain backing | §2 (Name/Frequency grounded in `Habit.java`); ADR-0006 (reused), ADR-0009 (reused) |
| NFR-1 | Strict per-path live verification; §3.2 (inherited ADR-0002/ADR-0008) |
| NFR-2 | Notion-before-ledger + no rollback; next-run reconcile; §3.2; non-destructive repair (reused adapter) |
| NFR-3 | Mockito service tests + fake-port IT; adapter reused/already covered; §5 |
| NFR-4 | Stub `UnsupportedOperationException` removed only as the real impl lands; §3.1/§3.2 |
| NFR-5 | `log.info` per run (workspaceId, dashboardId, prior ledger id, `VerificationResult`, acted-on id, outcome — no token/raw body); §3.2 |
| NFR-6 | §4 Security; message-construction rule reused from `NotionClient` |
| NFR-7 | No shared mutable state; only `HABITS_DB` ledger entry written; §3.4 |
| NFR-8 | Upsert `record` → exactly one `HABITS_DB` entry (`Workspace.record` semantics, reused) |
| NFR-9 | Bounded call count per run (reused adapter, unchanged budget) |
| NFR-10 | `429`/`529` `Retry-After` clamp in reused `NotionClient` (unchanged) |

---

## 8. Implementation notes (file list for the Implementer)

- `backend/src/main/java/com/lifeos/application/usecase/habit/CreateHabitsDatabaseService.java` — replace stub with §3.2's full source; 3-arg constructor (`NotionProvisioningPort`, `WorkspaceRepository`, `WorkspaceLedgerWriter`); `habitsSpec()`/`habitsExpectedShape()` package-private statics; SLF4J logging per NFR-5; no `@Transactional`.
- `backend/src/test/java/com/lifeos/application/usecase/habit/CreateHabitsDatabaseServiceTest.java` — fully rewritten (3-arg constructor), 17 tests (§5.1).
- `backend/src/test/java/com/lifeos/application/usecase/habit/CreateHabitsDatabaseServiceIT.java` — new `@SpringBootTest` + Testcontainers Postgres IT, 4 tests (§5.2); fake port's in-memory map cleared in `@BeforeEach`.

No changes to any other file. No changes to `WorkspaceController`, `WorkspaceCommands`, `ApiExceptionHandler`, any web/CLI DTO, `infrastructure.adapter.persistence`, `application.yml`, or any Flyway migration.

---

## 9. Findings / notes back to the Architect

None. The architecture is complete and self-consistent for this step; no deviation was needed to produce this spec. ADR-0009 is reused by reference as written and requires no SME-level qualification beyond what §2 already states (verify is name-only, so the `Frequency.name()` label choice cannot cause drift).

---

## Implementation notes

- `backend/src/main/java/com/lifeos/application/usecase/habit/CreateHabitsDatabaseService.java` [MODIFIED] — stub replaced with §3.2's full source verbatim (3-arg constructor, `habitsSpec()`/`habitsExpectedShape()`, no `@Transactional`).
- `backend/src/test/java/com/lifeos/application/usecase/habit/CreateHabitsDatabaseServiceTest.java` [REWRITTEN] — 17 tests per §5.1, all green.
- `backend/src/test/java/com/lifeos/application/usecase/habit/CreateHabitsDatabaseServiceIT.java` [NEW] — 4 Testcontainers-Postgres/fake-port tests per §5.2, all green under Failsafe.
