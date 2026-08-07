# ADR-0014: `Person.email` → Notion `email` property type (reject `rich_text`); add `NotionPropertyType.EMAIL` + adapter `case EMAIL`

## Status
Accepted (Architect stage, Create People Database branch). Scope-local to the People step plus the **bounded, in-scope port+adapter extension** it carries (`NotionPropertyType.EMAIL` + `NotionProvisioningAdapter.propertyConfig` `case EMAIL`). Depends on ADR-0005 (data-source model), ADR-0007 (typed `DatabaseSpec`/`ExpectedShape`/`PropertyDefinition`/`NotionPropertyType`), and ADR-0008 (name-only verification, non-destructive add-only repair). ADR-0005..0009 and **ADR-0013** (the immediately-preceding `url` property-type extension) are reused unchanged by reference (`../../create-projects-database/adr/`, `../../create-tasks-database/adr/`, `../../create-resources-database/adr/ADR-0013-url-property-type.md`). This is the **only** new decision this branch makes, and it is an Architect-authority modeling call — **no domain change and no stakeholder question** (`Person` already carries `name` + an `Email` value object; unlike the Projects OQ-A / Journal OQ-A decisions, which extended aggregates).

## Context
The People database (spec §3) needs exactly two properties: **Name** (from `Person.name`) and **Email** (from `Person.email`). Notion needs a concrete property **type** per property at creation and when adding a property on repair (ADR-0007). `Name` maps obviously to `title`. The load-bearing choice is the **Email** column, because `Person.email` (`domain/person/Person.java` l.14) is an `Email` value object wrapping a `String` address (`domain/person/Email.java`), and two Notion types could plausibly carry an address string:

- **`email`** — a first-class Notion property type whose value is a string containing an email address, with native mailto/link affordances.
- **`rich_text`** — a free-text property whose value is "an array of rich text objects".

This is the same shape of decision the codebase has already made three times for other primitive types (`date` for `Task.dueDate`/`JournalEntry.timestamp`, `url` for `Resource.url`), and this ADR follows that precedent rather than treating it as an open question:

- **`URL` precedent (ADR-0013).** `Resource.url` (a link `String`) was mapped to a first-class Notion **`url`** property — **not** stringified into `rich_text` — for type honesty, native link affordances, and consistency with the `DATE` precedent (ADR-0006, ADR-0012), which likewise rejected `rich_text` as "losing … type honesty". The `NotionPropertyType` enum and the adapter `propertyConfig` switch were extended with a `URL` member/branch to carry it. `Person.email` → `email` is the identical move for the address primitive.

