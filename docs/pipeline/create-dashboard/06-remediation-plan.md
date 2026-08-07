# 06 — Remediation Plan: Create Dashboard

> Reconstructed 2026-08-05 during pipeline resume. The remediation loop already ran and closed on 2026-08-05 (tracked inline in `04-qa-report.md §9` and `05-audit-report.md §5`); this file records the routing per the `spring-findings-protocol` contract. Input: `findings.yml`.

## Human-decision gate

**None.** No finding required a human architectural/security decision — the six architectural open questions were already resolved in `02-open-questions.md` (answered 2026-08-05). Every audit/QA finding was an implementation- or build-config-level fix within an existing owner's authority. No gate; no ADR change.

## Auto-cascade (executed, upstream-first)

| Order | Finding | Owner | Action | Non-regression test | Status |
|---|---|---|---|---|---|
| 1 | AUD-01 (High, blocking) | Implementer | Bounded connect/read timeouts on Notion `RestClient` via new `NotionClientConfiguration` (`RestClientCustomizer`) | `NotionClientTest` | closed |
| 2 | AUD-02 (Medium) | Implementer | Clamp `Retry-After` sleep to an upper bound | `NotionClientTest` | closed |
| 3 | AUD-03 (Medium) | Implementer | Percent-encoded URI variables for page/db ids | `NotionProvisioningAdapterTest` (+1) | closed |
| 4 | AUD-04 (Medium) | Implementer | `/v1/search` pagination so >1 identity match → `FAILED` (never-duplicate invariant) | `NotionProvisioningAdapterTest` (+2) | closed |
| 5 | AUD-05 (Low) | Implementer | Inject shared Spring `ObjectMapper` | existing adapter tests | closed |
| 6 | AUD-06 / QA-FAILSAFE (Info) | Build-config | Declare `maven-failsafe-plugin` (`integration-test`+`verify`) so `*IT` run under `verify` | ITs now gated | closed |

All items were **ACCEPTED** by their proposed owner — zero up-hops, zero reassignments.

## Cascade down

Implementer fixes (AUD-01..05) flowed to QA for re-verification of the changed HTTP surface only. Build-config fix (AUD-06) verified by observing both ITs execute under failsafe.

## Re-verification (scoped to the change)

- Command: `TESTCONTAINERS_RYUK_DISABLED=true ./mvnw verify`
- Result: **BUILD SUCCESS** — unit `Tests run: 133, Failures: 0` (was 126; +7 regression tests across `NotionClientTest` and `NotionProvisioningAdapterTest`); failsafe `*IT` `Tests run: 5, Failures: 0`.
- New/changed files: `NotionClient`, `NotionClientConfiguration` (new), `NotionProvisioningAdapter`, `NotionClientTest` (new), `NotionProvisioningAdapterTest` (+3), `backend/pom.xml`.

## Outcome

- **Iterations:** 1 (converged on first pass).
- **Findings:** 6 opened (5 audit + 1 QA; AUD-06 duplicates QA-FAILSAFE) → 6 closed → 0 open, 0 escalated.
- **Guardrails:** none tripped — no recurrence, no up-hop cap, well under the 3-iteration limit.
- Nothing committed; all changes remain in the working tree.
