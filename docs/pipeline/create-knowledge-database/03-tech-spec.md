# 03 — Technical Specification: Create Knowledge Database (Phase B — third child database)

Status: Ready for Implementer
Owner (SME stage): pipeline automation
Input: `docs/pipeline/create-knowledge-database/02-architecture.md` (FINAL, no open questions) + `adr/ADR-0010-content-rich-text-property-not-page-body.md` (Accepted) + reused by reference `../create-projects-database/adr/ADR-0005..0008` and `../create-tasks-database/adr/ADR-0009` (not restated) + existing code under `backend/src/main/java/com/lifeos/`.
Grounding skills applied: `spring-boot-conventions`, `spring-data-jpa` (N/A — no schema/migration change; no `KnowledgeRepository`/table exists), `spring-security` (N/A — no new auth surface, token handling reused verbatim), `spring-testing`.

> This is the **leanest** pattern-application spec of the three Phase-B database steps: the entire production delta is one class, `CreateKnowledgeDatabaseService`, mirrored verbatim off the already-shipped `CreateTasksDatabaseService` (`backend/src/main/java/com/lifeos/application/usecase/task/CreateTasksDatabaseService.java`) with `TASKS_DB → KNOWLEDGE_DB`, title `"Tasks" → "Knowledge"`, and a two-property schema (**Title + Content only** — no `TaskStatus`-equivalent enum, no `select`, no `date`). Reference pattern for structure/rigor: `docs/pipeline/create-tasks-database/03-tech-spec.md` (own-transaction ledger write, verify-before-trust, outcome mapping, Mockito service tests, Testcontainers IT). No port, adapter, or domain change — confirmed in §1.

---

## 1. Package layout (file-by-file change list)

```
com.lifeos
 ├─ domain.knowledge/
 │    Knowledge.java                             (UNCHANGED — backend/src/main/java/com/lifeos/domain/knowledge/Knowledge.java;
 │                                                             id, title, content, workspaceId, areaId; create() validates
 │                                                             non-blank title + non-null workspaceId)
 │    KnowledgeDiscoveryService.java              (UNCHANGED — unrelated existing domain service; not read, invoked, or modified)
 │
 ├─ application
 │   ├─ port/                                    (ALL UNCHANGED — NotionProvisioningPort, DatabaseSpec, ExpectedShape,
 │   │                                             PropertyDefinition, NotionPropertyType, VerificationResult)
 │   └─ usecase.knowledge/
 │        CreateKnowledgeDatabaseService.java      [MODIFIED] stub removed; 3-arg constructor (+WorkspaceRepository);
 │                                                            real verify/create/adopt/repair algorithm targeting KNOWLEDGE_DB;
 │                                                            adds knowledgeSpec()/knowledgeExpectedShape() private-static helpers
 │        CreateKnowledgeDatabaseUseCase.java       (unchanged — ProvisioningStepResult execute(UUID))
 │
 └─ infrastructure.adapter.notion/                (ALL UNCHANGED — NotionProvisioningAdapter, NotionClient,
                                                     NotionApiException, NotionProperties, dto/*)
```

**Confirmed NOT modified** (per architecture "Reused UNCHANGED" table and §8 findings):
- `application.port.NotionProvisioningPort` and every record/enum in `application.port` (`DatabaseSpec`, `ExpectedShape`, `PropertyDefinition`, `NotionPropertyType`, `VerificationResult`) — already generic; Knowledge passes a `KNOWLEDGE_DB` `ProvisionedResourceType` and its own `DatabaseSpec`/`ExpectedShape` instances, same as Projects/Tasks do today.
- `infrastructure.adapter.notion.NotionProvisioningAdapter` — the four database methods (`createDatabase`/`verify`/`findChildByIdentity`/`repairShape`) are already implemented and generic over `ProvisionedResourceType` + the typed schema value types (shipped for Projects, reused verbatim by Tasks); zero code changes needed for Knowledge to use them.
- `infrastructure.adapter.notion.NotionClient` and its DTOs — reused verbatim, no new endpoint, no new transport concern.
- `application.usecase.workspace.WorkspaceLedgerWriter` — reused verbatim (`record(workspaceId, type, notionId)`, its own `@Transactional`).
- `domain.knowledge.Knowledge` / `domain.knowledge.KnowledgeDiscoveryService` — **no domain change** (like Tasks, unlike Projects which added `ProjectStatus`/`status`/`dueDate` under OQ-A). `Knowledge` already carries `title`, `content`, `workspaceId`, `areaId` (`Knowledge.java` l.11–15) — every field the §2 schema needs already exists. `KnowledgeDiscoveryService` is not read, invoked, or modified by this step.
- `domain.workspace.ProvisionedResourceType.KNOWLEDGE_DB` — already defined (`ProvisionedResourceType.java` l.5), no enum change.

