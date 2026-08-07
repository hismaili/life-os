# ADR-0012: Bounded pagination for Notion search / child-listing loops

- **Status:** Accepted
- **Feature:** audit-remediation — FR-5 / NFR-2 (closes AUD-003, Low; in scope per owner directive)
- **Owner:** spring-architect → SME (`infrastructure.adapter.notion`)

## Context
`findRootByIdentity` (line 95–112) and `findChildByIdentity` (line 161–173) loop on the Notion
search/children API's `has_more` / `next_cursor` fields:
```
do { ... cursor = response.hasMore() ? response.nextCursor() : null; } while (cursor != null);
```
Two boundary failures exist: (a) a misbehaving or malicious API returning a **repeating**
`next_cursor` never terminates the loop → unbounded memory growth / DoS; (b) `response.results()`
can be `null` (malformed `results: null`), causing an NPE in the stream pipeline. NFR-2 requires an
explicit bound and null-safe handling.

## Options considered
1. **Explicit page-count cap + null-default `results()` to empty list; stop (break or throw
   `NotionApiException`) when the cap is exceeded.**
   - + Deterministic upper bound on memory and API calls; simple, testable; no new dependency.
   - − The cap is a magic number requiring a justified default (see below).
2. **Cursor-cycle detection (track seen cursors in a `Set`).**
   - + Terminates precisely on a repeating cursor without an arbitrary ceiling.
   - − Does not bound the *legitimate-but-huge* result set; a non-repeating adversarial cursor
     stream still grows unboundedly. Extra state for a case the cap already covers. Can be added
     later if needed.
3. **Do nothing / accept the loop (auditor rated Low).**
   - − Rejected: owner brought AUD-003 in scope; the fix is cheap and adjacent to ADR-0011's guard
     work.

## Decision
Adopt **Option 1**: introduce an explicit `MAX_SEARCH_PAGES` cap (and/or a max-accumulated-matches
ceiling) in the adapter, and default a null `results()` to `List.of()` via a `nullSafe(...)` helper
before streaming. On exceeding the cap, terminate the loop by throwing a `NotionApiException`
("Notion search exceeded the page cap") rather than silently truncating, so an operator sees the
anomaly.

**Cap value is an `[ASSUMPTION]`** (spec §7 permits any enforced bound). Recommend
`MAX_SEARCH_PAGES = 50` (Notion default page size 100 → up to ~5,000 candidates), comfortably above
any realistic single-workspace identity search while bounding the pathological case. The SME may
tune it; the load-bearing requirement is only that a bound exists and is enforced.

## Consequences
- A repeating/adversarial cursor terminates at the cap instead of looping forever (FR-5 acceptance).
- `results: null` is treated as an empty page — no NPE — and pagination proceeds/terminates per the
  cap (FR-5 acceptance).
- Legitimate workspaces (far below the cap) are unaffected (NFR-4).
- Cursor-cycle detection (Option 2) is explicitly deferred; revisit only if a bounded-but-large
  legitimate result set is ever observed.

## References
- OWASP ASVS v4.0 — V5 (validate untrusted input) and V12/DoS-resilience considerations for
  unbounded resource consumption driven by external input.
- *Effective Java* (Bloch), Item 54: Return empty collections or arrays, not nulls.
- Notion API — search pagination (`has_more`, `next_cursor`) semantics (developers.notion.com;
  behavioral reference only, not cited as an authority for the design decision).
