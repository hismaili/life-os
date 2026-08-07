# 02 — Open Questions: Create Dashboard

Status: **RESOLVED** — every question below has a recorded human decision (answered 2026-08-05). The design in `02-architecture.md` and the ADRs reflect these answers. Four items are intentionally deferred as tracked future features (see the bottom section); they do not block the pipeline.

---

## Resolved decisions

### OQ-1 — Which Notion integration type creates the root page, and under what parent? → **(a) Internal integration + a designated parent page**
An *internal* Notion integration cannot create a true workspace-root page ([Notion — Create a page](https://developers.notion.com/reference/post-page)); it must create pages under a parent it has been shared into.
**Decision:** Use an internal integration. The operator manually creates one Notion page ("LifeOS"), shares it with the integration, and configures its id as `notion.root-parent-page-id`. Every workspace Dashboard is created as a **child of that configured parent**. `createRootPage` never targets the workspace root.
**Reflected in:** ADR-0001/ADR-0002; `02-architecture.md` §5.3, §6 (Configuration); `NotionProperties.rootParentPageId`.

### OQ-2 — Deterministic-identity heuristic for orphan adoption → **(b) title marker + scoping parent**
**Decision:** `findRootByIdentity` matches a page by the **derived Dashboard title** AND membership under the configured root parent (`parent.page_id == rootParentPageId`). On **multiple** matches the adapter raises `NotionApiException` (step `FAILED`) rather than adopt an arbitrary page — never guess, never duplicate.
**Reflected in:** ADR-0002; `02-architecture.md` §4.1/§4.2 (`>1 → FAILED`), §5.2, §5.3.

### OQ-3 — Title collisions across workspaces sharing one Notion account → **include the workspace name in the title**
**Decision:** The Dashboard title includes the workspace `name` — template `"LifeOS — {name}"` via a single-source-of-truth `dashboardTitle(Workspace)` — in addition to the parent-scoping from OQ-1a/OQ-2b. (If collisions ever persist under the same parent, a dedicated marker property is future work — item 4 below.)
**Reflected in:** `02-architecture.md` §5.1, §5.4.

### OQ-4 — Does a page need its own shape representation? → **(b) dedicated `PageShape`**
`ExpectedShape(title, requiredPropertyNames)` (database-oriented) cannot express "must live under parent X", which is required to repair a moved Dashboard.
**Decision:** Introduce a dedicated page value type `PageShape(String title, ParentConstraint parent)` with `enum ParentConstraint { ROOT_PARENT }` in `application.port`. This makes a page moved out from under the configured parent a repairable `PRESENT_DRIFTED` (via the Notion **Move page** endpoint). `ExpectedShape` stays for the seven databases.
**Reflected in:** ADR-0002; `02-architecture.md` §5.2, §5.4, §4.2 (parent-drift row).

### OQ-5 — What must the Dashboard contain at creation? → **empty titled placeholder page**
**Decision:** Creation makes a titled page with no body content. Body content / navigation links to databases are **out of scope** for this step (future feature 1).
**Reflected in:** `02-architecture.md` §5.3, §11.

### OQ-6 — Is the Dashboard re-entered once child databases exist? → **one-shot Phase A**
**Decision:** Create Dashboard verifies title/existence/not-trashed/parent only and does **not** own child-database link maintenance. Keeping the Dashboard's links to the seven databases current is owned by a later phase (future feature 2).
**Reflected in:** `02-architecture.md` §4/§5, §11.

---

## Deferred / tracked future features (non-blocking)

1. **Dashboard body content / navigation links** (from OQ-5) — add page body blocks/links in a later feature.
2. **Child-database link maintenance on the Dashboard** (from OQ-6) — owned by a later phase once Phase B exists; likely an additive `PageShape` extension or a new step.
3. **Personal-access-token / public-connection support** for true workspace-root pages + OAuth (the non-chosen OQ-1(b)) — pairs with the deferred Create Workspace REST authn/OAuth; `ParentConstraint` is an enum so a `WORKSPACE_ROOT` value can be added additively.
4. **Richer orphan-adoption identity via a dedicated marker property** (OQ-2 option c / OQ-3) — if title + parent scoping ever proves insufficient under the same parent.
