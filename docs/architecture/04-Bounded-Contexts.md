# LifeOS Use Cases (v0)

The first iteration of LifeOS focuses on the automated creation and configuration of a structured Notion workspace. These use cases are implemented as application services that coordinate the domain logic and the Notion adapter.

## Core Use Cases

### Workspace Orchestration
- **Create Workspace**: Initializes the entire LifeOS ecosystem within Notion, including the top-level pages.
- **Create Dashboard**: Generates the primary entry point (Dashboard) with navigation and high-level overviews.

### Database Provisioning
The system will systematically create highly structured databases to support each core aggregate:
- **Create Projects Database**: Sets up the database for managing active and archived projects.
- **Create Tasks Database**: Sets up the database for actionable items.
- **Create Knowledge Database**: Sets up the database for notes and information.
- **Create Habits Database**: Sets up the database for recurring routines.
- **Create Goals Database**: Sets up the database for strategic objectives.
- **Create Journal Database**: Sets up the database for daily logs and reflections.
- **Create Resources Database**: Sets up the database for external references and links.
- **Create People Database**: Sets up the database for managing contacts and stakeholders.
- **Create Reviews Database**: Sets up the database for periodic evaluations.

### Relationship and Logic Configuration
To ensure the databases function as a cohesive system, the following configurations will be applied:
- **Create Relations**: Establishes the vital links between databases (e.
    - e.g., Task $\leftrightarrow$ Project, Project $\leftrightarrow$ Goal, Knowledge $\leftrightarrow$ Resource).
- **Create Rollups**: Implements data aggregation across relations (
    - e.g., Summing completed tasks in a Project, counting active habits).
- **Create Formulas**: Implements the intelligent logic for progress tracking and status updates within Notion.

### Data Seeding
- **Populate Example Data**: After the structure is established, the system will populate the workspace with a realistic set of sample data to demonstrate the interconnectedness of the model.

## Implementation Note
All use cases must be **idempotent**. The system should be able to run the "Create Workspace" process multiple times without creating duplicate databases or pages, instead verifying existing structures and applying missing configurations.
