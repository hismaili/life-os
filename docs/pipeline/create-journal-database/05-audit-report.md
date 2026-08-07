# 05 — Audit Report: Create Journal Database (database slice + `JournalEntry.title` domain change)

Stage: Auditor (6/6) · Scope: the touched production surface of the "Create Journal Database"
feature — `application/usecase/journal/CreateJournalDatabaseService.java` and
`domain/journal/JournalEntry.java` (the nullable-`title` domain change) — plus their three tests
(`JournalEntryTest.java`, `CreateJournalDatabaseServiceTest.java`, `CreateJournalDatabaseServiceIT.java`).
The generic `NotionProvisioningAdapter` DB slice, `NotionClient`, `NotionProvisioningPort`, the typed
schema value types, and `NotionPropertyType.DATE` are **out of scope** (unchanged / pre-existing;
audited under Projects — AUD-07 fixed, AUD-08 accepted — and Tasks AUD-09; Knowledge/Habits clean).
Confirmed via the QA scope-honesty check (`04-qa-report.md`, `findings.yml` QA-001) and ADR-0012 §Consequences
("only `domain/journal/` and the service change").

Verdict: **Approve — 0 Critical, 0 High, 0 Medium, no blocking issues.** The service is a faithful,
correct mirror of the already-audited `CreateTasksDatabaseService`/`CreateProjectsDatabaseService`;
it inherits their good security and design properties and reintroduces none of the known risks. The
`JournalEntry.title` domain change is a sound, coherent modeling choice that preserves every existing
invariant. Two non-blocking items are raised: one **Info** cross-reference to the inherited, accepted
name-only-verify trade-off as it applies to Journal's `Date` column (AUD-12), and one **Low**
pre-existing design-consistency note that `JournalEntry`'s public `@Builder` is an alternate
construction path around the self-validating `create(...)` factory (AUD-13). A short report is the
correct outcome here.

## 1. Executive summary — faithful-mirror verification

Every focus area was checked line-by-line against the audited baseline
(`../create-tasks-database/05-audit-report.md`,
`application/usecase/task/CreateTasksDatabaseService.java`).

- **Outcome mapping — identical.** `execute`, `executeWarmPath`, `executeColdPath` are structurally
  identical to the Tasks/Projects baseline with `TASKS_DB → JOURNAL_DB`
  (`CreateJournalDatabaseService.java:37-110` vs `CreateTasksDatabaseService.java:39-112`). `CREATED`
  only on first-time cold create / orphan-absent (`:89,107`); adoption is never `CREATED`
  (`:74,78,96-102`); `REPAIRED` ⇔ a Notion write ran this run; `RECONCILED` ⇔ none (`:64,97`). No
  divergence in control flow, outcome strings, or `detail` text.
- **`>1` identity match ⇒ FAILED propagation — preserved.** `findChildByIdentity`'s ambiguity
  `NotionApiException` is never caught and propagates out of `execute` on both the cold (`:84`) and
  warm-ABSENT (`:71`) paths, aborting before any `ledger.record`. Directly asserted by
  `CreateJournalDatabaseServiceTest.execute_propagatesAmbiguousMatchFailureOnColdPath` (`:138`) and
  `...OnWarmAbsentPath` (`:207`), each with `verifyNoInteractions(ledger)`.
- **Ledger own-transaction — preserved.** The only transactional write remains
  `WorkspaceLedgerWriter.record` (reused verbatim, its own `@Transactional`); the service adds no
  transaction. Notion-write-before-ledger-write ordering is intact, so a Notion failure never writes a
  ledger row (`:76-77,87-88,105-106`; tests `:220,232`, and the IT
  `execute_throwsWhenPhaseAIncomplete` asserts no `JOURNAL_DB` row on failure, `:128-137`).
- **No `@Transactional` on the HTTP-bound `execute` — preserved and enforced.** Neither the class nor
  `execute` carries `@Transactional` (grep-confirmed; `:26-38`); asserted reflectively by
  `execute_isNotAnnotatedTransactional` (`:265-269`). Correctly avoids holding a JDBC connection across
  several slow Notion HTTP calls (Spring Data JPA reference — Transaction Management: the connection is
  bound to the transaction scope — docs.spring.io/spring-framework/reference/data-access/transaction.html).
