# ADR-0005: Database provisioning uses Notion's data-source model (version 2025-09-03+)

## Status
Accepted (Architect stage, Create Projects Database branch). First database-creating step in the project; governs the `NotionProvisioningAdapter` database slice (`createDatabase`/`verify`/`repairShape`). Reuses the transport of Create Dashboard ADR-0001 (RestClient + `NotionClient`) unchanged.

## Context
Create Projects Database is the first step to create a Notion **database** (all prior steps create the Dashboard page or throw). The project already pins the Notion API to version **`2025-09-03` or later**: Create Dashboard's `repairPage` uses `POST /v1/pages/{id}/move`, available only since `2025-09-03` (Create Dashboard ADR-0002; [Move a page](https://developers.notion.com/reference/move-page)). That API version introduced **data sources**, which changes how databases and their schemas are created, retrieved, and modified. We must decide how the adapter models a database and what the ledger stores.

Under `2025-09-03` ([Upgrade guide](https://developers.notion.com/docs/upgrade-guide-2025-09-03)):
- A **database** is a container of one-or-more **data sources**; **the schema (`properties`) lives on the data source, not the database**.
- `POST /v1/databases` takes database-level attributes (`parent`, `title`, `icon`, `cover`) plus the initial schema under **`initial_data_source.properties`** ([Create a database](https://developers.notion.com/reference/create-a-database)).
- The response and `GET /v1/databases/{id}` return a **`data_sources[]`** array (id + name) ([Retrieve a database]).
- Schema is read via `GET /v1/data_sources/{id}` and modified via `PATCH /v1/data_sources/{id}` ([Update a data source](https://developers.notion.com/reference/update-a-data-source)).

## Options considered
1. **Assume the legacy single-object database model** (schema directly on the database via `POST /v1/databases {properties}` and `PATCH /v1/databases/{id} {properties}`).
   - (+) One id, fewer calls.
   - (−) **Wrong for the pinned API version.** Under `2025-09-03` the schema is not on the database; a legacy-shaped create/patch body targets fields the versioned API no longer honours there. Contradicts the version the Dashboard already relies on. Rejected.
2. **Store the data-source id in the ledger** as the `PROJECTS_DB` identity.
   - (+) Schema ops need no dereference.
   - (−) A data source's identity/parent is the **database**, not the Dashboard page; the spec's identity rule (FR-8) is "a database directly parented under the Dashboard." Orphan adoption enumerates the Dashboard page's children, which are **databases** (block type `child_database`), not data sources. Storing the data-source id would mismatch the identity/adoption unit and complicate future relations. Rejected.
3. **Store the database id; the adapter dereferences database → data source internally for schema ops** (chosen).
   - (+) The ledger identity is the parent-scoped, user-visible **database** — exactly what FR-8 adoption (`child_database` block) and FR-9 (`>1`) operate on.
   - (+) The data-source concept stays **entirely inside the adapter**; the application service never sees it (hexagonal boundary preserved, `CLAUDE.md`).
   - (−) `verify`/`repairShape` make one extra `GET /v1/databases/{id}` to resolve `data_sources[0].id` before touching the schema — a bounded, cheap cost within the ~3 req/s budget ([Request limits](https://developers.notion.com/reference/request-limits)).

## Decision
Model the database slice on the `2025-09-03` data-source API:
- **Create**: `POST /v1/databases` with `parent = { type: "page_id", page_id: <dashboardId> }`, `title`, and `initial_data_source.properties = <schema>`; **return the database `id`** for the ledger.
- **Identity**: the ledger stores the **database id** (see ADR-0008 for how it is found/adopted).
- **Schema read/write**: the adapter resolves `data_sources[0].id` from `GET /v1/databases/{id}` (the common single-source case), then uses `GET`/`PATCH /v1/data_sources/{id}` for verification and repair. The application layer is unaware of data sources.
- Transport, headers, retries, and timeouts are the existing `NotionClient` (ADR-0001), reused unchanged.

## Consequences
- The adapter gains data-source DTOs (`NotionDatabaseResponse` with `data_sources[]`, `NotionDataSourceResponse` with `properties`); nothing Notion-shaped leaks past `NotionProvisioningPort`.
- The **entire** database slice is coupled to Notion-Version `>= 2025-09-03`; the single `NotionProperties.version` pin governs it (finding for the SME: document the minimum and fail fast if misconfigured).
- The single-source assumption (`data_sources[0]`) is correct for databases this pipeline creates (it always creates exactly one initial data source). Multi-source databases are out of scope; if ever needed, resolution becomes name-based rather than positional — an additive change.
- Sibling database steps (Tasks, Knowledge, …) reuse this model verbatim.
</content>
