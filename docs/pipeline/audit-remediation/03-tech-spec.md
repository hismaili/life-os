# 03 — Tech Spec: Audit Remediation (Notion Adapter + CLI Wiring)

Mechanical implementation spec for `docs/pipeline/audit-remediation/02-architecture.md` and
ADR-0010…ADR-0016. Signatures, guard conditions, and test cases below are written against the
**actual current source** (read in full before drafting this spec) so the Implementer can apply
them as a diff, not a redesign. No design decision is introduced beyond what the architecture/ADRs
already resolved, **except** one explicitly SME-delegated choice — see §0.

---

## 0. One SME decision the ADRs explicitly delegate

ADR-0013's "coupling note" flags that classifying by `instanceof NotionApiException` in
`CreateWorkspaceService` (application layer) creates an `application → infrastructure.adapter.notion`
type reference, which violates CLAUDE.md's "inner layers never depend on outer ones" rule — and
offers a marker-interface alternative "for the SME to finalize."

**Decision: adopt the marker-interface alternative.** Introduce
`com.lifeos.application.usecase.workspace.SafeToSurfaceException` (a no-method marker interface) in
the **application** layer; `NotionApiException` (infrastructure) implements it. This is the correct
hexagonal direction — the outer layer implements an inner-layer-owned contract, exactly like
`NotionProvisioningAdapter implements NotionProvisioningPort` already does. `safeDetail(...)` then
checks `instanceof SafeToSurfaceException`, never `instanceof NotionApiException`, so the
application layer gains **zero** new dependency on `infrastructure.adapter.notion`.
`UnsupportedOperationException` (`java.lang`, not a project outer-layer type) is checked separately
by its own JDK type — no layering concern there.

This is not a deviation from the architecture; ADR-0013 names this exact alternative and defers the
choice to this stage. No escalation needed.

---

## 1. Package layout (net change)

```
backend/src/main/java/com/lifeos/
  application/usecase/workspace/
    CreateWorkspaceService.java        (MODIFIED — logger + safeDetail)
    SafeToSurfaceException.java        (NEW — marker interface, §0)
  infrastructure/adapter/notion/
    NotionProperties.java              (MODIFIED — toString override)
    NotionProvisioningAdapter.java     (MODIFIED — guards, pagination cap, rename)
    NotionClient.java                  (MODIFIED — named status constants)
    NotionApiException.java            (MODIFIED — implements SafeToSurfaceException)
  infrastructure/adapter/cli/
    WorkspaceCommands.java             (MODIFIED — Terminal, labels, concise exception)
    ResourceTypeLabel.java             (NEW — presentation-only enum→label map)
    CommandFailedException.java        (UNCHANGED)
  domain/workspace/
    ProvisionedResourceType.java       (UNCHANGED — no presentation added)

backend/src/test/java/com/lifeos/
  infrastructure/adapter/notion/
    NotionPropertiesTest.java                    (MODIFIED — +2 tests)
    NotionProvisioningAdapterTest.java            (MODIFIED — +2 tests)
    NotionProvisioningAdapterDatabaseTest.java    (MODIFIED — +7 tests)
    NotionClientTest.java                         (UNCHANGED — must still pass)
  application/usecase/workspace/
    CreateWorkspaceServiceTest.java     (MODIFIED — +3 tests)
  infrastructure/adapter/cli/
    WorkspaceCommandsTest.java              (MODIFIED — ctor sig, label assertions, -1/+2 tests)
    WorkspaceCommandsRegistrationTest.java   (MODIFIED — -1 test, +Terminal bean)
    WorkspaceCommandsShellTest.java          (NEW — @ShellTest, 4 tests, no Testcontainers)
```

No entities, repositories, DTO records, or database migrations change. §§2–4 of the standard
tech-spec template (Entities / DTOs / Repositories) are **not applicable** to this feature — the
remediation touches adapter, application-service, and CLI-presentation code only.

---

## 2. `NotionProperties` — full token redaction (FR-1 / ADR-0010)

File: `infrastructure/adapter/notion/NotionProperties.java`

```java
package com.lifeos.infrastructure.adapter.notion;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "notion")
@Validated
public record NotionProperties(
        @NotBlank String token,
        @NotBlank String version,
        @NotBlank String rootParentPageId
) {

    private static final String REDACTED = "****";

    @Override
    public String toString() {
        return "NotionProperties[token=" + REDACTED
                + ", version=" + version
                + ", rootParentPageId=" + rootParentPageId + "]";
    }
}
```

- `token()` accessor is untouched (still returns the raw value — `NotionClient` needs it for
  `"Bearer " + properties.token()`).
- No other file changes; every caller that logs/interpolates a `NotionProperties` instance is fixed
  at the source.

---

## 3. `NotionProvisioningAdapter` — fail-closed guards + bounded pagination (FR-2, FR-5, FR-6 / ADR-0011, ADR-0012)

File: `infrastructure/adapter/notion/NotionProvisioningAdapter.java`

