# 05 — Audit Report: Create People Database (database slice + bounded `email` port/adapter extension)

Stage: Auditor (6/6) · Scope: the sole touched production surface of the "Create People
Database" feature —
`application/port/NotionPropertyType.java` (additive `EMAIL` member),
the single `case EMAIL` branch of `NotionProvisioningAdapter.propertyConfig`, and
`application/usecase/person/CreatePeopleDatabaseService.java` — plus the touched tests
(`NotionProvisioningAdapterDatabaseTest` new EMAIL contract tests, `CreatePeopleDatabaseServiceTest`,
`CreatePeopleDatabaseServiceIT`). The rest of `NotionProvisioningAdapter`, `NotionClient`,
`NotionProvisioningPort`, `WorkspaceLedgerWriter`, and `domain/person/*` are **out of scope**
(unchanged; AUD-07 fixed and AUD-08 accepted under Projects; shared-adapter backlog items
ADAPTER-AMBIGUITY-MSG and BUILDER-SWEEP already tracked — not re-raised here).

Verdict: **Approve — 0 Critical, 0 High, 0 Medium, 0 Low; no blocking issues.** The service is a
faithful, correct mirror of the already-audited `CreateResourcesDatabaseService` (the URL sibling),
and the bounded `email` port+adapter extension is minimal, additive, compile-checked, and correctly
covered by exact-shape contract tests. One Info item (PEOPLE-15) records the inherited, accepted
name-only-verify trade-off as it applies to the new `Email` column — the direct analog of AUD-14
(URL) / AUD-08 (Projects). A short report is the correct outcome here.

## 1. Executive summary — faithful-mirror + bounded-extension verification

**The `email` extension (the real delta) — correct, minimal, safe.**
- **Config shape.** `case EMAIL -> Map.of("type", "email", "email", Map.of())`
  (`NotionProvisioningAdapter.java:268`) emits exactly `{"type":"email","email":{}}` — an empty
  configuration object with no `options`/extra keys. This is the correct minimal Notion `email`
  property config per the feature's Architect decision `adr/ADR-0014 §Context/§Decision`, which cites
  the Notion Property-object reference. Asserted end-to-end by the two new contract tests:
  `createDatabase_postsEmailPropertyWithEmptyEmailConfig`
  (`NotionProvisioningAdapterDatabaseTest.java:121-137`: `$.…Email.type == "email"`, `$.…Email.email`
  isEmpty) and `repairShape_addsMissingEmailPropertyWithEmptyEmailConfig` (`:139-159`), the latter
  also asserting the add-missing PATCH carries only `Email` and not `Name` (`:153`). Because
  `propertyConfig` is the single helper called by both `createDatabase` and `repairShape`'s
  add-missing loop, the config is emitted identically on create and repair with no second edit.
- **Exhaustiveness — no silent fall-through.** `propertyConfig`'s `switch` expression has **no
  `default`** (`:263-271`). A `switch` expression over an enum must be exhaustive; adding
  `NotionPropertyType.EMAIL` forces the compiler to require the new branch — a missing branch is a
  compile error, not a runtime `null`/fall-through (Oracle Java SE — JLS §14.11.2, switch expression
  exhaustiveness). The build is green (QA-001 `./mvnw verify`: 299 unit + 33 failsafe, 0 failures),
  so the branch is provably present. The five pre-existing branches are unmoved.
- **Additive enum — no exhaustiveness break elsewhere.** `NotionPropertyType` becomes
  `{ TITLE, RICH_TEXT, SELECT, DATE, URL, EMAIL }` (`NotionPropertyType.java:3`); existing constants
  keep their identity and declaration order; the only exhaustive consumer is `propertyConfig`, which
  now handles all six. No other switch/consumer over this enum exists on the changed surface.
  Backward-compatible extension of an enumerated type (Effective Java, Item 34: use enums instead of
  int constants — extension is additive and existing callers are unaffected).
- **No injection from the emitted JSON.** The `email` config is a compile-time constant map; the only
  variable values reaching the request body are the constant title `"People"` and the constant
  property names `"Name"`/`"Email"` (`CreatePeopleDatabaseService.java:112-116`), carried as data
  inside `DatabaseSpec`/`PropertyDefinition` records and structurally serialized by the (unchanged)
  Jackson `ObjectMapper`. Nothing is string-concatenated into JSON, a URL path, or a query. No
  injection surface is added (OWASP Cheat Sheet Series — Injection Prevention; values passed as typed
  data, never interpolated into the message).
- **No PII / secret written by this step.** This is a schema-provisioning step: it establishes the
  `email`-typed **column** only and writes **no rows**. No `Person.email` address value is
  serialized, logged, or transmitted here (`CreatePeopleDatabaseService.java:112-120` authors only the
  constant column name `"Email"`). Row-write PII/validation via the `Email` VO regex
  (`domain/person/Email.java:7,14`) is a distinct, out-of-scope concern (ADR-0014 §Consequences
  "Schema/row-validation boundary"). No sensitive-data-exposure surface is introduced (OWASP ASVS
  v4.0.3 §V8 — sensitive data is not handled on this path).

