# 02 — Open Questions: Create Knowledge Database

**None — all resolved.**

This is a pattern-application step whose architecture was fixed by Create Projects Database (ADR-0005..0008) and Create Tasks Database (ADR-0009). Knowledge's schema is a strict subset of Tasks's (Title + Content only; no `select`, no `date`), so no type-mapping or label decision is even reachable here.

The single deferred item flagged by the spec — the `Content` property's `rich_text`-property-vs-page-body representation, and the 2000-char-per-`rich_text`-object limit — is **decided, not open**: `Content` is a `rich_text` property (ADR-0010), consistent with `Description` on Projects/Tasks. The length limit is inert for this schema-only step (no rows are written); a long-form write strategy is a tracked Phase F follow-up (ADR-0010), not a blocker.

No architectural question requires a stakeholder decision to proceed. Ready for the SME.
</content>
