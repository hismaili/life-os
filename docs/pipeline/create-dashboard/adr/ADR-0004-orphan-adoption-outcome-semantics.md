# ADR-0004: Outcome semantics for orphan adoption

## Status
Accepted (Architect stage, Create Dashboard branch). Resolves the spec's FR-7 `[ASSUMPTION]` — "exact outcome label for adopted-and-matching vs adopted-needing-repair left to the Architect; must not be CREATED."

## Context
`ProvisioningStepResult.outcome` is a closed set: `CREATED | RECONCILED | REPAIRED | FAILED | BLOCKED` (Create Workspace `ProvisioningOutcome`, unchanged). The Dashboard step can reach a confirmed page id by four routes: created new, verified-matching, repaired-drifted, or **adopted** an orphan (a page that already existed in Notion with no ledger entry, FR-7). The spec fixes that adoption "must not be `CREATED`" but leaves the exact label open. The label matters: it drives the `ProvisioningReport`, CLI/REST rendering, `201`-vs-`200` on the REST path (Create Workspace ADR-0008), and multi-run observability (NFR-5).

## Options considered
1. **Always `RECONCILED` for any adoption.**
   - (+) Simple. (−) Hides that a repair (rename-back / restore) actually mutated Notion — indistinguishable from a pure no-op re-run in the report; misleads NFR-5 debugging.
2. **Always `REPAIRED` for any adoption** (adoption is itself "healing" the ledger).
   - (+) Signals "the system did reconciliation work." (−) Conflates a zero-mutation adoption (only a local ledger row written, Notion untouched) with a Notion-mutating repair; over-reports drift.
3. **Split by whether Notion was mutated** (chosen): adopted-and-matching → `RECONCILED`; adopted-but-drifted (a `repairShape` call was made) → `REPAIRED`.
   - (+) The outcome tells the truth about whether Notion changed on this run — the distinction NFR-5 and the `201`/`200` REST rule care about. (+) Consistent with the existing verb meanings (`REPAIRED` ⇔ a Notion write happened; `RECONCILED` ⇔ no Notion write).

## Decision
Adopt option 3, giving the full mapping (architecture §4.2):

| Situation | Notion write this run | Outcome |
|---|---|---|
| No ledger id, no page found → create | `createRootPage` | `CREATED` |
| No ledger id, orphan found, title matches | none | `RECONCILED` |
| No ledger id, orphan found, drifted | `repairShape` | `REPAIRED` |
| Ledger id present, page ok | none | `RECONCILED` |
| Ledger id present, page drifted | `repairShape` | `REPAIRED` |
| Ledger id present, page gone, orphan found | none | `REPAIRED` |
| Ledger id present, page gone, none found → create | `createRootPage` | `REPAIRED` |

Rule of thumb, stated for the SME: **`CREATED`** = a new page was made *and* there was no prior ledger record; **`RECONCILED`** = no Notion mutation occurred this run; **`REPAIRED`** = Notion was mutated (renamed/restored/re-created) to converge on the expected state, or a stale ledger id was healed by re-adoption/re-creation. Adoption is never `CREATED` (no new page).

## Consequences
- The `ProvisioningReport` and logs distinguish "nothing changed" from "we healed Notion," satisfying NFR-5's multi-run-history requirement.
- On the REST path, a pure-adoption-matching run stays `200 OK` (all `RECONCILED`); a run that repaired/re-created returns `201` (Create Workspace ADR-0008), which is truthful.
- No new `ProvisioningOutcome` value is introduced; the closed enum is unchanged.
- The two "ledger id present, page gone" rows are labelled `REPAIRED` (not `CREATED`) because a prior record existed — the step healed a broken reference, it did not provision from scratch.
