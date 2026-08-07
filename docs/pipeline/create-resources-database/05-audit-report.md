# 05 — Audit Report: Create Resources Database (database slice + bounded URL port/adapter extension)

Stage: Auditor (6/6) · Scope: the sole touched production surface of the "Create Resources
Database" feature —
`application/port/NotionPropertyType.java` (additive `URL` member),
the single `case URL` branch of `NotionProvisioningAdapter.propertyConfig`, and
`application/usecase/resource/CreateResourcesDatabaseService.java` — plus the three touched tests
(`NotionProvisioningAdapterDatabaseTest` §2 new URL contract tests, `CreateResourcesDatabaseServiceTest`,
`CreateResourcesDatabaseServiceIT`). The rest of `NotionProvisioningAdapter`, `NotionClient`,
`NotionProvisioningPort`, `WorkspaceLedgerWriter`, and `domain/resource/*` are **out of scope**
(unchanged; AUD-07 fixed and AUD-08 accepted under the Projects/Tasks audits, confirmed by the
QA scope-honesty check `findings.yml` QA-001 and tech-spec "Ripple: none").

Verdict: **Approve — 0 Critical, 0 High, 0 Medium, 0 Low; no blocking issues.** The service is a
faithful, correct mirror of the already-audited `CreateTasksDatabaseService`/`CreateProjectsDatabaseService`,
and the bounded `url` port+adapter extension is minimal, additive, compile-checked, and correctly
covered by exact-shape contract tests. One Info item (AUD-14) records the inherited, accepted
name-only-verify trade-off as it applies to the new `URL` column. A short report is the correct
outcome here.

## 1. Executive summary — faithful-mirror + bounded-extension verification

**The `url` extension (the real delta) — correct, minimal, safe.**
- **Config shape.** `case URL -> Map.of("type", "url", "url", Map.of())`
  (`NotionProvisioningAdapter.java:267`) emits exactly `{"type":"url","url":{}}` — an empty
  configuration object with no `options`/extra keys. This is the correct minimal Notion `url`
  property config per the feature's own Architect decision `adr/ADR-0013 §Context/§Decision`
  (which cites the Notion Property-object reference). Asserted end-to-end by the two new contract
  tests: `createDatabase_postsUrlPropertyWithEmptyUrlConfig` (`NotionProvisioningAdapterDatabaseTest.java:78-94`,
  `$.…URL.type == "url"`, `$.…URL.url` isEmpty) and `repairShape_addsMissingUrlPropertyWithEmptyUrlConfig`
  (`:96-116`), the latter also asserting the add-missing PATCH carries only `URL` and not `Title`
  (`:110`). Because `propertyConfig` is the single helper called by both `createDatabase` (`:129`)
  and `repairShape`'s add-missing loop (`:202`), the config is emitted identically on create and
  repair with no second edit.
- **Exhaustiveness — no silent fall-through.** `propertyConfig`'s `switch` expression has **no
  `default`** (`:262-270`). A `switch` expression over an enum must be exhaustive; adding
  `NotionPropertyType.URL` forces the compiler to require the new branch — a missing branch is a
  compile error, not a runtime `null`/fall-through (Oracle Java SE — JLS §14.11.2, switch
  expression exhaustiveness). The build is green (QA-001 `./mvnw verify`), so the branch is
  provably present. The four pre-existing branches are unmoved.
- **Additive enum — no exhaustiveness break elsewhere.** `NotionPropertyType` becomes
  `{ TITLE, RICH_TEXT, SELECT, DATE, URL }` (`NotionPropertyType.java:3`); existing constants keep
  their identity and ordinals-in-declaration; the only other exhaustive consumer is `propertyConfig`,
  which now handles all five. No other switch/consumer over this enum exists on the changed surface.
  Backward-compatible extension of an enumerated type (Effective Java, Item 34: use enums instead of
  int constants — extension is additive and existing callers are unaffected).
- **No injection from the emitted JSON.** The `url` config is a compile-time constant map; the only
  variable values reaching the request body are the constant title `"Resources"` and the constant
  property names `"Title"`/`"URL"` (`CreateResourcesDatabaseService.java:112-116`), carried as data
  inside `DatabaseSpec`/`PropertyDefinition` records and structurally serialized by the (unchanged)
  Jackson `ObjectMapper`. Nothing is string-concatenated into JSON, a URL path, or a query. No
  injection surface is added (OWASP Cheat Sheet Series — Injection Prevention; values are passed as
  typed data, never interpolated into the message).

