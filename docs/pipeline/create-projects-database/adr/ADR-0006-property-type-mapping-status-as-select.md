# ADR-0006: §3 property → Notion property-type mapping; Status is a `select` seeded from the `ProjectStatus` domain enum

## Status
Accepted (Architect stage, Create Projects Database branch). **Reconciled 2026-08-05 after OQ-A was resolved** (human decision: extend the `Project` domain now — see `02-open-questions.md` OQ-A and `02-architecture.md` §5.6). Fixes the Notion property type for each §3 property and the source of the Status column's options. Depends on ADR-0005 (data-source model) and ADR-0007 (typed schema value type).

## Context
Spec §3 requires four properties on the Projects database: **Name** (title), **Description** (free text), **Status**, **Due Date**. Notion needs a concrete property **type** per property at creation and when adding a property on repair. Three map obviously; **Status** is the load-bearing choice, because Notion offers two candidate types — `select` and `status` — with different capabilities and API constraints ([Property object](https://developers.notion.com/reference/property-object)).

**New context from OQ-A (resolved):** the `Project` aggregate now gains a `ProjectStatus` domain enum with a fixed, closed value set (`PLANNED`, `ACTIVE`, `ON_HOLD`, `DONE` — `02-architecture.md` §5.6). This makes the domain the authoritative source of the valid Status values, which sharpens (and confirms) the type choice: the Notion column's options should be **derived from** that enum, not invented in the adapter.

Notion property configuration JSON ([Property object]; [Create a database](https://developers.notion.com/reference/create-a-database)):
- `title` → `{ "type": "title", "title": {} }` (a database must have exactly one title property).
- `rich_text` → `{ "type": "rich_text", "rich_text": {} }`.
- `date` → `{ "type": "date", "date": {} }`.
- `select` → `{ "type": "select", "select": { "options": [ { "name": "<label>" }, ... ] } }`; each option is an object whose `name` is the label (Notion assigns `id`/`color` if omitted) ([Property object] — select options carry `id`, `name`, `color`).
- `status` → single-choice values organised into **groups** (To-do / In-progress / Complete). A status property can be created via the API with default options, **but "to rename, reorder, or otherwise reconfigure groups, use the Notion UI"** ([Property object]) — the group structure is only partially API-manageable.

## Options considered (Status)
1. **`status` property, options/groups from the domain enum.**
   - (+) Semantically the richest fit for "active vs complete" (PARA grounding, spec §3): built-in To-do/In-progress/Complete groups map loosely onto the enum.
   - (−) **Group structure is not fully API-manageable** — groups can only be reconfigured in the UI ([Property object]). Mapping the four-value enum onto Notion's three fixed groups is lossy and cannot be fully reconciled by an idempotent API-only repair loop (NFR-1). A changed group layout becomes un-healable drift.
   - (−) `status` carries the most API caveats; a foundational, pattern-setting step should avoid that fragility.
2. **`select` property, options seeded from the `ProjectStatus` enum** (chosen).
   - (+) **Fully API-manageable**: create, add, and option changes are all achievable via `POST /v1/databases` / `PATCH /v1/data_sources/{id}` with no UI-only escape hatch — consistent with the strict idempotency/repair mandate (NFR-1).
   - (+) **Single source of truth**: the column's options are generated from `ProjectStatus.values()`, so the domain enum — not a hand-kept list in infrastructure — governs the valid Status labels. Adding an enum value is the one place a new option originates.
   - (+) Satisfies the spec: §3 requires the property to **exist**; seeding options additionally makes the column immediately usable and domain-consistent for the future rows/sync work.
   - (−) No built-in grouping (a select is a flat option list). Acceptable — the spec does not require groups, and a flat four-value list matches the enum exactly.
3. **`checkbox` (done/not-done).**
   - (−) A boolean under-models a four-state lifecycle (planned/active/on-hold/done). Rejected.

## Decision
Map the §3 schema as:

| §3 property | Notion type | Config JSON |
|---|---|---|
| **Name** | `title` | `"Name": { "type": "title", "title": {} }` |
| **Description** | `rich_text` | `"Description": { "type": "rich_text", "rich_text": {} }` |
| **Status** | **`select`** | `"Status": { "type": "select", "select": { "options": [ {"name":"Planned"}, {"name":"Active"}, {"name":"On hold"}, {"name":"Done"} ] } }` |
| **Due Date** | `date` | `"Due Date": { "type": "date", "date": {} }` |

**Status is a `select`**, chosen for full API-manageability under the idempotency mandate, **with its options seeded from `ProjectStatus.values()`** so the domain enum is the single source of truth for the column's option set. The service derives each option label from an enum value's display name (e.g. `ON_HOLD → "On hold"`); the option list is authored once in the schema builder (`02-architecture.md` §5.3/§5.6). Per ADR-0008, **verification remains name-only** — a live database whose `Status` property merely has different/extra options is *not* flagged as drift and is *not* repaired, so user-added options are preserved; only the property's **existence** is verified, and creation seeds the enum-derived defaults.

## Consequences
- Every Projects-database property is fully creatable and repairable through the API — no UI-only step breaks idempotency (NFR-1).
- The `ProjectStatus` enum is the authoritative option source; the `select` labels cannot drift from the domain without an enum change. The `NotionPropertyType` enum (ADR-0007) needs exactly `{ TITLE, RICH_TEXT, SELECT, DATE }`; `status` is deliberately absent.
- The typed schema value type (ADR-0007) carries the select options additively so the enum-seeded list reaches the adapter without leaking Notion JSON into the application layer (`02-architecture.md` §5.3).
- If product later needs grouped To-do/In-progress/Complete semantics, migrating `Status` from `select` to `status` becomes a separate, flagged change; the domain enum would then seed status *options* rather than select options.
- Sibling database steps reuse this type-mapping approach; each authors its own §3-equivalent schema.
</content>
