# ADR-0010: `Knowledge.content` is a Notion `rich_text` property, not the page body

## Status
Accepted (Architect stage, Create Knowledge Database branch). Scope-local to the Knowledge step. Depends on ADR-0007 (typed schema value types) and ADR-0008 (name-only verification, non-destructive repair). It is the **only** new decision this branch makes; ADR-0005..0008 (Projects) and ADR-0009 (Tasks) are reused unchanged by reference.

## Context
Spec §3 maps `Knowledge.content` (`domain/knowledge/Knowledge.java` l.13, a `String`) onto the Knowledge database's schema and flags an `[ASSUMPTION]`: is `content` best represented as a Notion **`rich_text` database property** (a column) or as the Notion **page body** (block content)?

Two facts frame the choice:

1. A Notion **`rich_text` property** is a first-class database column. Its schema config object is empty — `"Content": { "type": "rich_text", "rich_text": {} }` — and it is created/verified/repaired by the exact same generic adapter path already used for `Description` on the Projects and Tasks databases ([Notion — Property object, "rich_text"](https://developers.notion.com/reference/property-object): *"Contains text values. The `rich_text` type object is empty."*).
2. The `rich_text` **value** has documented size limits: each rich text object's `text.content` is capped at **2000 characters** ([Notion — Limits for property values / request limits](https://developers.notion.com/reference/request-limits)). Prose longer than one 2000-char object holds must be split across multiple rich text objects (or stored as page-body blocks). Zettelkasten/Second-Brain notes *can* be long-form, so this limit could matter — **but only when rows are written**, which this step never does.

The Knowledge step is **schema-only** (spec §1, §8, FR-14): it provisions the property container and creates **no** rows. So the 2000-char limit cannot be reached by anything this step does.

## Options considered
1. **Represent `content` as a `rich_text` property named "Content"** (chosen).
   - (+) **Zero new mechanism, pattern-consistent.** Identical in kind to `Description` (`RICH_TEXT`) on Projects/Tasks; provisioned, verified (name-only, ADR-0008) and repaired by the existing generic adapter with no new code path (ADR-0007).
   - (+) `content` surfaces as a queryable/filterable database column — usable by future views, relations, and rollups — which page-body blocks are not.
   - (+) **The 2000-char limit is inert for this step** (no rows written); it becomes relevant only at Phase F (row population), which is separately specified and out of scope (spec §8).
   - (−) A single `rich_text` object caps a row's `content` at 2000 chars; genuinely long notes would need multi-object splitting or a page-body fallback **at write time**. Deferred, not incurred here.
2. **Store `content` in the Notion page body** (block children of each Knowledge row).
   - (+) No practical length ceiling; natural home for long-form prose.
   - (−) **New mechanism this step neither has nor needs**: the page-body writer is not part of the shipped DB slice, so choosing it now would add scope (a block-append path) to a schema-only step for zero present benefit (YAGNI).
   - (−) `content` would not be a database column — invisible to filters/rollups/relations, breaking parity with how `Description` is modeled on the sibling databases.
   - (−) Rejected as scope creep; the limit it solves is unreachable in this step.
3. **Hybrid (property for a summary + page body for full text)** — deferred entirely.
   - (−) Premature; introduces a truncation/split policy the spec explicitly places in Phase F (spec §8). No FR needs it now. Rejected (YAGNI).

## Decision
Represent `Knowledge.content` as a **Notion `rich_text` property named "Content"**, authored once in `CreateKnowledgeDatabaseService.knowledgeSpec()` as `PropertyDefinition.of("Content", NotionPropertyType.RICH_TEXT)` — structurally identical to `Description` on Projects/Tasks. **No page-body mechanism is introduced.** The 2000-char-per-object `rich_text` limit is documented here for the record but is immaterial to this schema-only step, which writes no rows.

## Consequences
- `knowledgeSpec()` carries exactly two properties: `Title` (`TITLE`) and `Content` (`RICH_TEXT`). No `select`/`date`, no domain-enum dependency — the leanest of the three database specs to date.
- Verification is name-only (ADR-0008): the "Content" column's *presence* is checked; its (empty) config and any user-added content are never a drift trigger.
- **Deferred to Phase F (Populate Example Data), out of this step's scope:** deciding how to write `content` longer than 2000 characters into a row — either splitting across multiple rich text objects or falling back to page-body blocks. If that phase later concludes the page body is the right home for long-form content, migrating the "Content" column to a body-block strategy is a separate, flagged change; this step's `rich_text` property remains correct for short/medium notes and for the container-only guarantee it provides now.

### Tracked follow-up (out of this step's scope)
- **Long-form `content` write strategy (Phase F).** When rows are populated, define a truncation/multi-object-split or page-body-fallback policy for `content` beyond 2000 chars per rich text object. Non-blocking here; no row is written by this step.
</content>