**The service — faithful mirror of the audited Tasks/Projects baseline.**
- **Outcome mapping — identical.** `execute`, `executeWarmPath`, `executeColdPath`
  (`CreateResourcesDatabaseService.java:38-110`) are structurally identical to
  `CreateTasksDatabaseService.java:40-112` with `TASKS_DB → RESOURCES_DB` and `TITLE = "Resources"`.
  `CREATED` only on first-time cold create / warm-absent-no-orphan (`:89,107`); adoption is never
  `CREATED` (`:74,78,97,102`); `REPAIRED` ⇔ a Notion write ran this run; `RECONCILED` ⇔ none
  (`:64,97`). Verified by `CreateResourcesDatabaseServiceTest` (all 9 outcome rows) and the
  converge-to-one-row IT (`CreateResourcesDatabaseServiceIT.java:97-110`).
- **Ledger own-transaction; no `@Transactional` on `execute`.** The service adds no transaction;
  the only transactional write remains the reused `WorkspaceLedgerWriter.record`. Neither the class
  nor `execute` carries `@Transactional` (`:26-38`), asserted reflectively by
  `execute_isNotAnnotatedTransactional` (`CreateResourcesDatabaseServiceTest.java:264-268`).
  Correctly avoids pinning a JDBC connection across the slow Notion HTTP calls (Spring Framework /
  Data Access reference — Transaction Management: the connection is bound to the transaction scope).
  Notion-write-before-ledger-write ordering is intact, so a Notion failure never writes a ledger
  row (`:87-88,105-106`; tests `:219,231`).
- **`>1` identity match ⇒ FAILED propagation — preserved.** The ambiguity `NotionApiException` from
  `findChildByIdentity` is never caught and propagates out of `execute` on both the cold (`:84`) and
  warm-ABSENT (`:71`) paths, aborting before any `ledger.record`. Asserted by
  `execute_propagatesAmbiguousMatchFailureOnColdPath` (`:137`) and `...OnWarmAbsentPath` (`:206`),
  each with `verifyNoInteractions(ledger)`.
- **Token confinement — preserved.** The service never references the Bearer token; it depends only
  on the `NotionProvisioningPort` interface (`:33`). Every `ProvisioningStepResult.detail` is a fixed
  literal string (`:64,68,74,78,89,97,102,107`) — no token, no exception text, no request body. Logs
  carry only `workspaceId`, `dashboardId`, prior ledger id, `notionId`, `VerificationResult`, and
  outcome (`:54-55,62,85`) — never the token. No new leak surface (OWASP ASVS v4.0.3 §V7.1 — no
  sensitive data in logs; OWASP Cheat Sheet Series — Secrets Management).
- **DDD / reuse of `Resource` — correct, no anemic leakage.** The service authors the schema from
  the two `Resource` fields (`title`, `url`) via constant property definitions; no `Resource`
  entity is passed to or serialized by the port, no domain field/invariant/aggregate is added
  (ADR-0013 §Consequences "No domain change"). Aggregates are still referenced by `UUID`.
- **DIP / Clean architecture.** The application service speaks only
  `DatabaseSpec`/`ExpectedShape`/`PropertyDefinition`/`ProvisioningStepResult`; no Notion/HTTP type
  and no `data_source` concept appears in it. Inner→outer dependency direction preserved.
- **DRY / YAGNI.** Schema authored once in `resourcesSpec()`; `resourcesExpectedShape()` reuses
  `resourcesSpec().properties()` verbatim (`:112-120`). `NotionPropertyType.URL` is added only where
  needed and is **not** retrofitted onto any other schema (ADR-0013 §Consequences; QA-001 scope
  check). No speculative catch-all "text-like" type was introduced (ADR-0013 option 3 rejected).
- **No comment pollution.** The three touched production files and the three test files contain zero
  noise comments and zero "AI-generated" comments (workspace hard rule). The only comments in the
  adapter are the pre-existing `Notion-Version` requirement markers, untouched by this change.

### Severity counts

| Severity | Count | IDs |
|---|---|---|
| Critical | 0 | — |
| High | 0 | — |
| Medium | 0 | — |
| Low | 0 | — |
| Info | 1 | AUD-14 |

**Blocking issues: 0.**

## 2. Findings

### AUD-14 — Info — Design / Reconcile-completeness (inherited, accepted v0 scope) — name-only verify does not detect a `url`→`rich_text` retype of the `URL` column
Evidence: `CreateResourcesDatabaseService.resourcesExpectedShape()` (`:118-120`) drives shape
verification through the unchanged, out-of-scope `NotionProvisioningAdapter.verify`, which compares
only property-**name** presence, never the Notion property **type** (`NotionProvisioningAdapter.java:151-155`).
If a user out-of-band retypes the `URL` column from `url` to `rich_text` (or vice versa), `verify`
still returns `PRESENT_MATCHING` and no repair runs; the `url` type is only ever *established* at
creation/add-missing (`:267`), never *reconciled* to.

