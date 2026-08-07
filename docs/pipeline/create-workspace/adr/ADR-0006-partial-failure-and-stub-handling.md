# ADR-0006: Partial-failure and stub handling (collect, block dependents, fail overall)

## Status
Accepted (Architect stage).

## Context
Two requirements pull in different directions. FR-12/NFR-5 want a complete report of every step's outcome (created / reconciled / failed). FR-14/NFR-4 (`CLAUDE.md` "no silent no-op") require that an unimplemented step (a stub throwing `UnsupportedOperationException`) makes the **overall** operation fail explicitly, never silently skipped. Steps also have dependencies: relations cannot be built if a database step failed.

## Options considered
1. **Fail-fast abort**: on the first step failure, stop and propagate the exception immediately.
   - (+) Simplest; clearly "no silent no-op." (−) No complete report — the caller learns about only the first failure, violating FR-12's "distinguish created / existing / failed" for the rest.
2. **Best-effort swallow**: catch every failure, log, continue, return a report; do not fail the overall call.
   - (−) A stub would be effectively skipped and the command would appear to succeed — a direct FR-14/NFR-4 violation. Rejected.
3. **Collect-and-report, block dependents, then fail overall**: run each step; record `FAILED` on exception (never swallow the meaning); record `BLOCKED` for steps whose prerequisites failed (not attempted, not silently skipped); continue independent steps; after the run, if any step is `FAILED`/`BLOCKED`, the operation signals failure (report status `FAILED`, CLI non-zero result).
   - (+) Satisfies FR-12 (full report) and FR-14 (overall explicit failure). (+) Durable progress means a re-run reconciles (FR-11). (−) Slightly more orchestration logic.

## Decision
Adopt option 3. The orchestrator never lets a step "appear to succeed": exceptions map to `FAILED` with the message preserved; unmet prerequisites map to `BLOCKED`; a run containing any `FAILED`/`BLOCKED` step is reported as failed and surfaced to the caller as an error.

## Consequences
- FR-12, FR-14, NFR-4, NFR-5 satisfied together.
- The CLI must translate `ProvisioningReport.failed()` into a visible error / non-zero result.
- Dependency metadata (which steps block which) is encoded in the orchestrator's phase model (ADR-0005).
- A stubbed step (e.g. current `CreateTasksDatabaseService`) will make Create Workspace fail loudly until its adapter exists — the intended behavior.
