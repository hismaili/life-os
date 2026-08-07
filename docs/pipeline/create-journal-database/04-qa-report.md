# 04 — QA Report: Create Journal Database

Status: **PASS**
Owner (QA stage): pipeline automation
Verified against: `01-spec.md`, `02-architecture.md` (+ ADR-0012), `03-tech-spec.md`, `00-preflight.md`, code under `backend/src/`.

## 1. Verdict

**PASS** — 0 violations. All 15 FRs and all 10 NFRs are satisfied with real, correctly-asserting tests; the implementation matches the architecture/tech-spec exactly (only `JournalEntry.java` and `CreateJournalDatabaseService.java` changed in production); the build is green on both tiers, independently re-run.

## 2. Test run (independently executed, not trusted from the spec)

Command (verbatim from `00-preflight.md`, run from `backend/`):

```bash
export DOCKER_HOST="unix://$(podman machine inspect --format '{{.ConnectionInfo.PodmanSocket.Path}}')"
export TESTCONTAINERS_RYUK_DISABLED=true
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
./mvnw verify
```

- **Unit/slice tier:** `Tests run: 263, Failures: 0, Errors: 0, Skipped: 0`
- **Failsafe (`*IT`) tier:** `Tests run: 25, Failures: 0, Errors: 0, Skipped: 0`
- `BUILD SUCCESS`.
- `CreateJournalDatabaseServiceIT` **did run under failsafe** (confirmed in the log: `Running com.lifeos.application.usecase.journal.CreateJournalDatabaseServiceIT` … `Tests run: 4, Failures: 0, Errors: 0, Skipped: 0 -- in com.lifeos.application.usecase.journal.CreateJournalDatabaseServiceIT`), not skipped.
- Journal-specific classes in the unit tier, individually confirmed present and green:
  - `com.lifeos.domain.journal.JournalEntryTest` — `Tests run: 8, Failures: 0, Errors: 0, Skipped: 0`
  - `com.lifeos.application.usecase.journal.CreateJournalDatabaseServiceTest` — `Tests run: 17, Failures: 0, Errors: 0, Skipped: 0`

These exactly match the tech-spec's §9 "Verification" predicted counts (263 / 25, including 4/4 for the Journal IT). No failures found; nothing to report verbatim.

## 3. Scope-honesty check

Production files touched by this feature, confirmed by direct inspection of `backend/src/main/java/com/lifeos/{domain/journal,application/usecase/journal}`:

- `backend/src/main/java/com/lifeos/domain/journal/JournalEntry.java` — **modified** (domain change, §4).
- `backend/src/main/java/com/lifeos/application/usecase/journal/CreateJournalDatabaseService.java` — **modified** (stub → real implementation).
- `CreateJournalDatabaseUseCase.java` — present, unchanged (signature `ProvisioningStepResult execute(UUID)` intact).

Confirmed **NOT** touched:
- `infrastructure/adapter/notion/NotionProvisioningAdapter.java`, `NotionClient.java`, and all `infrastructure/adapter/notion/dto/*` — inspected; the `DATE` branch (`case DATE -> Map.of("type", "date", "date", Map.of())`, l.266) is generic, pre-dates this feature.
- `application/port/NotionProvisioningPort.java`, `DatabaseSpec.java`, `ExpectedShape.java`, `PropertyDefinition.java` — unchanged, generic, reused as-is.
- `application/usecase/workspace/WorkspaceLedgerWriter.java` — unchanged, reused as the sole `@Transactional` write path (`ledger.record(workspaceId, JOURNAL_DB, notionId)`), called from `CreateJournalDatabaseService`, own transaction confirmed by inspection (no `@Transactional` on `CreateJournalDatabaseService` or its `execute` method — asserted by `execute_isNotAnnotatedTransactional`).
- **`application/port/NotionPropertyType.DATE` was NOT added by this feature.** Confirmed by direct code inspection: `NotionPropertyType.java` line 3 declares `TITLE, RICH_TEXT, SELECT, DATE`, and `DATE` is already consumed by the pre-existing `CreateProjectsDatabaseService`/`CreateTasksDatabaseService` for their `"Due Date"` property (`PropertyDefinition.of("Due Date", NotionPropertyType.DATE)` — `CreateTasksDatabaseService.java:122`, `CreateProjectsDatabaseService.java:122`), and by `NotionProvisioningAdapter.java:266`'s `case DATE` branch, all pre-dating this feature. Journal is the first *caller* of `DATE` from a `LocalDateTime`-backed field, but not the first to *require* the enum constant or the adapter branch to exist — no enum/adapter change was needed or made.

No adapter, port, or `WorkspaceLedgerWriter` file changed. Scope guard **honored**.

## 4. Domain-change check (`JournalEntry`)

Verified directly against `backend/src/main/java/com/lifeos/domain/journal/JournalEntry.java`:

