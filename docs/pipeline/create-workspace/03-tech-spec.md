# 03 — Technical Specification: Create Workspace

Status: Ready for Implementer
Owner (SME stage): pipeline automation
Input: `docs/pipeline/create-workspace/02-architecture.md` + `adr/ADR-0001..0008` + `01-spec.md` + `02-open-questions.md` (all resolved) + existing code under `backend/src/main/java/com/lifeos/`.
Grounding skills applied: `spring-boot-conventions`, `spring-data-jpa`, `spring-security`, `spring-testing`.

> This spec is mechanical: signatures, annotations, field lists, HTTP contracts, and an ordered TDD task list. It does not introduce any design decision not already made in `02-architecture.md`/the ADRs. Any place where this spec appears to deviate is called out explicitly in §11 as a note back to the Architect — there is exactly one, non-blocking.

---

## 1. Package layout

```
com.lifeos
 ├─ domain
 │   └─ workspace/
 │        Workspace.java                       [EXTEND]
 │        ProvisionedResource.java              [NEW]
 │        ProvisionedResourceType.java          [NEW]
 │        WorkspaceRepository.java              [EXTEND]
 │
 ├─ application
 │   ├─ dto.workspace/
 │   │    CreateWorkspaceCommand.java           [EXTEND]
 │   │    ProvisioningOutcome.java               [NEW]
 │   │    ProvisioningStepResult.java            [NEW]
 │   │    ProvisioningReport.java                [NEW]
 │   │
 │   ├─ port/
 │   │    NotionProvisioningPort.java            [NEW]
 │   │    VerificationResult.java                [NEW] (enum)
 │   │    ExpectedShape.java                     [NEW] (record)
 │   │    DatabaseSpec.java                      [NEW] (record)
 │   │    RelationSpec.java                      [NEW] (record)
 │   │    RollupSpec.java                        [NEW] (record)
 │   │    FormulaSpec.java                       [NEW] (record)
 │   │    RecordSpec.java                        [NEW] (record)
 │   │
 │   └─ usecase
 │        ├─ workspace/
 │        │    CreateWorkspaceUseCase.java        [EXTEND]
 │        │    CreateWorkspaceService.java        [EXTEND]
 │        │    CreateDashboardUseCase.java        [EXTEND]
 │        │    CreateDashboardService.java        [EXTEND]
 │        │    CreateRelationsUseCase.java        [NEW]
 │        │    CreateRelationsService.java        [NEW]
 │        │    CreateRollupsUseCase.java          [NEW]
 │        │    CreateRollupsService.java          [NEW]
 │        │    CreateFormulasUseCase.java         [NEW]
 │        │    CreateFormulasService.java         [NEW]
 │        │    PopulateSampleDataUseCase.java     [NEW]
 │        │    PopulateSampleDataService.java     [NEW]
 │        ├─ project/
 │        │    CreateProjectsDatabaseUseCase.java [EXTEND — refactor to interface]
 │        │    CreateProjectsDatabaseService.java [NEW — was the broken no-op class]
 │        ├─ task/       CreateTasksDatabaseUseCase.java [EXTEND sig], CreateTasksDatabaseService.java [EXTEND sig]
 │        ├─ knowledge/  CreateKnowledgeDatabaseUseCase.java [EXTEND sig], CreateKnowledgeDatabaseService.java [EXTEND sig]
 │        ├─ habit/      CreateHabitsDatabaseUseCase.java [EXTEND sig], CreateHabitsDatabaseService.java [NEW]
 │        ├─ journal/    CreateJournalDatabaseUseCase.java [EXTEND sig], CreateJournalDatabaseService.java [NEW]
 │        ├─ resource/   CreateResourcesDatabaseUseCase.java [EXTEND sig], CreateResourcesDatabaseService.java [NEW]
 │        └─ person/     CreatePeopleDatabaseUseCase.java [EXTEND sig], CreatePeopleDatabaseService.java [NEW]
 │            (goal/, review/ packages: NOT created in v0 — deferred, OQ-1)
 │
 └─ infrastructure
     ├─ adapter.cli/
     │    WorkspaceCommands.java                 [NEW]
     ├─ adapter.web/
     │    WorkspaceController.java                [NEW]
     │    CreateWorkspaceRequest.java              [NEW] (record, in adapter.web or adapter.web.dto)
     │    ProvisioningReportResponse.java          [NEW] (record)
     │    ProvisioningStepResultResponse.java      [NEW] (record)
     │    ApiExceptionHandler.java                 [NEW]
     ├─ adapter.notion/
     │    NotionProvisioningAdapter.java           [NEW]
     │    NotionProperties.java                    [NEW] (@ConfigurationProperties)
     └─ adapter.persistence/
          JpaWorkspaceRepository.java              [NEW]
          WorkspaceJpaEntity.java                  [NEW]
          ProvisionedResourceJpaEntity.java         [NEW]
          SpringDataWorkspaceRepository.java        [NEW] (Spring Data interface, package-private collaborator)
```

Package-by-feature is respected: `workspace` collects everything about the aggregate/orchestrator; each database keeps its own feature package (`project`, `task`, …), consistent with the existing layout (`spring-boot-conventions`).

---

## 2. Domain (`com.lifeos.domain.workspace`) — framework-free (NFR-3)

### 2.1 `ProvisionedResourceType` [NEW] — enum

```java
public enum ProvisionedResourceType {
    DASHBOARD,
    PROJECTS_DB, TASKS_DB, KNOWLEDGE_DB, HABITS_DB, JOURNAL_DB, RESOURCES_DB, PEOPLE_DB,
    GOALS_DB, REVIEWS_DB,   // reserved, NOT provisioned in v0 (OQ-1) — no use case references these yet
    RELATIONS, ROLLUPS, FORMULAS,
    SAMPLE_DATA
}
```

### 2.2 `ProvisionedResource` [NEW] — value object (record)

```java
public record ProvisionedResource(
    ProvisionedResourceType type,
    String notionId,
    Instant provisionedAt
) {
    public ProvisionedResource {
        if (type == null) throw new IllegalArgumentException("type must not be null");
        if (notionId == null || notionId.isBlank()) throw new IllegalArgumentException("notionId must not be null or blank");
        if (provisionedAt == null) throw new IllegalArgumentException("provisionedAt must not be null");
    }
}
```

### 2.3 `Workspace` [EXTEND] — aggregate root

Current state: `@Value @Builder` with `id`, `name`; `create(String name)` has no `personId` and no ledger. Extend, do not replace:

```java
@Value
@Builder
public class Workspace {
    UUID id;
    UUID personId;
    String name;
    @Builder.Default
    List<ProvisionedResource> resources = List.of();

    public static Workspace create(String name, UUID personId) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Workspace name must not be null or blank");
        }
        if (personId == null) {
            throw new IllegalArgumentException("Workspace personId must not be null");
        }
        return Workspace.builder()
                .id(UUID.randomUUID())
                .personId(personId)
                .name(name)
                .resources(List.of())
                .build();
    }

    public Optional<ProvisionedResource> resource(ProvisionedResourceType type) {
        return resources.stream().filter(r -> r.type() == type).findFirst();
    }

    /** Copy-on-write: returns a NEW Workspace with the resource of this type upserted (replaced if present, appended if not). */
    public Workspace record(ProvisionedResourceType type, String notionId) {
        if (type == null) throw new IllegalArgumentException("type must not be null");
        if (notionId == null || notionId.isBlank()) throw new IllegalArgumentException("notionId must not be null or blank");
        List<ProvisionedResource> next = resources.stream()
                .filter(r -> r.type() != type)
                .collect(Collectors.toCollection(ArrayList::new));
        next.add(new ProvisionedResource(type, notionId, Instant.now()));
        return this.toBuilder().resources(List.copyOf(next)).build();
    }
}
```

