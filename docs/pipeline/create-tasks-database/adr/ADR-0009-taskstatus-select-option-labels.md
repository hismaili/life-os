# ADR-0009: Tasks `Status` select-option labels seeded verbatim from `TaskStatus.name()`

## Status
Accepted (Architect stage, Create Tasks Database branch). Scope-local to the Tasks step. Depends on ADR-0006 (Status is a `select`, options seeded from the domain enum) and ADR-0008 (verification is name-only). It is the **only** new decision this branch makes; ADR-0005..0008 are reused unchanged (`../../create-projects-database/adr/`).

## Context
Spec §3 maps the Tasks `Status` property to a Notion `select` whose options are seeded, one-per-constant, from the `TaskStatus` domain enum `{TODO, IN_PROGRESS, BLOCKED, COMPLETED, CANCELLED}` (`domain/task/TaskStatus.java`) — following ADR-0006's precedent that the domain enum is the single source of truth for the option set.

The Projects step derived each option **label** from `ProjectStatus.displayName()` (e.g. `ON_HOLD → "On hold"`). **`TaskStatus` has no `displayName()` method** and carries no per-constant label. The Create Tasks spec (§7, §8, open question §9) flags the label source as an `[ASSUMPTION]` and explicitly places *adding* a `displayName()` to `TaskStatus` outside its authorization ("no `domain/task/` change" constraint, §7). So the label source must be decided here without a domain change unless one is clearly justified.

Because ADR-0008 makes verification **name-only** (property *existence*, not option-set equality), the specific label strings **cannot** cause spurious drift or repair — they are applied at creation only and are never compared on re-run. The choice is therefore purely a v0 presentation nicety, and presentation is out of this backend step's scope (spec §8; `CLAUDE.md` reserves presentation for `notion/` assets).

## Options considered
1. **Seed labels verbatim from `TaskStatus.name()`** (chosen) — e.g. `"TODO"`, `"IN_PROGRESS"`, `"BLOCKED"`, `"COMPLETED"`, `"CANCELLED"`.
   - (+) **Zero churn, no domain change** — honours the spec's "no `domain/task/` change" constraint (§7).
   - (+) The enum stays the single source of truth (ADR-0006): options come from `TaskStatus.values()`, one per constant, mechanically.
   - (+) No new application-layer presentation code; the schema builder mirrors `CreateProjectsDatabaseService.projectsSpec()` structurally, swapping `displayName()` for `name()`.
   - (−) Labels render as `SCREAMING_SNAKE_CASE`, visually inconsistent with the Projects DB's humanized labels ("On hold"). Cosmetic only; invisible to every FR/AC and never a drift trigger (ADR-0008).
2. **Add `TaskStatus.displayName()`** (mirror `ProjectStatus`) and seed humanized labels.
   - (+) Cross-database label consistency ("In progress" alongside "On hold").
   - (−) A `domain/task/` change the spec explicitly excludes from this step's authorization (§8). Rejected here; recorded as a tracked follow-up (below) rather than smuggled in.
3. **Humanize `name()` in an application-layer helper** (e.g. `IN_PROGRESS → "In progress"`).
   - (+) Nice labels, no domain change.
   - (−) Puts presentation/formatting logic in the application layer for a concern that is out of scope, and splits the label vocabulary between two mechanisms (domain `displayName()` for Projects, an app-layer humanizer for Tasks) — an inconsistency worse than the cosmetic one it fixes. Rejected (YAGNI; scope).

## Decision
For the Tasks step, seed the `Status` select options from **`TaskStatus.name()` verbatim**, one option per enum constant, authored once in the service's `tasksSpec()` builder. No change to `TaskStatus`. This is the lowest-churn option that keeps the domain enum as the single source of truth (ADR-0006) and touches no domain code (spec §7). Label aesthetics are deliberately not addressed here because ADR-0008's name-only verification makes them immaterial to correctness and idempotency, and presentation is out of scope for this provisioning step.

## Consequences
- `CreateTasksDatabaseService.tasksSpec()` maps `Arrays.stream(TaskStatus.values()).map(Enum::name).toList()` into the `Status` `PropertyDefinition` — structurally identical to Projects except `name()` replaces `displayName()`.
- The Tasks DB's `Status` options render in `SCREAMING_SNAKE_CASE`; a user may freely rename them in Notion and the change is preserved (name-only verify never repairs option labels, ADR-0008).
- If cross-database label consistency is later wanted, the fix is a small, separate domain change — add `TaskStatus.displayName()` mirroring `ProjectStatus` and swap `name()` → `displayName()` in `tasksSpec()`. Tracked as a follow-up (below); not done in this step.
- No impact on any FR/NFR or acceptance criterion (verification is name-only; §3 requires the property to *exist*, options seed at creation only).

### Tracked follow-up (out of this step's scope)
- **Add `TaskStatus.displayName()`** for humanized, Projects-consistent select labels — a small `domain/task/` change outside this step's authorization (spec §8). Non-blocking; presentation quality only.