**The service — faithful mirror of the audited Resources baseline.**
- **Outcome mapping — identical.** `execute`, `executeWarmPath`, `executeColdPath`
  (`CreatePeopleDatabaseService.java:38-110`) are structurally identical to
  `CreateResourcesDatabaseService.java:38-110` with `RESOURCES_DB → PEOPLE_DB` and `TITLE = "People"`.
  `CREATED` only on first-time cold create / warm-absent-no-orphan (`:89,107`); adoption is never
  `CREATED` (`:74,78,97,102`); `REPAIRED` ⇔ a Notion write ran this run; `RECONCILED` ⇔ none
  (`:64,97`). Verified by `CreatePeopleDatabaseServiceTest` (all outcome rows) and the
  converge-to-one-row IT (`CreatePeopleDatabaseServiceIT.java:96-109`: two re-runs → `RECONCILED`,
  `hasSize(1)`).
- **Ledger own-transaction; no `@Transactional` on `execute`.** The service adds no transaction; the
  only transactional write remains the reused `WorkspaceLedgerWriter.record`. Neither the class nor
  `execute` carries `@Transactional` (`:26-38`), asserted reflectively by
  `execute_isNotAnnotatedTransactional` (`CreatePeopleDatabaseServiceTest.java:264-267`). Correctly
  avoids pinning a JDBC connection across the slow Notion HTTP calls (Spring Framework / Data Access
  reference — Transaction Management: the connection is bound to the transaction scope).
  Notion-write-before-ledger-write ordering is intact, so a Notion failure never writes a ledger row
  (`:87-88,105-106`).
- **`>1` identity match ⇒ FAILED propagation — preserved.** The ambiguity `NotionApiException` from
  `findChildByIdentity` is never caught and propagates out of `execute` on both the cold (`:84`) and
  warm-ABSENT (`:71`) paths, aborting before any `ledger.record`. Asserted by the ambiguity tests
  (`CreatePeopleDatabaseServiceTest.java:136-146` cold, `:205-216` warm-absent) and the IT
  (`CreatePeopleDatabaseServiceIT.java:112-136`: throws, ledger row `isEmpty()`).
- **Token confinement — preserved.** The service never references the Bearer token; it depends only
  on the `NotionProvisioningPort` interface (`:33`). Every `ProvisioningStepResult.detail` is a fixed
  literal string (`:64,68,74,78,89,97,102,107`) — no token, no exception text, no request body. Logs
  carry only `workspaceId`, `dashboardId`, prior ledger id, `notionId`, `VerificationResult`, and
  outcome (`:54-55,62,85`) — never the token. No new leak surface (OWASP ASVS v4.0.3 §V7.1 — no
  sensitive data in logs; OWASP Cheat Sheet Series — Secrets Management).
- **DDD / reuse of `Person`/`Email` — correct, no anemic leakage.** The service authors the schema
  from the two `Person` fields (`name`, `email`) via constant property definitions; no `Person`
  entity and no `Email` VO instance is passed to or serialized by the port, no domain
  field/invariant/aggregate is added (ADR-0014 §Consequences "No domain change"). The `Email` VO's
  address string is modeled honestly as a first-class Notion `email` type, not stringified into
  `rich_text` (ADR-0014 rejects option 2). Aggregates are still referenced by `UUID`.
- **DIP / Clean architecture.** The application service speaks only
  `DatabaseSpec`/`ExpectedShape`/`PropertyDefinition`/`ProvisioningStepResult`; no Notion/HTTP type
  and no `data_source` concept appears in it. Inner→outer dependency direction preserved.
- **DRY / YAGNI.** Schema authored once in `peopleSpec()`; `peopleExpectedShape()` reuses
  `peopleSpec().properties()` verbatim (`:112-120`). `NotionPropertyType.EMAIL` is added only where
  needed and is **not** retrofitted onto any other schema (ADR-0014 §Consequences; QA-001 scope
  check). No speculative catch-all "text-like" type was introduced (ADR-0014 option 3 rejected).
- **No comment pollution.** The three touched production files and the touched test files contain zero
  noise comments and zero "AI-generated" comments (workspace hard rule).

### Severity counts

| Severity | Count | IDs |
|---|---|---|
| Critical | 0 | — |
| High | 0 | — |
| Medium | 0 | — |
| Low | 0 | — |
| Info | 1 | PEOPLE-15 |

**Blocking issues: 0.**

## 2. Findings

### PEOPLE-15 — Info — Design / Reconcile-completeness (inherited, accepted v0 scope) — name-only verify does not detect an `email`→`rich_text` retype of the `Email` column
Evidence: `CreatePeopleDatabaseService.peopleExpectedShape()` (`:118-120`) drives shape verification
through the unchanged, out-of-scope `NotionProvisioningAdapter.verify`, which compares only
property-**name** presence, never the Notion property **type**. If a user out-of-band retypes the
`Email` column from `email` to `rich_text` (or vice versa), `verify` still returns `PRESENT_MATCHING`
and no repair runs; the `email` type is only ever *established* at creation/add-missing
(`NotionProvisioningAdapter.java:268`), never *reconciled* to.

