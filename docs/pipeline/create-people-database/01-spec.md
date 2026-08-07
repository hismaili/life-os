# 01 — Spec: Create People Database

## Traceability note

All requirements below are traced to in-repo sources by absolute-relative path. The immediately
preceding sibling feature (Create Resources Database), which added the `url` property type, is used
as the structural precedent for this feature's `email` property type addition
(`docs/pipeline/create-resources-database/01-spec.md`).

## 1. Summary

The **Create People Database** use case provisions a "People" Notion database as a child of the
workspace Dashboard page during "Create Workspace" orchestration, mirroring the six already-implemented
child database steps (Projects, Tasks, Knowledge, Habits, Journal, Resources). It gives users a durable,
structured home in their Notion workspace for the people/contacts captured in the LifeOS domain model
(`Person.name`, `Person.email` —
`backend/src/main/java/com/lifeos/domain/person/Person.java:13-14`), so that person records created in
the domain have a corresponding, idempotently-maintained destination in the provisioned workspace. This
is the **seventh and final** child-database provisioning step; once it lands, all seven Phase B database
steps referenced by `CreateWorkspaceService`
(`backend/src/main/java/com/lifeos/application/usecase/workspace/CreateWorkspaceService.java:59-60`) are
implemented. This feature also closes a capability gap: `Person.email` has no honest Notion property
mapping today (`NotionPropertyType` currently has no email-shaped member —
`backend/src/main/java/com/lifeos/application/port/NotionPropertyType.java:3`), so this spec's scope
includes adding a dedicated `email` property type to the Notion provisioning port and adapter, the same
shape of extension previously done for `URL`
(`backend/src/main/java/com/lifeos/infrastructure/adapter/notion/NotionProvisioningAdapter.java:267`) and
`DATE` before it.

## 2. Actors & stakeholders

- **LifeOS user (workspace owner)** — runs "Create Workspace"; consumes the resulting Notion workspace,
  including the People database, as a view onto their person/contact records.
- **Create Workspace orchestrator** (`CreateWorkspaceService`,
  `backend/src/main/java/com/lifeos/application/usecase/workspace/CreateWorkspaceService.java:36,60`) —
  invokes this step as the seventh of the seven Phase B (child database) steps, gated on the Dashboard
  step succeeding and contributing to `phaseBOk`.
- **Notion provisioning port/adapter maintainers** — own the `NotionPropertyType` enum
  (`backend/src/main/java/com/lifeos/application/port/NotionPropertyType.java`) and
  `NotionProvisioningAdapter.propertyConfig`
  (`backend/src/main/java/com/lifeos/infrastructure/adapter/notion/NotionProvisioningAdapter.java:262-271`)
  extension this feature requires.
- **Workspace ledger** (`WorkspaceLedgerWriter`,
  `backend/src/main/java/com/lifeos/application/usecase/workspace/WorkspaceLedgerWriter.java`) — records
  the confirmed Notion database id against `ProvisionedResourceType.PEOPLE_DB`
  (`backend/src/main/java/com/lifeos/domain/workspace/ProvisionedResourceType.java:5`).

## 3. Functional requirements

**FR-1 — Provision a "People" database as a Dashboard child.**
On workspace creation (or re-run), if no confirmed People database exists for the workspace, the system
creates a Notion database titled "People" as a child of the workspace's confirmed Dashboard page.

**FR-2 — People schema: Name.**
The created/verified database has a **Name** property of Notion type `title`, sourced from
`Person.name` (`backend/src/main/java/com/lifeos/domain/person/Person.java:13`, non-blank per
`Person.create`, lines 17-19).

**FR-3 — People schema: Email.**
The created/verified database has an **Email** property of the dedicated Notion `email` property type,
sourced from `Person.email` (`backend/src/main/java/com/lifeos/domain/person/Person.java:14`, an `Email`
value object — `backend/src/main/java/com/lifeos/domain/person/Email.java` — nullable on `Person`). No
`select`, `date`, `url`, or `rich_text` property is part of this schema.