### 3.1 New constant and private helpers (add near the top of the class, after the fields)

```java
private static final int MAX_SEARCH_PAGES = 50; // ADR-0012 [ASSUMPTION], see architecture §5.2

private static String requirePrimaryDataSourceId(NotionDatabaseResponse db, String ctx) {
    List<NotionDataSourceSummary> ds = db.dataSources();
    if (ds == null || ds.isEmpty() || ds.get(0) == null || ds.get(0).id() == null) {
        throw new NotionApiException("Notion API error: no data source on " + ctx);
    }
    return ds.get(0).id();
}

private static NotionDataSourceResponse requireDataSource(NotionDataSourceResponse ds, String ctx) {
    if (ds == null || ds.properties() == null) {
        throw new NotionApiException("Notion API error: data source unavailable for " + ctx);
    }
    return ds;
}

private static <T> List<T> nullSafe(List<T> results) {
    return results == null ? List.of() : results;
}
```

Import `com.lifeos.infrastructure.adapter.notion.dto.NotionDataSourceSummary` (currently unused in
the class; the guard helper needs the type name for the `List<NotionDataSourceSummary>` local).

### 3.2 `verify(...)` — replace bare dereferences

Replace:
```java
String dsId = response.dataSources().get(0).id();
NotionDataSourceResponse dataSource = client.get("/data_sources/{id}", NotionDataSourceResponse.class, dsId);
for (PropertyDefinition required : expected.requiredProperties()) {
    if (!dataSource.properties().containsKey(required.name())) {
```
with:
```java
String dsId = requirePrimaryDataSourceId(response, expected.title());
NotionDataSourceResponse dataSource = requireDataSource(
        client.get("/data_sources/{id}", NotionDataSourceResponse.class, dsId), expected.title());
for (PropertyDefinition required : expected.requiredProperties()) {
    if (!dataSource.properties().containsKey(required.name())) {
```
(Rest of the method body unchanged.)

### 3.3 `repairShape(...)` — replace bare dereferences

Replace:
```java
String dsId = current.dataSources().get(0).id();
NotionDataSourceResponse dataSource = client.get("/data_sources/{id}", NotionDataSourceResponse.class, dsId);
```
with:
```java
String dsId = requirePrimaryDataSourceId(current, expected.title());
NotionDataSourceResponse dataSource = requireDataSource(
        client.get("/data_sources/{id}", NotionDataSourceResponse.class, dsId), expected.title());
```
(Rest of the method body unchanged.)

### 3.4 `findRootByIdentity(...)` — bounded pagination + null-safe `results()`

Replace the whole method body with:
```java
@Override
public Optional<String> findRootByIdentity(PageShape expected) {
    List<NotionPageResponse> matches = new ArrayList<>();
    String cursor = null;
    int pages = 0;
    do {
        if (pages >= MAX_SEARCH_PAGES) {
            throw new NotionApiException("Notion search exceeded the page cap (" + MAX_SEARCH_PAGES
                    + ") while locating Dashboard '" + expected.title() + "'");
        }
        Map<String, Object> body = new HashMap<>();
        body.put("query", expected.title());
        body.put("filter", Map.of("value", "page", "property", "object"));
        if (cursor != null) {
            body.put("start_cursor", cursor);
        }
        NotionSearchResponse response = client.post("/search", body, NotionSearchResponse.class);
        pages++;
        nullSafe(response.results()).stream()
                .filter(result -> !result.archived() && !result.inTrash())
                .filter(result -> properties.rootParentPageId().equals(parentPageId(result)))
                .filter(result -> titleOf(result).equals(expected.title()))
                .forEach(matches::add);
        cursor = response.hasMore() ? response.nextCursor() : null;
    } while (cursor != null);

    if (matches.isEmpty()) {
        return Optional.empty();
    }
    if (matches.size() > 1) {
        throw new NotionApiException("Ambiguous Dashboard identity: " + matches.size()
                + " pages titled '" + expected.title() + "' found under the configured root parent");
    }
    return Optional.of(matches.get(0).id());
}
```
The cap check runs **before** issuing the next request, so a run that hits the cap makes exactly
`MAX_SEARCH_PAGES` HTTP calls (verifiable with `MockRestServiceServer.verify()` — no extra request
is recorded).

### 3.5 `findChildByIdentity(...)` — same pattern

