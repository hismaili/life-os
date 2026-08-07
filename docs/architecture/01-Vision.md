# LifeOS Vision

## Mission
To provide a platform-agnostic, permanent, and structured layer for a person's digital life, modeling their knowledge, projects, goals, tasks, habits, and learning.

## The Core Concept
LifeOS is not a tool; it is a **Domain Model**. 

Most productivity enthusiasts are trapped in the ecosystems of specific tools (Notion, Obsidian, Todoist). When these tools change or no longer suit their needs, their underlying structures (how they link ideas, how they track progress) are often lost or require significant migration effort.

LifeOS solves this by separating the **Domain Model** (the source of truth) from the **Adapters** (the interface).

## Key Distinction: Domain vs. Adapter
- **The Domain (LifeOS)**: Contains the logic, relationships, and structures for:
    - **Knowledge**: Zettelkasten, notes, references, learning tracks.
    - **Execution**: Projects, tasks, habits, routines.
    - **Strategy**: Goals, OKRs, intentions.
    - **Planning**: Schedules, reviews, logs.
- **The Adapter (e.g., Notion)**: A projection of the domain model into a specific tool's ecosystem. Notion is just the first supported adapter. If you move to Obsidian later, the core model remains intact; you simply build or use a new adapter.

## Core Values
1. **Integrity**: The domain model must be robust and mathematically/logically sound.
2. **Permanence**: The data structure should be designed for longevity, resisting tool-specific obsolescence.
3. **Independence**: The system's value is realized even if no external tool is connected.

## High-Level Goals (v0)
The first release must demonstrate the power of the adapter pattern by automating the creation of a complete Notion environment. The release must:
- Create a complete Notion workspace.
- Generate all required databases.
- Generate dashboard pages.
- Create relations and rollups.
- Implement formulas.
- Create templates where supported.
- Populate the workspace with sample data.
- Be rerunnable safely (idempotent).

The project architecture must ensure that future adapters (Obsidian, Capacities, Web UI, etc.) can be added without ever modifying the core domain model.
