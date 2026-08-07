# 01 — Specification: Create Workspace

Status: Draft for Architect review
Owner (Specifier stage): pipeline automation
Source input: feature request "Create Workspace" + `CLAUDE.md`, `docs/architecture/01-Vision.md`, `02-Architecture.md`, `03-Domain-Model.md`, `04-Bounded-Contexts.md`, and existing code under `backend/src/main/java/com/lifeos/domain/workspace/` and `backend/src/main/java/com/lifeos/application/usecase/`.

## 1. Summary

"Create Workspace" is the top-level orchestration capability of LifeOS v0: given a person and a workspace name, the system provisions (or, on re-run, verifies and reconciles) a complete, structured Notion workspace that mirrors the LifeOS domain model — a Dashboard plus one database per core aggregate (Projects, Tasks, Knowledge, Habits, Goals, Journal, Resources, People, Reviews), the relations/rollups/formulas that connect them, and optional sample data. The user value is going from "empty Notion account" to "a fully working, interconnected personal-operating-system workspace" in one command, without hand-building databases or risking duplicate structures on repeated runs. This spec covers the orchestrating use case only; the per-database provisioning use cases it depends on (`CreateTasksDatabaseUseCase`, `CreateProjectsDatabaseUseCase`, etc.) are referenced as collaborators, not re-specified in full here.

## 2. Actors & stakeholders

- **Primary user (Person aggregate)** — the individual who owns the LifeOS workspace and invokes "Create Workspace" via the CLI (Spring Shell) driving adapter.
- **LifeOS application core** — the use case/service layer that orchestrates provisioning; owns correctness of sequencing and idempotency.
- **Notion adapter (infrastructure)** — driven adapter that performs the actual page/database creation calls against the Notion API. `[ASSUMPTION]` Notion is the only provisioning target for v0 (per `docs/architecture/01-Vision.md`, "Notion is just the first supported adapter").
- **Persistence adapter (infrastructure)** — driven adapter that stores the `Workspace` aggregate and its provisioning state so re-runs can detect existing structures.
- **Downstream pipeline roles (Architect, SME, Implementer, QA, Auditor)** — consumers of this spec; not actors of the running system.

## 3. Functional requirements

