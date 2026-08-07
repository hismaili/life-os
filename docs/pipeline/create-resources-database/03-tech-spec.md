# 03 — Technical Specification: Create Resources Database (Phase B — sibling database + bounded port/adapter extension)

Status: Ready for Implementer
Owner (SME stage): pipeline automation
Input: `docs/pipeline/create-resources-database/02-architecture.md` (FINAL, no open questions) + `adr/ADR-0013-url-property-type.md` (Accepted) + reused by reference `../create-projects-database/adr/ADR-0005..0008` and `../create-tasks-database/adr/ADR-0009` (not restated) + existing code under `backend/src/main/java/com/lifeos/`.
Grounding skills applied: `spring-boot-conventions`, `spring-data-jpa` (N/A — no schema/migration change; no `ResourceRepository`/table exists), `spring-security` (N/A — no new auth surface, token handling reused verbatim), `spring-testing`.

> This is a **pattern-application spec with one bounded, in-scope extension**: the production delta is three files. `CreateResourcesDatabaseService` is mirrored verbatim off the already-shipped `CreateTasksDatabaseService` (`backend/src/main/java/com/lifeos/application/usecase/task/CreateTasksDatabaseService.java`) with `TASKS_DB → RESOURCES_DB`, no `SELECT`/status property, and a Resources-specific two-property schema. Unlike the Tasks pass, this step **also** extends `NotionPropertyType` with a new `URL` member and adds the matching `case URL` branch to `NotionProvisioningAdapter.propertyConfig` — the same shape of extension already done for `DATE` (ADR-0006/ADR-0012), now recorded in ADR-0013. Reference pattern for structure/rigor: `docs/pipeline/create-tasks-database/03-tech-spec.md` (own-transaction ledger write, verify-before-trust, outcome mapping, Mockito service tests, Testcontainers IT) and `docs/pipeline/create-projects-database/03-tech-spec.md` §5.1/§9.2 (how the adapter's typed schema + its `MockRestServiceServer` contract tests are structured — the model for the one new `URL` adapter test here).

---

## 1. Package layout (file-by-file change list)

```
com.lifeos
 ├─ domain.resource/
 │    Resource.java                              (UNCHANGED — backend/src/main/java/com/lifeos/domain/resource/Resource.java;
 │                                                             title + url already present, l.12–13)
 │
 ├─ application
 │   ├─ port/
 │   │    NotionPropertyType.java                 [MODIFIED] add URL: {TITLE, RICH_TEXT, SELECT, DATE} → {TITLE, RICH_TEXT, SELECT, DATE, URL}
 │   │    (DatabaseSpec, ExpectedShape,
 │   │     PropertyDefinition, NotionProvisioningPort,
 │   │     VerificationResult)                    (ALL UNCHANGED)
 │   └─ usecase.resource/
 │        CreateResourcesDatabaseService.java      [MODIFIED] stub removed; 3-arg constructor (+WorkspaceRepository);
 │                                                            real verify/create/adopt/repair algorithm targeting RESOURCES_DB;
 │                                                            adds resourcesSpec()/resourcesExpectedShape() private-static helpers
 │        CreateResourcesDatabaseUseCase.java      (unchanged — ProvisioningStepResult execute(UUID))
 │
 └─ infrastructure.adapter.notion/
      NotionProvisioningAdapter.java              [MODIFIED] add `case URL -> Map.of("type", "url", "url", Map.of())`
                                                              to the exhaustive propertyConfig switch (l.262–270)
      (NotionClient, NotionApiException,
       NotionProperties, dto/*)                   (ALL UNCHANGED)
```

### 1.1 `application/port/NotionPropertyType.java` [MODIFIED] — exact one-line change

Current (`backend/src/main/java/com/lifeos/application/port/NotionPropertyType.java`):
```java
package com.lifeos.application.port;

public enum NotionPropertyType { TITLE, RICH_TEXT, SELECT, DATE }
```

New:
```java
package com.lifeos.application.port;

public enum NotionPropertyType { TITLE, RICH_TEXT, SELECT, DATE, URL }
```

Only the enum's constant list changes — one line, one new trailing constant. `TITLE`, `RICH_TEXT`, `SELECT`, `DATE` keep their identity and declaration order; every existing caller (`CreateProjectsDatabaseService`, `CreateTasksDatabaseService`, and this step's own `resourcesSpec()`) compiles and behaves unchanged (spec NFR-5; ADR-0013 §Decision).

### 1.2 `infrastructure/adapter/notion/NotionProvisioningAdapter.java` [MODIFIED] — exact branch

Current `propertyConfig` (l.262–270):
```java
private static Map<String, Object> propertyConfig(PropertyDefinition definition) {
    return switch (definition.type()) {
        case TITLE -> Map.of("type", "title", "title", Map.of());
        case RICH_TEXT -> Map.of("type", "rich_text", "rich_text", Map.of());
        case DATE -> Map.of("type", "date", "date", Map.of());
        case SELECT -> Map.of("type", "select", "select",
                Map.of("options", definition.options().stream().map(name -> Map.of("name", name)).toList()));
    };
}
```

New (add one `case`, no reordering, no other line touched):
```java
private static Map<String, Object> propertyConfig(PropertyDefinition definition) {
    return switch (definition.type()) {
        case TITLE -> Map.of("type", "title", "title", Map.of());
        case RICH_TEXT -> Map.of("type", "rich_text", "rich_text", Map.of());
        case DATE -> Map.of("type", "date", "date", Map.of());
        case URL -> Map.of("type", "url", "url", Map.of());
        case SELECT -> Map.of("type", "select", "select",
                Map.of("options", definition.options().stream().map(name -> Map.of("name", name)).toList()));
    };
}
```

