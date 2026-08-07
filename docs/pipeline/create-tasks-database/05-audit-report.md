# 05 — Audit Report: Create Tasks Database (database slice)

Stage: Auditor (6/6) · Scope: the sole touched production surface of the "Create Tasks Database"
feature — `application/usecase/task/CreateTasksDatabaseService.java` — and its two tests
(`CreateTasksDatabaseServiceTest.java`, `CreateTasksDatabaseServiceIT.java`). The generic
`NotionProvisioningAdapter` DB slice, `NotionClient`, `NotionProvisioningPort`, the typed schema
value types, and `Task`/`TaskStatus` are **out of scope** (unchanged; audited under Create
Projects Database — AUD-07 fixed, AUD-08 accepted). Confirmed unchanged via the QA scope-honesty
check (`04-qa-report.md §4`, `findings.yml` QA-PASS) and the tech-spec's "Ripple: none" (§1).

Verdict: **Approve — 0 Critical, 0 High, 0 Medium, 0 Low, no blocking issues.** The service is a
faithful, correct mirror of the already-audited `CreateProjectsDatabaseService`; it inherits that
class's good security and design properties and reintroduces none of the known risks. One Info
item (AUD-09) tracks the inherited, accepted name-only-verify trade-off as it applies to the Tasks
`Status` value set. A short report is the correct outcome here.

## 1. Executive summary — faithful-mirror verification

Every focus area was checked line-by-line against the audited baseline
(`../create-projects-database/05-audit-report.md` and
`application/usecase/project/CreateProjectsDatabaseService.java`):

- **Outcome mapping — identical.** `execute`, `executeWarmPath`, `executeColdPath` are
  structurally byte-for-byte identical to the Projects baseline with `PROJECTS_DB → TASKS_DB`
  (`CreateTasksDatabaseService.java:40-112` vs `CreateProjectsDatabaseService.java:40-112`). The
  9-row outcome table holds: `CREATED` only on first-time cold create/orphan-absent
  (`:91,109`); adoption is never `CREATED` (`:76,80,98-104`); `REPAIRED` ⇔ a Notion write ran
  this run; `RECONCILED` ⇔ none (`:66,99`). No divergence.
- **`>1` identity match ⇒ FAILED propagation — preserved.** `findChildByIdentity`'s ambiguity
  `NotionApiException` is never caught and propagates out of `execute` on both the cold
  (`:86`) and warm-ABSENT (`:73`) paths, aborting before any `ledger.record`. Directly asserted
  by `CreateTasksDatabaseServiceTest.execute_propagatesAmbiguousMatchFailureOnColdPath` (`:138`)
  and `...OnWarmAbsentPath` (`:207`), each with `verifyNoInteractions(ledger)`.
- **Ledger own-transaction — preserved.** The only transactional write remains
  `WorkspaceLedgerWriter.record` (reused verbatim, its own `@Transactional`); the service adds
  no transaction. Notion-write-before-ledger-write ordering is intact, so a Notion failure never
  writes a ledger row (`:78-79,89-90,107-108`; tests `:220,232`).
- **No `@Transactional` on the HTTP-bound `execute` — preserved and enforced.** Neither the class
  nor `execute` carries `@Transactional` (`:28-31,39-40`); asserted reflectively by
  `execute_isNotAnnotatedTransactional` (`:265-269`). Correctly avoids holding a JDBC connection
  across several slow Notion HTTP calls (Spring Data JPA reference — Transaction Management;
  connection is bound to the transaction scope).
- **Token confinement — preserved.** The service never references the Bearer token; it depends
  only on the `NotionProvisioningPort` interface (`:35`). Every `ProvisioningStepResult.detail`
  is a fixed literal string (`:66,70,76,80,91,99,104,109`) — no token, no exception text, no
  request body. Logs carry only `workspaceId`, `dashboardId`, prior ledger id, `notionId`,
  `VerificationResult`, and outcome (`:56-57,64,87`) — never the token. This is the same
  provable confinement the Projects audit confirmed; no new leak surface is introduced.
- **No NPE reintroduction.** The only dereferences are `.map(...).orElseThrow(...)` guarded
  optionals (`:44-46,50`); the AUD-07 `repairShape`/`titleOf(null)` NPE lived in the (unchanged,
  already-fixed) adapter and is not touched here. The service adds no unguarded read.
- **Never-duplicate correctness — preserved.** Adoption-before-create on every path (`:73,86`);
  `Workspace.record` upsert semantics keep exactly one `TASKS_DB` row. Proven end-to-end by
  `CreateTasksDatabaseServiceIT.execute_convergesToOneRowAcrossThreeReruns` (`:97-110`,
  RECONCILED on reruns, one row, stable `notionId`).
- **Injection.** No user-influenced value is concatenated into a URL, query, or log format
  string; the title (`"Tasks"`, constant `:33`) and property names travel only inside the
  `DatabaseSpec`/`ExpectedShape` records handed to the port, which the (unchanged) adapter
  Jackson-serializes. No injection surface added.
- **DDD / closed-enum reuse — correct.** `Status` options are seeded from `TaskStatus.values()`
  mapped by `Enum::name`, one option per constant, in declaration order (`:114-117`) — the enum
  is the single source of truth (ADR-0006/ADR-0009). `TaskStatus` remains a proper closed enum
  (`TaskStatus.java:3-9`); no anemic leakage, no primitive obsession, no domain change.
- **DRY / YAGNI.** Schema authored once in `tasksSpec()`; `tasksExpectedShape()` reuses
  `tasksSpec().properties()` verbatim (`:114-127`). No speculative shared multi-database schema
  builder was introduced (tech-spec §6 scope guard honoured). No comment pollution — the service
  and both test files contain zero noise or "AI-generated" comments.

