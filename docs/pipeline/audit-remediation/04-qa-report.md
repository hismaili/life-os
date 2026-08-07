# 04 — QA Report: Audit Remediation (Notion Adapter + CLI Wiring)

## 1. Verdict

**PASS.** All FR-1..FR-8, the AUD-004 sanitization boundary, and the AUD-005 CLI label-mapping
are implemented per `02-architecture.md`/`03-tech-spec.md`, are genuinely exercised by tests (not
just present), and both the fast (`mvn test`) and full (`mvn verify`) suites are green with no
regressions. Zero violations found.

## 2. Test run

Commands run verbatim from `00-preflight.md`.

**Fast path:**
```
(cd backend && ./mvnw test)
```
Result: `Tests run: 332, Failures: 0, Errors: 0, Skipped: 0` — `BUILD SUCCESS`.

**Full verify (Testcontainers via podman):**
```
DOCKER_HOST="unix:///var/folders/7j/1y40850161s7gh17s6s9k5rh0000gn/T/podman/podman-machine-default-api.sock" \
TESTCONTAINERS_RYUK_DISABLED=true \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
(cd backend && ./mvnw verify)
```
Result: unit-test module `Tests run: 332` (as above) plus failsafe/IT module `Tests run: 33,
Failures: 0, Errors: 0, Skipped: 0` (includes `CreateWorkspaceIT`, `JpaWorkspaceRepositoryTest`,
etc.) — `BUILD SUCCESS`. No failing tests in either run. NFR-5 satisfied.

## 3. Acceptance criteria matrix

