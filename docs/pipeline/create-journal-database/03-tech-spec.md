# 03 — Technical Specification: Create Journal Database (Phase B — fifth child database)

Status: Ready for Implementer
Owner (SME stage): pipeline automation
Input: `docs/pipeline/create-journal-database/02-architecture.md` (FINAL, OQ-A resolved by stakeholder decision — option c) + `adr/ADR-0012-journal-title-property-and-date-mapping.md` (Accepted) + reused by reference `../create-projects-database/adr/ADR-0005-notion-database-datasource-model.md`, `../create-projects-database/adr/ADR-0007-typed-database-schema-value-type.md`, `../create-projects-database/adr/ADR-0008-database-identity-verification-nondestructive-repair.md`, `../create-tasks-database/adr/ADR-0009-taskstatus-select-option-labels.md` (not restated) + existing code under `backend/src/main/java/com/lifeos/`.
Grounding skills applied: `spring-boot-conventions`, `spring-data-jpa` (N/A — no schema/migration change; `JournalEntry` change is in-memory-only, no `JournalEntryRepository`/table exists), `spring-security` (N/A — no new auth surface, token handling reused verbatim), `spring-testing`.

> This is a **lean, pattern-application spec** with **one bounded in-scope domain change**. The production delta is exactly two files: `CreateJournalDatabaseService` (mirrored verbatim off the shipped `CreateTasksDatabaseService`, `backend/src/main/java/com/lifeos/application/usecase/task/CreateTasksDatabaseService.java`, with `TASKS_DB → JOURNAL_DB`, title marker `"Journal"`, and a three-property Title/Content/Date schema — no `select`, no domain-enum dependency), and `JournalEntry` (add a nullable `title` field per the stakeholder's OQ-A decision, ADR-0012, mirroring the treatment `docs/pipeline/create-projects-database/03-tech-spec.md` §2 gave `Project.status`/`dueDate`). Reference pattern for structure/rigor: `docs/pipeline/create-tasks-database/03-tech-spec.md`. **No port, no adapter, no `NotionClient` change — confirmed by direct code inspection in §1.1.**

---

## 1. Package layout (file-by-file change list)

```
com.lifeos
 ├─ domain.journal/
 │    JournalEntry.java                          [MODIFIED] +String title (nullable, placed after id);
 │                                                           create(...) gains a LEADING title parameter (unvalidated);
 │                                                           content non-blank / workspaceId non-null invariants unchanged;
 │                                                           @Value immutability + UUID references preserved
 │
 ├─ application
 │   ├─ port/                                     (ALL UNCHANGED — NotionProvisioningPort, DatabaseSpec, ExpectedShape,
 │   │                                              PropertyDefinition, NotionPropertyType, VerificationResult)
 │   └─ usecase.journal/
 │        CreateJournalDatabaseService.java        [MODIFIED] stub removed; 3-arg constructor (+WorkspaceRepository);
 │                                                            real verify/create/adopt/repair algorithm targeting JOURNAL_DB;
 │                                                            adds journalSpec()/journalExpectedShape() private-static helpers
 │        CreateJournalDatabaseUseCase.java         (unchanged — ProvisioningStepResult execute(UUID))
 │
 └─ infrastructure.adapter.notion/                 (ALL UNCHANGED — NotionProvisioningAdapter, NotionClient,
                                                      NotionApiException, NotionProperties, dto/*)
```

### 1.1 Confirmed NOT modified — including the DATE-type risk check

Per the architecture's "Reused UNCHANGED" table and §8 findings, plus direct SME-stage verification of the two files the orchestration prompt flagged as the key risk:

- **`application.port.NotionPropertyType`** (`backend/src/main/java/com/lifeos/application/port/NotionPropertyType.java`) — **`DATE` already exists** as an enum constant: `public enum NotionPropertyType { TITLE, RICH_TEXT, SELECT, DATE }`. Confirmed by direct read. No enum change needed.
- **`infrastructure.adapter.notion.NotionProvisioningAdapter#propertyConfig(PropertyDefinition)`** (l.262–267) — **already emits a valid Notion `date` property config**: `case DATE -> Map.of("type", "date", "date", Map.of());`, structurally identical in shape to the `TITLE`/`RICH_TEXT` branches already exercised by Projects/Tasks. Confirmed by direct read. This method is invoked generically from both `createDatabase` (l.129, building the initial-properties map) and `repairShape`'s missing-property computation (l.202) — Journal's `Date` property flows through the exact same generic code path already exercised for every prior `TITLE`/`RICH_TEXT`/`SELECT` property, with zero Journal-specific branching required.
- **Conclusion: Journal is the first feature to *use* `DATE` at runtime, but it is not the first to *support* it — `DATE` was added to the type and the adapter's `propertyConfig` switch during the Projects pass (ADR-0007) for `Task.dueDate`/`Project.dueDate`, even though those features mapped it from a `LocalDate`, not `JournalEntry.timestamp`'s `LocalDateTime`. The Notion `date` property value accepts an ISO 8601 date "with an optional time" ([Notion API — Page property values, Date](https://developers.notion.com/reference/page-property-values)), so no adapter change is needed for a `LocalDateTime`-backed field either — this schema-only step never serializes a *value* (FR-15; ADR-0012), only the property's *type* in the schema, which is identical regardless of the backing Java type.** No additional in-scope adapter item. No new adapter contract tests (§5.3).
- `application.port.NotionProvisioningPort` and every record/enum in `application.port` (`DatabaseSpec`, `ExpectedShape`, `PropertyDefinition`, `VerificationResult`) — already generic; Journal passes a `JOURNAL_DB` `ProvisionedResourceType` and its own `DatabaseSpec`/`ExpectedShape` instances, same as Projects/Tasks do today.
- `infrastructure.adapter.notion.NotionProvisioningAdapter` — the four database methods (`createDatabase`/`verify`/`findChildByIdentity`/`repairShape`) are already implemented and generic over `ProvisionedResourceType` + the typed schema value types; zero code changes needed for Journal to use them.
- `infrastructure.adapter.notion.NotionClient` and its DTOs — reused verbatim, no new endpoint, no new transport concern.
- `application.usecase.workspace.WorkspaceLedgerWriter` — reused verbatim (`record(workspaceId, type, notionId)`, its own `@Transactional`).
- `domain.workspace.ProvisionedResourceType.JOURNAL_DB` — already defined (`ProvisionedResourceType.java` l.5: `PROJECTS_DB, TASKS_DB, KNOWLEDGE_DB, HABITS_DB, JOURNAL_DB, RESOURCES_DB, PEOPLE_DB, …`). No enum change.

If any of the above proves insufficient during implementation, that is an **Architect-level finding** (`findings.yml`, `raised_by: spring-sme`, `suspected_layer: architecture`) — do not redesign silently.

### 1.2 Caller ripple for `JournalEntry.create(...)`

Searched (`grep -rn "JournalEntry.create(\|JournalEntry.builder(" backend/src/main backend/src/test`):

- The **only** existing reference is the factory's own `JournalEntry.builder()` chain inside `create(...)` (`JournalEntry.java` l.28) — updated in §2 to add `.title(title)`.
- **No other production call site** exists. No `JournalEntry` persistence adapter, mapper, or use case constructs it today.
- **No existing test** references `JournalEntry` (`backend/src/test/java/com/lifeos/domain/journal/` does not exist yet). `JournalEntryTest` (§5.1) is new.
- **Ripple: none beyond the file itself.** Unlike the Projects pass (which changed `findChildByIdentity`'s arity and forced compile-only edits to two Dashboard test files), the Journal pass makes **zero** signature changes to any shared production type outside `domain.journal`.

No change to `domain.workspace`, `infrastructure.adapter.persistence`, `infrastructure.adapter.web`/`adapter.cli`, any Flyway migration, or `application.yml`. Package-by-feature preserved (`spring-boot-conventions`).

---

## 2. Domain change — `domain/journal/JournalEntry.java` [MODIFIED] (OQ-A, stakeholder option c, ADR-0012)

### 2.1 Current state

```java
package com.lifeos.domain.journal;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;
import java.util.UUID;

@Value
@Builder
public class JournalEntry {
    UUID id;
    String content;
    LocalDateTime timestamp;
    UUID workspaceId;
    UUID personId;

    public static JournalEntry create(String content,
                                      LocalDateTime timestamp,
                                      UUID workspaceId,
                                      UUID personId) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("JournalEntry content must not be null or blank");
        }
        if (workspaceId == null) {
            throw new IllegalArgumentException("JournalEntry workspaceId must not be null");
        }
        return JournalEntry.builder()
                .id(UUID.randomUUID())
                .content(content)
                .timestamp(timestamp != null ? timestamp : LocalDateTime.now())
                .workspaceId(workspaceId)
                .personId(personId)
                .build();
    }
}
```

(`backend/src/main/java/com/lifeos/domain/journal/JournalEntry.java`, current shape — no `title` field, 4-arg `create`.)

### 2.2 Full intended source

```java
package com.lifeos.domain.journal;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;
import java.util.UUID;

@Value
@Builder
public class JournalEntry {
    UUID id;
    String title;          // NEW — nullable optional headline (ADR-0012); may be absent for a free-form entry
    String content;
    LocalDateTime timestamp;
    UUID workspaceId;
    UUID personId;

    public static JournalEntry create(String title,                // NEW — leading param, the entry's headline
                                      String content,
                                      LocalDateTime timestamp,
                                      UUID workspaceId,
                                      UUID personId) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("JournalEntry content must not be null or blank");
        }
        if (workspaceId == null) {
            throw new IllegalArgumentException("JournalEntry workspaceId must not be null");
        }
        return JournalEntry.builder()
                .id(UUID.randomUUID())
                .title(title)                                       // NEW — nullable passthrough, NO validation
                .content(content)
                .timestamp(timestamp != null ? timestamp : LocalDateTime.now())
                .workspaceId(workspaceId)
                .personId(personId)
                .build();
    }
}
```

### 2.3 Line-level delta (for the Implementer's diff)

1. **Field:** insert `String title;` immediately **after** `UUID id;` and **before** `String content;` (field order reads headline → body → moment — architecture §4.2b).
2. **Factory signature:** `create(String content, LocalDateTime timestamp, UUID workspaceId, UUID personId)` → `create(String title, String content, LocalDateTime timestamp, UUID workspaceId, UUID personId)` — `title` is the new **leading** parameter (mirrors `Project.create(name, description, …)`'s title-like field leading; analogous treatment to the Projects OQ-A domain change, `../create-projects-database/03-tech-spec.md` §2.2).
3. **Invariant — do NOT add validation for `title`.** It is intentionally nullable/unvalidated (ADR-0012: essence of a journal entry is `content` + `timestamp`; mirrors nullable `Project.dueDate`). The two existing invariants (`content` non-blank, `workspaceId` non-null) are **unchanged**, unchanged order, unchanged exception messages.
4. **Builder chain:** add `.title(title)` to the builder chain inside `create(...)`, positioned between `.id(...)` and `.content(...)` (mirrors field declaration order; no functional requirement on chain order, but keep it for readability).
5. **`@Value`/`@Builder` (public access), no `@Builder(access = AccessLevel.PRIVATE)` change** — `JournalEntry` currently uses the plain public `@Builder` (unlike `Project`'s `@Builder(access = AccessLevel.PRIVATE)`); **do not** change the builder's access level — that is out of scope for this ADR and not requested by architecture §4.2b.
6. **No `JournalEntry.reconstitute(...)` exists today and none is added by this feature** — the all-args `@Builder` is the reconstitution seam per `CLAUDE.md`; it already threads `title` through automatically once the field exists. If a future persistence adapter adds a `reconstitute(...)` factory, it must accept `title` — tracked, not built here.
7. **Imports unchanged** (`lombok.Builder`, `lombok.Value`, `java.time.LocalDateTime`, `java.util.UUID`).

**Do NOT add:** a `title` non-blank invariant (tracked follow-up, architecture §9 — "if the stakeholder later wants a mandatory title, that is a small additive invariant change... not reopened here"); a `JournalEntry.reconstitute(...)` method; any `JournalEntryRepository` or JPA entity; any change to `content`/`timestamp`/`workspaceId`/`personId` semantics.

---

## 3. Exact schema (title property + required properties, ADR-0012 mapping)

Grounded in the `JournalEntry` aggregate **after** §2's change (`domain/journal/JournalEntry.java`).

| §3 property | Field grounding | `NotionPropertyType` | Notion config |
|---|---|---|---|
| **Title** (db title property) | `JournalEntry.title` — **new nullable field** (§2; ADR-0012, stakeholder OQ-A option c) | `TITLE` | `{ "type": "title", "title": {} }` |
| **Content** | `JournalEntry.content` (non-blank via `JournalEntry.create`) | `RICH_TEXT` | `{ "type": "rich_text", "rich_text": {} }` |
| **Date** | `JournalEntry.timestamp` (`LocalDateTime`) | `DATE` | `{ "type": "date", "date": {} }` — a Notion `date` value carries an ISO 8601 date "with an optional time" ([Page property values — Date](https://developers.notion.com/reference/page-property-values)), so the full `LocalDateTime` is representable without truncation (ADR-0012) |

Database (page) title marker: `"Journal"` (fixed constant, matches architecture §3/§4.1) — distinct from the title *property* name `"Title"` (spec §7; architecture §0 "Database title marker"). Two independent concerns that happen to share the word "title".

**No `select` property** — `JournalEntry` has no closed-set/enum field, so ADR-0006 (Status-as-select)/ADR-0009 (label discipline) do not apply here; no `PropertyDefinition.options`, no domain-enum import in `CreateJournalDatabaseService`.

**Verify is name-only** (ADR-0008, inherited unchanged): a user renaming or leaving the `Title` column empty, or adding extra columns in Notion, never triggers repair. The nullable `title` is harmless to idempotency — this step writes no rows (FR-15), so no `title` value is populated regardless of the domain change.

**Excluded from this schema** (architecture §4.2, FR-14): the `JournalEntry.personId → People` relation (deferred to Phase C — Create Relations; requires both databases to exist first) and any rollup/formula/row. `JournalEntry.workspaceId` is expressed structurally (child of the Dashboard page), not as a column — same convention as Projects/Tasks.

---

## 4. `application.usecase.journal.CreateJournalDatabaseService` [MODIFIED]

### 4.1 Current state (to be replaced)

```java
package com.lifeos.application.usecase.journal;

import com.lifeos.application.dto.workspace.ProvisioningStepResult;
import com.lifeos.application.port.NotionProvisioningPort;
import com.lifeos.application.usecase.workspace.WorkspaceLedgerWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CreateJournalDatabaseService implements CreateJournalDatabaseUseCase {

    private final NotionProvisioningPort notion;
    private final WorkspaceLedgerWriter ledger;

    @Override
    public ProvisioningStepResult execute(UUID workspaceId) {
        throw new UnsupportedOperationException(
                "Journal database creation not yet implemented: requires the Notion adapter");
    }
}
```

(`backend/src/main/java/com/lifeos/application/usecase/journal/CreateJournalDatabaseService.java`, current 2-arg constructor + stub throw — NFR-4/"no silent no-op" is satisfied today only by the explicit throw; it is removed as this real implementation lands.)

### 4.2 Full intended source (verbatim mirror of `CreateTasksDatabaseService`, `TASKS_DB → JOURNAL_DB`, no select property)

```java
package com.lifeos.application.usecase.journal;

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
import static com.lifeos.domain.workspace.ProvisionedResourceType.JOURNAL_DB;

@Slf4j
@Service
@RequiredArgsConstructor
public class CreateJournalDatabaseService implements CreateJournalDatabaseUseCase {

    private static final String TITLE = "Journal";

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

        DatabaseSpec spec = journalSpec();
        ExpectedShape expected = journalExpectedShape();
        Optional<String> ledgerId = workspace.resource(JOURNAL_DB).map(ProvisionedResource::notionId);

        ProvisioningStepResult result = ledgerId.isPresent()
                ? executeWarmPath(workspaceId, ledgerId.get(), dashboardId, spec, expected)
                : executeColdPath(workspaceId, dashboardId, spec, expected);

        log.info("Journal database step for workspace {}: dashboardId={}, priorLedgerId={}, outcome={}",
                workspaceId, dashboardId, ledgerId.orElse("none"), result.outcome());
        return result;
    }

    private ProvisioningStepResult executeWarmPath(UUID workspaceId, String existingId, String dashboardId,
                                                     DatabaseSpec spec, ExpectedShape expected) {
        VerificationResult result = notion.verify(existingId, JOURNAL_DB, expected);
        log.info("Journal database verify for workspace {}: notionId={}, result={}", workspaceId, existingId, result);
        return switch (result) {
            case PRESENT_MATCHING -> new ProvisioningStepResult(JOURNAL_DB, ProvisioningOutcome.RECONCILED, null);
            case PRESENT_DRIFTED -> {
                notion.repairShape(existingId, expected);
                ledger.record(workspaceId, JOURNAL_DB, existingId);
                yield new ProvisioningStepResult(JOURNAL_DB, ProvisioningOutcome.REPAIRED, "database drifted; shape repaired");
            }
            case ABSENT -> {
                Optional<String> found = notion.findChildByIdentity(dashboardId, JOURNAL_DB, expected);
                if (found.isPresent()) {
                    ledger.record(workspaceId, JOURNAL_DB, found.get());
                    yield new ProvisioningStepResult(JOURNAL_DB, ProvisioningOutcome.REPAIRED, "ledger id was stale; re-adopted existing database");
                }
                String newId = notion.createDatabase(dashboardId, spec);
                ledger.record(workspaceId, JOURNAL_DB, newId);
                yield new ProvisioningStepResult(JOURNAL_DB, ProvisioningOutcome.REPAIRED, "ledger id was stale; database recreated");
            }
        };
    }

    private ProvisioningStepResult executeColdPath(UUID workspaceId, String dashboardId, DatabaseSpec spec, ExpectedShape expected) {
        Optional<String> found = notion.findChildByIdentity(dashboardId, JOURNAL_DB, expected);
        log.info("Journal database findChildByIdentity for workspace {}: found={}", workspaceId, found.isPresent());
        if (found.isEmpty()) {
            String newId = notion.createDatabase(dashboardId, spec);
            ledger.record(workspaceId, JOURNAL_DB, newId);
            return new ProvisioningStepResult(JOURNAL_DB, ProvisioningOutcome.CREATED, null);
        }

        String orphanId = found.get();
        VerificationResult orphanVerify = notion.verify(orphanId, JOURNAL_DB, expected);
        return switch (orphanVerify) {
            case PRESENT_MATCHING -> {
                ledger.record(workspaceId, JOURNAL_DB, orphanId);
                yield new ProvisioningStepResult(JOURNAL_DB, ProvisioningOutcome.RECONCILED, null);
            }
            case PRESENT_DRIFTED -> {
                notion.repairShape(orphanId, expected);
                ledger.record(workspaceId, JOURNAL_DB, orphanId);
                yield new ProvisioningStepResult(JOURNAL_DB, ProvisioningOutcome.REPAIRED, "adopted orphan database was drifted; shape repaired");
            }
            case ABSENT -> {
                String newId = notion.createDatabase(dashboardId, spec);
                ledger.record(workspaceId, JOURNAL_DB, newId);
                yield new ProvisioningStepResult(JOURNAL_DB, ProvisioningOutcome.CREATED, null);
            }
        };
    }

    static DatabaseSpec journalSpec() {
        return new DatabaseSpec(TITLE, List.of(
                PropertyDefinition.of("Title", NotionPropertyType.TITLE),      // backed by JournalEntry.title (nullable)
                PropertyDefinition.of("Content", NotionPropertyType.RICH_TEXT), // JournalEntry.content
                PropertyDefinition.of("Date", NotionPropertyType.DATE)));       // JournalEntry.timestamp
    }

    static ExpectedShape journalExpectedShape() {
        return new ExpectedShape(TITLE, journalSpec().properties());
    }
}
```

### 4.3 Line-level delta from `CreateTasksDatabaseService.java` (for reference — the Implementer may diff against it directly)

1. Package `com.lifeos.application.usecase.task` → `com.lifeos.application.usecase.journal`.
2. **Drop** the `com.lifeos.domain.task.TaskStatus` import — Journal has no `select`/enum-backed property. **Drop** the `java.util.Arrays` import (was only used for `TaskStatus.values()`).
3. Static import `TASKS_DB` → `JOURNAL_DB`.
4. `TITLE = "Tasks"` → `TITLE = "Journal"`.
5. Every `TASKS_DB` token (method bodies, log messages — phrasing stays "Journal database …") → `JOURNAL_DB`.
6. `tasksSpec()`/`tasksExpectedShape()` → `journalSpec()`/`journalExpectedShape()`.
7. In `journalSpec()`: **remove** the `Status` `PropertyDefinition` (no `SELECT`, no `statusOptions` list, no `Arrays.stream(...)` call) and the `Due Date` property; property list becomes exactly **three** entries: `PropertyDefinition.of("Title", TITLE)` / `PropertyDefinition.of("Content", RICH_TEXT)` / `PropertyDefinition.of("Date", DATE)` (was four: `Title`/`Description`/`Status`/`Due Date`).
8. Constructor, `execute`, `executeWarmPath`, `executeColdPath` bodies are otherwise **byte-for-byte structurally identical** (same branching, same log statement shape, same outcome/detail strings) — no algorithmic delta, only the `TASKS_DB → JOURNAL_DB` substitution and the smaller property list.

### 4.4 Outcome decision table

Reused verbatim from Projects/Tasks (architecture §3 "Outcome decision table"), substituting `JOURNAL_DB`. Not restated as a new table — the 9-row shape (`CREATED` only on first-time create with no prior ledger record; adoption is never `CREATED`; `REPAIRED` ⇔ a Notion write happened this run; `RECONCILED` ⇔ none; `>1` identity match ⇒ `FAILED` via propagated `NotionApiException`) applies identically; see §5.1 for the per-row test mapping.

### 4.5 Transaction boundary — summary

| Class | Transaction | Notes |
|---|---|---|
| `CreateJournalDatabaseService.execute` | **none** | Pure port orchestration; mirrors `CreateTasksDatabaseService` — several Notion HTTP calls would hold a DB connection across slow remote work if annotated. |
| `WorkspaceLedgerWriter.record` | `@Transactional` (existing, unchanged) | The **only** transactional write in this step. |
| `JpaWorkspaceRepository.findById` | `@Transactional(readOnly = true)` (existing, unchanged) | Read path for `workspaceRepository.findById`. |
| `NotionProvisioningAdapter.*` / `NotionClient.*` | none | Not a transactional resource; unchanged. |

No new `@Service`/`@Component`/`@Repository` bean.

---

## 5. Security

- **Token handling**: unchanged. `NotionClient` remains the only class reading the token; `CreateJournalDatabaseService` never touches it.
- **Token-never-leaked (NFR-6)**: enforced structurally exactly as Projects/Tasks — `NotionApiException` messages built only from status + Notion's `code`/`message`, plus the reused `>1`-match message which interpolates only the match count and `expected.title()` (constant `"Journal"`, not a secret).
- **No REST/CLI authn change, no OAuth, no per-Person token, no new config property.**

---

## 6. Test plan (write these first — TDD)

Narrowest-sufficient tier per class (`spring-testing`). Build/verify order: domain unit test (§6.1) → service unit test (§6.2, mocked port) → wiring IT (§6.3). **No new value-type unit tests** (`PropertyDefinition`/`DatabaseSpec`/`ExpectedShape`/`NotionPropertyType` are unchanged and already covered by the Projects pass's tests). **No new adapter contract tests** (§6.4 — confirmed by §1.1's `DATE` check). No live Notion, no token, no network egress anywhere.

### 6.1 `domain/journal/JournalEntryTest.java` [NEW]

Mirror `domain/project/ProjectTest.java`'s structure and rigor. Plain JUnit 5 + AssertJ, no Mockito, no Spring context (`spring-testing` — domain tests are the fastest tier, no slice annotation needed).

**Expected count: 8 tests.** Full method list:

1. `create_generatesId` — `JournalEntry.create("My headline", "body text", null, workspaceId, null).getId()` is non-null (`UUID`).
2. `create_defaultsTimestampToNowWhenNull` — `create(null title, "content", null timestamp, workspaceId, null)` → `getTimestamp()` is non-null and within a tolerance window of `LocalDateTime.now()` (e.g. `isCloseTo(LocalDateTime.now(), within(2, ChronoUnit.SECONDS))`).
3. `create_keepsProvidedTimestamp` — pass an explicit `LocalDateTime` → `getTimestamp()` equals exactly the value passed (no `now()` substitution).
4. `create_allowsNullTitle` — `create(null, "content", null, workspaceId, null)` does **not** throw; `getTitle()` is `null`.
5. `create_keepsProvidedTitle` — `create("My headline", "content", null, workspaceId, null)` → `getTitle()` equals `"My headline"` exactly.
6. `create_rejectsBlankContent` — `create("title", "", ..., workspaceId, null)` (and a separate case/assertion for `null` content) → `assertThatThrownBy(...).isInstanceOf(IllegalArgumentException.class).hasMessage("JournalEntry content must not be null or blank")`.
7. `create_rejectsNullWorkspaceId` — `create("title", "content", null, null, null)` → `IllegalArgumentException` with message `"JournalEntry workspaceId must not be null"`.
8. `create_isImmutable` — reflection or structural check that `JournalEntry` has no public setters (consistent with `@Value`); alternatively assert two `create(...)` calls with identical inputs produce distinct `id`s (proves no shared mutable state) — mirror whatever assertion style `ProjectTest`'s equivalent immutability test uses.

Each test constructs directly via `JournalEntry.create(...)` — no builder access in the test (the private/public builder is exercised only via the factory, consistent with `CLAUDE.md`'s reconstitution-seam discipline).

### 6.2 `application/usecase/journal/CreateJournalDatabaseServiceTest.java` [NEW — full rewrite of the existing stub test]

Delete the existing single test (`execute_throwsUnsupportedOperationExceptionUntilNotionAdapterExists`, 2-arg constructor, `backend/src/test/java/com/lifeos/application/usecase/journal/CreateJournalDatabaseServiceTest.java`) — the constructor becomes 3-arg, a breaking change. Mirror `CreateTasksDatabaseServiceTest` (`backend/src/test/java/com/lifeos/application/usecase/task/CreateTasksDatabaseServiceTest.java`) exactly, substituting `JOURNAL_DB`/`journalSpec`/`journalExpectedShape` and the Journal property names (no `Status`/enum options to assert). Mocks: `NotionProvisioningPort notion`, `WorkspaceRepository workspaceRepository`, `WorkspaceLedgerWriter ledger`. Fixture helper: `Workspace.reconstitute(id, UUID.randomUUID(), "Personal", List.of()).record(DASHBOARD, "dash-id")` (and `.record(JOURNAL_DB, notionId)` for warm-path fixtures).

**Expected count: 17 tests** (same count and shape as Tasks §5.1) — one per outcome-table row (9) + preconditions (2) + ambiguous-match propagation (2, cold and warm-ABSENT) + Notion-failure propagation without ledger write (2) + never-invokes-unrelated-port-methods (1) + not-`@Transactional` reflection (1) + `journalSpec()`/`journalExpectedShape()` direct assertions (2). Full method list:

1. `execute_throwsWhenWorkspaceNotFound` — `workspaceRepository.findById` empty → `IllegalStateException("Workspace not found: " + id)`; `verifyNoInteractions(notion)`, `verifyNoInteractions(ledger)` (FR-2).
2. `execute_throwsWhenNoDashboardLedgerEntry` — workspace present, no `DASHBOARD` resource → `IllegalStateException("No confirmed Dashboard for workspace " + id)`; `verifyNoInteractions(notion)`, `verifyNoInteractions(ledger)` (FR-3).
3. `execute_createsWhenColdAndNoOrphan` — no `JOURNAL_DB` resource; `findChildByIdentity(dashId, JOURNAL_DB, expected)` → `Optional.empty()`; `createDatabase(dashId, spec)` → `"new-db-id"`; capture the `DatabaseSpec` passed to `createDatabase` and assert `title()` equals `"Journal"` and `properties()` has exactly **3** entries in order `Title`(TITLE)/`Content`(RICH_TEXT)/`Date`(DATE), and no property has `type() == SELECT`; `ledger.record(workspaceId, JOURNAL_DB, "new-db-id")` called once; outcome `CREATED`, `detail` null (row 1).
4. `execute_adoptsWhenColdAndOrphanMatches` — `findChildByIdentity` → `Optional.of("orphan-id")`; `verify("orphan-id", JOURNAL_DB, expected)` → `PRESENT_MATCHING`; assert **no** `createDatabase`/`repairShape`; `ledger.record(workspaceId, JOURNAL_DB, "orphan-id")`; outcome `RECONCILED` (row 2).
5. `execute_adoptsAndRepairsWhenColdAndOrphanDrifted` — `findChildByIdentity` → present; `verify` → `PRESENT_DRIFTED`; assert `repairShape("orphan-id", expected)` called once, `ledger.record`; outcome `REPAIRED` (row 3).
6. `execute_propagatesAmbiguousMatchFailureOnColdPath` — `findChildByIdentity` throws `NotionApiException`; assert propagation (`isSameAs`), `verifyNoInteractions(ledger)` (row 4).
7. `execute_reconcilesWhenWarmAndMatching` — `JOURNAL_DB` resource present (`"existing-id"`); `verify` → `PRESENT_MATCHING`; assert **no** write, **no** `ledger.record`, **no** `findChildByIdentity` call; outcome `RECONCILED` (row 5).
8. `execute_repairsWhenWarmAndDrifted` — `verify` → `PRESENT_DRIFTED`; assert `repairShape("existing-id", expected)`, `ledger.record(workspaceId, JOURNAL_DB, "existing-id")`; outcome `REPAIRED` (row 6).
9. `execute_reAdoptsWhenWarmAndDeletedAndOrphanFound` — `verify` → `ABSENT`; `findChildByIdentity` → `Optional.of("orphan-id")`; assert **no** `createDatabase`, `ledger.record(workspaceId, JOURNAL_DB, "orphan-id")`; outcome `REPAIRED` (row 7).
10. `execute_reCreatesWhenWarmAndDeletedAndNoOrphanFound` — `verify` → `ABSENT`; `findChildByIdentity` → `Optional.empty()`; assert `createDatabase` called, `ledger.record`; outcome `REPAIRED` (row 8).
11. `execute_propagatesAmbiguousMatchFailureOnWarmAbsentPath` — `verify` → `ABSENT`; `findChildByIdentity` throws `NotionApiException`; assert propagation, `verifyNoInteractions(ledger)` (row 9).
12. `execute_propagatesNotionFailureFromVerifyWithoutWritingLedger` — `verify` throws `NotionApiException` directly (transport failure, not `ABSENT`); assert propagation, `verifyNoInteractions(ledger)` (FR-12).
13. `execute_propagatesNotionFailureFromCreateWithoutWritingLedger` — cold path, `findChildByIdentity` empty, `createDatabase` throws; assert propagation, `verifyNoInteractions(ledger)` (Notion-write-before-ledger-write ordering).
14. `execute_neverInvokesRelationRollupFormulaOrSampleOrPageMethods` — on a happy-path execution, `verify(notion, never())` for `ensureRelation`/`ensureRollup`/`ensureFormula`/`hasSampleRecords`/`insertSampleRecords`/`createRootPage`/`verifyPage`/`repairPage`/`findRootByIdentity` (FR-14).
15. `execute_isNotAnnotatedTransactional` — reflection: `CreateJournalDatabaseService.class.getMethod("execute", UUID.class).isAnnotationPresent(Transactional.class) == false`, and the class itself is not `@Transactional`.
16. `journalSpec_buildsThreePropertiesWithNoSelect` — direct unit test of the package-private static method: asserts title `"Journal"`, exactly 3 properties in order — `Title`(TITLE)/`Content`(RICH_TEXT)/`Date`(DATE); assert none has `type() == SELECT` and every `options()` is empty. Compare each `PropertyDefinition` via `.isEqualTo(...)` the same way Tasks' `tasksSpec_buildsFourPropertiesWithStatusOptionsFromEnum` does.
17. `journalExpectedShape_matchesSpecProperties` — `journalExpectedShape().requiredProperties()` equals `journalSpec().properties()`; `journalExpectedShape().title()` equals `"Journal"`.

### 6.3 `application/usecase/journal/CreateJournalDatabaseServiceIT.java` [NEW]

Mirror `CreateTasksDatabaseServiceIT` (`backend/src/test/java/com/lifeos/application/usecase/task/CreateTasksDatabaseServiceIT.java`) exactly: `@SpringBootTest` + `@Testcontainers` Postgres container, a `@TestConfiguration`-supplied `@Primary` `InMemoryDatabaseOnlyNotionPort` fake implementing only the four database methods realistically (create assigns a UUID string id, stores title + property-name set; verify/find/repair read/mutate the map) plus the four page methods delegating to a fixed pre-adopted Dashboard id, and every other port method throwing `UnsupportedOperationException`. `@BeforeEach` clears the fake's static map. (Reuse the Tasks IT's fake rather than writing a new one, if it is package-visible/reusable; otherwise mirror it verbatim in the `journal` test package.)

4 tests, substituting `JOURNAL_DB`/`createJournalDatabase`:

1. `execute_persistsJournalDbLedgerRowOnFirstRun` — a fresh workspace with a pre-seeded `DASHBOARD` resource → `execute` → outcome `CREATED`; a direct repository read shows exactly one `JOURNAL_DB` `ProvisionedResource` row.
2. `execute_convergesToOneRowAcrossThreeReruns` — run `execute` three times in sequence → still exactly one `JOURNAL_DB` row after all three; second/third outcomes are `RECONCILED` (FR-13); asserts the same `notionId` across all three reads. This is the multi-run convergence case — no separate class needed.
3. `execute_reachesRepairedOutcomeWhenFakeSimulatesExternalPropertyRemoval` — after the first run, mutate the fake's stored properties map directly (simulating an out-of-band Notion property deletion, e.g. remove `"Content"`) → second `execute` → `PRESENT_DRIFTED` → `REPAIRED`, ledger row's `notionId` unchanged.
4. `execute_throwsWhenPhaseAIncomplete` — a workspace with **no** `DASHBOARD` resource → `execute` throws `IllegalStateException`, and the repository shows **no** `JOURNAL_DB` row.

Class name ends in `IT` so Failsafe runs it under `./mvnw verify`. Zero real Notion calls; no `MockRestServiceServer` in this class.

### 6.4 No new adapter contract tests — explicit note (the `DATE`-risk resolution)

`NotionProvisioningAdapter`'s database slice (`createDatabase`/`verify`/`findChildByIdentity`/`repairShape`) is **generic over `ProvisionedResourceType` + the typed `DatabaseSpec`/`ExpectedShape`/`PropertyDefinition`**, and its `DATE` branch (`propertyConfig`, l.266: `case DATE -> Map.of("type", "date", "date", Map.of())`) is **already contract-tested** by `NotionProvisioningAdapterDatabaseTest` (shipped under the Projects pass, which introduced `Project.dueDate`/`Task.dueDate` as the first `DATE`-typed properties — see `docs/pipeline/create-projects-database/03-tech-spec.md` §9.2/§1). Journal passing a `JOURNAL_DB` type with a `Date` `PropertyDefinition` exercises the exact same `propertyConfig`/request-building/response-parsing code paths already proven for `PROJECTS_DB`'s `Due Date`/`TASKS_DB`'s `Due Date`. **Do not duplicate those adapter tests for Journal.** The correctness obligation that *is* new — that `CreateJournalDatabaseService` builds and passes the *right* three-property `DatabaseSpec`/`ExpectedShape` to the (mocked) port, with **no** fourth `SELECT` property — is covered instead at the service-unit level by §6.2 tests 3 and 16, and at the IT level by §6.3.

**Explicit confirmation for the Implementer:** `NotionPropertyType.DATE` and `NotionProvisioningAdapter#propertyConfig`'s `DATE` branch both already exist (verified §1.1) — this is **not** an in-scope adapter change, and no `NotionProvisioningAdapterDatabaseTest` edits are required.

---

## 7. Explicitly NOT built in this pass (scope guard for the Implementer)

- **Any `Journal ↔ Person` relation**, or any relation/rollup/formula of any kind. `ensureRelation`/`ensureRollup`/`ensureFormula` are untouched, unchanged stubs. `JournalEntry.personId` remains a UUID-only reference — do not add a Notion relation column for it (deferred to Phase C — Create Relations; FR-14).
- **Rows or sample data.** `hasSampleRecords`/`insertSampleRecords` untouched. This step provisions the container only — zero Notion database rows are written; `JournalEntry.title`/`content`/`timestamp` are never serialized to a Notion page value by this feature (FR-15).
- **The other five databases** (Projects, Tasks, Knowledge, Habits, Resources, People) and `GOALS_DB`/`REVIEWS_DB`. Only `JOURNAL_DB` is exercised. Do not generalize `journalSpec()`/`tasksSpec()`/`projectsSpec()` into a shared multi-database schema builder in this pass (YAGNI).
- **Any change to `JournalEntry` beyond the `title` field** (§2). No `title` non-blank invariant (tracked follow-up per architecture §9), no `JournalEntry.reconstitute(...)`, no `JournalEntryRepository`, no JPA entity, no Flyway migration.
- **Any change to the port, the adapter, `NotionClient`, or any typed schema value type** (`DatabaseSpec`/`ExpectedShape`/`PropertyDefinition`/`NotionPropertyType`) — all reused byte-for-byte; confirmed in §1.1/§6.4 that `DATE` support pre-exists and needs no change.
- **`docs/productivity/*` population** or any other documentation content change.
- **A semantic Notion-Version range/comparison check, new config property, or profile change.** Nothing here needs one.

---

## 8. Traceability (FR/NFR → spec section)

| Req | Satisfied by (this spec) |
|---|---|
| FR-1 | §4.2 unchanged `CreateJournalDatabaseUseCase.execute(UUID)` signature |
| FR-2 | §4.2 `workspaceRepository.findById` → `IllegalStateException` before any Notion call; test §6.2-1 |
| FR-3 | §4.2 `resource(DASHBOARD)` empty → `IllegalStateException`; test §6.2-2 |
| FR-4 | Cold path `findChildByIdentity` empty → `createDatabase` → `record` → `CREATED`; §4.4 row 1; test §6.2-3 |
| FR-5 | Warm `verify` `PRESENT_MATCHING` → `RECONCILED`, no write; §4.4 row 5; test §6.2-7 |
| FR-6a | Warm `ABSENT` → adopt-or-`createDatabase` → `REPAIRED`; §4.4 rows 7/8; tests §6.2-9, §6.2-10 |
| FR-6b | Warm `PRESENT_DRIFTED` → `repairShape` (non-destructive, reused adapter) → `REPAIRED`; test §6.2-8 |
| FR-7 | `verify`/`findChildByIdentity` on every path before any `RECONCILED`; §4.2; ADR-0008 (reused) |
| FR-8 | Cold `findChildByIdentity` (parent-scoped enumeration) → adopt; §4.2; ADR-0008 |
| FR-9 | `>1` child_database match → `NotionApiException` → `FAILED`; tests §6.2-6, §6.2-11 |
| FR-10 | `WorkspaceLedgerWriter.record` — own tx (reused); §4.5 |
| FR-11 | Returns `ProvisioningStepResult(JOURNAL_DB, …)`; failures propagate unmodified; §4.2 |
| FR-12 | Notion-before-ledger ordering + own-tx write + next-run adoption; tests §6.2-12, §6.2-13 |
| FR-13 | Adoption-before-create both paths; upsert `record`; IT convergence test §6.3-2 |
| FR-14 | Only the four DB port methods invoked; test §6.2-14; schema has no relation property (§3) |
| FR-15 | Schema-only; no row/value written; §3, §7; ADR-0012 |
| §3 schema (Title/Content/Date) + domain backing (OQ-A) | §3 (grounded in `JournalEntry.java` post-§2); §2 new `JournalEntry.title` field; ADR-0012 (title property option c + `date` mapping); tests §6.1 (domain), §6.2-16/17 (schema) |
| NFR-1 | Strict per-path live verification; §4.2 (inherited ADR-0002/ADR-0008) |
| NFR-2 | Notion-before-ledger + no rollback; next-run reconcile; §4.2; non-destructive repair (reused adapter) |
| NFR-3 | Mockito service tests + fake-port IT + domain unit tests; adapter reused/already covered; §6 |
| NFR-4 | Stub `UnsupportedOperationException` removed only as the real impl lands; §4.1/§4.2 |
| NFR-5 | `log.info` per run (workspaceId, dashboardId, prior ledger id, `VerificationResult`, acted-on id, outcome — no token/raw body); §4.2 |
| NFR-6 | §5 Security; message-construction rule reused from `NotionClient` |
| NFR-7 | No shared mutable state; only `JOURNAL_DB` ledger entry written; §4.5 |
| NFR-8 | Upsert `record` → exactly one `JOURNAL_DB` entry (`Workspace.record` semantics, reused) |
| NFR-9 | Bounded call count per run (reused adapter, unchanged budget) |
| NFR-10 | `429`/`529` `Retry-After` clamp in reused `NotionClient` (unchanged) |

---

## 9. Implementation notes (file list for the Implementer)

- `backend/src/main/java/com/lifeos/domain/journal/JournalEntry.java` — apply §2.2's full source (add `title` field + leading `create(...)` param; no validation on `title`).
- `backend/src/main/java/com/lifeos/application/usecase/journal/CreateJournalDatabaseService.java` — replace stub with §4.2's full source; 3-arg constructor (`NotionProvisioningPort`, `WorkspaceRepository`, `WorkspaceLedgerWriter`); `journalSpec()`/`journalExpectedShape()` package-private statics; SLF4J logging per NFR-5; no `@Transactional`.
- `backend/src/test/java/com/lifeos/domain/journal/JournalEntryTest.java` — new, 8 tests (§6.1).
- `backend/src/test/java/com/lifeos/application/usecase/journal/CreateJournalDatabaseServiceTest.java` — fully rewritten (3-arg constructor), 17 tests (§6.2).
- `backend/src/test/java/com/lifeos/application/usecase/journal/CreateJournalDatabaseServiceIT.java` — new `@SpringBootTest` + Testcontainers Postgres IT, 4 tests (§6.3); fake port's in-memory map cleared in `@BeforeEach`.

No changes to any other file. No changes to `WorkspaceController`, `WorkspaceCommands`, `ApiExceptionHandler`, any web/CLI DTO, `infrastructure.adapter.persistence`, `application.yml`, any Flyway migration, `NotionPropertyType`, or `NotionProvisioningAdapter`.

### Implementation notes

- `backend/src/main/java/com/lifeos/domain/journal/JournalEntry.java` — [MODIFIED] added nullable `title` field (after `id`, before `content`) and leading `title` param on `create(...)`; no new invariant.
- `backend/src/main/java/com/lifeos/application/usecase/journal/CreateJournalDatabaseService.java` — [MODIFIED] stub replaced with real 3-arg (`NotionProvisioningPort`, `WorkspaceRepository`, `WorkspaceLedgerWriter`) verify/create/adopt/repair implementation targeting `JOURNAL_DB`; `journalSpec()`/`journalExpectedShape()` package-private statics build the 3-property (Title/Content/Date) schema, no `SELECT`.
- `backend/src/test/java/com/lifeos/domain/journal/JournalEntryTest.java` — [NEW] 8 tests per §6.1.
- `backend/src/test/java/com/lifeos/application/usecase/journal/CreateJournalDatabaseServiceTest.java` — [MODIFIED, full rewrite] 17 tests per §6.2, 3-arg constructor.
- `backend/src/test/java/com/lifeos/application/usecase/journal/CreateJournalDatabaseServiceIT.java` — [NEW] `@SpringBootTest` + Testcontainers Postgres IT with in-memory fake `NotionProvisioningPort`, 4 tests per §6.3.

Verification: `./mvnw test` → `Tests run: 263, Failures: 0, Errors: 0, Skipped: 0` (unit/slice tier). `./mvnw verify` (Podman env exported per `00-preflight.md`) → unit/slice tier `Tests run: 263, Failures: 0, Errors: 0, Skipped: 0` + failsafe tier `Tests run: 25, Failures: 0, Errors: 0, Skipped: 0` (includes `CreateJournalDatabaseServiceIT` at 4/4) → `BUILD SUCCESS`.

---

## 10. Findings / notes back to the Architect

None. The architecture (including ADR-0012) is complete and self-consistent for this step; no deviation was needed to produce this spec. The one risk item the orchestration prompt flagged — whether `DATE` is a genuinely new adapter concern — was checked directly against `NotionPropertyType.java` and `NotionProvisioningAdapter.java` (§1.1) and resolved as **no adapter change required**: `DATE` and its `propertyConfig` branch were already added during the Projects pass (for `Project.dueDate`/`Task.dueDate`) and require no Journal-specific work; Journal is the first *caller* of `DATE` from a `LocalDateTime`-backed field but not the first *support* of the type, and the Notion `date` value format (ISO 8601 date with optional time) already accommodates that without change.

---

## Citations

- Notion API Reference — [Property object](https://developers.notion.com/reference/property-object) (mandatory single `title` property per data source).
- Notion API Reference — [Page property values § Date](https://developers.notion.com/reference/page-property-values) (ISO 8601 date "with an optional time").
- `docs/pipeline/create-journal-database/adr/ADR-0012-journal-title-property-and-date-mapping.md`.
- `docs/pipeline/create-projects-database/adr/ADR-0005-notion-database-datasource-model.md`, `ADR-0007-typed-database-schema-value-type.md`, `ADR-0008-database-identity-verification-nondestructive-repair.md`.
- `docs/pipeline/create-tasks-database/adr/ADR-0009-taskstatus-select-option-labels.md`.
- `docs/pipeline/create-tasks-database/03-tech-spec.md` (structural/rigor pattern).
- `docs/pipeline/create-projects-database/03-tech-spec.md` §2 (in-scope domain-change treatment pattern, `ProjectStatus`/`dueDate`).