Replace the whole method body with:
```java
@Override
public Optional<String> findChildByIdentity(String parentPageId, ProvisionedResourceType type, ExpectedShape expected) {
    List<NotionBlock> matches = new ArrayList<>();
    String cursor = null;
    int pages = 0;
    do {
        if (pages >= MAX_SEARCH_PAGES) {
            throw new NotionApiException("Notion search exceeded the page cap (" + MAX_SEARCH_PAGES
                    + ") while locating " + expected.title() + " under the Dashboard");
        }
        NotionBlockChildrenResponse response = cursor == null
                ? client.get("/blocks/{id}/children", NotionBlockChildrenResponse.class, parentPageId)
                : client.get("/blocks/{id}/children?start_cursor={cursor}", NotionBlockChildrenResponse.class, parentPageId, cursor);
        pages++;
        nullSafe(response.results()).stream()
                .filter(block -> "child_database".equals(block.type()))
                .filter(block -> block.childDatabase() != null && expected.title().equals(block.childDatabase().title()))
                .forEach(matches::add);
        cursor = response.hasMore() ? response.nextCursor() : null;
    } while (cursor != null);

    if (matches.isEmpty()) {
        return Optional.empty();
    }
    if (matches.size() > 1) {
        throw new NotionApiException("Ambiguous " + expected.title() + " database identity: " + matches.size()
                + " child databases titled '" + expected.title() + "' found under the Dashboard");
    }
    return Optional.of(matches.get(0).id());
}
```

### 3.6 `createDatabase(...)` — rename (FR-6, trivial)

Rename the local `Map<String, Object> properties` to `propertyConfigs` (it currently shadows the
instance field `private final NotionProperties properties`):
```java
Map<String, Object> propertyConfigs = new LinkedHashMap<>();
for (PropertyDefinition property : spec.properties()) {
    propertyConfigs.put(property.name(), propertyConfig(property));
}
Map<String, Object> body = Map.of(
        "parent", Map.of("type", "page_id", "page_id", parentPageId),
        "title", List.of(Map.of("text", Map.of("content", spec.title()))),
        "initial_data_source", Map.of("properties", propertyConfigs));
```
Behavior-preserving; no test changes required beyond the existing green suite.

---

## 4. `NotionClient` — named status constants (FR-7, trivial)

File: `infrastructure/adapter/notion/NotionClient.java`

Add import `org.springframework.http.HttpStatus` and a named constant:
```java
private static final int NOTION_OVERLOADED = 529; // non-standard Notion "overloaded" status
```

In `handleError(...)`, replace:
```java
if (status == 429 || status == 529) {
```
with:
```java
if (status == HttpStatus.TOO_MANY_REQUESTS.value() || status == NOTION_OVERLOADED) {
```

In `get(...)`, replace:
```java
if (response.getStatusCode().value() == 404) {
```
with:
```java
if (response.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
```

Behavior-preserving. `NotionClientTest` must pass unmodified.

---

## 5. `CreateWorkspaceService` — application-layer sanitization boundary (AUD-004 / ADR-0013)

### 5.1 New file: `application/usecase/workspace/SafeToSurfaceException.java`
```java
package com.lifeos.application.usecase.workspace;

/**
 * Marker for exceptions whose {@link Throwable#getMessage()} is authored by our own code and is
 * therefore safe to surface verbatim to the CLI operator (ADR-0013). Adapter exceptions implement
 * this application-owned interface — the application layer never references an adapter type
 * directly (CLAUDE.md: inner layers never depend on outer ones).
 */
public interface SafeToSurfaceException {
}
```

### 5.2 `NotionApiException` implements the marker
File: `infrastructure/adapter/notion/NotionApiException.java`
```java
package com.lifeos.infrastructure.adapter.notion;

import com.lifeos.application.usecase.workspace.SafeToSurfaceException;

public class NotionApiException extends RuntimeException implements SafeToSurfaceException {
    public NotionApiException(String message) {
        super(message);
    }

    public NotionApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

### 5.3 `CreateWorkspaceService` — logger + `safeDetail`
File: `application/usecase/workspace/CreateWorkspaceService.java`

Add imports `org.slf4j.Logger`, `org.slf4j.LoggerFactory`. Add field:
```java
private static final Logger log = LoggerFactory.getLogger(CreateWorkspaceService.class);
```

Replace `runStep(...)`:
```java
private ProvisioningStepResult runStep(Supplier<ProvisioningStepResult> step, ProvisionedResourceType type) {
    try {
        return step.get();
    } catch (Exception e) {
        log.error("Provisioning step {} failed", type, e);
        return new ProvisioningStepResult(type, ProvisioningOutcome.FAILED, safeDetail(e, type));
    }
}

private static String safeDetail(Exception e, ProvisionedResourceType type) {
    if (e instanceof SafeToSurfaceException || e instanceof UnsupportedOperationException) {
        return e.getMessage();
    }
    return "internal error during " + type + " provisioning (see server logs)";
}
```
`runOrBlock(...)` is unchanged — its `BLOCKED` branch already uses a fixed safe string.

No transactional/persistence change; `execute_isNotAnnotatedTransactional` continues to pass
unmodified.

---

## 6. `WorkspaceCommands` + `ResourceTypeLabel` (FR-8, AUD-005 / ADR-0014, ADR-0015)

### 6.1 New file: `infrastructure/adapter/cli/ResourceTypeLabel.java`
```java
package com.lifeos.infrastructure.adapter.cli;

import com.lifeos.domain.workspace.ProvisionedResourceType;

final class ResourceTypeLabel {

    private ResourceTypeLabel() {
    }