| Req | Verdict | Evidence |
|---|---|---|
| FR-1 (token redaction) | PASS | `NotionProperties.java:17-22` overrides `toString()`, fully redacts `token` to `"****"`, still renders `version`/`rootParentPageId`. `NotionPropertiesTest.java:16-29` (`toString_redactsTokenToFixedMarker`, `toString_stillRendersVersionAndRootParentPageId`) assert `.doesNotContain("secret-token")` / `.contains("****")`. `client_neverLeaksTokenInExceptionMessage` (`NotionProvisioningAdapterTest.java:322-335`) and `client_neverLeaksTokenInDatabaseSliceExceptionMessage` (`NotionProvisioningAdapterDatabaseTest.java:547-559`) independently assert no token leak via exception messages, not just `toString()`. Binding-error path: rationale in architecture §6.1 (only invalid token state is blank, never the secret) is sound and not contradicted by any test. |
| FR-2 (data-source guards) | PASS | `NotionProvisioningAdapter.java:50-63` `requirePrimaryDataSourceId`/`requireDataSource` guard both `verify` (line 177-179) and `repairShape` (line 232-234). Tests: `verify_throwsNotionApiExceptionWhenDataSourcesEmpty`/`…Absent`/`…LookupReturns404` and `repairShape_throwsNotionApiExceptionWhenDataSourcesEmpty`/`…LookupReturns404` (`NotionProvisioningAdapterDatabaseTest.java:161-229`) — each asserts `NotionApiException` with the expected message fragment (`"no data source"` / `"data source unavailable"`), not merely absence of NPE. |
| FR-3 (real shell test) | PASS | `WorkspaceCommandsShellTest.java` boots the real `@CommandScan`-discovered composition (`ShellTestConfig`) through the actual `spring-shell-autoconfigure` classes and drives `workspace create` via the real `Shell` + `NonInteractiveShellRunner` (the same runner `ShellTestClient.nonInterative` uses internally) — not a hand-constructed `WorkspaceCommands`. 3a: `create_rejectsMissingRequiredNameOption`/`…PersonIdOption` (lines 110-143) assert `CommandExecution.CommandParserExceptionsException` and `verifyNoInteractions(createWorkspace)`. 3b: `create_resolvesSampleDataDefaultToFalseWhenOptionOmitted` (145-163) captures the actual invoked `CreateWorkspaceCommand` via `ArgumentCaptor` after a shell-parsed invocation omitting `--sample-data` and asserts `sampleData()==false` — resolved by Spring Shell's own `@Option(defaultValue=...)`, not a Java literal. 3c: `create_signalsFailureExitWhenReportFailed` (165-184) asserts the shell run throws `CommandFailedException` for a FAILED report. |
| FR-4 (remove tautology/mislabelled test) | PASS | `WorkspaceCommandsRegistrationTest.java`: `applicationComposesCliViaCommandScan` is gone; the retained `commandScan_discoversAndDependencyInjectsWorkspaceCommands` proves only bean registration/DI (honest claim). `WorkspaceCommandsTest.java`: `create_defaultsSampleDataToFalseWhenOmitted` (which called `commands.create("Personal", personId, false)` with a Java literal) is gone; FR-3's shell test is now the source of truth for defaulting. `mvn test` green with these deletions in place (§2). |
| FR-5 (bounded pagination + null-safe results) | PASS | `NotionProvisioningAdapter.java:65-67` `nullSafe`, `MAX_SEARCH_PAGES=50` cap checked before each `/search`/`/blocks/.../children` request in `findRootByIdentity` (117-150) and `findChildByIdentity` (190-218). Tests: `findRootByIdentity_treatsNullResultsAsEmptyPage`/`…ThrowsWhenSearchExceedsPageCap` (`NotionProvisioningAdapterTest.java:209-230`, registers exactly 50 `/search` expectations, `server.verify()` confirms no 51st call) and the parallel `findChildByIdentity_*` tests (`NotionProvisioningAdapterDatabaseTest.java:232-255`). |
| FR-6 (rename shadowing local) | PASS | `NotionProvisioningAdapter.java:155` — local renamed to `propertyConfigs`; field `properties` (line 42) no longer shadowed. Existing `createDatabase`/`repairShape` tests pass unmodified (regression-free, `NotionProvisioningAdapterDatabaseTest.java` full suite green). |
| FR-7 (named HTTP status constants) | PASS | `NotionClient.java:23` `NOTION_OVERLOADED=529` (commented), lines 53 and 86 use `HttpStatus.NOT_FOUND.value()` / `HttpStatus.TOO_MANY_REQUESTS.value()`. `NotionClientTest` untouched per `03-tech-spec.md` §9.4 and green in the full run. |
| FR-8 (concise `CommandFailedException`) | PASS | `WorkspaceCommands.java:32-37`: on `report.failed()`, full rendering is written to `terminal.writer()` before throwing `CommandFailedException(conciseFailureSummary(report))` (54-63) — a short "N of M … failed: labels" string, not the multi-line report. `create_signalsFailureWhenReportFailed` (`WorkspaceCommandsTest.java:79-89`) asserts the exact concise message; `create_writesFullReportToTerminalOnFailureBeforeThrowing` (91-103) independently asserts the full report reached the terminal buffer even though the method threw. |
| AUD-004 (application-layer sanitization) | PASS | `SafeToSurfaceException.java` (application-owned marker) implemented by `NotionApiException` (infrastructure) — correct hexagonal direction, zero new `application → infrastructure.adapter.notion` type dependency (grep confirms `CreateWorkspaceService.java` imports no adapter types). `CreateWorkspaceService.java:92-106` `runStep`/`safeDetail`: curated exceptions pass `getMessage()` through, everything else collapses to a generic string. Tests: `execute_surfacesNotionApiExceptionMessageVerbatimOnStepFailure`, `execute_collapsesUnexpectedExceptionToGenericSafeDetail` (asserts `.doesNotContain("jdbc")`/`"internal-host"`), `execute_logsRawCauseAtErrorForFailedStep` (Logback `ListAppender` asserts the raw `IllegalStateException` + message is logged server-side at ERROR) — `CreateWorkspaceServiceTest.java:305-374`. |
| AUD-005 (CLI display-label mapping) | PASS | `ResourceTypeLabel.java` — package-private, presentation-only, exhaustive `switch` (no `default`, so a 15th enum constant is a compile error until labelled) over `ProvisionedResourceType`. `ProvisionedResourceType.java` (domain) unchanged — no presentation code added (see §4). `create_rendersHumanReadableLabelsNotRawEnumConstants` (`WorkspaceCommandsTest.java:67-77`) asserts rendering contains `"Tasks"` and `.doesNotContain("TASKS_DB")`. |
| NFR-1 (secret handling) | PASS | As FR-1; no `infrastructure/adapter/notion` component renders the raw token (checked `NotionClient`, `NotionProvisioningAdapter`, `NotionApiException`, `NotionProperties` — token only ever used to build the `Authorization` header, `NotionClient.java:34`). |
| NFR-2 (robustness on untrusted data) | PASS | As FR-2/FR-5; all null/empty-legal Notion fields consumed by the adapter are guarded before dereference; failures are `NotionApiException`, never NPE/IOOBE (verified by the negative tests listed above). |
| NFR-3 (test discipline) | PASS | As FR-3/FR-4; the shell test drives real Spring Shell parsing/defaulting/exit, and no remaining test in the suite claims shell-parsed behavior while invoking `WorkspaceCommands` directly (`create_invokesUseCaseWithParsedArguments` and friends in `WorkspaceCommandsTest.java` are honest Mockito unit tests of the command's own logic, not shell-parsing claims). |
| NFR-4 (no happy-path behavior change) | PASS | `create_rendersAllStepsOnSuccess` (updated for labels but structurally unchanged), success path still returns a single rendering via the method's return value (no double-print) — `WorkspaceCommands.java:37`. `createDatabase`/`repairShape` happy-path tests pass unmodified. |
| NFR-5 (no regression) | PASS | §2 — full and fast suites green, 332 + 33 tests, 0 failures/errors. |

## 4. Design conformance

- **Layering (CLAUDE.md / architecture §2, §4.1):** verified no violations.
  - `SafeToSurfaceException` lives in `application.usecase.workspace` (inner layer owns the
    contract); `NotionApiException` (`infrastructure.adapter.notion`) implements it — outer
    implements inner, matching the existing `NotionProvisioningAdapter implements
    NotionProvisioningPort` pattern. `grep` of `CreateWorkspaceService.java` shows no import of
    any `infrastructure.adapter.notion` type.
  - `ProvisionedResourceType.java` (domain) is unchanged and contains zero presentation logic —
    confirmed by direct read; `ResourceTypeLabel` (the only enum→string mapping) lives in
    `infrastructure.adapter.cli`, package-private, referenced only by `WorkspaceCommands` in the
    same package.
  - `WorkspaceCommands` remains a thin CLI adapter: it renders and dispatches, delegates all
    provisioning logic to `CreateWorkspaceUseCase`; no business logic added.
- **Transaction boundaries:** `CreateWorkspaceService.execute` unchanged in structure; only a
  logger field and a private `safeDetail` classifier were added — no new I/O inside the existing
  transactional shape, matching architecture §4.3.
- **SME-delegated decision (tech-spec §0):** the marker-interface approach was adopted exactly as
  directed; no deviation.
- **FR-3 test-technology deviation:** the tech spec specified `@ShellTest`; the Implementer
  documented (tech-spec §12) that `spring-shell-test:3.3.2` does not ship a `@ShellTest`
  annotation. Independently verified by inspecting the jar
  (`~/.m2/repository/org/springframework/shell/spring-shell-test/3.3.2/spring-shell-test-3.3.2.jar`)
  — it contains only `ShellTestClient`/`ShellScreen`/`ShellAssertions`/etc., no annotation class.
  The substituted `ApplicationContextRunner` + `AutoConfigurations.of(...)` + real `Shell` +
  `NonInteractiveShellRunner` composition is a faithful equivalent: it drives the identical
  production `@CommandScan` composition and the identical runner class
  `ShellTestClient.nonInterative(...)` delegates to internally, and genuinely exercises
  `@Option(required=...)`/`@Option(defaultValue=...)` binding through Spring Shell's real command
  dispatch pipeline. This is a reasonable, disclosed substitution, not a silent scope-narrowing —
  no violation raised.

## 5. Coverage gaps

None found. Every FR/AUD/NFR in the traceability table (`03-tech-spec.md` §10) has a
corresponding test that was independently read and confirmed to exercise the described behavior
(not merely present/passing by name).

## 6. Comment-pollution / quality gate

`grep` across the changed production files
(`infrastructure/adapter/notion/**`, `infrastructure/adapter/cli/**`,
`application/usecase/workspace/**`) for AI-authorship markers ("AI-generated", "as an AI",
"Generated by", stray "I have"/"I've added" narration, leftover `TODO`/`FIXME` placeholders)
found none. Comments present (e.g. `NotionApiException` marker-interface javadoc,
`NOTION_OVERLOADED` inline comment, `MAX_SEARCH_PAGES` ADR-reference comment,
`WorkspaceCommandsShellTest`'s class-level javadoc explaining the `@ShellTest` deviation) are all
substantive and load-bearing, not filler.

## 7. Violations

None. No findings raised.