**FR-4 — Add `EMAIL` to the Notion property type port.**
`NotionPropertyType` (`backend/src/main/java/com/lifeos/application/port/NotionPropertyType.java:3`,
currently `{TITLE, RICH_TEXT, SELECT, DATE, URL}`) gains an `EMAIL` member, so the People schema can
declare a `PropertyDefinition("Email", NotionPropertyType.EMAIL, ...)` using the existing
`PropertyDefinition`/`DatabaseSpec`/`ExpectedShape` port types without weakening their invariants
(`backend/src/main/java/com/lifeos/application/port/{PropertyDefinition,DatabaseSpec,ExpectedShape}.java`).

**FR-5 — Adapter emits a valid Notion `email` property config.**
`NotionProvisioningAdapter.propertyConfig`
(`backend/src/main/java/com/lifeos/infrastructure/adapter/notion/NotionProvisioningAdapter.java:262-271`)
gains a `case EMAIL` branch that emits `{"type": "email", "email": {}}`, mirroring the existing `TITLE`/
`RICH_TEXT`/`DATE`/`URL`/`SELECT` branches. This applies on both database creation (`createDatabase`,
lines 126-137) and shape repair (`repairShape`, lines 187-209, which patches only missing properties).

**FR-6 — Idempotent identity resolution (cold path).**
When no ledger entry exists for `PEOPLE_DB`, the system searches the Dashboard's children for an
existing database titled "People" (`findChildByIdentity`, `NotionProvisioningPort` — implemented at
`NotionProvisioningAdapter.java:161-183`) before creating a new one, so re-running workspace creation
does not duplicate the database. Given a found orphan, its shape is verified and, if drifted, repaired;
if absent (title match but object gone), a new database is created.

**FR-7 — Idempotent verification/repair (warm path).**
When a ledger entry exists for `PEOPLE_DB`, the system verifies the recorded Notion database via
`verify(...)` (`NotionProvisioningAdapter.java:140-157`). `PRESENT_MATCHING` yields `RECONCILED` with no
writes. `PRESENT_DRIFTED` (e.g. the Email or Name property is missing) triggers `repairShape` and
re-records the ledger, yielding `REPAIRED`. `ABSENT` re-attempts identity resolution under the Dashboard
before falling back to creating a new database, then re-records the ledger, yielding `REPAIRED`.

**FR-8 — Ledger recording on every successful create/adopt/repair.**
Every path that creates, adopts, or repairs the People database records
`(workspaceId, PEOPLE_DB, notionDatabaseId)` via `WorkspaceLedgerWriter.record`
(`backend/src/main/java/com/lifeos/application/usecase/workspace/WorkspaceLedgerWriter.java:19-23`)
before the step returns a non-failing outcome.

**FR-9 — Precondition: confirmed Dashboard required.**
If the workspace has no confirmed Dashboard resource (`Workspace.resource(DASHBOARD)`, per the pattern
implemented in `CreateResourcesDatabaseService.execute`,
`backend/src/main/java/com/lifeos/application/usecase/resource/CreateResourcesDatabaseService.java:42-44`),
the step fails fast with a descriptive error rather than attempting to create an orphaned database.

**FR-10 — Precondition: workspace must exist.**
If the given `workspaceId` does not resolve to a workspace, the step fails fast with a descriptive error
rather than proceeding.

**FR-11 — Ambiguous-identity failure.**
If more than one child database titled "People" is found under the Dashboard during identity
resolution, the step does not guess; it surfaces a failure (matching the ambiguity handling already
implemented for Dashboard/Projects/Resources identity resolution, `NotionProvisioningAdapter.java:
117-121` and `178-182`), which the orchestrator records as a `FAILED` step result.

