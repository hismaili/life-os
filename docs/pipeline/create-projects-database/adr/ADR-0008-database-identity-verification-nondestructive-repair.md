# ADR-0008: Database identity via parent-page child enumeration; name-only verification; non-destructive repair

## Status
Accepted (Architect stage, Create Projects Database branch). Fixes how the adapter finds/adopts the Projects database (FR-8/FR-9), how `verify` detects drift (FR-5/FR-6b), and how `repairShape` heals it (FR-6b) without destroying data. Depends on ADR-0005 (data-source model).

## Context
The Projects database is a **child of a known parent** — the Dashboard page whose id is in the ledger. This differs fundamentally from the Dashboard's own identity problem (a *root* page with no parent, which forced `/v1/search`, Create Dashboard ADR-0002). For a child database we must decide three things:

1. **Identity/adoption (FR-8/FR-9)** — how to find "the Projects database under this Dashboard" with no stored id (orphan-adoption / `>1` detection).
2. **Drift detection (FR-6b)** — what `verify` compares.
3. **Repair (FR-6b)** — how `repairShape` corrects drift **without destroying existing data**.

Two Notion facts from the pinned `2025-09-03` API are load-bearing:
- **`/v1/search` cannot filter to `database` objects.** Its `filter.value` accepts only `"page"` and `"data_source"` ([Search](https://developers.notion.com/reference/post-search)); search is also index-lagged (newly created objects "can take time" to appear), which is poor for the create-then-crash adoption race.
- **A database created under a page appears as a `child_database` block of that page**, and `GET /v1/blocks/{block_id}/children` returns those blocks (type `child_database`, with `child_database.title`, and the **block `id` equals the database id**), with pagination ([Retrieve block children](https://developers.notion.com/reference/get-block-children)).
- **`PATCH /v1/data_sources/{id}` with a property set to `null` removes it**, and type conversions may lose data ([Update a data source](https://developers.notion.com/reference/update-a-data-source)).

## Options considered

### Identity/adoption
1. **`/v1/search` filtered to databases, like the Dashboard.**
   - (−) **Not possible** — search cannot filter to `database` objects under `2025-09-03`. A `data_source` search would require mapping each hit's parent database and re-checking — extra hops — and still suffers index lag. Rejected.
2. **Enumerate the Dashboard page's children and filter `child_database` by title** (chosen).
   - (+) The parent **is** known, so identity is a direct, parent-scoped enumeration — exactly the spec's identity rule ("a database directly parented under the Dashboard," FR-8). The existing port method is even named `findChildByIdentity(parentPageId, …)`.
   - (+) **Immediately consistent** (block children reflect a just-created database at once), unlike search's index lag — the strongest convergence for the create-then-ledger-write-failed race (FR-13).
   - (+) `>1` matches are detected directly → fail loudly (FR-9).
   - (−) Requires an `ExpectedShape` parameter so the adapter knows the title to match (ADR-0007 finding); paginates for pages with many children (bounded, cheap).

### Drift detection (what `verify` compares)
1. **Title + required property names.** (chosen) Matches FR-6b's definition of drift ("renamed, or missing one or more required properties").
2. **Title + names + types + option sets.**
   - (−) Comparing types/options invites *destructive* repair (see below) and over-reports drift for benign user additions/edits the spec does not forbid. The spec requires the properties to **exist**, not to be byte-identical. Rejected for this step.

### Repair
1. **Add missing properties; rename via the property id; correct the title. Never delete or retype.** (chosen)
2. **Reconcile the schema to an exact target (delete extras, coerce types).**
   - (−) Deletion (`property: null`) and type coercion **destroy user data** ([Update a data source]); directly violates FR-6b ("without destroying existing data") and NFR-2. Rejected.

## Decision
- **Identity/adoption**: `findChildByIdentity(parentPageId, PROJECTS_DB, expected)` → `GET /v1/blocks/{parentPageId}/children` (paginated), keep blocks of type `child_database` whose `child_database.title == expected.title`. `0` → empty; `1` → that block id (the database id); **`> 1` → `NotionApiException`** (FR-9, never adopt arbitrarily). `findChildByIdentity` gains the `ExpectedShape` parameter (ADR-0007) so the title's source of truth stays in the service.
- **Drift detection**: `verify(databaseId, PROJECTS_DB, expected)` → `GET /v1/databases/{id}` (`404`/archived/`in_trash` → `ABSENT`; `title != expected.title` → `PRESENT_DRIFTED`), then resolve `data_sources[0].id` and `GET /v1/data_sources/{id}`; if any `expected.requiredProperties` **name** is absent → `PRESENT_DRIFTED`; else `PRESENT_MATCHING`. **Types and option sets are not compared.**
- **Repair**: `repairShape(databaseId, expected)` → if the database title drifted, `PATCH /v1/databases/{id} {title}`; then for **each missing required property name**, `PATCH /v1/data_sources/{dsId} { properties: { "<name>": { <type config> } } }`. **Additive only** — never sends `null`, never changes an existing property's type. A property that was *renamed* out of band appears as a missing required name and is simply re-added (the stale renamed column is left untouched), which is safe and convergent.

## Consequences
- Identity is deterministic, parent-scoped, and index-consistent — the create-then-crash race closes on the next run (FR-13), better than the Dashboard's search-based adoption could.
- `verify` is two `GET`s (database + data source); `repairShape` is one `GET` + up to (1 title PATCH + N small property PATCHes). Bounded, within the ~3 req/s budget ([Request limits](https://developers.notion.com/reference/request-limits)); NFR-9.
- **No repair path can destroy data**: only titles change and only missing properties are added (FR-6b, NFR-2).
- Because verify ignores types/options, a user who *adds* their own columns or edits `Status` options never triggers spurious `REPAIRED` — the step owns only the **required** structural set (FR-14).
- Sibling database steps reuse this identity/verify/repair shape verbatim; only their `ExpectedShape` differs.
</content>
