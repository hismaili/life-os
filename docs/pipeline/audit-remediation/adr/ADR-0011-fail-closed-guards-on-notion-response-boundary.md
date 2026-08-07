# ADR-0011: Fail-closed guards on nullable Notion response boundary fields

- **Status:** Accepted
- **Feature:** audit-remediation — FR-2 / NFR-2 (closes AUD-002, Medium)
- **Owner:** spring-architect → SME (`infrastructure.adapter.notion`)

## Context
`NotionProvisioningAdapter` treats Notion API responses as trusted:
- `verify` (line 149) calls `response.dataSources().get(0)` and (line 152)
  `dataSource.properties().containsKey(...)`;
- `repairShape` (line 197/201) does the same.

Per the Notion API, `data_sources` can be empty/absent and `NotionClient.get(...)` returns `null`
on a 404 for the data-source lookup. Today those cases throw `NullPointerException` /
`IndexOutOfBoundsException`, which escape the adapter boundary uncaught. NFR-2 requires that all
untrusted Notion fields consumed by the adapter are validated before dereference, and that failures
surface as the adapter's own `NotionApiException`, never as unchecked runtime exceptions.

## Options considered
1. **Inline null/empty guards at each dereference site, throwing `NotionApiException` with a
   description of the missing boundary data.**
   - + Localized, explicit, easy to unit-test each site; matches the adapter's existing "throw
     `NotionApiException` on a bad shape" convention (already used in `repairPage`/`repairShape`
     for null databases).
   - − Small amount of repetition across the 2–4 sites (mitigated by a shared private helper).
2. **Make the DTOs self-defaulting (compact constructors coerce null → empty).**
   - + Centralizes null-defaulting in the DTO.
   - − Cannot express "empty data_sources is an *error*" (empty is a legal-but-invalid state that
     must fail, not silently become empty); hides the boundary decision inside a data holder;
     changes DTO semantics used elsewhere. Wrong layer for a *policy* decision.
3. **Wrap the whole method body in try/catch(NullPointerException) → NotionApiException.**
   - + Minimal code.
   - − Catches NPEs from unrelated bugs and mislabels them as Notion-API errors; loses the specific
     "which field was missing" description NFR-2 asks for. Anti-pattern (control flow via NPE).

## Decision
Adopt **Option 1** via two small private helpers in the adapter:
`requirePrimaryDataSourceId(NotionDatabaseResponse, ctx)` (rejects null/empty `data_sources` and a
null summary id) and `requireDataSource(NotionDataSourceResponse, ctx)` (rejects a null lookup
result and null `properties()`). Both throw `NotionApiException` carrying the missing-data
description and the resource context. Applied at `verify` and `repairShape`. `NotionDataSourceSummary`
list access is never bare `.get(0)` again.

## Consequences
- Absent/empty `data_sources` and 404'd data-source lookups now produce a `NotionApiException`
  describing the missing data; no NPE/IOOBE escapes the adapter (NFR-2, FR-2 acceptance).
- The application layer's `runStep` continues to receive a curated `NotionApiException` on these
  paths, feeding the sanitization boundary (ADR-0013) cleanly.
- Valid/non-null responses flow through unchanged (FR-2 no-regression clause; NFR-4).
- New unit tests cover: empty `data_sources`, absent `data_sources`, and null data-source lookup.

## References
- OWASP Cheat Sheet Series — *Input Validation* (validate/normalize untrusted external input at the
  boundary); OWASP ASVS v4.0 — V5 (Validation).
- *Effective Java* (Bloch), Item 54 (return empty collections, not null) — informs the sibling
  null-`results()` defaulting in ADR-0012.
