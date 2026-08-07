# 05 — Audit Report: Create Habits Database (database slice)

Stage: Auditor (6/6) · Scope: the sole touched production surface of the "Create Habits Database"
feature — `application/usecase/habit/CreateHabitsDatabaseService.java` — and its two tests
(`CreateHabitsDatabaseServiceTest.java`, `CreateHabitsDatabaseServiceIT.java`). The generic
`NotionProvisioningAdapter` DB slice, `NotionClient`, `NotionProvisioningPort`, the typed schema
value types, and `Frequency` are **out of scope** (unchanged; the adapter/port slice was audited
under Create Projects Database — AUD-07 fixed, AUD-08 accepted — and Create Tasks Database — AUD-09
accepted; Create Knowledge Database — clean). Confirmed unchanged via the QA scope-honesty check
(`findings.yml` QA-001: adapter/port/`WorkspaceLedgerWriter`/`domain/habit` untouched, verified by
file-modification timestamps and content re-read).

Verdict: **Approve — 0 Critical, 0 High, 0 Medium, 0 Low, no blocking issues.** The service is a
faithful, correct mirror of the already-audited `CreateTasksDatabaseService` (itself a mirror of
`CreateProjectsDatabaseService`); it inherits those classes' good security and design properties and
reintroduces none of the known risks (token leak, NPE path, duplicate-guarantee break). One Info
item (AUD-11) tracks the inherited, accepted name-only-verify trade-off as it applies to the Habits
`Frequency` value set. A short report is the correct outcome here.

## 1. Executive summary — faithful-mirror verification

A mechanical substitution diff of the two services (`Habits`→`Tasks`, `HABITS_DB`→`TASKS_DB`) shows
`execute`, `executeWarmPath`, and `executeColdPath` (`CreateHabitsDatabaseService.java:39-112`) are
**byte-for-byte identical** to the audited `CreateTasksDatabaseService.java:39-112`. The only residual
differences are the package, the domain import (`domain.habit.Frequency` vs `domain.task.TaskStatus`),
the spec-builder method names, and the intended schema property list (`:114-125`). Every focus area
was checked line-by-line:

- **Outcome mapping — identical.** The 9-row outcome table holds: `CREATED` only on first-time cold
  create / orphan-absent (`:91,109`); adoption is never `CREATED` (`:76,80,98-104`); `REPAIRED` ⇔ a
  Notion write ran this run; `RECONCILED` ⇔ none (`:66,99`). No divergence in control flow, outcome
  strings, logging shape, or error propagation.
- **`>1` identity match ⇒ FAILED propagation — preserved.** `findChildByIdentity`'s ambiguity
  `NotionApiException` is never caught; it propagates out of `execute` on both the cold (`:86`) and
  warm-ABSENT (`:73`) paths, aborting before any `ledger.record`. Directly asserted by
  `execute_propagatesAmbiguousMatchFailureOnColdPath` (`Test.java:138`) and `...OnWarmAbsentPath`
  (`Test.java:207`), each with `verifyNoInteractions(ledger)`.
- **Ledger own-transaction — preserved.** The only transactional write remains
  `WorkspaceLedgerWriter.record` (reused verbatim, its own `@Transactional`); the service adds no
  transaction. Notion-write-before-ledger-write ordering is intact, so a Notion failure never writes a
  ledger row (`:78-79,89-90,107-108`; tests `Test.java:220,232` assert `verifyNoInteractions(ledger)`).
- **No `@Transactional` on the HTTP-bound `execute` — preserved and enforced.** Neither the class nor
  `execute` carries `@Transactional` (`:28-31,39-40`); asserted reflectively by
  `execute_isNotAnnotatedTransactional` (`Test.java:265-269`). Correctly avoids holding a JDBC
  connection across several slow Notion HTTP calls (Spring Data JPA reference — Transaction Management;
  the connection is bound to the transaction scope, docs.spring.io/spring-framework/reference/data-access/transaction.html).