**FR-12 — Orchestration integration, no change to `CreateWorkspaceService`.**
The step is invoked by the existing `CreateWorkspaceService` wiring
(`backend/src/main/java/com/lifeos/application/usecase/workspace/CreateWorkspaceService.java:36,60`) as
the seventh Phase B database step, gated by `phaseAOk` (Dashboard succeeded) and contributing to
`phaseBOk`. No change to the orchestrator is in scope; `CreatePeopleDatabaseUseCase`
(`backend/src/main/java/com/lifeos/application/usecase/person/CreatePeopleDatabaseUseCase.java`) is
already wired in — only `CreatePeopleDatabaseService.execute`
(`backend/src/main/java/com/lifeos/application/usecase/person/CreatePeopleDatabaseService.java:19-22`),
currently an explicit `UnsupportedOperationException` stub, needs a real implementation.

**FR-13 — Explicit failure on unexpected error, never silent success.**
Any unexpected exception during the step is not swallowed; the orchestrator's `runStep`/`runOrBlock`
wrapping converts it into a `FAILED` `ProvisioningStepResult` carrying a detail message, consistent with
`ProvisioningStepResult`'s invariant that `FAILED`/`BLOCKED` outcomes require a non-blank `detail`
(`backend/src/main/java/com/lifeos/application/dto/workspace/ProvisioningStepResult.java`).

## 4. Acceptance criteria

**AC-1 (FR-1, FR-6)**
Given a workspace with a confirmed Dashboard and no ledger entry for `PEOPLE_DB` and no existing
"People" child database under the Dashboard,
When the People database step runs,
Then a new Notion database titled "People" is created as a child of the Dashboard, the ledger records
it under `PEOPLE_DB`, and the step result outcome is `CREATED`.

**AC-2 (FR-2, FR-3, FR-4, FR-5)**
Given the People database is created,
When its schema is inspected,
Then it contains exactly a `title`-type "Name" property and an `email`-type "Email" property, and the
adapter's created payload for the "Email" property is `{"type": "email", "email": {}}`.

**AC-3 (FR-7)**
Given a ledger entry for `PEOPLE_DB` pointing at a Notion database that matches the expected title and
has both required properties present,
When the People database step runs again,
Then no create/patch call is made, the ledger is unchanged, and the step result outcome is
`RECONCILED`.

**AC-4 (FR-5, FR-7)**
Given a ledger entry for `PEOPLE_DB` pointing at a Notion database missing the "Email" property,
When the People database step runs,
Then `repairShape` is called and patches in the missing "Email" property using the `email` type config,
the ledger is re-recorded with the same id, and the step result outcome is `REPAIRED`.

**AC-5 (FR-6, FR-7)**
Given a ledger entry for `PEOPLE_DB` whose Notion database no longer exists (archived/trashed),
When the People database step runs,
Then the system re-searches the Dashboard's children by identity; if a matching orphan is found it is
adopted (and repaired if drifted); if none is found a new database is created; either way the ledger is
re-recorded and the outcome is `REPAIRED`.

**AC-6 (FR-6)**
Given no ledger entry for `PEOPLE_DB` but an existing "People" child database already present under the
Dashboard (orphan from a prior partial run) whose shape matches expectations,
When the People database step runs,
Then no new database is created, the existing one is adopted, the ledger records its id, and the step
result outcome is `RECONCILED`.

**AC-7 (FR-9)**
Given a workspace with no confirmed Dashboard resource,
When the People database step runs,
Then the step fails with a descriptive error identifying the missing Dashboard precondition, and no
Notion API create/patch call for a database is attempted.

**AC-8 (FR-10)**
Given a `workspaceId` that does not resolve to any workspace,
When the People database step runs,
Then the step fails with a descriptive error identifying the missing workspace, and no Notion API call
is attempted.

**AC-9 (FR-11)**
Given more than one child database titled "People" exists under the Dashboard,
When identity resolution runs during the People database step,
Then the step does not adopt either database, and the orchestrator records the step's outcome as
`FAILED` with a non-blank detail describing the ambiguity.

**AC-10 (FR-6, FR-7, AC-3)**
Given the People database step has already reached a stable `CREATED` or `RECONCILED` state,
When the full "Create Workspace" orchestration is re-run any number of additional times with no
external changes to the Notion workspace,
Then each subsequent run yields outcome `RECONCILED` for `PEOPLE_DB` and performs no additional Notion
write calls for that step (convergence).

