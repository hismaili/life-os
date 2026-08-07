# 02 — Open Questions: Create Habits Database

**None — all resolved.**

This is a pattern-application pass mirroring the shipped Create Tasks Database step. Every question a reader might raise is already settled:

- **Schema** — fully grounded in the complete `Habit`/`Frequency` aggregate (`domain/habit/Habit.java`, `domain/habit/Frequency.java`), verified against current source: exactly two properties, Name (`TITLE`) + Frequency (`SELECT`). No domain change.
- **Idempotency / identity / verify / repair** — fixed by ADR-0005..0008 (Create Projects Database), reused unchanged.
- **Frequency `select` option labels** — the only item the spec left as an `[ASSUMPTION]` (§7). It is **not** an open question: **ADR-0009** (Create Tasks Database) already governs it — seed labels verbatim from `Frequency.name()` (`DAILY`/`WEEKLY`/`MONTHLY`), no `displayName()`, no domain change. Immaterial to correctness because verify is name-only (ADR-0008).

No new ADR was required for this branch. Ready for the SME.