- **Token confinement — preserved.** The service never references the Bearer token; it depends only on
  the `NotionProvisioningPort` interface (`:35`). Every `ProvisioningStepResult.detail` is a fixed
  literal string or `null` (`:66,70,76,80,91,99,104,109`) — no token, no exception text, no request
  body. Logs carry only `workspaceId`, `dashboardId`, prior ledger id, `notionId`, `VerificationResult`,
  and outcome (`:56-57,64,87`) — never the token. Same provable confinement as the Tasks/Projects
  audits; no new leak surface introduced (OWASP ASVS v4.0.3 §V7.1 — no sensitive data in logs;
  §V9/§V6 — secret handling).
- **No NPE reintroduction.** The only dereferences are `.map(...).orElseThrow(...)`-guarded optionals
  (`:44-46,50`); the AUD-07 `repairShape`/`titleOf(null)` NPE lived in the (unchanged, already-fixed)
  adapter and is not touched here. The service adds no unguarded read.
- **Never-duplicate correctness — preserved.** Adoption-before-create on every path (`:73,86`);
  `Workspace.record` upsert semantics keep exactly one `HABITS_DB` row. Proven end-to-end by
  `CreateHabitsDatabaseServiceIT.execute_convergesToOneRowAcrossThreeReruns` (`IT.java:97-110`:
  RECONCILED on reruns, one row, stable `notionId`).
- **Injection.** No user-influenced value is concatenated into a URL, query, or log format string; the
  title (`"Habits"`, constant `:33`) and property names (`"Name"`, `"Frequency"`, `:119-120`) are
  compile-time literals that travel only inside the `DatabaseSpec`/`ExpectedShape` records handed to the
  port, which the (unchanged) adapter Jackson-serializes. No injection surface added (OWASP Cheat Sheet
  Series — Injection Prevention: use structured APIs / parameterization, not string assembly).
- **DDD / closed-enum reuse — correct.** `Frequency` options are seeded from `Frequency.values()`
  mapped by `Enum::name`, one option per constant, in declaration order (`:115-117`) — the enum is the
  single source of truth for the SELECT option set (ADR-0009 pattern). `Frequency` remains a proper
  closed domain enum (`Frequency.java:3-7`: DAILY/WEEKLY/MONTHLY); no anemic leakage, no primitive
  obsession, no domain change smuggled in (Effective Java, Item 34: use enums instead of int/String
  constants).
- **DRY / YAGNI.** Schema authored once in `habitsSpec()`; `habitsExpectedShape()` reuses
  `habitsSpec().properties()` verbatim (`:114-125`). No speculative shared multi-database schema builder
  introduced. No comment pollution — the service and both test files contain zero noise or
  "AI-generated" comments (workspace hard rule).

### Divergences from the Tasks baseline — reviewed, all intended

| Divergence | Location | Verdict |
|---|---|---|
| Schema = 2 properties (`Name`/TITLE, `Frequency`/SELECT) vs Tasks' 4 | `:118-120` | **Intended per spec.** Habits models only name + cadence; asserted by `habitsSpec_buildsTwoPropertiesWithFrequencyOptionsFromEnum` and `doesNotContain("Description","Due Date")` (`Test.java:271-282`). |
| Title property named `"Name"` (not `"Title"`) | `:119` | **Intended.** Each DB names its title property after its own aggregate; no structural difference — single `TITLE`-typed property. |
| `Frequency` options via `Enum::name` → `DAILY/WEEKLY/MONTHLY` | `:115-117` | Same pattern as Tasks `Status` via `Enum::name`; the closed enum is the single source of truth. |
| `TITLE = "Habits"`, `HABITS_DB` type, `domain.habit.Frequency` import | `:33,26,12` | Correct target substitution. |

No divergence in control flow, outcome strings, transaction boundary, logging shape, or error
propagation. This is the faithful mirror the tech-spec specified.

### Severity counts

| Severity | Count | IDs |
|---|---|---|
| Critical | 0 | — |
| High | 0 | — |
| Medium | 0 | — |
| Low | 0 | — |
| Info | 1 | AUD-11 |

**Blocking issues: 0.**

## 2. Findings