    static String of(ProvisionedResourceType type) {
        return switch (type) {
            case DASHBOARD -> "Dashboard";
            case PROJECTS_DB -> "Projects";
            case TASKS_DB -> "Tasks";
            case KNOWLEDGE_DB -> "Knowledge";
            case HABITS_DB -> "Habits";
            case JOURNAL_DB -> "Journal";
            case RESOURCES_DB -> "Resources";
            case PEOPLE_DB -> "People";
            case GOALS_DB -> "Goals";
            case REVIEWS_DB -> "Reviews";
            case RELATIONS -> "Relations";
            case ROLLUPS -> "Rollups";
            case FORMULAS -> "Formulas";
            case SAMPLE_DATA -> "Sample data";
        };
    }
}
```
Package-private (only `WorkspaceCommands`, same package, needs it) — no `default` branch, so adding
a 15th `ProvisionedResourceType` constant is a compile error here until labelled.

### 6.2 `WorkspaceCommands` — Terminal injection, labels, concise exception

**Design note (resolves an ambiguity the architecture's LLD leaves open):** the illustrative
pseudocode in architecture §5.6 writes the report unconditionally on both paths, but the *invariant*
it documents is "operator sees the report on success and failure" — which the pre-existing
**return-value** path already satisfies on success (Spring Shell prints a command method's return
value). Writing directly to the terminal is only *required* on the **failure** path, because
throwing bypasses the return-value print. Writing on both paths would double-print on success. This
spec writes directly to the terminal **only when `report.failed()`**, immediately before throwing;
the success path is unchanged (single print via the returned `String`), preserving
`create_rendersAllStepsOnSuccess`'s no-duplication behavior (NFR-4).

File: `infrastructure/adapter/cli/WorkspaceCommands.java`
```java
package com.lifeos.infrastructure.adapter.cli;

import com.lifeos.application.dto.workspace.CreateWorkspaceCommand;
import com.lifeos.application.dto.workspace.ProvisioningOutcome;
import com.lifeos.application.dto.workspace.ProvisioningReport;
import com.lifeos.application.dto.workspace.ProvisioningStepResult;
import com.lifeos.application.usecase.workspace.CreateWorkspaceUseCase;
import lombok.RequiredArgsConstructor;
import org.jline.terminal.Terminal;
import org.springframework.shell.command.annotation.Command;
import org.springframework.shell.command.annotation.Option;

import java.util.UUID;
import java.util.stream.Collectors;

@Command(group = "Workspace")
@RequiredArgsConstructor
public class WorkspaceCommands {

    private final CreateWorkspaceUseCase createWorkspace;
    private final Terminal terminal;

    @Command(command = "workspace create", description = "Create or reconcile a LifeOS workspace in Notion")
    public String create(
            @Option(longNames = "name", required = true) String name,
            @Option(longNames = "person-id", required = true) UUID personId,
            @Option(longNames = "sample-data", defaultValue = "false") boolean sampleData) {

        ProvisioningReport report = createWorkspace.execute(new CreateWorkspaceCommand(name, personId, sampleData));
        String rendering = renderReport(report);

        if (report.failed()) {
            terminal.writer().println(rendering);
            terminal.writer().flush();
            throw new CommandFailedException(conciseFailureSummary(report));
        }
        return rendering;
    }

    private String renderReport(ProvisioningReport report) {
        StringBuilder builder = new StringBuilder();
        for (ProvisioningStepResult step : report.steps()) {
            builder.append(ResourceTypeLabel.of(step.type()))
                    .append(": ")
                    .append(step.outcome());
            if (step.detail() != null && !step.detail().isBlank()) {
                builder.append(" (").append(step.detail()).append(")");
            }
            builder.append(System.lineSeparator());
        }
        return builder.toString();
    }

    private String conciseFailureSummary(ProvisioningReport report) {
        var failedOrBlocked = report.steps().stream()
                .filter(WorkspaceCommands::failedOrBlocked)
                .toList();
        String labels = failedOrBlocked.stream()
                .map(step -> ResourceTypeLabel.of(step.type()))
                .collect(Collectors.joining(", "));
        return failedOrBlocked.size() + " of " + report.steps().size()
                + " provisioning steps failed: " + labels;
    }

