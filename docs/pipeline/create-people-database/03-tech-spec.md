# 03 — Technical Specification: Create People Database (Phase B — final sibling database + bounded port/adapter extension)

Status: Ready for Implementer
Owner (SME stage): pipeline automation
Input: `docs/pipeline/create-people-database/02-architecture.md` (FINAL, no open questions) + `adr/ADR-0014-email-property-type.md` (Accepted) + reused by reference `../create-resources-database/adr/ADR-0013-url-property-type.md` (the exact precedent), `../create-projects-database/adr/ADR-0005,0007,0008`, `../create-tasks-database/adr/ADR-0009` (not restated) + existing code under `backend/src/main/java/com/lifeos/`.
Grounding skills applied: `spring-boot-conventions`, `spring-data-jpa` (N/A — no schema/migration change; no `PersonRepository`/table exists), `spring-security` (N/A — no new auth surface, token handling reused verbatim), `spring-testing`.

> This is a **pattern-application spec with one bounded, in-scope extension** — structurally identical to the Resources (`URL`) pass, substituted for `EMAIL`. The production delta is exactly three files. `CreatePeopleDatabaseService` is mirrored verbatim off the already-shipped `CreateResourcesDatabaseService` (`backend/src/main/java/com/lifeos/application/usecase/resource/CreateResourcesDatabaseService.java`) with `RESOURCES_DB → PEOPLE_DB`, title `"Resources"` → `"People"`, and a People-specific two-property schema (`Name` + `Email`, no `select`/`date`). This step **also** extends `NotionPropertyType` with a new `EMAIL` member and adds the matching `case EMAIL` branch to `NotionProvisioningAdapter.propertyConfig` — the identical shape of extension already done for `URL` (ADR-0013), now recorded in **ADR-0014**. This is the **seventh and final** Phase-B child database; once it lands, every database step referenced by `CreateWorkspaceService` is implemented. Reference pattern for structure/rigor: `docs/pipeline/create-resources-database/03-tech-spec.md` (this document mirrors its structure line-for-line, substituting `URL → EMAIL`, `Resources → People`, `Title → Name`).

---

## 1. Package layout (file-by-file change list)

```
com.lifeos
 ├─ domain.person/
 │    Person.java                                 (UNCHANGED — backend/src/main/java/com/lifeos/domain/person/Person.java;
 │                                                              name + email already present, l.13–14)
 │    Email.java                                  (UNCHANGED — backend/src/main/java/com/lifeos/domain/person/Email.java;
 │                                                              regex-validated VO, l.5–17 — row-write concern, not touched here)
 │
 ├─ application
 │   ├─ port/
 │   │    NotionPropertyType.java                 [MODIFIED] add EMAIL: {TITLE, RICH_TEXT, SELECT, DATE, URL} → {TITLE, RICH_TEXT, SELECT, DATE, URL, EMAIL}
 │   │    (DatabaseSpec, ExpectedShape,
 │   │     PropertyDefinition, NotionProvisioningPort,
 │   │     VerificationResult)                    (ALL UNCHANGED)
 │   └─ usecase.person/
 │        CreatePeopleDatabaseService.java         [MODIFIED] stub removed; 3-arg constructor (+WorkspaceRepository);
 │                                                             real verify/create/adopt/repair algorithm targeting PEOPLE_DB;
 │                                                             adds peopleSpec()/peopleExpectedShape() private-static helpers
 │        CreatePeopleDatabaseUseCase.java         (unchanged — ProvisioningStepResult execute(UUID))
 │
 └─ infrastructure.adapter.notion/
      NotionProvisioningAdapter.java              [MODIFIED] add `case EMAIL -> Map.of("type", "email", "email", Map.of())`
                                                              to the exhaustive propertyConfig switch (l.262–271)
      (NotionClient, NotionApiException,
       NotionProperties, dto/*)                   (ALL UNCHANGED)
```

### 1.1 `application/port/NotionPropertyType.java` [MODIFIED] — exact one-line change

Current (`backend/src/main/java/com/lifeos/application/port/NotionPropertyType.java`):
```java
package com.lifeos.application.port;

public enum NotionPropertyType { TITLE, RICH_TEXT, SELECT, DATE, URL }
```

New:
```java
package com.lifeos.application.port;

public enum NotionPropertyType { TITLE, RICH_TEXT, SELECT, DATE, URL, EMAIL }
```

Only the enum's constant list changes — one line, one new trailing constant. `TITLE`, `RICH_TEXT`, `SELECT`, `DATE`, `URL` keep their identity and declaration order; every existing caller (`CreateProjectsDatabaseService`, `CreateTasksDatabaseService`, `CreateResourcesDatabaseService`, and this step's own `peopleSpec()`) compiles and behaves unchanged (spec NFR-5; ADR-0014 §Decision).

### 1.2 `infrastructure/adapter/notion/NotionProvisioningAdapter.java` [MODIFIED] — exact branch

Current `propertyConfig` (`backend/src/main/java/com/lifeos/infrastructure/adapter/notion/NotionProvisioningAdapter.java` l.262–271):
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

New (add one `case`, no reordering, no other line touched):
```java
private static Map<String, Object> propertyConfig(PropertyDefinition definition) {
    return switch (definition.type()) {
        case TITLE -> Map.of("type", "title", "title", Map.of());
        case RICH_TEXT -> Map.of("type", "rich_text", "rich_text", Map.of());
        case DATE -> Map.of("type", "date", "date", Map.of());
        case URL -> Map.of("type", "url", "url", Map.of());
        case EMAIL -> Map.of("type", "email", "email", Map.of());
        case SELECT -> Map.of("type", "select", "select",
                Map.of("options", definition.options().stream().map(name -> Map.of("name", name)).toList()));
    };
}
```

