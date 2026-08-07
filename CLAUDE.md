# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

> An `AGENTS.md` also exists with overlapping guidance. Keep the two consistent when changing conventions.

## What this project is

LifeOS is a platform-agnostic **domain model** for a personal operating system (knowledge, projects, goals, tasks, habits, journal, people, reviews, resources, areas). The domain model *is* the product — productivity platforms like Notion are treated as **adapters (views)**, not the source of truth. The v0 goal is to automatically provision and configure a structured Notion workspace (databases, relations, rollups, formulas) from this core model.

## Project state (read before assuming code exists)

This is an early-stage scaffold. Be aware:
- There is **no `@SpringBootApplication` main class, no `src/main/resources`, and no tests yet.** `mvn spring-boot:run` has nothing to boot until a main class is added.
- The infrastructure adapters (`infrastructure/adapter/{notion,cli,persistence}`) and `infrastructure/port` are **empty directories** — the ports/adapters described in the docs are aspirational, not implemented.
- Several application services are **stubs** (e.g. `CreateTasksDatabaseService.execute` has an empty body). Don't mistake a stub for a finished feature.
- Top-level `backend/{application,cli,config,domain,infrastructure}` and `backend/scripts` are empty; the real code lives under `backend/src/main/java/com/lifeos/`.

## Build & test commands

Run from `backend/`:
- Build: `mvn clean install`
- Test all: `mvn test`
- Single test: `mvn test -Dtest=ClassName#methodName`

Stack: Java 21, Spring Boot 3.3.2, Spring Shell 3.3.2 (CLI), Maven. Lombok (boilerplate) and MapStruct 1.5.5 (DTO↔domain mapping) are annotation processors — a build is required for generated code, and Lombok is excluded from the Spring Boot fat jar (see `pom.xml`).

## Architecture: Hexagonal + DDD

Strict layer dependency direction — **inner layers never depend on outer ones**:

- `com.lifeos.domain.<aggregate>` — entities, value objects, domain services, and **repository *interfaces*** (ports). Zero framework/infrastructure dependencies. The domain must be testable with no knowledge of Notion or persistence.
- `com.lifeos.application.usecase.<aggregate>` — use cases and their services (orchestration). `application.dto` holds command records.
- `com.lifeos.infrastructure.adapter.{cli,notion,persistence}` — driving adapters (Spring Shell CLI) and driven adapters (Notion API, persistence) that *implement* the domain/application ports. `infrastructure.port` holds additional port interfaces.

When adding a feature, follow the layering: define the entity/VO in `domain`, the `UseCase` + `Service` in `application`, then an adapter in `infrastructure` only if external interaction is needed.

## Conventions (best practices to follow)

These are the standards new and refactored code should meet — not necessarily what every existing file currently does.

- **Self-validating domain entities**: keep `@Value` immutability, but create instances through a static factory (e.g. `Task.create(...)`) that generates the identity (`UUID`) and enforces invariants (non-blank titles, required references). The all-args builder/constructor is reserved for repository *reconstitution* of already-valid state. The application layer must never mint IDs or assemble an entity in an invalid state.
- **Reference other aggregates by `UUID`, never by object graph** (e.g. `Task.projectId`). Each aggregate root is its own consistency boundary; this is correct as-is — preserve it.
- **No primitive obsession**: model closed sets of values as domain enums (`TaskStatus`, habit `Frequency`) rather than `String`, and give meaningful strings that carry rules (email, URL) value objects. Domain logic compares enums, not string literals.
- **Repository ports** live in the *domain* package (e.g. `domain/workspace/WorkspaceRepository`), return domain types and `Optional`, and are implemented only in `infrastructure/adapter/persistence`.
- **Use case pattern**: an interface `CreateXUseCase` plus `@Service @RequiredArgsConstructor class CreateXService implements CreateXUseCase`. Write use cases carry `@Transactional`. Services depend on ports (interfaces), never concrete adapters.
- **Command/query DTOs** are Java records with **compact-constructor validation** (reject null/blank); they carry data, not behavior. Access fields as methods (`command.name()`).
- **Domain services** are pure, framework-free classes (no Spring annotations, no infrastructure imports); they return value objects where meaningful, not bare primitives. Application services *are* Spring `@Service` beans.
- **No silent no-op use cases**: an unimplemented use case must fail explicitly (`throw new UnsupportedOperationException(...)`) until its adapter exists, so a stub is never mistaken for working behavior.
- **Idempotency**: all workspace/database-provisioning use cases must be idempotent — re-running "Create Workspace" must verify and reconcile existing structures rather than duplicate them (see `docs/architecture/04-Bounded-Contexts.md`).

## Where the domain model comes from

`docs/architecture/` holds the design source of truth (`02-Architecture.md`, `03-Domain-Model.md`, `04-Bounded-Contexts.md`, `05-Ubiquitous-Language.md`). `docs/productivity/` holds the methodologies (GTD, PARA, Zettelkasten, Atomic Habits, OKRs, Second Brain, etc.) that the domain model encodes — consult these when modeling new aggregates. `docs/decisions/` holds ADRs. `notion/` (covers, formulas, icons, templates) holds Notion-specific presentation assets, separate from `backend/`.