Notes for the Implementer:
- `record(...)` must **replace** any existing entry of the same `type` (upsert), not append a duplicate — this is what makes `REPAIRED` outcomes work (a resource id can change after repair).
- `@Builder` needs `toBuilder = true` added (`@Builder(toBuilder = true)`) to support the copy-on-write pattern.
- `Instant.now()` inside the domain method is acceptable here (no clock injection required by the architecture); do not add a `Clock` abstraction — out of scope, avoid gold-plating.
- `Workspace.create(String name)` (old one-arg signature) is **removed** — every call site must supply `personId`. Existing tests referencing the old signature must be updated (flagged in ADR-0007 consequences).

### 2.4 `WorkspaceRepository` [EXTEND] — domain port

```java
public interface WorkspaceRepository {
    Workspace save(Workspace workspace);
    Optional<Workspace> findById(UUID id);
    Optional<Workspace> findByPersonIdAndName(UUID personId, String name);
}
```

---

## 3. Application DTOs (`com.lifeos.application.dto.workspace`)

### 3.1 `CreateWorkspaceCommand` [EXTEND]

```java
public record CreateWorkspaceCommand(String name, UUID personId, boolean sampleData) {
    public CreateWorkspaceCommand {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Workspace name must not be null or blank");
        }
        if (personId == null) {
            throw new IllegalArgumentException("Workspace personId must not be null");
        }
    }
}
```

The 2-arg constructor is removed; all call sites (existing tests, future callers) pass 3 args.

### 3.2 `ProvisioningOutcome` [NEW] — enum

```java
public enum ProvisioningOutcome { CREATED, RECONCILED, REPAIRED, FAILED, BLOCKED }
```

### 3.3 `ProvisioningStepResult` [NEW] — record

```java
public record ProvisioningStepResult(ProvisionedResourceType type, ProvisioningOutcome outcome, String detail) {
    public ProvisioningStepResult {
        if (type == null) throw new IllegalArgumentException("type must not be null");
        if (outcome == null) throw new IllegalArgumentException("outcome must not be null");
        // detail may be null/blank for a plain CREATED/RECONCILED result; required (non-blank) when outcome is FAILED or BLOCKED
        if ((outcome == ProvisioningOutcome.FAILED || outcome == ProvisioningOutcome.BLOCKED)
                && (detail == null || detail.isBlank())) {
            throw new IllegalArgumentException("detail is required when outcome is FAILED or BLOCKED");
        }
    }
}
```

### 3.4 `ProvisioningReport` [NEW] — record

```java
public record ProvisioningReport(UUID workspaceId, List<ProvisioningStepResult> steps) {
    public ProvisioningReport {
        if (workspaceId == null) throw new IllegalArgumentException("workspaceId must not be null");
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    public boolean failed() {
        return steps.stream().anyMatch(s -> s.outcome() == ProvisioningOutcome.FAILED
                                          || s.outcome() == ProvisioningOutcome.BLOCKED);
    }
}
```

---

## 4. Application — driven port (`com.lifeos.application.port`) [NEW]

Per ADR-0004: this port lives in `application.port` (not `infrastructure.port`) so the application core does not depend outward on infrastructure.

```java
public interface NotionProvisioningPort {

    VerificationResult verify(String rootPageId, ProvisionedResourceType type, ExpectedShape expected);

    Optional<String> findChildByIdentity(String rootPageId, ProvisionedResourceType type);

    String createRootPage(String workspaceName);

    String createDatabase(String rootPageId, DatabaseSpec spec);

    void repairShape(String notionId, ExpectedShape expected);

    void ensureRelation(RelationSpec spec);

    void ensureRollup(RollupSpec spec);

    void ensureFormula(FormulaSpec spec);

    boolean hasSampleRecords(String databaseId);

    void insertSampleRecords(String databaseId, List<RecordSpec> records);
}
```

Value types (application-layer, transport-agnostic — no Notion SDK types leak past the adapter):

```java
public enum VerificationResult { PRESENT_MATCHING, PRESENT_DRIFTED, ABSENT }

public record ExpectedShape(String title, List<String> requiredPropertyNames) {}

public record DatabaseSpec(String title, List<String> propertyNames) {}

public record RelationSpec(String name, ProvisionedResourceType fromDatabase, ProvisionedResourceType toDatabase, boolean bidirectional) {}

public record RollupSpec(String name, ProvisionedResourceType onDatabase, RelationSpec viaRelation, String rollupProperty) {}

public record FormulaSpec(String name, ProvisionedResourceType onDatabase, String expression) {}

public record RecordSpec(ProvisionedResourceType targetDatabase, Map<String, Object> properties) {}
```

These carry *intent* only. The Implementer does not need to finalize their internal fields precisely for v0 because every adapter method that consumes them (`NotionProvisioningAdapter`) throws `UnsupportedOperationException` for now (§6) — but the shapes above are fixed enough to compile against and are not to be redesigned without going back to the Architect.

---

## 5. Application — use cases

### 5.1 Step contract (applies to every provisioning-step interface)

All step `execute` methods change signature from `void execute(UUID workspaceId)` to:

```java
ProvisioningStepResult execute(UUID workspaceId);
```

This is a breaking, coordinated change across every existing stub (ADR-0005/0007, architecture §8 finding 2).

### 5.2 Orchestrator

```java
// [EXTEND]
public interface CreateWorkspaceUseCase {
    ProvisioningReport execute(CreateWorkspaceCommand command);
}
```