This is the **same accepted trade-off** as AUD-14 (Resources/URL) / AUD-08 (Projects) — a property of
the shared name-only-verify adapter contract (ADR-0008), **not reintroduced or worsened** by this
service or the `case EMAIL` branch (neither adds verification logic). It is already explicitly
documented and accepted in this feature's own `adr/ADR-0014 §Consequences` ("an `email`→`rich_text`
retype is not detected … an accepted consequence"). Recorded here only as an Info cross-reference so
the People branch ledger is complete and the risk is revisited alongside AUD-08 if/when
property-type fidelity must be guaranteed. It is the documented, intended v0 contract that keeps
repair provably non-destructive (add-only), so it is not a defect against the current spec.

Authoritative citation: a documented scope decision, not a standard violation — authority is the
feature's own `adr/ADR-0014-email-property-type.md §Consequences` and reused
`../create-projects-database/adr/ADR-0008` (name-only verification as the accepted contract), plus
OWASP ASVS v4.0.3 §V1 (security/verification requirements must be explicit and risk-based) —
https://owasp.org/www-project-application-security-verification-standard/. No new OWASP / Jakarta /
Spring requirement is implicated.

Recommended fix: none required for v0. Track with AUD-08; when type fidelity becomes a requirement,
extend the shared adapter's `verify`/`repairShape` (not this service) to compare the Notion property
`type`, still additively and non-destructively.

## 3. Principle scorecard

| Principle | Rating | Justification |
|---|---|---|
| SOLID | Good | DIP clean: `CreatePeopleDatabaseService` depends only on `NotionProvisioningPort`/`WorkspaceRepository`/`WorkspaceLedgerWriter` interfaces (`:33-35`), never a concrete adapter. SRP holds — one class, one provisioning step; the `case EMAIL` branch adds one responsibility-preserving mapping. OCP: the port/adapter was *extended* additively (new enum member + one branch) with existing branches unmoved. The pre-existing fat-`NotionProvisioningPort` ISP smell is inherited and untouched (no port method added) — scorecard note only, consistent with prior audits. |
| Clean / Hexagonal architecture | Excellent | Application service speaks only typed schema records; no Notion/HTTP/`data_source` type leaks inward. The `email` config detail is confined to the infrastructure adapter's `propertyConfig`. Inner→outer direction preserved; domain (`Person`/`Email` fields) referenced, infrastructure not. |
| DDD | Excellent | `Person` reused by its two fields (`name`, `email`) with no anemic leakage, no entity-as-DTO serialization, no primitive obsession introduced (`Email` VO modeled as a first-class Notion `email` type, not stringified — ADR-0014 rejects `rich_text`); aggregates referenced by `UUID`. No domain change smuggled in; row-level `Email` validation correctly left out of the schema step. |
| Security | Excellent (for the changed surface) | Token confined to the unchanged `NotionClient`, provably absent from the service's `detail` strings (fixed literals), logs (ids/outcomes only), and exceptions (propagated unchanged) — OWASP ASVS §V7.1 / Secrets Management. No user input concatenated into JSON/URLs/queries; the `email` config is a constant map — no injection surface (OWASP — Injection Prevention). Schema-only step writes no rows, so no PII/email address is handled here (OWASP ASVS §V8). No new deserialization or auth surface. Only standing item is the inherited, accepted PEOPLE-15 reconcile-completeness note (availability/fidelity, not confidentiality). |
| DRY / YAGNI | Excellent | Schema authored once (`peopleSpec()`), reused by `peopleExpectedShape()`; `NotionPropertyType.EMAIL` added only where consumed and not retrofitted; the rejected catch-all "text-like" type (ADR-0014 opt.3) honours YAGNI in the correct direction; no copy-paste logic drift from the audited Resources baseline; no comment pollution. |

## 4. Blocking issues

**None.** No Critical, High, Medium, or Low findings. The changed surface is safe to merge. The
`case EMAIL` extension emits the correct minimal `{"type":"email","email":{}}` config on both create
and repair, is compile-checked by the exhaustive `switch` (JLS §14.11.2), introduces no injection or
token-leak surface, handles no PII (schema-only, no rows written), and is covered by exact-shape
contract tests; the service is a correct, faithful mirror of the audited Resources service and
reintroduces none of the known risks (token leak, NPE path, duplicate-guarantee break, transaction-
across-HTTP). The sole raised item, **PEOPLE-15 (Info)**, is an inherited/accepted cross-reference to
AUD-14 / AUD-08, already documented in ADR-0014.

---
Routing: PEOPLE-15 → tracked with AUD-08 on the tech-spec/architecture backlog (no action for v0).
No source files were modified by this audit.