    private static boolean failedOrBlocked(ProvisioningStepResult step) {
        return step.outcome() == ProvisioningOutcome.FAILED || step.outcome() == ProvisioningOutcome.BLOCKED;
    }
}
```
- `Terminal` is a `spring-shell-starter`-provided bean (`org.jline.terminal.Terminal`); constructor
  injection via the existing `@RequiredArgsConstructor` (Lombok) requires no extra annotation.
- `CommandFailedException` is unchanged (single `String`-message constructor, package-private).
- Concise message shape: `"<failed+blocked count> of <total steps> provisioning steps failed: <comma-joined labels>"`.
  Example (1 step, DASHBOARD FAILED): `"1 of 1 provisioning steps failed: Dashboard"`.

---

## 7. Security (cross-cutting, no new endpoints/roles)

This feature has no HTTP controller and no new authn/authz surface — the CLI is a local, operator-run
tool. The two security-relevant changes are:
- **Secret handling:** the Notion Bearer token never appears in any string representation of
  `NotionProperties` (§2); `NotionClient` still sends it only in the `Authorization` header.
- **Error-detail sanitization:** `CreateWorkspaceService.safeDetail(...)` (§5.3) is the single choke
  point where downstream exception text is classified before reaching operator-facing output; no
  other code path may call `e.getMessage()` on a caught step exception.

---

## 8. Configuration

No new `application.yml` keys, no new profiles, no new Flyway migrations. `notion.token`,
`notion.version`, `notion.root-parent-page-id` are unchanged in shape and binding (`@ConfigurationProperties(prefix = "notion")`, `@NotBlank` on all three).

---

## 9. Test plan — write these first (TDD, ordered)

Run everything under `mvn test` unless marked **[Testcontainers]**. Nothing in this remediation
requires Testcontainers; the new shell test is explicitly designed to avoid it (§9.6).

### 9.1 `NotionPropertiesTest` (FR-1 / ADR-0010) — fast path
1. `toString_redactsTokenToFixedMarker` — `new NotionProperties("secret-token", "2026-03-11", "root-id").toString()` → `.contains("****")`, `.doesNotContain("secret-token")`.
2. `toString_stillRendersVersionAndRootParentPageId` — same instance → `.contains("2026-03-11")`, `.contains("root-id")`.
3. Existing 4 context-binding tests (`contextFails_whenTokenBlank`, etc.) unchanged — must still pass.

### 9.2 `NotionProvisioningAdapterTest` (FR-5 / ADR-0012) — fast path, `MockRestServiceServer`
4. `findRootByIdentity_treatsNullResultsAsEmptyPage` — respond `{"has_more":false,"next_cursor":null}` (no `results` key) to `/search` → `adapter.findRootByIdentity(SHAPE)` returns `Optional.empty()`, no exception.
5. `findRootByIdentity_throwsWhenSearchExceedsPageCap` — register `MAX_SEARCH_PAGES` (50) `/search` expectations each returning `has_more:true` with the **same** `next_cursor` (e.g. `"cursor-1"`) — assert `assertThatThrownBy(() -> adapter.findRootByIdentity(SHAPE)).isInstanceOf(NotionApiException.class).hasMessageContaining("page cap")`, then `server.verify()` to confirm exactly 50 requests were made (no 51st).

### 9.3 `NotionProvisioningAdapterDatabaseTest` (FR-2 / ADR-0011, FR-5 / ADR-0012) — fast path
6. `verify_throwsNotionApiExceptionWhenDataSourcesEmpty` — `databaseJson` with `"data_sources": []` → `assertThatThrownBy(() -> adapter.verify("db-id", PROJECTS_DB, EXPECTED_SHAPE)).isInstanceOf(NotionApiException.class).hasMessageContaining("no data source")`.
7. `verify_throwsNotionApiExceptionWhenDataSourcesAbsent` — `databaseJson` with the `data_sources` key omitted (null) → same assertion.
8. `verify_throwsNotionApiExceptionWhenDataSourceLookupReturns404` — `/databases/db-id` returns valid shape with `data_sources:[{"id":"ds-1"}]`; `/data_sources/ds-1` responds `404` (so `client.get` returns `null`) → `NotionApiException`, message contains `"data source unavailable"`.
9. `repairShape_throwsNotionApiExceptionWhenDataSourcesEmpty` — same as #6 but through `repairShape`.
10. `repairShape_throwsNotionApiExceptionWhenDataSourceLookupReturns404` — same as #8 but through `repairShape`.
11. `findChildByIdentity_treatsNullResultsAsEmptyPage` — `/blocks/dashboard-id/children` responds `{"has_more":false,"next_cursor":null}` (no `results`) → `Optional.empty()`, no NPE.
12. `findChildByIdentity_throwsWhenSearchExceedsPageCap` — 50 repeated `/blocks/.../children` responses with `has_more:true` and a fixed `next_cursor` → `NotionApiException` mentioning the page cap; `server.verify()` confirms exactly 50 calls.
- Existing tests in this file (createDatabase, repairShape happy paths, findChildByIdentity ambiguity, etc.) must pass unmodified — confirms FR-2/FR-5/FR-6 are non-regressive (NFR-4).

### 9.4 `NotionClientTest` (FR-7) — fast path
- No new tests required; all 4 existing tests must pass unmodified (behavior-preserving constant substitution).

### 9.5 `CreateWorkspaceServiceTest` (AUD-004 / ADR-0013) — fast path
13. `execute_surfacesNotionApiExceptionMessageVerbatimOnStepFailure` — stub `createTasksDatabase.execute(any())` to throw `new NotionApiException("no data source on Tasks")`; run `service.execute(command(false))`; assert the `TASKS_DB` step's `detail()` **equals** `"no data source on Tasks"`.
14. `execute_collapsesUnexpectedExceptionToGenericSafeDetail` — stub `createTasksDatabase.execute(any())` to throw `new IllegalStateException("jdbc://internal-host:5432 connection refused")`; assert the `TASKS_DB` step's `detail()` equals `"internal error during TASKS_DB provisioning (see server logs)"` and `.doesNotContain("jdbc")` / `.doesNotContain("internal-host")`.
15. `execute_logsRawCauseAtErrorForFailedStep` — attach a Logback `ListAppender<ILoggingEvent>` to `LoggerFactory.getLogger(CreateWorkspaceService.class)` (already on the test classpath via `spring-boot-starter-test`); stub a step to throw `new IllegalStateException("jdbc://internal-host:5432 connection refused")`; run `execute`; assert one `ERROR`-level event exists whose formatted message contains `"TASKS_DB"` and whose throwable is the original `IllegalStateException` with the raw message `"jdbc://internal-host:5432 connection refused"`.
- Existing `execute_mapsThrownExceptionFromAStepToFailedResult` (uses `UnsupportedOperationException("x")`, asserts `detail()).contains("x")`) must still pass — confirms the allowlist keeps `UnsupportedOperationException` passthrough (NFR-4).
- All other existing `CreateWorkspaceServiceTest` cases unchanged.

### 9.6 `WorkspaceCommandsTest` (FR-8, AUD-005 / ADR-0014, ADR-0015) — fast path, Mockito unit test
Constructor now takes `(CreateWorkspaceUseCase, Terminal)`; add `@Mock private Terminal terminal;` to
the class and pass it to every `new WorkspaceCommands(...)` call.

16. `create_invokesUseCaseWithParsedArguments` — **unchanged assertions**, updated constructor call only.
17. `create_rendersAllStepsOnSuccess` — **update assertions**: `.contains("Dashboard").contains("CREATED")` and `.contains("Tasks").contains("RECONCILED")` (was raw enum names `"DASHBOARD"`/`"TASKS_DB"` — now labels per ADR-0014).
18. `create_rendersHumanReadableLabelsNotRawEnumConstants` (**new**) — success report containing a `TASKS_DB` step → rendering `.contains("Tasks")` and `.doesNotContain("TASKS_DB")`.
19. `create_signalsFailureWhenReportFailed` — **extend**: report with one `DASHBOARD` `FAILED` step, detail `"boom"` → `assertThatThrownBy(...).isInstanceOf(CommandFailedException.class).hasMessage("1 of 1 provisioning steps failed: Dashboard")` (concise, single-line — not the full report body).
20. `create_writesFullReportToTerminalOnFailureBeforeThrowing` (**new**) — stub `terminal.writer()` to return a `new PrintWriter(new StringWriter())` captured in a local variable; trigger the failure path; catch the thrown `CommandFailedException`; assert the underlying `StringWriter`'s buffer `.contains("Dashboard: FAILED (boom)")` — i.e., the full multi-line report reached output even though the method threw.
- **Delete** `create_defaultsSampleDataToFalseWhenOmitted` (FR-4 / ADR-0016) — it calls
  `commands.create("Personal", personId, false)` with a Java literal `false`, never exercising
  `@Option(defaultValue = "false")`. Replaced by shell-level test #23 below.

### 9.7 `WorkspaceCommandsRegistrationTest` (FR-4 / ADR-0016) — fast path
21. **Delete** `applicationComposesCliViaCommandScan` — reflective `isAnnotationPresent(CommandScan.class)` tautology; proves nothing about runtime behavior.
22. Keep `commandScan_discoversAndDependencyInjectsWorkspaceCommands`, **adding** a `Terminal` bean to `ScanConfig` (now required by `WorkspaceCommands`'s constructor):
    ```java
    @Bean
    Terminal terminal() {
        return Mockito.mock(Terminal.class);
    }
    ```

### 9.8 `WorkspaceCommandsShellTest` (**NEW**, FR-3 / ADR-0016) — fast path, `@ShellTest`, no Testcontainers
Package: `infrastructure.adapter.cli`. Boots the real `@CommandScan` composition via a **nested**
`@Configuration` class (mirrors the existing `WorkspaceCommandsRegistrationTest.ScanConfig` pattern)
so `@ShellTest`'s `SpringBootTestContextBootstrapper` uses this narrow config instead of
auto-detecting `LifeOsApplication` (which would pull in JPA/DataSource autoconfiguration and defeat
the hermetic-test goal). `@ShellTest` supplies its own test `Terminal`/`ShellTestClient` — no manual
`Terminal` bean needed here.

```java
@ShellTest
class WorkspaceCommandsShellTest {

