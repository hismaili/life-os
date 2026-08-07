# Agent Instructions for LifeOS

## Architecture & Development
LifeOS follows Domain-Driven Design (DDD) principles. When adding new features, ensure you respect the layer boundaries:

- **`backend/src/main/java/com/lifeos/domain`**: Core business logic, entities, and domain services. No dependencies on external frameworks or adapters.
- **`backend/src/main/java/com/lifeos/application`**: Use cases, services, and DTOs. Orchestrates domain objects to fulfill application requirements.
- **`backend/src/main/java/com/lifeos/infrastructure`**: Implementation details.
    - **`adapter`**: External interfaces (e.g., `cli` via Spring Shell, `notion` via Notion API, `persistence` via JPA/SQL).
    - **`port`**: Interfaces defined by the domain or application that adapters must implement.

## Tech Stack
- **Language**: Java 21
- **Framework**: Spring Boot 3.3.2
- **CLI**: Spring Shell
- **Build Tool**: Maven
- **Key Libraries**: Lombok (boilerplate), MapStruct (mapping), Spring Boot Starter Test (testing).

## Development Commands
- **Build**: `mvn clean install` (run from `backend/`)
- **Test**: `mvn test` (run from `backend/`)
- **Run (CLI)**: Standard Spring Boot run command (check `pom.xml` for specific configurations if needed).

## Workflow Notes
- **New Feature Pattern**: 
    1. Define Entity/Value Object in `domain`.
    2. Define UseCase/Service in `application`.
    3. Implement Adapter in `infrastructure/adapter` if external interaction is required.
- **Documentation**: Refer to `docs/` for the underlying productivity methodologies (GTD, PARA, etc.) that drive the domain model.
