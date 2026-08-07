# ADR-0016: Shell-level integration test seam via `spring-shell-test`, with the use-case port doubled

- **Status:** Accepted
- **Feature:** audit-remediation — FR-3, FR-4 / NFR-3 (closes cli-wiring AUD-001, AUD-002)
- **Owner:** spring-architect → SME / QA (`infrastructure.adapter.cli` tests)

## Context
The current CLI tests do not exercise the shell contract:
- `WorkspaceCommandsRegistrationTest#applicationComposesCliViaCommandScan` is a reflective
  `isAnnotationPresent(CommandScan.class)` tautology — it proves an annotation is present, not that
  the command parses, defaults, or exits.
- `WorkspaceCommandsTest#create_defaultsSampleDataToFalseWhenOmitted` calls
  `commands.create("Personal", personId, false)` — it passes `false` as a Java literal and therefore
  never exercises `@Option(defaultValue = "false")` resolution, despite its name.

NFR-3 requires that any test claiming to verify shell parsing / defaulting / required-option
enforcement / exit-code behavior actually drives it through the Spring Shell runtime. `spring-shell-test`
(`ShellTestClient`, `@ShellTest`) is already on the test classpath (`pom.xml`).

## Options considered
1. **`@ShellTest` booting the real `@CommandScan` composition, with `CreateWorkspaceUseCase`
   replaced by a test double; drive `workspace create` through `ShellTestClient`.**
   - + Exercises the true registration + parsing + default + exit-code path (FR-3), through the
     production `@CommandScan`, while the double at the application-port boundary keeps the test
     from reaching the real Notion API or DB. Deterministic, fast, no Testcontainers/network.
   - + Respects the hexagonal seam: the CLI adapter is tested against the `CreateWorkspaceUseCase`
     *port*, exactly the boundary it depends on.
   - − Slightly more setup than a POJO unit test (a context boots).
2. **Full `@SpringBootTest` with the real `CreateWorkspaceService` and everything wired.**
   - + Highest fidelity.
   - − Pulls in the Notion adapter + PostgreSQL/Testcontainers for a test whose subject is the
     *shell contract*, not provisioning. Slow, brittle, and conflates two concerns. Rejected for
     this purpose (the adapter/service already have their own tests).
3. **Keep POJO tests only, rename them honestly.**
   - − Fails FR-3: parsing/defaulting/exit-code are still never exercised through the shell.
     Rejected.

## Decision
Adopt **Option 1**. Add a `@ShellTest` integration test that loads the real command composition and
supplies a **test double for `CreateWorkspaceUseCase`** (via `@MockBean`/a `@TestConfiguration`
`@Bean`), then uses `ShellTestClient.sendCommand(...)` to assert, through the shell:
- **(FR-3a)** omitting `--name` or `--person-id` is rejected **before** the use case is invoked
  (assert the mock is never called / a parse-failure signal on the `ShellScreen`);
- **(FR-3b)** `workspace create --name "Personal" --person-id <uuid>` (no `--sample-data`) resolves
  `sampleData = false`, verified via the captured `CreateWorkspaceCommand` on the double — not a
  Java literal;
- **(FR-3c)** a `FAILED` `ProvisioningReport` from the double yields a non-zero/failure outcome
  consistent with Spring Shell's documented exit-code mapping (command throws
  `CommandFailedException` → non-zero exit).

**FR-4:** delete `applicationComposesCliViaCommandScan` and `create_defaultsSampleDataToFalseWhenOmitted`.
The honest `commandScan_discoversAndDependencyInjectsWorkspaceCommands` bean-registration test may
remain (it proves only what it asserts). The remaining POJO tests in `WorkspaceCommandsTest` that
verify pure rendering/exception-type (not shell parsing) are retained and renamed if their names
overclaim.

## Consequences
- Parsing, required-option enforcement, `@Option` defaulting, and exit-code behavior are proven
  through the real shell runtime (FR-3, NFR-3), against the production `@CommandScan`.
- The test double at the `CreateWorkspaceUseCase` port keeps the test hermetic (no Notion, no DB),
  so it runs under `mvn test` without the Testcontainers/podman env (preflight fast path).
- The tautological and mislabelled tests are gone; no test claims shell behavior it does not drive
  through the shell (NFR-3, FR-4).
- Overall CLI coverage strictly increases (FR-4 replacement clause): FR-3 covers the same and more
  ground than the removed tests.

## References
- Spring Shell Reference — *Testing* (`@ShellTest`, `ShellTestClient`, `ShellScreen`,
  `ShellAssertions`; commands go through the real parsing pipeline against the actual context).
- Spring Shell Reference — *Exception Handling* (command-thrown exception → Spring Boot exit code).
- Spring Boot Reference — Testing; test doubles at a collaborator boundary (`@MockBean`).
- *Effective Java* (Bloch), Item 1/50 context on testing at interface seams (design intent).
