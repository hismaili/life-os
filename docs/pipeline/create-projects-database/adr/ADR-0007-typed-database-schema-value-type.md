# ADR-0007: A typed database schema value type (property name → type) for `DatabaseSpec`/`ExpectedShape`

## Status
Accepted (Architect stage, Create Projects Database branch). Refines two `application.port` value types that today carry only property names. This is the Architect-level finding the Create Dashboard architecture explicitly anticipated ("If this step's needs cannot be met by the existing port shape, that is an Architect-level finding," Create Dashboard §5.2/§8 and spec §7/§8). **Addendum 2026-08-05 (after OQ-A):** `PropertyDefinition` also carries a `SELECT` property's option list, seeded from the `ProjectStatus` domain enum (see Decision, and ADR-0006).

## Context
The existing database-oriented value types carry property **names only**:
```java
public record DatabaseSpec(String title, List<String> propertyNames) {}
public record ExpectedShape(String title, List<String> requiredPropertyNames) {}
```
Two operations this step must perform **cannot** be expressed with names alone:
1. **Create** the schema — Notion requires a **type** per property (`title`/`rich_text`/`select`/`date`), under `initial_data_source.properties` ([Create a database](https://developers.notion.com/reference/create-a-database); ADR-0005/0006).
2. **Repair** by adding a missing property — to `PATCH /v1/data_sources/{id}` a new property, the adapter must know the property's **type**, not just its name ([Update a data source](https://developers.notion.com/reference/update-a-data-source)).

Additionally, after OQ-A the `Status` column is a `select` whose **options are seeded from the `ProjectStatus` domain enum** (ADR-0006); those option labels must also flow from the service to the adapter without leaking Notion JSON.

Both `DatabaseSpec` and `ExpectedShape` are consumed **only** by the currently-stubbed database port methods; the shipped Dashboard page slice uses `PageShape` and is unaffected. So this refinement is contained to the database slice.

## Options considered
1. **Keep names-only; hardcode a name→type (and options) table inside the adapter.**
   - (−) Splits the schema's source of truth between the application service (which owns the property set per spec §3 and the `ProjectStatus` enum) and the adapter (which would own the types/options) — they can silently diverge. Violates single-source-of-truth and the DDD "ubiquitous language" intent (`CLAUDE.md`).
   - (−) Every new database's type table would live in infrastructure, not with its use case. Rejected.
2. **Pass raw Notion JSON (`Map<String,Object>`) as the schema from the service.**
   - (−) Leaks Notion's wire format into the application layer, breaking the hexagonal boundary (Create Workspace ADR-0004: nothing Notion-shaped past the port). Rejected.
3. **Introduce a small domain-neutral typed schema value type in `application.port`** (chosen): an enum of the property types this pipeline uses plus a `(name, type, options)` definition, and refine both records to carry a list of them.
   - (+) One schema definition, authored in the service, feeds create and repair; the adapter maps the neutral enum/options to Notion JSON.
   - (+) No Notion types leak; the enum is the ubiquitous language for "kinds of column," and select options travel as plain strings.
   - (−) Refines two record signatures (contained; only stub callers exist today — see Consequences).

## Decision
Add to `application.port`:
```java
public enum NotionPropertyType { TITLE, RICH_TEXT, SELECT, DATE }   // minimal closed set (extend additively per future DB)

public record PropertyDefinition(String name, NotionPropertyType type, List<String> options) {
    public PropertyDefinition {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("property name must not be null or blank");
        if (type == null) throw new IllegalArgumentException("property type must not be null");
        options = options == null ? List.of() : List.copyOf(options);
        if (type != NotionPropertyType.SELECT && !options.isEmpty())
            throw new IllegalArgumentException("options are only valid for SELECT properties");
    }
    public static PropertyDefinition of(String name, NotionPropertyType type) { return new PropertyDefinition(name, type, List.of()); }
}
```
and refine:
```java
public record DatabaseSpec(String title, List<PropertyDefinition> properties) { /* non-blank title; non-empty; exactly one TITLE */ }
public record ExpectedShape(String title, List<PropertyDefinition> requiredProperties) { /* non-blank title; non-empty */ }
```
- `PropertyDefinition` carries an **`options`** list so a `SELECT` property's choices reach the adapter without leaking Notion JSON. It is empty for non-select types and validated as such; the convenience `of(name, type)` factory covers the non-select case. For the Projects `Status` column the list is **seeded from `ProjectStatus.values()`** — the domain enum is the single source of truth for the option set (ADR-0006; `02-architecture.md` §5.3/§5.6). This is an additive field on the same value type, not a reversal of this ADR.
- The **adapter** owns the `NotionPropertyType` → Notion-config-JSON mapping (ADR-0006 table): `TITLE→{title:{}}`, `RICH_TEXT→{rich_text:{}}`, `SELECT→{select:{options:[…]}}` (from `options`), `DATE→{date:{}}`.
- **Verification** uses only the property **names** from `ExpectedShape.requiredProperties` (ADR-0008); the types drive **creation** and **repair-add**, and `options` drive **creation** only (options are not verified/repaired, so user edits survive — ADR-0006/0008).
- The service authors the fixed Projects schema (`projectsSpec()`/`projectsExpectedShape()`), keeping title + property set + types + Status options in one place.

## Consequences
- Creating a schema (incl. enum-seeded select options) and repairing a missing property are both expressible without leaking Notion JSON or splitting the source of truth.
- `NotionPropertyType` starts minimal (`{TITLE, RICH_TEXT, SELECT, DATE}`); sibling databases add values (`NUMBER`, `CHECKBOX`, …) additively with no signature change (YAGNI).
- **Caller ripple (contained):** the only existing references to `DatabaseSpec`/`ExpectedShape`/`findChildByIdentity` are stub throws — in `NotionProvisioningAdapter` (being implemented now) and the in-memory fake port in `CreateDashboardServiceIT` (compile-only update, no behaviour change), plus `never()` assertions in `CreateDashboardServiceTest`. No production behaviour changes outside this branch. This is the SME finding §8.1/§8.5.
- `PageShape`/`ParentConstraint` and the page slice are untouched; `ExpectedShape` and `DatabaseSpec` continue to serve all seven databases, now typed.
</content>
