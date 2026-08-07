# ADR-0012: Journal title property backed by a new nullable `JournalEntry.title` (option c, stakeholder decision) and `LocalDateTime` → Notion `date` mapping

## Status
Accepted (Architect stage, Create Journal Database branch). **Records a stakeholder decision (2026-08-06) on spec §9 OQ-A** that overrides the Architect's initial resolution — exactly as happened on the Projects OQ-A. Scope-local to the Journal step plus the in-scope `JournalEntry` domain change it carries. Depends on ADR-0005 (data-source model), ADR-0007 (typed `DatabaseSpec`/`ExpectedShape`/`PropertyDefinition`/`NotionPropertyType`), and ADR-0008 (name-only verification, non-destructive add-only repair). ADR-0005..0008 and ADR-0009 are reused unchanged (`../../create-projects-database/adr/`, `../../create-tasks-database/adr/`). This is the **only** new decision this branch makes.

## Context
This step provisions the **Journal** database as a schema-only container (no rows — spec FR-15). Two records this ADR settles.

**(1) The mandatory title property.** Every Notion data source requires exactly one `title`-typed property:
> "All data sources require exactly one `title` property. The API throws errors if you create a data source without a `title` property, or attempt to add or remove a `title` property." — Notion API Reference, [Property object](https://developers.notion.com/reference/property-object).

Unlike the four aggregates provisioned so far (`Project.name`, `Task.title`, and the Knowledge/Habits equivalents), `JournalEntry` (`domain/journal/JournalEntry.java` l.12–16: `id`, `content`, `timestamp`, `workspaceId`, `personId`) had **no** `title`/`name` field. `content` maps to a `rich_text` **Content** column and `timestamp` maps to a `date` **Date** column (both firm, spec §3), but neither is `title`-typed and Notion forbids substituting another type. Spec §9 posed three options: (a) a dedicated empty-semantic title property (no domain change); (b) derive the title's semantic from the entry date (no domain change); (c) add a `title`/`name` field to `JournalEntry` (**domain change**, which `CLAUDE.md` forbids the Architect from making unilaterally — it requires a stakeholder decision, as the Projects OQ-A did).

The Architect initially resolved this as option (a) within its authority. **The stakeholder reviewed and chose option (c)** — extend the domain with a real `title` field — mirroring the Projects OQ-A resolution ("extend the domain now"). This ADR records that decision and the modeling of the new field.

**(2) `LocalDateTime timestamp` → Notion `date`.** `JournalEntry.timestamp` is a `LocalDateTime` (`JournalEntry.java` l.14). The §3 table maps it to a Notion `date` property. This ADR confirms `date` (not `rich_text` or a truncating type) can carry a datetime.

## Options considered — the title property

1. **(c) Add a domain `title` field to `JournalEntry`** — the Notion `Title` property is backed by `JournalEntry.title`. *(chosen — stakeholder decision 2026-08-06)*
   - (+) **Symmetric with the other four aggregates** (`Project.name`, `Task.title`, …): every LifeOS database's title column has a domain source of truth, so a future population/sync step writes `JournalEntry.title` into the Notion title with no placeholder or ad-hoc derivation.
   - (+) **The schema is honest**: the title column is named `"Title"` and genuinely means "the entry's title", not a structural filler.
   - (−) A domain-aggregate change (new field, `create(...)` invariant, reconstitution seam). Acceptable because it is a **deliberate stakeholder decision**, executed via the OQ-A human-decision flow, and lands in-scope with this feature exactly as the Projects `ProjectStatus`/`dueDate` change did.

2. **(a) A dedicated, empty-semantic title property (`"Entry"`), no domain change** — the Architect's initial resolution.
   - (+) Lowest churn for a schema-only step (no rows).
   - (−) Leaves the title column with no domain source of truth; a future population step must invent what to write. The stakeholder judged a domain-backed title worth the change for cross-database symmetry. **Superseded by the stakeholder's choice of (c).**

3. **(b) Derive the title's semantic from the entry date, no domain change.**
   - (−) A Notion `title` is always rich text, so this stores a *text rendering* of `timestamp`, duplicating the separate `date`-typed `Date` column (or folding it in and losing the queryable `date` type), and needs a per-row semantic this schema-only step cannot exercise. Rejected as premature complexity (YAGNI). Not chosen.

## `title` — required or optional? → **optional (nullable)**

This is a **modeling call**, not a further stakeholder question (the stakeholder's steer was explicit: resolve as nullable and proceed). Rationale:

- **The essence of a journal entry is `content` + `timestamp`.** A journal entry is a "time-stamped log of thoughts, events, or reflections" (`docs/architecture/03-Domain-Model.md` l.20) — the body and the moment are what make it an entry. A title is an **optional headline**; a free-form entry is perfectly valid with none. Forcing a non-blank title would push a synthetic value (e.g. a date string) into every headless entry — the very outcome options (a)/(b) were rejected for.
- **Precedent: nullable `Project.dueDate`.** The Projects OQ-A resolution kept `status` non-null (a project always has a lifecycle state) but made `dueDate` **nullable** because a project may have no deadline yet (`../create-projects-database/02-architecture.md` §5.6). `JournalEntry.title` is the same shape of decision: an attribute that is meaningful when present but legitimately absent. `content` stays non-blank (the existing invariant); `title` is the nullable optional field.
- **Notion agrees a title may be empty per row.** Notion requires the title *property* to exist on the schema, but a page's title value may be an empty array — the title property's value can be empty for a given row ([Property object](https://developers.notion.com/reference/property-object); [Page property values](https://developers.notion.com/reference/page-property-values)). A nullable domain `title` maps cleanly: absent title → empty Notion title value.

If the stakeholder later wants a mandatory title, that is a small additive invariant change (`title` non-blank in `create`), tracked as a follow-up — not reopened here.

## Options considered — the `timestamp` property type

1. **Notion `date` property** *(chosen)* — Notion's `date` value is an ISO 8601 date "with an optional time" ([Page property values — Date](https://developers.notion.com/reference/page-property-values)), so it represents a `LocalDateTime` (date **and** time) without truncation. Semantically correct, natively sortable/filterable in Notion.
2. **`rich_text`** — would stringify the timestamp, losing native date sorting/filtering and type honesty. Rejected.

## Decision
- **Title property (stakeholder, option c):** add a new **nullable** `String title` field to `JournalEntry`, and back the Notion title property with it. The Notion title property is named **`"Title"`** (honest now that it is domain-backed, and consistent with `Task.title`'s `"Title"` column — `../create-tasks-database/02-architecture.md` §4.2). The `JournalEntry` domain change lands **in-scope with this feature** (same Implementer pass), designed per `CLAUDE.md`: self-validating via the static `create(...)` factory, immutable `@Value`, reference-by-UUID preserved.
- **`timestamp` mapping:** map `JournalEntry.timestamp` (`LocalDateTime`) to a Notion **`date`** property named **`"Date"`** (`NotionPropertyType.DATE`), relying on `date`'s optional-time support to carry the datetime.
- The database **identity title marker** remains the constant **`"Journal"`** (distinct from the `"Title"` title-*property* name — the two are independent concerns per spec §7).

Resulting authored schema (title marker `"Journal"`): `Title` (TITLE ← `JournalEntry.title`) · `Content` (RICH_TEXT ← `JournalEntry.content`) · `Date` (DATE ← `JournalEntry.timestamp`). No `select` property (`JournalEntry` has no closed-set field — spec §3), no relation property (the `personId → People` link is deferred to Phase C — spec §8, FR-14).

## Consequences
- **Domain change (in-scope, this feature):** `JournalEntry` gains `String title` (nullable); `create(...)` gains a leading `title` parameter (see `02-architecture.md` §4.2 for the exact LLD delta and caller ripple); the private all-args builder threads it through the reconstitution seam; `@Value` immutability and UUID references preserved. Unit-tested with this feature (`JournalEntryTest`).
- `CreateJournalDatabaseService.journalSpec()` authors three `PropertyDefinition`s; `DatabaseSpec`'s "exactly one TITLE" invariant is satisfied by `Title`. No `PropertyDefinition.options` (no `SELECT`), so ADR-0009's label concern does not arise for Journal.
- Because verification is **name-only** (ADR-0008), the nullable title never causes drift/repair; a user may rename or leave the `Title` column empty in Notion without triggering repair. This step writes no rows (FR-15), so no `title` value is populated here regardless.
- **No** `NotionProvisioningPort`/adapter/typed-value-type change (spec §7/§8) — only `domain/journal/` and the service change. The domain change does not gate schema creation (no rows), but is the source of truth for the title column when a future population step runs.
- If a mandatory-title invariant is later wanted, it is a small additive `create(...)` validation change; tracked, not done here.
