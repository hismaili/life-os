# 02 — Open Questions: Create People Database

**None — all resolved.**

The one genuine design choice in this feature — Notion `email` type vs. `rich_text` for `Person.email` — is resolved toward the dedicated **`email`** property type in **ADR-0014**, exactly as the immediately-preceding sibling resolved `url` vs. `rich_text` for `Resource.url` in **ADR-0013** (following the `DATE` precedent, ADR-0006/ADR-0012). No stakeholder input is required:

- **Domain schema is fully backed by existing code.** `Person` already carries `name` (required, non-blank via `Person.create`) and `email` (nullable `Email` value object) — `domain/person/{Person,Email}.java`. No domain change is in scope (spec §7).
- **Control flow is fully precedented.** The step is a verbatim mirror of `CreateResourcesDatabaseService` (warm/cold path, adoption, outcome mapping), substituting `RESOURCES_DB → PEOPLE_DB` and the `"People"` two-property schema. No deviation is specified (spec §7 `[ASSUMPTION]`, already validated against the shipped Resources service).
- **The property-type decision is an Architect-authority modeling call**, not a stakeholder question — it adds no field, invariant, or aggregate (ADR-0014 §Status), unlike the Projects/Journal aggregate-extending decisions.

Ready for the SME.
