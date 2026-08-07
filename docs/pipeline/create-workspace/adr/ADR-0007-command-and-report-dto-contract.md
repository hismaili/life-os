# ADR-0007: Command and report DTO contract changes

## Status
Accepted (Architect stage). Resolves OQ-5 (sample-data re-run = skip/reconcile: treat existing sample data as already present; never duplicate or reset).

## Context
The existing `CreateWorkspaceCommand(name, personId)` does not enforce `personId` non-null (FR-3) and has no sample-data flag (FR-13). `CreateWorkspaceUseCase.execute` returns `void`, so it cannot report outcomes (FR-12). DTOs in this project are records with compact-constructor validation carrying data, not behavior (`CLAUDE.md`; `spring-boot-conventions`).

## Options considered
**Command shape**
1. Keep `CreateWorkspaceCommand(name, personId)`, add sample-data as a separate CLI arg passed around out-of-band. (−) Splits one intent across two inputs; the use case cannot see the flag. Rejected.
2. Extend to `CreateWorkspaceCommand(name, personId, boolean sampleData)` with compact-constructor validation for non-blank name and non-null personId.
   - (+) Single validated intent; enforces FR-2/FR-3 before any adapter call. (+) Matches record-with-validation convention.

**Return type**
1. Keep `void`; expose outcomes via logs only. (−) Fails FR-12 (caller receives a summary). Rejected.
2. Return a `ProvisioningReport` (list of `ProvisioningStepResult` + a `failed()` predicate).
   - (+) Directly satisfies FR-12; drives the CLI's success/failure rendering (ADR-0006).

## Decision
- `CreateWorkspaceCommand` becomes `(String name, UUID personId, boolean sampleData)` with compact-constructor validation rejecting blank name and null personId.
- `CreateWorkspaceUseCase.execute` returns `ProvisioningReport`.
- Each provisioning step returns `ProvisioningStepResult` (see ADR-0005).

## Consequences
- Existing callers/tests of the two-arg command and the `void` use case must be updated (coordinated change; flagged for Implementer).
- `sampleData` re-run semantics (OQ-5, resolved): when `--sample-data` is set and sample data already exists, `PopulateSampleDataUseCase` skips/reconciles (idempotent — no duplicate records, no reset).
- No entity crosses the CLI or REST boundary; only records do (`spring-boot-conventions`).
