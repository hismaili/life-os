# ADR-0002: Idempotency via reconciliation with Notion as source of truth

## Status
Accepted (Architect stage). Resolves OQ-3 (idempotency key = `(personId, name)`) and OQ-6 (strict verification: live Notion is the source of truth; out-of-band edits are detected and repaired).

## Context
Idempotency is a hard, project-wide requirement (`CLAUDE.md`; `docs/architecture/04-Bounded-Contexts.md`). FR-10 requires re-runs to create no duplicates and to "verify existing structures against the expected target state." FR-11 requires resume-after-partial-failure. We keep a local provisioning ledger (ADR-0003), but a ledger alone can drift from Notion (manual edits, dual-write gaps).

## Options considered
1. **Ledger-only idempotency** — trust the local record of what we created; skip anything the ledger marks present.
   - (+) Fast, no extra Notion reads. (−) Cannot satisfy FR-10's "verify existing structures" — if a database was deleted in Notion but still in the ledger, the system would wrongly report it present. (−) Dual-write gaps become silent data loss.
2. **Notion-only idempotency** — before each create, query Notion for an existing resource by deterministic identity (root page + type/title).
   - (+) Always reflects actual Notion state (FR-10). (−) Notion permits duplicate titles, so title matching is heuristic and can miss or double-count. (−) More Notion reads (rate-limit pressure, NFR-2).
3. **Ledger as cache + Notion as source of truth (hybrid).** Fast path: if the ledger holds a Notion id, verify it still `exists` in Notion. Cold/miss path: query Notion for an existing child by type. Create only if absent; then record the id.
   - (+) Satisfies FR-10 verification. (+) Reuses stable ids the system itself created, avoiding the duplicate-title ambiguity of pure option 2. (+) Fewer reads than pure option 2 on the warm path. (−) Still has a dual-write window (OQ-6).

## Decision
Adopt option 3, made **strict** (OQ-6 resolved). Each step is independently idempotent: it reconciles its own resource with **live Notion as the sole source of truth for existence and shape**, using the ledger only as a hint/cache. The reconciliation is **verify-before-trust** and **self-healing**:

- **Verify-before-trust.** A ledger id is never trusted on its own. Before treating a resource as present, the step verifies it in Notion for both existence *and* shape (title, parent, key properties) → `PRESENT_MATCHING | PRESENT_DRIFTED | ABSENT`.
- **Repair out-of-band edits.** If a user manually deleted a provisioned resource (`ABSENT`), the step re-creates it; if it was renamed or its shape drifted (`PRESENT_DRIFTED`), the step repairs it (rename back / add missing properties) and records `REPAIRED`.
- **Close the dual-write window.** Because existence is confirmed against Notion (not merely the ledger), a "Notion-create-succeeded-but-ledger-write-failed" resource is found on the next run by deterministic identity (root page + type) and the ledger is reconciled to it rather than a duplicate being created.

The orchestrator adds no idempotency logic of its own beyond load-or-create of the `Workspace` aggregate, keyed on `(personId, name)` (OQ-3 resolved; many workspaces per Person, OQ-2).

## Consequences
- The `NotionProvisioningPort` read surface must support per-step **existence *and* shape verification** and repair — `verify(...)`, `findChildByIdentity(...)`, `repairShape(...)` — not a mere boolean `exists`.
- FR-10 / FR-11 are satisfied strictly, including recovery from manual out-of-band Notion edits.
- Strict verification costs extra Notion reads per step (rate-limit pressure, NFR-2); the adapter must apply backoff.
- Workspace load-or-create uses `WorkspaceRepository.findByPersonIdAndName(personId, name)`.