Notion property configuration JSON, from the official reference:
- `email` → `{ "type": "email", "email": {} }` — the configuration is the **empty object** `"email": {}`. The reference shows a property such as `{"id":"oZbC","name":"Contact email","type":"email","email":{}}` ([Property object](https://developers.notion.com/reference/property-object)), and [Create a database](https://developers.notion.com/reference/create-a-database) accepts this config in `properties`.
- `email` is a **distinct type from `rich_text`**: an `email` value "contains email address values" (a string), whereas a `rich_text` value "contains text values" (an array of rich text objects) ([Property object](https://developers.notion.com/reference/property-object); [Page property values](https://developers.notion.com/reference/page-property-values)). Notion renders `email` values with native address affordances, not plain formatted text.

## Options considered

1. **Notion `email` property, backed by `Person.email`** *(chosen)*.
   - (+) **Type honesty / semantic fit.** `Person.email` *is* an email address (validated by the `Email` VO's regex, `Email.java` l.7,14); the Notion `email` type models exactly that and renders it with native address affordances. An `email` column is natively usable in Notion in a way plain text is not.
   - (+) **Follows the established `URL`/`DATE` precedent** (ADR-0013, ADR-0006, ADR-0012): a first-class primitive Notion type is added to `NotionPropertyType` + `propertyConfig` rather than approximated with `rich_text`/`select`. Consistency across the database-step family — and closes the honest-mapping gap the spec identifies for `Person.email` (spec §1).
   - (+) **Fully API-manageable and idempotent-friendly.** `email` has an **empty** configuration object — nothing to reconcile inside the property config (no options, no groups), unlike `select`/`status`. Creation and add-missing repair emit the same `{"type":"email","email":{}}` with no UI-only escape hatch.
   - (−) Requires a **bounded port+adapter extension** (`NotionPropertyType.EMAIL` + one adapter `case`). Acceptable: it is additive-only (NFR-5), compile-checked (the adapter `switch` is exhaustive with no `default`, so the branch cannot be forgotten — *JLS §14.11.2*), and is precisely the extension the spec scopes in (spec FR-4/FR-5).

2. **Notion `rich_text` property, storing the address as text** *(rejected)*.
   - (+) **Zero port/adapter change** — `RICH_TEXT` already exists.
   - (−) **Type-dishonest and lossy of function.** Storing an email as `rich_text` yields plain text: Notion does not treat it as a first-class address, loses the `email`-type affordances, and misrepresents the domain field — a field the domain already models as a dedicated `Email` value object. This is the exact anti-pattern ADR-0013 rejected for `url → rich_text` and ADR-0012 rejected for `timestamp → rich_text`. The convenience of avoiding a one-line enum/adapter change does not justify shipping a dishonest schema.
   - Rejected.

3. **A generic "text-like" catch-all type** *(rejected)*.
   - (−) Collapses distinct Notion primitives (`email`, `url`, `phone_number`, `rich_text`) into one lossy bucket, defeating the typed-schema design (ADR-0007) and the `URL`/`DATE` precedent. YAGNI cuts the other way here: the codebase already models primitives explicitly. Rejected.

## Decision
- **Map `Person.email` to a Notion `email` property** named **`"Email"`** (`NotionPropertyType.EMAIL`), config **`{ "type": "email", "email": {} }`**. The database **identity title marker** is the constant **`"People"`** (distinct from the `"Email"` property name).
- **Extend the port:** `NotionPropertyType` becomes `{ TITLE, RICH_TEXT, SELECT, DATE, URL, EMAIL }` — additive; existing constants unmoved; existing callers (Projects/Tasks/Knowledge/Habits/Journal/Resources) unchanged (NFR-5).
- **Extend the adapter:** add `case EMAIL -> Map.of("type", "email", "email", Map.of())` to `NotionProvisioningAdapter.propertyConfig` (`NotionProvisioningAdapter.java` l.262–271). Because `propertyConfig` is the single config helper called by both `createDatabase` (l.129) and `repairShape`'s add-missing loop (l.202), the `email` config is emitted on **both database creation and add-missing repair** with no second edit (spec FR-5). The `EMAIL` property carries no `options`, satisfying `PropertyDefinition`'s "options only for SELECT" invariant unchanged.

Resulting authored schema (title marker `"People"`): `Name` (TITLE ← `Person.name`) · `Email` (EMAIL ← `Person.email`). No `select` (no closed-set field), no `date`, no `url`, no relation (`Person` has no relation field today — spec §8).

## Consequences
- **Bounded extension, additive only.** Three artifacts change (`NotionPropertyType`, `propertyConfig`, the service); nothing else. The enum growth is backward-compatible (NFR-5); the exhaustive `switch` makes the missing branch a compile error (*JLS §14.11.2*), so the extension cannot ship half-done.
- **Verify/repair stays name-only (ADR-0008), so an `email`→`rich_text` retype is not detected.** `verify` compares only the **existence of the property name `"Email"`**, never its Notion type. If a user out-of-band retypes the `Email` column to `rich_text` (or vice versa), it is **not** flagged as drift and is **not** repaired. This is an **accepted consequence**, consistent with every prior feature: Projects/Tasks/Resources likewise do not heal a retyped `Status`/`Due Date`/`URL` column (ADR-0008 §Consequences; ADR-0013 §Consequences). The `email` type is therefore only ever *established* at creation/add-missing, never *reconciled* to. If type-level reconciliation is later required, that is a separate, flagged change to the verify/repair contract (ADR-0008), not reopened here.
- **Schema/row-validation boundary.** This ADR establishes the `email`-typed Notion **column** only. The `Email` value object's regex validation (`Email.java` l.7,14) governs future `Person` row writes into this database and is a distinct row-write concern — out of scope for the schema-provisioning step (spec NFR-7, §8).
- **Testability (spec AC-13, NFR-6).** A new adapter contract test asserts `propertyConfig(EMAIL)` emits exactly `{"type":"email","email":{}}` — no `options`, no extra keys — verifiable without a live Notion call via the pure helper (directly or via a captured `createDatabase`/`repairShape` body). This is the one new adapter test in this branch, because the adapter itself changed.
- **Reusable capability.** `NotionPropertyType.EMAIL` is now available to any future schema, but this spec **only consumes it for the People database** (spec §8) — no other schema is retrofitted here.
- **No domain change.** `Person` already carries `name` + an `Email` value object (spec §7); this ADR adds no field, invariant, or aggregate change.