    @Configuration
    @CommandScan(basePackageClasses = WorkspaceCommands.class)
    static class ShellTestConfig {
    }

    @Autowired
    private ShellTestClient client;

    @MockBean
    private CreateWorkspaceUseCase createWorkspace;

    // test methods below
}
```

23. `create_rejectsMissingRequiredNameOption` (FR-3a) — `client.nonInterative("workspace", "create", "--person-id", UUID.randomUUID().toString()).run()`; assert the use case is **never** invoked: `verifyNoInteractions(createWorkspace)`; assert the session's screen reflects a parse failure (missing required option) — confirm the exact `ShellScreen`/`ShellTestClient` assertion call against `spring-shell-test:3.3.2` during implementation (e.g. screen text mentioning the missing `--name` option).
24. `create_rejectsMissingRequiredPersonIdOption` (FR-3a) — same shape, omitting `--person-id` instead.
25. `create_resolvesSampleDataDefaultToFalseWhenOptionOmitted` (FR-3b) — stub `createWorkspace.execute(any())` to return a report with one `CREATED` `DASHBOARD` step; run `client.nonInterative("workspace", "create", "--name", "Personal", "--person-id", <uuid>).run()` (no `--sample-data`); capture the invocation with `ArgumentCaptor<CreateWorkspaceCommand>`; assert `captor.getValue().sampleData()` is `false` — resolved by the **shell's** `@Option(defaultValue = "false")`, not a Java literal.
26. `create_signalsFailureExitWhenReportFailed` (FR-3c) — stub `createWorkspace.execute(any())` to return a report with one `FAILED` `DASHBOARD` step, detail `"boom"`; run the command through `client`; assert `createWorkspace.execute(any())` was invoked, and assert the shell surfaces the failure (per Spring Shell's documented exception→exit-code integration: the thrown `CommandFailedException` produces a non-zero/error outcome visible via the session's result/screen — confirm exact `ShellTestClient`/`ShellAssertions` API during implementation) and that the screen contains the concise summary text `"1 of 1 provisioning steps failed: Dashboard"`.

**Why this stays on the fast path:** the nested `@Configuration @CommandScan(basePackageClasses = WorkspaceCommands.class)` class is the *only* configuration `@ShellTest` discovers (Spring Boot's test context bootstrapper prefers a nested `@Configuration` over package-scanning up to `LifeOsApplication`), so no `@SpringBootApplication` component scan, no JPA, no `DataSource`, no Notion adapter, and no network I/O are ever wired. `@MockBean CreateWorkspaceUseCase` supplies the only collaborator `WorkspaceCommands` needs.

### 9.9 Full-suite regression (NFR-5)
27. `mvn test` — all fast-path unit/slice tests above plus every pre-existing green test pass.
28. `mvn verify` **[Testcontainers]** — the pre-existing `*ServiceIT`/`*IT` Postgres-backed tests (`CreateDashboardServiceIT`, `CreateWorkspaceIT`, etc.) are untouched by this remediation and must remain green; this feature adds no new Testcontainers-dependent test.

---

## 10. Traceability

| Requirement | Component | ADR | Test(s) |
|---|---|---|---|
| FR-1 / NFR-1 | `NotionProperties#toString` | ADR-0010 | §9.1 #1–#2 |
| FR-2 / NFR-2 | `NotionProvisioningAdapter#verify`, `#repairShape` guards | ADR-0011 | §9.3 #6–#10 |
| FR-3 / NFR-3 | `WorkspaceCommandsShellTest` (new) | ADR-0016 | §9.8 #23–#26 |
| FR-4 / NFR-3 | Deletion of tautological/mislabelled tests | ADR-0016 | §9.6 (delete), §9.7 #21 |
| FR-5 / NFR-2 | `NotionProvisioningAdapter` pagination cap + `nullSafe` | ADR-0012 | §9.2 #4–#5, §9.3 #11–#12 |
| FR-6 | `createDatabase` local rename | — (trivial) | compile + existing `NotionProvisioningAdapterDatabaseTest` green |
| FR-7 | `NotionClient` named status constants | — (trivial) | existing `NotionClientTest` green |
| FR-8 | `WorkspaceCommands#create` concise exception + report-to-output | ADR-0015 | §9.6 #19–#20 |
| AUD-004 | `CreateWorkspaceService#safeDetail` + logger; `SafeToSurfaceException` marker (§0) | ADR-0013 | §9.5 #13–#15 |
| AUD-005 | `ResourceTypeLabel` (CLI) | ADR-0014 | §9.6 #17–#18 |
| NFR-4 (no regression) | all of the above | all | existing tests listed as "must pass unmodified" in each subsection |
| NFR-5 (no regression, full suite) | all | all | §9.9 #27–#28 |