```java
// [EXTEND] CreateWorkspaceService — NOT @Transactional (ADR-0001; remove the existing annotation)
@Service
@RequiredArgsConstructor
public class CreateWorkspaceService implements CreateWorkspaceUseCase {

    private final WorkspaceRepository workspaceRepository;
    private final CreateDashboardUseCase createDashboard;
    private final CreateProjectsDatabaseUseCase createProjectsDatabase;
    private final CreateTasksDatabaseUseCase createTasksDatabase;
    private final CreateKnowledgeDatabaseUseCase createKnowledgeDatabase;
    private final CreateHabitsDatabaseUseCase createHabitsDatabase;
    private final CreateJournalDatabaseUseCase createJournalDatabase;
    private final CreateResourcesDatabaseUseCase createResourcesDatabase;
    private final CreatePeopleDatabaseUseCase createPeopleDatabase;
    private final CreateRelationsUseCase createRelations;
    private final CreateRollupsUseCase createRollups;
    private final CreateFormulasUseCase createFormulas;
    private final PopulateSampleDataUseCase populateSampleData;

    @Override
    public ProvisioningReport execute(CreateWorkspaceCommand command) {
        Workspace workspace = workspaceRepository.findByPersonIdAndName(command.personId(), command.name())
                .orElseGet(() -> workspaceRepository.save(Workspace.create(command.name(), command.personId())));

        List<ProvisioningStepResult> results = new ArrayList<>();

        // Phase A
        ProvisioningStepResult dashboard = runStep(() -> createDashboard.execute(workspace.getId()), ProvisionedResourceType.DASHBOARD);
        results.add(dashboard);
        boolean phaseAOk = isOk(dashboard);

        // Phase B — 7 databases, each independent of the others but blocked if Dashboard failed
        List<ProvisioningStepResult> dbResults = new ArrayList<>();
        dbResults.add(runOrBlock(phaseAOk, () -> createProjectsDatabase.execute(workspace.getId()), ProvisionedResourceType.PROJECTS_DB));
        dbResults.add(runOrBlock(phaseAOk, () -> createTasksDatabase.execute(workspace.getId()), ProvisionedResourceType.TASKS_DB));
        dbResults.add(runOrBlock(phaseAOk, () -> createKnowledgeDatabase.execute(workspace.getId()), ProvisionedResourceType.KNOWLEDGE_DB));
        dbResults.add(runOrBlock(phaseAOk, () -> createHabitsDatabase.execute(workspace.getId()), ProvisionedResourceType.HABITS_DB));
        dbResults.add(runOrBlock(phaseAOk, () -> createJournalDatabase.execute(workspace.getId()), ProvisionedResourceType.JOURNAL_DB));
        dbResults.add(runOrBlock(phaseAOk, () -> createResourcesDatabase.execute(workspace.getId()), ProvisionedResourceType.RESOURCES_DB));
        dbResults.add(runOrBlock(phaseAOk, () -> createPeopleDatabase.execute(workspace.getId()), ProvisionedResourceType.PEOPLE_DB));
        results.addAll(dbResults);
        boolean phaseBOk = dbResults.stream().allMatch(CreateWorkspaceService::isOk);

        // Phase C
        ProvisioningStepResult relations = runOrBlock(phaseAOk && phaseBOk, () -> createRelations.execute(workspace.getId()), ProvisionedResourceType.RELATIONS);
        results.add(relations);
        boolean phaseCOk = isOk(relations);

        // Phase D
        ProvisioningStepResult rollups = runOrBlock(phaseCOk, () -> createRollups.execute(workspace.getId()), ProvisionedResourceType.ROLLUPS);
        results.add(rollups);
        boolean phaseDOk = isOk(rollups);

        // Phase E
        ProvisioningStepResult formulas = runOrBlock(phaseDOk, () -> createFormulas.execute(workspace.getId()), ProvisionedResourceType.FORMULAS);
        results.add(formulas);
        boolean phaseEOk = isOk(formulas);

        // Phase F — only attempted if requested AND phases A-E fully succeeded (FR-13)
        boolean allStructuralOk = phaseAOk && phaseBOk && phaseCOk && phaseDOk && phaseEOk;
        if (command.sampleData()) {
            results.add(runOrBlock(allStructuralOk, () -> populateSampleData.execute(workspace.getId()), ProvisionedResourceType.SAMPLE_DATA));
        }

        return new ProvisioningReport(workspace.getId(), results);
    }

    private static boolean isOk(ProvisioningStepResult r) {
        return r.outcome() != ProvisioningOutcome.FAILED && r.outcome() != ProvisioningOutcome.BLOCKED;
    }

    private ProvisioningStepResult runStep(Supplier<ProvisioningStepResult> step, ProvisionedResourceType type) {
        try {
            return step.get();
        } catch (Exception e) {
            return new ProvisioningStepResult(type, ProvisioningOutcome.FAILED, e.getMessage());
        }
    }

    private ProvisioningStepResult runOrBlock(boolean prerequisiteOk, Supplier<ProvisioningStepResult> step, ProvisionedResourceType type) {
        if (!prerequisiteOk) {
            return new ProvisioningStepResult(type, ProvisioningOutcome.BLOCKED, "prerequisite step failed or was blocked");
        }
        return runStep(step, type);
    }
}
```

Notes:
- `runStep`/`runOrBlock` catch `Exception` (not just `RuntimeException`) so an `UnsupportedOperationException` from a stub, or any adapter exception, always becomes a `FAILED` result — never propagates uncaught and never silently vanishes (ADR-0006, CLAUDE.md "no silent no-op").
- No `@Transactional` anywhere on this class (ADR-0001) — **this is the required removal of the existing `@Transactional` on `CreateWorkspaceService.execute`.**
- The orchestrator calls `workspace.getId()`, not the immutable `Workspace` object passed between steps — each step independently reloads/persists via `WorkspaceRepository` inside its own transaction (§5.3), so the orchestrator never mutates or re-saves the aggregate itself after the initial load-or-create.
- Workspace load-or-create is the **only** persistence write the orchestrator itself performs, and it is not wrapped in `@Transactional` either — `WorkspaceRepository.save` on a freshly created aggregate is a single, already-atomic JPA operation; no explicit boundary is needed at the orchestrator.

### 5.3 Provisioning step services — pattern for all 12

Every step interface: `ProvisioningStepResult execute(UUID workspaceId);` (dashboard, 7 databases, relations, rollups, formulas, sample data).

Every step **service** (concrete class, `@Service @RequiredArgsConstructor`) follows this shape:

```java
@Service
@RequiredArgsConstructor
public class Create<X>DatabaseService implements Create<X>DatabaseUseCase {

    private final NotionProvisioningPort notion;
    private final WorkspaceRepository workspaceRepository;

    @Override
    public ProvisioningStepResult execute(UUID workspaceId) {
        throw new UnsupportedOperationException(
                "<X> database creation not yet implemented: requires the Notion adapter");
    }

    @Transactional
    void recordLedger(UUID workspaceId, ProvisionedResourceType type, String notionId) {
        Workspace workspace = workspaceRepository.findById(workspaceId)
                .orElseThrow(() -> new IllegalStateException("Workspace not found: " + workspaceId));
        workspaceRepository.save(workspace.record(type, notionId));
    }
}
```

**Until the Notion adapter exists, every step's `execute` throws `UnsupportedOperationException` — this is intentional and matches CLAUDE.md's "no silent no-op."** The `recordLedger` helper shows the intended *future* shape (own short `@Transactional` unit per ADR-0001) once the adapter lands; it is dead code reachable only after `execute` stops throwing, but must still be present so the transaction boundary is unambiguous for whoever implements the adapter next. Do not wire `recordLedger` into `execute` while `execute` still throws unconditionally — keep the throw as the very first statement so behavior is unambiguous and testable.

`@Transactional` is placed on the **concrete class method**, never the interface, because Spring may silently ignore interface-level annotations (Spring Framework Reference — Declarative transaction management; ADR-0001, ADR-0005 note).

Apply this pattern to produce/modify:

