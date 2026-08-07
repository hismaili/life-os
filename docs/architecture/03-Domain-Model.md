# Package Structure

LifeOS follows a structured package layout to support Hexagonal Architecture and Domain-Driven Design.

## Root Package: `com.lifeos`

## Core Aggregates

The domain model represents a single, cohesive domain focused on personal organization and productivity. The following aggregates are central to the system:

- **Workspace**: The top-level container for all organizing elements.
- **Person**: Represents the user, their identity, and fundamental preferences.
- **Area**: Broad areas of responsibility or interest (e.g., "Health", "Work", "Finances").
- **Project**: A series of tasks aimed at a specific goal within an area.
- **Task**: The smallest unit of actionable work.
- **Goal**: High-level objectives that projects and tasks contribute toward.
- **Knowledge**: Structured information and insights.
- **Resource**: External references, documents, and links.
- **Habit**: Recurring actions and routines designed for consistency.
- **JournalEntry**: Time-stamped logs of thoughts, events, or reflections.
- **Review**: Periodic evaluations of progress, projects, and goals.

## Design Principles

- **Single Cohesive Domain**: The aggregates are designed to work together to form a unified model of a person's life.
- **Rich Domain Model**: We avoid anemic domain models. Entities and aggregates must encapsulate both state and behavior. Business logic resides within the domain, not in the application or infrastructure layers.
- **Relationship-Driven**: Use relationships (identifiers/references) between aggregates to model complex interdependencies (e.g., a Project is linked to an Area; a Task is part of a Project).
- **Invariant Enforcement**: The domain layer is responsible for ensuring that all business rules and invariants are maintained during state transitions.


- **Repository Port**: Interface defining how the aggregate is persisted (to be implemented by infrastructure).
- **Domain Service**: Logic that involves multiple entities or transcends a single aggregate.
- **Factory**: Logic for complex entity or aggregate creation.
- **Events**: Domain events representing significant state changes.

### `application`
Contains **Use Cases** and orchestrates the flow of data between the domain and infrastructure.
- **Services**: Implement the use case logic by coordinating aggregates and repository ports.
- **DTOs**: Data Transfer Objects for input and output.
- **Ports**: Input ports (Interfaces for use cases).

### `infrastructure`
Contains the implementations of the ports defined in the domain and application layers.
- **Adapters (Driven/Secondary)**:
    - `notion`: Implementation of the Notion API integration.
    - `persistence`: Database or file-based implementations of repository ports.
    - `config`: Loading external configuration.
- **Adapters (Driving/Primary)**:
    - `cli`: Spring Shell commands and interactions.
    - `web` (Future): REST controllers.

### `cli`
Specific implementations for the **Spring Shell** interface, mapping user commands to application use cases.

### `config`
Global application configuration and Spring Bean definitions.

### `common`
Shared utilities, exceptions, and constants used across the entire project.

### `tests`
Test suites organized to mirror the source structure:
- `unit`: Testing individual domain entities and services.
- `integration`: Testing the interaction between layers and infrastructure adapters.
- `e2e`: Testing complete use case flows.
