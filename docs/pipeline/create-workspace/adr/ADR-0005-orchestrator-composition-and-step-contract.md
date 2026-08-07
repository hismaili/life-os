# ADR-0005: Orchestrator composition and provisioning-step contract

## Status
Accepted (Architect stage).

## Context
Create Workspace sequences ~15 steps with a dependency DAG (databases → relations → rollups → formulas → sample data). The orchestrator must invoke each step in order, know which depend on which, and collect per-step outcomes for FR-12. Existing per-database use cases are separate interfaces (`CreateTasksDatabaseUseCase`, …) with `execute(UUID)` returning `void`.

## Options considered
1. **Orchestrator depends on an injected `List<ProvisioningStep>`** where each step declares its own phase/order; iterate generically.
   - (+) Open/Closed: add a step = add a bean. (−) Dependency ordering and "block dependents on failure" (ADR-0006) become implicit/config-driven and harder to read for a fixed, well-known set of 15 steps. (−) YAGNI: the step set is fixed by the domain model, not open-ended.
2. **Orchestrator holds explicit, typed dependencies** on each step interface and sequences them in code by phase; each step returns a `ProvisioningStepResult`.
   - (+) The sequence and dependency/blocking rules are explicit and unit-testable. (+) Reuses the existing per-use-case interfaces (spec §7 "extend, not replace"). (−) Adding a step edits the orchestrator — acceptable for a fixed set.
3. **Keep `void` steps; orchestrator infers created-vs-existing by reading the ledger before/after each call.**
   - (−) Fragile inference, extra reads, and can't distinguish `FAILED` cleanly. Rejected in favor of an explicit result type.

## Decision
Adopt option 2 for composition and change the step contract to **return `ProvisioningStepResult`** (option 3 rejected). The orchestrator lists steps by phase (A: dashboard, B: 7 databases — Goals & Reviews deferred, OQ-1 resolved, C: relations, D: rollups, E: formulas, F: sample data), invokes each, and aggregates results into a `ProvisioningReport`. The `List<ProvisioningStep>` approach (option 1) is noted as a future refactor if the step set ever becomes dynamic.

## Consequences
- Existing `execute(UUID)` signatures change from `void` to `ProvisioningStepResult` (see ADR-0007) — a coordinated edit across all stubs.
- The orchestrator is the single place encoding provisioning order and the phase-F gate for sample data (FR-13).
- Dependency/blocking logic (ADR-0006) lives in the orchestrator, explicitly.