This is the **same accepted trade-off** as AUD-08 (Projects) / AUD-09 (Tasks) — a property of the
shared name-only-verify adapter contract (ADR-0008), **not reintroduced or worsened** by this
service or the `case URL` branch (neither adds verification logic). It is already explicitly
documented and accepted in this feature's own `adr/ADR-0013 §Consequences` ("a `url`→`rich_text`
retype is not detected … an accepted consequence"). Recorded here only as an Info cross-reference so
the Resources branch ledger is complete and the risk is revisited alongside AUD-08 if/when
property-type fidelity must be guaranteed. It is the documented, intended v0 contract that keeps
repair provably non-destructive (add-only), so it is not a defect against the current spec.

Authoritative citation: a documented scope decision, not a standard violation — authority is the
feature's own `adr/ADR-0013-url-property-type.md §Consequences` and reused
`../create-projects-database/adr/ADR-0008` (name-only verification as the accepted contract), plus
OWASP ASVS v4.0.3 §V1 (security/verification requirements must be explicit and risk-based) —
https://owasp.org/www-project-application-security-verification-standard/. No new OWASP / Jakarta /
Spring requirement is implicated.

Recommended fix: none required for v0. Track with AUD-08; when type fidelity becomes a requirement,
extend the shared adapter's `verify`/`repairShape` (not this service) to compare
`NotionPropertyConfig.type`, still additively and non-destructively.

## 3. Principle scorecard

| Principle | Rating | Justification |
|---|---|---|
| SOLID | Good | DIP clean: `CreateResourcesDatabaseService` depends only on `NotionProvisioningPort`/`WorkspaceRepository`/`WorkspaceLedgerWriter` interfaces (`:33-35`), never a concrete adapter. SRP holds — one class, one provisioning step; the `case URL` branch adds one responsibility-preserving mapping. OCP: the port/adapter was *extended* additively (new enum member + one branch) with existing branches unmoved. The pre-existing fat-`NotionProvisioningPort` ISP smell is inherited and untouched (no port method added) — scorecard note only, consistent with prior audits. |
| Clean / Hexagonal architecture | Excellent | Application service speaks only typed schema records; no Notion/HTTP/`data_source` type leaks inward. The `url` config detail is confined to the infrastructure adapter's `propertyConfig`. Inner→outer direction preserved; domain (`Resource` fields) referenced, infrastructure not. |
| DDD | Excellent | `Resource` reused by its two fields (`title`, `url`) with no anemic leakage, no entity-as-DTO serialization, no primitive obsession introduced (URL modeled as a first-class Notion type, not stringified — ADR-0013 rejects `rich_text`); aggregates referenced by `UUID`. No domain change smuggled in. |
| Security | Excellent (for the changed surface) | Token confined to the unchanged `NotionClient`, provably absent from the service's `detail` strings (fixed literals), logs (ids/outcomes only), and exceptions (propagated unchanged) — OWASP ASVS §V7.1 / Secrets Management. No user input concatenated into JSON/URLs/queries; the `url` config is a constant map — no injection surface (OWASP — Injection Prevention). No new deserialization or auth surface. Only standing item is the inherited, accepted AUD-14 reconcile-completeness note (availability/fidelity, not confidentiality). |
| DRY / YAGNI | Excellent | Schema authored once (`resourcesSpec()`), reused by `resourcesExpectedShape()`; `NotionPropertyType.URL` added only where consumed and not retrofitted; the rejected catch-all "text-like" type (ADR-0013 opt.3) honours YAGNI in the correct direction; no copy-paste logic drift from the audited baseline; no comment pollution. |

## 4. Blocking issues

**None.** No Critical, High, Medium, or Low findings. The changed surface is safe to merge. The
`case URL` extension emits the correct minimal `{"type":"url","url":{}}` config on both create and
repair, is compile-checked by the exhaustive `switch` (JLS §14.11.2), introduces no injection or
token-leak surface, and is covered by exact-shape contract tests; the service is a correct, faithful
mirror of the audited Tasks/Projects services and reintroduces none of the known risks (token leak,
NPE path, duplicate-guarantee break, transaction-across-HTTP). The sole raised item, **AUD-14
(Info)**, is an inherited/accepted cross-reference to AUD-08, already documented in ADR-0013.

Out-of-scope observation (no finding raised, per scope): the ambiguity message in the unchanged
`findChildByIdentity` is hardcoded `"Ambiguous Projects database identity…"`
(`NotionProvisioningAdapter.java:179`) and reads "Projects" even for the Resources DB — a cosmetic
string in an out-of-scope method (AUD-08 surface), not touched by this feature; noted for the
shared-adapter backlog, not routed here.

---
Routing: AUD-14 → tracked with AUD-08 on the tech-spec/architecture backlog (no action for v0).
No source files were modified by this audit.