**AC-11 (FR-12)**
Given the Dashboard step failed or was blocked in a given orchestration run,
When `CreateWorkspaceService` reaches the People database step,
Then the step is not invoked; the orchestrator records outcome `BLOCKED` for `PEOPLE_DB` with detail
"prerequisite step failed or was blocked", per existing `runOrBlock` behavior.

**AC-12 (NFR — no token leakage, FR-13)**
Given the Notion API call underlying any part of the People database step fails (e.g. network error,
4xx/5xx response),
When the resulting `ProvisioningStepResult` (outcome `FAILED`) is produced,
Then its `detail` message contains no Notion integration token, authorization header value, or other
credential material.

**AC-13 (adapter contract test, FR-5)**
Given a `PropertyDefinition` of type `NotionPropertyType.EMAIL`,
When `NotionProvisioningAdapter`'s internal property-config mapping is exercised (directly or via a
`createDatabase`/`repairShape` call captured against a stub/mock Notion HTTP layer),
Then the emitted JSON body for that property is exactly `{"type": "email", "email": {}}` — no
`options`, no extraneous keys.

**AC-14 (FR-12 — pipeline completion)**
Given all seven Phase B database steps (Projects, Tasks, Knowledge, Habits, Journal, Resources, People),
When "Create Workspace" is run end-to-end against a workspace with a confirmed Dashboard,
Then every one of the seven steps yields a non-`FAILED`, non-`BLOCKED` outcome (`CREATED`, `RECONCILED`,
or `REPAIRED`), and `phaseBOk` evaluates true.

## 5. Non-functional requirements

**NFR-1 — Idempotency.** Re-running the People database step, and re-running the full "Create Workspace"
orchestration, must never create a second "People" database for the same workspace or duplicate the
ledger entry. (Mirrors the charter's idempotency mandate for all workspace/database-provisioning use
cases, `CLAUDE.md` "Idempotency" section.)

**NFR-2 — No secret/token leakage.** Error messages and log output produced by this step (including
`NotionApiException` messages and `ProvisioningStepResult.detail`) must never include the Notion
integration token or any authorization header value, consistent with existing exception messages in
`NotionProvisioningAdapter`, which reference only resource ids and counts (`NotionProvisioningAdapter.
java:118-119, 179-180`).

**NFR-3 — Non-destructive repair.** Shape repair for the People database only adds missing properties;
it must not remove or rename properties/data not covered by the expected shape, matching
`repairShape`'s existing "patch only missing" behavior (`NotionProvisioningAdapter.java:199-208`).

**NFR-4 — Consistent outcome vocabulary.** The step must report outcomes using only the existing
`ProvisioningOutcome` values (`CREATED, RECONCILED, REPAIRED, FAILED, BLOCKED`,
`backend/src/main/java/com/lifeos/application/dto/workspace/ProvisioningOutcome.java`); no new outcome
value is introduced by this feature.

**NFR-5 — Backward-compatible port extension.** Adding `NotionPropertyType.EMAIL` must not change the
existing behavior of `TITLE`, `RICH_TEXT`, `SELECT`, `DATE`, or `URL` handling anywhere in
`PropertyDefinition`, `DatabaseSpec`, `ExpectedShape`, or `NotionProvisioningAdapter`. Existing callers
(Projects, Tasks, Knowledge, Habits, Journal, Resources database steps) must continue to compile and
behave unchanged.

**NFR-6 — Testability.** The property-config mapping for `EMAIL` must be verifiable via a contract test
that asserts the exact emitted JSON shape, without requiring a live Notion API call (mirrors existing
adapter test conventions implied by `NotionProvisioningAdapter`'s pure `propertyConfig` helper).

**NFR-7 — Schema/row-validation boundary.** This step creates the `email`-typed Notion column only; it
does not write, validate, or transform any row data. `Email`'s validation logic
(`backend/src/main/java/com/lifeos/domain/person/Email.java:9-17`, a regex-based format check) governs
future `Person` record writes into this database and is out of scope for the schema-provisioning step
itself.

## 6. Data & entities (conceptual only)

- **Person** (existing domain aggregate, `domain/person/Person.java`) — has a `name` (required) and an
  `email` (optional, validated `Email` value object when present); no relation fields on the entity
  today.
- **People Database** (Notion-side projection, not a domain entity) — a child database of the
  workspace's Dashboard page, with two properties: Name (from `Person.name`) and Email (from
  `Person.email`).
