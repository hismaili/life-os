# 01 — Spec: Create Resources Database

## Traceability note

`docs/pipeline/create-tasks-database/01-spec.md`, `docs/pipeline/create-projects-database/03-tech-spec.md`,
the ADRs under `docs/pipeline/*/adr/`, and `docs/architecture/{03-Domain-Model,05-Ubiquitous-Language}.md`
were named as required reading but do **not exist in this repository checkout** (`docs/` is absent
entirely). Every requirement below is instead traced to the in-repo source files actually present,
listed inline by path. Where a prior-feature convention (e.g. the DATE property precedent) is
asserted, it is verified against `NotionProvisioningAdapter.propertyConfig` and
`CreateTasksDatabaseService`, not against an unavailable spec document.

## 1. Summary

The **Create Resources Database** use case provisions a "Resources" Notion database as a child of the
workspace Dashboard page during "Create Workspace" orchestration, mirroring the already-implemented
Projects/Tasks/Knowledge/Habits/Journal database steps. It gives users a durable, structured home in
their Notion workspace for external references, documents, and links captured in the LifeOS domain
model (`Resource.title`, `Resource.url` — `src/main/java/com/lifeos/domain/resource/Resource.java:10-15`),
so that resource records created in the domain have a corresponding, idempotently-maintained
destination in the provisioned workspace. This feature also closes a capability gap: the Resource
`url` field has no honest Notion property mapping today, so this spec's scope includes adding a
dedicated `url` property type to the Notion provisioning port and adapter (the same shape of
extension previously done for `DATE`, verified at
`src/main/java/com/lifeos/infrastructure/adapter/notion/NotionProvisioningAdapter.java:264-269`).

## 2. Actors & stakeholders

- **LifeOS user (workspace owner)** — runs "Create Workspace"; consumes the resulting Notion
  workspace, including the Resources database, as a view onto their resource records.
- **Create Workspace orchestrator** (`CreateWorkspaceService`,
  `src/main/java/com/lifeos/application/usecase/workspace/CreateWorkspaceService.java:59`) — invokes
  this step as one of the seven Phase B (child database) steps, gated on the Dashboard step
  succeeding and gating downstream Relations/Rollups/Formulas/Sample-Data phases.
- **Notion provisioning port/adapter maintainers** — own the `NotionPropertyType` enum and
  `NotionProvisioningAdapter.propertyConfig` extension this feature requires.
- **Workspace ledger** (`WorkspaceLedgerWriter`,
  `src/main/java/com/lifeos/application/usecase/workspace/WorkspaceLedgerWriter.java`) — records the
  confirmed Notion database id against `ProvisionedResourceType.RESOURCES_DB`
  (`src/main/java/com/lifeos/domain/workspace/ProvisionedResourceType.java:5`).

## 3. Functional requirements

**FR-1 — Provision a "Resources" database as a Dashboard child.**
On workspace creation (or re-run), if no confirmed Resources database exists for the workspace, the
system creates a Notion database titled "Resources" as a child of the workspace's confirmed Dashboard
page.

**FR-2 — Resources schema: Title.**
The created/verified database has a **Title** property of Notion type `title`, sourced from
`Resource.title` (`src/main/java/com/lifeos/domain/resource/Resource.java:12`, non-blank per
`Resource.create` at line 21-23).

**FR-3 — Resources schema: URL.**
The created/verified database has a **URL** property of the dedicated Notion `url` property type,
sourced from `Resource.url` (`src/main/java/com/lifeos/domain/resource/Resource.java:13`, nullable).
No `select` or `date` property is part of this schema.

**FR-4 — Add `URL` to the Notion property type port.**
`NotionPropertyType` (`src/main/java/com/lifeos/application/port/NotionPropertyType.java:3`, currently
`{TITLE, RICH_TEXT, SELECT, DATE}`) gains a `URL` member, so the Resources schema can declare a
`PropertyDefinition("URL", NotionPropertyType.URL, ...)` using the existing
`PropertyDefinition`/`DatabaseSpec`/`ExpectedShape` port types without weakening their invariants
(`src/main/java/com/lifeos/application/port/PropertyDefinition.java`,
`src/main/java/com/lifeos/application/port/DatabaseSpec.java`,
`src/main/java/com/lifeos/application/port/ExpectedShape.java`).

**FR-5 — Adapter emits a valid Notion `url` property config.**
`NotionProvisioningAdapter.propertyConfig`
(`src/main/java/com/lifeos/infrastructure/adapter/notion/NotionProvisioningAdapter.java:262-270`) gains
a `case URL` branch that emits `{"type": "url", "url": {}}`, mirroring the existing `TITLE`/
`RICH_TEXT`/`DATE`/`SELECT` branches. This applies on both database creation
(`createDatabase`, line 126-137) and shape repair (`repairShape`, line 187-209, which patches only
missing properties).

