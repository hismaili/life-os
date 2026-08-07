# 03 — Technical Specification: Create Projects Database (Phase B — first child database)

Status: Ready for Implementer
Owner (SME stage): pipeline automation
Input: `docs/pipeline/create-projects-database/02-architecture.md` + `adr/ADR-0005..0008` (all Accepted) + `01-spec.md` + `02-open-questions.md` (all resolved) + existing code under `backend/src/main/java/com/lifeos/`.
Grounding skills applied: `spring-boot-conventions`, `spring-data-jpa` (N/A — no schema/migration change; the `Project` domain change is in-memory-only, no `ProjectRepository`/table exists yet), `spring-security` (N/A — no new auth surface, token handling reused verbatim), `spring-testing`.

> This spec is mechanical: exact signatures, JSON request/response shapes, and an ordered TDD task list. It introduces no design decision beyond `02-architecture.md`/ADR-0005..0008. Reference pattern: `docs/pipeline/create-dashboard/03-tech-spec.md` (own-transaction ledger write, verify-before-trust, outcome mapping, `MockRestServiceServer` contract tests, Testcontainers IT) — this spec mirrors its structure and rigor for the **database** slice instead of the **page** slice, plus one bounded domain change (OQ-A).

---

## 1. Package layout (file-by-file change list)

```
com.lifeos
 ├─ domain.project/
 │    ProjectStatus.java                    [NEW]     enum PLANNED, ACTIVE, ON_HOLD, DONE (+displayName())
 │    Project.java                          [MODIFIED] +status (non-null, default PLANNED), +dueDate (nullable LocalDate);
 │                                                      Project.create(...) gains 2 params; @Value/@Builder(PRIVATE) preserved
 │    ProjectProgressService.java           (unchanged — not redesigned here)
 │
 ├─ application
 │   ├─ port/
 │   │    NotionProvisioningPort.java        [MODIFIED] findChildByIdentity gains ExpectedShape param;
 │   │                                                  verify/createDatabase param names clarified (no behavioural change)
 │   │    DatabaseSpec.java                  [MODIFIED] List<String> propertyNames → List<PropertyDefinition> properties
 │   │    ExpectedShape.java                 [MODIFIED] List<String> requiredPropertyNames → List<PropertyDefinition> requiredProperties
 │   │    PropertyDefinition.java            [NEW]     record(name, NotionPropertyType, options)
 │   │    NotionPropertyType.java            [NEW]     enum TITLE, RICH_TEXT, SELECT, DATE
 │   │    PageShape.java / ParentConstraint.java / VerificationResult.java   (unchanged — page slice)
 │   │
 │   └─ usecase.project/
 │        CreateProjectsDatabaseService.java [MODIFIED] 3-arg constructor (+WorkspaceRepository), real verify/create/adopt/repair
 │                                                      algorithm, stub throw removed
 │        CreateProjectsDatabaseUseCase.java (unchanged — ProvisioningStepResult execute(UUID))
 │
 └─ infrastructure
     └─ adapter.notion/
          NotionProvisioningAdapter.java     [MODIFIED] implement createDatabase/verify/findChildByIdentity/repairShape;
                                                        every other stub (ensureRelation/ensureRollup/ensureFormula/
                                                        hasSampleRecords/insertSampleRecords) untouched, verbatim
          NotionClient.java                  (unchanged — reused as-is: post/get/patch, 429/529 backoff, timeouts)
          NotionApiException.java            (unchanged)
          NotionProperties.java              (unchanged — version pin documented, see §8)
          dto/
              NotionDatabaseResponse.java     [NEW] id, title, archived, in_trash, data_sources[]
              NotionDataSourceSummary.java    [NEW] id, name  (element of data_sources[])
              NotionDataSourceResponse.java   [NEW] properties map
              NotionPropertyConfig.java       [NEW] type, select{options[]} — property config on the data source
              NotionSelectOption.java         [NEW] name
              NotionBlockChildrenResponse.java[NEW] results[] (child_database blocks), has_more, next_cursor
              NotionBlock.java                [NEW] id, type, child_database{title}
              NotionChildDatabase.java        [NEW] title
```

**Ripple (compile-only, no behaviour change):**
- `backend/src/test/java/com/lifeos/application/usecase/workspace/CreateDashboardServiceIT.java` — `InMemoryPageOnlyNotionPort.findChildByIdentity` gains the third `ExpectedShape` parameter; body stays `throw new UnsupportedOperationException()`.
- `backend/src/test/java/com/lifeos/application/usecase/workspace/CreateDashboardServiceTest.java` — line 236, `verify(notion, never()).findChildByIdentity(any(), any())` → `verify(notion, never()).findChildByIdentity(any(), any(), any())`. No other line in that file changes; `createDatabase(any(), any())` (line 229) keeps its 2-arg shape (unchanged).

**Caller ripple for `Project.create(...)` (searched — see §6):** zero production or test call sites exist today (`grep -rn "Project.create(\|Project.builder("` under `backend/` returns only the definition itself, `Project.java:30`, and `ProjectProgressService` never constructs a `Project`). No other file needs editing for the signature change; the new `ProjectTest`/`ProjectStatusTest` (§9) are the only new callers.

No change to `domain.workspace`, `infrastructure.adapter.persistence`, `infrastructure.adapter.web`/`adapter.cli`, any Flyway migration, or `application.yml` (no new config property this step). Package-by-feature preserved (`spring-boot-conventions`).

---

## 2. Domain change — `domain/project` (OQ-A, architecture §5.6)

### 2.1 `ProjectStatus` [NEW] — plain enum, framework-free

```java
package com.lifeos.domain.project;

public enum ProjectStatus {
    PLANNED("Planned"),
    ACTIVE("Active"),
    ON_HOLD("On hold"),
    DONE("Done");

    private final String displayName;

    ProjectStatus(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
```

No Spring annotation, no infrastructure import (`CLAUDE.md` — domain services/types are pure). `displayName()` is the single mapping from enum value to Notion select-option label; only `CreateProjectsDatabaseService.projectsSpec()`/`projectsExpectedShape()` (§4.3) call it.

### 2.2 `Project` [MODIFIED]

```java
package com.lifeos.domain.project;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Value;

import java.time.LocalDate;
import java.util.UUID;

@Value
@Builder(access = AccessLevel.PRIVATE)
public class Project {
    UUID id;
    String name;
    String description;
    ProjectStatus status;   // NEW — never null; defaults to PLANNED at create()
    LocalDate dueDate;      // NEW — nullable; a project may have no deadline yet
    UUID areaId;
    UUID workspaceId;
    UUID goalId;

    public static Project create(String name,
                                 String description,
                                 ProjectStatus status,
                                 LocalDate dueDate,
                                 UUID areaId,
                                 UUID workspaceId,
                                 UUID goalId) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Project name must not be null or blank");
        }
        if (workspaceId == null) {
            throw new IllegalArgumentException("Project workspaceId must not be null");
        }
        return Project.builder()
                .id(UUID.randomUUID())
                .name(name)
                .description(description)
                .status(status == null ? ProjectStatus.PLANNED : status)
                .dueDate(dueDate)
                .areaId(areaId)
                .workspaceId(workspaceId)
                .goalId(goalId)
                .build();
    }
}
```

- **Parameter order** — insert `status` and `dueDate` immediately after `description` and before `areaId` (matches architecture §5.6 exactly; do not reorder further).
- **Invariants**: `name` non-blank, `workspaceId` non-null (unchanged, unchanged messages); `status` is never null in the built object — a `null` argument is coerced to `PLANNED` inside `create`, it is **not** rejected with `IllegalArgumentException` (this is a default, not a validation failure). `dueDate` is passed through unchanged, including `null`.
- **Reconstitution**: the private all-args `@Builder` already threads both new fields with no further change — there is no `ProjectRepository`/`Project.reconstitute(...)` yet, so this is the only reconstitution seam that exists today. Do **not** add a `Project.reconstitute(...)` factory in this pass — out of scope (no persistence work here, §10).
- **No other file constructs a `Project`** (confirmed by grep, §1) — this is a safe, non-breaking signature change today.

---

