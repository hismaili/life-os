# ADR-0001: No single transaction spanning Notion provisioning

## Status
Accepted (Architect stage). All related open questions resolved (OQ-6: strict verification).

## Context
Create Workspace orchestrates ~15 provisioning steps, most of which make remote calls to the Notion API and also persist provisioning state locally (FR-4–FR-9). FR-11 requires that a run failing partway leaves the workspace in a state from which a later run resumes and reconciles — partial progress must be **durable**. The existing `CreateWorkspaceService.execute` is annotated `@Transactional` and the guidance stub comment implies it would eventually trigger all downstream creation inside that same method.

## Options considered
1. **One outer `@Transactional` around the whole orchestration.**
   - Trade-offs: (−) Holds a DB connection open across many slow remote calls. (−) A mid-run Notion failure rolls back the ledger, erasing the durable progress FR-11 depends on. (−) Notion side effects cannot be rolled back anyway, so the DB and Notion diverge. Spring's transaction guidance is that a transactional service method is one atomic unit of work and should not span external calls that cannot be rolled back (`spring-data-jpa` skill; Spring Framework Reference — Declarative transaction management, docs.spring.io/spring-framework).
2. **No overall transaction; each ledger write is its own short `@Transactional` unit at the service layer, with reconciliation on re-run providing eventual convergence.**
   - Trade-offs: (+) Durable partial progress (FR-11). (+) Short transactions, no connection held across remote I/O. (+) Matches Spring guidance. (−) No cross-step atomicity — mitigated by idempotent reconciliation (ADR-0002). (−) A dual-write window (Notion succeeds, ledger write fails) exists — surfaced as OQ-6.

## Decision
Adopt option 2. The orchestrator (`CreateWorkspaceService.execute`) is **not** `@Transactional`. Each provisioning step service wraps only its ledger write in `@Transactional` on the concrete class (Spring warns interface-level `@Transactional` may be silently ignored — Spring Framework Reference — Declarative transaction management). Cross-step consistency is provided by idempotent reconciliation, not by a database transaction.

## Consequences
- FR-11 is satisfiable: re-running resumes from durable ledger state.
- The design must treat Notion, not the transaction, as the consistency anchor (ADR-0002).
- The current `@Transactional` on the orchestrator must be **removed**; note this for the Implementer.
- A dual-write failure window remains but is closed as far as feasible (OQ-6, resolved: strict verification): on the next run, strict verify-before-trust reconciliation finds a resource that exists in Notion but has no ledger entry (by deterministic identity) and reconciles the ledger to it rather than creating a duplicate.

## Post-audit refinement (2026-08-04)

The per-step ledger transaction was originally placed as a `@Transactional recordLedger(...)` method on each step service, called from that same class's `execute()`. Audit finding L1 (`05-audit-report.md`) noted this is **self-invocation**, which Spring's proxy does not intercept — the write would run with no transaction once wired. Refinement: the transactional read-modify-write now lives in a dedicated `@Component WorkspaceLedgerWriter` (`@Transactional record(...)`) that each step service injects and calls across a proxied bean boundary. This preserves this ADR's decision (orchestrator non-transactional; each ledger write its own short transaction) while making the boundary actually effective.