If any of the above proves insufficient during implementation, that is an **Architect-level finding** (`findings.yml`, `raised_by: spring-sme`, `suspected_layer: architecture`) — do not redesign silently (architecture "Reused UNCHANGED" note, §8 finding 5).

**Ripple:** none. Zero signature changes to any shared type — every touched interface/record already has the shape Knowledge needs. No other test file requires editing to compile.

No change to `domain.workspace`, `infrastructure.adapter.persistence`, `infrastructure.adapter.web`/`adapter.cli`, any Flyway migration, or `application.yml`. Package-by-feature preserved (`spring-boot-conventions`).

---

## 2. Exact schema (title + properties, ADR-0010 mapping)

Grounded in the complete `Knowledge` aggregate (`domain/knowledge/Knowledge.java` l.11–15); every field already exists — no domain change. This is the leanest schema of the three databases to date: **no enum, no `select`, no `date`.**

| §2 property | Field grounding | `NotionPropertyType` | Notion config |
|---|---|---|---|
| **Title** (db title property) | `Knowledge.title` (`Knowledge.java` l.12, non-blank via `Knowledge.create`) | `TITLE` | `{ "type": "title", "title": {} }` |
| **Content** | `Knowledge.content` (l.13) | `RICH_TEXT` | `{ "type": "rich_text", "rich_text": {} }` — ADR-0010 |

Database (page) title: `"Knowledge"` (fixed constant, matches architecture §3/§4.1; not derived from the workspace name — identity is already scoped by the unique Dashboard parent, same rule as Projects/Tasks).

**Naming note:** the title *property* is named `"Title"` (matching `Knowledge.title`), consistent with Tasks (`"Title"`, matching `Task.title`); Projects named it `"Name"` (matching `Project.name`). No structural difference; both are the single `TITLE`-typed property (architecture §4.2).

**Excluded from this schema** (architecture §4.2, FR-14): `Knowledge.areaId → Areas` relation (deferred to Phase C — Create Relations; requires an Areas database to exist first — none is planned by any Phase-B step) and any rollup/formula/row. `Knowledge.workspaceId` is expressed structurally (child of the Dashboard page), not as a column — same convention as Projects/Tasks.

**Verify is name-only** (ADR-0008, inherited unchanged): a user adding extra columns in Notion never triggers repair. Since there is no enum/select property, **ADR-0006's `select`-mapping branch and ADR-0009's label-sourcing decision do not apply to this step** — cited for completeness only.

---

## 3. `application.usecase.knowledge.CreateKnowledgeDatabaseService` [MODIFIED]

### 3.1 Current state (to be replaced)

```java
package com.lifeos.application.usecase.knowledge;

import com.lifeos.application.dto.workspace.ProvisioningStepResult;
import com.lifeos.application.port.NotionProvisioningPort;
import com.lifeos.application.usecase.workspace.WorkspaceLedgerWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CreateKnowledgeDatabaseService implements CreateKnowledgeDatabaseUseCase {

    private final NotionProvisioningPort notion;
    private final WorkspaceLedgerWriter ledger;

    @Override
    public ProvisioningStepResult execute(UUID workspaceId) {
        throw new UnsupportedOperationException(
                "Knowledge database creation not yet implemented: requires the Notion adapter");
    }
}
```

(`backend/src/main/java/com/lifeos/application/usecase/knowledge/CreateKnowledgeDatabaseService.java`, current 2-arg constructor + stub throw — NFR-4/"no silent no-op" is satisfied today only by the explicit throw; it is removed as this real implementation lands.)

### 3.2 Full intended source (verbatim mirror of `CreateTasksDatabaseService`, `TASKS_DB → KNOWLEDGE_DB`, no enum)

