# 04 — QA Report: Create Workspace

Status: Complete
Owner (QA stage): pipeline automation
Inputs: `01-spec.md`, `02-architecture.md`, `adr/ADR-0001..0008`, `03-tech-spec.md`, `backend/src/`.

## 1. Verdict

**PASS** — the implementation is a faithful, mechanical realization of `03-tech-spec.md`, which in turn is a faithful realization of `02-architecture.md`/the ADRs and `01-spec.md`. All 67 tests pass, the build succeeds, and every load-bearing architectural decision is honored. The scaffold-only nature of Notion-facing behavior (every provisioning step and `NotionProvisioningAdapter` method throws `UnsupportedOperationException`) is **intentional and explicitly scoped** by `03-tech-spec.md` §12 ("Any real Notion SDK/HTTP integration... is a separate, later implementation pass") and architecture §8 finding 5 — this is not treated as a defect, consistent with the "no silent no-op" convention it is designed to demonstrate (FR-14).

0 violations found.

## 2. Test run

Command run (from `backend/`):

```
TESTCONTAINERS_RYUK_DISABLED=true ./mvnw verify
```

Result: **BUILD SUCCESS**

```
[INFO] Results:
[INFO]
[INFO] Tests run: 67, Failures: 0, Errors: 0, Skipped: 0
[INFO]
[INFO] BUILD SUCCESS
[INFO] Total time:  5.934 s
```

No failing tests. Testcontainers ran against Podman via the Unix docker socket with Ryuk disabled, as documented (accepted environment adaptation, not a finding). `@MockBean` (not `@MockitoBean`) is used in `WorkspaceControllerTest.java:12,34` — accepted per the Spring Boot 3.3.2 constraint noted in the task briefing, not a finding.

## 3. Acceptance criteria matrix

