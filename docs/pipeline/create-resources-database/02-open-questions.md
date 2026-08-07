# 02 — Open Questions: Create Resources Database

**None — all resolved.**

The spec (`01-spec.md` §9) raised no open questions for the stakeholder, and the Architect pass surfaced none that require a human decision:

- **Schema is fully backed by existing code.** `Resource` (`domain/resource/Resource.java` l.12–13) already carries `title` (non-blank) and `url` (nullable); no domain change is needed or in scope (spec §7).
- **Control flow is fully precedented.** The verify → create/adopt/repair → ledger algorithm is reused verbatim from `CreateTasksDatabaseService` (ADR-0005..0009), substituting `RESOURCES_DB` and the two-property schema.
- **The one genuine design choice — Notion `url` type vs. `rich_text` for `Resource.url` — is resolved, not deferred.** It is decided in favour of the first-class Notion **`url`** property type (config `{"type":"url","url":{}}`), rejecting `rich_text`, per **ADR-0013**. This mirrors the established `DATE` precedent for introducing a first-class primitive Notion type (ADR-0006, ADR-0012) rather than approximating it. It is an Architect-authority modeling call, not a stakeholder question — no domain change is involved (unlike the Projects/Journal OQ-A that required extending an aggregate).

No `findings.yml` escalation is raised. Ready for the SME.
</content>