```java
package com.lifeos.application.usecase.knowledge;

import com.lifeos.application.dto.workspace.ProvisioningOutcome;
import com.lifeos.application.dto.workspace.ProvisioningStepResult;
import com.lifeos.application.port.DatabaseSpec;
import com.lifeos.application.port.ExpectedShape;
import com.lifeos.application.port.NotionPropertyType;
import com.lifeos.application.port.NotionProvisioningPort;
import com.lifeos.application.port.PropertyDefinition;
import com.lifeos.application.port.VerificationResult;
import com.lifeos.application.usecase.workspace.WorkspaceLedgerWriter;
import com.lifeos.domain.workspace.ProvisionedResource;
import com.lifeos.domain.workspace.Workspace;
import com.lifeos.domain.workspace.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.lifeos.domain.workspace.ProvisionedResourceType.DASHBOARD;
import static com.lifeos.domain.workspace.ProvisionedResourceType.KNOWLEDGE_DB;

@Slf4j
@Service
@RequiredArgsConstructor
public class CreateKnowledgeDatabaseService implements CreateKnowledgeDatabaseUseCase {

    private static final String TITLE = "Knowledge";

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

        DatabaseSpec spec = knowledgeSpec();
        ExpectedShape expected = knowledgeExpectedShape();
        Optional<String> ledgerId = workspace.resource(KNOWLEDGE_DB).map(ProvisionedResource::notionId);

        ProvisioningStepResult result = ledgerId.isPresent()
                ? executeWarmPath(workspaceId, ledgerId.get(), dashboardId, spec, expected)
                : executeColdPath(workspaceId, dashboardId, spec, expected);

        log.info("Knowledge database step for workspace {}: dashboardId={}, priorLedgerId={}, outcome={}",
                workspaceId, dashboardId, ledgerId.orElse("none"), result.outcome());
        return result;
    }

    private ProvisioningStepResult executeWarmPath(UUID workspaceId, String existingId, String dashboardId,
                                                     DatabaseSpec spec, ExpectedShape expected) {
        VerificationResult result = notion.verify(existingId, KNOWLEDGE_DB, expected);
        log.info("Knowledge database verify for workspace {}: notionId={}, result={}", workspaceId, existingId, result);
        return switch (result) {
            case PRESENT_MATCHING -> new ProvisioningStepResult(KNOWLEDGE_DB, ProvisioningOutcome.RECONCILED, null);
            case PRESENT_DRIFTED -> {
                notion.repairShape(existingId, expected);
                ledger.record(workspaceId, KNOWLEDGE_DB, existingId);
                yield new ProvisioningStepResult(KNOWLEDGE_DB, ProvisioningOutcome.REPAIRED, "database drifted; shape repaired");
            }
            case ABSENT -> {
                Optional<String> found = notion.findChildByIdentity(dashboardId, KNOWLEDGE_DB, expected);
                if (found.isPresent()) {
                    ledger.record(workspaceId, KNOWLEDGE_DB, found.get());
                    yield new ProvisioningStepResult(KNOWLEDGE_DB, ProvisioningOutcome.REPAIRED, "ledger id was stale; re-adopted existing database");
                }
                String newId = notion.createDatabase(dashboardId, spec);
                ledger.record(workspaceId, KNOWLEDGE_DB, newId);
                yield new ProvisioningStepResult(KNOWLEDGE_DB, ProvisioningOutcome.REPAIRED, "ledger id was stale; database recreated");
            }
        };
    }

    private ProvisioningStepResult executeColdPath(UUID workspaceId, String dashboardId, DatabaseSpec spec, ExpectedShape expected) {
        Optional<String> found = notion.findChildByIdentity(dashboardId, KNOWLEDGE_DB, expected);
        log.info("Knowledge database findChildByIdentity for workspace {}: found={}", workspaceId, found.isPresent());
        if (found.isEmpty()) {
            String newId = notion.createDatabase(dashboardId, spec);
            ledger.record(workspaceId, KNOWLEDGE_DB, newId);
            return new ProvisioningStepResult(KNOWLEDGE_DB, ProvisioningOutcome.CREATED, null);
        }

        String orphanId = found.get();
        VerificationResult orphanVerify = notion.verify(orphanId, KNOWLEDGE_DB, expected);
        return switch (orphanVerify) {
            case PRESENT_MATCHING -> {
                ledger.record(workspaceId, KNOWLEDGE_DB, orphanId);
                yield new ProvisioningStepResult(KNOWLEDGE_DB, ProvisioningOutcome.RECONCILED, null);
            }
            case PRESENT_DRIFTED -> {
                notion.repairShape(orphanId, expected);
                ledger.record(workspaceId, KNOWLEDGE_DB, orphanId);
                yield new ProvisioningStepResult(KNOWLEDGE_DB, ProvisioningOutcome.REPAIRED, "adopted orphan database was drifted; shape repaired");
            }
            case ABSENT -> {
                String newId = notion.createDatabase(dashboardId, spec);
                ledger.record(workspaceId, KNOWLEDGE_DB, newId);
                yield new ProvisioningStepResult(KNOWLEDGE_DB, ProvisioningOutcome.CREATED, null);
            }
        };
    }

    static DatabaseSpec knowledgeSpec() {
        return new DatabaseSpec(TITLE, List.of(
                PropertyDefinition.of("Title", NotionPropertyType.TITLE),
                PropertyDefinition.of("Content", NotionPropertyType.RICH_TEXT)));
    }

    static ExpectedShape knowledgeExpectedShape() {
        return new ExpectedShape(TITLE, knowledgeSpec().properties());
    }
}
```

