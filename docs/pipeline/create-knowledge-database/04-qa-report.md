# 04 — QA Report: Create Knowledge Database

Status: Final
Owner (QA stage): pipeline automation
Input: `01-spec.md`, `02-architecture.md` (+ `adr/ADR-0010`), `03-tech-spec.md`, `00-preflight.md`, `backend/src/main/java/com/lifeos/application/usecase/knowledge/CreateKnowledgeDatabaseService.java` and its two test classes.

## 1. Verdict

**PASS.** All 14 FRs and 10 NFRs are satisfied with covering, correctly-asserting tests; the build is green on both tiers; the implementation touches only `CreateKnowledgeDatabaseService.java` in production code; no design deviations found.

## 2. Test run (independently executed, not trusted from report)

Command (from `backend/`, Podman env exported per `00-preflight.md`):

```bash
export DOCKER_HOST="unix://$(podman machine inspect --format '{{.ConnectionInfo.PodmanSocket.Path}}')"
export TESTCONTAINERS_RYUK_DISABLED=true
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
./mvnw verify
```

Result: `BUILD SUCCESS`.

- **Unit tier (Surefire), full suite**: `Tests run: 223, Failures: 0, Errors: 0, Skipped: 0`.
  - `CreateKnowledgeDatabaseServiceTest`: `Tests run: 17, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.022 s`.
- **Failsafe tier (`*IT`, Testcontainers), full suite**: `Tests run: 17, Failures: 0, Errors: 0, Skipped: 0`.
  - `CreateKnowledgeDatabaseServiceIT`: `Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.889 s` — confirmed it ran under `maven-failsafe-plugin` (`./mvnw verify`), not skipped; log shows Testcontainers Postgres container starting, Flyway migration, and the IT executing.

No failing tests anywhere in the full suite (both tiers).

## 3. Scope-honesty check

File-modification timestamps (`stat -f %Sm`) confirm production changes are confined to the one class the tech-spec authorizes:

| File | Last modified |
|---|---|
| `application/usecase/knowledge/CreateKnowledgeDatabaseService.java` | 2026-08-05 23:42:24 (latest of all inspected files) |
| `application/port/NotionProvisioningPort.java` | 2026-08-05 16:41:56 |
| `application/port/DatabaseSpec.java` / `ExpectedShape.java` / `PropertyDefinition.java` / `NotionPropertyType.java` | 2026-08-05 16:41:2x–16:41:32 |
| `infrastructure/adapter/notion/NotionProvisioningAdapter.java` | 2026-08-05 22:27:58 |
| `application/usecase/workspace/WorkspaceLedgerWriter.java` | 2026-08-04 18:35:36 |
| `domain/knowledge/Knowledge.java` | 2026-08-04 12:48:48 |
| `domain/knowledge/KnowledgeDiscoveryService.java` | 2026-08-04 11:48:12 |

All port/adapter/ledger-writer/domain files predate the Knowledge service edit by hours; only `CreateKnowledgeDatabaseService.java` is newer. Content confirms this structurally:
- `Knowledge.java` (read in full) still has exactly `id`, `title`, `content`, `workspaceId`, `areaId` — no new field, no relation reference.
- `WorkspaceLedgerWriter.record` (read in full) is unchanged: `@Transactional`, its own transaction, `findById` → `record(type, notionId)` → `save`.
- No page-body/block-append mechanism appears anywhere in `CreateKnowledgeDatabaseService.java` — `Content` is authored only as a `RICH_TEXT` `PropertyDefinition` (ADR-0010 honored).

**Confirmed: only `CreateKnowledgeDatabaseService` changed in production.** Adapter, ports, `WorkspaceLedgerWriter`, and `domain/knowledge/*` are untouched.

## 4. Acceptance criteria matrix

