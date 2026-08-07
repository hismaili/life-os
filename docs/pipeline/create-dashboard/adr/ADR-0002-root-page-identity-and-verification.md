# ADR-0002: Root-page identity & verification — retrieve-by-id (warm) + search-by-title (adoption)

## Status
Accepted (Architect stage, Create Dashboard branch). The identity predicate and page-shape sub-questions are now **RESOLVED** by the human (2026-08-05): the predicate is the derived title **plus** membership under the configured root parent (OQ-2b), and pages use a dedicated `PageShape`/`ParentConstraint` value type (OQ-4b). This ADR fixes both the mechanism and the (now-decided) heuristic.

## Context
The Dashboard is a **root** Notion page: it has no LifeOS-managed parent. Strict verify-before-trust (Create Workspace ADR-0002) requires that, before returning `RECONCILED`, the step confirm the page exists and matches shape in live Notion; and that a create-succeeded-but-ledger-write-failed page (FR-7) be re-found and adopted rather than duplicated on the next run. The existing `NotionProvisioningPort.findChildByIdentity(String rootPageId, type)` is built to find a resource **under a parent page** — structurally impossible for a root page, which has no parent to search under. Notion additionally **permits duplicate page titles**, so any title-based identity is heuristic.

## Options considered
1. **Store the id only; never search; on a missing/absent ledger id, always create.**
   - (+) Simplest; one call.
   - (−) Fails FR-7: a page created before a failed ledger write is orphaned forever and a duplicate is made on re-run. Violates strict idempotency (Create Workspace ADR-0002) and FR-11.
2. **Reuse `findChildByIdentity(rootPageId, DASHBOARD)`.**
   - (−) A root page has no `rootPageId` parent; the method's contract does not fit. Overloading it with a null/sentinel parent gives one method two meanings — a maintenance trap.
3. **Retrieve-by-id for the warm path + `POST /v1/search` by title for adoption, behind an additive `findRootByIdentity(ExpectedShape)` port method** (chosen mechanism).
   - Warm path: `GET /v1/pages/{id}` returns `is_archived`/`in_trash` and title; a deleted page returns `404 object_not_found` ([Notion — Retrieve a page](https://developers.notion.com/reference/retrieve-a-page)). This yields `PRESENT_MATCHING | PRESENT_DRIFTED | ABSENT` for a known id.
   - Adoption path: `POST /v1/search` "searches all parent or child pages … shared with a connection" and returns pages whose titles include the query, filterable by `object = page` ([Notion — Search](https://developers.notion.com/reference/post-search)) — the only way to find a parentless page the ledger does not know about.
   - (+) Satisfies FR-4/5/6/7 and closes the dual-write window as far as Notion allows.
   - (−) Search-by-title is heuristic: duplicate titles can match 0, 1, or many pages; the predicate that turns hits into "the Dashboard" is not decidable from Notion primitives alone (OQ-2/OQ-3).

## Decision
Adopt option 3's mechanism, using **page-oriented** port methods and a resolved identity predicate:
- **Warm path** (ledger has a `DASHBOARD` id): `verifyPage(id, PageShape)` → `GET /v1/pages/{id}`; `404`/`in_trash`/`is_archived` ⇒ `ABSENT`; `parent.page_id != rootParentPageId` **or** title mismatch ⇒ `PRESENT_DRIFTED`; else `PRESENT_MATCHING`.
- **Adoption path** (no ledger id, or a warm-path `ABSENT`): `findRootByIdentity(PageShape)` → `POST /v1/search` filtered to pages, then narrowed to those whose `parent.page_id == rootParentPageId` **and** whose title equals the derived Dashboard title, returning at most one id.
- **Repair**: `repairPage(id, PageShape)` → `PATCH /v1/pages/{id}` to rename back and/or restore a trashed page (`in_trash:false`), plus `POST /v1/pages/{id}/move` to re-parent a page moved out from under the root parent ([Notion — Update page](https://developers.notion.com/reference/patch-page); [Move a page](https://developers.notion.com/reference/move-page)).
- Add **`verifyPage`/`repairPage`/`findRootByIdentity(PageShape)` as purely additive `NotionProvisioningPort` methods**, and refine `createRootPage` to take `PageShape` (Dashboard-only). Additive ⇒ no existing Create Workspace caller changes; `findChildByIdentity`/`ExpectedShape` remain for databases.

The **identity predicate is resolved (OQ-2b)**: derived title (`"LifeOS — {name}"`, OQ-3) **plus** membership under the configured root parent (OQ-1a). Because all Dashboards live under one known parent, the scoping parent disambiguates most collisions; on **multiple** matches the adapter fails loudly (`NotionApiException` → step `FAILED`), never adopting arbitrarily. A dedicated marker property is deferred as a future feature if title+parent ever proves insufficient.

## Consequences
- The port gains page-oriented additive methods; `findChildByIdentity` remains for database children and is **not** used for the Dashboard.
- Verification is a single `GET` on the warm path (cheap) and a single `search` on the cold/ABSENT path — within the ~3 req/s budget ([Notion — Request limits](https://developers.notion.com/reference/request-limits)).
- Adoption correctness rests on title + configured-parent scoping; `> 1` match is a loud `FAILED`, never a silent wrong adoption.
- Page position/parent **is** part of shape via `PageShape.parent` (OQ-4b), so a moved Dashboard is a repairable `PRESENT_DRIFTED` (re-parented with the Move-page endpoint), not an unrepaired drift.