---

## 11. Note back to the Architect (informational, not blocking)

None required — ADR-0013's coupling note explicitly delegated the `SafeToSurfaceException` vs.
`instanceof NotionApiException` choice to this stage (§0), and every other seam in the architecture
maps onto the actual source without contradiction. No escalation raised.

---

## 12. Implementation notes

One deviation from §9.8 as written, everything else implemented per spec. Files created/changed
below (paths relative to `backend/src/`).

**Deviation (§9.8):** `spring-shell-test:3.3.2` (the version pinned by this project) does not
contain a `@ShellTest` annotation — verified by inspecting the jar: it holds only
`ShellTestClient`/`ShellScreen`/`ShellScreenAssert`/`ShellAssertions` classes, no annotation class,
and `spring-shell-autoconfigure:3.3.2` registers no auto-configuration for them. `@ShellTest` is
not usable as specified. Faithful equivalent implemented: `WorkspaceCommandsShellTest` boots the
same `@CommandScan`-discovered composition through an explicit `AutoConfigurations.of(...)` import
of the individual `spring-shell-autoconfigure` classes (`SpringShellAutoConfiguration`,
`JLineAutoConfiguration`, `JLineShellAutoConfiguration`, `CompleterAutoConfiguration`,
`CommandCatalogAutoConfiguration`, `ShellContextAutoConfiguration`, `ExitCodeAutoConfiguration`,
`ParameterResolverAutoConfiguration`, `StandardAPIAutoConfiguration`, `UserConfigAutoConfiguration`)
via `ApplicationContextRunner`, and drives the real `Shell` through `NonInteractiveShellRunner` —
the same classes `ShellTestClient.nonInterative(...)` delegates to internally — with a dumb JLine
`Terminal` backed by a captured byte buffer standing in for `ShellScreen`. Empirically (this
composition, like the real app, registers no `CommandExceptionResolver`), parser/command exceptions
propagate uncaught out of `NonInteractiveShellRunner.run(...)`, so #23/#24/#26 assert directly on
the thrown exception (`CommandExecution.CommandParserExceptionsException` for missing required
options, `CommandFailedException` for a failed report) rather than reading a rendered screen; #26
additionally asserts the pre-throw terminal buffer contains the full report line, and #25 asserts
`sampleData()` via an `ArgumentCaptor` exactly as specified. All four run on the fast `mvn test`
path with no Testcontainers. Still fully exercises the real command-dispatch pipeline (`@Option`
required/defaultValue binding) the spec's FR-3/FR-3a/FR-3b/FR-3c intended to cover.

