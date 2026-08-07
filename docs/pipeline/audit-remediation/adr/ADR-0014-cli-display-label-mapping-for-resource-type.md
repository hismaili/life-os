# ADR-0014: CLI-layer display-label mapping for `ProvisionedResourceType`

- **Status:** Accepted (implements resolved stakeholder decision #3 — AUD-005 now in scope)
- **Feature:** audit-remediation — AUD-005 (cli-wiring, Low)
- **Owner:** spring-architect → SME (`infrastructure.adapter.cli`)

## Context
`WorkspaceCommands#renderReport` (line 37) renders `step.type()` — the raw
`domain.workspace.ProvisionedResourceType` enum constant name — directly into operator-facing
console text, so the operator sees `TASKS_DB`, `SAMPLE_DATA`, etc. The stakeholder has resolved that
the CLI should show human-readable labels (`"Tasks"`, not `TASKS_DB`), and that the mapping must
live in the **CLI adapter** so the **domain enum stays framework/presentation-free** (CLAUDE.md:
"model closed sets of values as domain enums… domain logic compares enums"; the domain must have no
knowledge of presentation).

## Options considered
1. **A presentation-only mapper in the CLI adapter (`ResourceTypeLabel`) — an exhaustive `switch`
   expression from enum constant → display label.**
   - + Keeps the domain enum untouched (Clean Architecture: presentation depends on domain, never
     the reverse). An exhaustive `switch` expression over the enum gives a **compile-time**
     guarantee that a newly added constant is given a label (no silent fallback). Trivially
     unit-testable in the adapter.
   - − One more small class in the CLI package (acceptable; SRP-aligned).
2. **Add a `displayName` field/method to the `ProvisionedResourceType` enum.**
   - + Single source; `type.displayName()` everywhere.
   - − **Rejected:** pushes presentation concerns into the domain, violating the project's
     domain-purity rule and the hexagonal dependency direction. A domain enum should not encode how
     a CLI (one of several possible adapters/views) chooses to render it.
3. **Externalize labels to a `messages.properties` / `ResourceBundle`.**
   - + Standard i18n path.
   - − Overkill for a single-locale CLI remediation; adds indirection and a runtime lookup with no
     current requirement for localization. YAGNI. Can be adopted later behind the same `ResourceTypeLabel`
     seam if i18n is ever needed.

## Decision
Adopt **Option 1**: introduce `infrastructure.adapter.cli.ResourceTypeLabel` with a static
`of(ProvisionedResourceType) : String` using an **exhaustive** `switch` expression covering all 14
constants (`DASHBOARD`, `PROJECTS_DB`, `TASKS_DB`, `KNOWLEDGE_DB`, `HABITS_DB`, `JOURNAL_DB`,
`RESOURCES_DB`, `PEOPLE_DB`, `GOALS_DB`, `REVIEWS_DB`, `RELATIONS`, `ROLLUPS`, `FORMULAS`,
`SAMPLE_DATA`). `renderReport` calls `ResourceTypeLabel.of(step.type())` instead of `step.type()`.
No default branch — a future enum constant forces a compile error until a label is chosen.

## Consequences
- Operator output shows human-readable labels; the domain enum is unchanged and remains
  presentation-free (satisfies the stakeholder's layering constraint).
- Adding a `ProvisionedResourceType` constant will not compile until `ResourceTypeLabel` maps it —
  a deliberate, maintainer-friendly tripwire.
- Co-located with the FR-8 `renderReport` change, avoiding a second unrelated edit to the same
  method (no scope creep into other files).
- A small CLI unit test asserts representative labels (`TASKS_DB → "Tasks"`) and that the domain
  enum has no presentation members.

## References
- Clean Architecture — dependency rule: presentation/adapters depend on the domain; the domain does
  not depend on presentation. Project convention: `CLAUDE.md` (domain enums carry rules, not
  rendering).
- Oracle Java SE 21 — exhaustive `switch` expressions over an enum (compile-time completeness).
- *Effective Java* (Bloch), Item 34: Use enums instead of int constants (the domain enum stays the
  canonical closed set; labelling is a separate, outer concern).
