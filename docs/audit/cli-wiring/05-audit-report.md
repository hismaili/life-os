# 05 — Audit Report: CLI Wiring (`workspace create`)

Scope: the recently-changed CLI-wiring surface only —
`LifeOsApplication` (`@CommandScan`), `WorkspaceCommands`, `CommandFailedException`,
`WorkspaceCommandsRegistrationTest`, `WorkspaceCommandsTest`. Supporting files
(`CreateWorkspaceService`, `ProvisioningReport`, `CreateWorkspaceCommand`) were read for
context only; findings rooted outside the target set are marked as such.

This is a standalone audit — findings only, no code changed, nothing staged/committed.

## 1. Summary

| Severity | Count |
|----------|-------|
| Critical | 0 |
| High     | 0 |
| Medium   | 2 |
| Low      | 3 |

**Blocking (Critical + High): 0.** The wiring is fundamentally sound — correct hexagonal
layering, constructor injection, typed options, explicit failure signalling. The findings
below are test-discipline and minor design/robustness improvements; none block merge.

## 2. Findings

### AUD-001 — Medium — Quality / Test discipline
**`WorkspaceCommandsTest.java:48-59`** (`create_defaultsSampleDataToFalseWhenOmitted`).
The test name claims it verifies the `--sample-data` default, but the body calls
`commands.create("Personal", personId, false)` — it passes `false` **explicitly** through a
direct Java method call, so the `@Option(defaultValue = "false")` binding
(`WorkspaceCommands.java:23`) is never exercised. Option defaulting is resolved by Spring
Shell's argument binder, not by the Java default value of a `boolean` parameter; a direct
constructor+method invocation bypasses it entirely. The test therefore asserts a behavior it
does not exercise (false confidence) and is effectively a duplicate of AUD-002's parsing gap.
- **Citation:** Spring Shell Reference 3.3.2 — Option handling / default values
  (docs.spring.io/spring-shell/reference/3.3.2, `commands/registration/annotation.html`): option
  defaults are applied by the shell's option resolution, only observable through the shell.
- **Fix:** exercise the default via a `spring-shell-test` `ShellTestClient` invocation of
  `workspace create --name X --person-id <uuid>` (no `--sample-data`) and assert the captured
  command's `sampleData()` is `false`.

### AUD-002 — Medium — Quality / Test discipline
**`WorkspaceCommandsRegistrationTest.java:22-48`** and **`WorkspaceCommandsTest.java:37,50,63,76`.**
Neither the registration nor the behavioral tests load the real composition root through
Spring Shell, despite `spring-shell-test:3.3.2` being on the classpath (`pom.xml:68-73`):
- The registration test builds a **parallel** `@CommandScan(basePackageClasses = WorkspaceCommands.class)`
  config (line 23) rather than booting the real `LifeOsApplication` context, so it proves a
  *fresh* scan works, not that the production `@CommandScan` on `LifeOsApplication` reaches
  `com.lifeos.infrastructure.adapter.cli`. The second assertion
  (`applicationComposesCliViaCommandScan`, line 44) only reflectively checks the annotation is
  *present* — a tautology that passes even if the scan were misconfigured.
- The behavioral tests call `new WorkspaceCommands(...)` + `create(...)` directly (lines
  37/50/63/76), bypassing option parsing, required-option enforcement
  (`@Option(required = true)`), and exit-code mapping. Names such as
  `create_invokesUseCaseWithParsedArguments` (line 36) claim "parsed arguments" though no
  parsing occurs.
- **Citation:** Spring Shell Reference 3.3.2 — Testing (`ShellTestClient` /
  `@ShellTest`), and Exit Code Mappings (`commands/exceptionhandling/mappings.html`: an uncaught
  exception maps to exit code 1) — the failure→non-zero-exit contract is asserted in design but
  covered by no test.
- **Fix:** add one `spring-shell-test` integration test that boots the real application context
  and runs `workspace create` end-to-end (required-option rejection, default binding, and a
  non-zero exit on a `FAILED` report).

### AUD-003 — Low — Design (exception semantics)
**`WorkspaceCommands.java:27-31`.** The same `rendering` string is the normal return value on
success (line 31) and the `CommandFailedException` **message** on failure (line 29). Using the
full multi-line report as an exception detail message conflates presentation output with
exception semantics; the failure path also produces no return value, so the report survives to
the user only as long as no layer reformats the exception message.
- **Citation:** *Effective Java*, Item 75: "Include failure-capture information in detail
  messages" — the detail message should describe the failure, not double as the command's
  normal report body.