| Criterion | Verdict | Evidence |
|---|---|---|
| FR-1 (input contract) | PASS | `CreateKnowledgeDatabaseUseCase.execute(UUID)` unchanged signature; `CreateKnowledgeDatabaseService.java:38` — single `workspaceId` param, no other input |
| FR-2 (workspace not found) | PASS | `CreateKnowledgeDatabaseService.java:39-40` throws `IllegalStateException("Workspace not found: " + id)`; `CreateKnowledgeDatabaseServiceTest.java:61-72` (`execute_throwsWhenWorkspaceNotFound`) asserts message + `verifyNoInteractions(notion)`/`verifyNoInteractions(ledger)` |
| FR-3 (missing Dashboard precondition) | PASS | `CreateKnowledgeDatabaseService.java:42-44`; `CreateKnowledgeDatabaseServiceTest.java:74-86` (`execute_throwsWhenNoDashboardLedgerEntry`) |
| FR-4 (first-time creation) | PASS | Cold path, `CreateKnowledgeDatabaseService.java:83-90`; `CreateKnowledgeDatabaseServiceTest.java:88-106` (`execute_createsWhenColdAndNoOrphan`) — asserts exactly 2 properties `Title`(TITLE)/`Content`(RICH_TEXT), `ledger.record` called once, outcome `CREATED`; also IT `CreateKnowledgeDatabaseServiceIT.java:85-94` (`execute_persistsKnowledgeDbLedgerRowOnFirstRun`) |
| FR-5 (pure reconcile) | PASS | `CreateKnowledgeDatabaseService.java:63-64`; `CreateKnowledgeDatabaseServiceTest.java:149-162` (`execute_reconcilesWhenWarmAndMatching`) — asserts no write, no `ledger.record`, no `findChildByIdentity` |
| FR-6a (repair after out-of-band deletion) | PASS | `CreateKnowledgeDatabaseService.java:70-79`; `CreateKnowledgeDatabaseServiceTest.java:177-204` (`execute_reAdoptsWhenWarmAndDeletedAndOrphanFound`, `execute_reCreatesWhenWarmAndDeletedAndNoOrphanFound`) |
| FR-6b (repair after drift) | PASS | `CreateKnowledgeDatabaseService.java:65-69`; `CreateKnowledgeDatabaseServiceTest.java:164-175` (`execute_repairsWhenWarmAndDrifted`); IT `CreateKnowledgeDatabaseServiceIT.java:112-126` (`execute_reachesRepairedOutcomeWhenFakeSimulatesExternalPropertyRemoval`) |
| FR-7 (verify-before-trust) | PASS | `executeWarmPath` always calls `notion.verify(...)` before any `RECONCILED`; `execute_reconcilesWhenWarmAndMatching` asserts `verify` is the only call made |
| FR-8 (orphan adoption) | PASS | `CreateKnowledgeDatabaseService.java:84-97` (cold path `findChildByIdentity` then adopt); `CreateKnowledgeDatabaseServiceTest.java:108-121` (`execute_adoptsWhenColdAndOrphanMatches`) |
| FR-9 (fail loudly on ambiguous identity) | PASS | Adapter propagates `NotionApiException`, uncaught by service; `CreateKnowledgeDatabaseServiceTest.java:137-147` and `:206-217` (`execute_propagatesAmbiguousMatchFailureOnColdPath`, `...OnWarmAbsentPath`) — assert `isSameAs` + `verifyNoInteractions(ledger)` |
| FR-10 (ledger write path) | PASS | Every write path calls `ledger.record(workspaceId, KNOWLEDGE_DB, id)`; `WorkspaceLedgerWriter.java:18` `@Transactional`, own unit — confirmed unchanged/reused |
| FR-11 (result contract) | PASS | All paths return `ProvisioningStepResult(KNOWLEDGE_DB, outcome, detail)`; failure paths only ever propagate exceptions (never fabricate a `FAILED` result) — `CreateKnowledgeDatabaseServiceTest.java:137-147,206-242` |
| FR-12 (no partial ledger write) | PASS | `CreateKnowledgeDatabaseServiceTest.java:219-242` (`execute_propagatesNotionFailureFromVerifyWithoutWritingLedger`, `...FromCreateWithoutWritingLedger`) — both assert `verifyNoInteractions(ledger)` after a thrown `NotionApiException` |
| FR-13 (idempotent convergence) | PASS | IT `CreateKnowledgeDatabaseServiceIT.java:96-110` (`execute_convergesToOneRowAcrossThreeReruns`) — runs `execute` 3×, asserts exactly one `KNOWLEDGE_DB` row and same `notionId` after all three, 2nd/3rd outcomes `RECONCILED` |
| FR-14 (scope boundary) | PASS | `CreateKnowledgeDatabaseServiceTest.java:244-262` (`execute_neverInvokesRelationRollupFormulaOrSampleOrPageMethods`) — asserts `never()` on `ensureRelation`/`ensureRollup`/`ensureFormula`/`hasSampleRecords`/`insertSampleRecords`/`createRootPage`/`verifyPage`/`repairPage`/`findRootByIdentity`; `knowledgeSpec()` schema (test `knowledgeSpec_buildsTwoPropertiesTitleAndContent`, `:272-280`) has no relation-typed property |
| §3 schema exactness (Title+Content, no select/date) | PASS | `CreateKnowledgeDatabaseServiceTest.java:271-280` — asserts `spec.title()=="Knowledge"`, exactly 2 properties, `PropertyDefinition.of("Title", TITLE)` and `PropertyDefinition.of("Content", RICH_TEXT)` in order, `options()` null/empty on Content |
| NFR-1 (strict idempotency) | PASS | Live `verify`/`findChildByIdentity` on every branch before any `RECONCILED`/adoption — same tests as FR-7/FR-8/FR-13 |
| NFR-2 (resilience to external failure) | PASS | Notion-write-before-ledger-write ordering + no compensating rollback; FR-12 tests |
| NFR-3 (testability) | PASS | `CreateKnowledgeDatabaseServiceTest` is pure Mockito over `NotionProvisioningPort`/`WorkspaceRepository`/`WorkspaceLedgerWriter`, zero real Notion calls |
| NFR-4 (no silent no-op) | PASS | Stub `UnsupportedOperationException` fully removed; real algorithm implemented (`CreateKnowledgeDatabaseService.java`, whole file) |
| NFR-5 (observability) | PASS | `log.info` at `CreateKnowledgeDatabaseService.java:54-55,62,85` — workspaceId, dashboardId, prior ledger id, `VerificationResult`, outcome; no token/raw body logged |
| NFR-6 (no token leakage) | PASS | Service never touches the token (only `NotionClient` does, unchanged); `NotionApiException` messages built from status/code only — verified in reused, unmodified adapter/client |
| NFR-7 (failure isolation) | PASS | Service reads only `DASHBOARD`, writes only `KNOWLEDGE_DB`; no shared mutable state introduced |
| NFR-8 (ledger recording, exactly-once upsert) | PASS | `ledger.record` upsert semantics reused unchanged (`WorkspaceLedgerWriter`, `Workspace.record`); IT `execute_convergesToOneRowAcrossThreeReruns` empirically confirms exactly one row after 3 runs |
| NFR-9 (performance / bounded calls) | PASS | Algorithm structurally bounds calls to at most one verify/find + one create-or-repair + one ledger write per run (same reused shape as Tasks/Projects) |
| NFR-10 (rate-limit awareness) | PASS | Inherited unchanged from `NotionClient` (not touched by this step) |