- **ProvisionedResource** ledger entry — records the mapping from `(workspaceId, PEOPLE_DB)` to the
  confirmed Notion database id (`domain/workspace/ProvisionedResource.java`,
  `domain/workspace/ProvisionedResourceType.java:5`).

No new domain entity, value object, or relation is introduced.

## 7. Constraints & assumptions

- `[ASSUMPTION]` The People database step's control flow (cold path / warm path / ambiguity handling /
  precondition checks) should exactly mirror `CreateResourcesDatabaseService`'s structure
  (`backend/src/main/java/com/lifeos/application/usecase/resource/CreateResourcesDatabaseService.java`),
  substituting `PEOPLE_DB` for `RESOURCES_DB` and the Name/Email two-property schema for the
  Title/URL two-property schema. This is inferred from the "reusing the idempotent pattern" instruction
  and the identical shape of the six already-implemented database steps; no deviation is specified.
- The dedicated Notion `email` property type (`{"type": "email", "email": {}}`) is the correct mapping
  for `Person.email`, not `rich_text`. Notion's public API models `email` as a first-class property
  type distinct from `rich_text`, and the existing `URL`/`DATE` precedents in this codebase already
  establish that new primitive Notion property types are added to `NotionPropertyType` + `propertyConfig`
  as needed rather than approximated with `RICH_TEXT`/`SELECT`. This spec follows that precedent rather
  than treating `email`-as-`rich_text` as an open question.
- Notion API version `2025-09-03` (data-source model) is assumed to remain the operative API version
  for this step, per the existing `// requires Notion-Version >= 2025-09-03` comments annotating
  `createDatabase`, `verify`, `findChildByIdentity`, and `repairShape` in
  `NotionProvisioningAdapter.java`. This feature does not change API version handling.
- No domain model change is required or in scope; `Person.java` and `Email.java` already carry `name`
  and `email` in a form sufficient to derive the Notion schema (per the feature request's explicit
  statement, confirmed by reading both files).

## 8. Out of scope

- Any relation, rollup, or formula referencing the People database — `Person` has no relation field to
  another aggregate today, and no such property is part of this schema; deferred to Create
  Relations/Rollups/Formulas steps generally, should a future Person↔X relation be introduced.
- Inserting rows or sample data into the People database (deferred to `PopulateSampleDataUseCase`).
- Provisioning of any other child database (Projects, Tasks, Knowledge, Habits, Journal, Resources,
  Goals, Reviews) — each already implemented or separately scoped.
- Any change to the `Person` or `Email` domain entities themselves — none is needed per the feature
  request; both already model `name` and `email` correctly.
- `Email` value-object validation of row-level data written into the People database (regex format
  check at write time) — out of scope; this step only creates the `email`-typed column.
- Any change to `docs/productivity/` methodology content.
- Any change to `CreateWorkspaceService`'s orchestration wiring beyond what already exists (the use case
  is already injected and invoked at line 60).
- Broadening `NotionPropertyType.EMAIL` support to other in-repo features (e.g. adding an email property
  to Projects/Tasks/Knowledge schemas) — this spec adds the port/adapter capability but only consumes
  it for the People database.

## 9. Open questions for the stakeholder

None. The domain schema is fully backed by existing code (`domain/person/{Person,Email}.java`), the
control-flow pattern is fully precedented by `CreateResourcesDatabaseService`, and the one genuine
design choice in this feature (Notion `email` type vs. `rich_text` for `Person.email`) is resolved in
section 7 toward the dedicated `email` type, consistent with the existing `URL`/`DATE`-type precedent in
`NotionProvisioningAdapter`.
