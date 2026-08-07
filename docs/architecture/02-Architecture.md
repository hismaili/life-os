# LifeOS Architecture

## Architectural Patterns

LifeOS is built using **Hexagonal Architecture** (Ports and Adapters) and **Domain-Driven Design (DDD)** principles to ensure that the core business logic remains independent of external technologies and platforms.

### Layers

1.  **Domain Layer**: The heart of the system. It contains the core business logic, entities, value objects, domain services, and domain events. The domain layer has **zero dependencies** on any external infrastructure, such as Notion or databases.
2.  **Application Layer**: Orchestrates the use cases. It coordinates the flow of data between the domain and the infrastructure through ports. This layer is responsible for implementing the system's capabilities (e.g., "Sync Notion Workspace").
3.  **Infrastructure Layer**: Implements the ports defined by the domain and application layers. This is where the "Adapters" live.
    - **Primary Adapters (Driving)**: Interfaces that allow users or external systems to trigger use cases (e.g., **Spring Shell** CLI, Web API).
    - **Secondary Adapters (Driven)**: Implementations that allow the application to interact with the outside world (e.g., **Notion API Adapter**, Persistence Adapter, File System Adapter).

### Principles

- **SOLID**: All software design decisions must adhere to SOLID principles.
- **Dependency Inversion**: High-level modules (Domain/Application) do not depend on low-level modules (Infrastructure). Both depend on abstractions (Ports).
- **Independence**: The domain model must be able to exist and be tested without any knowledge of Notion.

## Technology Stack

- **Language**: Java 21
- **Framework**: Spring Boot 3.x
- **Build Tool**: Maven
- **JSON Processing**: Jackson
- **CLI Interface**: Spring Shell
- **Testing**: JUnit 5, Mockito
- **Mapping**: MapStruct (where beneficial for DTO/Domain mapping)
- **Logging**: SLF4J
- **Utilities**: Lombok (strictly for improving readability and reducing boilerplate)
- **Data Access**: Spring Data (where justified for persistence)