**Line-level delta from `CreateTasksDatabaseService.java` (for reference — the Implementer may diff against it directly):**
1. Package `com.lifeos.application.usecase.task` → `com.lifeos.application.usecase.knowledge`.
2. **No `TaskStatus`-equivalent import** — `Knowledge` has no enum field, so there is no `Arrays.stream(...).map(Enum::name)` block and no `java.util.Arrays` import.
3. Static import `TASKS_DB` → `KNOWLEDGE_DB`.
4. `TITLE = "Tasks"` → `TITLE = "Knowledge"`.
5. Every `TASKS_DB` token (method bodies, log message prefixes "Tasks database …" → "Knowledge database …") → `KNOWLEDGE_DB`.
6. `tasksSpec()`/`tasksExpectedShape()` → `knowledgeSpec()`/`knowledgeExpectedShape()`.
7. `knowledgeSpec()` builds **two** `PropertyDefinition.of(...)` entries only — `Title`(TITLE)/`Content`(RICH_TEXT) — versus Tasks's four (`Title`/`Description`/`Status`/`Due Date`). No `new PropertyDefinition(name, type, options)` overload usage (no `select` property), so no options list is built at all.
8. Constructor, `execute`, `executeWarmPath`, `executeColdPath` bodies are otherwise **byte-for-byte structurally identical** (same branching, same log statement shape, same outcome/detail strings) — the algorithm is agnostic to schema shape.

### 3.3 Outcome decision table

Reused verbatim from Projects (architecture §3 "Outcome decision table"; Tasks tech spec §3.3), substituting `KNOWLEDGE_DB`. Not restated as a new table — the 9-row shape (`CREATED` only on first-time create with no prior ledger record; adoption is never `CREATED`; `REPAIRED` ⇔ a Notion write happened this run; `RECONCILED` ⇔ none; `>1` identity match ⇒ `FAILED` via propagated `NotionApiException`) applies identically; see §5 test plan for the per-row test mapping.

### 3.4 Transaction boundary — summary

| Class | Transaction | Notes |
|---|---|---|
| `CreateKnowledgeDatabaseService.execute` | **none** | Pure port orchestration; mirrors `CreateTasksDatabaseService` — several Notion HTTP calls would hold a DB connection across slow remote work if annotated. |
| `WorkspaceLedgerWriter.record` | `@Transactional` (existing, unchanged) | The **only** transactional write in this step. |
| `JpaWorkspaceRepository.findById` | `@Transactional(readOnly = true)` (existing, unchanged) | Read path for `workspaceRepository.findById`. |
| `NotionProvisioningAdapter.*` / `NotionClient.*` | none | Not a transactional resource; unchanged. |

No new `@Service`/`@Component`/`@Repository` bean.

---

## 4. Security

- **Token handling**: unchanged. `NotionClient` remains the only class reading the token; `CreateKnowledgeDatabaseService` never touches it.
- **Token-never-leaked (NFR-6)**: enforced structurally exactly as Projects/Tasks — `NotionApiException` messages built only from status + Notion's `code`/`message`, plus the reused `>1`-match message which interpolates only the match count and `expected.title()` (constant `"Knowledge"`, not a secret).
- **No REST/CLI authn change, no OAuth, no per-Person token, no new config property.**

---

## 5. Test plan (write these first — TDD)