- `title` field is present, typed `String`, **nullable** (no non-null/non-blank check on it in `create(...)`), placed after `id` and before `content` — matches architecture §4.2b / tech-spec §2.2 exactly.
- `create(...)` gained a **leading** `title` parameter: `create(String title, String content, LocalDateTime timestamp, UUID workspaceId, UUID personId)` — matches spec.
- **Self-validating**: `content` non-blank and `workspaceId` non-null invariants are unchanged (still throw `IllegalArgumentException` with the original exact messages), `id` still minted via `UUID.randomUUID()` inside the factory — no invalid-state construction path exists in application code.
- `content` non-blank enforcement preserved: `create_rejectsBlankContent` covers both `""` and `null` content, both message-asserted.
- `timestamp` defaults to `LocalDateTime.now()` when `null`, preserved: `create_defaultsTimestampToNowWhenNull` asserts non-null + within 2s of `now()`; `create_keepsProvidedTimestamp` asserts the exact provided value is kept, no `now()` substitution.
- `@Value`/`@Builder` immutability preserved (`create_isImmutable` asserts no public setter methods); reference-by-UUID preserved (`workspaceId`, `personId` remain `UUID`, no object-graph reference introduced).
- Both the "constructs with title" (`create_keepsProvidedTitle`) and "constructs without title" (`create_allowsNullTitle`) paths are exercised in `JournalEntryTest.java`.

**Domain change fully covered, no violations.**

## 5. Acceptance criteria matrix