- `infrastructure/adapter/notion/NotionProperties.java` — added `toString()` override redacting the token (§2).
- `application/usecase/workspace/SafeToSurfaceException.java` — new marker interface (§0, §5.1).
- `infrastructure/adapter/notion/NotionApiException.java` — implements `SafeToSurfaceException` (§5.2).
- `application/usecase/workspace/CreateWorkspaceService.java` — added logger + `safeDetail(...)` classification in `runStep(...)` (§5.3).
- `infrastructure/adapter/notion/NotionClient.java` — named `HttpStatus`/`NOTION_OVERLOADED` constants replacing magic numbers 404/429/529 (§4).
- `infrastructure/adapter/notion/NotionProvisioningAdapter.java` — added `requirePrimaryDataSourceId`/`requireDataSource`/`nullSafe` guards, `MAX_SEARCH_PAGES` bounded pagination in `findRootByIdentity`/`findChildByIdentity`, `propertyConfigs` rename in `createDatabase` (§3).
- `infrastructure/adapter/cli/ResourceTypeLabel.java` — new presentation-only enum→label map (§6.1).
- `infrastructure/adapter/cli/WorkspaceCommands.java` — constructor now takes `Terminal`; labels via `ResourceTypeLabel`; writes full report to terminal and throws a concise `CommandFailedException` on failure (§6.2).
- `infrastructure/adapter/notion/NotionPropertiesTest.java` — added `toString_redactsTokenToFixedMarker`, `toString_stillRendersVersionAndRootParentPageId`.
- `infrastructure/adapter/notion/NotionProvisioningAdapterTest.java` — added `findRootByIdentity_treatsNullResultsAsEmptyPage`, `findRootByIdentity_throwsWhenSearchExceedsPageCap`.
- `infrastructure/adapter/notion/NotionProvisioningAdapterDatabaseTest.java` — added the 7 guard/pagination-cap test cases (§9.3 #6–#12) plus `databaseJsonWithDataSources`/`databaseJsonWithoutDataSources` helpers.
- `application/usecase/workspace/CreateWorkspaceServiceTest.java` — added `execute_surfacesNotionApiExceptionMessageVerbatimOnStepFailure`, `execute_collapsesUnexpectedExceptionToGenericSafeDetail`, `execute_logsRawCauseAtErrorForFailedStep` (Logback `ListAppender`).
- `infrastructure/adapter/cli/WorkspaceCommandsTest.java` — constructor now takes `(CreateWorkspaceUseCase, Terminal)`; label assertions updated; deleted `create_defaultsSampleDataToFalseWhenOmitted`; added `create_rendersHumanReadableLabelsNotRawEnumConstants`, `create_writesFullReportToTerminalOnFailureBeforeThrowing`; `create_signalsFailureWhenReportFailed` now asserts the concise message.
- `infrastructure/adapter/cli/WorkspaceCommandsRegistrationTest.java` — deleted tautological `applicationComposesCliViaCommandScan`; added a `Terminal` bean to `ScanConfig`.
- `infrastructure/adapter/cli/WorkspaceCommandsShellTest.java` — new (see deviation note above); 4 tests, fast path, no Testcontainers.