Narrowest-sufficient tier per class (`spring-testing`). Build/verify order: service (mocked port) → optional wiring IT. **No new value-type unit tests** (`PropertyDefinition`/`DatabaseSpec`/`ExpectedShape` are unchanged and already covered by the Projects pass's `PropertyDefinitionTest`/`DatabaseSpecTest`/`ExpectedShapeTest`), **no new domain unit tests** (`Knowledge`/`KnowledgeDiscoveryService` unchanged, no domain change), **no new adapter contract tests** (see §5.3). No live Notion, no token, no network egress anywhere.

### 5.1 `application/usecase/knowledge/CreateKnowledgeDatabaseServiceTest.java` [NEW — full rewrite of the existing stub test]

Delete the existing single test (`execute_throwsUnsupportedOperationExceptionUntilNotionAdapterExists`, 2-arg constructor) — the constructor becomes 3-arg, a breaking change. Mirror `CreateTasksDatabaseServiceTest` (`backend/src/test/java/com/lifeos/application/usecase/task/CreateTasksDatabaseServiceTest.java`) exactly, substituting `KNOWLEDGE_DB`/`knowledgeSpec`/`knowledgeExpectedShape` and the Knowledge property names, and **dropping every enum/`select`/`date`-specific assertion** (no `TaskStatus` analogue exists). Mocks: `NotionProvisioningPort notion`, `WorkspaceRepository workspaceRepository`, `WorkspaceLedgerWriter ledger`. Fixture helper: `Workspace.reconstitute(id, UUID.randomUUID(), "Personal", List.of()).record(DASHBOARD, "dash-id")` (and `.record(KNOWLEDGE_DB, notionId)` for warm-path fixtures).

**Expected count: 17 tests** (same count as Tasks §5.1 — the outcome-table/precondition/failure-propagation surface is identical in shape; only the spec content shrinks from 4 to 2 properties). Full method list:

1. `execute_throwsWhenWorkspaceNotFound` — `workspaceRepository.findById` empty → `IllegalStateException("Workspace not found: " + id)`; `verifyNoInteractions(notion)`, `verifyNoInteractions(ledger)` (FR-2).
2. `execute_throwsWhenNoDashboardLedgerEntry` — workspace present, no `DASHBOARD` resource → `IllegalStateException("No confirmed Dashboard for workspace " + id)`; `verifyNoInteractions(notion)`, `verifyNoInteractions(ledger)` (FR-3).
3. `execute_createsWhenColdAndNoOrphan` — no `KNOWLEDGE_DB` resource; `findChildByIdentity(dashId, KNOWLEDGE_DB, expected)` → `Optional.empty()`; `createDatabase(dashId, spec)` → `"new-db-id"`; capture the `DatabaseSpec` passed to `createDatabase` and assert `properties()` has **exactly 2 entries** in order `Title`(TITLE)/`Content`(RICH_TEXT); `ledger.record(workspaceId, KNOWLEDGE_DB, "new-db-id")` called once; outcome `CREATED`, `detail` null (row 1).
4. `execute_adoptsWhenColdAndOrphanMatches` — `findChildByIdentity` → `Optional.of("orphan-id")`; `verify("orphan-id", KNOWLEDGE_DB, expected)` → `PRESENT_MATCHING`; assert **no** `createDatabase`/`repairShape`; `ledger.record(workspaceId, KNOWLEDGE_DB, "orphan-id")`; outcome `RECONCILED` (row 2).
5. `execute_adoptsAndRepairsWhenColdAndOrphanDrifted` — `findChildByIdentity` → present; `verify` → `PRESENT_DRIFTED`; assert `repairShape("orphan-id", expected)` called once, `ledger.record`; outcome `REPAIRED` (row 3).
6. `execute_propagatesAmbiguousMatchFailureOnColdPath` — `findChildByIdentity` throws `NotionApiException`; assert propagation (`isSameAs`), `verifyNoInteractions(ledger)` (row 4).
7. `execute_reconcilesWhenWarmAndMatching` — `KNOWLEDGE_DB` resource present (`"existing-id"`); `verify` → `PRESENT_MATCHING`; assert **no** write, **no** `ledger.record`, **no** `findChildByIdentity` call; outcome `RECONCILED` (row 5).
8. `execute_repairsWhenWarmAndDrifted` — `verify` → `PRESENT_DRIFTED`; assert `repairShape("existing-id", expected)`, `ledger.record(workspaceId, KNOWLEDGE_DB, "existing-id")`; outcome `REPAIRED` (row 6).
9. `execute_reAdoptsWhenWarmAndDeletedAndOrphanFound` — `verify` → `ABSENT`; `findChildByIdentity` → `Optional.of("orphan-id")`; assert **no** `createDatabase`, `ledger.record(workspaceId, KNOWLEDGE_DB, "orphan-id")`; outcome `REPAIRED` (row 7).
10. `execute_reCreatesWhenWarmAndDeletedAndNoOrphanFound` — `verify` → `ABSENT`; `findChildByIdentity` → `Optional.empty()`; assert `createDatabase` called, `ledger.record`; outcome `REPAIRED` (row 8).
11. `execute_propagatesAmbiguousMatchFailureOnWarmAbsentPath` — `verify` → `ABSENT`; `findChildByIdentity` throws `NotionApiException`; assert propagation, `verifyNoInteractions(ledger)` (row 9).
12. `execute_propagatesNotionFailureFromVerifyWithoutWritingLedger` — `verify` throws `NotionApiException` directly (transport failure, not `ABSENT`); assert propagation, `verifyNoInteractions(ledger)` (FR-12).
13. `execute_propagatesNotionFailureFromCreateWithoutWritingLedger` — cold path, `findChildByIdentity` empty, `createDatabase` throws; assert propagation, `verifyNoInteractions(ledger)` (Notion-write-before-ledger-write ordering).
14. `execute_neverInvokesRelationRollupFormulaOrSampleOrPageMethods` — on a happy-path execution, `verify(notion, never())` for `ensureRelation`/`ensureRollup`/`ensureFormula`/`hasSampleRecords`/`insertSampleRecords`/`createRootPage`/`verifyPage`/`repairPage`/`findRootByIdentity` (FR-14).
15. `execute_isNotAnnotatedTransactional` — reflection: `CreateKnowledgeDatabaseService.class.getMethod("execute", UUID.class).isAnnotationPresent(Transactional.class) == false`, and the class itself is not `@Transactional`.
16. `knowledgeSpec_buildsTwoPropertiesTitleAndContent` — direct unit test of the package-private static method: asserts title `"Knowledge"`, **exactly 2** properties in order — `Title`(TITLE)/`Content`(RICH_TEXT); assert the `Content` `PropertyDefinition`'s `options()` is empty/null (no `select` options ever attached to a `RICH_TEXT` property). Compare each `PropertyDefinition` via `.isEqualTo(...)` the same way Tasks' `tasksSpec_buildsFourPropertiesWithStatusOptionsFromEnum` does, scaled down to 2 entries.
17. `knowledgeExpectedShape_matchesSpecProperties` — `knowledgeExpectedShape().requiredProperties()` equals `knowledgeSpec().properties()`; `knowledgeExpectedShape().title()` equals `"Knowledge"`.

### 5.2 `application/usecase/knowledge/CreateKnowledgeDatabaseServiceIT.java` [NEW]

Mirror `CreateTasksDatabaseServiceIT` (`backend/src/test/java/com/lifeos/application/usecase/task/CreateTasksDatabaseServiceIT.java`) exactly: `@SpringBootTest` + `@Testcontainers` Postgres container, a `@TestConfiguration`-supplied `@Primary` `InMemoryDatabaseOnlyNotionPort` fake implementing only the four database methods realistically (create assigns a UUID string id, stores title + property-name set; verify/find/repair read/mutate the map) plus the four page methods delegating to a fixed pre-adopted Dashboard id, and every other port method throwing `UnsupportedOperationException`. `@BeforeEach` clears the fake's static map. (This fake can be the same shared test-support class used by the Tasks IT, or a `KNOWLEDGE_DB`-scoped instance of it — either is acceptable; no port change either way.)

4 tests, substituting `KNOWLEDGE_DB`/`createKnowledgeDatabase`:

1. `execute_persistsKnowledgeDbLedgerRowOnFirstRun` — a fresh workspace with a pre-seeded `DASHBOARD` resource → `execute` → outcome `CREATED`; a direct repository read shows exactly one `KNOWLEDGE_DB` `ProvisionedResource` row.
2. `execute_convergesToOneRowAcrossThreeReruns` — run `execute` three times in sequence → still exactly one `KNOWLEDGE_DB` row after all three; second/third outcomes are `RECONCILED` (FR-13); asserts the same `notionId` across all three reads. **This is the multi-run convergence case explicitly required by the orchestration prompt** — no separate class needed, it is this test method.
3. `execute_reachesRepairedOutcomeWhenFakeSimulatesExternalPropertyRemoval` — after the first run, mutate the fake's stored properties map directly (simulating an out-of-band Notion property deletion, e.g. remove `"Content"`) → second `execute` → `PRESENT_DRIFTED` → `REPAIRED`, ledger row's `notionId` unchanged.
4. `execute_throwsWhenPhaseAIncomplete` — a workspace with **no** `DASHBOARD` resource → `execute` throws `IllegalStateException`, and the repository shows **no** `KNOWLEDGE_DB` row.

Class name ends in `IT` so Failsafe runs it under `./mvnw verify` (already configured). Zero real Notion calls; no `MockRestServiceServer` in this class.

### 5.3 No new adapter contract tests — explicit note

`NotionProvisioningAdapter`'s database slice (`createDatabase`/`verify`/`findChildByIdentity`/`repairShape`) is **generic over `ProvisionedResourceType` + the typed `DatabaseSpec`/`ExpectedShape`/`PropertyDefinition`** and is already fully contract-tested end-to-end by `NotionProvisioningAdapterDatabaseTest` (17 `MockRestServiceServer` cases, shipped under the Projects pass, reused unchanged by Tasks — see `docs/pipeline/create-projects-database/03-tech-spec.md` §9.2). Passing it a `KNOWLEDGE_DB` type with a Knowledge `DatabaseSpec`/`ExpectedShape` (two properties, `TITLE` + `RICH_TEXT` — both types already exercised by the Description property on Projects/Tasks) exercises the exact same request-building and response-parsing code paths already proven. **Do not duplicate those adapter tests for Knowledge.** The correctness obligation that *is* new — that `CreateKnowledgeDatabaseService` builds and passes the *right* `DatabaseSpec`/`ExpectedShape` (exactly two properties, no options) to the (mocked) port — is covered instead at the service-unit level by §5.1 tests 3 and 16 (which assert the exact captured/returned `DatabaseSpec`/`ExpectedShape` contents), and at the IT level by §5.2 (which exercises the real algorithm against a fake port).

---

## 6. Explicitly NOT built in this pass (scope guard for the Implementer)

- **Any `Knowledge ↔ Area` relation**, or any relation/rollup/formula of any kind. `ensureRelation`/`ensureRollup`/`ensureFormula` are untouched, unchanged stubs. `Knowledge.areaId` remains a UUID-only reference — do not add a Notion relation column for it (deferred to Phase C — Create Relations; requires an Areas database that no Phase-B step provisions; FR-14).
- **Rows or sample data.** `hasSampleRecords`/`insertSampleRecords` untouched. This step provisions the container only — zero Notion database rows are written.
- **The page-body mechanism for `Content`.** ADR-0010 chose the `rich_text` **property**, not Notion page-body blocks; no block-append path is introduced. The 2000-char-per-rich-text-object limit and any long-form write/split strategy are explicitly deferred to Phase F (Populate Example Data) per ADR-0010's tracked follow-up — out of scope here because no row is written by this step.
- **The other databases** (Habits, Journal, Resources, People) and `GOALS_DB`/`REVIEWS_DB`. Only `KNOWLEDGE_DB` is exercised. Do not generalize `knowledgeSpec()`/`tasksSpec()`/`projectsSpec()` into a shared multi-database schema builder in this pass (YAGNI — each sibling step authors its own schema when it is built, per architecture framing).
- **Any change to `Knowledge` or `KnowledgeDiscoveryService`.** No new field, no `KnowledgeRepository`, no JPA entity, no Flyway migration, no reconstitution factory. `KnowledgeDiscoveryService` is not read, invoked, or modified.
- **Any change to the adapter, `NotionClient`, the port interface, or any typed schema value type** (`DatabaseSpec`/`ExpectedShape`/`PropertyDefinition`/`NotionPropertyType`) — all reused byte-for-byte. No new `NotionPropertyType` variant is needed (`RICH_TEXT` already exists).
- **`docs/productivity/*` population** or any other documentation content change.
- **A semantic Notion-Version range/comparison check, new config property, or profile change.** Nothing here needs one.

---

## 7. Traceability (FR/NFR → spec section)

| Req | Satisfied by (this spec) |
|---|---|
| FR-1 | §3.2 unchanged `CreateKnowledgeDatabaseUseCase.execute(UUID)` signature |
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
| FR-11 | Returns `ProvisioningStepResult(KNOWLEDGE_DB, …)`; failures propagate unmodified; §3.2 |
| FR-12 | Notion-before-ledger ordering + own-tx write + next-run adoption; tests 5.1-12, 5.1-13 |
| FR-13 | Adoption-before-create both paths; upsert `record`; IT convergence test 5.2-2 |
| FR-14 | Only the four DB port methods invoked; test 5.1-14; schema has no relation property (§2) |
| §3 schema + domain backing | §2 (Title/Content grounded in `Knowledge.java`); ADR-0010 (Content = `rich_text` property, not page body) |
| NFR-1 | Strict per-path live verification; §3.2 (inherited ADR-0002/ADR-0008) |
| NFR-2 | Notion-before-ledger + no rollback; next-run reconcile; §3.2; non-destructive repair (reused adapter) |
| NFR-3 | Mockito service tests + fake-port IT; adapter reused/already covered; §5 |
| NFR-4 | Stub `UnsupportedOperationException` removed only as the real impl lands; §3.1/§3.2 |
| NFR-5 | `log.info` per run (workspaceId, dashboardId, prior ledger id, `VerificationResult`, acted-on id, outcome — no token/raw body); §3.2 |
| NFR-6 | §4 Security; message-construction rule reused from `NotionClient` |
| NFR-7 | No shared mutable state; only `KNOWLEDGE_DB` ledger entry written; §3.4 |
| NFR-8 | Upsert `record` → exactly one `KNOWLEDGE_DB` entry (`Workspace.record` semantics, reused) |
| NFR-9 | Bounded call count per run (reused adapter, unchanged budget) |
| NFR-10 | `429`/`529` `Retry-After` clamp in reused `NotionClient` (unchanged) |

---

## 8. Implementation notes (file list for the Implementer)

- `backend/src/main/java/com/lifeos/application/usecase/knowledge/CreateKnowledgeDatabaseService.java` — replace stub with §3.2's full source; 3-arg constructor (`NotionProvisioningPort`, `WorkspaceRepository`, `WorkspaceLedgerWriter`); `knowledgeSpec()`/`knowledgeExpectedShape()` package-private statics; SLF4J logging per NFR-5; no `@Transactional`.
- `backend/src/test/java/com/lifeos/application/usecase/knowledge/CreateKnowledgeDatabaseServiceTest.java` — fully rewritten (3-arg constructor), 17 tests (§5.1).
- `backend/src/test/java/com/lifeos/application/usecase/knowledge/CreateKnowledgeDatabaseServiceIT.java` — new `@SpringBootTest` + Testcontainers Postgres IT, 4 tests (§5.2); fake port's in-memory map cleared in `@BeforeEach`.

No changes to any other file. No changes to `WorkspaceController`, `WorkspaceCommands`, `ApiExceptionHandler`, any web/CLI DTO, `infrastructure.adapter.persistence`, `application.yml`, or any Flyway migration.

---

## 9. Findings / notes back to the Architect

None. The architecture is complete and self-consistent for this step; no deviation was needed to produce this spec. ADR-0010 is accepted as written and requires no SME-level qualification beyond what §2/§6 already state (verify is name-only, so the `rich_text`-vs-page-body choice cannot cause spurious drift; the 2000-char limit is inert because no row is written by this step).

---

## Implementation notes

- `backend/src/main/java/com/lifeos/application/usecase/knowledge/CreateKnowledgeDatabaseService.java` — stub replaced with the §3.2 full implementation verbatim (3-arg constructor, `knowledgeSpec()`/`knowledgeExpectedShape()` package-private statics, no `@Transactional`).
- `backend/src/test/java/com/lifeos/application/usecase/knowledge/CreateKnowledgeDatabaseServiceTest.java` — [NEW] full rewrite of the stub test; 17 Mockito unit tests per §5.1.
- `backend/src/test/java/com/lifeos/application/usecase/knowledge/CreateKnowledgeDatabaseServiceIT.java` — [NEW] `@SpringBootTest` + Testcontainers Postgres IT with an in-memory fake `NotionProvisioningPort`; 4 tests per §5.2.

`./mvnw verify` (Podman env exported) → BUILD SUCCESS. Unit tier: `CreateKnowledgeDatabaseServiceTest` — `Tests run: 17, Failures: 0, Errors: 0, Skipped: 0`. Failsafe tier: `CreateKnowledgeDatabaseServiceIT` — `Tests run: 4, Failures: 0, Errors: 0, Skipped: 0` (confirmed running, not skipped). Full-suite totals: unit `Tests run: 223, Failures: 0, Errors: 0, Skipped: 0`; failsafe `Tests run: 17, Failures: 0, Errors: 0, Skipped: 0`.