| Criterion | Verdict | Evidence |
|---|---|---|
| FR-1 (input contract) | PASS | `CreateJournalDatabaseUseCase.execute(UUID)` unchanged signature; exercised by every test in `CreateJournalDatabaseServiceTest`/`...IT` |
| FR-2 (workspace not found) | PASS | `CreateJournalDatabaseServiceTest#execute_throwsWhenWorkspaceNotFound` (l.61-72): asserts `IllegalStateException("Workspace not found: " + id)`, `verifyNoInteractions(notion)`, `verifyNoInteractions(ledger)` |
| FR-3 (missing Dashboard precondition) | PASS | `CreateJournalDatabaseServiceTest#execute_throwsWhenNoDashboardLedgerEntry` (l.74-86); IT `execute_throwsWhenPhaseAIncomplete` (l.128-137) confirms no `JOURNAL_DB` row is written |
| FR-4 (first-time creation) | PASS | `CreateJournalDatabaseServiceTest#execute_createsWhenColdAndNoOrphan` (l.88-106): asserts `DatabaseSpec.title()=="Journal"`, exactly 3 properties in order Title/Content/Date, `outcome=CREATED`; IT `execute_persistsJournalDbLedgerRowOnFirstRun` (l.85-94) |
| FR-5 (pure reconcile) | PASS | `execute_reconcilesWhenWarmAndMatching` (l.150-162): asserts zero writes, `verifyNoInteractions(ledger)`, `outcome=RECONCILED` |
| FR-6a (repair after deletion) | PASS | `execute_reAdoptsWhenWarmAndDeletedAndOrphanFound` (l.178-189), `execute_reCreatesWhenWarmAndDeletedAndNoOrphanFound` (l.191-204) |
| FR-6b (repair after drift) | PASS | `execute_repairsWhenWarmAndDrifted` (l.164-175); IT `execute_reachesRepairedOutcomeWhenFakeSimulatesExternalPropertyRemoval` (l.112-126) |
| FR-7 (verify-before-trust) | PASS | Every warm-path test calls `notion.verify(...)` before any outcome is returned; `execute_reconcilesWhenWarmAndMatching` proves `RECONCILED` is never returned without a live `verify` call |
| FR-8 (orphan adoption) | PASS | `execute_adoptsWhenColdAndOrphanMatches` (l.108-121), `execute_adoptsAndRepairsWhenColdAndOrphanDrifted` (l.123-135) |
| FR-9 (fail loudly on >1 match) | PASS | `execute_propagatesAmbiguousMatchFailureOnColdPath` (l.137-147), `execute_propagatesAmbiguousMatchFailureOnWarmAbsentPath` (l.206-217) — both assert exception propagation + `verifyNoInteractions(ledger)` |
| FR-10 (ledger write path) | PASS | Every write-path test asserts `verify(ledger).record(id, JOURNAL_DB, <id>)` called exactly once, via the reused `WorkspaceLedgerWriter` (own `@Transactional`, unchanged) |
| FR-11 (result contract) | PASS | All 9 outcome-table tests assert `ProvisioningStepResult(JOURNAL_DB, <outcome>, ...)`; failure-path tests assert exceptions propagate rather than a fabricated result |
| FR-12 (no partial ledger write on failure) | PASS | `execute_propagatesNotionFailureFromVerifyWithoutWritingLedger` (l.219-229), `execute_propagatesNotionFailureFromCreateWithoutWritingLedger` (l.231-242) — both assert `verifyNoInteractions(ledger)` |
| FR-13 (idempotent convergence) | PASS | IT `execute_convergesToOneRowAcrossThreeReruns` (l.96-110): 3 consecutive runs, exactly one `JOURNAL_DB` row, same `notionId` throughout, `RECONCILED` on 2nd/3rd |
| FR-14 (scope boundary) | PASS | `execute_neverInvokesRelationRollupFormulaOrSampleOrPageMethods` (l.244-262): asserts `never()` on `ensureRelation`/`ensureRollup`/`ensureFormula`/`hasSampleRecords`/`insertSampleRecords`/`createRootPage`/`verifyPage`/`repairPage`/`findRootByIdentity`; schema assertions (`journalSpec_buildsThreePropertiesWithNoSelect`) confirm no relation/select property |
| FR-15 (no row/record data written) | PASS | Schema-only algorithm — `execute` never constructs or serializes a `JournalEntry`; IT's fake port never stores per-row data, only title+property-name sets; grep confirms no `JournalEntry.create(` call site in production code outside `JournalEntry.java` itself |
| NFR-1 (strict idempotency) | PASS | Live `verify`/`findChildByIdentity` on every path before any `RECONCILED`/adoption decision (§ FR-7/FR-13 evidence above) |
| NFR-2 (resilience to external failures) | PASS | FR-12 tests; Notion-write-before-ledger-write ordering in `executeColdPath`/`executeWarmPath` |
| NFR-3 (testability) | PASS | `CreateJournalDatabaseServiceTest` is pure Mockito, zero real Notion calls; `JournalEntryTest` is plain JUnit5+AssertJ, no Spring context |
| NFR-4 (no silent no-op) | PASS | Stub `UnsupportedOperationException` fully removed; `execute` now performs the real sequence unconditionally |
| NFR-5 (observability) | PASS | `log.info` calls in `execute`, `executeWarmPath`, `executeColdPath` (lines 54-55, 62, 85) — workspaceId, dashboardId, prior ledger id, `VerificationResult`, outcome — matches spec's field list |
| NFR-6 (no token leakage) | PASS | `CreateJournalDatabaseService` never references the token; `NotionApiException` messages built only from status/code/count/title (`journalSpec()`'s `"Journal"` is not a secret); no test or code path places a token in `detail` |
| NFR-7 (failure isolation) | PASS | Service reads only `DASHBOARD`, writes only `JOURNAL_DB`; no shared mutable state; confirmed by code inspection (`workspace.resource(DASHBOARD)`, `workspace.resource(JOURNAL_DB)` — no other resource type touched) |
| NFR-8 (ledger recording) | PASS | Every write path calls `ledger.record(...)` exactly once (Mockito `verify(ledger).record(...)`, not `atLeastOnce`); IT confirms exactly one `JOURNAL_DB` row after CREATED and after repeated RECONCILED runs |
| NFR-9 (performance / bounded calls) | PASS | Algorithm structurally bounds Notion calls to at most: 1 verify/find + 1 create-or-repair; no loops; confirmed by code read of `executeWarmPath`/`executeColdPath` |
| NFR-10 (rate-limit awareness) | PASS | Inherited unchanged from reused `NotionClient` (no code change in this feature's scope; not re-tested here per tech-spec §6.4) |

## 6. Design conformance

No deviations found between the shipped code and `02-architecture.md`/`03-tech-spec.md`:

- `CreateJournalDatabaseService` constructor is 3-arg (`NotionProvisioningPort`, `WorkspaceRepository`, `WorkspaceLedgerWriter`) exactly as architecture §4.1/tech-spec §4.2 specify (`CreateJournalDatabaseService.java:33-35`).
- `journalSpec()`/`journalExpectedShape()` are package-private statics building exactly 3 properties (`Title`/TITLE, `Content`/RICH_TEXT, `Date`/DATE), no `select`/relation — matches tech-spec §3/§4.2 verbatim (`CreateJournalDatabaseService.java:112-121`).
- `execute` carries no `@Transactional`; the sole transactional write is `WorkspaceLedgerWriter.record` (unchanged) — matches architecture §3 "Error strategy & transaction boundary" and tech-spec §4.5 exactly; verified structurally by `execute_isNotAnnotatedTransactional`.
- Outcome mapping (`CREATED` only on first-time cold-path create with no prior ledger record; `REPAIRED` ⇔ a Notion write happened this run; `RECONCILED` ⇔ none; `>1` match ⇒ propagated `NotionApiException` ⇒ `FAILED` at the orchestrator) matches the reused 9-row outcome table verbatim — confirmed row-by-row against the 9 outcome-table unit tests.
- `JournalEntry`'s domain delta matches tech-spec §2.2's "full intended source" line-for-line (field order, leading `title` param, unchanged invariants, unchanged builder chain order).
- No new `@Service`/`@Component`/`@Repository` bean introduced beyond `CreateJournalDatabaseService` itself, as required.

No design-conformance violations found.

## 7. Coverage gaps

**None.** Every FR (1–15) and every NFR (1–10) has at least one directly-asserting test, cross-checked against `CreateJournalDatabaseServiceTest` (17 tests), `CreateJournalDatabaseServiceIT` (4 tests), and `JournalEntryTest` (8 tests) — 29 tests total for this feature, matching the tech-spec's §6 predicted counts exactly (8 + 17 + 4).

## 8. Violations

**None.** 0 violations raised.
