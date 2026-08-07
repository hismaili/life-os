# ADR-0013: `Resource.url` → Notion `url` property type (reject `rich_text`); add `NotionPropertyType.URL` + adapter `case URL`

## Status
Accepted (Architect stage, Create Resources Database branch). Scope-local to the Resources step plus the **bounded, in-scope port+adapter extension** it carries (`NotionPropertyType.URL` + `NotionProvisioningAdapter.propertyConfig` `case URL`). Depends on ADR-0005 (data-source model), ADR-0007 (typed `DatabaseSpec`/`ExpectedShape`/`PropertyDefinition`/`NotionPropertyType`), and ADR-0008 (name-only verification, non-destructive add-only repair). ADR-0005..0009 are reused unchanged (`../../create-projects-database/adr/`, `../../create-tasks-database/adr/`). This is the **only** new decision this branch makes, and it is an Architect-authority modeling call — **no domain change and no stakeholder question** (unlike the Projects OQ-A `ProjectStatus`/`dueDate` and Journal OQ-A `JournalEntry.title` decisions, which extended aggregates).

## Context
The Resources database (spec §3) needs exactly two properties: **Title** (from `Resource.title`) and **URL** (from `Resource.url`). Notion needs a concrete property **type** per property at creation and when adding a property on repair (ADR-0007). `Title` maps obviously to `title`. The load-bearing choice is the **URL** column, because `Resource.url` (`domain/resource/Resource.java` l.13) is a `String` holding a link, and two Notion types could plausibly carry a link string:

- **`url`** — a first-class Notion property type whose value is "a string with the URL", rendered as a clickable link.
- **`rich_text`** — a free-text property whose value is "an array of rich text objects".

This is the same shape of decision the codebase has already made twice for other primitive types, and this ADR follows that precedent rather than treating it as an open question:

- **`DATE` precedent (ADR-0006, ADR-0012).** `Task.dueDate` (`LocalDate`) and `JournalEntry.timestamp` (`LocalDateTime`) were mapped to a first-class Notion **`date`** property — **not** stringified into `rich_text` — because `date` is "semantically correct, natively sortable/filterable in Notion", and `rich_text` "would stringify … losing native date sorting/filtering and type honesty" (ADR-0012 §Options — timestamp). The `NotionPropertyType` enum and the adapter `propertyConfig` switch were extended with a `DATE` member/branch to carry it. `Resource.url` → `url` is the identical move for the link primitive.