- **Fix:** give `CommandFailedException` a concise failure message (e.g. the failed step
  types) and write the full report to the shell's output on both success and failure paths.

### AUD-004 — Low — Security / Sensitive information exposure
**`WorkspaceCommands.java:40-41`** (surfacing) with root cause at
**`CreateWorkspaceService.java:92`** (outside target set). `renderReport` appends each step's
`detail` verbatim; for `FAILED` steps that `detail` is a caught `Exception.getMessage()` from
downstream Notion/persistence calls, so internal error text is echoed to the console (and into
the exception message per AUD-003). For a local operator CLI the exposure surface is small,
hence Low, but the pattern leaks internals with no sanitisation boundary.
- **Citation:** OWASP ASVS v4 V7 (Error Handling & Logging) and OWASP Cheat Sheet Series —
  Error Handling: log full internal detail, surface a sanitised message.
- **Fix:** in `runStep`, log the raw exception and store a sanitised `detail`; render that.

### AUD-005 — Low — Design / Clean Architecture (coupling)
**`WorkspaceCommands.java:37`.** `renderReport` appends `step.type()` — the domain enum
`ProvisionedResourceType` — directly, emitting raw constant names (`DASHBOARD`, `TASKS_DB`) as
user-facing CLI text. This couples the CLI presentation to domain enum constant identifiers, so
a domain rename silently changes CLI output. (The leak originates one layer up: the application
DTO `ProvisioningStepResult` exposes the domain enum — outside the target set — but it is the
CLI that renders it to the user.)
- **Citation:** *Effective Java*, Item 34 (enums) — do not rely on `Enum.name()`/ordinal for
  externally-visible representations; and hexagonal dependency-direction guidance
  (CLAUDE.md "Architecture: Hexagonal + DDD").
- **Fix:** map `ProvisionedResourceType` to a stable display label in the adapter (or a
  presentation VO) rather than emitting the constant name.

### Categories with no material findings (stated explicitly)
- **Injection / parameterized queries:** none — no SQL/query strings in these files; the
  aggregate reference (`person-id`) is a typed `UUID` option (`WorkspaceCommands.java:22`), not
  a string.
- **AuthN / AuthZ, CORS/CSRF:** N/A — local Spring Shell CLI, no HTTP surface in the target set.
- **Secret management:** none — no hardcoded credentials/tokens in the target files.
- **Mass assignment:** none — the command DTO is a validated record
  (`CreateWorkspaceCommand.java:6-13`), not a mutable/bindable entity.
- **Persistence (N+1, `@Transactional`, entity-as-DTO, lost update):** N/A to the CLI-wiring
  files; the transactional boundary lives in `CreateWorkspaceService` (out of scope).
- **Comment pollution / AI-generated comments:** none — no noise comments; the Javadoc on
  `WorkspaceCommandsRegistrationTest.java:14-19` is a legitimate rationale doc, not pollution.

## 3. Principle scorecard

| Principle | Rating | Justification |
|-----------|--------|---------------|
| **SOLID** | Good | Single responsibility per class; depends on `CreateWorkspaceUseCase` interface (DIP), not a concrete service (`WorkspaceCommands.java:17`). |
| **Clean Architecture** | Good (minor leak) | Driving adapter → application use case + DTOs, correct inward direction; one Low coupling to a domain enum in rendering (AUD-005). |
| **DDD** | Good | Aggregates referenced by `UUID`; validation lives in the domain/application records; the CLI stays a thin adapter. |
| **Security** | Good | No injection/authN/secret issues in scope; one Low info-exposure via verbatim error detail (AUD-004). |
| **DRY / YAGNI** | Adequate | Production code is lean; test duplication of setup and a redundant/misleading default test (AUD-001) are the only smells. |
| **Test discipline** | Needs work | Tests bypass the shell runtime they claim to guard (AUD-001, AUD-002); `spring-shell-test` is available but unused. |

## 4. Blocking issues

**None.** No Critical or High findings. AUD-001 and AUD-002 (Medium) are strongly recommended
before the CLI is relied upon in CI, because the current suite does not actually exercise the
Spring Shell registration, option binding, or exit-code contract that the design depends on —
but they do not block merge of the wiring itself.