### Divergences from the Projects baseline — reviewed, all intended

| Divergence | Location | Verdict |
|---|---|---|
| `Status` options via `Enum::name` (not `ProjectStatus::displayName`) → `SCREAMING_SNAKE_CASE` labels | `:115` | **Accepted decision, not a defect.** ADR-0009 (Accepted) chooses `TaskStatus.name()` verbatim because `TaskStatus` has no `displayName()` and adding one is out of this step's authorization; name-only verify (ADR-0008) makes labels immaterial to correctness/idempotency. Cosmetic only; tracked follow-up. |
| Title property named `"Title"` (not `"Name"`) | `:119` | **Intended.** Each DB names its title property after its own aggregate field (`Task.title`); tech-spec §2 "Naming note". No structural difference — single `TITLE`-typed property. |
| `TITLE = "Tasks"`, `TASKS_DB` type | `:33,26` | Correct target substitution. |

No divergence in control flow, outcome strings, transaction boundary, logging shape, or error
propagation. This is the faithful mirror the tech-spec (§3.2 delta list) specified.

### Severity counts

| Severity | Count | IDs |
|---|---|---|
| Critical | 0 | — |
| High | 0 | — |
| Medium | 0 | — |
| Low | 0 | — |
| Info | 1 | AUD-09 |

**Blocking issues: 0.**

## 2. Findings

### AUD-09 — Info — Design / Reconcile-completeness (inherited, accepted v0 scope) — name-only verify does not detect `Status` option-set or property-type drift for the Tasks DB
Evidence: `CreateTasksDatabaseService.java:115-121` seeds the `Status` select from
`TaskStatus.values()`, but shape verification runs through the unchanged, out-of-scope
`NotionProvisioningAdapter.verify` (name-presence-only, ADR-0008). If a user retypes `Status`
from select→text or edits its options in Notion, `verify` still returns `PRESENT_MATCHING` and no
repair occurs.

This is the **same accepted trade-off** raised and accepted as AUD-08 under the Projects audit —
it is a property of the shared adapter, **not reintroduced or worsened** by this service (the
service adds no verification logic). It is recorded here only as an Info cross-reference so the
Tasks branch's ledger is complete and so the risk is revisited alongside AUD-08 if/when `Status`
value fidelity must be guaranteed. It is the documented, intended v0 contract that makes repair
provably non-destructive, so it is not a defect against the current spec. Note also that ADR-0009
explicitly relies on name-only verify to make the `SCREAMING_SNAKE_CASE` label choice immaterial.

Authoritative citation: this is a documented scope decision, not a standard violation — authority
is the feature's own `adr/ADR-0009-taskstatus-select-option-labels.md` (Consequences) and reused
`../create-projects-database/adr/ADR-0008` (name-only verification as the accepted contract), plus
OWASP ASVS v4.0.3 §V1 (security requirements must be explicit and risk-based) —
https://owasp.org/www-project-application-security-verification-standard/. No new OWASP / Jakarta /
Spring requirement is implicated.

Recommended fix: none required for v0. Track with AUD-08; when option/type fidelity becomes a
requirement, extend the shared adapter's `verify`/`repairShape` (not this service) to compare
`NotionPropertyConfig.type` and the SELECT option-name set, still additively and non-destructively.

## 3. Principle scorecard

| Principle | Rating | Justification |
|---|---|---|
| SOLID | Good | DIP clean: `CreateTasksDatabaseService` depends only on the `NotionProvisioningPort` / `WorkspaceRepository` / `WorkspaceLedgerWriter` interfaces (`:35-37`), never a concrete adapter. SRP holds — one class, one provisioning step. The pre-existing fat-`NotionProvisioningPort` ISP smell is inherited from the adapter layer and is untouched by this feature (it adds no port method); scorecard note only, consistent with the Projects/Dashboard audits. |
| Clean / Hexagonal architecture | Excellent | Application-layer service speaks only `DatabaseSpec`/`ExpectedShape`/`PropertyDefinition`/`ProvisioningStepResult`; no Notion/HTTP type and no `data_source` concept appears. Inner→outer dependency direction preserved; domain (`TaskStatus`) referenced, infrastructure not. |
| DDD | Excellent | `TaskStatus` is a proper closed domain enum and the single source of truth for the option set (`:115`, ADR-0006/0009); no anemic leakage, no primitive obsession, aggregates referenced by `UUID` only. No domain change smuggled in (tech-spec §6). |
| Security | Excellent (for the changed surface) | Token confined to the unchanged `NotionClient`, provably absent from the service's `detail` strings (fixed literals), logs (ids/outcomes only), and exceptions (propagated unchanged); no user input concatenated into URLs/queries/log formats; no new deserialization or auth surface. Only standing item is the inherited, accepted AUD-09/AUD-08 reconcile-completeness note — availability/fidelity, not confidentiality. |
| DRY / YAGNI | Excellent | Schema authored once (`tasksSpec()`), reused by `tasksExpectedShape()`; faithful reuse of the audited Projects pattern with no copy-paste drift in logic; no speculative multi-database abstraction added; no comment pollution. |

## 4. Blocking issues

**None.** No Critical, High, Medium, or Low findings. The feature is safe to merge on the audited
surface. `CreateTasksDatabaseService` is a correct, faithful mirror of the audited
`CreateProjectsDatabaseService`, inherits its good properties, and reintroduces none of the known
risks (token leak, NPE path, duplicate-guarantee break). The sole raised item, **AUD-09 (Info)**,
is an inherited/accepted cross-reference to AUD-08 — tracked, no action required for v0.

---
Routing: AUD-09 → tracked with AUD-08 on the tech-spec/architecture backlog (no action for v0).
No source files were modified by this audit.
