# 02 — Open Questions (Architecture): Audit Remediation

**Status: EMPTY — no blocking architectural questions.**

The spec's three open questions (§9) were resolved by the stakeholder before this design and are
recorded as constraints in `02-architecture.md` §0:

1. FR-1 token masking → **full, non-reversible redaction** (ADR-0010).
2. cli-wiring AUD-004 → **in scope**; application-layer sanitization boundary (ADR-0013).
3. cli-wiring AUD-005 → **in scope**; CLI-adapter display-label mapping (ADR-0014).

No new blocking question surfaced during design. All in-scope requirements (FR-1…FR-8 + AUD-004 +
AUD-005) are traceable to a component and covered by an ADR or a trivial-change note. The remaining
deferrals (notion AUD-004/AUD-005, retry-design assumptions, ISP observation) are intentional per
spec §7 and are not questions.

Implementation-choice `[ASSUMPTION]`s that are **not** blocking (delegated to the SME within
guardrails):
- FR-5 pagination cap **value** (ADR-0012 recommends `MAX_SEARCH_PAGES = 50`; any enforced bound
  satisfies the requirement).
- FR-8 concise-message **wording** and the exact Spring Shell output-writer mechanism (ADR-0015
  fixes the *write-then-throw-concise* shape; wording is the SME's).
- ADR-0013 coupling style (`instanceof NotionApiException` vs. a `SafeToSurfaceException` marker
  interface) — either satisfies the boundary; SME's call.

If the SME hits a genuine architectural conflict while detailing these, raise it via
`findings.yml` (`raised_by: spring-architect` / `suspected_layer: spec`) rather than guessing.
