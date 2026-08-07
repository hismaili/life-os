# LifeOS Backend

The core engine that provisions and reconciles a structured Notion workspace from the LifeOS
domain model. It ships a **Spring Shell CLI** (and a REST endpoint) that drives the idempotent
"Create Workspace" use case.

> Design source of truth lives in [`../docs/architecture`](../docs/architecture) and the per-feature
> pipelines under [`../docs/pipeline`](../docs/pipeline). This README covers building and running only.

## Stack

Java 21 · Spring Boot 3.3.2 · Spring Shell 3.3.2 · Spring Data JPA + Flyway (PostgreSQL) · Maven.
Lombok and MapStruct run as annotation processors, so a build is required for generated code.

## Prerequisites

- JDK 21+
- A reachable **PostgreSQL** database (schema is owned by Flyway migrations; `ddl-auto=validate`).
- A **Notion integration token** and the **page id** under which the workspace root is created.

Configuration is environment-driven (see `src/main/resources/application.yml`); nothing is hardcoded:

| Variable | Purpose | Default |
|---|---|---|
| `NOTION_TOKEN` | Notion integration secret (required) | _(empty — startup fails if unset)_ |
| `NOTION_ROOT_PARENT_PAGE_ID` | Page the workspace root is created under (required) | _(empty — startup fails if unset)_ |
| `NOTION_VERSION` | Notion API version header | `2026-03-11` |
| `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD` | PostgreSQL connection | Spring Boot defaults |

## Build & test

```bash
./mvnw clean install          # compile + run the full suite
./mvnw test                   # unit tests (surefire)
./mvnw verify                 # + integration tests (failsafe; needs Docker for Testcontainers)
./mvnw test -Dtest=WorkspaceCommandsTest#create_rendersAllStepsOnSuccess   # a single test
```

Integration tests (`*IT`) and the `@DataJpaTest`/`@SpringBootTest` classes use Testcontainers, so a
running Docker daemon is required for `verify`. Plain unit tests do not need Docker.

## Running the CLI

The CLI command lives in `infrastructure.adapter.cli.WorkspaceCommands` and is registered via
`@CommandScan` on `LifeOsApplication`.

**Non-interactive** (pass the command as program arguments — the process exits non-zero if any
provisioning step fails):

```bash
export NOTION_TOKEN=secret_xxx
export NOTION_ROOT_PARENT_PAGE_ID=<notion-page-id>

java -jar target/lifeos-backend-0.0.1-SNAPSHOT.jar \
  workspace create --name "Personal" --person-id 00000000-0000-0000-0000-000000000001

# with sample data
java -jar target/lifeos-backend-0.0.1-SNAPSHOT.jar \
  workspace create --name "Personal" --person-id <uuid> --sample-data true
```

**Interactive** (start with no command; a `shell:>` prompt opens):

```bash
java -jar target/lifeos-backend-0.0.1-SNAPSHOT.jar
shell:> workspace create --name "Personal" --person-id <uuid>
```

### `workspace create` options

| Option | Required | Default | Description |
|---|---|---|---|
| `--name` | yes | — | Workspace name (also the idempotency key together with `--person-id`) |
| `--person-id` | yes | — | Owning person's UUID |
| `--sample-data` | no | `false` | Populate example rows after the structure is built |

The command is **idempotent**: re-running with the same `--name`/`--person-id` verifies and reconciles
the existing Notion structures instead of duplicating them. It prints one line per step
(`TYPE: OUTCOME (detail)`) and exits non-zero if the report contains any `FAILED`/`BLOCKED` step.

### Current provisioning coverage

`workspace create` provisions the Dashboard and the seven core databases (Projects, Tasks, Knowledge,
Habits, Journal, Resources, People). The **Relations, Rollups, Formulas, and Sample Data** phases are
still stubbed (`UnsupportedOperationException`) — the orchestrator reports them as `FAILED`/`BLOCKED`
and the command exits non-zero until those adapter methods are implemented. This is intentional: a
stub never silently reports success (see [`../CLAUDE.md`](../CLAUDE.md), "no silent no-op").

## REST alternative

The same use case is exposed at `POST /api/workspaces` (see
`infrastructure.adapter.web.WorkspaceController`) with a `CreateWorkspaceRequest` JSON body.