- **FR-1**: The system shall accept a request to create a workspace consisting, at minimum, of a workspace `name` and the `personId` of the owning Person. (Matches existing `CreateWorkspaceCommand(name, personId)`.)
- **FR-2**: The system shall reject a create-workspace request when `name` is null or blank, without contacting any external adapter.
- **FR-3**: The system shall reject a create-workspace request when `personId` is null. `[ASSUMPTION]` — current `CreateWorkspaceCommand` does not yet enforce this; spec requires it since a workspace must always be owned by a Person.
- **FR-4**: On a valid request where no workspace exists yet for the given `personId` (or workspace identity, per Architect's decision on identity strategy), the system shall create a new `Workspace` aggregate and persist it.
- **FR-5**: On a valid request, the system shall provision a top-level Dashboard page as the single entry point of the workspace, containing navigation to each database.
- **FR-6**: On a valid request, the system shall provision one database per core aggregate defined in the domain model: Projects, Tasks, Knowledge, Habits, Goals, Journal, Resources, People, Reviews.
- **FR-7**: On a valid request, the system shall establish the inter-database relations required by the domain model (e.g., Task↔Project, Project↔Goal, Project↔Area, Knowledge↔Resource, Task↔Habit where applicable), per `docs/architecture/03-Domain-Model.md` and `05-Ubiquitous-Language.md`.
- **FR-8**: On a valid request, the system shall configure rollups that aggregate data across the relations established in FR-7 (e.g., completed-task count on a Project).
- **FR-9**: On a valid request, the system shall configure formulas needed for derived status/progress fields (e.g., Project completion percentage) as defined by the domain model's derived values.
- **FR-10**: Re-running "Create Workspace" with the same `personId`/workspace identity shall be idempotent: it shall not create duplicate Dashboard pages, databases, relations, rollups, or formulas. Existing structures shall be verified against the expected target state and reconciled (missing pieces created, matching pieces left untouched).
- **FR-11**: If provisioning fails partway (e.g., 3 of 9 databases created before an error), the system shall leave the workspace in a state from which a subsequent "Create Workspace" run can resume/reconcile to completion, per FR-10, rather than requiring manual cleanup.
- **FR-12**: The system shall report, on completion, which structures were newly created versus already-existing/reconciled versus failed, so the caller can confirm provisioning outcome. `[ASSUMPTION]` — exact reporting format (CLI output, return DTO) is left to the Architect.
- **FR-13**: The system shall support an optional flag/parameter to also populate the workspace with sample data after structural provisioning completes (per "Populate Example Data" in `04-Bounded-Contexts.md`); sample-data population must run only after all structural provisioning (FR-4–FR-9) succeeds.
- **FR-14**: An unimplemented downstream provisioning step (e.g., a stub such as `CreateTasksDatabaseService`) shall cause the overall "Create Workspace" operation to fail explicitly and audibly (matching the workspace-wide "no silent no-op" convention in `CLAUDE.md`), not silently skip that database.

## 4. Acceptance criteria (Given/When/Then)

**FR-1 / FR-2 / FR-3 — input validation**
- Given a create-workspace request with a blank `name`, when the use case executes, then it throws an `IllegalArgumentException` (or equivalent validation failure) and no persistence or Notion call occurs.
- Given a create-workspace request with `personId = null`, when the use case executes, then it throws a validation failure and no persistence or Notion call occurs.
- Given a create-workspace request with a non-blank `name` and a valid `personId`, when the use case executes, then no validation error is raised.

**FR-4 — workspace creation**
- Given no existing workspace for the given `personId`, when "Create Workspace" runs with a valid request, then a new `Workspace` record is persisted with a generated `id`, the given `name`, and an association to `personId`.

**FR-5 — dashboard**
- Given a newly provisioned or existing workspace, when "Create Workspace" runs, then exactly one Dashboard page exists at the top level of the workspace, linking to all nine core databases.

**FR-6 — databases**
- Given a valid request, when "Create Workspace" completes successfully, then exactly one database each for Projects, Tasks, Knowledge, Habits, Goals, Journal, Resources, People, and Reviews exists in the workspace.

**FR-7/FR-8/FR-9 — relations, rollups, formulas**
- Given all nine databases exist, when relation configuration runs, then each relation defined in the domain model (e.g., Task→Project) is present and bidirectional where the domain model specifies it.
- Given relations exist, when rollup configuration runs, then each specified rollup field exists on its owning database and aggregates the correct related field.
- Given relations/rollups exist, when formula configuration runs, then each specified formula field exists and evaluates using the documented derivation logic (verified functionally by QA once implemented; this spec only requires the field's existence and documented intent).

**FR-10 — idempotency**
- Given a workspace that was already fully provisioned by a prior "Create Workspace" run, when "Create Workspace" is run again with the same `personId`, then no duplicate Dashboard, databases, relations, rollups, or formulas are created, and the operation completes successfully reporting zero new structures created.
- Given a workspace that is partially provisioned (e.g., Dashboard and 4 of 9 databases exist), when "Create Workspace" is run again, then only the missing structures (remaining 5 databases, plus relations/rollups/formulas depending on them) are created, and the existing 4 databases are left unmodified in their identity (same Notion page/database ID).

**FR-11 — resume after partial failure**
- Given a "Create Workspace" run that fails after creating the Dashboard and 2 databases (e.g., due to a transient Notion API error), when the operation is retried, then it resumes from the point of failure — the existing Dashboard and 2 databases are reused, not duplicated, and provisioning continues for the remaining databases.

**FR-12 — reporting**
- Given a "Create Workspace" run completes (fully or partially), when it returns, then the caller receives a summary distinguishing newly-created, already-existing/reconciled, and failed structures.

**FR-13 — sample data (optional)**
- Given a request with the sample-data flag enabled, when structural provisioning (Dashboard + 9 databases + relations/rollups/formulas) completes successfully, then sample records are created in each database respecting the configured relations.
- Given a request with the sample-data flag enabled, when structural provisioning fails, then no sample data is populated.
- Given a request without the sample-data flag, when "Create Workspace" completes, then no sample data is created.

**FR-14 — explicit failure on stubbed dependencies**
- Given a downstream provisioning use case is an unimplemented stub (throws `UnsupportedOperationException`), when "Create Workspace" invokes it, then the overall operation fails with that exception surfaced to the caller (not swallowed), and FR-12 reporting reflects it as failed.

## 5. Non-functional requirements

- **NFR-1 (Idempotency)**: All provisioning use cases invoked by "Create Workspace" must be safely re-runnable per FR-10/FR-11; this is a hard project-wide requirement (`CLAUDE.md`, `docs/architecture/04-Bounded-Contexts.md`).
- **NFR-2 (Resilience to external failures)**: Failures from the Notion adapter (rate limits, network errors, auth errors) must not corrupt already-persisted workspace state; partial progress must remain reconcilable per FR-11. `[ASSUMPTION]` retry/backoff strategy for Notion API rate limits is left to the Architect; Notion's documented rate limit is ~3 requests/second per integration `[ASSUMPTION — verify against current Notion API docs at implementation time]`.
- **NFR-3 (Testability)**: The domain layer (`Workspace`, its invariants) must remain testable with zero knowledge of Notion or persistence, per the hexagonal architecture rule in `CLAUDE.md`.
- **NFR-4 (No silent no-ops)**: Any unimplemented step must fail explicitly, never appear to succeed (FR-14).
- **NFR-5 (Observability)**: Each provisioning step's outcome (created/reconciled/failed) must be logged and reflected in the FR-12 summary to support debugging multi-step failures.
- **NFR-6 (Data ownership/security)**: The workspace and its Notion integration token are scoped to a single Person; no cross-person data leakage. `[ASSUMPTION]` — v0 is single-tenant/local-use; no multi-user auth model is specified.
- **NFR-7 (Performance)**: `[ASSUMPTION]` No hard latency SLA is set for v0 given Notion API's own rate limits dominate wall-clock time; full provisioning of a new workspace should complete in a single run without requiring manual intervention under normal (non-rate-limited) conditions.
- **NFR-8 (Compliance/retention)**: Not applicable for v0 — LifeOS stores personal productivity data, not regulated categories (health/financial records) in this feature. `[ASSUMPTION]`.
- **NFR-9 (Safe error responses)** `[added 2026-08-04 following security audit H1/M1]`: Error responses returned over the REST boundary must not expose internal implementation details (raw exception messages, Notion API payloads, SQL/constraint text, stack traces). Every failure path must return a controlled RFC 9457 `ProblemDetail` with a generic client message while the full detail is logged server-side. This refines NFR-6 (no leakage) for the network boundary; the local CLI surface, which is not a network boundary, may still show per-step detail to the operator.

## 6. Data & entities (conceptual only)

- **Workspace**: top-level container; has an identity, a `name`, and is owned by exactly one `Person`. Aggregates provisioning state (which databases/pages exist).
- **Person**: the owner of a Workspace; referenced by `personId` (UUID reference, not object graph, per project convention).
- **Dashboard**: a single navigational page belonging to a Workspace, linking to each provisioned database.
- **Provisioned Database** (one per core aggregate: Project, Task, Knowledge, Habit, Goal, JournalEntry, Resource, Person, Review): a structured collection within the Workspace corresponding to a domain aggregate.
- **Relation**: a named link between two Provisioned Databases (e.g., Task↔Project).
- **Rollup**: a derived aggregate field on a Provisioned Database, computed over a Relation.
- **Formula**: a derived field on a Provisioned Database computed from its own or related fields.
- **Provisioning Outcome / Report**: a conceptual record of what was created/reconciled/failed during a "Create Workspace" run (for FR-12); not necessarily a persisted aggregate.

Relationships: `Person 1—1 Workspace`, `Workspace 1—1 Dashboard`, `Workspace 1—* Provisioned Database`, `Provisioned Database *—* Provisioned Database` (via Relation), `Provisioned Database 1—* Rollup/Formula (fields)`.

## 7. Constraints & assumptions

- `[ASSUMPTION]` Notion is the sole provisioning target for v0; the use case is adapter-agnostic in interface but only the Notion adapter will exist initially.
- `[ASSUMPTION]` One Workspace per Person for v0 (no multi-workspace-per-person support); the Architect should confirm this against `CreateWorkspaceCommand`'s current shape.
- `[ASSUMPTION]` "Idempotent" is interpreted as: identical inputs re-run any number of times converge to the same target state, never duplicating structures — consistent with the explicit statement in `CLAUDE.md` and `docs/architecture/04-Bounded-Contexts.md`.
- The nine core databases are fixed by the existing use case inventory (`CreateProjectsDatabaseUseCase`, `CreateTasksDatabaseUseCase`, `CreateKnowledgeDatabaseUseCase`, `CreateHabitsDatabaseUseCase`, `CreateJournalDatabaseUseCase`, `CreateResourcesDatabaseUseCase`, `CreatePeopleDatabaseUseCase`, plus Goals and Reviews per `04-Bounded-Contexts.md`, which currently lack corresponding use case files in the repo — flagged as an open question below).
- Constraint: must follow the hexagonal architecture layering already established (`domain` → `application` → `infrastructure`) and existing conventions in `CLAUDE.md` (self-validating entities, UUID references, explicit failure for stubs).
- Constraint: `CreateWorkspaceUseCase`, `WorkspaceRepository`, `Workspace`, and `CreateWorkspaceCommand` already exist in skeletal form and should be extended, not replaced, unless the Architect determines a breaking change is required.

## 8. Out of scope

- Implementation details of the Notion API adapter (auth flow, SDK choice, request batching) — Architect/SME/Implementer concern.
- The internal design of each per-database provisioning use case (field types, Notion property schemas) — separate feature specs if not already covered by existing stub use cases.
- Multi-tenant or multi-user workspace sharing.
- Non-Notion adapters (Obsidian, Capacities, Web UI) — explicitly future work per `01-Vision.md`.
- Workspace deletion, archival, or migration between adapters.
- Authentication/authorization model for who may invoke "Create Workspace" beyond the single-owner assumption (NFR-6).
- Detailed sample-data content/realism criteria beyond "respects configured relations" (FR-13) — a follow-on spec for "Populate Example Data" should own this in detail.
- Formula/rollup *business logic* correctness (e.g., exact progress-percentage formula) — owned by the domain model docs (`03-Domain-Model.md`) and the SME stage, not restated here beyond "must exist and be documented."

## 9. Open questions for the stakeholder

1. `docs/architecture/04-Bounded-Contexts.md` lists "Create Goals Database" and "Create Reviews Database" as required use cases, but no `CreateGoalsDatabaseUseCase` or `CreateReviewsDatabaseUseCase` exists in the current codebase (only Projects, Tasks, Knowledge, Habits, Journal, Resources, People do). Should "Create Workspace" v0 depend on these two once created, or is Goals/Reviews provisioning deferred to a later release, with "Create Workspace" v0 covering only the seven databases that currently have use case stubs?
2. Is "Workspace" strictly one-per-Person, or should the system support a Person requesting multiple named workspaces (e.g., "Personal" vs "Work")? Current `CreateWorkspaceCommand` takes a `name`, suggesting multiple workspaces might be intended.
3. What identity/lookup key should idempotency be keyed on — `personId` alone, or `(personId, name)`? This affects what "re-running Create Workspace" means when a Person could have multiple workspaces.
4. Should "Create Workspace" be exposed only via the CLI (Spring Shell) for v0, or is a REST/API trigger also in scope, per the "Future: web" note in `03-Domain-Model.md`?
5. What is the expected behavior of FR-13's sample data flag with respect to idempotency — should re-running with the flag enabled add more sample data, or also reconcile (skip if sample data already present)?