## 5. Design conformance

- **Layering**: `CreateKnowledgeDatabaseService` in `application.usecase.knowledge`, depends only on ports (`NotionProvisioningPort`, `WorkspaceRepository`) and the `WorkspaceLedgerWriter` component — matches architecture §2 component diagram exactly. No infrastructure import in the service.
- **Constructor**: 3-arg (`NotionProvisioningPort`, `WorkspaceRepository`, `WorkspaceLedgerWriter`), matching tech-spec §3.2 and architecture §4.1 finding 1.
- **Transaction boundary**: `execute` carries no `@Transactional` (verified by reflection test `execute_isNotAnnotatedTransactional`, `CreateKnowledgeDatabaseServiceTest.java:264-269`); the sole transactional unit remains `WorkspaceLedgerWriter.record` (`WorkspaceLedgerWriter.java:18`) — matches tech-spec §3.4 exactly.
- **Outcome mapping**: `CREATED` only on first-time create with no prior ledger entry (cold path, no orphan); `RECONCILED` ⇔ no Notion write; `REPAIRED` ⇔ a Notion write happened this run — verified by outcome assertions across all 11 branch tests, matches the reused outcome table (tech-spec §3.3).
- **Schema authoring** (`knowledgeSpec()`/`knowledgeExpectedShape()`): package-private statics, exactly two `PropertyDefinition.of(...)` entries, no enum/select stream — matches tech-spec §3.2 delta notes verbatim (no `Arrays.stream`, no `java.util.Arrays` import).
- **No comment pollution**: `CreateKnowledgeDatabaseService.java` has zero inline `//` comments — consistent with the mirrored `CreateTasksDatabaseService` style.
- **Scope guard honored**: no adapter/port/domain modification (§3 above); no page-body/block-append mechanism introduced for `Content` (ADR-0010 respected — `RICH_TEXT` property only).
- No deviation from `02-architecture.md`/`03-tech-spec.md` found.

## 6. Coverage gaps

None. Every FR (1–14) and NFR (1–10) has at least one directly-asserting unit test, and the multi-run convergence (FR-13), missing-Dashboard (FR-3), workspace-not-found (FR-2), and `>1`→`FAILED` propagation (FR-9) scenarios are all covered at both the unit tier (Mockito) and, where applicable, the IT tier (Testcontainers + in-memory fake port).

## 7. Violations

None found. Zero violations.