| Use case | File action | Interface change | Service action |
|---|---|---|---|
| `CreateDashboardUseCase` | EXTEND | add return type | EXTEND `CreateDashboardService` (already throws `UnsupportedOperationException` — keep, add return type) |
| `CreateProjectsDatabaseUseCase` | **EXTEND → convert to interface** (see §6.1) | new interface, `ProvisioningStepResult execute(UUID)` | **NEW** `CreateProjectsDatabaseService implements CreateProjectsDatabaseUseCase`, throws `UnsupportedOperationException` |
| `CreateTasksDatabaseUseCase` | EXTEND | add return type | EXTEND `CreateTasksDatabaseService` (keep throw, add return type) |
| `CreateKnowledgeDatabaseUseCase` | EXTEND | add return type | EXTEND `CreateKnowledgeDatabaseService` (keep throw, add return type) |
| `CreateHabitsDatabaseUseCase` | EXTEND | add return type | **NEW** `CreateHabitsDatabaseService` (no service class exists today) |
| `CreateJournalDatabaseUseCase` | EXTEND | add return type | **NEW** `CreateJournalDatabaseService` |
| `CreateResourcesDatabaseUseCase` | EXTEND | add return type | **NEW** `CreateResourcesDatabaseService` |
| `CreatePeopleDatabaseUseCase` | EXTEND | add return type | **NEW** `CreatePeopleDatabaseService` |
| `CreateRelationsUseCase` | **NEW** | `ProvisioningStepResult execute(UUID)` | **NEW** `CreateRelationsService`, throws `UnsupportedOperationException` |
| `CreateRollupsUseCase` | **NEW** | same | **NEW** `CreateRollupsService`, throws |
| `CreateFormulasUseCase` | **NEW** | same | **NEW** `CreateFormulasService`, throws |
| `PopulateSampleDataUseCase` | **NEW** | same | **NEW** `PopulateSampleDataService`, throws |

Every `execute(UUID)` throwing `UnsupportedOperationException` uses the message pattern: `"<Resource> not yet implemented: requires the Notion adapter"` (matches the two existing stubs verbatim in tone) — keep messages consistent so CLI/REST error output reads uniformly.

---

## 6. Explicit deviations from existing code required by this spec

### 6.1 `CreateProjectsDatabaseUseCase` refactor (architecture §8 finding 1)

Current code:

```java
package com.lifeos.application.usecase.project;

@Service
@RequiredArgsConstructor
public class CreateProjectsDatabaseUseCase {
    public void execute(UUID workspaceId) {
        // Implementation for creating the Projects database structure
    }
}
```

This is a silent no-op: no interface, no exception, `@Service` annotated directly on what should be the interface's name. Required change:

1. Rename this class's role: `CreateProjectsDatabaseUseCase` becomes the **interface** (`ProvisioningStepResult execute(UUID workspaceId);`), matching every sibling database use case.
2. Create `CreateProjectsDatabaseService implements CreateProjectsDatabaseUseCase`, `@Service @RequiredArgsConstructor`, throwing `UnsupportedOperationException("Projects database creation not yet implemented: requires the Notion adapter")`.
3. Delete the old combined class body entirely — do not leave a second, dead `execute` overload.

### 6.2 `void` → `ProvisioningStepResult` (architecture §8 finding 2)

