# 01 — Specification: Create Dashboard

Status: Draft for Architect review
Owner (Specifier stage): pipeline automation
Source input: feature request "Create Dashboard" + `CLAUDE.md`, `docs/architecture/03-Domain-Model.md`, `04-Bounded-Contexts.md`, the completed **Create Workspace** pipeline (`docs/pipeline/create-workspace/01-spec.md`, `02-architecture.md`, `adr/ADR-0001..0008`, `03-tech-spec.md`), and existing code under `backend/src/main/java/com/lifeos/{domain/workspace,application/{usecase/workspace,port},infrastructure/adapter/notion}`.

> This spec covers **Phase A only** — the single Provisioning Step that creates/verifies/repairs the workspace's root Notion page (the "Dashboard"). It does not re-specify the orchestrator (`CreateWorkspaceUseCase`), the seven database steps, relations/rollups/formulas, or sample data — those are separate, already-specified or future collaborators. Where this spec repeats a decision already made by Create Workspace's architecture (strict idempotency, per-step ledger transaction, single process-level token), it does so only to bind Create Dashboard to that contract, not to re-litigate it.

## 1. Summary

"Create Dashboard" is the first provisioning step (Phase A) invoked by the Create Workspace orchestrator: given an existing `Workspace` aggregate, it provisions (or, on re-run, verifies and reconciles) the single top-level Notion page — the **Dashboard** — that acts as the root container under which every subsequent database (Projects, Tasks, Knowledge, Habits, Journal, Resources, People, and later Goals/Reviews) will be created as a child. The user value: every workspace has exactly one stable, discoverable entry point in Notion, created once and never duplicated, and any subsequent database-provisioning step has a known, verified parent page id (`rootPageId`) to attach to. This is the first real implementation of a provisioning step against the live Notion API (today `CreateDashboardService` and `NotionProvisioningAdapter` are intentional stubs throwing `UnsupportedOperationException`); this spec defines what "implemented" must mean for this step specifically.

## 2. Actors & stakeholders