- Emits exactly `{"type":"email","email":{}}` — no `options`, no extra keys (ADR-0014 §Decision; Notion [Property object](https://developers.notion.com/reference/property-object) reference: the `email` configuration is the empty object `"email": {}`, distinct from `rich_text`).
- **This is the only edit to `NotionProvisioningAdapter.java`.** No other method, import, or branch changes. The `URL` branch added by the Resources pass is untouched.
- **One helper, two call sites — confirmed applies to both `createDatabase` and `repairShape`.** `propertyConfig` is invoked from:
  - `createDatabase` (`NotionProvisioningAdapter.java` l.126–137, specifically l.129): `for (PropertyDefinition property : spec.properties()) { properties.put(property.name(), propertyConfig(property)); }` — every property in a `DatabaseSpec`, including an `EMAIL` one, gets `{"type":"email","email":{}}` at creation time.
  - `repairShape`'s add-missing loop (`NotionProvisioningAdapter.java` l.187–209, specifically l.202): `if (!dataSource.properties().containsKey(required.name())) { missing.put(required.name(), propertyConfig(required)); }` — a missing `Email` property is added with the identical config on repair.

  No second edit is needed for repair; both call sites share the one private static helper (ADR-0014 §Decision, spec FR-5).
- The `switch` has no `default` — it is exhaustive over the enum. Adding `EMAIL` to `NotionPropertyType` without adding this `case` is a **compile error** (*JLS §14.11.2*); the Implementer cannot forget the branch or ship the enum-only half of the change.

### 1.3 `application/usecase/person/CreatePeopleDatabaseService.java` [MODIFIED]

See §3 for the full intended source and line-level delta from `CreateResourcesDatabaseService`.

### 1.4 Confirmed NOT modified

- **`NotionClient`** and its DTOs (`dto/*`) — reused verbatim; the `email` property travels over the exact same `POST /v1/databases` / `PATCH /v1/data_sources/{id}` transport already used for `url`/`date`/every other property type. No new endpoint, no new header, no new transport concern.
- **`NotionProvisioningPort`** (`application.port`) — the interface's four database-slice method signatures (`createDatabase`, `verify`, `findChildByIdentity`, `repairShape`) are unchanged. People passes a `PEOPLE_DB` `ProvisionedResourceType` and its own `DatabaseSpec`/`ExpectedShape` instances, exactly as Projects/Tasks/Resources do today. **No new port method.**
- **`DatabaseSpec`, `ExpectedShape`, `PropertyDefinition`** (`application.port`) — record shapes and compact-constructor invariants unchanged. `PropertyDefinition`'s "`options` only valid for `SELECT`" invariant is untouched and correctly rejects an `EMAIL` property carrying options; `peopleSpec()` only ever builds `EMAIL` properties via the no-options `PropertyDefinition.of(name, type)` factory, so this invariant is never exercised adversarially by this step. **No new value type.**
- **`WorkspaceLedgerWriter`** (`application.usecase.workspace`) — `record(workspaceId, type, notionId)`, its own `@Transactional`, reused verbatim.
- **`domain/person/Person.java`, `domain/person/Email.java`** — `name` and `email` fields already exist (`Person.java` l.13–14); **no domain change**. `Person.create` already enforces non-blank `name`; `email` is nullable, and when present is a regex-validated `Email` VO (`Email.java` l.7,9–17). The `Email` VO's regex validation is a **row-write** concern, not exercised by this **schema-provisioning** step (§5.4 boundary note below; ADR-0014 §Consequences).
- **`domain.workspace.ProvisionedResourceType.PEOPLE_DB`** — already defined (`ProvisionedResourceType.java` l.5), no enum change.
- **`NotionProvisioningAdapter`'s other three database methods** (`verify`, `findChildByIdentity`, `repairShape`'s control flow itself — only the `propertyConfig` helper it calls changes) and all four page methods, `ensureRelation`/`ensureRollup`/`ensureFormula`, `hasSampleRecords`/`insertSampleRecords` — untouched. The `case URL` branch shipped by the Resources pass is untouched.

If any of the above proves insufficient during implementation, that is an **Architect-level finding** (`findings.yml`, `raised_by: spring-sme`, `suspected_layer: architecture`) — do not redesign silently (architecture §"Reused UNCHANGED" note, §9 findings 7).

**Ripple:** none beyond the two port/adapter files above and the one service file. No signature of any shared type changes arity or parameter types — `NotionPropertyType` only grows a constant, `propertyConfig` only grows a `case`. No other production or test file requires a *compile-forced* edit. (The **existing** `NotionProvisioningAdapterDatabaseTest` is extended with new test methods per §5.1 — those are additive, not forced by a signature change.)

No change to `domain.workspace`, `infrastructure.adapter.persistence`, `infrastructure.adapter.web`/`adapter.cli`, any Flyway migration, or `application.yml`. Package-by-feature preserved (`spring-boot-conventions`).

---

## 2. Exact schema (title + properties)

Grounded in the complete `Person` aggregate (`domain/person/Person.java` l.13–14); every field already exists — no domain change.

| §2 property | Field grounding | `NotionPropertyType` | Notion config |
|---|---|---|---|
| **Name** (db title property) | `Person.name` (`Person.java` l.13, non-blank via `Person.create` l.17–19) | `TITLE` | `{ "type": "title", "title": {} }` |
| **Email** | `Person.email` (`Person.java` l.14, an `Email` VO, **nullable**) | `EMAIL` **[new type]** | `{ "type": "email", "email": {} }` — ADR-0014 |

Database (page) title: `"People"` (fixed constant; not derived from the workspace name — identity is already scoped by the unique Dashboard parent, same rule as Projects/Tasks/Resources).

**Naming note:** the title *property* is named `"Name"` (matching `Person.name`), same convention as Projects (`Project.name` → `"Name"`); Tasks/Resources named it `"Title"` (matching `Task.title`/`Resource.title`) — each database names its title property after its own aggregate's field.

**Only two properties — no `SELECT`.** Like Resources, People has no status/enum field, so there is no enum-seeded option-labels step (ADR-0009's label concern does not arise here).

**Excluded from this schema** (architecture §5.4, spec §8): any relation/rollup/formula. `Person` has **no relation field** to another aggregate today — unlike Resources (which defers a `knowledgeId → Knowledge` link to Phase C), there is not even a deferred relation here. No row/sample data. `Person` has no `workspaceId`; parentage is expressed structurally (child of the Dashboard page), not as a column — same convention as every prior database step.

**Verify is name-only** (ADR-0008, inherited unchanged): a user renaming/adding columns in Notion never triggers repair; the `email` type is only ever *established* at creation/add-missing, never *reconciled*. If a user out-of-band retypes the `Email` column to `rich_text` (or vice versa), it is **not** detected as drift and **not** repaired — an accepted consequence, identical to Resources not healing a retyped `URL` column (ADR-0008 §Consequences; ADR-0014 §Consequences).

### `peopleSpec()` / `peopleExpectedShape()` construction

```java
static DatabaseSpec peopleSpec() {
    return new DatabaseSpec(TITLE, List.of(
            PropertyDefinition.of("Name",  NotionPropertyType.TITLE),   // ← Person.name
            PropertyDefinition.of("Email", NotionPropertyType.EMAIL))); // ← Person.email  (ADR-0014)
}

static ExpectedShape peopleExpectedShape() {
    return new ExpectedShape(TITLE, peopleSpec().properties());
}
```

Both properties use the no-options `PropertyDefinition.of(name, type)` factory (neither is `SELECT`), so `PropertyDefinition`'s "options only for `SELECT`" compact-constructor invariant (§1.4) is satisfied trivially and needs no special handling.

---

## 3. `application.usecase.person.CreatePeopleDatabaseService` [MODIFIED]

### 3.1 Current state (to be replaced)

```java
package com.lifeos.application.usecase.person;

import com.lifeos.application.dto.workspace.ProvisioningStepResult;
import com.lifeos.application.port.NotionProvisioningPort;
import com.lifeos.application.usecase.workspace.WorkspaceLedgerWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CreatePeopleDatabaseService implements CreatePeopleDatabaseUseCase {

    private final NotionProvisioningPort notion;
    private final WorkspaceLedgerWriter ledger;

    @Override
    public ProvisioningStepResult execute(UUID workspaceId) {
        throw new UnsupportedOperationException(
                "People database creation not yet implemented: requires the Notion adapter");
    }
}
```

(`backend/src/main/java/com/lifeos/application/usecase/person/CreatePeopleDatabaseService.java`, current 2-arg constructor + stub throw — NFR-4/"no silent no-op" is satisfied today only by the explicit throw; it is removed as this real implementation lands.)

### 3.2 Full intended source (verbatim mirror of `CreateResourcesDatabaseService`, `RESOURCES_DB → PEOPLE_DB`, `"Resources" → "People"`, `Title → Name`/`URL → Email`)

```java
package com.lifeos.application.usecase.person;

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
import static com.lifeos.domain.workspace.ProvisionedResourceType.PEOPLE_DB;

@Slf4j
@Service
@RequiredArgsConstructor
public class CreatePeopleDatabaseService implements CreatePeopleDatabaseUseCase {

    private static final String TITLE = "People";

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

        DatabaseSpec spec = peopleSpec();
        ExpectedShape expected = peopleExpectedShape();
        Optional<String> ledgerId = workspace.resource(PEOPLE_DB).map(ProvisionedResource::notionId);

        ProvisioningStepResult result = ledgerId.isPresent()
                ? executeWarmPath(workspaceId, ledgerId.get(), dashboardId, spec, expected)
                : executeColdPath(workspaceId, dashboardId, spec, expected);

        log.info("People database step for workspace {}: dashboardId={}, priorLedgerId={}, outcome={}",
                workspaceId, dashboardId, ledgerId.orElse("none"), result.outcome());
        return result;
    }

    private ProvisioningStepResult executeWarmPath(UUID workspaceId, String existingId, String dashboardId,
                                                     DatabaseSpec spec, ExpectedShape expected) {
        VerificationResult result = notion.verify(existingId, PEOPLE_DB, expected);
        log.info("People database verify for workspace {}: notionId={}, result={}", workspaceId, existingId, result);
        return switch (result) {
            case PRESENT_MATCHING -> new ProvisioningStepResult(PEOPLE_DB, ProvisioningOutcome.RECONCILED, null);
            case PRESENT_DRIFTED -> {
                notion.repairShape(existingId, expected);
                ledger.record(workspaceId, PEOPLE_DB, existingId);
                yield new ProvisioningStepResult(PEOPLE_DB, ProvisioningOutcome.REPAIRED, "database drifted; shape repaired");
            }
            case ABSENT -> {
                Optional<String> found = notion.findChildByIdentity(dashboardId, PEOPLE_DB, expected);
                if (found.isPresent()) {
                    ledger.record(workspaceId, PEOPLE_DB, found.get());
                    yield new ProvisioningStepResult(PEOPLE_DB, ProvisioningOutcome.REPAIRED, "ledger id was stale; re-adopted existing database");
                }
                String newId = notion.createDatabase(dashboardId, spec);
                ledger.record(workspaceId, PEOPLE_DB, newId);
                yield new ProvisioningStepResult(PEOPLE_DB, ProvisioningOutcome.REPAIRED, "ledger id was stale; database recreated");
            }
        };
    }

    private ProvisioningStepResult executeColdPath(UUID workspaceId, String dashboardId, DatabaseSpec spec, ExpectedShape expected) {
        Optional<String> found = notion.findChildByIdentity(dashboardId, PEOPLE_DB, expected);
        log.info("People database findChildByIdentity for workspace {}: found={}", workspaceId, found.isPresent());
        if (found.isEmpty()) {
            String newId = notion.createDatabase(dashboardId, spec);
            ledger.record(workspaceId, PEOPLE_DB, newId);
            return new ProvisioningStepResult(PEOPLE_DB, ProvisioningOutcome.CREATED, null);
        }

        String orphanId = found.get();
        VerificationResult orphanVerify = notion.verify(orphanId, PEOPLE_DB, expected);
        return switch (orphanVerify) {
            case PRESENT_MATCHING -> {
                ledger.record(workspaceId, PEOPLE_DB, orphanId);
                yield new ProvisioningStepResult(PEOPLE_DB, ProvisioningOutcome.RECONCILED, null);
            }
            case PRESENT_DRIFTED -> {
                notion.repairShape(orphanId, expected);
                ledger.record(workspaceId, PEOPLE_DB, orphanId);
                yield new ProvisioningStepResult(PEOPLE_DB, ProvisioningOutcome.REPAIRED, "adopted orphan database was drifted; shape repaired");
            }
            case ABSENT -> {
                String newId = notion.createDatabase(dashboardId, spec);
                ledger.record(workspaceId, PEOPLE_DB, newId);
                yield new ProvisioningStepResult(PEOPLE_DB, ProvisioningOutcome.CREATED, null);
            }
        };
    }

    static DatabaseSpec peopleSpec() {
        return new DatabaseSpec(TITLE, List.of(
                PropertyDefinition.of("Name", NotionPropertyType.TITLE),
                PropertyDefinition.of("Email", NotionPropertyType.EMAIL)));
    }

    static ExpectedShape peopleExpectedShape() {
        return new ExpectedShape(TITLE, peopleSpec().properties());
    }
}
```

**Line-level delta from `CreateResourcesDatabaseService.java` (for reference — the Implementer may diff against it directly):**
1. Package `com.lifeos.application.usecase.resource` → `com.lifeos.application.usecase.person`.
2. Static import `RESOURCES_DB` → `PEOPLE_DB`.
3. `TITLE = "Resources"` → `TITLE = "People"`.
4. Every `RESOURCES_DB` token (method bodies, log message prefixes "Resources database …" → "People database …") → `PEOPLE_DB`.
5. `resourcesSpec()`/`resourcesExpectedShape()` → `peopleSpec()`/`peopleExpectedShape()`.
6. In `peopleSpec()`: rename `PropertyDefinition.of("Title", TITLE)` → `PropertyDefinition.of("Name", TITLE)` (matching `Person.name`, not `Resource.title`), and `PropertyDefinition.of("URL", URL)` → `PropertyDefinition.of("Email", EMAIL)`. The list stays exactly two entries.
7. Constructor (3-arg: `NotionProvisioningPort`, `WorkspaceRepository`, `WorkspaceLedgerWriter`), `execute`, `executeWarmPath`, `executeColdPath` bodies are otherwise **byte-for-byte structurally identical** (same branching, same log statement shape, same outcome/detail strings) — only the `RESOURCES_DB`/`PEOPLE_DB` and `"Resources"`/`"People"` tokens differ.

### 3.3 Outcome decision table

Reused verbatim from Resources §3.3 / Tasks §3.3 / Projects §4.5 (architecture §4 "Outcome decision table"), substituting `PEOPLE_DB`. Not restated as a new table — the 9-row shape (`CREATED` only on first-time create with no prior ledger record; adoption is never `CREATED`; `REPAIRED` ⇔ a Notion write happened this run; `RECONCILED` ⇔ none; `>1` identity match ⇒ `FAILED` via propagated `NotionApiException`) applies identically; see §5 test plan for the per-row test mapping.

### 3.4 Transaction boundary — summary

| Class | Transaction | Notes |
|---|---|---|
| `CreatePeopleDatabaseService.execute` | **none** | Pure port orchestration; mirrors `CreateResourcesDatabaseService`/`CreateTasksDatabaseService`/`CreateProjectsDatabaseService` — several Notion HTTP calls would hold a DB connection across slow remote work if annotated. |
| `WorkspaceLedgerWriter.record` | `@Transactional` (existing, unchanged) | The **only** transactional write in this step. |
| `JpaWorkspaceRepository.findById` | `@Transactional(readOnly = true)` (existing, unchanged) | Read path for `workspaceRepository.findById`. |
| `NotionProvisioningAdapter.*` / `NotionClient.*` | none | Not a transactional resource; unchanged. |

No new `@Service`/`@Component`/`@Repository` bean.

---

## 4. Security

- **Token handling**: unchanged. `NotionClient` remains the only class reading the token; `CreatePeopleDatabaseService` never touches it. The `EMAIL` branch in `propertyConfig` is a pure in-process JSON-shape mapper — it makes no HTTP call and carries no secret.
- **Token-never-leaked (NFR-2)**: enforced structurally exactly as Resources/Tasks/Projects — `NotionApiException` messages built only from status + Notion's `code`/`message`, plus the reused `>1`-match message which interpolates only the match count and `expected.title()` (constant `"People"`, not a secret).
- **No REST/CLI authn change, no OAuth, no per-Person token, no new config property.**
- **PII note (informational, not a code change).** `Person.email` is personal data. This step provisions the **column** only — no email address ever traverses this code path (no rows are written; §6 "do NOT build"). No PII handling decision is required here; a future row-write feature is where `Email` VO validation and any PII/logging policy for actual addresses would apply, out of scope for this spec.

---

## 5. Test plan (write these first — TDD)

Narrowest-sufficient tier per class (`spring-testing`). Build/verify order: adapter contract test extension (pure JSON-shape, no dependencies) → service unit tests (mocked port) → optional wiring IT. No new domain unit tests (`Person`/`Email` unchanged, no domain change). No live Notion, no token, no network egress anywhere.

### 5.1 Adapter contract test — extend the existing `NotionProvisioningAdapterDatabaseTest`

File: `backend/src/test/java/com/lifeos/infrastructure/adapter/notion/NotionProvisioningAdapterDatabaseTest.java` [MODIFIED — additive only; do not remove or change any existing test method or the existing `EXPECTED_SHAPE`/`URL_EXPECTED_SHAPE`/`databaseJson` fixtures].

**Actual current baseline (read from the file as it stands today): 20 `@Test` methods.** (18 shipped under the Projects/Resources passes + the 2 `URL`-specific tests already added by the Resources step: `createDatabase_postsUrlPropertyWithEmptyUrlConfig`, `repairShape_addsMissingUrlPropertyWithEmptyUrlConfig`.) Adding 2 new `EMAIL`-specific tests brings the file to **22 total**.

Add a **third, EMAIL-specific fixture** alongside the existing `EXPECTED_SHAPE` and `URL_EXPECTED_SHAPE` (do not repurpose either — they must stay `PROJECTS_DB`/`RESOURCES_DB`-flavored for their own tests):

```java
private static final ExpectedShape EMAIL_EXPECTED_SHAPE = new ExpectedShape("People", List.of(
        PropertyDefinition.of("Name", NotionPropertyType.TITLE),
        PropertyDefinition.of("Email", NotionPropertyType.EMAIL)));
```

Add exactly two new `@Test` methods (mirroring the `MockRestServiceServer` idiom already used by `createDatabase_postsUrlPropertyWithEmptyUrlConfig`/`repairShape_addsMissingUrlPropertyWithEmptyUrlConfig`):

1. **`createDatabase_postsEmailPropertyWithEmptyEmailConfig`** — asserts `createDatabase`'s request JSON contains the `Email` property with `{"type":"email","email":{}}`.
   ```java
   @Test
   void createDatabase_postsEmailPropertyWithEmptyEmailConfig() {
       DatabaseSpec spec = new DatabaseSpec("People", EMAIL_EXPECTED_SHAPE.requiredProperties());

       server.expect(requestTo("https://api.notion.com/v1/databases"))
               .andExpect(method(HttpMethod.POST))
               .andExpect(jsonPath("$.title[0].text.content").value("People"))
               .andExpect(jsonPath("$.initial_data_source.properties.Name.type").value("title"))
               .andExpect(jsonPath("$.initial_data_source.properties.Email.type").value("email"))
               .andExpect(jsonPath("$.initial_data_source.properties.Email.email").isEmpty())
               .andRespond(withSuccess("{\"id\":\"new-db-id\",\"data_sources\":[{\"id\":\"ds-1\",\"name\":\"People\"}]}", MediaType.APPLICATION_JSON));

       String id = adapter.createDatabase(DASHBOARD_ID, spec);

       assertThat(id).isEqualTo("new-db-id");
       server.verify();
   }
   ```
   `jsonPath("$....email").isEmpty()` asserts the `email` value is the empty JSON object `{}` (no keys) — exactly `PropertyDefinition`'s "no options, no extra keys" contract (ADR-0014 §Decision).

2. **`repairShape_addsMissingEmailPropertyWithEmptyEmailConfig`** — asserts the repair add-missing path emits the identical config.
   ```java
   @Test
   void repairShape_addsMissingEmailPropertyWithEmptyEmailConfig() {
       server.expect(requestTo("https://api.notion.com/v1/databases/db-id"))
               .andExpect(method(HttpMethod.GET))
               .andRespond(withSuccess(databaseJson(false, false, "People"), MediaType.APPLICATION_JSON));
       server.expect(requestTo("https://api.notion.com/v1/data_sources/ds-1"))
               .andExpect(method(HttpMethod.GET))
               .andRespond(withSuccess("""
                       {"properties": {"Name": {"type":"title"}}}
                       """, MediaType.APPLICATION_JSON));
       server.expect(requestTo("https://api.notion.com/v1/data_sources/ds-1"))
               .andExpect(method(HttpMethod.PATCH))
               .andExpect(jsonPath("$.properties.Email.type").value("email"))
               .andExpect(jsonPath("$.properties.Email.email").isEmpty())
               .andExpect(jsonPath("$.properties.Name").doesNotExist())
               .andRespond(withSuccess("{\"properties\":{}}", MediaType.APPLICATION_JSON));

       adapter.repairShape("db-id", EMAIL_EXPECTED_SHAPE);

       server.verify();
   }
   ```
   Note this reuses the shared `databaseJson(archived, inTrash, title)` helper already in the file with `title="People"` — no new fixture helper needed. `jsonPath("$.properties.Name").doesNotExist()` reconfirms the add-only/non-destructive repair contract (ADR-0008, NFR-3) for this new property type too.

**Exact expectations, stated:** both new tests assert the emitted JSON for an `EMAIL`-typed `PropertyDefinition` is exactly `{"type":"email","email":{}}` — `type` equals the string `"email"`, and the nested `email` object has **zero** keys (no `options`, no extra keys) — on **both** `createDatabase` and `repairShape`'s add-missing `PATCH`. This is the adapter-level proof of ADR-0014 §Decision and spec AC-13/FR-5.

File total after this change: **22 test methods (20 existing + 2 new)**.

### 5.2 `application/usecase/person/CreatePeopleDatabaseServiceTest.java` [NEW — full rewrite of the existing stub test]

Delete the existing single test (`execute_throwsUnsupportedOperationExceptionUntilNotionAdapterExists`, 2-arg constructor) — the constructor becomes 3-arg, a breaking change. Mirror `CreateResourcesDatabaseServiceTest` (`backend/src/test/java/com/lifeos/application/usecase/resource/CreateResourcesDatabaseServiceTest.java`) exactly, substituting `PEOPLE_DB`/`peopleSpec`/`peopleExpectedShape` and the two-property People shape (`Name`:TITLE, `Email`:EMAIL — no `TaskStatus`/enum-options assertions apply, no `URL`). Mocks: `NotionProvisioningPort notion`, `WorkspaceRepository workspaceRepository`, `WorkspaceLedgerWriter ledger`. Fixture helper: `Workspace.reconstitute(id, UUID.randomUUID(), "Personal", List.of()).record(DASHBOARD, "dash-id")` (and `.record(PEOPLE_DB, notionId)` for warm-path fixtures).

**Expected count: 17 tests** (same count and shape as Resources §5.2 / Tasks §5.1) — one per outcome-table row (9) + preconditions (2) + ambiguous-match propagation (2, cold and warm-ABSENT) + Notion-failure propagation without ledger write (2) + never-invokes-unrelated-port-methods (1) + not-`@Transactional` reflection (1) + `peopleSpec()`/`peopleExpectedShape()` direct assertions (2). Full method list:

1. `execute_throwsWhenWorkspaceNotFound` — `workspaceRepository.findById` empty → `IllegalStateException("Workspace not found: " + id)`; `verifyNoInteractions(notion)`, `verifyNoInteractions(ledger)` (FR-10).
2. `execute_throwsWhenNoDashboardLedgerEntry` — workspace present, no `DASHBOARD` resource → `IllegalStateException("No confirmed Dashboard for workspace " + id)`; `verifyNoInteractions(notion)`, `verifyNoInteractions(ledger)` (FR-9).
3. `execute_createsWhenColdAndNoOrphan` — no `PEOPLE_DB` resource; `findChildByIdentity(dashId, PEOPLE_DB, expected)` → `Optional.empty()`; `createDatabase(dashId, spec)` → `"new-db-id"`; capture the `DatabaseSpec` passed to `createDatabase` and assert `properties()` has exactly 2 entries in order `Name`(TITLE)/`Email`(EMAIL), and neither carries `options`; `ledger.record(workspaceId, PEOPLE_DB, "new-db-id")` called once; outcome `CREATED`, `detail` null (row 1).
4. `execute_adoptsWhenColdAndOrphanMatches` — `findChildByIdentity` → `Optional.of("orphan-id")`; `verify("orphan-id", PEOPLE_DB, expected)` → `PRESENT_MATCHING`; assert **no** `createDatabase`/`repairShape`; `ledger.record(workspaceId, PEOPLE_DB, "orphan-id")`; outcome `RECONCILED` (row 2).
5. `execute_adoptsAndRepairsWhenColdAndOrphanDrifted` — `findChildByIdentity` → present; `verify` → `PRESENT_DRIFTED`; assert `repairShape("orphan-id", expected)` called once, `ledger.record`; outcome `REPAIRED` (row 3).
6. `execute_propagatesAmbiguousMatchFailureOnColdPath` — `findChildByIdentity` throws `NotionApiException`; assert propagation (`isSameAs`), `verifyNoInteractions(ledger)` (row 4).
7. `execute_reconcilesWhenWarmAndMatching` — `PEOPLE_DB` resource present (`"existing-id"`); `verify` → `PRESENT_MATCHING`; assert **no** write, **no** `ledger.record`, **no** `findChildByIdentity` call; outcome `RECONCILED` (row 5).
8. `execute_repairsWhenWarmAndDrifted` — `verify` → `PRESENT_DRIFTED`; assert `repairShape("existing-id", expected)`, `ledger.record(workspaceId, PEOPLE_DB, "existing-id")`; outcome `REPAIRED` (row 6).
9. `execute_reAdoptsWhenWarmAndDeletedAndOrphanFound` — `verify` → `ABSENT`; `findChildByIdentity` → `Optional.of("orphan-id")`; assert **no** `createDatabase`, `ledger.record(workspaceId, PEOPLE_DB, "orphan-id")`; outcome `REPAIRED` (row 7).
10. `execute_reCreatesWhenWarmAndDeletedAndNoOrphanFound` — `verify` → `ABSENT`; `findChildByIdentity` → `Optional.empty()`; assert `createDatabase` called, `ledger.record`; outcome `REPAIRED` (row 8).
11. `execute_propagatesAmbiguousMatchFailureOnWarmAbsentPath` — `verify` → `ABSENT`; `findChildByIdentity` throws `NotionApiException`; assert propagation, `verifyNoInteractions(ledger)` (row 9).
12. `execute_propagatesNotionFailureFromVerifyWithoutWritingLedger` — `verify` throws `NotionApiException` directly (transport failure, not `ABSENT`); assert propagation, `verifyNoInteractions(ledger)` (FR-13).
13. `execute_propagatesNotionFailureFromCreateWithoutWritingLedger` — cold path, `findChildByIdentity` empty, `createDatabase` throws; assert propagation, `verifyNoInteractions(ledger)` (Notion-write-before-ledger-write ordering).
14. `execute_neverInvokesRelationRollupFormulaOrSampleOrPageMethods` — on a happy-path execution, `verify(notion, never())` for `ensureRelation`/`ensureRollup`/`ensureFormula`/`hasSampleRecords`/`insertSampleRecords`/`createRootPage`/`verifyPage`/`repairPage`/`findRootByIdentity` (spec §8 scope guard).
15. `execute_isNotAnnotatedTransactional` — reflection: `CreatePeopleDatabaseService.class.getMethod("execute", UUID.class).isAnnotationPresent(Transactional.class) == false`, and the class itself is not `@Transactional`.
16. `peopleSpec_buildsTwoPropertiesNameAndEmail` — direct unit test of the package-private static method: asserts title `"People"`, exactly 2 properties in order — `Name`(TITLE, `.options()` empty) / `Email`(EMAIL, `.options()` empty). Compare each `PropertyDefinition` via `.isEqualTo(...)` the same way Resources' `resourcesSpec_buildsTwoPropertiesTitleAndUrl` does.
17. `peopleExpectedShape_matchesSpecProperties` — `peopleExpectedShape().requiredProperties()` equals `peopleSpec().properties()`; `peopleExpectedShape().title()` equals `"People"`.

### 5.3 `application/usecase/person/CreatePeopleDatabaseServiceIT.java` [NEW]

Mirror `CreateResourcesDatabaseServiceIT` (`backend/src/test/java/com/lifeos/application/usecase/resource/CreateResourcesDatabaseServiceIT.java`) exactly: `@SpringBootTest` + `@Testcontainers` Postgres container, a `@TestConfiguration`-supplied `@Primary` in-memory fake `NotionProvisioningPort` implementing only the four database methods realistically (create assigns a UUID string id, stores title + property-name set; verify/find/repair read/mutate the map) plus the four page methods delegating to a fixed pre-adopted Dashboard id, and every other port method throwing `UnsupportedOperationException`. If a shared fake already exists from the Resources/Tasks pass, reuse it as-is (it is generic over `ProvisionedResourceType`/`DatabaseSpec`); do not fork a People-specific copy. `@BeforeEach` clears the fake's static map.

4 tests, substituting `PEOPLE_DB`/`createPeopleDatabase`:

1. `execute_persistsPeopleDbLedgerRowOnFirstRun` — a fresh workspace with a pre-seeded `DASHBOARD` resource → `execute` → outcome `CREATED`; a direct repository read shows exactly one `PEOPLE_DB` `ProvisionedResource` row.
2. `execute_convergesToOneRowAcrossThreeReruns` — run `execute` three times in sequence → still exactly one `PEOPLE_DB` row after all three; second/third outcomes are `RECONCILED`; asserts the same `notionId` across all three reads. **This is the multi-run convergence case** — no separate class needed, it is this test method.
3. `execute_reachesRepairedOutcomeWhenFakeSimulatesExternalPropertyRemoval` — after the first run, mutate the fake's stored properties map directly (simulating an out-of-band Notion property deletion, e.g. remove `"Email"`) → second `execute` → `PRESENT_DRIFTED` → `REPAIRED`, ledger row's `notionId` unchanged.
4. `execute_throwsWhenPhaseAIncomplete` — a workspace with **no** `DASHBOARD` resource → `execute` throws `IllegalStateException`, and the repository shows **no** `PEOPLE_DB` row.

Class name ends in `IT` so Failsafe runs it under `./mvnw verify` (already configured). Zero real Notion calls; no `MockRestServiceServer` in this class.

### 5.4 No other new tests — explicit note

`NotionProvisioningAdapter`'s database slice (`createDatabase`/`verify`/`findChildByIdentity`/`repairShape`) remains **generic over `ProvisionedResourceType` + the typed `DatabaseSpec`/`ExpectedShape`/`PropertyDefinition`** and is already fully contract-tested end-to-end by the existing 20 `NotionProvisioningAdapterDatabaseTest` cases (shipped under the Projects/Resources passes). Passing a `PEOPLE_DB` type with a People `DatabaseSpec`/`ExpectedShape` exercises the same request-building/response-parsing paths already proven for `PROJECTS_DB`/`TASKS_DB`/`RESOURCES_DB`; only the **new `EMAIL` type-config branch** needs its own coverage (§5.1's 2 new tests). No new `PropertyDefinition`/`DatabaseSpec`/`ExpectedShape` unit tests (unchanged, already covered by the Projects pass's `PropertyDefinitionTest`/`DatabaseSpecTest`/`ExpectedShapeTest`) — the "options only for SELECT" invariant is not touched by adding `EMAIL` as a value the enum can hold.

---

## 6. "Do NOT build" — scope guard for the Implementer

- **Any relation/rollup/formula of any kind.** `ensureRelation`/`ensureRollup`/`ensureFormula` are untouched, unchanged stubs. `Person` has no relation field to another aggregate today — do not add one to satisfy this step.
- **Rows or sample data.** `hasSampleRecords`/`insertSampleRecords` untouched. This step provisions the container only — zero Notion database rows are written. No email address ever traverses this code path.
- **The other databases** (Projects, Tasks, Knowledge, Habits, Journal, Resources) and `GOALS_DB`/`REVIEWS_DB`. Only `PEOPLE_DB` is exercised. Do not generalize `peopleSpec()`/`resourcesSpec()`/`tasksSpec()`/`projectsSpec()` into a shared multi-database schema builder in this pass (YAGNI — each sibling step authors its own schema when it is built).
- **Any domain change.** No new field on `Person`, no `PersonRepository`, no JPA entity, no Flyway migration, no reconstitution factory. `Person` already carries every field this schema needs. Do **not** touch `Email.java`'s regex or add any new validation to it.
- **`Email`-VO row validation as part of this step.** The `Email` VO's regex (`Email.java` l.7,14) is a **future row-write concern** governing `Person` record writes into this database — it is not exercised by, and not in scope for, the schema-provisioning step (spec NFR-7; ADR-0014 §Consequences).
- **Any port change beyond the additive `EMAIL` enum constant + the one `propertyConfig` branch.** No change to `NotionProvisioningPort`'s method signatures, no change to `DatabaseSpec`/`ExpectedShape`/`PropertyDefinition`'s shape or invariants, no new port method, no reordering of existing `NotionPropertyType` constants, no `default` added to the `propertyConfig` switch (that would defeat the exhaustiveness compile-check — ADR-0014 §Decision).
- **Retrofitting `EMAIL` onto any other schema.** `NotionPropertyType.EMAIL` becomes a reusable capability, but this spec consumes it **only** for the People database (ADR-0014 §Consequences "Reusable capability") — do not add an `email` column to Projects/Tasks/Resources/etc. in this pass.
- **Type-level reconciliation in `verify`/`repair`.** Do not extend `verify` to compare Notion property *types* (only names) to try to detect an `email`↔`rich_text` retype — that is an accepted, explicitly out-of-scope consequence (ADR-0014 §Consequences; ADR-0008 unchanged).
- **`docs/productivity/*` population** or any other documentation content change.
- **A semantic Notion-Version range/comparison check, new config property, or profile change.** Nothing here needs one.
- **Any change to `CreateWorkspaceService`'s wiring/ordering.** `CreatePeopleDatabaseUseCase.execute(UUID)` is already invoked from `CreateWorkspaceService`; do not touch step ordering or the `phaseBOk` aggregation logic.

---

## 7. Traceability (FR/NFR → spec section)

| Req | Satisfied by (this spec) |
|---|---|
| FR-1 | Cold path `findChildByIdentity` empty → `createDatabase("People")` → `record` → `CREATED`; §3.3 row 1; test 5.2-3 |
| FR-2 | `peopleSpec()` `Name` (TITLE ← `Person.name`); §2, §3.2 |
| FR-3 | `peopleSpec()` `Email` (EMAIL ← `Person.email`); no select/date/url; §2, §3.2; ADR-0014 |
| FR-4 | `NotionPropertyType` gains `EMAIL`; §1.1; ADR-0014 |
| FR-5 | `propertyConfig` `case EMAIL` → `{"type":"email","email":{}}` on create + add-missing repair; §1.2; test 5.1-1, 5.1-2; ADR-0014 |
| FR-6 | Cold `findChildByIdentity` (parent-scoped) → adopt (`RECONCILED`/`REPAIRED`); §3.2; ADR-0008; tests 5.2-4, 5.2-5 |
| FR-7 | Warm `verify`: `PRESENT_MATCHING`→`RECONCILED`, `PRESENT_DRIFTED`→`repairShape`→`REPAIRED`, `ABSENT`→adopt/recreate→`REPAIRED`; §3.2; tests 5.2-7..10 |
| FR-8 | `WorkspaceLedgerWriter.record(workspaceId, PEOPLE_DB, id)` on every create/adopt/repair — own tx (reused); §3.2 |
| FR-9 | `resource(DASHBOARD)` empty → `IllegalStateException` before any Notion call; §3.2; test 5.2-2 |
| FR-10 | `WorkspaceRepository.findById` empty → `IllegalStateException` before any Notion call; §3.2; test 5.2-1 |
| FR-11 | `>1` "People" child → `NotionApiException` → orchestrator `FAILED`; ADR-0008; tests 5.2-6, 5.2-11 |
| FR-12 | `CreatePeopleDatabaseUseCase.execute(UUID)` unchanged; already wired in `CreateWorkspaceService`; §3.2 |
| FR-13 | Unexpected exceptions propagate to `runStep` → `FAILED` with detail; no silent success; §3.2 error strategy; tests 5.2-12, 5.2-13 |
| §3 schema + domain backing | `peopleSpec()`/`peopleExpectedShape()` (§2, §3.2); `Person`/`Email` unchanged; ADR-0014 |
| AC-13 | `propertyConfig(EMAIL)` emits exactly `{"type":"email","email":{}}` — no options, no extra keys; tests 5.1-1, 5.1-2 |
| AC-14 | Seventh and final Phase-B database step lands; `phaseBOk` can evaluate true end-to-end (informational, no code change here) |
| NFR-1 | Strict per-path live verification + adoption-before-create + upsert `record`; §3.2; §6 IT test 5.3-2 (inherited ADR-0008) |
| NFR-2 | Token in `NotionClient` header only, never logged/in `detail`; §4 (reused) |
| NFR-3 | `repairShape` add-only, non-destructive (never null, never retype); ADR-0008; test 5.1-2 asserts no unrelated key sent |
| NFR-4 | Only existing `ProvisioningOutcome` values; stub `UnsupportedOperationException` removed only as real impl lands; §3.1/§3.2 |
| NFR-5 | `EMAIL` added additively; TITLE/RICH_TEXT/SELECT/DATE/URL branches + all existing callers unchanged; §1.1, §1.2 |
| NFR-6 | Mockito service tests (§5.2) + Testcontainers IT (§5.3) + new adapter contract test for the EMAIL JSON shape (§5.1, AC-13) |
| NFR-7 | Schema-only step; `Email` VO regex validation is a future row-write concern, out of scope; §2, §6; ADR-0014 §Consequences |

---

## 8. Implementation notes (file list for the Implementer)

- `backend/src/main/java/com/lifeos/application/port/NotionPropertyType.java` — add `EMAIL` per §1.1 (one line).
- `backend/src/main/java/com/lifeos/infrastructure/adapter/notion/NotionProvisioningAdapter.java` — add `case EMAIL` to `propertyConfig` per §1.2 (one branch, no other change).
- `backend/src/main/java/com/lifeos/application/usecase/person/CreatePeopleDatabaseService.java` — replace stub with §3.2's full source; 3-arg constructor (`NotionProvisioningPort`, `WorkspaceRepository`, `WorkspaceLedgerWriter`); `peopleSpec()`/`peopleExpectedShape()` package-private statics; SLF4J logging; no `@Transactional`.
- `backend/src/test/java/com/lifeos/infrastructure/adapter/notion/NotionProvisioningAdapterDatabaseTest.java` — add the `EMAIL_EXPECTED_SHAPE` fixture + 2 new tests per §5.1 (20 → 22 total).
- `backend/src/test/java/com/lifeos/application/usecase/person/CreatePeopleDatabaseServiceTest.java` — fully rewritten (3-arg constructor), 17 tests (§5.2).
- `backend/src/test/java/com/lifeos/application/usecase/person/CreatePeopleDatabaseServiceIT.java` — new `@SpringBootTest` + Testcontainers Postgres IT, 4 tests (§5.3); fake port's in-memory map cleared in `@BeforeEach`.

No changes to any other file. No changes to `WorkspaceController`, `WorkspaceCommands`, `ApiExceptionHandler`, any web/CLI DTO, `infrastructure.adapter.persistence`, `application.yml`, or any Flyway migration.

---

## 9. Findings / notes back to the Architect

None. The architecture (`02-architecture.md`) and ADR-0014 are complete and self-consistent for this step; no deviation was needed to produce this spec. ADR-0014 is accepted as written and requires no SME-level qualification beyond what §2/§6 already state (verify is name-only, so an `email`↔`rich_text` out-of-band retype cannot cause drift detection or repair — an accepted, documented consequence carried forward unchanged from ADR-0008, identical in shape to ADR-0013's `url` consequence).

Note for the pipeline orchestrator: with this step, all seven Phase-B database steps referenced by `CreateWorkspaceService` are implemented (spec AC-14, architecture §"Scope"). No further Phase-B database sibling steps remain to be specified.
