# ADR-0015: Concise `CommandFailedException` message, decoupled from the rendered report

- **Status:** Accepted
- **Feature:** audit-remediation — FR-8 (closes cli-wiring AUD-003, Low)
- **Owner:** spring-architect → SME (`infrastructure.adapter.cli`)

## Context
`WorkspaceCommands#create` (line 27–30) renders the full multi-line `ProvisioningReport` and passes
that entire body as the `CommandFailedException` detail message. Two problems: (a) an exception's
`getMessage()` is meant to be a concise cause, not a screenful of report — a caller that logs only
`getMessage()` gets the whole report body; (b) the report and the failure signal are conflated.
FR-8 requires a **concise** exception message *and* that the full report still reaches the operator
on **both** success and failure paths.

## Options considered
1. **Write the full rendered report to the shell output on both paths; throw
   `CommandFailedException` with a concise summary (which step(s) failed) on the failure path.**
   - + Separates *human-readable report* (output stream) from *failure signal + concise cause*
     (exception → exit code). `getMessage()` becomes a one-liner; the report is still shown.
   - − On the failure path the command must write output *before* throwing (the thrown method
     cannot also return the string).
2. **Keep passing the full report as the message, but add a `getReport()`/structured field on the
   exception.**
   - − Still violates FR-8 (a plain `getMessage()` logger still gets the full body); more complex
     exception type for no gain.
3. **Suppress the report entirely on failure; only throw the concise message.**
   - − Violates FR-8's "full report must still be made available to the operator on the failure
     path." Rejected.

## Decision
Adopt **Option 1**. `create` renders the report once (labels via ADR-0014), writes the full
rendering to the shell's output writer on **both** paths, and on `report.failed()` throws
`CommandFailedException` with a **concise** summary — e.g. `"N of M provisioning steps failed:
Tasks, Journal"` — never the multi-line body. The thrown exception drives Spring Shell's non-zero
exit via its Spring Boot exit-code integration.

**Output-then-throw seam:** obtain the output writer from the `@Command` method's `CommandContext` /
`Terminal`, or an injected `PrintWriter`; write the report, then throw. The SME selects the exact
Spring Shell mechanism; the architectural invariant is *write full report → throw concise
exception*. `CommandFailedException` retains its single `String`-message constructor.

## Consequences
- `getMessage()` is a concise, log-friendly summary (FR-8 acceptance: a caller logging only
  `getMessage()` no longer gets the full report body).
- The operator still sees the complete step-by-step report on success and failure (FR-8 + the
  report-availability clause).
- A non-zero exit is preserved on failure (the exception still propagates to Spring Shell). This
  pairs with FR-3's shell-level exit assertion (ADR-0016).
- Existing `create_signalsFailureWhenReportFailed` still passes (it asserts the exception type, not
  the message body); a new assertion checks the message is concise and the report reached output.

## References
- Spring Shell Reference — *Exception Handling* (command-thrown exceptions integrate with Spring
  Boot exit codes) and *Testing* (`ShellTestClient` asserts written output / thrown exceptions).
- *Effective Java* (Bloch), Item 75: Include failure-capture information in detail messages — a
  *concise* cause, not an entire rendered document.
