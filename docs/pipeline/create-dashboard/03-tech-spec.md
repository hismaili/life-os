# 03 — Technical Specification: Create Dashboard (Phase A — root Notion page)

Status: Ready for Implementer
Owner (SME stage): pipeline automation
Input: `docs/pipeline/create-dashboard/02-architecture.md` + `adr/ADR-0001..0004` (all Accepted) + `docs/pipeline/create-dashboard/01-spec.md` + `02-open-questions.md` (all resolved) + existing code under `backend/src/main/java/com/lifeos/`.
Grounding skills applied: `spring-boot-conventions`, `spring-data-jpa` (N/A — no schema change), `spring-security`, `spring-testing`.

> This spec is mechanical: signatures, annotations, request/response JSON, and an ordered TDD task list. It does not introduce any design decision not already made in `02-architecture.md`/the ADRs. This is the **first** real Notion-integration implementation in the project — every prior provisioning step stops at `UnsupportedOperationException`. §11 records the one clarification-style note back to the Architect; there is no schema/migration change (no new table, no new column — the Dashboard's Notion id lives in the existing `provisioned_resources` row of type `DASHBOARD`).

---

## 1. Package layout

```
com.lifeos
 ├─ application
 │   ├─ port/
 │   │    NotionProvisioningPort.java            [EXTEND — refine createRootPage, add verifyPage/repairPage/findRootByIdentity]
 │   │    PageShape.java                          [NEW] (record)
 │   │    ParentConstraint.java                   [NEW] (enum)
 │   │    ExpectedShape.java                       (unchanged — databases)
 │   │    VerificationResult.java                  (unchanged — reused by the page slice)
 │   │
 │   └─ usecase.workspace/
 │        CreateDashboardUseCase.java              (unchanged — ProvisioningStepResult execute(UUID))
 │        CreateDashboardService.java              [EXTEND — 3-arg constructor, real algorithm, remove stub throw]
 │        WorkspaceLedgerWriter.java                (unchanged)
 │
 └─ infrastructure
     └─ adapter.notion/
          NotionProvisioningAdapter.java           [EXTEND — implement page slice only]
          NotionClient.java                        [NEW] (adapter-internal, package-private)
          NotionApiException.java                  [NEW] (unchecked, adapter-owned)
          NotionProperties.java                    [EXTEND — + version, + rootParentPageId]
          dto/ (adapter-internal Notion JSON records — package-private, never leave adapter.notion)
              NotionPageResponse.java               [NEW]
              NotionSearchResponse.java              [NEW]
              NotionErrorResponse.java                [NEW]
```

No change to `domain.workspace` (no schema/entity change), no change to `infrastructure.adapter.persistence`, no change to `infrastructure.adapter.web`/`adapter.cli` (the controller/CLI already render whatever `ProvisioningOutcome` the orchestrator returns — `DASHBOARD` outcomes flow through the existing `ProvisioningReportResponse` unchanged). Package-by-feature preserved (`spring-boot-conventions`).

---

## 2. `application.port` — new/changed value types

### 2.1 `PageShape` [NEW] — record

```java
package com.lifeos.application.port;

public record PageShape(String title, ParentConstraint parent) {
    public PageShape {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be null or blank");
        }
        if (parent == null) {
            throw new IllegalArgumentException("parent must not be null");
        }
    }
}
```

### 2.2 `ParentConstraint` [NEW] — enum

```java
package com.lifeos.application.port;

public enum ParentConstraint { ROOT_PARENT }
```

Single value today by design (YAGNI, architecture §5.4) — the adapter resolves `ROOT_PARENT` to the configured `NotionProperties.rootParentPageId()`; the concrete Notion id never appears in `application`.

### 2.3 `NotionProvisioningPort` [EXTEND]

Only these four members change; every other method (`verify`, `findChildByIdentity`, `createDatabase`, `repairShape`, `ensureRelation`, `ensureRollup`, `ensureFormula`, `hasSampleRecords`, `insertSampleRecords`) is **byte-for-byte unchanged**:

```java
package com.lifeos.application.port;

import com.lifeos.domain.workspace.ProvisionedResourceType;
import java.util.List;
import java.util.Optional;

public interface NotionProvisioningPort {

    VerificationResult verify(String rootPageId, ProvisionedResourceType type, ExpectedShape expected); // unchanged

    Optional<String> findChildByIdentity(String rootPageId, ProvisionedResourceType type); // unchanged — NOT used for Dashboard

    String createRootPage(PageShape expected);                       // REFINED — was createRootPage(String workspaceName)

    VerificationResult verifyPage(String pageId, PageShape expected); // [NEW]

    void repairPage(String pageId, PageShape expected);                // [NEW]

    Optional<String> findRootByIdentity(PageShape expected);          // [NEW]

    String createDatabase(String rootPageId, DatabaseSpec spec);       // unchanged

    void repairShape(String notionId, ExpectedShape expected);         // unchanged — NOT used for Dashboard

    void ensureRelation(RelationSpec spec);                            // unchanged

    void ensureRollup(RollupSpec spec);                                // unchanged

    void ensureFormula(FormulaSpec spec);                              // unchanged

    boolean hasSampleRecords(String databaseId);                       // unchanged

    void insertSampleRecords(String databaseId, List<RecordSpec> records); // unchanged
}
```

`createRootPage`'s only caller across the whole codebase is `CreateDashboardService` — grep `createRootPage(` before editing to confirm no other call site exists; if one does, that is a stop-the-line finding back to the Architect, not a silent extra edit.

---

## 3. `application.usecase.workspace.CreateDashboardService` [EXTEND]

### 3.1 Constructor / fields

```java
package com.lifeos.application.usecase.workspace;

@Service
@RequiredArgsConstructor
public class CreateDashboardService implements CreateDashboardUseCase {

    private final NotionProvisioningPort notion;
    private final WorkspaceRepository workspaceRepository;   // [NEW dependency] read-only: name + ledger hint
    private final WorkspaceLedgerWriter ledger;               // existing — the ONLY transactional write path

    @Override
    public ProvisioningStepResult execute(UUID workspaceId) { ... } // §3.3

    static String dashboardTitle(Workspace w) {                // package-private for testability; single source of truth (OQ-3)
        return "LifeOS — " + w.name();
    }
}
```

`CreateDashboardService` becomes 3-arg. This is a breaking constructor change — `CreateDashboardServiceTest` (currently 2-arg, asserting the stub throw) must be rewritten (§9.1).

No `@Transactional` anywhere on this class or `execute` (architecture §4.4) — a reflection test pins this (§9.1, mirrors Create Workspace's `execute_isNotAnnotatedTransactional` pattern).

### 3.2 `dashboardTitle`

`"LifeOS — " + workspace.name()` — exactly this format, em dash `—` (U+2014) surrounded by single spaces, no other transformation (no trimming/casing beyond what `Workspace.name()` already holds). This value feeds `createRootPage`, `verifyPage`, `repairPage`, and `findRootByIdentity` identically so they can never diverge (architecture §5.4). Called exactly once per `execute` invocation and the resulting `PageShape` is reused for every subsequent port call in that run — do not recompute mid-algorithm.

### 3.3 `execute(UUID workspaceId)` — algorithm

Concrete branch-by-branch restatement of architecture §4.1 / §4.2. Pseudocode (not literal Java — the Implementer writes idiomatic Java from this):

```
workspace = workspaceRepository.findById(workspaceId)
                .orElseThrow(() -> new IllegalStateException("Workspace not found: " + workspaceId))
        // FR-2 — no Notion call before this line

shape = new PageShape(dashboardTitle(workspace), ParentConstraint.ROOT_PARENT)
ledgerId = workspace.resource(DASHBOARD).map(ProvisionedResource::notionId)   // Optional<String>

if ledgerId.isPresent():
    result = notion.verifyPage(ledgerId.get(), shape)
    switch (result):
        case PRESENT_MATCHING:
            return new ProvisioningStepResult(DASHBOARD, RECONCILED, null)   // no write, no record()

        case PRESENT_DRIFTED:
            notion.repairPage(ledgerId.get(), shape)
            ledger.record(workspaceId, DASHBOARD, ledgerId.get())
            return new ProvisioningStepResult(DASHBOARD, REPAIRED, "renamed/moved page repaired")

        case ABSENT:
            found = notion.findRootByIdentity(shape)                         // re-adopt before re-create
            if found.isPresent():
                ledger.record(workspaceId, DASHBOARD, found.get())
                return new ProvisioningStepResult(DASHBOARD, REPAIRED, "ledger id was stale; re-adopted existing page")
            else:
                newId = notion.createRootPage(shape)
                ledger.record(workspaceId, DASHBOARD, newId)
                return new ProvisioningStepResult(DASHBOARD, REPAIRED, "ledger id was stale; page recreated")

else:  // cold path — no ledger entry
    found = notion.findRootByIdentity(shape)
    if found.isEmpty():
        newId = notion.createRootPage(shape)
        ledger.record(workspaceId, DASHBOARD, newId)
        return new ProvisioningStepResult(DASHBOARD, CREATED, null)
    else:
        orphanId = found.get()
        // findRootByIdentity already filtered to title+parent match; a second verifyPage call
        // is NOT required to decide matching vs drifted for the *identity* predicate itself,
        // BUT the adapter's findRootByIdentity contract (§5) guarantees a returned id already
        // satisfies title+parent — so a cold-path orphan hit is always "matching" by construction.
        ledger.record(workspaceId, DASHBOARD, orphanId)
        return new ProvisioningStepResult(DASHBOARD, RECONCILED, null)
```

Read the note under §5.3 (`findRootByIdentity` contract) before implementing the cold-path branch: because `findRootByIdentity` only ever returns an id that **already matches** title + parent (or nothing, or throws on >1 match), the cold-path "found" case is unconditionally `RECONCILED` per the decision table row `absent / orphan, title matches, under root parent / none / RECONCILED`. There is **no** cold-path "orphan found, drifted" case reachable through `findRootByIdentity` alone — ADR-0004's "adopted-but-drifted → REPAIRED" row is real but reached only via the **warm ABSENT** path re-adopting (`ledger id present, page gone, orphan found → REPAIRED`, which is `REPAIRED` because a *prior ledger record* existed, not because the found page itself drifted). Do not add a spurious extra `verifyPage` call on the cold path to manufacture a drifted case that cannot occur — that would be an extra, unbudgeted Notion call (NFR-7) and is out of scope.

Any exception thrown by `notion.*` (a `NotionApiException`, including the >1-match case) or by `workspaceRepository.findById` propagates **unmodified** out of `execute` — do not catch it, do not wrap it, do not construct a `FAILED` `ProvisioningStepResult` here (FR-9; the orchestrator's `runStep` does that mapping, architecture §4.3). `ledger.record(...)` is called **after** the Notion write in every branch that performs one — if `ledger.record` itself throws, that exception also propagates unmodified (the Notion-side effect stays; the next run's adoption path reconciles it, FR-10).

### 3.4 Outcome decision table (restated as the exact branch that produces it — traceability aid)

| # | Ledger id | `verifyPage`/`findRootByIdentity` result | Notion write | `ledger.record` called | Outcome | `detail` |
|---|---|---|---|---|---|---|
| 1 | absent | `findRootByIdentity` → empty | `createRootPage` | yes | `CREATED` | `null` |
| 2 | absent | `findRootByIdentity` → present | none | yes | `RECONCILED` | `null` |
| 3 | absent | `findRootByIdentity` → >1 match | — | no | *(propagates `NotionApiException`; orchestrator maps to `FAILED`)* | n/a |
| 4 | present | `verifyPage` → `PRESENT_MATCHING` | none | no | `RECONCILED` | `null` |
| 5 | present | `verifyPage` → `PRESENT_DRIFTED` | `repairPage` | yes | `REPAIRED` | non-blank |
| 6 | present | `verifyPage` → `ABSENT`, then `findRootByIdentity` → present | none | yes | `REPAIRED` | non-blank |
| 7 | present | `verifyPage` → `ABSENT`, then `findRootByIdentity` → empty | `createRootPage` | yes | `REPAIRED` | non-blank |
| 8 | present | `verifyPage` → `ABSENT`, then `findRootByIdentity` → >1 match | — | no | *(propagates; orchestrator maps to `FAILED`)* | n/a |

Row 3 and 8 are the same failure mode surfaced from two different entry points into `findRootByIdentity`; both are exercised as separate test cases because the calling context (cold vs warm-ABSENT) differs (§9.1).

---

## 4. `NotionProperties` [EXTEND]

```java
package com.lifeos.infrastructure.adapter.notion;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.NotBlank;

@ConfigurationProperties(prefix = "notion")
@Validated
public record NotionProperties(
        @NotBlank String token,
        @NotBlank String version,
        @NotBlank String rootParentPageId
) {}
```

`@Validated` + `@NotBlank` on a `@ConfigurationProperties` record makes a blank/missing value a **startup** failure (`ConfigurationPropertiesBindException` wrapping a `BindValidationException`), not a per-run `FAILED` step — this satisfies architecture §6 ("fail-fast at startup ... a misconfiguration is a boot error, not a per-run FAILED that masquerades as a Notion outage"). This requires `spring-boot-starter-validation` (or equivalently `jakarta.validation` + a validator) on the classpath — check `pom.xml`; if absent, add it as a `[NEW dependency]` (`org.springframework.boot:spring-boot-starter-validation`) and note the addition in the Implementer's PR description. Do **not** hand-roll a `@PostConstruct` blank-check as an alternative — `@Validated` is the idiomatic Spring Boot mechanism and is testable via `ApplicationContextRunner` without booting the full app (§9.2).

### 4.1 `application.yml`

```yaml
notion:
  token: ${NOTION_TOKEN:}
  version: ${NOTION_VERSION:2026-03-11}
  root-parent-page-id: ${NOTION_ROOT_PARENT_PAGE_ID:}
```

- `token` and `root-parent-page-id` have **no** safe default (empty string default triggers the `@NotBlank` startup failure when the env var is unset — this is intentional; a missing secret must fail fast, not silently boot with a blank token).
- `version` **does** have a concrete default (`2026-03-11`, matching the ADR-0001/architecture example) so local dev/tests do not need to set `NOTION_VERSION` explicitly, but it remains overridable — pinning is a deliberate config choice per environment (architecture §6).
- Relaxed binding maps `root-parent-page-id` (kebab-case in YAML) to `rootParentPageId` (camelCase in the record) automatically (Spring Boot relaxed binding) — no `@Name`/`@ConfigurationPropertiesBinding` needed.

---

## 5. `infrastructure.adapter.notion` — the page slice

### 5.1 `NotionClient` [NEW] — adapter-internal, package-private, not a Spring bean visible outside `adapter.notion`

```java
package com.lifeos.infrastructure.adapter.notion;

class NotionClient {

    private final RestClient restClient;

    NotionClient(NotionProperties properties, RestClient.Builder builder) {
        this.restClient = builder
                .baseUrl("https://api.notion.com/v1")
                .defaultHeader("Authorization", "Bearer " + properties.token())
                .defaultHeader("Notion-Version", properties.version())
                .defaultStatusHandler(HttpStatusCode::isError, this::handleError)
                .build();
    }

    <T> T post(String path, Object body, Class<T> responseType) { /* with 429/529 backoff, see 5.1.2 */ }
    <T> T get(String path, Class<T> responseType) { /* returns null-mapped Optional for 404 at call site; see verifyPage */ }
    <T> T patch(String path, Object body, Class<T> responseType) { ... }

    private void handleError(HttpRequest request, ClientHttpResponse response) throws IOException { ... } // maps to NotionApiException, §5.4
}
```

- `RestClient.Builder` is injected (not `RestClient.builder()` called directly) so `MockRestServiceServer.bindTo(RestClient.Builder)` can bind to it in tests (ADR-0003; Spring Framework Reference — Client-side REST test support). `NotionProvisioningAdapter` constructs `NotionClient` itself (not a separate `@Bean`) — see §5.2 wiring — but the constructor takes a `RestClient.Builder` parameter precisely so a test can hand it a bound builder.
- Concrete HTTP methods (`post`/`get`/`patch`) are private-ish helpers used only by the four page methods in §5.3-5.6; do not build a generic pass-through the rest of the adapter depends on yet (YAGNI — only the Dashboard slice is real this pass).
- `get` on a `404` response: the Notion API returns HTTP `404` with a JSON body `{"object":"error","status":404,"code":"object_not_found","message":"..."}` for `GET /v1/pages/{id}` on a deleted/never-existed/not-shared-with-integration page. `NotionClient` must **not** throw `NotionApiException` for this specific case when called from `verifyPage` — `verifyPage` needs to distinguish "404 → ABSENT" from "other error → throw". Recommended shape: `NotionClient` exposes a `get`-style method returning `Optional<NotionPageResponse>` (empty on 404, exception on any other error status) used only by `verifyPage`; other callers (`patch`, `post`) always throw on any error status via `handleError`. Document this asymmetry with a code comment at the method, since it is the one place a 4xx is *not* automatically fatal.

#### 5.1.1 429/529 backoff (NFR-8)

- On response status `429` or `529`: read the `Retry-After` header as an integer number of seconds (Notion's documented contract — [Notion — Request limits](https://developers.notion.com/reference/request-limits)); sleep that many seconds (e.g. `Thread.sleep(Duration.ofSeconds(retryAfter))` — this is a synchronous `RestClient`, blocking the calling thread is the accepted behavior, no reactive alternative in scope per ADR-0001); retry the same request.
- Bounded retry count: **3 attempts total** (1 initial + 2 retries) — a fixed, hardcoded constant in `NotionClient` (`private static final int MAX_ATTEMPTS = 3;`), not externalized to `NotionProperties` (YAGNI; no ADR calls for configurability here). After the 3rd failed attempt, throw `NotionApiException` with a message noting the retry exhaustion (no token, no raw body — §5.4).
- If `Retry-After` is missing or unparseable on a `429`/`529` response, treat it as `1` second (a safe, small default) rather than failing to parse — do not let a malformed header crash the retry loop itself.
- A single Dashboard run's own call budget is 1–3 Notion calls (architecture §5.3) — the retry loop only engages on an actual `429`/`529`, which is not the common case; do not let backoff logic run on every call.

### 5.2 `NotionProvisioningAdapter` [EXTEND] — wiring

```java
package com.lifeos.infrastructure.adapter.notion;

@Component
@EnableConfigurationProperties(NotionProperties.class)
public class NotionProvisioningAdapter implements NotionProvisioningPort {

    private final NotionProperties properties;
    private final NotionClient client;

    public NotionProvisioningAdapter(NotionProperties properties, RestClient.Builder restClientBuilder) {
        this.properties = properties;
        this.client = new NotionClient(properties, restClientBuilder);
    }

    // page slice — real (§5.3–5.6)
    @Override public String createRootPage(PageShape expected) { ... }
    @Override public VerificationResult verifyPage(String pageId, PageShape expected) { ... }
    @Override public void repairPage(String pageId, PageShape expected) { ... }
    @Override public Optional<String> findRootByIdentity(PageShape expected) { ... }

    // everything else — UNCHANGED stub bodies, byte-for-byte as they exist today
    @Override public VerificationResult verify(String rootPageId, ProvisionedResourceType type, ExpectedShape expected) {
        throw new UnsupportedOperationException("Notion verification not yet implemented: requires the Notion API client");
    }
    @Override public Optional<String> findChildByIdentity(String rootPageId, ProvisionedResourceType type) {
        throw new UnsupportedOperationException("Notion child lookup not yet implemented: requires the Notion API client");
    }
    @Override public String createDatabase(String rootPageId, DatabaseSpec spec) {
        throw new UnsupportedOperationException("Notion database creation not yet implemented: requires the Notion API client");
    }
    @Override public void repairShape(String notionId, ExpectedShape expected) {
        throw new UnsupportedOperationException("Notion shape repair not yet implemented: requires the Notion API client");
    }
    @Override public void ensureRelation(RelationSpec spec) {
        throw new UnsupportedOperationException("Notion relation creation not yet implemented: requires the Notion API client");
    }
    @Override public void ensureRollup(RollupSpec spec) {
        throw new UnsupportedOperationException("Notion rollup creation not yet implemented: requires the Notion API client");
    }
    @Override public void ensureFormula(FormulaSpec spec) {
        throw new UnsupportedOperationException("Notion formula creation not yet implemented: requires the Notion API client");
    }
    @Override public boolean hasSampleRecords(String databaseId) {
        throw new UnsupportedOperationException("Notion sample record lookup not yet implemented: requires the Notion API client");
    }
    @Override public void insertSampleRecords(String databaseId, List<RecordSpec> records) {
        throw new UnsupportedOperationException("Notion sample record insertion not yet implemented: requires the Notion API client");
    }
}
```

`RestClient.Builder` is injected as a constructor parameter (Spring Boot auto-configures a prototype `RestClient.Builder` bean when `spring-boot-starter-web` is present — no extra config needed) rather than the adapter calling `RestClient.builder()` itself, purely so `NotionProvisioningAdapterTest` can construct the adapter with a builder pre-bound to `MockRestServiceServer` (§9.2).

### 5.3 `createRootPage(PageShape expected)`

**Request**: `POST /v1/pages`

```json
{
  "parent": { "page_id": "<rootParentPageId>" },
  "properties": {
    "title": {
      "title": [ { "text": { "content": "<expected.title()>" } } ]
    }
  }
}
```

- `<rootParentPageId>` = `properties.rootParentPageId()` (the adapter resolves `ParentConstraint.ROOT_PARENT` here — the only place the concrete id is used).
- The `title` property key **must** be the literal string `"title"` (Notion pages created under a page parent always name their title property `title` — [Notion — Create a page](https://developers.notion.com/reference/post-page)); this is the only property Notion accepts at page-creation time under a page parent (architecture §5.3, OQ-5 — an empty titled placeholder, no other body/blocks).

**Response** (`200`): `{"id": "<uuid-with-dashes>", ...other fields ignored...}`. Map to `response.id()`.

**Error mapping**: any non-2xx → `NotionApiException` via `NotionClient.handleError` (§5.4). No special-case handling in this method beyond the client's default.

### 5.4 `verifyPage(String pageId, PageShape expected)`

**Request**: `GET /v1/pages/{pageId}`

**Response body fields consumed** (ignore everything else):
```json
{
  "id": "...",
  "archived": false,
  "in_trash": false,
  "parent": { "type": "page_id", "page_id": "<parent-page-id>" },
  "properties": {
    "title": { "title": [ { "plain_text": "<current title>" } ] }
  }
}
```

Decision logic (exact order):
1. `GET` returns HTTP `404` → `VerificationResult.ABSENT`. (`NotionClient`'s 404-tolerant `get` overload, §5.1, returns `Optional.empty()` here.)
2. `response.archived() == true` OR `response.inTrash() == true` → `VerificationResult.ABSENT`.
3. `response.parent().pageId()` does not equal `properties.rootParentPageId()` → `VerificationResult.PRESENT_DRIFTED`.
4. Joined `plain_text` of `properties.title.title[]` does not equal `expected.title()` (exact string match — no trim/case-fold) → `VerificationResult.PRESENT_DRIFTED`.
5. Otherwise → `VerificationResult.PRESENT_MATCHING`.

Any non-404 error status → `NotionApiException` (§5.5 below covers only the success/404 cases; a `401`/`403`/`5xx` on this call is a hard failure, not `ABSENT`).

### 5.5 `repairPage(String pageId, PageShape expected)`

Two possible Notion calls, issued conditionally based on **what drifted** — `repairPage` itself first re-checks by re-reading the same fields the adapter already fetched conceptually, but per the port contract it receives only `(pageId, expected)`, so it must (re-)discover what's wrong. Simplest correct implementation: **always issue both calls it needs**, decided from a fresh `GET` (or, to save a call, from state the caller already has — but the port signature doesn't pass that state in, so a `GET` inside `repairPage` is required unless the adapter internally re-derives it from the immediately-preceding `verifyPage`/`findRootByIdentity` call in the same adapter instance — **do not** attempt that co-ordination; treat `repairPage` as self-contained):

1. `GET /v1/pages/{pageId}` to determine current `archived`/`in_trash`/`parent`/`title` (same shape as §5.4).
2. If title differs from `expected.title()` **or** `archived`/`in_trash` is true: `PATCH /v1/pages/{pageId}`
   ```json
   {
     "properties": { "title": { "title": [ { "text": { "content": "<expected.title()>" } } ] } },
     "archived": false,
     "in_trash": false
   }
   ```
   (Setting `archived`/`in_trash` to `false` unconditionally in this PATCH is harmless when they were already `false` — Notion's update endpoint treats a same-value field as a no-op — [Notion — Update page](https://developers.notion.com/reference/patch-page).)
3. If `parent.page_id` differs from `properties.rootParentPageId()`: `POST /v1/pages/{pageId}/move`
   ```json
   { "parent": { "page_id": "<rootParentPageId>" } }
   ```
   (Move endpoint — [Notion — Move a page](https://developers.notion.com/reference/move-page), available since `Notion-Version: 2025-09-03`; `properties.version()` must be pinned to that date or later for this call to succeed — call out at startup only if feasible, otherwise a `400`/`validation_error` from Notion surfaces as `NotionApiException` and is a config bug, not a code bug.)
4. Step 2 and step 3 are independent — a drifted page may need one, the other, or both; issue only the calls the fresh `GET` in step 1 indicates are needed. (This keeps `repairPage` itself idempotent and within the NFR-7 budget — at most `GET` + `PATCH` + `POST /move` = 3 calls in the worst case, and the overall run budget in architecture §5.3 already accounts for "at most 2–3 calls" as an average, not an absolute per-run ceiling across every branch — do not over-optimize this method to shave a call at the cost of correctness.)
5. Return type is `void`; no response mapping. Any non-2xx from any of the up-to-3 calls → `NotionApiException`.

### 5.6 `findRootByIdentity(PageShape expected)`

**Request**: `POST /v1/search`

```json
{
  "query": "<expected.title()>",
  "filter": { "value": "page", "property": "object" }
}
```

**Response**: `{"results": [ { "id": "...", "parent": {"type":"page_id","page_id":"..."}, "properties": {"title": {"title": [{"plain_text": "..."}]}}, "archived": false, "in_trash": false }, ... ]}`

Filtering logic (exact order):
1. Deserialize `results[]`.
2. Discard any result where `archived == true` or `in_trash == true` (a trashed page is not a live orphan candidate).
3. Discard any result where `parent.page_id != properties.rootParentPageId()`.
4. Discard any result whose joined title `plain_text` does not **exactly** equal `expected.title()` (Notion's search `query` does substring/fuzzy matching server-side — [Notion — Search](https://developers.notion.com/reference/post-search) — so the adapter must still narrow client-side to an exact title match; this is what makes "duplicate titles" a real scenario the adapter must guard, OQ-2b).
5. After filtering: `0` remaining → `Optional.empty()`. `1` remaining → `Optional.of(thatId)`. `> 1` remaining → throw `NotionApiException` (message: e.g. `"Ambiguous Dashboard identity: N pages titled '<title>' found under the configured root parent"` — no token, no raw Notion response body verbatim, architecture §4.1/§4.2 last row).
6. Notion's `/v1/search` is paginated (`has_more`/`next_cursor`); for v0, a single page of results (default `page_size`) is sufficient — do not implement pagination traversal. This is a deliberate scope boundary: at v0 scale (one Dashboard per Workspace, one integration), pagination is not expected to matter; if it ever does, that is a future finding, not silently handled here.

### 5.7 `NotionApiException` [NEW]

```java
package com.lifeos.infrastructure.adapter.notion;

public class NotionApiException extends RuntimeException {
    public NotionApiException(String message) { super(message); }
    public NotionApiException(String message, Throwable cause) { super(message, cause); }
}
```

- Unchecked, adapter-owned (architecture §4.3, §8 finding 6). Package-private visibility is **not** used here — `NotionApiException` must be visible where `CreateDashboardServiceTest` and `CreateWorkspaceService.runStep` need to reference/catch it conceptually (they don't catch it by type — `runStep` catches `Exception` broadly — but tests assert on the exception type thrown by mocks), so it is `public`.
- **Message construction rule (NFR-6, load-bearing): build every message from HTTP status code + the Notion JSON error's `code`/`message` fields only.** Never interpolate a request header, the `Authorization` value, or the raw request/response body verbatim into the message. Example: `"Notion API error (status=401, code=unauthorized): API token is invalid."` — the `code`/`message` here are *values Notion sent back*, not our request; they are safe to surface. A dedicated adapter-internal `NotionErrorResponse` record (`{object, status, code, message}`) is deserialized from the error body precisely so the exception constructor only ever touches these four fields, never the raw body string or headers.
- `NotionClient.handleError` is the single place that constructs every `NotionApiException` thrown by the four page methods (aside from the >1-match case in `findRootByIdentity`, which the adapter itself throws after successfully parsing a 200 response) — centralizing this is what makes the "token never leaked" test (§9.2) a single-point guarantee rather than four repeated ones.

### 5.8 Adapter-internal JSON DTOs (`infrastructure.adapter.notion.dto`, package-private records)

Minimal fields only — do not model the full Notion page schema:

```java
record NotionPageResponse(
        String id,
        boolean archived,
        @JsonProperty("in_trash") boolean inTrash,
        NotionParent parent,
        Map<String, NotionTitleProperty> properties
) {}
record NotionParent(String type, @JsonProperty("page_id") String pageId) {}
record NotionTitleProperty(List<NotionRichText> title) {}
record NotionRichText(@JsonProperty("plain_text") String plainText) {}

record NotionSearchResponse(List<NotionPageResponse> results, boolean hasMore, String nextCursor) {}

record NotionErrorResponse(String object, int status, String code, String message) {}
```

Jackson (already on the classpath via `spring-boot-starter-web`) deserializes these directly; no custom `ObjectMapper` configuration is required beyond what Spring Boot auto-configures. Extracting the title string for comparison is `properties.get("title").title().stream().map(NotionRichText::plainText).collect(Collectors.joining())`.

---

## 6. Services / transaction boundaries — summary (no new collaborators beyond §3)

| Class | Transaction | Notes |
|---|---|---|
| `CreateDashboardService.execute` | **none** | Pure port-orchestration; a transaction here would hold a DB connection across a slow remote HTTP call (architecture §4.4). |
| `WorkspaceLedgerWriter.record` | `@Transactional` (existing, unchanged) | The **only** transactional write in this step. |
| `JpaWorkspaceRepository.findById` | `@Transactional(readOnly = true)` (existing, unchanged) | Read path for the `workspaceRepository.findById` call in §3.3. |
| `NotionProvisioningAdapter.*` / `NotionClient.*` | none | Not a Spring-managed transactional resource; pure HTTP. |

No new `@Service`/`@Component`/`@Repository` bean is introduced beyond `NotionProvisioningAdapter`'s existing `@Component` (unchanged annotation) and the adapter-internal, non-bean `NotionClient` (plain `new NotionClient(...)` inside the adapter constructor, §5.2 — it is **not** annotated `@Component` and is **not** independently injectable; this keeps it invisible to the application layer per hexagonal layering).

---

## 7. Controllers / CLI

**No changes.** `WorkspaceController` and `WorkspaceCommands` already render whatever `ProvisioningStepResult`/`ProvisioningReport` the orchestrator returns, generically, by `type`/`outcome` — a `DASHBOARD` step reaching `CREATED`/`RECONCILED`/`REPAIRED`/`FAILED` flows through the existing `201`/`200`/`502` mapping (Create Workspace ADR-0008/§8.1) without any new code. Do not touch `WorkspaceController.java`, `WorkspaceCommands.java`, `ApiExceptionHandler.java`, or any web/CLI DTO.

---

## 8. Security

- **Token handling**: unchanged mechanism (`NotionProperties.token()`, sourced from `NOTION_TOKEN` env var, bound once at startup) — see §4. `NotionClient` is the **only** class that reads `properties.token()`, and only to set the `Authorization` header on the shared `RestClient`. No other class in `adapter.notion` touches the raw token value.
- **Token-never-leaked (NFR-6)** is enforced structurally: `NotionApiException` messages are built exclusively from status code + Notion's own `code`/`message` fields (§5.7); logging (per §3.3/architecture §6) logs `workspaceId`, prior ledger id, `VerificationResult`, acted-on Notion id, and outcome — never a header map, never a raw request/response body. A dedicated test (§9.2) asserts a simulated `401` response's exception message does not contain the configured token string.
- **`rootParentPageId` is not a secret** (it's a page id, not a credential) but is still bound via `NotionProperties`/env var rather than hardcoded, for the same "config is profile-driven" reason as the token (`spring-boot-conventions`).
- **No REST/CLI authn change** — out of scope, unchanged from Create Workspace (§9, that spec).
- **No OAuth, no per-Person token** — explicitly out of scope (§10 below).

---

## 9. Test plan (write first — TDD)

Narrowest-sufficient tier per class (`spring-testing`, ADR-0003). Build/verify order: value types → service (mocked port) → adapter contract (`MockRestServiceServer`) → properties validation → optional wiring IT. No live Notion, no token, no network egress anywhere in this plan.

### 9.1 `CreateDashboardServiceTest` (plain unit, Mockito) — `application.usecase.workspace`

Mocks: `NotionProvisioningPort notion`, `WorkspaceRepository workspaceRepository`, `WorkspaceLedgerWriter ledger`. Fixture: a `Workspace` with `name = "Personal"` (so `dashboardTitle` = `"LifeOS — Personal"`) via `Workspace.reconstitute(...)` or `Workspace.create(...)`, both empty and pre-loaded with a `DASHBOARD` ledger entry as needed per test.

One test per §3.4 decision-table row, plus scope/failure paths:

1. `execute_throwsWhenWorkspaceNotFound` — `workspaceRepository.findById` empty → `IllegalStateException("Workspace not found: " + id)`; `verifyNoInteractions(notion)`, `verifyNoInteractions(ledger)` (FR-2).
2. `execute_createsWhenNoLedgerAndNoOrphan` — no `DASHBOARD` resource; `findRootByIdentity` → `Optional.empty()`; `createRootPage` → `"new-id"`; assert `createRootPage` called once with a `PageShape` whose `title()` equals `"LifeOS — Personal"` and `parent() == ROOT_PARENT`; `ledger.record(workspaceId, DASHBOARD, "new-id")` called once; outcome `CREATED`, `detail` null/blank-acceptable (row 1).
3. `execute_reconcilesWhenLedgerPresentAndMatching` — `DASHBOARD` resource present with id `"existing-id"`; `verifyPage("existing-id", shape)` → `PRESENT_MATCHING`; assert **no** `createRootPage`/`repairPage`/`findRootByIdentity` call, **no** `ledger.record` call; outcome `RECONCILED` (row 4).
4. `execute_repairsWhenLedgerPresentAndDrifted` — `verifyPage` → `PRESENT_DRIFTED`; assert `repairPage("existing-id", shape)` called once, `ledger.record(workspaceId, DASHBOARD, "existing-id")` called once, **no** `createRootPage`/`findRootByIdentity`; outcome `REPAIRED` (row 5).
5. `execute_reCreatesWhenLedgerPresentButPageDeletedAndNoOrphanFound` — `verifyPage` → `ABSENT`; `findRootByIdentity(shape)` → `Optional.empty()`; assert `createRootPage` called once, `ledger.record(workspaceId, DASHBOARD, newId)`; outcome `REPAIRED` (row 7).
6. `execute_readoptsWhenLedgerPresentButPageDeletedAndOrphanFound` — `verifyPage` → `ABSENT`; `findRootByIdentity(shape)` → `Optional.of("orphan-id")`; assert **no** `createRootPage`, `ledger.record(workspaceId, DASHBOARD, "orphan-id")`; outcome `REPAIRED` (row 6).
7. `execute_adoptsOrphanWhenNoLedgerAndMatching` — no `DASHBOARD` resource; `findRootByIdentity(shape)` → `Optional.of("orphan-id")`; assert **no** `createRootPage`/`repairPage`/`verifyPage`, `ledger.record(workspaceId, DASHBOARD, "orphan-id")`; outcome `RECONCILED` (row 2).
8. `execute_propagatesAmbiguousMatchFailureOnColdPath` — no `DASHBOARD` resource; `findRootByIdentity(shape)` throws `NotionApiException("Ambiguous Dashboard identity...")`; assert the same exception (same instance or same type+message) propagates out of `execute` uncaught, and `verifyNoInteractions(ledger)` (row 3).
9. `execute_propagatesAmbiguousMatchFailureOnWarmAbsentPath` — `DASHBOARD` resource present; `verifyPage` → `ABSENT`; `findRootByIdentity` throws `NotionApiException`; assert propagation, `verifyNoInteractions(ledger)` (row 8).
10. `execute_propagatesNotionFailureFromVerifyWithoutWritingLedger` — `verifyPage` throws `NotionApiException` (e.g. simulating a transport-level failure distinct from ABSENT); assert propagation, `verifyNoInteractions(ledger)` (FR-9/FR-10).
11. `execute_propagatesNotionFailureFromCreateWithoutWritingLedger` — cold path, `findRootByIdentity` → empty, `createRootPage` throws; assert propagation, `verifyNoInteractions(ledger)` (FR-10, "Notion write before ledger write" ordering — confirms `ledger.record` is never reached when the write itself fails).
12. `execute_neverInvokesDatabaseOrRelationPortMethods` — on any happy-path execution, assert via `verify(notion, never()).createDatabase(any(), any())` (and similarly for `ensureRelation`/`ensureRollup`/`ensureFormula`/`hasSampleRecords`/`insertSampleRecords`/`verify`/`findChildByIdentity`/`repairShape`) that only the four page-oriented methods are ever called (FR-12).
13. `execute_usesSameTitleAcrossAllPortCallsInOneRun` — capture the `PageShape` argument passed to every `notion.*` invocation in a multi-call scenario (e.g. the warm-`ABSENT`-then-recreate path, test 5) via `ArgumentCaptor`, and assert every captured `PageShape.title()` is identical (single-source-of-truth guard for `dashboardTitle`, §3.2).
14. `execute_isNotAnnotatedTransactional` — reflection: `CreateDashboardService.class.getMethod("execute", UUID.class).isAnnotationPresent(Transactional.class) == false` and no class-level `@Transactional` — pins architecture §4.4 so a regression is caught by CI (mirrors Create Workspace's equivalent test).

### 9.2 `NotionPropertiesTest` (plain unit or `ApplicationContextRunner`) — `infrastructure.adapter.notion`

Use `new ApplicationContextRunner().withUserConfiguration(TestConfig.class).withPropertyValues(...)` (Spring Boot Test) to assert startup-time fail-fast without booting the full app:

- `contextFails_whenTokenBlank` — `notion.token=` (blank), `notion.version=2026-03-11`, `notion.root-parent-page-id=abc` → context fails to start (`BeanCreationException`/`ConfigurationPropertiesBindException`).
- `contextFails_whenVersionBlank` — same pattern for `notion.version`.
- `contextFails_whenRootParentPageIdBlank` — same pattern for `notion.root-parent-page-id`.
- `contextStarts_whenAllPropertiesPresent` — all three non-blank → context starts, `NotionProperties` bean bound with the given values.

### 9.3 `NotionProvisioningAdapterTest` (`MockRestServiceServer` bound to the adapter's injected `RestClient.Builder`) — `infrastructure.adapter.notion`

Construct via `RestClient.Builder builder = RestClient.builder(); MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build(); NotionProperties props = new NotionProperties("test-token", "2026-03-11", "root-parent-id"); NotionProvisioningAdapter adapter = new NotionProvisioningAdapter(props, builder);` — every case below asserts **both** the outgoing request (path, verb, `Authorization: Bearer test-token`, `Notion-Version: 2026-03-11`, request JSON body) **and** the resulting return value/behavior.

1. `createRootPage_postsToPagesEndpointWithParentAndTitle_returnsId` — expect `POST /v1/pages` with the exact JSON shape of §5.3 (`parent.page_id == "root-parent-id"`, `properties.title.title[0].text.content == "LifeOS — Personal"`); respond `200` with `{"id":"new-page-id"}`; assert returned id.
2. `verifyPage_returnsAbsentOn404` — expect `GET /v1/pages/{id}`; respond `404` with a Notion error body; assert `ABSENT`.
3. `verifyPage_returnsAbsentWhenArchivedOrInTrash` — respond `200` with `archived:true` (and a second case with `in_trash:true`); assert `ABSENT` for each.
4. `verifyPage_returnsDriftedOnTitleMismatch` — respond `200`, correct parent, title `"Something Else"`; assert `PRESENT_DRIFTED`.
5. `verifyPage_returnsDriftedOnParentMismatch` — respond `200`, correct title, `parent.page_id` different from configured root; assert `PRESENT_DRIFTED`.
6. `verifyPage_returnsMatchingWhenTitleAndParentMatch` — respond `200`, exact match; assert `PRESENT_MATCHING`.
7. `repairPage_patchesTitleWhenDrifted` — `GET` returns title-mismatched page, correct parent; expect `PATCH /v1/pages/{id}` with the §5.5 body; assert no `/move` call issued.
8. `repairPage_movesPageWhenParentDrifted` — `GET` returns correct title, wrong parent; expect `POST /v1/pages/{id}/move` with `{"parent":{"page_id":"root-parent-id"}}`; assert no `PATCH` issued.
9. `repairPage_patchesAndMovesWhenBothDrifted` — both wrong; expect both calls.
10. `repairPage_restoresTrashedPage` — `GET` returns `in_trash:true`, correct title/parent; expect `PATCH` with `in_trash:false`/`archived:false`.
11. `findRootByIdentity_postsSearchAndReturnsSingleMatch` — expect `POST /v1/search` with `{"query":"LifeOS — Personal","filter":{"value":"page","property":"object"}}`; respond one matching result; assert `Optional.of(id)`.
12. `findRootByIdentity_returnsEmptyWhenNoHit` — respond `{"results":[]}`; assert `Optional.empty()`.
13. `findRootByIdentity_filtersOutResultsWithWrongParent` — respond a hit with matching title but different `parent.page_id`; assert `Optional.empty()`.
14. `findRootByIdentity_filtersOutResultsWithNonExactTitleMatch` — respond a hit with a title that merely contains the query substring (Notion's fuzzy search behavior) but is not exactly equal; assert `Optional.empty()`.
15. `findRootByIdentity_filtersOutArchivedOrTrashedResults` — respond a title+parent-matching but `archived:true` hit; assert `Optional.empty()`.
16. `findRootByIdentity_throwsOnMultipleMatches` — respond two results both matching title+parent exactly; assert `NotionApiException` thrown, message does not contain the token.
17. `client_retriesOn429ThenSucceeds` — first response `429` with `Retry-After: 0` (or `1`, whatever keeps the test fast — consider stubbing/overriding the sleep in a test-visible way, e.g. package-private constant or injectable `Sleeper` if the Implementer finds a 1-second real sleep unacceptably slow for CI; not load-bearing which mechanism, but the test must not sleep for a long real duration), second response `200` success; assert the method call ultimately succeeds and exactly 2 requests were sent.
18. `client_failsAfterExhaustingBoundedRetries` — three consecutive `429` responses; assert `NotionApiException` after exactly `MAX_ATTEMPTS` (3) requests, no 4th request sent.
19. `client_neverLeaksTokenInExceptionMessage` — respond `401` with body `{"object":"error","status":401,"code":"unauthorized","message":"API token is invalid."}`; assert the thrown `NotionApiException.getMessage()` contains `"unauthorized"`/`"API token is invalid."` but does **not** contain the literal configured token string (`"test-token"`) nor the substring `"Bearer"`.
20. `client_mapsGenericErrorStatusToNotionApiExceptionWithCodeAndMessage` — respond `500` with a Notion-shaped error body on, e.g., `createRootPage`; assert `NotionApiException` message includes status `500` and the body's `code`/`message`.

Use `server.verify()` at the end of each test that sets request expectations (`MockRestServiceServer` convention).

### 9.4 `PageShapeTest` / `ParentConstraintTest` (plain unit, no Spring context) — `application.port`

- `constructor_rejectsBlankTitle` — null, empty, whitespace-only all throw `IllegalArgumentException`.
- `constructor_rejectsNullParent`.
- `constructor_acceptsValidInputs` — round-trips `title()`/`parent()`.
- (No test needed for the single-value enum beyond compilation — do not over-test.)

### 9.5 `CreateDashboardServiceIT` (optional `@SpringBootTest`, Testcontainers Postgres) — `application.usecase.workspace` (integration package, or co-located per existing `CreateWorkspaceIT` convention)

`NotionProvisioningPort` supplied via `@TestConfiguration` with an **in-memory fake** (`Map<String, PageRecord>`-backed) implementing only the four page methods realistically (create assigns a new UUID string and stores title+parent; verify/find read the map; repair mutates it) and every other port method throwing `UnsupportedOperationException` (mirrors production scope). Real `JpaWorkspaceRepository`/`WorkspaceLedgerWriter` wired against Testcontainers Postgres.

- `execute_persistsDashboardLedgerRowOnFirstRun` — a fresh workspace → `execute` → outcome `CREATED`; a direct repository read shows exactly one `DASHBOARD` `ProvisionedResource` row with the fake's returned id.
- `execute_convergesToOneRowAcrossReruns` — run `execute` twice in sequence against the same workspace (second run hits the warm `PRESENT_MATCHING` path against the fake) → still exactly one `DASHBOARD` row, second outcome `RECONCILED` (FR-11).
- `execute_reachesRepairedOutcomeWhenFakeSimulatesExternalRename` — after the first run, mutate the fake's stored title directly (simulating an out-of-band Notion rename) → second `execute` → `PRESENT_DRIFTED` → `REPAIRED`, and the ledger row's `notionId` is unchanged (rename repairs in place, id doesn't change) — sanity check on the upsert semantics from the Dashboard's perspective.

Class name ends in `IT` so Failsafe runs it under `./mvnw verify`, consistent with `spring-testing`/ADR-0003. Zero real Notion calls in this class — the fake port guarantees that; no `MockRestServiceServer` is used here (that's §9.3's job).

---

## 10. Explicitly NOT built in this pass (scope guard for the Implementer)

Do not build any of the following — deferred per the architecture (§11 tracked future features) or already fixed out-of-scope by Create Workspace:

- **Any of the seven database steps' real Notion implementation, or `RELATIONS`/`ROLLUPS`/`FORMULAS`/`SAMPLE_DATA` steps.** `NotionProvisioningAdapter.createDatabase`/`repairShape`/`ensureRelation`/`ensureRollup`/`ensureFormula`/`hasSampleRecords`/`insertSampleRecords`/`verify`/`findChildByIdentity` all keep their existing `UnsupportedOperationException` bodies, unchanged, verbatim. Do not implement, do not partially stub differently, do not rename their messages.
- **Dashboard body content, navigation links, or child-database link maintenance.** `createRootPage` sends `title` only — no page body blocks, no `children` array, no post-creation block-append call (architecture §11.1/§11.2, OQ-5). Do not add a "links to the seven databases" feature here.
- **OAuth / public-connection / workspace-root (`parent.workspace = true`) pages.** `ParentConstraint` has exactly one value, `ROOT_PARENT`; do not add a `WORKSPACE_ROOT` value or any OAuth flow (architecture §11.3).
- **Per-Person Notion token storage/scoping.** Still one process-level `NotionProperties.token()` (architecture, inherited from Create Workspace OQ-7). Do not add a `Person`-to-token association or per-request token resolution.
- **A dedicated marker property for identity (beyond title+parent).** Architecture §11.4 defers this; `findRootByIdentity`'s filtering stays title+parent-exact as specified in §5.6.
- **Notion `/v1/search` pagination traversal.** Single page of results only (§5.6 point 6).
- **REST/CLI authentication, new endpoints, or changes to `WorkspaceController`/`WorkspaceCommands`/`ApiExceptionHandler`.** §7 — no changes.
- **A generic/reusable HTTP retry abstraction shared across future adapter methods.** `NotionClient`'s backoff logic (§5.1.1) is scoped to what the four page methods need; do not build a general-purpose resilience library here (YAGNI — Create Workspace ADR-0005 rejected over-generalized abstractions for the same reason).
- **Any Flyway migration.** No schema change — the Dashboard's Notion id reuses the existing `provisioned_resources` table/`DASHBOARD` enum value from Create Workspace's `V1__create_workspace_tables.sql`.

---

## 11. Note back to the Architect (non-blocking)

`repairPage(String pageId, PageShape expected)`'s port signature does not receive the `VerificationResult` that triggered the repair — only the id and the expected shape. This means the adapter implementation (§5.5) must perform its own `GET` inside `repairPage` to determine *which* of title/parent actually drifted, even though `CreateDashboardService` already knows this (it just received `PRESENT_DRIFTED` from a preceding `verifyPage` call in the same `execute` run). This costs one extra Notion `GET` on every repair-from-drift path (row 5 of §3.4) beyond the architecture's "at most 2–3 calls" estimate (which is stated as an average, not a hard ceiling, so this is not a contradiction — but it is worth flagging). An alternative — passing the stale `NotionPageResponse`/drift detail into `repairPage` — would require a port signature change (e.g. `repairPage(String pageId, PageShape expected, VerificationResult priorResult)`), which is more invasive than this pass's additive-only mandate. Recommendation: accept the extra `GET` for this pass (correctness and port stability over one HTTP call); revisit only if NFR-7's budget is later tightened to a hard per-run ceiling by the Architect.

No other deviation from `02-architecture.md`/the ADRs was needed; this spec is a direct, mechanical elaboration of the resolved design.

---

## 12. Traceability (FR/NFR → spec section)

| Req | Satisfied by (this spec) |
|---|---|
| FR-1 | §3.1 unchanged `CreateDashboardUseCase.execute(UUID)` |
| FR-2 | §3.3 `workspaceRepository.findById` → `IllegalStateException` before any Notion call; test 9.1-1 |
| FR-3 | §3.3 cold-path create; §3.4 row 1; test 9.1-2 |
| FR-4 | §3.3 warm `PRESENT_MATCHING`; §3.4 row 4; test 9.1-3 |
| FR-5a | §3.3 warm `ABSENT` → adopt-or-create; §3.4 rows 6/7; tests 9.1-5, 9.1-6 |
| FR-5b | §3.3 warm `PRESENT_DRIFTED` → repair; §3.4 row 5; test 9.1-4 |
| FR-6 | Every path calls `verifyPage`/`findRootByIdentity` before `RECONCILED`; §3.3; tests 9.1-3, 9.1-7 |
| FR-7 | Cold-path adoption; §3.3, §5.6; test 9.1-7 |
| FR-8 | `WorkspaceLedgerWriter.record` — own tx (§6) |
| FR-9 | `ProvisioningStepResult(DASHBOARD, …)`; no self-constructed `FAILED` (§3.3); tests 9.1-8–9.1-11 |
| FR-10 | Notion-before-ledger ordering; §3.3; test 9.1-11 |
| FR-11 | Adoption-before-create both paths; upsert `record`; IT convergence (§9.5) |
| FR-12 | Only page methods invoked; test 9.1-12 |
| NFR-1 | Strict per-path live verification (§3.3, §5.4) |
| NFR-2 | Notion-before-ledger, no rollback, next-run reconcile (§3.3) |
| NFR-3 | Mockito service tests + `MockRestServiceServer` adapter tests + fake-port IT (§9) |
| NFR-4 | Stub throw removed only with real adapter slice, gated by §9 tests |
| NFR-5 | Per-run logging (workspaceId, prior ledger id, `VerificationResult`, acted-on id, outcome) — Implementer adds SLF4J logging inside `execute` per architecture §6; no dedicated test required beyond not-breaking existing tests, but do not log the token (§8) |
| NFR-6 | §8 Security; §5.7 message-construction rule; test 9.3-19 |
| NFR-7 | ≤ ~3 calls per run (§5, §11 note on `repairPage`'s extra `GET`) |
| NFR-8 | 429/529 bounded backoff (§5.1.1); tests 9.3-17, 9.3-18 |

---

## Implementation notes

- `backend/src/main/java/com/lifeos/application/port/PageShape.java` — new record, validates non-blank title / non-null parent.
- `backend/src/main/java/com/lifeos/application/port/ParentConstraint.java` — new single-value enum (`ROOT_PARENT`).
- `backend/src/main/java/com/lifeos/application/port/NotionProvisioningPort.java` — refined `createRootPage(String)` → `createRootPage(PageShape)`; added `verifyPage`, `repairPage`, `findRootByIdentity`; all other members byte-for-byte unchanged.
- `backend/src/main/java/com/lifeos/application/usecase/workspace/CreateDashboardService.java` — 3-arg constructor (`NotionProvisioningPort`, `WorkspaceRepository`, `WorkspaceLedgerWriter`); real §3.3 algorithm implemented (cold/warm paths, all 8 decision-table rows); stub throw removed; SLF4J `@Slf4j` logging added per NFR-5 (workspaceId, prior ledger id, `VerificationResult`, found/outcome — no token, no raw bodies); no `@Transactional`.
- `backend/src/main/java/com/lifeos/infrastructure/adapter/notion/NotionProperties.java` — extended to a validated record (`token`, `version`, `rootParentPageId`, all `@NotBlank`) with `@Validated`.
- `backend/src/main/java/com/lifeos/infrastructure/adapter/notion/NotionClient.java` — new adapter-internal, package-private HTTP client (`post`/`get`/`patch`), 429/529 bounded backoff (`MAX_ATTEMPTS = 3`), centralized error mapping to `NotionApiException`.
- `backend/src/main/java/com/lifeos/infrastructure/adapter/notion/NotionApiException.java` — new unchecked, adapter-owned exception; messages built only from HTTP status + Notion's `code`/`message`, never token/headers/raw body.
- `backend/src/main/java/com/lifeos/infrastructure/adapter/notion/NotionProvisioningAdapter.java` — page slice (`createRootPage`/`verifyPage`/`repairPage`/`findRootByIdentity`) implemented against `NotionClient`; all other port methods keep their existing `UnsupportedOperationException` bodies verbatim; constructor now also takes `RestClient.Builder`.
- `backend/src/main/java/com/lifeos/infrastructure/adapter/notion/dto/NotionPageResponse.java`, `NotionParent.java`, `NotionTitleProperty.java`, `NotionRichText.java`, `NotionSearchResponse.java`, `NotionErrorResponse.java` — new adapter-internal Jackson DTOs (public within the `dto` subpackage so the parent `adapter.notion` package can consume them; never exposed outside `adapter.notion`).
- `backend/src/main/resources/application.yml` — added `notion.version` (default `2026-03-11`) and `notion.root-parent-page-id` (no default — fails fast) keys.
- `backend/src/test/resources/application.yml` — new test-scope defaults (`notion.token=test-token`, `notion.version=2026-03-11`, `notion.root-parent-page-id=test-root-parent-id`) so full-context tests (`CreateWorkspaceIT`, `CreateDashboardServiceIT`) still start under the new `@NotBlank` validation.
- `backend/src/test/java/com/lifeos/application/port/PageShapeTest.java` — new value-type unit tests.
- `backend/src/test/java/com/lifeos/application/usecase/workspace/CreateDashboardServiceTest.java` — rewritten for the 3-arg constructor; one test per §3.4 decision-table row plus scope/failure/transactional-reflection tests (14 tests total).
- `backend/src/test/java/com/lifeos/infrastructure/adapter/notion/NotionPropertiesTest.java` — new `ApplicationContextRunner` fail-fast tests for blank `token`/`version`/`rootParentPageId`.
- `backend/src/test/java/com/lifeos/infrastructure/adapter/notion/NotionProvisioningAdapterTest.java` — new `MockRestServiceServer` contract tests covering all four page methods, 429 retry/exhaustion, and token-non-leak (21 tests).
- `backend/src/test/java/com/lifeos/application/usecase/workspace/CreateDashboardServiceIT.java` — new `@SpringBootTest` + Testcontainers Postgres integration test with an in-memory fake `NotionProvisioningPort` implementing only the four page methods (create/converge/repair-on-rename scenarios).

No changes to `WorkspaceController`, `WorkspaceCommands`, `ApiExceptionHandler`, any web/CLI DTO, `domain.workspace`, `infrastructure.adapter.persistence`, or any Flyway migration.

Note: no `maven-failsafe-plugin` is configured in `pom.xml`, so `*IT.java` classes (including the pre-existing `CreateWorkspaceIT` and the new `CreateDashboardServiceIT`) are not auto-executed by `./mvnw verify`/`./mvnw test` — this predates this change and was left as-is (out of this spec's scope). Both IT classes were verified green via `./mvnw test -Dtest=CreateDashboardServiceIT,CreateWorkspaceIT`.

## 13. Post-audit remediation (added 2026-08-05)

The Auditor (`05-audit-report.md`) raised 1 High + 2 Medium + 2 Low + 1 informational. All were addressed; this section supersedes the affected implementation notes. Build re-verified: `TESTCONTAINERS_RYUK_DISABLED=true ./mvnw verify` → BUILD SUCCESS, **133 unit + 5 IT** tests.

- **AUD-01 (RestClient timeouts).** New `infrastructure/adapter/notion/NotionClientConfiguration` provides a `RestClientCustomizer` bean applying finite connect (5s) / read (20s) timeouts via `NotionClient.requestFactory(Duration, Duration)`. The customizer (not `NotionClient`'s constructor) applies the factory so the `MockRestServiceServer`-bound adapter tests keep their mock factory. Test: `NotionClientTest.requestFactory_enforcesReadTimeoutRatherThanHangingForever` (black-hole socket).
- **AUD-02 (Retry-After clamp).** `NotionClient.parseRetryAfter` clamps to `MAX_BACKOFF_SECONDS = 30`, floors non-positive to 1s, 1s fallback for non-numeric/HTTP-date. Made package-private; tested in `NotionClientTest`.
- **AUD-03 (URI variables).** `NotionClient.get/post/patch` take `Object... uriVariables`; the adapter passes page ids as `"/pages/{id}"` / `"/pages/{id}/move"` variables (percent-encoded). Test: `NotionProvisioningAdapterTest.verifyPage_encodesPageIdAsUriVariable`.
- **AUD-04 (search pagination).** `findRootByIdentity` now traverses `next_cursor`/`has_more`, accumulating matches across all pages before empty/unique/ambiguous. This **lifts** the "no `/v1/search` pagination traversal" item from §10's do-NOT-build list. Tests: `findRootByIdentity_followsPaginationAcrossPages`, `findRootByIdentity_throwsOnMatchesSpreadAcrossPages`.
- **AUD-05 (shared ObjectMapper).** `NotionClient` takes the Spring-managed `ObjectMapper` as a constructor arg; `NotionProvisioningAdapter`'s constructor is now `(NotionProperties, RestClient.Builder, ObjectMapper)`. The `new ObjectMapper()` is removed.
- **AUD-06 (failsafe).** `maven-failsafe-plugin` (`integration-test` + `verify`) is now declared in `backend/pom.xml`; `verify` runs `CreateDashboardServiceIT` + `CreateWorkspaceIT` automatically. The "not auto-executed" note above is now historical.
- New files: `NotionClientConfiguration`, `NotionClientTest`. Changed: `NotionClient`, `NotionProvisioningAdapter`, `NotionProvisioningAdapterTest` (+3 tests → 24), `backend/pom.xml`.