| Criterion | Verdict | Evidence |
|---|---|---|
| FR-1 (accept name+personId) | PASS | `CreateWorkspaceCommand(String,UUID,boolean)` — `backend/src/main/java/com/lifeos/application/dto/workspace/CreateWorkspaceCommand.java:5`; `WorkspaceCommands.create` — `.../infrastructure/adapter/cli/WorkspaceCommands.java:20-25`; `WorkspaceController.create` — `.../infrastructure/adapter/web/WorkspaceController.java:24-26` |
| FR-2 (reject blank name, no adapter call) | PASS | Compact constructor throws before any collaborator is touched — `CreateWorkspaceCommand.java:7-9`; test `CreateWorkspaceCommandTest.constructor_rejectsBlankName` — `.../test/.../CreateWorkspaceCommandTest.java:13-20` |
| FR-3 (reject null personId) | PASS | `CreateWorkspaceCommand.java:10-12`; test `CreateWorkspaceCommandTest.constructor_rejectsNullPersonId:23-26` |
| FR-1/2/3 "no validation error on valid input" | PASS | `CreateWorkspaceCommandTest.constructor_acceptsValidInputsWithSampleDataFlag:28-37` |
| FR-4 (create/persist new Workspace, load-or-create) | PASS | `CreateWorkspaceService.execute:44-45` (load-or-create by `(personId,name)`); `Workspace.create` mints id — `domain/workspace/Workspace.java:22-35`; tests `CreateWorkspaceServiceTest.execute_createsNewWorkspaceWhenNoneExists:90-99`, `execute_reusesExistingWorkspaceWhenPresent:101-109`; `JpaWorkspaceRepositoryTest.save_persistsNewWorkspaceWithEmptyLedger:64-73`; `CreateWorkspaceIT.reRunWithSamePersonIdAndName_reusesExistingWorkspaceRow:67-79` (real DB, one row after two runs) |
| FR-5 (dashboard, Phase A) | PASS (contract/orchestration); functional behavior intentionally not yet implemented | `CreateWorkspaceService.execute:49-51` runs Dashboard first, blocks dependents on failure; `CreateDashboardService.execute` throws `UnsupportedOperationException` — `application/usecase/workspace/CreateDashboardService.java:22-24` (per tech-spec §12, real Notion calls deferred); orchestration tested in `CreateWorkspaceServiceTest.execute_blocksDependentDatabaseStepsWhenDashboardFails:157-172` |
| FR-6 (seven databases) | PASS (contract/orchestration); functional behavior deferred | `CreateWorkspaceService.execute:53-61` invokes all 7 database steps; `CreateWorkspaceServiceTest.execute_runsAllPhasesInOrderOnHappyPath:111-134` asserts order and count; each database step service stubbed identically (see §4) |
| FR-7 (relations) | PASS (contract/orchestration); functional behavior deferred | `CreateWorkspaceService.execute:64-66` (Phase C, blocked if any DB failed); `CreateRelationsService.execute` throws — `application/usecase/workspace/CreateRelationsService.java:22-24`; `CreateWorkspaceServiceTest.execute_blocksRelationsWhenAnyDatabaseStepFails:174-194` |
| FR-8 (rollups) | PASS (contract/orchestration); functional behavior deferred | `CreateWorkspaceService.execute:68-70` (Phase D); `CreateRollupsService.execute` throws — `.../CreateRollupsService.java:22-24`; `CreateWorkspaceServiceTest.execute_blocksRollupsWhenRelationsBlocked:196-206` |
| FR-9 (formulas) | PASS (contract/orchestration); functional behavior deferred | `CreateWorkspaceService.execute:72-74` (Phase E); `CreateFormulasService.execute` throws — `.../CreateFormulasService.java:22-24`; `CreateWorkspaceServiceTest.execute_blocksFormulasWhenRollupsBlocked:208-218` |
| FR-10 (idempotency, no duplicates, verify+reconcile) | PARTIAL PASS — contract/persistence layer fully idempotent and tested; live-Notion strict-reconciliation behavior (`verify`/`findChildByIdentity`/`repairShape`) is not functionally implementable/testable because `NotionProvisioningAdapter` is intentionally a stub in this pass (tech-spec §12) | `Workspace.record` upsert-by-type (never appends duplicates) — `domain/workspace/Workspace.java:41-49`, tested `WorkspaceTest.record_replacesExistingResourceOfSameType:54-65`; unique `(workspace_id,type)` and `(person_id,name)` constraints — `db/migration/V1__create_workspace_tables.sql:8,17`; `JpaWorkspaceRepositoryTest.save_upsertsResourceOfSameTypeOnReRecord:91-106`, `save_rejectsDuplicatePersonIdAndName:148-157`; `NotionProvisioningPort.verify/findChildByIdentity/repairShape` contract fixed — `application/port/NotionProvisioningPort.java:10,12,18` |
| FR-11 (resume after partial failure) | PASS (design-level; ADR-0001 mechanism verified) | No orchestrator transaction (`execute_isNotAnnotatedTransactional` — `CreateWorkspaceServiceTest.java:299-305`); the per-step ledger write is its own `@Transactional` unit — since the post-audit fix (L1) this is the dedicated `WorkspaceLedgerWriter.record` bean each step injects, not a self-invoked method; durable partial progress demonstrated at persistence level in `JpaWorkspaceRepositoryTest` |
| FR-12 (report distinguishing created/reconciled/failed) | PASS | `ProvisioningReport`/`ProvisioningStepResult`/`ProvisioningOutcome` — `application/dto/workspace/{ProvisioningReport,ProvisioningStepResult,ProvisioningOutcome}.java`; CLI rendering — `WorkspaceCommands.renderReport:34-46`, tested `WorkspaceCommandsTest.create_rendersAllStepsOnSuccess:61-72`; REST rendering — `WorkspaceController.toResponse:40-47`, tested `WorkspaceControllerTest.create_returns201WhenNewStructuresCreated:39-50` |
| FR-13 (sample data optional, gated on structural success, skip on failure) | PASS | `CreateWorkspaceService.execute:76-79` (Phase F gate); tests `execute_skipsSampleDataStepWhenFlagFalse:245-254`, `execute_runsSampleDataStepWhenFlagTrueAndAllStructuralPhasesSucceed:256-265`, `execute_blocksSampleDataStepWhenFlagTrueButAStructuralPhaseFailed:267-277` |
| FR-14 (stub causes explicit overall failure) | PASS | `runStep`/`runOrBlock` catch `Exception`, map to `FAILED` — `CreateWorkspaceService.java:88-101`; never swallowed, exception message preserved — test `execute_mapsThrownExceptionFromAStepToFailedResult:136-155`; end-to-end via `CreateWorkspaceIT.postWorkspace_persistsWorkspaceAndReturnsFailedReportWhileStepsAreStubbed:54-65` (real chain, `502` returned because every step still throws) |
| NFR-1 (idempotent steps) | PASS (contract-level; see FR-10 caveat) | Same evidence as FR-10 |
| NFR-2 (resilience to Notion failures / durable progress) | PASS (design-level) | ADR-0001 per-step transaction isolation; `runStep` never lets a Notion-adapter exception corrupt persisted state (catch-and-record pattern) |
| NFR-3 (domain framework-free) | PASS | `domain/workspace/{Workspace,ProvisionedResource,ProvisionedResourceType,WorkspaceRepository}.java` import only `java.*`/Lombok `@Value`/`@Builder` (no JPA/Spring); JPA mapping isolated in `infrastructure/adapter/persistence` |
| NFR-4 (no silent no-op) | PASS | Every stub throws `UnsupportedOperationException` explicitly (12 step services + `NotionProvisioningAdapter`'s 9 methods); no empty-bodied "success" stub remains anywhere in `application.usecase.*` |
| NFR-5 (observability — outcome logged/reported) | PASS (reporting + REST-path server-side logging; per-step service logging still deferred) | `ProvisioningStepResult.detail` carries failure/blocked reason and is surfaced end-to-end (CLI/REST). Post-audit (H1/M1), `ApiExceptionHandler` now logs the full failed report server-side via SLF4J. Per-step SLF4J logging inside the 12 step services remains deferred (steps are stubs; see §5 Coverage gaps) |
| NFR-6 (single-Person scoping, no cross-person leak, single process token) | PASS | `NotionProperties(String token)` single `@ConfigurationProperties` record — `infrastructure/adapter/notion/NotionProperties.java`; every step keyed to one `workspaceId`/`personId`; no cross-person query anywhere in the codebase |
| NFR-7 (no SLA; single-run completion) | N/A / not falsifiable pre-adapter | No code contradicts this |
| NFR-8 (N/A) | N/A | — |

## 4. Design conformance

All checked; no deviations found.

- **ADR-0001 (no orchestrator transaction)** — `CreateWorkspaceService` carries no `@Transactional` at class or method level; verified by a reflection-based test (`CreateWorkspaceServiceTest.execute_isNotAnnotatedTransactional:299-305`) and by direct source inspection (`application/usecase/workspace/CreateWorkspaceService.java:24-26,42-43`). Every step service's `recordLedger` helper is `@Transactional` on the concrete class only (e.g. `CreateDashboardService.java:27`).
- **ADR-0002 (strict idempotency/reconciliation)** — `NotionProvisioningPort` exposes `verify`, `findChildByIdentity`, `repairShape` exactly as specified (`application/port/NotionProvisioningPort.java:10-18`); `Workspace.record` is a strict upsert-by-type. Live-Notion verification logic itself cannot be exercised because the adapter is intentionally stubbed this pass — consistent with tech-spec §12, not a deviation.
- **ADR-0003 (ledger inside Workspace aggregate)** — `List<ProvisionedResource> resources` lives on `Workspace`, mutated only via `record(...)`/read via `resource(...)` — `domain/workspace/Workspace.java:19-49`. No separate `ProvisioningState` aggregate exists.
- **ADR-0004 (`NotionProvisioningPort` in `application.port`)** — confirmed at `application/port/NotionProvisioningPort.java:1` (package `com.lifeos.application.port`); implemented by `infrastructure/adapter/notion/NotionProvisioningAdapter.java:19` which depends inward on `application.port`, never the reverse.
- **ADR-0005 (explicit typed orchestrator, no generic `List<ProvisioningStep>`)** — `CreateWorkspaceService` has 12 explicitly typed constructor dependencies (`CreateWorkspaceService.java:28-40`); no `ProvisioningStep` interface or `List<X>` injection exists anywhere in `src/`.
- **ADR-0006 (collect/block/fail-overall)** — `runStep` catches `Exception`→`FAILED`; `runOrBlock` short-circuits to `BLOCKED` on unmet prerequisite; independent siblings still run (`execute_runsIndependentDatabaseStepsEvenWhenAnotherDatabaseStepFails:220-243`); overall failure surfaced via `ProvisioningReport.failed()` (`ProvisioningReport.java:12-15`).
- **ADR-0007 (Command/Report DTO contract)** — `CreateWorkspaceCommand(String,UUID,boolean)` with compact-constructor validation; `ProvisioningReport`/`ProvisioningStepResult`/`ProvisioningOutcome` all present with the exact validation rules specified (detail required on FAILED/BLOCKED — `ProvisioningStepResult.java:9-12`).
- **ADR-0008 (CLI + REST, RFC 9457 ProblemDetail)** — `WorkspaceCommands` (`infrastructure/adapter/cli`) and `WorkspaceController` (`infrastructure/adapter/web`) both call the same `CreateWorkspaceUseCase`; `ApiExceptionHandler` returns `ProblemDetail` for `MethodArgumentNotValidException`, `IllegalArgumentException`, and `WorkspaceProvisioningFailedException` (`infrastructure/adapter/web/ApiExceptionHandler.java`), verified end-to-end by `WorkspaceControllerTest.create_returns400ProblemDetailOnBlankName/MissingPersonId/502ProblemDetailWhenReportFailed`.
- **Idempotency key `(personId, name)` / many-workspaces-per-person** — `WorkspaceRepository.findByPersonIdAndName` (`domain/workspace/WorkspaceRepository.java:9`); unique DB constraint `uq_workspaces_person_id_name` on `(person_id, name)`, not `personId` alone (`db/migration/V1__create_workspace_tables.sql:8`); tested `JpaWorkspaceRepositoryTest.findByPersonIdAndName_returnsEmptyWhenNameDiffers:124-130`.
- **Hexagonal layering** — `domain.workspace` imports nothing beyond `java.*` and Lombok; `application.*` imports `domain.*` and its own DTOs/ports, never `infrastructure.*`; `infrastructure.*` adapters depend inward on `application`/`domain`. Confirmed by import inspection of every file listed above; no violation found.
- **Breaking changes required by tech-spec §6 all present**: `CreateProjectsDatabaseUseCase` converted to interface + new `CreateProjectsDatabaseService` implementing it, no combined no-op class remains (`application/usecase/project/{CreateProjectsDatabaseUseCase,CreateProjectsDatabaseService}.java`); all step `execute` signatures return `ProvisioningStepResult`, not `void`; `Workspace.create(String,UUID)` replaces the old 1-arg factory (no 1-arg overload exists); `CreateWorkspaceCommand` is the 3-arg record (no 2-arg constructor exists); orchestrator `@Transactional` removed.
- **No comment pollution** — `grep` for `//` and `/**` across every feature file in `application.usecase`, `application.dto`, `application.port`, `domain.workspace`, and the four `infrastructure.adapter.{cli,web,notion,persistence}` packages returned zero matches; code is self-documenting per the mechanical spec, no dead/explanatory comment clutter.

## 5. Coverage gaps

1. **NFR-5 (per-step outcome logging)** — the tech-spec (§9, "Logging: ... step logs (NFR-5) log `type`/`outcome`/`detail`/`workspaceId` only") implies each step should emit a log line. Post-audit remediation added server-side SLF4J logging of the full failed report in `ApiExceptionHandler` (H1/M1), so the REST failure path is now logged; however, per-step logging inside the 12 step services themselves is still absent. Because the steps are intentional stubs (tech-spec §12), meaningful per-step log lines belong to the later Notion-adapter implementation pass. The `ProvisioningReport` return value already satisfies the *reporting* half of NFR-5. Remaining item is a minor enhancement for the next pass, not a functional defect.
2. **FR-7/8/9/10's live-Notion behavior** — as noted in §3, the acceptance criteria's "relation is present," "rollup aggregates the correct field," "existing structures verified/reconciled" clauses cannot be exercised by any test in this pass because `NotionProvisioningAdapter` is an intentional stub (tech-spec §12). This is a known, explicitly-scoped gap, not an oversight — no action needed from the Implementer for this feature slice; flagged here only for traceability into the next implementation pass ("wire the real Notion adapter").

No other acceptance criterion lacks a corresponding test — every FR/NFR row in §3 above is backed by a named test class/method or (for the two design-level NFRs) directly-inspectable source evidence.

## 6. Violations

None found. (0 items.)

## 7. Post-audit re-verification (2026-08-04)

After this PASS, the Auditor's findings (`05-audit-report.md`) were remediated and the suite re-run. QA verdict is unchanged — **PASS** — and strengthened:

- Build: `TESTCONTAINERS_RYUK_DISABLED=true ./mvnw verify` → **BUILD SUCCESS**, `Tests run: 82, Failures: 0` (67 → 82; +15 regression tests).
- The remediation does not alter any acceptance-criteria verdict above; it hardens security (H1/M1 — no internal error detail leaves the REST boundary; 409/500 handlers added), aggregate integrity (M2 — private builders + `reconstitute`), transaction correctness (L1 — `WorkspaceLedgerWriter` bean replaces self-invoked `recordLedger`), domain modelling (L3 — `Email` VO), and persistence (L4 in-place reconcile, L5 `SEQUENCE`). L6 (REST authn) remains deferred by design.
- No comment pollution introduced. Line references in §3 predate the remediation and may be off by a few lines in the touched web/persistence/service files.

**Correction (2026-08-05):** at the time of this report `backend/pom.xml` had no `maven-failsafe-plugin`, so `CreateWorkspaceIT` was in fact **not** executed by `./mvnw verify` (surefire excludes `*IT`); the FR-4/FR-14 rows above cite it as evidence, but it only ran when invoked explicitly. This was discovered and fixed during the Create Dashboard branch: failsafe is now wired, and `CreateWorkspaceIT` (2 tests) runs automatically under `verify` alongside `CreateDashboardServiceIT`.
