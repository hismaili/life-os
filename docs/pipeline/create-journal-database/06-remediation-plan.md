# 06 — Remediation Plan: Create Journal Database

> Authored by the orchestrator from `findings.yml` (post QA + Audit). One Low finding fixed on the owned file; one Info accepted; one cross-cutting item tracked. No human-decision gate.

## Human-decision gate

**None.** (The one genuine product decision this feature had — the title property — was gated earlier at the architecture stage; the stakeholder chose the domain change, now shipped.)

## Auto-cascade (upstream-first)

| Finding | Sev | Owner | Action | Result |
|---|---|---|---|---|
| AUD-13 | Low | Implementer | `JournalEntry`: `@Builder` → `@Builder(access = AccessLevel.PRIVATE)`, closing the un-validated construction back door, matching `Project`/`Task`/`Goal`. | **Fixed.** grep-verified the factory's own `create(...)` is the only `builder()` caller; `./mvnw verify` stayed green (compile-time proof) — failsafe 25/25, BUILD SUCCESS on Podman. No non-regression *test* is meaningful here — a private builder is a compile-time guarantee (any misuse fails the build), which the green build already enforces. |

## Accepted / tracked (no code change)

| Finding | Sev | Disposition |
|---|---|---|
| AUD-12 | Info | **Accepted.** Inherited name-only-verify trade-off (won't detect a `Date`-column type retype) — Journal has no `select`; cross-reference to the accepted ADR-0008 contract. Reconcile-completeness is a future feature. |
| BUILDER-SWEEP | Low | **Tracked follow-up.** The same public-`@Builder` pattern remains on `Area`, `Habit`, `Knowledge`, `Review`, `Resource` (pre-existing, spans multiple aggregates). Deliberately NOT swept here to keep this feature's touched surface minimal. Recommend a dedicated cleanup: one-line-per-file `@Builder(access = PRIVATE)`, each verified by a green `./mvnw verify`. |

## Re-verify

Full `./mvnw verify` re-run after the AUD-13 fix (the change is a domain-wide annotation, so a scoped run isn't safer than the full one): unit `Tests run: 263`, failsafe `*IT` 25/25, **BUILD SUCCESS** on Podman.

## Outcome

Findings: QA-PASS + 2 audit findings + 1 orchestrator-tracked. **AUD-13 fixed, AUD-12 accepted, BUILDER-SWEEP tracked.** 0 open non-gated findings. 1 iteration, no up-hops, no human gate.
