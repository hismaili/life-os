# 02 — Open Questions: Create Tasks Database

Status: **None — all resolved.** No blocking architectural question.

The Create Tasks Database step is a pattern-application pass over the already-shipped Create Projects Database design:

- **Schema is fully grounded.** Every §3 property (`Title`, `Description`, `Status`, `Due Date`) maps to an existing, complete field on the `Task` aggregate (`domain/task/Task.java`). Unlike Projects (OQ-A, which required a domain change), **no `domain/task/` change is needed** (spec §7).
- **Idempotency / identity / verify / repair** are fixed by the reused, shipped adapter DB slice and ADR-0005..0008 — nothing to re-decide.
- **Property-type mapping** (Status = `select`, options seeded from the domain enum, verify name-only) follows ADR-0006 unchanged.
- **The one label question** — `TaskStatus` has no `displayName()` (unlike `ProjectStatus`) — is a settled decision, **not** an open question: options are seeded verbatim from `TaskStatus.name()` (no domain change, immaterial to correctness because verify is name-only). Recorded in **ADR-0009**. A humanized `TaskStatus.displayName()` is noted as a non-blocking, out-of-scope follow-up in that ADR.

No stakeholder decision is required to proceed to the SME.