### AUD-11 — Info — Design / Reconcile-completeness (inherited, accepted v0 scope) — name-only verify does not detect `Frequency` option-set or property-type drift for the Habits DB
Evidence: `CreateHabitsDatabaseService.java:115-120` seeds the `Frequency` select from
`Frequency.values()`, but shape verification runs through the unchanged, out-of-scope
`NotionProvisioningAdapter.verify` (name-presence-only). The IT's fake port models this exactly:
`verify` only checks title equality and property-name presence (`IT.java:161-176`). If a user retypes
`Frequency` from select→text or edits its options in Notion, `verify` still returns `PRESENT_MATCHING`
and no repair occurs.

This is the **same accepted trade-off** raised as AUD-08 (Projects) and cross-referenced as AUD-09
(Tasks) — a property of the shared adapter, **not reintroduced or worsened** by this service (the
service adds no verification logic). It is recorded here only as an Info cross-reference so the Habits
branch's ledger is complete and the risk is revisited alongside AUD-08 if/when `Frequency` value
fidelity must be guaranteed. It is the documented, intended v0 contract that makes repair provably
non-destructive, so it is not a defect against the current spec.

Authoritative citation: this is a documented scope decision, not a standard violation — authority is
the reused `../create-projects-database/adr/ADR-0008` (name-only verification as the accepted contract)
and the ADR-0009 select-option-label pattern, plus OWASP ASVS v4.0.3 §V1 (security requirements must
be explicit and risk-based) —
https://owasp.org/www-project-application-security-verification-standard/. No new OWASP / Jakarta /
Spring requirement is implicated.

Recommended fix: none required for v0. Track with AUD-08; when option/type fidelity becomes a
requirement, extend the shared adapter's `verify`/`repairShape` (not this service) to compare property
type and the SELECT option-name set, still additively and non-destructively.

## 3. Principle scorecard

| Principle | Rating | Justification |
|---|---|---|
| SOLID | Good | DIP clean: the service depends only on the `NotionProvisioningPort` / `WorkspaceRepository` / `WorkspaceLedgerWriter` interfaces (`:35-37`), never a concrete adapter. SRP holds — one class, one provisioning step. The pre-existing fat-`NotionProvisioningPort` ISP smell is inherited from the adapter layer and untouched by this feature (it adds no port method); scorecard note only, consistent with prior audits. |
| Clean / Hexagonal architecture | Excellent | Application-layer service speaks only `DatabaseSpec`/`ExpectedShape`/`PropertyDefinition`/`ProvisioningStepResult`; no Notion/HTTP type appears. Inner→outer dependency direction preserved; domain (`Frequency`) referenced, infrastructure not. |
| DDD | Excellent | `Frequency` is a proper closed domain enum (`Frequency.java:3-7`) and the single source of truth for the option set (`:115`); no anemic leakage, no primitive obsession, aggregates referenced by `UUID` only. No domain change smuggled in. |
| Security | Excellent (for the changed surface) | Token confined to the unchanged `NotionClient`, provably absent from the service's `detail` strings (fixed literals / `null`), logs (ids/outcomes only), and exceptions (propagated unchanged); no user input concatenated into URLs/queries/log formats; no new deserialization or auth surface. Only standing item is the inherited, accepted AUD-11/AUD-08 reconcile-completeness note — availability/fidelity, not confidentiality. |
| DRY / YAGNI | Excellent | Schema authored once (`habitsSpec()`), reused by `habitsExpectedShape()`; faithful reuse of the audited Tasks pattern with no copy-paste drift in logic (substitution diff clean); no speculative multi-database abstraction added; no comment pollution. |

## 4. Blocking issues

**None.** No Critical, High, Medium, or Low findings. The feature is safe to merge on the audited
surface. `CreateHabitsDatabaseService` is a correct, faithful mirror of the audited
`CreateTasksDatabaseService`, inherits its good properties, and reintroduces none of the known risks
(token leak, NPE path, duplicate-guarantee break). The sole raised item, **AUD-11 (Info)**, is an
inherited/accepted cross-reference to AUD-08 — tracked, no action required for v0.

---
Routing: AUD-11 → tracked with AUD-08 on the tech-spec/architecture backlog (no action for v0).
No source files were modified by this audit.