**FR-6 — Idempotent identity resolution (cold path).**
When no ledger entry exists for `RESOURCES_DB`, the system searches the Dashboard's children for an
existing database titled "Resources" (`findChildByIdentity`,
`NotionProvisioningPort` line 12) before creating a new one, so re-running workspace creation does not
duplicate the database. Given a found orphan, its shape is verified and, if drifted, repaired; if
absent (title match but object gone), a new database is created.

**FR-7 — Idempotent verification/repair (warm path).**
When a ledger entry exists for `RESOURCES_DB`, the system verifies the recorded Notion database via
`verify(...)` (`NotionProvisioningPort` line 10). `PRESENT_MATCHING` yields `RECONCILED` with no writes.
`PRESENT_DRIFTED` (e.g. the URL or Title property is missing) triggers `repairShape` and re-records the
ledger, yielding `REPAIRED`. `ABSENT` re-attempts identity resolution under the Dashboard before falling
back to creating a new database, then re-records the ledger, yielding `REPAIRED`.

**FR-8 — Ledger recording on every successful create/adopt/repair.**
Every path that creates, adopts, or repairs the Resources database records
`(workspaceId, RESOURCES_DB, notionDatabaseId)` via `WorkspaceLedgerWriter.record`
(`src/main/java/com/lifeos/application/usecase/workspace/WorkspaceLedgerWriter.java:19-23`) before the
step returns a non-failing outcome.

**FR-9 — Precondition: confirmed Dashboard required.**
If the workspace has no confirmed Dashboard resource
(`Workspace.resource(DASHBOARD)`, referenced pattern in
`src/main/java/com/lifeos/application/usecase/task/CreateTasksDatabaseService.java:44-46`), the step
fails fast with a descriptive error rather than attempting to create an orphaned database.

**FR-10 — Precondition: workspace must exist.**
If the given `workspaceId` does not resolve to a workspace, the step fails fast with a descriptive
error rather than proceeding.

**FR-11 — Ambiguous-identity failure.**
If more than one child database titled "Resources" is found under the Dashboard during identity
resolution, the step does not guess; it surfaces a failure (matching the ambiguity handling already
implemented for Dashboard/Projects identity resolution,
`NotionProvisioningAdapter.java:117-121` and `178-182`), which the orchestrator records as a `FAILED`
step result.

**FR-12 — Orchestration integration, no change to `CreateWorkspaceService`.**
The step is invoked by the existing `CreateWorkspaceService` wiring
(`src/main/java/com/lifeos/application/usecase/workspace/CreateWorkspaceService.java:59`) as one of the
Phase B database steps, gated by `phaseAOk` (Dashboard succeeded) and contributing to `phaseBOk`. No
change to the orchestrator is in scope; `CreateResourcesDatabaseUseCase`
(`src/main/java/com/lifeos/application/usecase/resource/CreateResourcesDatabaseUseCase.java`) is
already wired in.

**FR-13 — Explicit failure on unexpected error, never silent success.**
Any unexpected exception during the step is not swallowed; the orchestrator's `runStep`/`runOrBlock`
wrapping (`CreateWorkspaceService.java:88-101`) converts it into a `FAILED` `ProvisioningStepResult`
carrying a detail message, consistent with `ProvisioningStepResult`'s invariant that `FAILED`/`BLOCKED`
outcomes require a non-blank `detail`
(`src/main/java/com/lifeos/application/dto/workspace/ProvisioningStepResult.java:9-11`).

## 4. Acceptance criteria

**AC-1 (FR-1, FR-6)**
Given a workspace with a confirmed Dashboard and no ledger entry for `RESOURCES_DB` and no existing
"Resources" child database under the Dashboard,
When the Resources database step runs,
Then a new Notion database titled "Resources" is created as a child of the Dashboard, the ledger
records it under `RESOURCES_DB`, and the step result outcome is `CREATED`.

**AC-2 (FR-2, FR-3, FR-4, FR-5)**
Given the Resources database is created,
When its schema is inspected,
Then it contains exactly a `title`-type "Title" property and a `url`-type "URL" property, and the
adapter's created payload for the "URL" property is `{"type": "url", "url": {}}`.

**AC-3 (FR-7)**
Given a ledger entry for `RESOURCES_DB` pointing at a Notion database that matches the expected title
and has both required properties present,
When the Resources database step runs again,
Then no create/patch call is made, the ledger is unchanged, and the step result outcome is
`RECONCILED`.