- **Token confinement (Security) — preserved.** The service never references the Bearer token; it
  depends only on the `NotionProvisioningPort` interface (`:33`). Every `ProvisioningStepResult.detail`
  is a fixed literal string (`:64,68,74,78,89,97,102,107`) — no token, no exception text, no request
  body. Logs (`:54-55,62,85`) carry only `workspaceId`, `dashboardId`, prior ledger id, `notionId`,
  `VerificationResult`, and outcome — never the token. Same provable confinement the Tasks/Projects
  audits confirmed; no new leak surface.
- **No NPE reintroduction.** The only dereferences are `.map(...).orElseThrow(...)` guarded optionals
  (`:42-44,48`); the AUD-07 `repairShape`/`titleOf(null)` NPE lived in the (unchanged, already-fixed)
  adapter and is not touched here.
- **Never-duplicate correctness — preserved.** Adoption-before-create on every path (`:71,84`);
  `Workspace.record` upsert semantics keep exactly one `JOURNAL_DB` row. Proven end-to-end by
  `CreateJournalDatabaseServiceIT.execute_convergesToOneRowAcrossThreeReruns` (`:96-110`, RECONCILED on
  reruns, one row, stable non-blank `notionId`).
- **Injection.** No user-influenced value is concatenated into a URL, query, or log format string. The
  database title marker (`"Journal"`, constant `:31`) and all property names (`"Title"`, `"Content"`,
  `"Date"`, constants `:113-116`) are compile-time literals handed to the port inside the typed
  `DatabaseSpec`/`ExpectedShape` records, which the (unchanged) adapter Jackson-serializes. No injection
  surface added. The nullable `JournalEntry.title` is domain data only and is **not** written by this
  schema-only step (FR-15; ADR-0012 §Consequences), so no user title value flows into any request here.
- **DRY / YAGNI.** Schema authored once in `journalSpec()`; `journalExpectedShape()` reuses
  `journalSpec().properties()` verbatim (`:112-121`). Correctly authors **no** `SELECT` property
  (`JournalEntry` has no closed-set field — ADR-0012), so ADR-0009's label concern does not arise. No
  speculative multi-database abstraction. No comment pollution — all three files and both production
  files contain **zero** comments (grep-confirmed), noise or otherwise.

### The `JournalEntry.title` domain change — reviewed, sound

- **Invariants preserved.** `create(...)` still mints the `UUID` identity (`JournalEntry.java:31`),
  rejects null/blank `content` (`:24-26`), rejects null `workspaceId` (`:27-29`), and defaults
  `timestamp` to `now()` when null (`:34`). Each is covered by a directly-asserting test
  (`JournalEntryTest.java:54-70,22-37,16-20`). `@Value` immutability holds — asserted by
  `create_isImmutable` (no `set*` methods, `:72-76`). Aggregates are still referenced by `UUID`
  (`workspaceId`, `personId`), never by object graph.
