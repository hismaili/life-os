# 06 — Remediation Plan: Create Projects Database

> Authored by the orchestrator from `findings.yml` (post QA + Audit). One open Medium finding is auto-cascade (implementer-owned, unambiguous); one Info finding is accepted scope. No item requires a human-decision gate.

## Human-decision gate

**None.** AUD-07 is a robustness fix within the Implementer's existing authority (mirror the guard the sibling `repairPage` already has). AUD-08 is a decision *already made and recorded* in ADR-0006/0008 — no new decision is owed.

## Auto-cascade (upstream-first)

| Order | Finding | Sev | Owner | Action | Non-regression test |
|---|---|---|---|---|---|
| 1 | AUD-07 | Medium | Implementer | In `NotionProvisioningAdapter.repairShape`, guard the initial `client.get("/databases/{id}", …)` result for `null` (HTTP 404 → controlled `NotionApiException`), matching `repairPage` (`NotionProvisioningAdapter.java:73-76`), before `titleOf(current)` is dereferenced. | Add an adapter contract test: `MockRestServiceServer` returns 404 for the `GET /databases/{id}` during repair → expect `NotionApiException` (not `NullPointerException`). |

Owner accepts (no reassignment / up-hop): the fix lives entirely in the infrastructure adapter the Implementer already authored.

## Accepted / tracked (no code change)

| Finding | Sev | Disposition |
|---|---|---|
| AUD-08 | Info/Low | **Accepted scope.** Name-only verify/repair (won't reconcile `select`-option or property-type drift) is the intended v0 contract per ADR-0006/0008 and tech-spec §5.4 — the trade-off that keeps repair provably non-destructive. Recorded as a known caveat; reconcile-completeness is a future feature, not a defect. `status → accepted`. |

## Re-verify (scoped to the change)

Re-run only the adapter contract tier + the failsafe ITs affected by `NotionProvisioningAdapter` (Podman env exported), confirm green, and mark AUD-07 `closed`. No full re-audit — the change is one null guard + one test in an already-audited file.

## Guardrails

Single finding, single owner, no up-hops. Loop expected to converge in **1 iteration**.