**AC-4 (FR-5, FR-7)**
Given a ledger entry for `RESOURCES_DB` pointing at a Notion database missing the "URL" property,
When the Resources database step runs,
Then `repairShape` is called and patches in the missing "URL" property using the `url` type config,
the ledger is re-recorded with the same id, and the step result outcome is `REPAIRED`.

**AC-5 (FR-6, FR-7)**
Given a ledger entry for `RESOURCES_DB` whose Notion database no longer exists (archived/trashed),
When the Resources database step runs,
Then the system re-searches the Dashboard's children by identity; if a matching orphan is found it is
adopted (and repaired if drifted); if none is found a new database is created; either way the ledger
is re-recorded and the outcome is `REPAIRED` (adoption) or `CREATED`-equivalent per the warm-path
contract (`REPAIRED`, consistent with `CreateTasksDatabaseService.executeWarmPath` ABSENT branch).

**AC-6 (FR-6)**
Given no ledger entry for `RESOURCES_DB` but an existing "Resources" child database already present
under the Dashboard (orphan from a prior partial run) whose shape matches expectations,
When the Resources database step runs,
Then no new database is created, the existing one is adopted, the ledger records its id, and the step
result outcome is `RECONCILED`.

**AC-7 (FR-9)**
Given a workspace with no confirmed Dashboard resource,
When the Resources database step runs,
Then the step fails with a descriptive error identifying the missing Dashboard precondition, and no
Notion API create/patch call for a database is attempted.

**AC-8 (FR-10)**
Given a `workspaceId` that does not resolve to any workspace,
When the Resources database step runs,
Then the step fails with a descriptive error identifying the missing workspace, and no Notion API call
is attempted.

**AC-9 (FR-11)**
Given more than one child database titled "Resources" exists under the Dashboard,
When identity resolution runs during the Resources database step,
Then the step does not adopt either database, and the orchestrator records the step's outcome as
`FAILED` with a non-blank detail describing the ambiguity.

**AC-10 (FR-6, FR-7, AC-3)**
Given the Resources database step has already reached a stable `CREATED` or `RECONCILED` state,
When the full "Create Workspace" orchestration is re-run any number of additional times with no
external changes to the Notion workspace,
Then each subsequent run yields outcome `RECONCILED` for `RESOURCES_DB` and performs no additional
Notion write calls for that step (convergence).

**AC-11 (FR-12)**
Given the Dashboard step failed or was blocked in a given orchestration run,
When `CreateWorkspaceService` reaches the Resources database step,
Then the step is not invoked; the orchestrator records outcome `BLOCKED` for `RESOURCES_DB` with detail
"prerequisite step failed or was blocked", per existing `runOrBlock` behavior
(`CreateWorkspaceService.java:96-101`).

**AC-12 (NFR — no token leakage, FR-13)**
Given the Notion API call underlying any part of the Resources database step fails (e.g. network error,
4xx/5xx response),
When the resulting `ProvisioningStepResult` (outcome `FAILED`) is produced,
Then its `detail` message contains no Notion integration token, authorization header value, or other
credential material.

**AC-13 (adapter contract test, FR-5)**
Given a `PropertyDefinition` of type `NotionPropertyType.URL`,
When `NotionProvisioningAdapter`'s internal property-config mapping is exercised (directly or via a
`createDatabase`/`repairShape` call captured against a stub/mock Notion HTTP layer),
Then the emitted JSON body for that property is exactly `{"type": "url", "url": {}}` — no `options`,
no extraneous keys.

## 5. Non-functional requirements

**NFR-1 — Idempotency.** Re-running the Resources database step, and re-running the full "Create
Workspace" orchestration, must never create a second "Resources" database for the same workspace or
duplicate the ledger entry. (Mirrors the charter's idempotency mandate for all
workspace/database-provisioning use cases, `CLAUDE.md` "Idempotency" section.)

**NFR-2 — No secret/token leakage.** Error messages and log output produced by this step (including
`NotionApiException` messages and `ProvisioningStepResult.detail`) must never include the Notion
integration token or any authorization header value, consistent with existing exception messages in
`NotionProvisioningAdapter` which reference only resource ids and counts
(`NotionProvisioningAdapter.java:118-119, 179-180`).

**NFR-3 — Non-destructive repair.** Shape repair for the Resources database only adds missing
properties; it must not remove or rename properties/data not covered by the expected shape, matching
`repairShape`'s existing "patch only missing" behavior (`NotionProvisioningAdapter.java:199-208`).

**NFR-4 — Consistent outcome vocabulary.** The step must report outcomes using only the existing
`ProvisioningOutcome` values (`CREATED, RECONCILED, REPAIRED, FAILED, BLOCKED`,
`src/main/java/com/lifeos/application/dto/workspace/ProvisioningOutcome.java:3`); no new outcome value
is introduced by this feature.