## 3. `application.port` — typed schema value types (ADR-0007)

### 3.1 `NotionPropertyType` [NEW] — enum

```java
package com.lifeos.application.port;

public enum NotionPropertyType { TITLE, RICH_TEXT, SELECT, DATE }
```

Minimal closed set for this step; sibling databases (Tasks, Knowledge, …) add values (`NUMBER`, `CHECKBOX`, …) additively — no signature change (YAGNI). `status` is deliberately absent (ADR-0006).

### 3.2 `PropertyDefinition` [NEW] — record

```java
package com.lifeos.application.port;

import java.util.List;

public record PropertyDefinition(String name, NotionPropertyType type, List<String> options) {

    public PropertyDefinition {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("property name must not be null or blank");
        }
        if (type == null) {
            throw new IllegalArgumentException("property type must not be null");
        }
        options = options == null ? List.of() : List.copyOf(options);
        if (type != NotionPropertyType.SELECT && !options.isEmpty()) {
            throw new IllegalArgumentException("options are only valid for SELECT properties");
        }
    }

    public static PropertyDefinition of(String name, NotionPropertyType type) {
        return new PropertyDefinition(name, type, List.of());
    }
}
```

### 3.3 `DatabaseSpec` [MODIFIED]

```java
package com.lifeos.application.port;

import java.util.List;

public record DatabaseSpec(String title, List<PropertyDefinition> properties) {

    public DatabaseSpec {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be null or blank");
        }
        if (properties == null || properties.isEmpty()) {
            throw new IllegalArgumentException("properties must not be null or empty");
        }
        properties = List.copyOf(properties);
        long titleCount = properties.stream().filter(p -> p.type() == NotionPropertyType.TITLE).count();
        if (titleCount != 1) {
            throw new IllegalArgumentException("exactly one TITLE property is required, found " + titleCount);
        }
    }
}
```

### 3.4 `ExpectedShape` [MODIFIED]

```java
package com.lifeos.application.port;

import java.util.List;

public record ExpectedShape(String title, List<PropertyDefinition> requiredProperties) {

    public ExpectedShape {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be null or blank");
        }
        if (requiredProperties == null || requiredProperties.isEmpty()) {
            throw new IllegalArgumentException("requiredProperties must not be null or empty");
        }
        requiredProperties = List.copyOf(requiredProperties);
    }
}
```

Both records were previously `(String, List<String>)` with no compact-constructor validation — this is a from-scratch validated record, not merely a field-type change. Both are consumed **only** by the (until now stubbed) database port methods; `PageShape`/the page slice is unaffected (confirmed: `PageShape` does not reference either type).

### 3.5 `NotionProvisioningPort` [MODIFIED]

Exactly these three signatures change; every other method (`verifyPage`, `repairPage`, `findRootByIdentity`, `createRootPage`, `ensureRelation`, `ensureRollup`, `ensureFormula`, `hasSampleRecords`, `insertSampleRecords`) is byte-for-byte unchanged:

```java
package com.lifeos.application.port;

import com.lifeos.domain.workspace.ProvisionedResourceType;

import java.util.List;
import java.util.Optional;

public interface NotionProvisioningPort {

    VerificationResult verify(String databaseId, ProvisionedResourceType type, ExpectedShape expected); // param renamed (was rootPageId) — first arg is the database's OWN id

    Optional<String> findChildByIdentity(String parentPageId, ProvisionedResourceType type, ExpectedShape expected); // [MODIFIED] +ExpectedShape

    String createRootPage(PageShape expected);

    VerificationResult verifyPage(String pageId, PageShape expected);

    void repairPage(String pageId, PageShape expected);

    Optional<String> findRootByIdentity(PageShape expected);

    String createDatabase(String parentPageId, DatabaseSpec spec); // param renamed (was rootPageId) — first arg is the PARENT page id

    void repairShape(String databaseId, ExpectedShape expected); // param renamed (was notionId) for consistency; signature otherwise unchanged

    void ensureRelation(RelationSpec spec);

    void ensureRollup(RollupSpec spec);

    void ensureFormula(FormulaSpec spec);

    boolean hasSampleRecords(String databaseId);

    void insertSampleRecords(String databaseId, List<RecordSpec> records);
}
```