- Emits exactly `{"type":"url","url":{}}` — no `options`, no extra keys (ADR-0013 §Decision; Notion [Property object](https://developers.notion.com/reference/property-object) reference: the `url` configuration is the empty object `"url": {}`).
- **This is the only edit to `NotionProvisioningAdapter.java`.** No other method, import, or branch changes.
- **One helper, two call sites — confirmed applies to both paths.** `propertyConfig` is invoked from:
  - `createDatabase` (l.128–129): `for (PropertyDefinition property : spec.properties()) { properties.put(property.name(), propertyConfig(property)); }` — every property in a `DatabaseSpec`, including a `URL` one, gets `{"type":"url","url":{}}` at creation time.
  - `repairShape`'s add-missing loop (l.200–203): `if (!dataSource.properties().containsKey(required.name())) { missing.put(required.name(), propertyConfig(required)); }` — a missing `URL` property is added with the identical config on repair.

  No second edit is needed for repair; both call sites share the one private static helper (ADR-0013 §Decision, spec FR-5).
- The `switch` has no `default` — it is exhaustive over the enum. Adding `URL` to `NotionPropertyType` without adding this `case` is a **compile error** (*JLS §14.11.2*); the Implementer cannot forget the branch or ship the enum-only half of the change.

### 1.3 `application/usecase/resource/CreateResourcesDatabaseService.java` [MODIFIED]

See §3 for the full intended source and line-level delta from `CreateTasksDatabaseService`.

### 1.4 Confirmed NOT modified

- **`NotionClient`** and its DTOs (`dto/*`) — reused verbatim; the `url` property travels over the exact same `POST /v1/databases` / `PATCH /v1/data_sources/{id}` transport already used for every other property type. No new endpoint, no new header, no new transport concern.
- **`NotionProvisioningPort`** (`application.port`) — the interface's four database-slice method signatures (`createDatabase`, `verify`, `findChildByIdentity`, `repairShape`) are unchanged. Resources passes a `RESOURCES_DB` `ProvisionedResourceType` and its own `DatabaseSpec`/`ExpectedShape` instances, exactly as Projects/Tasks do today. **No new port method.**
- **`DatabaseSpec`, `ExpectedShape`, `PropertyDefinition`** (`application.port`) — record shapes and compact-constructor invariants unchanged. `PropertyDefinition`'s "`options` only valid for `SELECT`" invariant (l.15–17) is untouched and correctly rejects a `URL` property carrying options; `resourcesSpec()` only ever builds `URL` properties via the no-options `PropertyDefinition.of(name, type)` factory, so this invariant is never exercised adversarially by this step. **No new value type.**
- **`WorkspaceLedgerWriter`** (`application.usecase.workspace`) — `record(workspaceId, type, notionId)`, its own `@Transactional`, reused verbatim.
- **`domain/resource/Resource.java`** — `title` and `url` fields already exist (l.12–13); **no domain change**. `Resource.create` already enforces non-blank `title`; `url` is nullable and carries no domain-level validation (URL format is a Notion-side rendering concern, not a domain invariant — out of scope; spec §7).
- **`domain.workspace.ProvisionedResourceType.RESOURCES_DB`** — already defined (`ProvisionedResourceType.java` l.5), no enum change.
- **`NotionProvisioningAdapter`'s other three database methods** (`verify`, `findChildByIdentity`, `repairShape`'s control flow itself — only the `propertyConfig` helper it calls changes) and all four page methods, `ensureRelation`/`ensureRollup`/`ensureFormula`, `hasSampleRecords`/`insertSampleRecords` — untouched.

If any of the above proves insufficient during implementation, that is an **Architect-level finding** (`findings.yml`, `raised_by: spring-sme`, `suspected_layer: architecture`) — do not redesign silently (architecture §"Reused UNCHANGED" note, §9 findings 7).

**Ripple:** none beyond the two port/adapter files above and the one service file. No signature of any shared type changes arity or parameter types — `NotionPropertyType` only grows a constant, `propertyConfig` only grows a `case`. No other production or test file requires a *compile-forced* edit. (The **existing** `NotionProvisioningAdapterDatabaseTest` is extended with new test methods per §5.3 — those are additive, not forced by a signature change.)

No change to `domain.workspace`, `infrastructure.adapter.persistence`, `infrastructure.adapter.web`/`adapter.cli`, any Flyway migration, or `application.yml`. Package-by-feature preserved (`spring-boot-conventions`).

---

## 2. Exact schema (title + properties)

Grounded in the complete `Resource` aggregate (`domain/resource/Resource.java` l.12–13); every field already exists — no domain change.

| §2 property | Field grounding | `NotionPropertyType` | Notion config |
|---|---|---|---|
| **Title** (db title property) | `Resource.title` (`Resource.java` l.12, non-blank via `Resource.create`) | `TITLE` | `{ "type": "title", "title": {} }` |
| **URL** | `Resource.url` (`Resource.java` l.13, **nullable**) | `URL` **[new type]** | `{ "type": "url", "url": {} }` — ADR-0013 |

Database (page) title: `"Resources"` (fixed constant; not derived from the workspace name — identity is already scoped by the unique Dashboard parent, same rule as Projects/Tasks).

**Naming note:** the title *property* is named `"Title"` (matching `Resource.title`), same convention as Tasks (`Task.title` → `"Title"`); Projects named it `"Name"` (matching `Project.name`) — each database names its title property after its own aggregate's field.

**Only two properties — no `SELECT`.** Unlike Projects/Tasks, Resources has no status/enum field, so there is no enum-seeded option-labels step (ADR-0009's label concern does not arise here).

**Excluded from this schema** (architecture §5.4, spec §8): the `Resource.knowledgeId → Knowledge` relation (deferred to Phase C — Create Relations; requires both databases to exist first) and any rollup/formula/row. `Resource.workspaceId` is expressed structurally (child of the Dashboard page), not as a column — same convention as every prior database step.

**Verify is name-only** (ADR-0008, inherited unchanged): a user renaming/adding columns in Notion never triggers repair; the `url` type is only ever *established* at creation/add-missing, never *reconciled*. If a user out-of-band retypes the `URL` column to `rich_text` (or vice versa), it is **not** detected as drift and **not** repaired — an accepted consequence, identical to Projects/Tasks not healing a retyped `Status`/`Due Date` column (ADR-0008 §Consequences; ADR-0013 §Consequences).

### `resourcesSpec()` / `resourcesExpectedShape()` construction

```java
static DatabaseSpec resourcesSpec() {
    return new DatabaseSpec(TITLE, List.of(
            PropertyDefinition.of("Title", NotionPropertyType.TITLE),   // ← Resource.title
            PropertyDefinition.of("URL",   NotionPropertyType.URL)));   // ← Resource.url  (ADR-0013)
}

static ExpectedShape resourcesExpectedShape() {
    return new ExpectedShape(TITLE, resourcesSpec().properties());
}
```

Both properties use the no-options `PropertyDefinition.of(name, type)` factory (neither is `SELECT`), so `PropertyDefinition`'s "options only for `SELECT`" compact-constructor invariant (§1.4) is satisfied trivially and needs no special handling.

---

## 3. `application.usecase.resource.CreateResourcesDatabaseService` [MODIFIED]

### 3.1 Current state (to be replaced)

```java
package com.lifeos.application.usecase.resource;

import com.lifeos.application.dto.workspace.ProvisioningStepResult;
import com.lifeos.application.port.NotionProvisioningPort;
import com.lifeos.application.usecase.workspace.WorkspaceLedgerWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CreateResourcesDatabaseService implements CreateResourcesDatabaseUseCase {

    private final NotionProvisioningPort notion;
    private final WorkspaceLedgerWriter ledger;

    @Override
    public ProvisioningStepResult execute(UUID workspaceId) {
        throw new UnsupportedOperationException(
                "Resources database creation not yet implemented: requires the Notion adapter");
    }
}
```

(`backend/src/main/java/com/lifeos/application/usecase/resource/CreateResourcesDatabaseService.java`, current 2-arg constructor + stub throw — NFR-4/"no silent no-op" is satisfied today only by the explicit throw; it is removed as this real implementation lands.)

### 3.2 Full intended source (verbatim mirror of `CreateTasksDatabaseService`, `TASKS_DB → RESOURCES_DB`, no SELECT/status property)

```java
package com.lifeos.application.usecase.resource;

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
import static com.lifeos.domain.workspace.ProvisionedResourceType.RESOURCES_DB;

@Slf4j
@Service
@RequiredArgsConstructor
public class CreateResourcesDatabaseService implements CreateResourcesDatabaseUseCase {

    private static final String TITLE = "Resources";

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

        DatabaseSpec spec = resourcesSpec();
        ExpectedShape expected = resourcesExpectedShape();
        Optional<String> ledgerId = workspace.resource(RESOURCES_DB).map(ProvisionedResource::notionId);

        ProvisioningStepResult result = ledgerId.isPresent()
                ? executeWarmPath(workspaceId, ledgerId.get(), dashboardId, spec, expected)
                : executeColdPath(workspaceId, dashboardId, spec, expected);

        log.info("Resources database step for workspace {}: dashboardId={}, priorLedgerId={}, outcome={}",
                workspaceId, dashboardId, ledgerId.orElse("none"), result.outcome());
        return result;
    }

    private ProvisioningStepResult executeWarmPath(UUID workspaceId, String existingId, String dashboardId,
                                                     DatabaseSpec spec, ExpectedShape expected) {
        VerificationResult result = notion.verify(existingId, RESOURCES_DB, expected);
        log.info("Resources database verify for workspace {}: notionId={}, result={}", workspaceId, existingId, result);
        return switch (result) {
            case PRESENT_MATCHING -> new ProvisioningStepResult(RESOURCES_DB, ProvisioningOutcome.RECONCILED, null);
            case PRESENT_DRIFTED -> {
                notion.repairShape(existingId, expected);
                ledger.record(workspaceId, RESOURCES_DB, existingId);
                yield new ProvisioningStepResult(RESOURCES_DB, ProvisioningOutcome.REPAIRED, "database drifted; shape repaired");
            }
            case ABSENT -> {
                Optional<String> found = notion.findChildByIdentity(dashboardId, RESOURCES_DB, expected);
                if (found.isPresent()) {
                    ledger.record(workspaceId, RESOURCES_DB, found.get());
                    yield new ProvisioningStepResult(RESOURCES_DB, ProvisioningOutcome.REPAIRED, "ledger id was stale; re-adopted existing database");
                }
                String newId = notion.createDatabase(dashboardId, spec);
                ledger.record(workspaceId, RESOURCES_DB, newId);
                yield new ProvisioningStepResult(RESOURCES_DB, ProvisioningOutcome.REPAIRED, "ledger id was stale; database recreated");
            }
        };
    }

    private ProvisioningStepResult executeColdPath(UUID workspaceId, String dashboardId, DatabaseSpec spec, ExpectedShape expected) {
        Optional<String> found = notion.findChildByIdentity(dashboardId, RESOURCES_DB, expected);
        log.info("Resources database findChildByIdentity for workspace {}: found={}", workspaceId, found.isPresent());
        if (found.isEmpty()) {
            String newId = notion.createDatabase(dashboardId, spec);
            ledger.record(workspaceId, RESOURCES_DB, newId);
            return new ProvisioningStepResult(RESOURCES_DB, ProvisioningOutcome.CREATED, null);
        }

        String orphanId = found.get();
        VerificationResult orphanVerify = notion.verify(orphanId, RESOURCES_DB, expected);
        return switch (orphanVerify) {
            case PRESENT_MATCHING -> {
                ledger.record(workspaceId, RESOURCES_DB, orphanId);
                yield new ProvisioningStepResult(RESOURCES_DB, ProvisioningOutcome.RECONCILED, null);
            }
            case PRESENT_DRIFTED -> {
                notion.repairShape(orphanId, expected);
                ledger.record(workspaceId, RESOURCES_DB, orphanId);
                yield new ProvisioningStepResult(RESOURCES_DB, ProvisioningOutcome.REPAIRED, "adopted orphan database was drifted; shape repaired");
            }
            case ABSENT -> {
                String newId = notion.createDatabase(dashboardId, spec);
                ledger.record(workspaceId, RESOURCES_DB, newId);
                yield new ProvisioningStepResult(RESOURCES_DB, ProvisioningOutcome.CREATED, null);
            }
        };
    }

    static DatabaseSpec resourcesSpec() {
        return new DatabaseSpec(TITLE, List.of(
                PropertyDefinition.of("Title", NotionPropertyType.TITLE),
                PropertyDefinition.of("URL", NotionPropertyType.URL)));
    }

    static ExpectedShape resourcesExpectedShape() {
        return new ExpectedShape(TITLE, resourcesSpec().properties());
    }
}
```

**Line-level delta from `CreateTasksDatabaseService.java` (for reference — the Implementer may diff against it directly):**
1. Package `com.lifeos.application.usecase.task` → `com.lifeos.application.usecase.resource`.
2. Drop the `com.lifeos.domain.task.TaskStatus` import (no status field) and the `java.util.Arrays` import (no `Arrays.stream` needed — no enum-to-options mapping).
3. Static import `TASKS_DB` → `RESOURCES_DB`.
4. `TITLE = "Tasks"` → `TITLE = "Resources"`.
5. Every `TASKS_DB` token (method bodies, log message prefixes "Tasks database …" → "Resources database …") → `RESOURCES_DB`.
6. `tasksSpec()`/`tasksExpectedShape()` → `resourcesSpec()`/`resourcesExpectedShape()`.
7. In `resourcesSpec()`: drop the `Description` (`RICH_TEXT`) and `Status` (`SELECT`, enum-seeded options) and `Due Date` (`DATE`) properties entirely; the list becomes exactly two entries — `PropertyDefinition.of("Title", TITLE)` and `PropertyDefinition.of("URL", URL)`. No `Arrays.stream(...).map(Enum::name)` block (no status enum to seed).
8. Constructor (3-arg: `NotionProvisioningPort`, `WorkspaceRepository`, `WorkspaceLedgerWriter`), `execute`, `executeWarmPath`, `executeColdPath` bodies are otherwise **byte-for-byte structurally identical** (same branching, same log statement shape, same outcome/detail strings) — only the `TASKS_DB`/`RESOURCES_DB` and `"Tasks"`/`"Resources"` tokens differ.

### 3.3 Outcome decision table

Reused verbatim from Tasks §3.3 / Projects §4.5 (architecture §4 "Outcome decision table"), substituting `RESOURCES_DB`. Not restated as a new table — the 9-row shape (`CREATED` only on first-time create with no prior ledger record; adoption is never `CREATED`; `REPAIRED` ⇔ a Notion write happened this run; `RECONCILED` ⇔ none; `>1` identity match ⇒ `FAILED` via propagated `NotionApiException`) applies identically; see §5 test plan for the per-row test mapping.

### 3.4 Transaction boundary — summary

| Class | Transaction | Notes |
|---|---|---|
| `CreateResourcesDatabaseService.execute` | **none** | Pure port orchestration; mirrors `CreateTasksDatabaseService`/`CreateProjectsDatabaseService` — several Notion HTTP calls would hold a DB connection across slow remote work if annotated. |
| `WorkspaceLedgerWriter.record` | `@Transactional` (existing, unchanged) | The **only** transactional write in this step. |
| `JpaWorkspaceRepository.findById` | `@Transactional(readOnly = true)` (existing, unchanged) | Read path for `workspaceRepository.findById`. |
| `NotionProvisioningAdapter.*` / `NotionClient.*` | none | Not a transactional resource; unchanged. |

No new `@Service`/`@Component`/`@Repository` bean.

---

## 4. Security

- **Token handling**: unchanged. `NotionClient` remains the only class reading the token; `CreateResourcesDatabaseService` never touches it. The `URL` branch in `propertyConfig` is a pure in-process JSON-shape mapper — it makes no HTTP call and carries no secret.
- **Token-never-leaked (NFR-2)**: enforced structurally exactly as Tasks/Projects — `NotionApiException` messages built only from status + Notion's `code`/`message`, plus the reused `>1`-match message which interpolates only the match count and `expected.title()` (constant `"Resources"`, not a secret).
- **No REST/CLI authn change, no OAuth, no per-Person token, no new config property.**

---

## 5. Test plan (write these first — TDD)

Narrowest-sufficient tier per class (`spring-testing`). Build/verify order: adapter contract test extension (pure JSON-shape, no dependencies) → service unit tests (mocked port) → optional wiring IT. No new domain unit tests (`Resource` unchanged, no domain change). No live Notion, no token, no network egress anywhere.

### 5.1 Adapter contract test — extend the existing `NotionProvisioningAdapterDatabaseTest`

File: `backend/src/test/java/com/lifeos/infrastructure/adapter/notion/NotionProvisioningAdapterDatabaseTest.java` [MODIFIED — additive only; do not remove or change any existing test method or the existing `EXPECTED_SHAPE`/`databaseJson` fixtures].

Add a **second, URL-specific fixture** alongside the existing `EXPECTED_SHAPE` (do not repurpose the Projects fixture — it must stay `PROJECTS_DB`-flavored for its own tests):

```java
private static final ExpectedShape URL_EXPECTED_SHAPE = new ExpectedShape("Resources", List.of(
        PropertyDefinition.of("Title", NotionPropertyType.TITLE),
        PropertyDefinition.of("URL", NotionPropertyType.URL)));
```

Add exactly two new `@Test` methods (mirroring the `MockRestServiceServer` idiom already used by `createDatabase_postsDatabaseWithInitialDataSource_returnsDatabaseId` and `repairShape_addsMissingPropertyOnDataSource_neverSendsNull`):

1. **`createDatabase_postsUrlPropertyWithEmptyUrlConfig`** — asserts `createDatabase`'s request JSON contains the `URL` property with `{"type":"url","url":{}}`.
   ```java
   @Test
   void createDatabase_postsUrlPropertyWithEmptyUrlConfig() {
       DatabaseSpec spec = new DatabaseSpec("Resources", URL_EXPECTED_SHAPE.requiredProperties());

       server.expect(requestTo("https://api.notion.com/v1/databases"))
               .andExpect(method(HttpMethod.POST))
               .andExpect(jsonPath("$.title[0].text.content").value("Resources"))
               .andExpect(jsonPath("$.initial_data_source.properties.Title.type").value("title"))
               .andExpect(jsonPath("$.initial_data_source.properties.URL.type").value("url"))
               .andExpect(jsonPath("$.initial_data_source.properties.URL.url").isEmpty())
               .andRespond(withSuccess("{\"id\":\"new-db-id\",\"data_sources\":[{\"id\":\"ds-1\",\"name\":\"Resources\"}]}", MediaType.APPLICATION_JSON));

       String id = adapter.createDatabase(DASHBOARD_ID, spec);

       assertThat(id).isEqualTo("new-db-id");
       server.verify();
   }
   ```
   `jsonPath("$....url").isEmpty()` asserts the `url` value is the empty JSON object `{}` (no keys) — exactly `PropertyDefinition`'s "no options, no extra keys" contract (ADR-0013 §Decision).

2. **`repairShape_addsMissingUrlPropertyWithEmptyUrlConfig`** — asserts the repair add-missing path emits the identical config.
   ```java
   @Test
   void repairShape_addsMissingUrlPropertyWithEmptyUrlConfig() {
       server.expect(requestTo("https://api.notion.com/v1/databases/db-id"))
               .andExpect(method(HttpMethod.GET))
               .andRespond(withSuccess(databaseJson(false, false, "Resources"), MediaType.APPLICATION_JSON));
       server.expect(requestTo("https://api.notion.com/v1/data_sources/ds-1"))
               .andExpect(method(HttpMethod.GET))
               .andRespond(withSuccess("""
                       {"properties": {"Title": {"type":"title"}}}
                       """, MediaType.APPLICATION_JSON));
       server.expect(requestTo("https://api.notion.com/v1/data_sources/ds-1"))
               .andExpect(method(HttpMethod.PATCH))
               .andExpect(jsonPath("$.properties.URL.type").value("url"))
               .andExpect(jsonPath("$.properties.URL.url").isEmpty())
               .andExpect(jsonPath("$.properties.Title").doesNotExist())
               .andRespond(withSuccess("{\"properties\":{}}", MediaType.APPLICATION_JSON));

       adapter.repairShape("db-id", URL_EXPECTED_SHAPE);

       server.verify();
   }
   ```
   Note this reuses the shared `databaseJson(archived, inTrash, title)` helper already in the file (l.359–369) with `title="Resources"` — no new fixture helper needed. `jsonPath("$.properties.Title").doesNotExist()` reconfirms the add-only/non-destructive repair contract (ADR-0008, NFR-3) for this new property type too.

**Exact expectations, stated:** both new tests assert the emitted JSON for a `URL`-typed `PropertyDefinition` is exactly `{"type":"url","url":{}}` — `type` equals the string `"url"`, and the nested `url` object has **zero** keys (no `options`, no extra keys) — on **both** `createDatabase` and `repairShape`'s add-missing `PATCH`. This is the adapter-level proof of ADR-0013 §Decision and spec AC-13/FR-5.

File total after this change: 20 test methods (18 existing + 2 new).

### 5.2 `application/usecase/resource/CreateResourcesDatabaseServiceTest.java` [NEW — full rewrite of the existing stub test]

Delete the existing single test (`execute_throwsUnsupportedOperationExceptionUntilNotionAdapterExists`, 2-arg constructor) — the constructor becomes 3-arg, a breaking change. Mirror `CreateTasksDatabaseServiceTest` (`backend/src/test/java/com/lifeos/application/usecase/task/CreateTasksDatabaseServiceTest.java`) exactly, substituting `RESOURCES_DB`/`resourcesSpec`/`resourcesExpectedShape` and the two-property Resources shape (no `TaskStatus`/enum-options assertions apply). Mocks: `NotionProvisioningPort notion`, `WorkspaceRepository workspaceRepository`, `WorkspaceLedgerWriter ledger`. Fixture helper: `Workspace.reconstitute(id, UUID.randomUUID(), "Personal", List.of()).record(DASHBOARD, "dash-id")` (and `.record(RESOURCES_DB, notionId)` for warm-path fixtures).

**Expected count: 17 tests** (same count and shape as Tasks §5.1) — one per outcome-table row (9) + preconditions (2) + ambiguous-match propagation (2, cold and warm-ABSENT) + Notion-failure propagation without ledger write (2) + never-invokes-unrelated-port-methods (1) + not-`@Transactional` reflection (1) + `resourcesSpec()`/`resourcesExpectedShape()` direct assertions (2). Full method list:

1. `execute_throwsWhenWorkspaceNotFound` — `workspaceRepository.findById` empty → `IllegalStateException("Workspace not found: " + id)`; `verifyNoInteractions(notion)`, `verifyNoInteractions(ledger)` (FR-10).
2. `execute_throwsWhenNoDashboardLedgerEntry` — workspace present, no `DASHBOARD` resource → `IllegalStateException("No confirmed Dashboard for workspace " + id)`; `verifyNoInteractions(notion)`, `verifyNoInteractions(ledger)` (FR-9).
3. `execute_createsWhenColdAndNoOrphan` — no `RESOURCES_DB` resource; `findChildByIdentity(dashId, RESOURCES_DB, expected)` → `Optional.empty()`; `createDatabase(dashId, spec)` → `"new-db-id"`; capture the `DatabaseSpec` passed to `createDatabase` and assert `properties()` has exactly 2 entries in order `Title`(TITLE)/`URL`(URL), and neither carries `options`; `ledger.record(workspaceId, RESOURCES_DB, "new-db-id")` called once; outcome `CREATED`, `detail` null (row 1).
4. `execute_adoptsWhenColdAndOrphanMatches` — `findChildByIdentity` → `Optional.of("orphan-id")`; `verify("orphan-id", RESOURCES_DB, expected)` → `PRESENT_MATCHING`; assert **no** `createDatabase`/`repairShape`; `ledger.record(workspaceId, RESOURCES_DB, "orphan-id")`; outcome `RECONCILED` (row 2).
5. `execute_adoptsAndRepairsWhenColdAndOrphanDrifted` — `findChildByIdentity` → present; `verify` → `PRESENT_DRIFTED`; assert `repairShape("orphan-id", expected)` called once, `ledger.record`; outcome `REPAIRED` (row 3).
6. `execute_propagatesAmbiguousMatchFailureOnColdPath` — `findChildByIdentity` throws `NotionApiException`; assert propagation (`isSameAs`), `verifyNoInteractions(ledger)` (row 4).
7. `execute_reconcilesWhenWarmAndMatching` — `RESOURCES_DB` resource present (`"existing-id"`); `verify` → `PRESENT_MATCHING`; assert **no** write, **no** `ledger.record`, **no** `findChildByIdentity` call; outcome `RECONCILED` (row 5).
8. `execute_repairsWhenWarmAndDrifted` — `verify` → `PRESENT_DRIFTED`; assert `repairShape("existing-id", expected)`, `ledger.record(workspaceId, RESOURCES_DB, "existing-id")`; outcome `REPAIRED` (row 6).
9. `execute_reAdoptsWhenWarmAndDeletedAndOrphanFound` — `verify` → `ABSENT`; `findChildByIdentity` → `Optional.of("orphan-id")`; assert **no** `createDatabase`, `ledger.record(workspaceId, RESOURCES_DB, "orphan-id")`; outcome `REPAIRED` (row 7).
10. `execute_reCreatesWhenWarmAndDeletedAndNoOrphanFound` — `verify` → `ABSENT`; `findChildByIdentity` → `Optional.empty()`; assert `createDatabase` called, `ledger.record`; outcome `REPAIRED` (row 8).
11. `execute_propagatesAmbiguousMatchFailureOnWarmAbsentPath` — `verify` → `ABSENT`; `findChildByIdentity` throws `NotionApiException`; assert propagation, `verifyNoInteractions(ledger)` (row 9).
12. `execute_propagatesNotionFailureFromVerifyWithoutWritingLedger` — `verify` throws `NotionApiException` directly (transport failure, not `ABSENT`); assert propagation, `verifyNoInteractions(ledger)` (FR-13).
13. `execute_propagatesNotionFailureFromCreateWithoutWritingLedger` — cold path, `findChildByIdentity` empty, `createDatabase` throws; assert propagation, `verifyNoInteractions(ledger)` (Notion-write-before-ledger-write ordering).
14. `execute_neverInvokesRelationRollupFormulaOrSampleOrPageMethods` — on a happy-path execution, `verify(notion, never())` for `ensureRelation`/`ensureRollup`/`ensureFormula`/`hasSampleRecords`/`insertSampleRecords`/`createRootPage`/`verifyPage`/`repairPage`/`findRootByIdentity` (spec §8 scope guard).
15. `execute_isNotAnnotatedTransactional` — reflection: `CreateResourcesDatabaseService.class.getMethod("execute", UUID.class).isAnnotationPresent(Transactional.class) == false`, and the class itself is not `@Transactional`.
16. `resourcesSpec_buildsTwoPropertiesTitleAndUrl` — direct unit test of the package-private static method: asserts title `"Resources"`, exactly 2 properties in order — `Title`(TITLE, `.options()` empty) / `URL`(URL, `.options()` empty). Compare each `PropertyDefinition` via `.isEqualTo(...)` the same way Tasks' `tasksSpec_buildsFourPropertiesWithStatusOptionsFromEnum` does.
17. `resourcesExpectedShape_matchesSpecProperties` — `resourcesExpectedShape().requiredProperties()` equals `resourcesSpec().properties()`; `resourcesExpectedShape().title()` equals `"Resources"`.

### 5.3 `application/usecase/resource/CreateResourcesDatabaseServiceIT.java` [NEW]

Mirror `CreateTasksDatabaseServiceIT` (`backend/src/test/java/com/lifeos/application/usecase/task/CreateTasksDatabaseServiceIT.java`) exactly: `@SpringBootTest` + `@Testcontainers` Postgres container, a `@TestConfiguration`-supplied `@Primary` `InMemoryDatabaseOnlyNotionPort` fake implementing only the four database methods realistically (create assigns a UUID string id, stores title + property-name set; verify/find/repair read/mutate the map) plus the four page methods delegating to a fixed pre-adopted Dashboard id, and every other port method throwing `UnsupportedOperationException`. If a shared fake already exists from the Tasks pass, reuse it as-is (it is generic over `ProvisionedResourceType`/`DatabaseSpec`); do not fork a Resources-specific copy. `@BeforeEach` clears the fake's static map.

4 tests, substituting `RESOURCES_DB`/`createResourcesDatabase`:

1. `execute_persistsResourcesDbLedgerRowOnFirstRun` — a fresh workspace with a pre-seeded `DASHBOARD` resource → `execute` → outcome `CREATED`; a direct repository read shows exactly one `RESOURCES_DB` `ProvisionedResource` row.
2. `execute_convergesToOneRowAcrossThreeReruns` — run `execute` three times in sequence → still exactly one `RESOURCES_DB` row after all three; second/third outcomes are `RECONCILED`; asserts the same `notionId` across all three reads. **This is the multi-run convergence case** — no separate class needed, it is this test method.
3. `execute_reachesRepairedOutcomeWhenFakeSimulatesExternalPropertyRemoval` — after the first run, mutate the fake's stored properties map directly (simulating an out-of-band Notion property deletion, e.g. remove `"URL"`) → second `execute` → `PRESENT_DRIFTED` → `REPAIRED`, ledger row's `notionId` unchanged.
4. `execute_throwsWhenPhaseAIncomplete` — a workspace with **no** `DASHBOARD` resource → `execute` throws `IllegalStateException`, and the repository shows **no** `RESOURCES_DB` row.

Class name ends in `IT` so Failsafe runs it under `./mvnw verify` (already configured). Zero real Notion calls; no `MockRestServiceServer` in this class.

### 5.4 No other new tests — explicit note

`NotionProvisioningAdapter`'s database slice (`createDatabase`/`verify`/`findChildByIdentity`/`repairShape`) remains **generic over `ProvisionedResourceType` + the typed `DatabaseSpec`/`ExpectedShape`/`PropertyDefinition`** and is already fully contract-tested end-to-end by the existing 13 `NotionProvisioningAdapterDatabaseTest` cases (shipped under the Projects pass). Passing a `RESOURCES_DB` type with a Resources `DatabaseSpec`/`ExpectedShape` exercises the same request-building/response-parsing paths already proven for `PROJECTS_DB`/`TASKS_DB`; only the **new `URL` type-config branch** needs its own coverage (§5.1's 2 new tests). No new `PropertyDefinition`/`DatabaseSpec`/`ExpectedShape` unit tests (unchanged, already covered by the Projects pass's `PropertyDefinitionTest`/`DatabaseSpecTest`/`ExpectedShapeTest`) — the "options only for SELECT" invariant is not touched by adding `URL` as a value the enum can hold.

---

## 6. "Do NOT build" — scope guard for the Implementer

- **Any `Resource ↔ Knowledge` relation**, or any relation/rollup/formula of any kind. `ensureRelation`/`ensureRollup`/`ensureFormula` are untouched, unchanged stubs. `Resource.knowledgeId` remains a UUID-only reference — do not add a Notion relation column for it (deferred to Phase C — Create Relations; spec §8).
- **Rows or sample data.** `hasSampleRecords`/`insertSampleRecords` untouched. This step provisions the container only — zero Notion database rows are written.
- **The other databases** (Projects, Tasks, Knowledge, Habits, Journal, People) and `GOALS_DB`/`REVIEWS_DB`. Only `RESOURCES_DB` is exercised. Do not generalize `resourcesSpec()`/`tasksSpec()`/`projectsSpec()` into a shared multi-database schema builder in this pass (YAGNI — each sibling step authors its own schema when it is built).
- **Any domain change.** No new field on `Resource`, no `ResourceRepository`, no JPA entity, no Flyway migration, no reconstitution factory. `Resource` already carries every field this schema needs.
- **Any port change beyond the additive `URL` enum constant + the one `propertyConfig` branch.** No change to `NotionProvisioningPort`'s method signatures, no change to `DatabaseSpec`/`ExpectedShape`/`PropertyDefinition`'s shape or invariants, no new port method, no reordering of existing `NotionPropertyType` constants, no `default` added to the `propertyConfig` switch (that would defeat the exhaustiveness compile-check — ADR-0013 §Decision).
- **Retrofitting `URL` onto any other schema.** `NotionPropertyType.URL` becomes a reusable capability, but this spec consumes it **only** for the Resources database (ADR-0013 §Consequences "Reusable capability") — do not add a `url` column to Projects/Tasks/etc. in this pass.
- **Type-level reconciliation in `verify`/`repair`.** Do not extend `verify` to compare Notion property *types* (only names) to try to detect a `url`↔`rich_text` retype — that is an accepted, explicitly out-of-scope consequence (ADR-0013 §Consequences; ADR-0008 unchanged).
- **`docs/productivity/*` population** or any other documentation content change.
- **A semantic Notion-Version range/comparison check, new config property, or profile change.** Nothing here needs one.

---

## 7. Traceability (FR/NFR → spec section)

| Req | Satisfied by (this spec) |
|---|---|
| FR-1 | Cold path `findChildByIdentity` empty → `createDatabase("Resources")` → `record` → `CREATED`; §3.3 row 1; test 5.2-3 |
| FR-2 | `resourcesSpec()` `Title` (TITLE ← `Resource.title`); §2, §3.2 |
| FR-3 | `resourcesSpec()` `URL` (URL ← `Resource.url`); no select/date; §2, §3.2; ADR-0013 |
| FR-4 | `NotionPropertyType` gains `URL`; §1.1; ADR-0013 |
| FR-5 | `propertyConfig` `case URL` → `{"type":"url","url":{}}` on create + add-missing repair; §1.2; test 5.1-1, 5.1-2; ADR-0013 |
| FR-6 | Cold `findChildByIdentity` (parent-scoped) → adopt (`RECONCILED`/`REPAIRED`); §3.2; ADR-0008; tests 5.2-4, 5.2-5 |
| FR-7 | Warm `verify`: `PRESENT_MATCHING`→`RECONCILED`, `PRESENT_DRIFTED`→`repairShape`→`REPAIRED`, `ABSENT`→adopt/recreate→`REPAIRED`; §3.2; tests 5.2-7..10 |
| FR-8 | `WorkspaceLedgerWriter.record(workspaceId, RESOURCES_DB, id)` on every create/adopt/repair — own tx (reused); §3.2 |
| FR-9 | `resource(DASHBOARD)` empty → `IllegalStateException` before any Notion call; §3.2; test 5.2-2 |
| FR-10 | `WorkspaceRepository.findById` empty → `IllegalStateException` before any Notion call; §3.2; test 5.2-1 |
| FR-11 | `>1` "Resources" child → `NotionApiException` → orchestrator `FAILED`; ADR-0008; tests 5.2-6, 5.2-11 |
| FR-12 | `CreateResourcesDatabaseUseCase.execute(UUID)` unchanged; already wired in `CreateWorkspaceService`; §3.2 |
| FR-13 | Unexpected exceptions propagate to `runStep` → `FAILED` with detail; no silent success; §3.2 error strategy; tests 5.2-12, 5.2-13 |
| §3 schema + domain backing | `resourcesSpec()`/`resourcesExpectedShape()` (§2, §3.2); `Resource` unchanged; ADR-0013 |
| NFR-1 | Strict per-path live verification + adoption-before-create + upsert `record`; §3.2; §6 IT test 5.3-2 (inherited ADR-0008) |
| NFR-2 | Token in `NotionClient` header only, never logged/in `detail`; §4 (reused) |
| NFR-3 | `repairShape` add-only, non-destructive (never null, never retype); ADR-0008; test 5.1-2 asserts no unrelated key sent |
| NFR-4 | Only existing `ProvisioningOutcome` values; stub `UnsupportedOperationException` removed only as real impl lands; §3.1/§3.2 |
| NFR-5 | `URL` added additively; TITLE/RICH_TEXT/SELECT/DATE branches + all existing callers unchanged; §1.1, §1.2 |
| NFR-6 | Mockito service tests (§5.2) + Testcontainers IT (§5.3) + new adapter contract test for the URL JSON shape (§5.1, AC-13) |

---

## 8. Implementation notes (file list for the Implementer)

- `backend/src/main/java/com/lifeos/application/port/NotionPropertyType.java` — add `URL` per §1.1 (one line).
- `backend/src/main/java/com/lifeos/infrastructure/adapter/notion/NotionProvisioningAdapter.java` — add `case URL` to `propertyConfig` per §1.2 (one branch, no other change).
- `backend/src/main/java/com/lifeos/application/usecase/resource/CreateResourcesDatabaseService.java` — replace stub with §3.2's full source; 3-arg constructor (`NotionProvisioningPort`, `WorkspaceRepository`, `WorkspaceLedgerWriter`); `resourcesSpec()`/`resourcesExpectedShape()` package-private statics; SLF4J logging; no `@Transactional`.
- `backend/src/test/java/com/lifeos/infrastructure/adapter/notion/NotionProvisioningAdapterDatabaseTest.java` — add the `URL_EXPECTED_SHAPE` fixture + 2 new tests per §5.1 (18 → 20 total).
- `backend/src/test/java/com/lifeos/application/usecase/resource/CreateResourcesDatabaseServiceTest.java` — fully rewritten (3-arg constructor), 17 tests (§5.2).
- `backend/src/test/java/com/lifeos/application/usecase/resource/CreateResourcesDatabaseServiceIT.java` — new `@SpringBootTest` + Testcontainers Postgres IT, 4 tests (§5.3); fake port's in-memory map cleared in `@BeforeEach`.

No changes to any other file. No changes to `WorkspaceController`, `WorkspaceCommands`, `ApiExceptionHandler`, any web/CLI DTO, `infrastructure.adapter.persistence`, `application.yml`, or any Flyway migration.

---

## 9. Findings / notes back to the Architect

None. The architecture (`02-architecture.md`) and ADR-0013 are complete and self-consistent for this step; no deviation was needed to produce this spec. ADR-0013 is accepted as written and requires no SME-level qualification beyond what §2/§6 already state (verify is name-only, so a `url`↔`rich_text` out-of-band retype cannot cause drift detection or repair — an accepted, documented consequence carried forward unchanged from ADR-0008).

---

## Implementation notes (Implementer changelog)

- `backend/src/main/java/com/lifeos/application/port/NotionPropertyType.java` — added `URL` constant (one line, additive).
- `backend/src/main/java/com/lifeos/infrastructure/adapter/notion/NotionProvisioningAdapter.java` — added `case URL -> Map.of("type", "url", "url", Map.of())` to the `propertyConfig` switch.
- `backend/src/main/java/com/lifeos/application/usecase/resource/CreateResourcesDatabaseService.java` — replaced the `UnsupportedOperationException` stub with the full 3-arg (`NotionProvisioningPort`, `WorkspaceRepository`, `WorkspaceLedgerWriter`) verify/create/adopt/repair implementation targeting `RESOURCES_DB`, and `resourcesSpec()`/`resourcesExpectedShape()` package-private statics (Title + URL, no SELECT).
- `backend/src/test/java/com/lifeos/infrastructure/adapter/notion/NotionProvisioningAdapterDatabaseTest.java` — added `URL_EXPECTED_SHAPE` fixture and 2 new tests (`createDatabase_postsUrlPropertyWithEmptyUrlConfig`, `repairShape_addsMissingUrlPropertyWithEmptyUrlConfig`); file now has 20 test methods.
- `backend/src/test/java/com/lifeos/application/usecase/resource/CreateResourcesDatabaseServiceTest.java` — fully rewritten with 3-arg constructor and 17 tests mirroring `CreateTasksDatabaseServiceTest`, substituting the two-property `RESOURCES_DB` schema.
- `backend/src/test/java/com/lifeos/application/usecase/resource/CreateResourcesDatabaseServiceIT.java` — new `@SpringBootTest` + Testcontainers Postgres IT with 4 tests, mirroring `CreateTasksDatabaseServiceIT` with an in-memory fake `NotionProvisioningPort` targeting `RESOURCES_DB`.

Verification: `./mvnw verify` (Podman env exported per `00-preflight.md`) → BUILD SUCCESS. Unit tier: `Tests run: 281, Failures: 0, Errors: 0, Skipped: 0`. Failsafe (`*IT`) tier: `Tests run: 29, Failures: 0, Errors: 0, Skipped: 0`, including `CreateResourcesDatabaseServiceIT` — `Tests run: 4, Failures: 0, Errors: 0, Skipped: 0`.