Notion property configuration JSON, from the official reference:
- `url` → `{ "type": "url", "url": {} }` — the configuration is the **empty object** `"url": {}` ([Property object](https://developers.notion.com/reference/property-object); the reference shows `{"id":"BZKU","name":"Project URL","type":"url","url":{}}`, and [Create a database](https://developers.notion.com/reference/create-a-database) accepts this config in `properties`).
- `url` is a **distinct type from `rich_text`**: a `url` page-property value is "a string with the URL", whereas a `rich_text` value is "an array of rich text objects" ([Property object]; [Page property values](https://developers.notion.com/reference/page-property-values)). Notion renders `url` values as clickable links, not plain formatted text.

## Options considered

1. **Notion `url` property, backed by `Resource.url`** *(chosen)*.
   - (+) **Type honesty / semantic fit.** `Resource.url` *is* a URL; the Notion `url` type models exactly that and renders it as a clickable link. A `url` column is natively usable in Notion (open-in-browser, link handling) in a way plain text is not.
   - (+) **Follows the established `DATE` precedent** (ADR-0006, ADR-0012): a first-class primitive Notion type is added to `NotionPropertyType` + `propertyConfig` rather than approximated with `rich_text`/`select`. Consistency across the database-step family.
   - (+) **Fully API-manageable and idempotent-friendly.** `url` has an **empty** configuration object — nothing to reconcile inside the property config (no options, no groups), unlike `select`/`status`. Creation and add-missing repair emit the same `{"type":"url","url":{}}` with no UI-only escape hatch (contrast ADR-0006's rejection of `status` for its UI-only group reconfiguration).
   - (−) Requires a **bounded port+adapter extension** (`NotionPropertyType.URL` + one adapter `case`). Acceptable: it is additive-only (NFR-5), compile-checked (the adapter `switch` is exhaustive with no `default`, so the branch cannot be forgotten — *JLS §14.11.2*), and is precisely the extension the spec scopes in (spec FR-4/FR-5).

2. **Notion `rich_text` property, storing the URL as text** *(rejected)*.
   - (+) **Zero port/adapter change** — `RICH_TEXT` already exists.
   - (−) **Type-dishonest and lossy of function.** Storing a URL as `rich_text` yields plain (or manually-linked) text: Notion does not treat it as a first-class link, loses the `url`-type affordances, and misrepresents the domain field. This is the exact anti-pattern ADR-0012 rejected for `timestamp → rich_text` ("losing … type honesty"). The convenience of avoiding a one-line enum/adapter change does not justify shipping a dishonest schema for a foundational, pattern-setting field.
   - Rejected.

3. **A generic "text-like" catch-all type** *(rejected)*.
   - (−) Collapses distinct Notion primitives (`url`, `email`, `phone_number`, `rich_text`) into one lossy bucket, defeating the typed-schema design (ADR-0007) and the `DATE` precedent. YAGNI cuts the other way here: the codebase already models primitives explicitly. Rejected.

## Decision
- **Map `Resource.url` to a Notion `url` property** named **`"URL"`** (`NotionPropertyType.URL`), config **`{ "type": "url", "url": {} }`**. The database **identity title marker** is the constant **`"Resources"`** (distinct from the `"URL"` property name).
- **Extend the port:** `NotionPropertyType` becomes `{ TITLE, RICH_TEXT, SELECT, DATE, URL }` — additive; existing constants unmoved; existing callers (Projects/Tasks/Knowledge/Habits/Journal) unchanged (NFR-5).
- **Extend the adapter:** add `case URL -> Map.of("type", "url", "url", Map.of())` to `NotionProvisioningAdapter.propertyConfig` (`NotionProvisioningAdapter.java` l.262–270). Because `propertyConfig` is the single config helper called by both `createDatabase` (l.129) and `repairShape`'s add-missing loop (l.202), the `url` config is emitted on **both database creation and add-missing repair** with no second edit (spec FR-5). The `URL` property carries no `options`, satisfying `PropertyDefinition`'s "options only for SELECT" invariant unchanged.

Resulting authored schema (title marker `"Resources"`): `Title` (TITLE ← `Resource.title`) · `URL` (URL ← `Resource.url`). No `select` (no closed-set field), no `date`, no relation (the `knowledgeId → Knowledge` link is deferred to Phase C — spec §8).

## Consequences
- **Bounded extension, additive only.** Three artifacts change (`NotionPropertyType`, `propertyConfig`, the service); nothing else. The enum growth is backward-compatible (NFR-5); the exhaustive `switch` makes the missing branch a compile error (*JLS §14.11.2*), so the extension cannot ship half-done.
- **Verify/repair stays name-only (ADR-0008), so a `url`→`rich_text` retype is not detected.** `verify` compares only the **existence of the property name `"URL"`**, never its Notion type. If a user out-of-band retypes the `URL` column to `rich_text` (or vice versa), it is **not** flagged as drift and is **not** repaired. This is an **accepted consequence**, consistent with every prior feature: Projects/Tasks likewise do not heal a retyped `Status`/`Due Date` column (ADR-0008 §Consequences). The `url` type is therefore only ever *established* at creation/add-missing, never *reconciled* to. If type-level reconciliation is later required, that is a separate, flagged change to the verify/repair contract (ADR-0008), not reopened here.
- **Testability (spec AC-13, NFR-6).** A new adapter contract test asserts `propertyConfig(URL)` emits exactly `{"type":"url","url":{}}` — no `options`, no extra keys — verifiable without a live Notion call via the pure helper (directly or via a captured `createDatabase`/`repairShape` body). This is the one new adapter test in this branch, because the adapter itself changed (contrast the Tasks pass, which changed no adapter).
- **Reusable capability.** `NotionPropertyType.URL` is now available to any future schema, but this spec **only consumes it for the Resources database** (spec §8) — no other schema is retrofitted here.
- **No domain change.** `Resource` already carries `title` + `url` (spec §7); this ADR adds no field, invariant, or aggregate change.
</content>