- **Nullable `title` is a coherent modeling call.** `title` is intentionally nullable and unvalidated
  per the stakeholder decision recorded in ADR-0012 (§"`title` — required or optional? → optional
  (nullable)"). This is the same shape of decision the Projects audit accepted for nullable
  `Project.dueDate`: an attribute meaningful when present but legitimately absent, while the aggregate's
  essence (`content` + `timestamp`) stays invariant-guarded. It is **not** anemic leakage — the entity
  keeps a rich self-validating factory; it simply models one genuinely-optional attribute. Covered by
  `create_allowsNullTitle` (`:39-44`) and `create_keepsProvidedTitle` (`:46-51`).
- **Leading `title` parameter applied consistently.** `create(...)` gained `title` as its leading
  parameter (`JournalEntry.java:19`). Inside the factory the value is threaded via the **named** builder
  method `.title(title)` (`:32`), so parameter position cannot silently mis-map to another field. There
  are **no** production call sites of `JournalEntry.create` / `JournalEntry.builder` (grep of
  `backend/src/main` returned none — the entity is not yet consumed by a provisioning path; the
  schema-only service does not touch it), and all six test call sites pass the 5-argument signature
  correctly (`JournalEntryTest.java:17,24,34,41,48,57-67`). No call site is silently wrong.

### Severity counts

| Severity | Count | IDs |
|---|---|---|
| Critical | 0 | — |
| High | 0 | — |
| Medium | 0 | — |
| Low | 1 | AUD-13 |
| Info | 1 | AUD-12 |

**Blocking issues: 0.**

## 2. Findings

### AUD-12 — Info — Design / Reconcile-completeness (inherited, accepted v0 scope) — name-only verify does not detect property-**type** drift, including the Journal `Date` column
Evidence: `CreateJournalDatabaseService.java:112-116` authors `Date` as `NotionPropertyType.DATE`, but
shape verification runs through the unchanged, out-of-scope `NotionProvisioningAdapter.verify`
(name-presence-only, ADR-0008). If a user retypes the `Date` column from `date` → `text` (or renames /
retypes `Content`) in Notion, `verify` still returns `PRESENT_MATCHING` and no repair occurs.

This is the **same accepted trade-off** raised as AUD-08 (Projects, `Status` option/type drift) and
cross-referenced as AUD-09 (Tasks). It is a property of the shared adapter, **not reintroduced or
worsened** by this service (the service adds no verification logic). Journal has **no** `SELECT`
property, so the AUD-08/09 option-set concern is N/A here; the residual is only the property-**type**
dimension (the `Date` column's `date` type), recorded so the Journal branch's ledger is complete and the
risk is revisited alongside AUD-08 if/when property-type fidelity must be guaranteed. It is the
documented, intended v0 contract that makes repair provably non-destructive, so it is not a defect
against the current spec (ADR-0012 §Consequences: "verification is name-only … the nullable title never
causes drift/repair"). This step also writes no rows (FR-15), so no `Date` value is populated here.

Authoritative citation: this is a documented scope decision, not a standard violation — authority is the
feature's own `adr/ADR-0012` (§Consequences) and reused `../create-projects-database/adr/ADR-0008`
(name-only verification as the accepted contract), with OWASP ASVS v4.0.3 §V1 (security/verification
requirements must be explicit and risk-based) —
https://owasp.org/www-project-application-security-verification-standard/. No new OWASP / Jakarta /
Spring requirement is implicated.

Recommended fix: none required for v0. Track with AUD-08/AUD-09; when type fidelity becomes a
requirement, extend the shared adapter's `verify`/`repairShape` (not this service) to compare
`NotionPropertyConfig.type`, still additively and non-destructively.

### AUD-13 — Low — Design / DDD self-validation (pre-existing, not a regression from this change) — `JournalEntry`'s public `@Builder` is an alternate construction path around the self-validating `create(...)` factory
Evidence: `domain/journal/JournalEntry.java:10` declares a plain, **public** `@Builder`. Because the
builder is public, application-layer code can call
`JournalEntry.builder().content(null).workspaceId(null).build()` (or mint its own `id`) and assemble a
`JournalEntry` in a state the `create(...)` factory (`:19-38`) forbids — bypassing the non-blank-`content`
and non-null-`workspaceId` invariants and the factory-minted identity. The audited baseline aggregates
`Project` (`domain/project/Project.java:11`) and `Task` (`domain/task/Task.java:11`) close this gap with
`@Builder(access = AccessLevel.PRIVATE)` — the exact property the Projects audit scored "DDD Excellent
… private builder". So on the in-scope aggregate the self-validation guarantee is weaker than the
mirror baseline.

Scope-honesty note: this is **pre-existing** — the feature's edit added the `title` field/parameter and
did not change the builder's visibility — and it is shared with several sibling aggregates that also use
a public `@Builder` (`Area`, `Habit`, `Knowledge`, `Review`, `Resource`), while `Project`, `Task`,
`Goal`, `Person`, `Workspace` use the private-builder form. It is therefore a codebase-wide
consistency smell surfaced by (not introduced by) this change, raised **Low / non-blocking** because it
directly bears on the focus question "is `JournalEntry` still a sound self-validating aggregate?": the
`create(...)` factory is sound, but the public builder leaves an un-validated back door. No current call
site exploits it (grep found none).

Authoritative citation: Effective Java, Item 15: Minimize the accessibility of classes and members — the
all-args builder should be no more accessible than its purpose (repository reconstitution of
already-valid state) requires; exposing it publicly widens the surface on which the class's own
construction-time invariants can be violated. (Also the project's own `CLAUDE.md` convention: "The
all-args builder/constructor is reserved for repository reconstitution … The application layer must
never mint IDs or assemble an entity in an invalid state.")

Recommended fix: change `@Builder` → `@Builder(access = AccessLevel.PRIVATE)` on `JournalEntry` (adding
a `reconstitute(...)` factory only if/when a `JournalEntryRepository` needs one), matching `Project`/`Task`;
optionally align the sibling public-builder aggregates in the same architecture-consistency sweep.

## 3. Principle scorecard

| Principle | Rating | Justification |
|---|---|---|
| SOLID | Good | DIP clean: `CreateJournalDatabaseService` depends only on the `NotionProvisioningPort` / `WorkspaceRepository` / `WorkspaceLedgerWriter` interfaces (`:33-35`), never a concrete adapter. SRP holds — one class, one provisioning step. The pre-existing fat-`NotionProvisioningPort` ISP smell is inherited from the adapter layer and untouched (this feature adds no port method); scorecard note only, consistent with the Projects/Tasks audits. |
| Clean / Hexagonal architecture | Excellent | The application-layer service speaks only `DatabaseSpec`/`ExpectedShape`/`PropertyDefinition`/`ProvisioningStepResult`; no Notion/HTTP type and no `data_source` concept appears. Inner→outer dependency direction preserved; domain referenced, infrastructure not. `JournalEntry` remains framework-free (`lombok` only). |
| DDD | Good | `JournalEntry` stays an immutable `@Value` aggregate with a self-validating `create(...)` factory that mints identity and enforces `content`/`workspaceId` invariants; aggregates referenced by `UUID`; the new nullable `title` is a coherent optional attribute (ADR-0012), not anemic leakage. One standing caveat: the public `@Builder` (AUD-13) is an un-validated construction back door the audited `Project`/`Task` baseline closes — pre-existing, non-blocking. |
| Security | Excellent (for the changed surface) | Token confined to the unchanged `NotionClient`, provably absent from the service's `detail` strings (fixed literals), logs (ids/outcomes only), and exceptions (propagated unchanged); all titles/property names are compile-time literals (no injection); the nullable `title` is not serialized by this schema-only step; no new deserialization or auth surface. Only standing item is the inherited, accepted AUD-12 reconcile-completeness note — availability/fidelity, not confidentiality. |
| DRY / YAGNI | Excellent | Schema authored once (`journalSpec()`), reused by `journalExpectedShape()`; faithful reuse of the audited Tasks/Projects pattern with no copy-paste drift in logic; correctly authors no `SELECT`/`relation` (YAGNI — `personId` link deferred to Phase C per ADR-0012); no comment pollution in any file. |

## 4. Blocking issues

**None.** No Critical, High, or Medium findings. The feature is safe to merge on the audited surface.
`CreateJournalDatabaseService` is a correct, faithful mirror of the audited
`CreateTasksDatabaseService`/`CreateProjectsDatabaseService`, and the `JournalEntry.title` domain change
is a sound, invariant-preserving stakeholder decision (ADR-0012).

Recommended (non-blocking) follow-ups:
- **AUD-13 (Low)** — align `JournalEntry`'s builder to the private-builder baseline (`Project`/`Task`)
  in an architecture-consistency sweep; pre-existing, not a regression from this change.
- **AUD-12 (Info)** — tracked with AUD-08/AUD-09; revisit property-type drift detection (incl. the
  `Date` column) when type fidelity becomes a requirement.

---
Routing: AUD-13 → architecture/impl consistency backlog (builder-access alignment; scorecard-consistent
with Projects/Tasks). AUD-12 → tracked with AUD-08/AUD-09 on the tech-spec/architecture backlog (no
action for v0). No source files were modified by this audit.
