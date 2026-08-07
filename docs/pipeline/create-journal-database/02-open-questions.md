# 02 — Open Questions: Create Journal Database

Status: **RESOLVED (2026-08-06).** The single open question (§9 OQ-A) has a recorded **stakeholder decision** and is baked into `02-architecture.md` and ADR-0012. No questions remain for the SME.

---

## Resolved decisions

### OQ-A — What backs the Journal database's mandatory Notion title property? → **Option (c): extend `JournalEntry` with a new nullable `title` field. Domain change, in-scope now.** (spec §9)

**Decision (human/stakeholder, 2026-08-06).** `JournalEntry` had no `title`/`name` field, yet every Notion data source requires exactly one `title`-typed property ([Property object](https://developers.notion.com/reference/property-object)). The stakeholder chose to **extend the domain now** rather than use a placeholder title column — mirroring the Projects OQ-A resolution ("extend the `Project` domain now"). This **overrides** the Architect's initial option-(a) resolution.

**Baked into the design (`02-architecture.md` §4.2; ADR-0012):**
- `JournalEntry` gains a new **`String title`** field, made **optional (nullable)** — a journal entry's essence is `content` + `timestamp`; a title is an optional headline, and a Notion title value may be empty per row. This mirrors the nullable `Project.dueDate` precedent (`content` stays non-blank; `title` is the nullable field). Rationale and Notion citation in ADR-0012. The stakeholder's steer confirmed nullable; this is a modeling call, **not** a further open question.
- `create(...)` gains a leading `title` parameter (position and caller-ripple in §4.2); the private all-args builder threads it through the reconstitution seam; `@Value` immutability and UUID references preserved (`CLAUDE.md`).
- The Notion title property is named **`"Title"`** (domain-backed now, so "Title" is honest and consistent with `Task.title`'s column). `Content` (rich_text ← `content`) and `Date` (date ← `timestamp`) are unchanged.
- **The domain change is an in-scope SME/Implementer deliverable for this branch** — it lands with this feature in the same Implementer pass and is unit-tested (`JournalEntryTest`), analogous to how the Projects branch carried its `ProjectStatus`/`dueDate` domain change (`../create-projects-database/02-open-questions.md` OQ-A; `../create-projects-database/02-architecture.md` §8.9).

Also confirmed and recorded in ADR-0012: `JournalEntry.timestamp` (`LocalDateTime`) maps to a Notion **`date`** property, since Notion's `date` value is an ISO 8601 date "with an optional time" ([Page property values — Date](https://developers.notion.com/reference/page-property-values)) and therefore carries the datetime without truncation.

---

## Tracked follow-up (out of this step's scope)

- **Mandatory-title invariant** — if a non-blank `title` is ever wanted, it is a small additive `create(...)` validation change. Non-blocking; not done here (ADR-0012).
