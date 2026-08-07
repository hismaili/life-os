# 02 — Open Questions: Create Workspace

Status: **RESOLVED** — every question below has a recorded human decision (answered 2026-08-04). The design in `02-architecture.md` and the ADRs reflect these answers. Two items are intentionally deferred as tracked future work (see the bottom section); they do not block the pipeline.

---

## Resolved decisions

### OQ-1 — Goals & Reviews databases in v0 scope? → **SEVEN databases; Goals & Reviews DEFERRED**
`docs/architecture/04-Bounded-Contexts.md` lists "Create Goals Database" and "Create Reviews Database", but no `CreateGoalsDatabaseUseCase` / `CreateReviewsDatabaseUseCase` exists in the codebase.
**Decision:** Create Workspace v0 provisions only the **seven** databases that have stubs today — Projects, Tasks, Knowledge, Habits, Journal, Resources, People. Goals & Reviews are **deferred** and tracked as future work (see below). The domain enum reserves `GOALS_DB`/`REVIEWS_DB` slots, but Phase B provisions only the seven.
**Reflected in:** ADR-0005 (phase B = 7 databases); `02-architecture.md` §4/§5/§7 and §8 finding 4.

### OQ-2 — One Workspace per Person, or many? → **MANY per Person**
`CreateWorkspaceCommand` takes a `name`, implying several named workspaces per Person.
**Decision:** A Person may own **many** named workspaces (chosen for convenience and extensibility). `Workspace` is **not** one-per-Person.
**Reflected in:** ADR-0003 (consequences); `02-architecture.md` §5.1 (`WorkspaceRepository`).

### OQ-3 — Idempotency / lookup key → **`(personId, name)`**
**Decision:** Re-run identity is keyed on the pair **`(personId, name)`**. `WorkspaceRepository` exposes `findByPersonIdAndName(personId, name)`; a unique `(person_id, name)` constraint backs it at the store.
**Reflected in:** ADR-0002 (status/decision), ADR-0003; `02-architecture.md` §5.1, §6.

### OQ-4 — CLI-only, or also a REST/API trigger, for v0? → **BOTH CLI and REST**
**Decision:** v0 exposes **both** the Spring Shell CLI command and a REST endpoint. A new `infrastructure.adapter.web` package provides `WorkspaceController` (`POST /api/workspaces`, Jakarta Bean Validation) and `ApiExceptionHandler` returning RFC 9457 `ProblemDetail`. REST authn/authz is out of scope for single-tenant v0 (tracked future work).
**Reflected in:** ADR-0008 (new); `02-architecture.md` §5.5, §6.

### OQ-5 — Sample-data behavior on re-run → **(b) skip / reconcile**
**Decision:** When Create Workspace is re-run with `--sample-data` and sample data already exists, it **skips / reconciles** — treats existing sample data as already present. It does **not** add more records and does **not** reset. `PopulateSampleDataUseCase` is therefore idempotent (gated behind `hasSampleRecords`).
**Reflected in:** ADR-0007 (status/consequences); `02-architecture.md` §5.4, §7 (FR-13).

### OQ-6 — Source of truth for existence & out-of-band Notion edits → **STRICT verification**
**Decision:** Idempotency must be **strict**. Live Notion is the source of truth for existence *and* shape; the local ledger is only a hint/cache. Verification is **verify-before-trust** (existence + shape per step) and **self-healing**: a manually deleted resource is re-created; a renamed/drifted resource is repaired (rename back / add missing properties) and recorded `REPAIRED`. The dual-write window (ADR-0001) is closed as far as feasible because existence is confirmed against Notion, not the ledger — a create-succeeded-but-ledger-write-failed resource is found by deterministic identity on the next run and reconciled rather than duplicated.
**Reflected in:** ADR-0002 (rewritten strict), ADR-0001 (consequences); `02-architecture.md` §4.5, §5.4 (`NotionProvisioningPort.verify/findChildByIdentity/repairShape`).

### OQ-7 — Notion integration token: storage & per-Person scoping → **single process-level secret for v0**
**Decision:** For single-tenant v0 the Notion token is a **single process-level secret** (environment/config), never hardcoded. Per-Person token storage/scoping is **deferred** as a possible future improvement (see below).
**Reflected in:** `02-architecture.md` §6 (Security); the Notion adapter reads one configured token.

---

## Deferred / tracked future work (non-blocking)

1. **Goals & Reviews databases (from OQ-1)** — add `CreateGoalsDatabaseUseCase` and `CreateReviewsDatabaseUseCase` (and their Phase-B wiring + `GOALS_DB`/`REVIEWS_DB` enum activation) in a later release, to reach the full nine-database workspace described in `04-Bounded-Contexts.md`.
2. **Per-Person Notion token storage & scoping (from OQ-7 / OQ-4)** — when LifeOS becomes multi-tenant, replace the single process-level secret with per-Person token storage (secret management + a Person↔token association) and secure the REST endpoint (authn/authz), which is out of scope for single-tenant v0.