- **Primary user (Person aggregate)** — indirectly benefits; does not invoke this step directly. Invokes "Create Workspace," which invokes this step as Phase A.
- **`CreateWorkspaceUseCase` / `CreateWorkspaceService` (orchestrator)** — the sole caller of `CreateDashboardUseCase.execute(workspaceId)`. Treats this step's `ProvisioningStepResult` as the gate for Phase B (the seven database steps are `BLOCKED` if this step fails, per the orchestrator's existing `runOrBlock` logic).
- **`CreateDashboardService` (this step's application service)** — owns the step's idempotent verify/create/repair/record sequence. Depends on `NotionProvisioningPort` and `WorkspaceLedgerWriter`.
- **`NotionProvisioningPort` / `NotionProvisioningAdapter` (infrastructure)** — driven port/adapter that performs the actual Notion API calls (`createRootPage`, `verify`, `findChildByIdentity`, `repairShape`) this step depends on. Making these real for the Dashboard resource is in scope for the downstream Architect/Implementer stages of *this* pipeline branch, not for Create Workspace's.
- **`WorkspaceLedgerWriter`** — the shared, already-implemented collaborator that performs this step's ledger write in its own `@Transactional` unit (per Create Workspace ADR post-audit refinement, `02-architecture.md` §9 / tech spec §14 L1). This spec does not change its contract.
- **Downstream pipeline roles (Architect, SME, Implementer, QA, Auditor)** — consumers of this spec for the Create Dashboard branch.

## 3. Functional requirements

- **FR-1**: The system shall accept a request to provision the Dashboard for an existing `Workspace`, identified by `workspaceId` (`UUID`), matching the existing `CreateDashboardUseCase.execute(UUID workspaceId)` contract. No other input is required — the Dashboard's title/identity is derived from the `Workspace` aggregate's own `name`/`personId`, not supplied separately.
- **FR-2**: Given a `workspaceId` that does not correspond to any persisted `Workspace`, the system shall fail explicitly (not silently no-op), surfacing an error that the orchestrator can convert into a `FAILED` `ProvisioningStepResult`.
- **FR-3**: On a valid request where the Workspace's provisioning ledger has **no** `DASHBOARD` entry, and live verification against Notion finds no matching root page, the system shall create exactly one new Notion page to serve as the Dashboard, titled to identify the workspace `[ASSUMPTION — title derivation, e.g. "{Workspace.name} — LifeOS Dashboard"; exact template is an Architect/SME decision, not fixed here]`, and record it in the ledger with its Notion page id. Outcome: `CREATED`.
- **FR-4**: On a valid request where the ledger has a `DASHBOARD` entry **and** live verification confirms the referenced Notion page still exists and matches the expected shape (title, and — once databases exist — the expected set of child links), the system shall make no write calls to Notion and shall report outcome `RECONCILED`.
- **FR-5**: On a valid request where the ledger has a `DASHBOARD` entry but live verification finds the referenced Notion page **missing** (deleted out-of-band) or **present but drifted** (renamed, moved, or missing expected shape elements), the system shall repair it — re-creating the page if deleted, or correcting the drifted shape (e.g. renaming back) if present-but-wrong — and shall update the ledger entry with the (possibly new) Notion page id. Outcome: `REPAIRED`.
- **FR-6**: Before trusting any ledger-recorded Dashboard id, the system shall verify it live against Notion (existence + expected shape) rather than assuming the ledger is accurate — the ledger is a hint, live Notion is the source of truth (matching the strict-idempotency contract already decided for Create Workspace, `02-architecture.md` ADR-0002).
- **FR-7**: If no ledger entry exists but a page matching the expected Dashboard identity (deterministic identity: e.g. a page directly owned by this integration/workspace with the expected title marker) is found live in Notion, the system shall adopt that page's id into the ledger rather than creating a duplicate — closing the create-succeeded-but-ledger-write-failed race window (mirrors `NotionProvisioningPort.findChildByIdentity`).
- **FR-8**: The system shall record the confirmed Notion page id (whether newly created, reconciled, or repaired) into the Workspace provisioning ledger via the existing `WorkspaceLedgerWriter.record(workspaceId, DASHBOARD, notionId)` collaborator, in that collaborator's own short transaction — this step does not introduce a new transactional write path.
- **FR-9**: The system shall return a `ProvisioningStepResult(DASHBOARD, outcome, detail)` reflecting exactly one of `CREATED`, `RECONCILED`, `REPAIRED`, or `FAILED` for every invocation — never `void`, never a swallowed exception (matches the existing interface signature and `CLAUDE.md` "no silent no-op").
- **FR-10**: When Notion API calls made by this step fail (network error, rate limit, authentication/token failure, unexpected response shape), the system shall not partially update the ledger — either the full verify-and-write sequence for this step succeeds and the ledger reflects the confirmed state, or it fails and the ledger is left exactly as it was before this invocation (no half-written ledger entry). The failure shall propagate as an exception the orchestrator converts to a `FAILED` result (FR-9), not be swallowed inside this step.
- **FR-11**: Re-running this step any number of times for the same `workspaceId` with no out-of-band changes to Notion shall converge to the same single Dashboard page (same Notion id) and never create a second page — this is the idempotency contract this spec exists to bind (`CLAUDE.md`, Create Workspace `04-Bounded-Contexts.md`).
- **FR-12**: This step shall not create, modify, or verify any database, relation, rollup, or formula — it is scoped exclusively to the single root/Dashboard page. Later steps (Phase B onward) are responsible for attaching their databases as children of the page id this step confirms.

## 4. Acceptance criteria (Given/When/Then)

**FR-1 — input contract**
- Given a valid `workspaceId` for an existing `Workspace`, when `CreateDashboardUseCase.execute(workspaceId)` is called, then the step runs without requiring any additional caller-supplied parameter.

**FR-2 — workspace not found**
- Given a `workspaceId` with no corresponding persisted `Workspace`, when the step executes, then it throws (e.g. `IllegalStateException`, matching `WorkspaceLedgerWriter`'s existing "Workspace not found" convention) and makes no Notion API call.

**FR-3 — first-time creation**
- Given a `Workspace` whose ledger has no `DASHBOARD` entry and Notion has no matching root page, when the step executes, then exactly one new Notion page is created, the ledger gains a `DASHBOARD` entry with that page's id, and the returned `ProvisioningStepResult` has `outcome = CREATED`.

**FR-4 — pure reconcile (idempotent re-run, no drift)**
- Given a `Workspace` whose ledger has a `DASHBOARD` entry and live Notion verification confirms that page exists with the expected title/shape, when the step executes, then no Notion write call is made, the ledger is left unchanged, and the returned result has `outcome = RECONCILED`.

**FR-5a — repair after out-of-band deletion**
- Given a `Workspace` whose ledger has a `DASHBOARD` entry but the referenced Notion page no longer exists (deleted by the user directly in Notion), when the step executes, then a new Dashboard page is created, the ledger's `DASHBOARD` entry is updated (upserted) to the new page id, and the returned result has `outcome = REPAIRED`.

**FR-5b — repair after out-of-band drift (rename/shape change)**
- Given a `Workspace` whose ledger has a `DASHBOARD` entry and the referenced Notion page still exists but has been renamed or is missing an expected shape element, when the step executes, then the page's shape is corrected in place (same Notion id retained), the ledger entry is confirmed/updated, and the returned result has `outcome = REPAIRED`.

**FR-6 — verify-before-trust**
- Given a `Workspace` whose ledger has a `DASHBOARD` entry, when the step executes, then it calls Notion to verify that entry's live existence/shape before returning `RECONCILED` — it never returns `RECONCILED` purely because a ledger row is present.

**FR-7 — orphan adoption (race recovery)**
- Given a `Workspace` whose ledger has no `DASHBOARD` entry, but a page matching the expected Dashboard identity already exists live in Notion (e.g. a prior run created it but crashed before the ledger write), when the step executes, then the existing page is adopted (its id recorded in the ledger) rather than a second page being created, and the returned result reflects either `RECONCILED` or `REPAIRED` (not `CREATED`) `[ASSUMPTION — exact outcome label for "adopted, matching shape" vs "adopted, needed repair" left to the Architect; must not be CREATED since no new page was made]`.

**FR-8 — ledger write path**
- Given any of the FR-3/FR-4/FR-5/FR-7 scenarios reach a confirmed Notion page id, when the step completes, then the ledger update is performed through `WorkspaceLedgerWriter.record(workspaceId, DASHBOARD, notionId)` and that write is durably committed in its own transaction, independent of the orchestrator's transaction boundary (there is none, per Create Workspace ADR-0001).

**FR-9 — result contract**
- Given any successful execution path, when the step returns, then the result's `type` is `DASHBOARD` and `outcome` is exactly one of `CREATED`/`RECONCILED`/`REPAIRED`.
- Given any failure path, when the step raises an exception, then no `ProvisioningStepResult` is fabricated by this step itself — the exception propagates for the orchestrator's `runStep` to convert into `FAILED` with a non-blank `detail` (consistent with `ProvisioningStepResult`'s existing compact-constructor requirement).

**FR-10 — no partial ledger write on external failure**
- Given a Notion API call within this step fails (timeout, 5xx, rate limit, 401/403), when the step executes, then the ledger is not modified, and the thrown exception surfaces to the caller unmodified in essence (message may be wrapped, but not swallowed).
- Given the Notion API call succeeds but the subsequent `WorkspaceLedgerWriter.record` call fails (e.g. DB unavailable), when the step executes, then the created/repaired Notion page is left in place (not rolled back — Notion has no transactional rollback) and the next run's FR-7 orphan-adoption path is responsible for reconciling the ledger to the already-existing page.

**FR-11 — idempotent convergence**
- Given the step is run three times consecutively with the same `workspaceId` and no out-of-band Notion changes between runs, when each run completes, then exactly one Dashboard page exists in Notion across all three runs, and the ledger's `DASHBOARD` entry references that same page id after every run.

**FR-12 — scope boundary**
- Given the step completes successfully, when its `NotionProvisioningPort` call log/interactions are inspected, then no `createDatabase`, `ensureRelation`, `ensureRollup`, `ensureFormula`, or sample-data method is invoked by this step.

## 5. Non-functional requirements

- **NFR-1 (Strict idempotency)**: This step must be safely re-runnable any number of times per FR-11, consulting live Notion (not just the ledger) as the source of truth — inherits Create Workspace's ADR-0002 decision; this spec does not weaken it for the Dashboard specifically.
- **NFR-2 (Resilience to external failures)**: A Notion API failure during this step must leave both the ledger and any already-created Notion page in a state the *next* run can reconcile without manual cleanup (FR-10); no compensating transaction/rollback of the Notion side is required or expected.
- **NFR-3 (Testability)**: `CreateDashboardService`'s verify/create/repair/record sequencing logic must be unit-testable against a mocked `NotionProvisioningPort` and `WorkspaceLedgerWriter`, with zero real Notion calls — matching the existing `application` layer's dependency-on-ports-only convention.
- **NFR-4 (No silent no-op)**: Until the Notion adapter's relevant methods (`createRootPage`, `verify`, `findChildByIdentity`, `repairShape`) are genuinely implemented, `CreateDashboardService.execute` must continue to fail explicitly (`UnsupportedOperationException` or equivalent) rather than appear to succeed — this spec's job is to define the target behavior; the Architect/Implementer stages decide the cutover point at which the stub is replaced.
- **NFR-5 (Observability)**: Each invocation's outcome (`CREATED`/`RECONCILED`/`REPAIRED`/failure) must be logged with enough detail (workspaceId, notion page id, prior ledger state) to debug a multi-run reconciliation history — feeds the orchestrator's `ProvisioningReport` (Create Workspace FR-12).
- **NFR-6 (Security/token scoping)**: This step uses the single process-level Notion integration token already decided for v0 (Create Workspace `02-architecture.md` §6, OQ-7); it introduces no new secret or token-scoping requirement. The token must never appear in logs or the `detail` field of a `ProvisioningStepResult`.
- **NFR-7 (Performance)**: `[ASSUMPTION]` No hard latency SLA; a single successful run should require at most a small, bounded number of Notion API calls (one verify + at most one create-or-repair + one ledger write) — no polling loops or unbounded retries within a single invocation.
- **NFR-8 (Rate-limit awareness)**: `[ASSUMPTION — carried from Create Workspace NFR-2]` Notion's documented rate limit (~3 requests/second per integration) applies; this step's implementation must not assume unlimited throughput, though a specific backoff strategy is an Architect/Implementer decision for this branch, not fixed here.

## 6. Data & entities (conceptual only)

- **Workspace**: existing aggregate root (see Create Workspace spec) that owns the provisioning ledger this step reads from and writes to. Referenced here only by its `id` (UUID) — this step never mints or mutates a `Workspace`'s `name`/`personId`.
- **Dashboard**: the single Notion page that is this step's sole subject — a root-level page, owned by the Workspace, with no parent other than the Notion integration's top level. Conceptually: `Workspace 1—0..1 Dashboard` before this step runs, `Workspace 1—1 Dashboard` after a successful run (or after any successful reconciliation).
- **Provisioned Resource (type = DASHBOARD)**: the existing ledger entry value object (`ProvisionedResourceType.DASHBOARD`, `notionId`, `provisionedAt`) this step is responsible for creating/updating. No new domain type is introduced by this spec.
- **Expected Shape (Dashboard)**: the conceptual target state this step verifies against — title, and later (once databases exist) presence of child links to each provisioned database. `[ASSUMPTION]` Whether "child links" are checked by *this* step or only by later steps (since the Dashboard is created before any database exists) is an open question — see §9.

Relationships: unchanged from Create Workspace's conceptual model (`Workspace 1—1 Dashboard`); this spec narrows scope to the mechanics of establishing and maintaining that one relationship link, not the surrounding databases.

## 7. Constraints & assumptions

- Constraint: must implement the existing `CreateDashboardUseCase.execute(UUID workspaceId) : ProvisioningStepResult` signature and the existing `CreateDashboardService` class (currently a stub) without changing the interface shape — the orchestrator (`CreateWorkspaceService`) already depends on this exact contract and is out of this spec's scope to modify.
- Constraint: must use the existing `WorkspaceLedgerWriter.record(...)` collaborator for the ledger write (per Create Workspace's post-audit ADR refinement) — this spec does not reopen the transaction-boundary decision.
- Constraint: must use the existing `NotionProvisioningPort` contract (`verify`, `findChildByIdentity`, `createRootPage`, `repairShape`, and their value types `ExpectedShape`, `VerificationResult`, `DatabaseSpec`, etc.) as already defined in `application/port/` — this spec does not redesign the port; if the Dashboard's needs cannot be met by the existing port shape (e.g. `createRootPage` vs. a generic `create`), that is an Architect-level finding, flagged in §9.
- `[ASSUMPTION]` The Dashboard's identity for verification/adoption purposes (FR-7) is "the one page directly created by this integration token that carries the workspace's deterministic title marker" — no Notion-native concept of "the workspace's designated root page" exists beyond what this system tracks, so identity is necessarily heuristic (title + ownership by the integration), not a guaranteed-unique Notion construct. The exact heuristic is an Architect/SME decision.
- `[ASSUMPTION]` Dashboard title format/content beyond "identifies the workspace" (FR-3) is not fixed by this spec; downstream stages choose it. `[ASSUMPTION]` No page icon/cover/body content requirement is specified — out of scope (§8) unless the stakeholder says otherwise.
- `[ASSUMPTION]` "Verify shape" for the Dashboard, at the point this step alone runs (before any database exists), is limited to title + existence; a *fuller* shape check (e.g. "links to all seven databases") would necessarily be re-verified by a later phase once those databases exist, not owned solely by this step. See open question in §9.
- Constraint: single process-level Notion token (no per-Person token) — inherited unchanged from Create Workspace (`NFR-6`).

## 8. Out of scope

- Provisioning any of the seven (or later nine) core databases — that is Phase B, specified separately, not by this document.
- Establishing relations, rollups, or formulas — Phases C–E, out of scope here.
- Populating sample data — Phase F, out of scope here.
- The orchestrator's (`CreateWorkspaceService`) sequencing, blocking, and reporting logic — already specified and implemented by the Create Workspace pipeline; this spec only defines the contract this one step must fulfill within that sequence.
- Real OAuth / interactive Notion authorization flow — v0 uses a single pre-provisioned process-level integration token (NFR-6); this spec does not introduce or change how that token is obtained.
- Dashboard visual design, navigation UX, icon/cover choice, or rich body content beyond what's needed to identify and later link to databases — a presentation concern for `notion/` assets (per `CLAUDE.md`), not this backend provisioning step.
- Workspace/Dashboard deletion or archival.
- Any change to `NotionProvisioningPort`'s method signatures or value types — if the Architect determines the existing port is insufficient for the Dashboard's needs, that is a finding to raise, not something this spec pre-decides.
- Multi-workspace-per-person Dashboard-naming conflicts beyond what the existing `(personId, name)` uniqueness constraint on `Workspace` already guarantees — each `Workspace` has its own Dashboard; cross-workspace naming collisions in the same Notion account (e.g. two workspaces both wanting a page titled "Dashboard") are an SME/Architect concern, flagged in §9.

## 9. Open questions for the stakeholder

1. What exact information must the Dashboard page display/link to at the moment *this* step runs, before any database exists? Is an empty (or near-empty) placeholder page acceptable for FR-3's initial creation, with navigation links added later (by which step?), or must this step itself pre-create empty placeholder links/blocks for each future database?
2. What is the deterministic identity heuristic for "this is the workspace's Dashboard" (FR-7 orphan adoption) when the ledger has no entry — title text match under the integration's accessible pages? A dedicated metadata marker (e.g. a hidden property)? This affects whether `NotionProvisioningPort.findChildByIdentity` (currently scoped to children of a `rootPageId`) is even the right method signature for finding the *root* page itself, since the Dashboard by definition has no parent root page to search under.
3. Should the Dashboard's title be allowed to collide across two different Workspaces owned by the same Person (e.g. "Personal" and "Work" workspaces both producing a page titled "LifeOS Dashboard")? If so, what disambiguates them in Notion's UI/search beyond the page hierarchy?
4. Is "shape" for FR-5b repair, at this step's point in the sequence, limited to title only, or does it also cover position/parent-container constraints (e.g. "must be a top-level page, not nested under another page")? The existing `ExpectedShape(String title, List<String> requiredPropertyNames)` record's `requiredPropertyNames` concept appears aimed at databases (property schemas), not pages — does the Dashboard need a distinct expected-shape representation, or does `requiredPropertyNames` stay empty for a page?
5. Should this step verify/repair "links to child databases" once those databases exist (i.e., does the Dashboard's expected shape evolve across re-runs after Phase B completes), or is that link-maintenance owned by each database step / a later phase instead? This determines whether "Create Dashboard" is truly a one-shot Phase A step or must be re-entered by later phases.