Only `findChildByIdentity` changes **arity** (2→3 params); `verify`/`createDatabase`/`repairShape` change only parameter **names** in the interface (Java doesn't encode names in bytecode — no caller ripple from the rename itself). The arity change on `findChildByIdentity` is why the `CreateDashboardServiceIT`/`CreateDashboardServiceTest` ripple (§1) is required.

---

## 4. `application.usecase.project.CreateProjectsDatabaseService` [MODIFIED]

### 4.1 Constructor / fields

```java
package com.lifeos.application.usecase.project;

import com.lifeos.application.dto.workspace.ProvisioningOutcome;
import com.lifeos.application.dto.workspace.ProvisioningStepResult;
import com.lifeos.application.port.DatabaseSpec;
import com.lifeos.application.port.ExpectedShape;
import com.lifeos.application.port.NotionPropertyType;
import com.lifeos.application.port.NotionProvisioningPort;
import com.lifeos.application.port.PropertyDefinition;
import com.lifeos.application.usecase.workspace.WorkspaceLedgerWriter;
import com.lifeos.domain.project.ProjectStatus;
import com.lifeos.domain.workspace.ProvisionedResource;
import com.lifeos.domain.workspace.ProvisionedResourceType;
import com.lifeos.domain.workspace.Workspace;
import com.lifeos.domain.workspace.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.lifeos.domain.workspace.ProvisionedResourceType.DASHBOARD;
import static com.lifeos.domain.workspace.ProvisionedResourceType.PROJECTS_DB;

@Service
@RequiredArgsConstructor
public class CreateProjectsDatabaseService implements CreateProjectsDatabaseUseCase {

    private static final String TITLE = "Projects";

    private final NotionProvisioningPort notion;
    private final WorkspaceRepository workspaceRepository;   // [NEW dependency] read-only
    private final WorkspaceLedgerWriter ledger;               // existing — the ONLY transactional write path

    @Override
    public ProvisioningStepResult execute(UUID workspaceId) { ... } // §4.4

    static DatabaseSpec projectsSpec() { ... }          // §4.3
    static ExpectedShape projectsExpectedShape() { ... } // §4.3
}
```

3-arg constructor is a breaking change to `CreateProjectsDatabaseServiceTest` — rewrite it fully (§9.1); the current single test (`execute_throwsUnsupportedOperationExceptionUntilNotionAdapterExists`, 2-arg constructor) is deleted, not extended.

No `@Transactional` on the class or `execute` (architecture §4.4) — mirrors `CreateDashboardService`; a reflection test pins this (§9.1).

### 4.2 §3 fixed schema (property → type/options table, ADR-0006)

| Property name | `NotionPropertyType` | Options |
|---|---|---|
| `Name` | `TITLE` | — |
| `Description` | `RICH_TEXT` | — |
| `Status` | `SELECT` | `ProjectStatus.values()` mapped via `displayName()`: `["Planned", "Active", "On hold", "Done"]`, in enum declaration order |
| `Due Date` | `DATE` | — |

### 4.3 `projectsSpec()` / `projectsExpectedShape()`

```java
static DatabaseSpec projectsSpec() {
    List<String> statusOptions = Arrays.stream(ProjectStatus.values())
            .map(ProjectStatus::displayName)
            .toList();
    return new DatabaseSpec(TITLE, List.of(
            PropertyDefinition.of("Name", NotionPropertyType.TITLE),
            PropertyDefinition.of("Description", NotionPropertyType.RICH_TEXT),
            new PropertyDefinition("Status", NotionPropertyType.SELECT, statusOptions),
            PropertyDefinition.of("Due Date", NotionPropertyType.DATE)));
}

static ExpectedShape projectsExpectedShape() {
    return new ExpectedShape(TITLE, projectsSpec().properties());
}
```

- `TITLE` constant `"Projects"` — final (OQ-C resolved); it does **not** carry the workspace name (unlike the Dashboard title), because identity is already scoped by the unique Dashboard parent (architecture §5.1).
- `projectsSpec()`/`projectsExpectedShape()` are package-private static methods (testable directly, no Spring context needed) and are the **one place** the schema is authored — `execute` calls them once per run and reuses the results for every port call in that run (mirrors `CreateDashboardService.dashboardTitle` single-computation discipline).
- `projectsExpectedShape().requiredProperties()` reuses `projectsSpec().properties()` verbatim (same four `PropertyDefinition`s, including `Status`'s options) — there is no separate "required subset"; all four properties in the fixed schema are required (architecture §3, no optional properties this step).

### 4.4 `execute(UUID workspaceId)` — algorithm

Concrete branch-by-branch restatement of architecture §4.1/§4.2. Pseudocode (Implementer writes idiomatic Java):

```
workspace = workspaceRepository.findById(workspaceId)
                .orElseThrow(() -> new IllegalStateException("Workspace not found: " + workspaceId))
        // FR-2 — no Notion call before this line

dashboardId = workspace.resource(DASHBOARD)
                .map(ProvisionedResource::notionId)
                .orElseThrow(() -> new IllegalStateException(
                        "No confirmed Dashboard for workspace " + workspaceId))
        // FR-3 — no Notion call before this line

spec = projectsSpec()
expected = projectsExpectedShape()
ledgerId = workspace.resource(PROJECTS_DB).map(ProvisionedResource::notionId)   // Optional<String>

if ledgerId.isPresent():
    result = notion.verify(ledgerId.get(), PROJECTS_DB, expected)
    switch (result):
        case PRESENT_MATCHING:
            return new ProvisioningStepResult(PROJECTS_DB, RECONCILED, null)          // no write, no record()

        case PRESENT_DRIFTED:
            notion.repairShape(ledgerId.get(), expected)
            ledger.record(workspaceId, PROJECTS_DB, ledgerId.get())
            return new ProvisioningStepResult(PROJECTS_DB, REPAIRED, "database drifted; shape repaired")

        case ABSENT:
            found = notion.findChildByIdentity(dashboardId, PROJECTS_DB, expected)
            if found.isPresent():
                ledger.record(workspaceId, PROJECTS_DB, found.get())
                return new ProvisioningStepResult(PROJECTS_DB, REPAIRED, "ledger id was stale; re-adopted existing database")
            else:
                newId = notion.createDatabase(dashboardId, spec)
                ledger.record(workspaceId, PROJECTS_DB, newId)
                return new ProvisioningStepResult(PROJECTS_DB, REPAIRED, "ledger id was stale; database recreated")

else:  // cold path — no ledger entry
    found = notion.findChildByIdentity(dashboardId, PROJECTS_DB, expected)
    if found.isEmpty():
        newId = notion.createDatabase(dashboardId, spec)
        ledger.record(workspaceId, PROJECTS_DB, newId)
        return new ProvisioningStepResult(PROJECTS_DB, CREATED, null)
    else:
        orphanId = found.get()
        orphanVerify = notion.verify(orphanId, PROJECTS_DB, expected)   // cold-path orphan needs an explicit re-verify — unlike
                                                                          // the Dashboard's findRootByIdentity, findChildByIdentity
                                                                          // matches on TITLE ONLY (ADR-0008 identity rule), so a
                                                                          // title-matching orphan can still be schema-drifted
        switch (orphanVerify):
            case PRESENT_MATCHING:
                ledger.record(workspaceId, PROJECTS_DB, orphanId)
                return new ProvisioningStepResult(PROJECTS_DB, RECONCILED, null)
            case PRESENT_DRIFTED:
                notion.repairShape(orphanId, expected)
                ledger.record(workspaceId, PROJECTS_DB, orphanId)
                return new ProvisioningStepResult(PROJECTS_DB, REPAIRED, "adopted orphan database was drifted; shape repaired")
            case ABSENT:
                // unreachable in practice: findChildByIdentity just confirmed the block exists; treat defensively
                // as "not found" by falling through to create, rather than looping — do not special-case further.
                newId = notion.createDatabase(dashboardId, spec)
                ledger.record(workspaceId, PROJECTS_DB, newId)
                return new ProvisioningStepResult(PROJECTS_DB, CREATED, null)
```

**Divergence note from the Dashboard pattern (important — read before implementing):** unlike `findRootByIdentity` (which filters on title **and** parent **and** returns only exact matches, so a cold-path hit is unconditionally `RECONCILED` by construction, per the Create Dashboard tech spec §3.3 note), `findChildByIdentity` here identifies a database **by title only** under the Dashboard (ADR-0008 §"Drift detection" is explicitly a *separate* concern from identity). A title-matching orphan database can still have missing required properties. The architecture's §4.1 sequence diagram bundles "adopt" and "verify-after-adopt" together for the cold path precisely for this reason (see the diagram's `alt orphan found → Svc->>Notion: verify(orphanId, ...)` branch) — **this is not a spec addition, it is architecture §4.1 made explicit**; do not skip the post-adoption `verify` call on the cold path.

Any exception (`NotionApiException`, including the `>1`-match case from `findChildByIdentity`) or `IllegalStateException`/repository failure propagates **unmodified** out of `execute` — never caught, never wrapped, never turned into a self-constructed `FAILED` result (FR-11; orchestrator's `runStep` does that mapping). `ledger.record(...)` is called strictly **after** the corresponding Notion write; if `ledger.record` itself throws, that exception also propagates unmodified — the Notion-side effect stays in place and the next run's adoption path reconciles it (FR-12).

### 4.5 Outcome decision table (restated as the exact branch — traceability aid, mirrors architecture §4.2)

| # | Ledger `PROJECTS_DB` id | `verify`/`findChildByIdentity` result | Notion write | `ledger.record` called | Outcome |
|---|---|---|---|---|---|
| 1 | absent | `findChildByIdentity` empty | `createDatabase` | yes | `CREATED` |
| 2 | absent | `findChildByIdentity` present, `verify` → `PRESENT_MATCHING` | none | yes | `RECONCILED` |
| 3 | absent | `findChildByIdentity` present, `verify` → `PRESENT_DRIFTED` | `repairShape` | yes | `REPAIRED` |
| 4 | absent | `findChildByIdentity` → `>1` match | — | no | *(propagates `NotionApiException`; orchestrator maps to `FAILED`)* |
| 5 | present | `verify` → `PRESENT_MATCHING` | none | no | `RECONCILED` |
| 6 | present | `verify` → `PRESENT_DRIFTED` | `repairShape` | yes | `REPAIRED` |
| 7 | present | `verify` → `ABSENT`, then `findChildByIdentity` → present | none | yes | `REPAIRED` |
| 8 | present | `verify` → `ABSENT`, then `findChildByIdentity` → empty | `createDatabase` | yes | `REPAIRED` |
| 9 | present | `verify` → `ABSENT`, then `findChildByIdentity` → `>1` match | — | no | *(propagates; orchestrator maps to `FAILED`)* |

Row 4/9 are the same failure mode from two different call sites — both are separate test cases (§9.1) because the calling context (cold vs warm-ABSENT) differs. `CREATED` is reserved for row 1 only (no prior record, brand-new database); adoption is never `CREATED` (row 2).

### 4.6 Transaction boundary — summary

| Class | Transaction | Notes |
|---|---|---|
| `CreateProjectsDatabaseService.execute` | **none** | Pure port orchestration; several Notion HTTP calls would hold a DB connection across slow remote work (architecture §4.4). |
| `WorkspaceLedgerWriter.record` | `@Transactional` (existing, unchanged) | The **only** transactional write in this step. |
| `JpaWorkspaceRepository.findById` | `@Transactional(readOnly = true)` (existing, unchanged) | Read path for `workspaceRepository.findById`. |
| `NotionProvisioningAdapter.*` / `NotionClient.*` | none | Not a transactional resource. |

No new `@Service`/`@Component`/`@Repository` bean beyond the existing `NotionProvisioningAdapter` `@Component` (unchanged annotation).

---

## 5. Adapter DB slice — `infrastructure.adapter.notion.NotionProvisioningAdapter` [MODIFIED]

Only `createDatabase`/`verify`/`findChildByIdentity`/`repairShape` become real. Every other method (`ensureRelation`, `ensureRollup`, `ensureFormula`, `hasSampleRecords`, `insertSampleRecords`) keeps its current `UnsupportedOperationException` body verbatim — do not touch those six lines. `createRootPage`/`verifyPage`/`repairPage`/`findRootByIdentity` (page slice) are also untouched. `NotionClient` is reused unchanged (constructor, `post`/`get`/`patch`, 429/529 backoff, timeouts, `handleError`/`NotionApiException` construction) — no new method is added to `NotionClient`.

### 5.1 `createDatabase(String parentPageId, DatabaseSpec spec)`

**Request**: `POST /v1/databases`

```json
{
  "parent": { "type": "page_id", "page_id": "<parentPageId>" },
  "title": [ { "text": { "content": "<spec.title()>" } } ],
  "initial_data_source": {
    "properties": {
      "Name": { "type": "title", "title": {} },
      "Description": { "type": "rich_text", "rich_text": {} },
      "Status": { "type": "select", "select": { "options": [ {"name":"Planned"}, {"name":"Active"}, {"name":"On hold"}, {"name":"Done"} ] } },
      "Due Date": { "type": "date", "date": {} }
    }
  }
}
```

Reference: [Create a database](https://developers.notion.com/reference/create-a-database); [Upgrade guide 2025-09-03](https://developers.notion.com/docs/upgrade-guide-2025-09-03) (schema moves under `initial_data_source.properties`).

- Property JSON per `spec.properties()`, in list order, keyed by `PropertyDefinition.name()`, built by a private `propertyConfig(PropertyDefinition)` mapper:
  - `TITLE` → `{"type":"title","title":{}}`
  - `RICH_TEXT` → `{"type":"rich_text","rich_text":{}}`
  - `SELECT` → `{"type":"select","select":{"options":[{"name":o} for o in options]}}`
  - `DATE` → `{"type":"date","date":{}}`
- `title` array uses the same `{text:{content:...}}` shape as the page slice's `titlePropertyBody` (do not introduce a second helper — reuse or mirror the existing private `titlePropertyBody`-style construction).
- **Response** (`200`): `NotionDatabaseResponse` — consume only `id`. Return `response.id()` (the database id, per ADR-0005 — never the data source id).
- **Error mapping**: any non-2xx → `NotionApiException` via `NotionClient`'s existing `handleError` (no special-casing here).

### 5.2 `verify(String databaseId, ProvisionedResourceType type, ExpectedShape expected)`

**Request 1**: `GET /v1/databases/{databaseId}` — [Retrieve a database](https://developers.notion.com/reference/retrieve-a-database)

Response fields consumed:
```json
{
  "id": "...",
  "title": [ { "plain_text": "..." } ],
  "archived": false,
  "in_trash": false,
  "data_sources": [ { "id": "...", "name": "..." } ]
}
```

Decision logic (exact order):
1. `GET` returns HTTP `404` → `VerificationResult.ABSENT` (use `NotionClient.get`'s existing 404→`null` behavior, mirroring `verifyPage`; `NotionDatabaseResponse response == null` ⇒ `ABSENT`).
2. `response.archived() == true` OR `response.inTrash() == true` → `ABSENT`.
3. Joined `plain_text` of `title[]` does not equal `expected.title()` (exact string match) → `PRESENT_DRIFTED`.
4. Otherwise, resolve `dsId = response.dataSources().get(0).id()` (single-source assumption, ADR-0005) and issue **request 2**.

**Request 2**: `GET /v1/data_sources/{dsId}` — [Retrieve a data source](https://developers.notion.com/reference/retrieve-a-data-source)

Response fields consumed:
```json
{ "properties": { "Name": {...}, "Description": {...}, "Status": {...}, "Due Date": {...} } }
```

5. For each `PropertyDefinition` in `expected.requiredProperties()`, check `response.properties().containsKey(p.name())`. If any required name is **absent** → `PRESENT_DRIFTED`.
6. Otherwise → `PRESENT_MATCHING`.

**Types and option sets are never compared** (ADR-0006/ADR-0008) — only property **names** presence on the data source, and only the database **title**. A non-404 error status at either request → `NotionApiException` (hard failure, not `ABSENT`).

### 5.3 `findChildByIdentity(String parentPageId, ProvisionedResourceType type, ExpectedShape expected)`

**Request**: `GET /v1/blocks/{parentPageId}/children` (paginated via `start_cursor` query param) — [Retrieve block children](https://developers.notion.com/reference/get-block-children)

Response fields consumed:
```json
{
  "results": [
    { "id": "...", "type": "child_database", "child_database": { "title": "..." } }
  ],
  "has_more": false,
  "next_cursor": null
}
```

Filtering logic (exact order, mirrors `findRootByIdentity`'s pagination-accumulate-then-decide shape, architecture §4.1 note + Create Dashboard AUD-04 precedent):
1. Accumulate matches across all pages: for each page's `results[]`, keep blocks where `type == "child_database"` AND `childDatabase.title().equals(expected.title())` (exact match, no fuzzy filtering needed here — unlike `/v1/search`, block children returns exact titles, not query-matched substrings).
2. Continue while `has_more == true`, passing `next_cursor` as `start_cursor` on the next request.
3. After exhausting pagination: `0` matches → `Optional.empty()`. `1` match → `Optional.of(thatBlock.id())` (the block id **is** the database id). `> 1` matches → throw `NotionApiException("Ambiguous Projects database identity: " + matches.size() + " child databases titled '" + expected.title() + "' found under the Dashboard")` (FR-9, no token/raw body in the message).

### 5.4 `repairShape(String databaseId, ExpectedShape expected)`

1. `GET /v1/databases/{databaseId}` to read current `title`/`archived`/`in_trash`/`data_sources`.
2. If `title != expected.title()` (joined `plain_text` comparison, same as §5.2 step 3): `PATCH /v1/databases/{databaseId}` — [Update a database](https://developers.notion.com/reference/update-a-database)
   ```json
   { "title": [ { "text": { "content": "<expected.title()>" } } ] }
   ```
3. Resolve `dsId = data_sources[0].id()`; `GET /v1/data_sources/{dsId}` to read current `properties`.
4. For **each** `PropertyDefinition` in `expected.requiredProperties()` whose `name()` is **absent** from the current properties map, issue `PATCH /v1/data_sources/{dsId}` — [Update a data source](https://developers.notion.com/reference/update-a-data-source):
   ```json
   { "properties": { "<name>": <propertyConfig(definition)> } }
   ```
   using the same `propertyConfig` mapper as §5.1 (so a re-added `Status` carries the enum-seeded options). Batch all missing properties into **one** `PATCH` call if more than one is missing (a single `properties` object may carry multiple keys) rather than issuing one PATCH per missing property — this keeps the call budget bounded (architecture §5.4/NFR-9) without changing the additive-only contract.
5. **Never** send a property key with value `null`, and never re-send an **existing** property's config (only genuinely-missing keys are included in the PATCH body) — `PATCH .../data_sources/{id}` with a property set to `null` **removes** it ([Update a data source](https://developers.notion.com/reference/update-a-data-source), "Properties set to null will be removed") — this must never happen (FR-6b, NFR-2, non-destructive).
6. Return type `void`. Steps 2 and 4 are independent — a drifted database may need one, the other, or both; issue only the calls step 1/3's fresh reads indicate are needed. Any non-2xx at any call → `NotionApiException`.

### 5.5 Adapter DTOs [NEW] — `infrastructure.adapter.notion.dto` (package-private-visible-within-package records, `@JsonIgnoreProperties(ignoreUnknown = true)`, mirroring `NotionPageResponse`)

```java
package com.lifeos.infrastructure.adapter.notion.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record NotionDatabaseResponse(
        String id,
        List<NotionRichText> title,
        boolean archived,
        @JsonProperty("in_trash") boolean inTrash,
        @JsonProperty("data_sources") List<NotionDataSourceSummary> dataSources
) {}

@JsonIgnoreProperties(ignoreUnknown = true)
public record NotionDataSourceSummary(String id, String name) {}

@JsonIgnoreProperties(ignoreUnknown = true)
public record NotionDataSourceResponse(Map<String, NotionPropertyConfig> properties) {}

@JsonIgnoreProperties(ignoreUnknown = true)
public record NotionPropertyConfig(String type, NotionSelectConfig select) {}

@JsonIgnoreProperties(ignoreUnknown = true)
public record NotionSelectConfig(List<NotionSelectOption> options) {}

@JsonIgnoreProperties(ignoreUnknown = true)
public record NotionSelectOption(String name) {}

@JsonIgnoreProperties(ignoreUnknown = true)
public record NotionBlockChildrenResponse(
        List<NotionBlock> results,
        @JsonProperty("has_more") boolean hasMore,
        @JsonProperty("next_cursor") String nextCursor
) {}

@JsonIgnoreProperties(ignoreUnknown = true)
public record NotionBlock(String id, String type, @JsonProperty("child_database") NotionChildDatabase childDatabase) {}

@JsonIgnoreProperties(ignoreUnknown = true)
public record NotionChildDatabase(String title) {}
```

`title` on `NotionDatabaseResponse` reuses the existing `NotionRichText` DTO (`{plain_text}`) from the page slice — do not create a duplicate rich-text record. Joined-title extraction (`title().stream().map(NotionRichText::plainText).collect(Collectors.joining())`) mirrors the existing `titleOf(NotionPageResponse)` helper; add a sibling private helper (e.g. `titleOf(NotionDatabaseResponse)`) rather than trying to unify the two response types.

`NotionDataSourceResponse.properties()` is only inspected for **key presence** (§5.2/§5.4) — `NotionPropertyConfig`/`NotionSelectConfig`/`NotionSelectOption` exist so the map deserializes cleanly, but their `type`/`select` fields are never read by this step's logic (types/options are not verified — ADR-0006/0008). Keep them minimal; do not add fields for `rich_text`/`date`/`number` configs that nothing reads.

### 5.6 Rate-limit / timeout / error handling

Entirely `NotionClient`'s existing, reused mechanism — no new code: `429`/`529` → clamped `Retry-After` (1–30s, `NotionClient.parseRetryAfter`), 3 bounded attempts (`MAX_ATTEMPTS`), 5s connect / 20s read timeouts (`NotionClientConfiguration`), token-safe `NotionApiException` messages built only from status + Notion's `code`/`message` (never touches the request). This step's `NotionApiException` throw sites (§5.3 step 3 `>1` match) follow the same "no token/no raw body" rule as the existing `findRootByIdentity` `>1` case.

Call budget per run: cold-create ≈ 1 (`findChildByIdentity`, possibly paginated) + 1 (`createDatabase`) = 2; warm-match ≈ 1 (`verify` database GET) + 1 (`verify` data-source GET) = 2; warm-repair ≈ 2 (verify) + 1–2 (repair GET + up to 2 PATCH) — bounded, within the documented ~3 req/s budget ([Request limits](https://developers.notion.com/reference/request-limits)).

---

## 6. `Project.create(...)` caller-ripple search (explicit record for the Implementer)

Ran (mentally reproducible via):
```
grep -rn "Project\.create(\|Project\.builder(" backend/src/main backend/src/test
```
Result: the **only** occurrence is the definition site itself (`Project.builder()` inside `Project.create`, `domain/project/Project.java:30`). No test file references `Project.create` today (no `ProjectTest.java` exists yet — §9 creates it). No application/infrastructure code constructs a `Project`. `ProjectProgressService.calculateProgress(Project, List<Task>)` only **reads** `project` (never seen dereferencing `status`/`dueDate` — it doesn't need to for this step) and never constructs one. **Conclusion: this is a zero-ripple signature change** beyond the two new domain test classes this spec creates.

---

## 7. Controllers / CLI

**No changes.** No new HTTP/CLI surface — `WorkspaceController`/`WorkspaceCommands` already render whatever `ProvisioningStepResult` the orchestrator returns generically by `type`/`outcome`; a `PROJECTS_DB` step flows through the existing mapping unchanged. Do not touch `WorkspaceController.java`, `WorkspaceCommands.java`, `ApiExceptionHandler.java`, or any web/CLI DTO.

---

## 8. Security

- **Token handling**: unchanged. `NotionClient` remains the only class reading `properties.token()`; `NotionProvisioningAdapter`'s new methods never touch the token directly.
- **Token-never-leaked (NFR-6)**: enforced structurally exactly as in Create Dashboard — `NotionApiException` messages built only from status + Notion's `code`/`message`, plus this step's own `>1`-match message (§5.3) which interpolates only the match count and `expected.title()` (a schema constant, `"Projects"` — never a secret).
- **`NotionProperties.version` minimum**: this entire DB slice requires Notion-Version `>= 2025-09-03` (data-source model, ADR-0005). `application.yml`'s existing default (`2026-03-11`, set by Create Dashboard) already satisfies this — **no config change needed**. Document the minimum explicitly in a code comment on `NotionProvisioningAdapter`'s database methods: `// requires Notion-Version >= 2025-09-03 (data-source model, ADR-0005)`. A stronger boot-time guard (parsing/comparing the version string) is **not** built in this pass (architecture §8 finding 6 flags it as optional; `@NotBlank` fail-fast already exists — a semantic version-range check is a follow-up, not required here).
- **No REST/CLI authn change, no OAuth, no per-Person token** — unchanged, out of scope (same as Create Dashboard §8/§10).

---

## 9. Test plan (write first — TDD)

Narrowest-sufficient tier per class (`spring-testing`). Build/verify order: domain unit (`ProjectStatus`/`Project`) → value-type unit (`PropertyDefinition`/`DatabaseSpec`/`ExpectedShape`) → service (mocked port) → adapter contract (`MockRestServiceServer`) → optional wiring IT. No live Notion, no token, no network egress anywhere.

### 9.1 `CreateProjectsDatabaseServiceTest` (plain Mockito unit, rewritten) — `application.usecase.project`

Mocks: `NotionProvisioningPort notion`, `WorkspaceRepository workspaceRepository`, `WorkspaceLedgerWriter ledger`. Fixture: a `Workspace` via `Workspace.create(...)`/`.record(DASHBOARD, "dash-id")`, with/without a `PROJECTS_DB` resource per test.

One test per §4.5 decision-table row, plus preconditions/scope:

1. `execute_throwsWhenWorkspaceNotFound` — `workspaceRepository.findById` empty → `IllegalStateException("Workspace not found: " + id)`; `verifyNoInteractions(notion)`, `verifyNoInteractions(ledger)` (FR-2).
2. `execute_throwsWhenNoDashboardLedgerEntry` — workspace present but no `DASHBOARD` resource → `IllegalStateException("No confirmed Dashboard for workspace " + id)`; `verifyNoInteractions(notion)`, `verifyNoInteractions(ledger)` (FR-3).
3. `execute_createsWhenColdAndNoOrphan` — no `PROJECTS_DB` resource; `findChildByIdentity(dashId, PROJECTS_DB, expected)` → `Optional.empty()`; `createDatabase(dashId, spec)` → `"new-db-id"`; assert `createDatabase` called once with a `DatabaseSpec` whose `properties()` has exactly 4 entries (`Name`/`Description`/`Status`/`Due Date`) and `Status`'s `options()` equals `["Planned","Active","On hold","Done"]`; `ledger.record(workspaceId, PROJECTS_DB, "new-db-id")` called once; outcome `CREATED`, `detail` null (row 1).
4. `execute_adoptsWhenColdAndOrphanMatches` — `findChildByIdentity` → `Optional.of("orphan-id")`; `verify("orphan-id", PROJECTS_DB, expected)` → `PRESENT_MATCHING`; assert **no** `createDatabase`/`repairShape`; `ledger.record(workspaceId, PROJECTS_DB, "orphan-id")`; outcome `RECONCILED` (row 2).
5. `execute_adoptsAndRepairsWhenColdAndOrphanDrifted` — `findChildByIdentity` → present; `verify` → `PRESENT_DRIFTED`; assert `repairShape("orphan-id", expected)` called once, `ledger.record`; outcome `REPAIRED` (row 3).
6. `execute_propagatesAmbiguousMatchFailureOnColdPath` — `findChildByIdentity` throws `NotionApiException`; assert propagation, `verifyNoInteractions(ledger)` (row 4).
7. `execute_reconcilesWhenWarmAndMatching` — `PROJECTS_DB` resource present (`"existing-id"`); `verify` → `PRESENT_MATCHING`; assert **no** write, **no** `ledger.record`; outcome `RECONCILED` (row 5).
8. `execute_repairsWhenWarmAndDrifted` — `verify` → `PRESENT_DRIFTED`; assert `repairShape("existing-id", expected)`, `ledger.record(workspaceId, PROJECTS_DB, "existing-id")`; outcome `REPAIRED` (row 6).
9. `execute_reAdoptsWhenWarmAndDeletedAndOrphanFound` — `verify` → `ABSENT`; `findChildByIdentity` → `Optional.of("orphan-id")`; assert **no** `createDatabase`, `ledger.record(workspaceId, PROJECTS_DB, "orphan-id")`; outcome `REPAIRED` (row 7).
10. `execute_reCreatesWhenWarmAndDeletedAndNoOrphanFound` — `verify` → `ABSENT`; `findChildByIdentity` → `Optional.empty()`; assert `createDatabase` called, `ledger.record`; outcome `REPAIRED` (row 8).
11. `execute_propagatesAmbiguousMatchFailureOnWarmAbsentPath` — `verify` → `ABSENT`; `findChildByIdentity` throws `NotionApiException`; assert propagation, `verifyNoInteractions(ledger)` (row 9).
12. `execute_propagatesNotionFailureFromVerifyWithoutWritingLedger` — `verify` throws `NotionApiException` directly (transport failure, not `ABSENT`); assert propagation, `verifyNoInteractions(ledger)` (FR-12).
13. `execute_propagatesNotionFailureFromCreateWithoutWritingLedger` — cold path, `findChildByIdentity` empty, `createDatabase` throws; assert propagation, `verifyNoInteractions(ledger)` (confirms Notion-write-before-ledger-write ordering).
14. `execute_neverInvokesRelationRollupFormulaOrSampleOrPageMethods` — on any happy-path execution, `verify(notion, never())` for `ensureRelation`/`ensureRollup`/`ensureFormula`/`hasSampleRecords`/`insertSampleRecords`/`createRootPage`/`verifyPage`/`repairPage`/`findRootByIdentity` (FR-14).
15. `execute_isNotAnnotatedTransactional` — reflection: `CreateProjectsDatabaseService.class.getMethod("execute", UUID.class).isAnnotationPresent(Transactional.class) == false`, no class-level `@Transactional` (architecture §4.4).
16. `projectsSpec_buildsFourPropertiesWithStatusOptionsFromEnum` — direct unit test of the package-private static method: asserts title `"Projects"`, 4 properties in order `Name`(TITLE)/`Description`(RICH_TEXT)/`Status`(SELECT, options=`ProjectStatus` display names in enum order)/`Due Date`(DATE).
17. `projectsExpectedShape_matchesSpecProperties` — `projectsExpectedShape().requiredProperties()` equals `projectsSpec().properties()`.

### 9.2 `NotionProvisioningAdapterDatabaseTest` (`MockRestServiceServer` bound to the adapter's injected `RestClient.Builder`) — `infrastructure.adapter.notion`

Constructed exactly as the existing page-slice adapter test (`RestClient.Builder` + `MockRestServiceServer.bindTo(builder).build()` + `NotionProperties` + `ObjectMapper`); assert **both** outgoing request (path, verb, `Authorization`/`Notion-Version` headers, JSON body) **and** the resulting return value/behavior. May live in the same `NotionProvisioningAdapterTest` class as the existing page-slice tests, or a new `NotionProvisioningAdapterDatabaseTest` in the same package — Implementer's choice, but do not duplicate the `MockRestServiceServer` bootstrap boilerplate if kept in one file.

1. `createDatabase_postsDatabaseWithInitialDataSource_returnsDatabaseId` — expect `POST /v1/databases` with the exact §5.1 JSON (assert `initial_data_source.properties.Status.select.options` = 4 objects with the enum-seeded `name`s); respond `200` `{"id":"new-db-id","data_sources":[{"id":"ds-1","name":"Projects"}]}`; assert returned id `"new-db-id"`.
2. `verify_returnsAbsentOn404` — `GET /v1/databases/{id}` → `404`; assert `ABSENT`.
3. `verify_returnsAbsentWhenArchivedOrInTrash` — `200`, `archived:true` (and separately `in_trash:true`); assert `ABSENT` both.
4. `verify_returnsDriftedOnTitleMismatch` — `200`, title `"Something Else"`; assert `PRESENT_DRIFTED`; no `data_sources` GET issued (assert only 1 request sent).
5. `verify_returnsDriftedWhenRequiredPropertyMissing` — `GET /v1/databases/{id}` returns matching title + `data_sources:[{"id":"ds-1"}]`; `GET /v1/data_sources/ds-1` returns `properties` missing `"Status"`; assert `PRESENT_DRIFTED`.
6. `verify_returnsMatchingWhenTitleAndAllRequiredPropertiesPresent` — both GETs succeed, all 4 names present (extra unrelated property key also present); assert `PRESENT_MATCHING`.
7. `verify_ignoresExtraUserOptionsAndUnrelatedProperties` — `Status` property present with a different/extra `select.options` list than the enum, plus a 5th unrelated user-added property; assert `PRESENT_MATCHING` (name-only check, ADR-0006/0008).
8. `findChildByIdentity_listsChildBlocksAndFiltersByTitle_returnsSingleMatch` — expect `GET /v1/blocks/{dashboardId}/children`; respond one `child_database` block titled `"Projects"` plus one other block type (e.g. `"child_page"`) that must be ignored; assert `Optional.of(matchingBlockId)`.
9. `findChildByIdentity_returnsEmptyWhenNoChildDatabaseTitledProjects` — respond blocks with no title match; assert `Optional.empty()`.
10. `findChildByIdentity_throwsOnMoreThanOneMatch` — respond two `child_database` blocks both titled `"Projects"`; assert `NotionApiException`, message contains `"2"`, does not contain the token.
11. `findChildByIdentity_paginatesAcrossPages` — first response `has_more:true`+`next_cursor:"c1"` with 0 matches, second response (requested with `start_cursor=c1`) has the single match; assert 2 requests sent, `Optional.of(id)` returned.
12. `repairShape_patchesDatabaseTitleWhenDrifted` — `GET /v1/databases/{id}` returns drifted title, all properties present; expect `PATCH /v1/databases/{id}` with `{"title":[...]}`; assert no `PATCH /v1/data_sources/...` issued.
13. `repairShape_addsMissingPropertyOnDataSource_neverSendsNull` — `GET` returns matching title; `GET /v1/data_sources/{dsId}` missing `"Due Date"`; expect exactly one `PATCH /v1/data_sources/{dsId}` with body `{"properties":{"Due Date":{"type":"date","date":{}}}}` — assert the request body contains no `null` value anywhere (non-destructive).
14. `repairShape_batchesMultipleMissingPropertiesInOnePatch` — two properties missing (`Description`, `Status`); assert exactly **one** `PATCH /v1/data_sources/{dsId}` call whose `properties` object has both keys (not two separate PATCH calls).
15. `repairShape_reAddedStatusCarriesEnumSeededOptions` — `Status` missing; assert the PATCH body's `properties.Status.select.options` equals the same 4 enum-derived option names as `createDatabase` uses.
16. `client_neverLeaksTokenInDatabaseSliceExceptionMessage` — respond `401` on `createDatabase`; assert message doesn't contain the configured token (reuses `NotionClient`'s existing coverage, one representative case for this slice — do not re-test 429/backoff/timeout, already covered by the page-slice adapter tests against the same shared `NotionClient`).

Use `server.verify()` at the end of each request-expectation test.

### 9.3 `ProjectTest` (plain domain unit, no Spring) — `domain.project`

- `create_defaultsStatusToPlannedWhenNull` — `Project.create("P", "d", null, null, null, workspaceId, null).getStatus()` equals `PLANNED`.
- `create_keepsProvidedStatus` — pass `ACTIVE` explicitly → `getStatus()` equals `ACTIVE`.
- `create_allowsNullDueDate` — `dueDate` argument `null` → `getDueDate()` is `null`, no exception.
- `create_keepsProvidedDueDate` — pass a `LocalDate` → round-trips via `getDueDate()`.
- `create_rejectsBlankName` — `null`/`""`/`"  "` all throw `IllegalArgumentException("Project name must not be null or blank")`.
- `create_rejectsNullWorkspaceId` — throws `IllegalArgumentException("Project workspaceId must not be null")`.
- `create_generatesNonNullUniqueId` — two `create(...)` calls with the same args produce different `getId()`.
- `create_isImmutable` — `@Value` sanity: no setter methods exist (compile-time proof — no explicit test needed beyond the class compiling with `@Value`; the effective test is that the record of tests above never mutate an instance and still pass).

### 9.4 `ProjectStatusTest` (plain enum unit) — `domain.project`

- `values_hasExactlyFourInDeclarationOrder` — `ProjectStatus.values()` equals `[PLANNED, ACTIVE, ON_HOLD, DONE]`.
- `displayName_returnsExpectedLabelsForEachValue` — `PLANNED→"Planned"`, `ACTIVE→"Active"`, `ON_HOLD→"On hold"`, `DONE→"Done"`.

### 9.5 `PropertyDefinitionTest` / `DatabaseSpecTest` / `ExpectedShapeTest` (plain unit, no Spring) — `application.port`

- `PropertyDefinition`: `constructor_rejectsBlankName`, `constructor_rejectsNullType`, `constructor_rejectsOptionsOnNonSelectType`, `constructor_allowsEmptyOptionsOnNonSelectType`, `constructor_allowsOptionsOnSelectType`, `of_createsWithEmptyOptions`.
- `DatabaseSpec`: `constructor_rejectsBlankTitle`, `constructor_rejectsNullOrEmptyProperties`, `constructor_rejectsZeroTitleProperties`, `constructor_rejectsMoreThanOneTitleProperty`, `constructor_acceptsExactlyOneTitleProperty`.
- `ExpectedShape`: `constructor_rejectsBlankTitle`, `constructor_rejectsNullOrEmptyRequiredProperties`, `constructor_acceptsValidInput`.

### 9.6 `CreateProjectsDatabaseServiceIT` (optional `@SpringBootTest`, Testcontainers Postgres) — `application.usecase.project`

Mirrors `CreateDashboardServiceIT`'s shape: `NotionProvisioningPort` supplied via `@TestConfiguration` with an in-memory fake (`Map<String, DbRecord>`-backed) implementing only the four database methods realistically (create assigns a UUID string, stores title+properties; verify/find/repair read/mutate the map) plus the four page methods delegating to a **fixed pre-adopted Dashboard id** (so the precondition FR-3 is satisfiable without re-implementing the page slice — the fake's `createRootPage`/`verifyPage`/etc. can be trivial fixed-return stubs since this IT only exercises the Projects step), and every other port method throwing `UnsupportedOperationException`.

- `execute_persistsProjectsDbLedgerRowOnFirstRun` — a fresh workspace with a pre-seeded `DASHBOARD` resource → `execute` → outcome `CREATED`; a direct repository read shows exactly one `PROJECTS_DB` `ProvisionedResource` row.
- `execute_convergesToOneRowAcrossThreeReruns` — run `execute` three times in sequence → still exactly one `PROJECTS_DB` row after all three; second/third outcomes are `RECONCILED` (FR-13); asserts the same `notionId` across all three reads.
- `execute_reachesRepairedOutcomeWhenFakeSimulatesExternalPropertyRemoval` — after the first run, mutate the fake's stored properties map directly (simulating an out-of-band Notion property deletion) → second `execute` → `PRESENT_DRIFTED` → `REPAIRED`, ledger row's `notionId` unchanged.
- `execute_throwsWhenPhaseAIncomplete` — a workspace with **no** `DASHBOARD` resource → `execute` throws `IllegalStateException`, and the repository shows **no** `PROJECTS_DB` row (confirms the orchestrator's Phase-A gate precondition holds even if this step is invoked directly).

Class name ends in `IT` so Failsafe runs it under `./mvnw verify` (the plugin is already configured per the Create Dashboard AUD-06 remediation). Zero real Notion calls; no `MockRestServiceServer` in this class.

---

## 10. Explicitly NOT built in this pass (scope guard for the Implementer)

- **Relations, rollups, or formulas of any kind.** `ensureRelation`/`ensureRollup`/`ensureFormula` keep their existing `UnsupportedOperationException` bodies, unchanged, verbatim. The Projects schema has **no** relation property this step (OQ-B deferred; FR-14). `Project.areaId`/`goalId` remain UUID-only references — do not add a Notion relation column for them.
- **Rows or sample data.** `hasSampleRecords`/`insertSampleRecords` keep their existing stub bodies, unchanged. This step provisions the **container only** — zero Notion database rows are written, ever, by this step.
- **The other six databases** (Tasks, Knowledge, Habits, Journal, Resources, People) and `GOALS_DB`/`REVIEWS_DB`. Only `PROJECTS_DB` is exercised. Do not generalize `projectsSpec()`/`projectsExpectedShape()` into a shared multi-database builder in this pass — each sibling step authors its own schema when it is built (YAGNI, matches architecture's explicit "sibling steps reuse this shape verbatim" framing without doing the reuse work prematurely).
- **`Project` persistence.** No `ProjectRepository` interface, no JPA entity, no Flyway migration, no `Project` reconstitution factory beyond the existing all-args builder. `status`/`dueDate` are in-memory domain fields only in this pass.
- **Any Notion `status`-type property.** The `Status` column is a `select`, seeded from `ProjectStatus.values()` — never the Notion `status` property type (ADR-0006 rejects it explicitly; `NotionPropertyType` has no `STATUS` value).
- **`docs/productivity/*` population.** Tracked as a non-blocking follow-up (architecture §11.1); do not add `PARA.md`/`GTD.md` content as part of this Implementer pass.
- **Any change to `ProjectProgressService`.** It is a separate, pre-existing domain service; do not make it status-aware in this pass even though `Project` now carries `status` (architecture §5.6 explicitly defers this).
- **A generic/reusable multi-property-type Notion mapping abstraction beyond `NotionPropertyType`'s 4-value closed set.** Do not add `NUMBER`/`CHECKBOX`/`PEOPLE`/etc. — YAGNI until a sibling database needs them.
- **A semantic Notion-Version range/comparison check at startup.** The existing `@NotBlank` fail-fast on `NotionProperties.version` is sufficient for this pass; a "must be `>= 2025-09-03`" parser is an optional follow-up, not required (§8).
- **Any Flyway migration.** No schema change of any kind.

---

## 11. Findings / notes back to the Architect

None blocking. One informational note, mirroring the Create Dashboard tech spec's §11 pattern: the cold-path "orphan found" branch in this step requires an **extra** `verify` call after `findChildByIdentity` (§4.4), unlike the Dashboard's `findRootByIdentity` which guarantees an exact match by construction. This is already anticipated by architecture §4.1's sequence diagram (the `alt orphan found → Svc->>Notion: verify(orphanId, ...)` branch under the cold path) — it is **not** a deviation, just called out here explicitly because a reader coming from the Create Dashboard tech spec's "cold-path hit is unconditionally RECONCILED" note (its §3.3) could otherwise mis-port that shortcut into this step, where it does not hold (identity here is title-only, drift is a separate check). No ADR or architecture change is needed; this spec's §4.4 pseudocode is the authoritative resolution.

---

## 12. Traceability (FR/NFR → spec section)

| Req | Satisfied by (this spec) |
|---|---|
| FR-1 | §4.1 unchanged `CreateProjectsDatabaseUseCase.execute(UUID)` |
| FR-2 | §4.4 `workspaceRepository.findById` → `IllegalStateException` before any Notion call; test 9.1-1 |
| FR-3 | §4.4 `resource(DASHBOARD)` empty → `IllegalStateException`; test 9.1-2 |
| FR-4 | Cold path `findChildByIdentity` empty → `createDatabase` → `record` → `CREATED`; §4.4/§4.5 row 1; test 9.1-3 |
| FR-5 | Warm `verify` `PRESENT_MATCHING` → `RECONCILED`, no write; §4.5 row 5; test 9.1-7 |
| FR-6a | Warm `ABSENT` → adopt-or-`createDatabase` → `REPAIRED`; §4.5 rows 7/8; tests 9.1-9, 9.1-10 |
| FR-6b | Warm `PRESENT_DRIFTED` → `repairShape` (title and/or add missing prop, non-destructive) → `REPAIRED`; §5.4; test 9.1-8 |
| FR-7 | `verify`/`findChildByIdentity` on every path before any `RECONCILED`; §4.4; ADR-0008 |
| FR-8 | Cold `findChildByIdentity` (parent-scoped enumeration) → adopt; §4.4; ADR-0008 + ADR-0004 |
| FR-9 | `>1` child_database match → `NotionApiException` → `FAILED`; §5.3; tests 9.1-6, 9.1-11, 9.2-10 |
| FR-10 | `WorkspaceLedgerWriter.record` — own tx; §4.6 |
| FR-11 | Returns `ProvisioningStepResult(PROJECTS_DB, …)`; failures propagate; §4.4 |
| FR-12 | Notion-before-ledger ordering + own-tx write + next-run adoption; tests 9.1-12, 9.1-13 |
| FR-13 | Adoption-before-create both paths; upsert `record`; IT convergence; test 9.6 |
| FR-14 | Only the four DB methods invoked; test 9.1-14; schema has no relation property (§4.2) |
| §3 Status/Due Date columns + domain backing (OQ-A) | §2 (`ProjectStatus`, `Project`); §4.2/§4.3 schema; tests 9.3, 9.4, 9.1-16 |
| NFR-1 | Strict per-path live verification; §4.4 (inherited ADR-0002) |
| NFR-2 | Notion-before-ledger + no rollback; next-run reconcile; §4.4; non-destructive repair §5.4 |
| NFR-3 | Mockito service tests + `MockRestServiceServer` adapter tests + fake-port IT + domain unit tests; §9 |
| NFR-4 | Stub throw removed only at adapter cutover, gated by §9 tests |
| NFR-5 | Implementer adds SLF4J logging inside `execute` per architecture §6 (workspaceId, dashboardId, prior ledger id, `VerificationResult`, acted-on id, outcome — no token, no raw body); no dedicated test required beyond not breaking existing tests |
| NFR-6 | §8 Security; message-construction rule reused from `NotionClient`; test 9.2-16 |
| NFR-7 | No shared mutable state; only `PROJECTS_DB` ledger entry written; §4.6 |
| NFR-8 | Upsert `record` → exactly one `PROJECTS_DB` entry (`Workspace.record` semantics) |
| NFR-9 | Bounded call count per run; §5.6 |
| NFR-10 | `429`/`529` `Retry-After` clamp in reused `NotionClient` (unchanged) |

---

## Implementation notes

- `backend/src/main/java/com/lifeos/domain/project/ProjectStatus.java` — new enum, 4 values + `displayName()`.
- `backend/src/main/java/com/lifeos/domain/project/Project.java` — add `status`/`dueDate` fields; `create(...)` gains 2 params in the position specified in §2.2; `status` defaults to `PLANNED` when `null`; `dueDate` nullable passthrough.
- `backend/src/main/java/com/lifeos/application/port/NotionPropertyType.java` — new enum, 4 values.
- `backend/src/main/java/com/lifeos/application/port/PropertyDefinition.java` — new validated record.
- `backend/src/main/java/com/lifeos/application/port/DatabaseSpec.java` — refined to `(String title, List<PropertyDefinition> properties)` with compact-constructor validation (non-blank title, non-empty properties, exactly one `TITLE`).
- `backend/src/main/java/com/lifeos/application/port/ExpectedShape.java` — refined to `(String title, List<PropertyDefinition> requiredProperties)` with compact-constructor validation.
- `backend/src/main/java/com/lifeos/application/port/NotionProvisioningPort.java` — `findChildByIdentity` gains `ExpectedShape` third param; `verify`/`createDatabase`/`repairShape` param names clarified (no arity change on those three).
- `backend/src/main/java/com/lifeos/application/usecase/project/CreateProjectsDatabaseService.java` — 3-arg constructor (`NotionProvisioningPort`, `WorkspaceRepository`, `WorkspaceLedgerWriter`); real §4.4 algorithm (all 9 decision-table rows); `projectsSpec()`/`projectsExpectedShape()` package-private statics; stub throw removed; SLF4J logging per NFR-5; no `@Transactional`.
- `backend/src/main/java/com/lifeos/infrastructure/adapter/notion/NotionProvisioningAdapter.java` — implement `createDatabase`/`verify`/`findChildByIdentity`/`repairShape` against the existing `NotionClient`; every other stub method body untouched, verbatim.
- `backend/src/main/java/com/lifeos/infrastructure/adapter/notion/dto/NotionDatabaseResponse.java`, `NotionDataSourceSummary.java`, `NotionDataSourceResponse.java`, `NotionPropertyConfig.java`, `NotionSelectConfig.java`, `NotionSelectOption.java`, `NotionBlockChildrenResponse.java`, `NotionBlock.java`, `NotionChildDatabase.java` — new adapter-internal Jackson DTOs, all `@JsonIgnoreProperties(ignoreUnknown = true)`.
- `backend/src/test/java/com/lifeos/application/usecase/workspace/CreateDashboardServiceIT.java` — `InMemoryPageOnlyNotionPort.findChildByIdentity` gains the `ExpectedShape` param (compile-only).
- `backend/src/test/java/com/lifeos/application/usecase/workspace/CreateDashboardServiceTest.java` — line 236 `never()).findChildByIdentity(any(), any())` → `(any(), any(), any())` (compile-only).
- `backend/src/test/java/com/lifeos/domain/project/ProjectTest.java` — new, 8 tests (§9.3).
- `backend/src/test/java/com/lifeos/domain/project/ProjectStatusTest.java` — new, 2 tests (§9.4).
- `backend/src/test/java/com/lifeos/application/port/PropertyDefinitionTest.java`, `DatabaseSpecTest.java`, `ExpectedShapeTest.java` — new value-type unit tests (§9.5).
- `backend/src/test/java/com/lifeos/application/usecase/project/CreateProjectsDatabaseServiceTest.java` — fully rewritten (3-arg constructor), 17 tests (§9.1).
- `backend/src/test/java/com/lifeos/infrastructure/adapter/notion/NotionProvisioningAdapterDatabaseTest.java` (or appended to the existing `NotionProvisioningAdapterTest`) — new `MockRestServiceServer` contract tests, 17 tests (§9.2's 16 cases; the archived/in-trash case is split into two independent tests because a single `MockRestServiceServer` instance cannot register a second expectation after the first request has already been dispatched).
- `backend/src/test/java/com/lifeos/application/usecase/project/CreateProjectsDatabaseServiceIT.java` — new `@SpringBootTest` + Testcontainers Postgres IT, 4 tests (§9.6); fake port's in-memory database map is cleared in a `@BeforeEach` since the Spring context (and the fake port bean) is reused across test methods in the same class.

No changes to `WorkspaceController`, `WorkspaceCommands`, `ApiExceptionHandler`, any web/CLI DTO, `infrastructure.adapter.persistence`, `application.yml`, or any Flyway migration.

### Verification (this Implementer pass)

- `./mvnw test` (unit + slice tier): `Tests run: 190, Failures: 0, Errors: 0, Skipped: 0` — BUILD SUCCESS.
- `./mvnw verify` (adds Testcontainers ITs via Failsafe, Podman-backed): unit tier `Tests run: 190, Failures: 0, Errors: 0, Skipped: 0`; failsafe `*IT` tier `Tests run: 9, Failures: 0, Errors: 0, Skipped: 0` (`CreateDashboardServiceIT` 3, `CreateProjectsDatabaseServiceIT` 4, `CreateWorkspaceIT` 2) — BUILD SUCCESS.