**NFR-5 — Backward-compatible port extension.** Adding `NotionPropertyType.URL` must not change the
existing behavior of `TITLE`, `RICH_TEXT`, `SELECT`, or `DATE` handling anywhere in
`PropertyDefinition`, `DatabaseSpec`, `ExpectedShape`, or `NotionProvisioningAdapter`. Existing callers
(Projects, Tasks, Knowledge, Habits, Journal database steps) must continue to compile and behave
unchanged.

**NFR-6 — Testability.** The property-config mapping for `URL` must be verifiable via a contract test
that asserts the exact emitted JSON shape, without requiring a live Notion API call (mirrors existing
adapter test conventions implied by `NotionProvisioningAdapter`'s pure `propertyConfig` helper).

## 6. Data & entities (conceptual only)

- **Resource** (existing domain aggregate, `domain/resource/Resource.java`) — has a `title`
  (required) and a `url` (optional); belongs to a `Workspace`; may reference a `Knowledge` item.
- **Resources Database** (Notion-side projection, not a domain entity) — a child database of the
  workspace's Dashboard page, with two properties: Title (from `Resource.title`) and URL (from
  `Resource.url`).
- **ProvisionedResource** ledger entry — records the mapping from `(workspaceId, RESOURCES_DB)` to the
  confirmed Notion database id (`domain/workspace/ProvisionedResource.java`,
  `domain/workspace/ProvisionedResourceType.java:5`).

No new domain entity, value object, or relation is introduced. The Resource↔Knowledge relationship
(`Resource.knowledgeId`) is a conceptual link only at this stage — see Out of scope.

## 7. Constraints & assumptions

- `[ASSUMPTION]` The Resources database step's control flow (cold path / warm path / ambiguity
  handling / precondition checks) should exactly mirror `CreateTasksDatabaseService`'s structure
  (`src/main/java/com/lifeos/application/usecase/task/CreateTasksDatabaseService.java`), substituting
  `RESOURCES_DB` for `TASKS_DB` and the two-property schema for the four-property Tasks schema. This
  is inferred from the "reusing the idempotent pattern" instruction and the identical shape of the
  five already-implemented database steps; no deviation is specified.
- The dedicated Notion `url` property type (`{"type": "url", "url": {}}`) is the correct mapping for
  `Resource.url`, not `rich_text`. Notion's public API models `url` as a first-class property type
  distinct from `rich_text`, and the existing `DATE` precedent in this codebase already establishes
  that new primitive Notion property types are added to `NotionPropertyType` + `propertyConfig` as
  needed rather than approximated with `RICH_TEXT`/`SELECT`. This spec follows that precedent rather
  than treating `url`-as-`rich_text` as an open question.
- Notion API version `2025-09-03` (data-source model) is assumed to remain the operative API version
  for this step, per the existing `// requires Notion-Version >= 2025-09-03` comments annotating
  `createDatabase`, `verify`, `findChildByIdentity`, and `repairShape` in
  `NotionProvisioningAdapter.java`. This feature does not change API version handling.
- No domain model change is required or in scope; `Resource.java` already carries `title` and `url`
  in a form sufficient to derive the Notion schema (per the feature request's explicit statement).

## 8. Out of scope

- The `Resource.knowledgeId` → Knowledge database relation, and any Notion `relation` property —
  deferred to the "Create Relations" step (`CreateRelationsUseCase`/`CreateRelationsService`).
- Any rollup or formula referencing the Resources database (deferred to Create Rollups / Create
  Formulas steps).
- Inserting rows or sample data into the Resources database (deferred to `PopulateSampleDataUseCase`).
- Provisioning of any other child database (Projects, Tasks, Knowledge, Habits, Journal, People,
  Goals, Reviews) — each already implemented or separately scoped.
- Any change to the `Resource` domain entity itself — none is needed per the feature request.
- Any change to `docs/productivity/` methodology content.
- Any change to `CreateWorkspaceService`'s orchestration wiring beyond what already exists (the use
  case is already injected and invoked).
- Broadening `NotionPropertyType.URL` support to other in-repo features (e.g. adding a URL property to
  Projects/Tasks/Knowledge schemas) — this spec adds the port/adapter capability but only consumes it
  for the Resources database.

## 9. Open questions for the stakeholder

None. The domain schema is fully backed by existing code
(`domain/resource/Resource.java`), the control-flow pattern is fully precedented by
`CreateTasksDatabaseService`, and the one genuine design choice in this feature (Notion `url` type vs.
`rich_text` for `Resource.url`) is resolved in section 7 toward the dedicated `url` type, consistent
with the existing `DATE`-type precedent in `NotionProvisioningAdapter`.