Applies to: `CreateDashboardUseCase`/`Service`, `CreateProjectsDatabaseUseCase` (new), `CreateTasksDatabaseUseCase`/`Service`, `CreateKnowledgeDatabaseUseCase`/`Service`, `CreateHabitsDatabaseUseCase` (+ new Service), `CreateJournalDatabaseUseCase` (+ new Service), `CreateResourcesDatabaseUseCase` (+ new Service), `CreatePeopleDatabaseUseCase` (+ new Service). All become `ProvisioningStepResult execute(UUID workspaceId);` and their stub bodies unconditionally `throw new UnsupportedOperationException(...)` (the return type never actually returns while stubbed — this is correct and expected; the orchestrator's `runStep`/`runOrBlock` convert the thrown exception into a `FAILED` `ProvisioningStepResult`).

### 6.3 Orchestrator transaction removal (ADR-0001, architecture §4.3)

`CreateWorkspaceService.execute` currently carries `@Transactional`. **Remove it.** No transaction annotation belongs on the orchestrator class or method. See §5.2 for the replacement control flow.

### 6.4 `Workspace.create(String name)` → `Workspace.create(String name, UUID personId)`

Breaking signature change (§2.3). Every existing caller/test using the 1-arg factory must be updated to pass a `personId`.

### 6.5 `CreateWorkspaceCommand(String, UUID)` → `CreateWorkspaceCommand(String, UUID, boolean)`

Breaking constructor change (§3.1). Update all call sites.

---

## 7. Repositories & persistence (`infrastructure.adapter.persistence`)

### 7.1 `WorkspaceJpaEntity` [NEW]

```java
@Entity
@Table(name = "workspaces", uniqueConstraints = @UniqueConstraint(columnNames = {"person_id", "name"}))
class WorkspaceJpaEntity {
    @Id
    private UUID id;

    @Column(name = "person_id", nullable = false)
    private UUID personId;

    @Column(nullable = false)
    private String name;

    @OneToMany(mappedBy = "workspace", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<ProvisionedResourceJpaEntity> resources = new ArrayList<>();

    @Version
    private long version;

    // no-arg + all-args constructors, getters/setters (Lombok @Getter/@Setter/@NoArgsConstructor/@AllArgsConstructor acceptable here — this is an infra entity, not the domain aggregate)
}
```

### 7.2 `ProvisionedResourceJpaEntity` [NEW]

```java
@Entity
@Table(name = "provisioned_resources", uniqueConstraints = @UniqueConstraint(columnNames = {"workspace_id", "type"}))
class ProvisionedResourceJpaEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private WorkspaceJpaEntity workspace;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProvisionedResourceType type;

    @Column(name = "notion_id", nullable = false)
    private String notionId;

    @Column(name = "provisioned_at", nullable = false)
    private Instant provisionedAt;
}
```

The `(workspace_id, type)` unique constraint backs the domain invariant "a workspace has at most one resource of each type" (ADR-0003) at the store; the JPA-mapping code in `JpaWorkspaceRepository` upserts (delete-then-insert or merge-by-type) rather than blind-inserting, matching `Workspace.record`'s replace semantics.

### 7.3 `SpringDataWorkspaceRepository` [NEW] — Spring Data interface

```java
interface SpringDataWorkspaceRepository extends JpaRepository<WorkspaceJpaEntity, UUID> {
    @EntityGraph(attributePaths = "resources")
    Optional<WorkspaceJpaEntity> findById(UUID id);

    @EntityGraph(attributePaths = "resources")
    Optional<WorkspaceJpaEntity> findByPersonIdAndName(UUID personId, String name);
}
```

`@EntityGraph` on both derived finders avoids N+1 when the ledger is read alongside the workspace (`spring-data-jpa`) — the ledger is always needed together with the aggregate for reconciliation, so it is never worth a lazy round trip.

### 7.4 `JpaWorkspaceRepository` [NEW] — domain port adapter

```java
@Repository
@RequiredArgsConstructor
class JpaWorkspaceRepository implements WorkspaceRepository {

    private final SpringDataWorkspaceRepository springData;

    @Override
    @Transactional
    public Workspace save(Workspace workspace) { /* map domain -> entity (upsert resources by type), save, map back */ }

    @Override
    @Transactional(readOnly = true)
    public Optional<Workspace> findById(UUID id) { /* map entity -> domain */ }

    @Override
    @Transactional(readOnly = true)
    public Optional<Workspace> findByPersonIdAndName(UUID personId, String name) { /* map entity -> domain */ }
}
```

Mapping is hand-written (small aggregate; MapStruct is available per `CLAUDE.md` but not required here — do not add a mapper class solely for two small entities unless the Implementer finds it clearer; either is acceptable, this is not a load-bearing decision).

### 7.5 Migration (Flyway — `db/migration/`, per `spring-data-jpa` skill: schema owned by migrations, `ddl-auto: validate`)

New file `V1__create_workspace_tables.sql` (or next available `V<n>__...sql` if earlier migrations exist — check `backend/src/main/resources/db/migration/` at implementation time; none currently exists per the "no `src/main/resources`" note in `CLAUDE.md`, so this is likely `V1__`):

```sql
CREATE TABLE workspaces (
    id UUID PRIMARY KEY,
    person_id UUID NOT NULL,
    name VARCHAR(255) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_workspaces_person_id_name UNIQUE (person_id, name)
);

CREATE TABLE provisioned_resources (
    id BIGSERIAL PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    type VARCHAR(50) NOT NULL,
    notion_id VARCHAR(255) NOT NULL,
    provisioned_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_provisioned_resources_workspace_type UNIQUE (workspace_id, type)
);

CREATE INDEX idx_provisioned_resources_workspace_id ON provisioned_resources (workspace_id);
```

`spring.jpa.hibernate.ddl-auto=validate` in `application.yml` (all profiles) — schema changes only via new, immutable Flyway files (`spring-data-jpa`).

---

## 8. Controllers & CLI

### 8.1 REST — `infrastructure.adapter.web`

**Request/response DTOs (records, Jakarta Bean Validation):**

```java
public record CreateWorkspaceRequest(
    @NotBlank String name,
    @NotNull UUID personId,
    boolean sampleData
) {}

public record ProvisioningStepResultResponse(String type, String outcome, String detail) {}

public record ProvisioningReportResponse(UUID workspaceId, List<ProvisioningStepResultResponse> steps, boolean failed) {}
```

**`WorkspaceController`:**

```java
@RestController
@RequestMapping("/api/workspaces")
@RequiredArgsConstructor
public class WorkspaceController {

    private final CreateWorkspaceUseCase createWorkspace;

    @PostMapping
    public ResponseEntity<ProvisioningReportResponse> create(@Valid @RequestBody CreateWorkspaceRequest request) {
        ProvisioningReport report = createWorkspace.execute(
                new CreateWorkspaceCommand(request.name(), request.personId(), request.sampleData()));

        ProvisioningReportResponse body = toResponse(report);

        if (report.failed()) {
            throw new WorkspaceProvisioningFailedException(report); // caught by ApiExceptionHandler -> 502 ProblemDetail carrying the report
        }
        HttpStatus status = report.steps().stream()
                .allMatch(s -> s.outcome() == ProvisioningOutcome.RECONCILED)
                ? HttpStatus.OK
                : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(body);
    }
}
```

Notes:
- **201 Created** when at least one step was newly `CREATED`/`REPAIRED`; **200 OK** when the run was a pure reconcile (every step `RECONCILED`) — matches ADR-0008 ("201 Created (or 200 OK on a pure reconcile)").
- On a `failed()` report, the controller does **not** return a 2xx body with an embedded failure — it raises a dedicated unchecked exception (`WorkspaceProvisioningFailedException`, `application.dto.workspace` or `infrastructure.adapter.web`, carrying the `ProvisioningReport`) that `ApiExceptionHandler` maps to a `ProblemDetail`. This keeps "controller has no business logic" while still routing every error through the one `@RestControllerAdvice` (`spring-boot-conventions`).
- No entity ever crosses the boundary; `CreateWorkspaceCommand`/`ProvisioningReport` are application DTOs, not domain objects — `Workspace` itself never appears in a request/response.

**`ApiExceptionHandler`:**

```java
@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        pd.setTitle("Validation failed");
        pd.setProperty("errors", ex.getBindingResult().getFieldErrors().stream()
                .map(e -> Map.of("field", e.getField(), "message", e.getDefaultMessage())).toList());
        return pd;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        pd.setTitle("Invalid request");
        return pd;
    }

    @ExceptionHandler(WorkspaceProvisioningFailedException.class)
    ProblemDetail handleProvisioningFailed(WorkspaceProvisioningFailedException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY,
                "One or more provisioning steps failed or were blocked");
        pd.setTitle("Workspace provisioning incomplete");
        pd.setProperty("workspaceId", ex.getReport().workspaceId());
        pd.setProperty("steps", ex.getReport().steps());
        return pd;
    }
}
```

`502 Bad Gateway` is used for provisioning failure because the root cause is an upstream (Notion) or unimplemented-dependency failure, not a malformed client request; `400` stays reserved for validation/`IllegalArgumentException`. This mapping is not fixed by any ADR — it is the SME's concrete, reasonable choice within ADR-0008's "a failed run maps to an error" instruction; if the Architect prefers a different status code (e.g. `422`), that is a one-line change, noted here so the Implementer does not have to decide it.

### 8.2 CLI — `infrastructure.adapter.cli.WorkspaceCommands`

```java
@Command(group = "Workspace")
@RequiredArgsConstructor
public class WorkspaceCommands {

    private final CreateWorkspaceUseCase createWorkspace;

    @Command(command = "workspace create", description = "Create or reconcile a LifeOS workspace in Notion")
    public String create(
            @Option(longNames = "name", required = true) String name,
            @Option(longNames = "person-id", required = true) UUID personId,
            @Option(longNames = "sample-data", defaultValue = "false") boolean sampleData) {

        ProvisioningReport report = createWorkspace.execute(new CreateWorkspaceCommand(name, personId, sampleData));

        String rendering = renderReport(report);
        if (report.failed()) {
            throw new CommandFailedException(rendering); // or: return rendering and rely on ExitCodeMappings — see note below
        }
        return rendering;
    }
}
```

Spring Shell 3.3.x exposes non-zero-exit-on-failure via `CommandExecutionExceptionHandler`/`ExitCodeMappings` (or by throwing from the `@Command` method, which Spring Shell surfaces as a non-zero exit and printed error by default — Spring Shell Reference, Command Registration/Exit Codes, docs.spring.io/spring-shell). The Implementer picks either throwing directly (simplest, matches this spec) or registering an `ExitCodeMappings` bean if the team's Spring Shell version needs finer control; **the required outcome is non-negotiable: `report.failed()` must produce a non-zero process exit**, per ADR-0006/ADR-0008.

`renderReport(ProvisioningReport)` is a private formatting helper (one line per step: type, outcome, detail) — no business logic, pure string formatting.

---

## 9. Security (per `spring-security` skill; NFR-6, OQ-7)

- **Notion token**: single process-level secret, bound via `@ConfigurationProperties(prefix = "notion")` (`NotionProperties` record/class with a `token` field), sourced from an environment variable (e.g. `NOTION_TOKEN`) or secret manager at deploy time — **never hardcoded, never committed** (`spring-boot-conventions` checklist: "no secret is hardcoded; config is profile-driven"). `NotionProvisioningAdapter` is the only class that reads this property.
- **REST endpoint authn/authz**: explicitly **out of scope for v0** (ADR-0008 consequences, spec §8). Do **not** add Spring Security, JWT, or any authentication filter to `WorkspaceController` — that would be scope creep beyond this spec. The controller still validates all input server-side (`@Valid`) and never trusts client-supplied data beyond the DTO's declared shape.
- **No cross-Person leakage**: every operation is scoped to the `personId` in the command; there is no endpoint or CLI option that lists/queries workspaces across `personId`s in this feature.
- **Logging**: never log the Notion token; step logs (NFR-5) log `type`/`outcome`/`detail`/`workspaceId` only.

---

## 10. Test plan (write first — TDD)

Ordering below is the required build order: domain → DTOs → orchestrator (with step interfaces mocked) → individual stub services → persistence adapter → CLI → REST. Each test class lists the unit under test and the specific cases. Use the narrowest test type per `spring-testing` (plain unit > `@WebMvcTest`/`@DataJpaTest` slice > `@SpringBootTest` + Testcontainers).

### 10.1 `WorkspaceTest` (plain unit, no Spring context) — `domain.workspace`

- `create_rejectsBlankName` — `Workspace.create("", personId)` throws `IllegalArgumentException`; same for `null`, whitespace-only.
- `create_rejectsNullPersonId` — `Workspace.create("Personal", null)` throws `IllegalArgumentException`.
- `create_mintsIdAndEmptyLedger` — valid inputs produce a non-null `id`, the given `name`/`personId`, and an empty `resources` list.
- `record_appendsNewResourceType` — `record(DASHBOARD, "notion-1")` on an empty workspace returns a **new** `Workspace` (not the same reference) whose `resource(DASHBOARD)` is present with `notionId = "notion-1"`.
- `record_replacesExistingResourceOfSameType` — recording `TASKS_DB` twice with different `notionId`s leaves exactly one `ProvisionedResource` of type `TASKS_DB`, holding the **second** id (upsert, not append).
- `record_doesNotMutateOriginalInstance` — after calling `record(...)`, the original `Workspace`'s `resources` list is unchanged (immutability).
- `resource_returnsEmptyForUnrecordedType` — `resource(PROJECTS_DB)` on a workspace with no `PROJECTS_DB` entry returns `Optional.empty()`.

### 10.2 `ProvisionedResourceTest` (plain unit) — `domain.workspace`

- `constructor_rejectsNullType`, `constructor_rejectsBlankNotionId`, `constructor_rejectsNullProvisionedAt` — compact-constructor validation.

### 10.3 `CreateWorkspaceCommandTest` (plain unit) — `application.dto.workspace`

- `constructor_rejectsBlankName`
- `constructor_rejectsNullPersonId`
- `constructor_acceptsValidInputsWithSampleDataFlag` — `sampleData=true` is retained and readable via `command.sampleData()`.

### 10.4 `ProvisioningStepResultTest` / `ProvisioningReportTest` (plain unit) — `application.dto.workspace`

- `constructor_requiresDetailWhenFailed` — `FAILED` outcome with null/blank detail throws.
- `constructor_requiresDetailWhenBlocked` — same for `BLOCKED`.
- `constructor_allowsNullDetailWhenCreated` — `CREATED`/`RECONCILED`/`REPAIRED` do not require detail.
- `failed_trueWhenAnyStepFailed` — a report with one `FAILED` step among others → `failed() == true`.
- `failed_trueWhenAnyStepBlocked` — same for `BLOCKED`.
- `failed_falseWhenAllStepsSucceed` — all `CREATED`/`RECONCILED`/`REPAIRED` → `failed() == false`.
- `failed_falseForEmptySteps` — degenerate case, no steps → `false`.

### 10.5 `CreateWorkspaceServiceTest` (plain unit, Mockito mocks for `WorkspaceRepository` and all 12 step use cases) — `application.usecase.workspace`

This is the highest-value test class; cover the orchestrator's sequencing/blocking logic exhaustively before touching any adapter.

- `execute_createsNewWorkspaceWhenNoneExists` — `findByPersonIdAndName` returns empty → `workspaceRepository.save` is called once with a `Workspace` built via `Workspace.create(name, personId)`.
- `execute_reusesExistingWorkspaceWhenPresent` — `findByPersonIdAndName` returns a workspace → `save` is **not** called again by the orchestrator itself (only steps persist).
- `execute_runsAllPhasesInOrderOnHappyPath` — all 12 step mocks return `CREATED`/`RECONCILED`; assert (via `InOrder`/Mockito) dashboard → 7 databases → relations → rollups → formulas, and that `ProvisioningReport.steps()` contains one result per invoked step in that order.
- `execute_mapsThrownExceptionFromAStepToFailedResult` — one database step mock throws `UnsupportedOperationException("x")`; assert the corresponding `ProvisioningStepResult` is `FAILED` with `detail` containing `"x"`, and the exception does **not** propagate out of `execute`.
- `execute_blocksDependentDatabaseStepsWhenDashboardFails` — dashboard step throws → every one of the 7 database steps is `BLOCKED` and is **never invoked** (`verifyNoInteractions`/`verify(..., never())` on the database step mocks).
- `execute_blocksRelationsWhenAnyDatabaseStepFails` — one of the 7 DB steps returns `FAILED`; assert `CreateRelationsUseCase.execute` is never invoked and its result is `BLOCKED`.
- `execute_blocksRollupsWhenRelationsBlocked` and `execute_blocksFormulasWhenRollupsBlocked` — cascading block through phases C→D→E.
- `execute_runsIndependentDatabaseStepsEvenWhenAnotherDatabaseStepFails` — e.g. Tasks DB step throws, but Projects/Knowledge/etc. step mocks are still invoked and their (successful) results appear in the report — independent steps are not blocked by a sibling's failure (only *dependents*, per ADR-0006).
- `execute_skipsSampleDataStepWhenFlagFalse` — `sampleData=false` → `PopulateSampleDataUseCase.execute` never invoked, and no `SAMPLE_DATA` entry appears in the report.
- `execute_runsSampleDataStepWhenFlagTrueAndAllStructuralPhasesSucceed` — `sampleData=true`, all phases A–E succeed → `PopulateSampleDataUseCase.execute` invoked once.
- `execute_blocksSampleDataStepWhenFlagTrueButAStructuralPhaseFailed` — `sampleData=true`, some structural step fails → `PopulateSampleDataUseCase.execute` never invoked; a `SAMPLE_DATA` `BLOCKED` entry is present in the report (FR-13's "no sample data on structural failure").
- `execute_reportFailedTrueWhenAnyStepFailedOrBlocked` / `execute_reportFailedFalseOnFullHappyPath` — end-to-end assertions on `ProvisioningReport.failed()` for both cases.
- `execute_isNotAnnotatedTransactional` — a reflection-based assertion (`CreateWorkspaceService.class.getMethod("execute", CreateWorkspaceCommand.class).isAnnotationPresent(Transactional.class)` is `false`, and the class itself carries no class-level `@Transactional`) — pins ADR-0001's required removal so a future regression is caught by CI, not code review.

### 10.6 Per-stub-service unit tests — one class per new/changed step service, e.g. `CreateDashboardServiceTest`, `CreateProjectsDatabaseServiceTest`, `CreateTasksDatabaseServiceTest`, `CreateKnowledgeDatabaseServiceTest`, `CreateHabitsDatabaseServiceTest`, `CreateJournalDatabaseServiceTest`, `CreateResourcesDatabaseServiceTest`, `CreatePeopleDatabaseServiceTest`, `CreateRelationsServiceTest`, `CreateRollupsServiceTest`, `CreateFormulasServiceTest`, `PopulateSampleDataServiceTest` (plain unit, no Spring context)

Each asserts exactly:
- `execute_throwsUnsupportedOperationExceptionUntilNotionAdapterExists` — `assertThatThrownBy(() -> service.execute(workspaceId)).isInstanceOf(UnsupportedOperationException.class)`.

(These are intentionally minimal — the stub does nothing else; do not over-test a `throw` statement.)

### 10.7 `JpaWorkspaceRepositoryTest` (`@DataJpaTest`, Testcontainers Postgres per `spring-testing`) — `infrastructure.adapter.persistence`

- `save_persistsNewWorkspaceWithEmptyLedger`
- `save_persistsWorkspaceWithLedgerEntries` — save a `Workspace` with 2 `ProvisionedResource`s, reload, assert both round-trip with correct `type`/`notionId`/`provisionedAt`.
- `save_upsertsResourceOfSameTypeOnReRecord` — save, then save again after `workspace.record(TASKS_DB, "new-id")` on the same aggregate — reload and assert exactly one `TASKS_DB` row with `"new-id"` (proves the unique `(workspace_id, type)` constraint + upsert mapping works, not just the domain method).
- `findById_returnsEmptyForUnknownId`
- `findByPersonIdAndName_returnsMatchingWorkspace`
- `findByPersonIdAndName_returnsEmptyWhenNameDiffers` — same `personId`, different `name` → empty (proves the compound key, not `personId` alone).
- `findByPersonIdAndName_doesNotNPlusOneOnLedger` — assert (via Hibernate statistics or SQL log capture) that loading a workspace with N ledger entries executes a bounded number of queries (fetch-join/`@EntityGraph` working) rather than 1+N.
- `save_rejectsDuplicatePersonIdAndName` — inserting two workspaces with the same `(person_id, name)` violates the unique constraint (`DataIntegrityViolationException`), proving FR-4's uniqueness is enforced at the store, not just in application logic.

### 10.8 `WorkspaceCommandsTest` (plain unit, Mockito mock of `CreateWorkspaceUseCase`) — `infrastructure.adapter.cli`

- `create_invokesUseCaseWithParsedArguments` — verify `CreateWorkspaceCommand(name, personId, sampleData)` built correctly from the three options.
- `create_defaultsSampleDataToFalseWhenOmitted`
- `create_rendersAllStepsOnSuccess` — output/return string contains each step's type and outcome.
- `create_signalsFailureWhenReportFailed` — `report.failed() == true` → command throws/returns a non-zero-exit signal (exact mechanism per §8.2; assert whichever the Implementer picks, but the test must prove a non-zero-exit path is exercised).

### 10.9 `WorkspaceControllerTest` (`@WebMvcTest(WorkspaceController.class)`, `@MockitoBean CreateWorkspaceUseCase`) — `infrastructure.adapter.web`

- `create_returns201WhenNewStructuresCreated` — mock report with at least one `CREATED` step → `201`, body matches `ProvisioningReportResponse` shape.
- `create_returns200WhenPureReconcile` — mock report with only `RECONCILED` steps → `200`.
- `create_returns400ProblemDetailOnBlankName` — POST with `name=""` → `400`, `content-type: application/problem+json`, `title` present.
- `create_returns400ProblemDetailOnMissingPersonId` — POST without `personId` → `400` problem detail.
- `create_returns502ProblemDetailWhenReportFailed` — mock report with a `FAILED` step → `502` problem detail carrying `workspaceId` and `steps` properties.
- `create_neverExposesDomainWorkspaceInResponse` — response body JSON does not contain a raw domain `Workspace` shape (sanity check that only DTOs serialize).

### 10.10 `CreateWorkspaceIT` (`@SpringBootTest(webEnvironment = RANDOM_PORT)` + Testcontainers Postgres, per `spring-testing`) — end-to-end wiring, `NotionProvisioningPort` mocked/stubbed via a `@TestConfiguration` bean (no real Notion calls in CI)

- `postWorkspace_persistsWorkspaceAndReturnsFailedReportWhileStepsAreStubbed` — with the real `CreateWorkspaceService` and real JPA repository wired, a `POST /api/workspaces` call persists a `Workspace` row and returns a `502` (because every step still throws `UnsupportedOperationException` at this stage of implementation) — proves the whole chain (controller → orchestrator → persistence) is wired correctly even before any Notion adapter exists. This test class documents and locks in the intentionally-red-until-Notion-adapter-exists behavior described in §6, so CI stays honest about what's really implemented (CLAUDE.md "no silent no-op" verified end-to-end, not just per-unit).
- `reRunWithSamePersonIdAndName_reusesExistingWorkspaceRow` — two sequential `POST /api/workspaces` calls with the same `(personId, name)` result in exactly one `workspaces` row (idempotency key, FR-4/FR-10) — assert via a direct repository query, independent of step outcomes.

---

## 11. Note back to the Architect (non-blocking)

One place in this spec makes a concrete choice the architecture document left open: **HTTP status code for a failed provisioning report** (§8.1 — `502 Bad Gateway` for `report.failed()`). ADR-0008 only says "a failed run maps to an error." `502` was chosen because the failure's root cause (an unimplemented/erroring downstream Notion step) is upstream-shaped, not a malformed client request; `422 Unprocessable Entity` is a reasonable alternative reading. This is a one-line change in `ApiExceptionHandler` if the Architect prefers otherwise — flagging so the Implementer does not have to re-derive or silently pick one.

No other deviation from `02-architecture.md`/the ADRs was needed; this spec is a direct, mechanical elaboration of the resolved design.

---

## 12. Explicitly NOT built in v0 (scope guard for the Implementer)

Do not build any of the following — they are deferred/out-of-scope per the architecture and ADRs, and building them would be scope creep beyond this spec:

- **`CreateGoalsDatabaseUseCase` / `CreateGoalsDatabaseService`** and **`CreateReviewsDatabaseUseCase` / `CreateReviewsDatabaseService`** — Goals and Reviews databases are deferred (OQ-1). Do not add `goal`/`review` use-case packages, do not wire `GOALS_DB`/`REVIEWS_DB` into the orchestrator's Phase B. The enum slots exist as reserved values only.
- **Any real Notion SDK/HTTP integration** inside `NotionProvisioningAdapter` — every adapter method may be a stub throwing `UnsupportedOperationException` for this pass; wiring the actual Notion API client, rate-limit/backoff logic (NFR-2), and shape-verification HTTP calls is a separate, later implementation pass once this scaffold is green. The adapter class and its Spring bean registration must exist so `NotionProvisioningPort` has exactly one implementation in the context, but its method bodies are not required to work yet.
- **Per-Person Notion token storage/scoping** — v0 uses one process-level secret (§9). Do not add a `Person`-to-token association, a token-management endpoint, or per-request token resolution.
- **REST authentication/authorization** — no Spring Security filter chain, no API key, no JWT on `WorkspaceController`. The endpoint is intentionally open in v0 (§9, ADR-0008 consequences).
- **A `List<ProvisioningStep>` generic/pluggable orchestration model** — ADR-0005 explicitly rejects this for v0 in favor of the orchestrator's explicit, typed, hand-sequenced dependencies (§5.2). Do not introduce a `ProvisioningStep` interface or a Spring `List<X>` injection pattern.
- **Workspace deletion/archival/multi-adapter (Obsidian, Capacities, web UI) support** — out of scope per `01-spec.md` §8.
- **A `Clock`/time-abstraction bean for `Instant.now()`** — not required by any ADR; keep `Workspace.record` using `Instant.now()` directly (§2.3 note).

---

## 13. Traceability (FR/NFR → spec section)

| Req | Satisfied by (this spec) |
|---|---|
| FR-1 | §3.1 `CreateWorkspaceCommand`; §8.1 `WorkspaceController`; §8.2 `WorkspaceCommands` |
| FR-2 | §3.1 compact constructor (name); test 10.3 |
| FR-3 | §3.1 compact constructor (personId); test 10.3 |
| FR-4 | §5.2 orchestrator load-or-create; §2.3 `Workspace.create`; §7.5 unique constraint; tests 10.5, 10.7, 10.10 |
| FR-5 | §5.3 `CreateDashboardUseCase` Phase A; test 10.5 (blocking cascade) |
| FR-6 | §5.3 seven database use cases Phase B; test 10.5 |
| FR-7 | §5.3 `CreateRelationsUseCase` Phase C; test 10.5 |
| FR-8 | §5.3 `CreateRollupsUseCase` Phase D; test 10.5 |
| FR-9 | §5.3 `CreateFormulasUseCase` Phase E; test 10.5 |
| FR-10 | §4 `NotionProvisioningPort.verify/findChildByIdentity/repairShape` (adapter stubbed, contract fixed); §2.3 `Workspace.record` upsert; test 10.7 |
| FR-11 | §5.2 no orchestrator transaction + durable per-step ledger writes (§5.3); §6.3 |
| FR-12 | §3.3/§3.4 `ProvisioningStepResult`/`ProvisioningReport`; §8.1/§8.2 rendering; tests 10.4, 10.8, 10.9 |
| FR-13 | §5.2 Phase F gating; test 10.5 sample-data cases |
| FR-14 | §5.3 stub `UnsupportedOperationException`; §5.2 `runStep` catch; tests 10.5, 10.6, 10.10 |
| NFR-1 | §4 verify-before-trust port contract (fixed now, implemented later); §2.3 upsert semantics |
| NFR-2 | §9 token config; adapter retry/backoff deferred to Notion-adapter implementation pass (§12) |
| NFR-3 | §2 domain package framework-free (no Lombok `@Entity`/JPA annotations); §7 JPA mapping isolated to adapter |
| NFR-4 | §5.2/§5.3 no silent no-op; test 10.5, 10.6, 10.10 |
| NFR-5 | §3.3 `detail` field; §8 rendering; logging note §9 |
| NFR-6 | §9 Security section |
| NFR-7 | No SLA changes; N/A beyond what's specified |
| NFR-8 | N/A v0 (unchanged) |

---

## 14. Post-audit remediation (added 2026-08-04)

The Auditor (`05-audit-report.md`) raised 1 High, 2 Medium, 6 Low findings. All except the out-of-scope informational L6 were implemented; this section supersedes the specific sub-sections named below. Build re-verified: `TESTCONTAINERS_RYUK_DISABLED=true ./mvnw verify` → BUILD SUCCESS, **82 tests** (was 67).

- **H1 / §3.3, §8.1 (REST response shape).** `ProvisioningStepResultResponse` drops `detail` → `record ProvisioningStepResultResponse(String type, String outcome)`; the REST body never carries per-step free text on any path. `ApiExceptionHandler.handleProvisioningFailed` emits only `{type, outcome}` per step and logs the full report server-side (SLF4J); `handleIllegalArgument` stops echoing `getMessage()`. (The application-layer `ProvisioningStepResult.detail` in §3.3 is unchanged — it still feeds server-side logging and the local CLI rendering, which is not a network boundary.)
- **M1 / §8.1 (error handling).** `ApiExceptionHandler` gains `@ExceptionHandler(DataIntegrityViolationException.class)` → 409 `ProblemDetail` and a fallback `@ExceptionHandler(Exception.class)` → 500 `ProblemDetail`, both generic + logged.
- **M2 / §2.3, §7.4 (aggregate construction).** `Workspace` (and `Task`/`Goal`/`Project`/`Person`) use `@Builder(access = AccessLevel.PRIVATE)`. New factory `public static Workspace reconstitute(UUID id, UUID personId, String name, List<ProvisionedResource> resources)` is the repository rehydration entry point; `JpaWorkspaceRepository.toDomain` calls `reconstitute(...)` instead of the builder.
- **L1 / §5.3 (transaction boundary).** New `@Component WorkspaceLedgerWriter` with `@Transactional public void record(UUID, ProvisionedResourceType, String)`. The per-service `recordLedger(...)` methods are removed; each of the 12 step services now injects `WorkspaceLedgerWriter` (not `WorkspaceRepository`) and calls it across a proxied boundary. Update §10.6 per-stub-service tests to mock `WorkspaceLedgerWriter` instead of `WorkspaceRepository` in the 2-arg constructor.
- **L2 / §8.1 (controller).** `report.failed()` check precedes `toResponse(...)`; the response body is built only on success.
- **L3 / new `domain/person`.** New self-validating `record Email(String value)` (+ `Email.of`); `Person.email` is typed `Email`, wrapped in `Person.create`. New `EmailTest`, `PersonTest`.
- **L4 / §7.4 (persistence).** `JpaWorkspaceRepository.save` reconciles the ledger in place (`removeIf` absent types, update matched, add new) instead of `clear()` + re-add.
- **L5 / §7.2, §7.5 (id generation).** `ProvisionedResourceJpaEntity.id` uses `GenerationType.SEQUENCE` + `@SequenceGenerator("provisioned_resource_id_seq", allocationSize = 50)`; migration `V1` adds `CREATE SEQUENCE provisioned_resource_id_seq ... INCREMENT BY 50` and the column becomes `BIGINT`. §10.7 N+1 test flushes before clearing statistics (SEQUENCE defers inserts to flush).
- **L6 — deferred by design.** REST authn/authz remains out of v0 scope (see §12 and `02-open-questions.md`); no change.
- **§10.9 note.** The controller slice test uses `@MockBean` (Boot 3.3.2), not `@MockitoBean` (Boot 3.4+), and adds cases asserting the 502/409/500 bodies never leak internal detail.
- **NFR-5 traceability update.** Server-side logging of provisioning failures is now implemented in `ApiExceptionHandler` (the `detail` field alone no longer represents the whole of NFR-5).
