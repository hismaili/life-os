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
- A **Notion integration token** and the **page id** under which the workspace root is created —
  supplied **per call** (BYOK), not as server-side config (see below).

Everything except the Notion credentials is environment-driven (see
`src/main/resources/application.yml`):

| Variable | Purpose | Default |
|---|---|---|
| `NOTION_VERSION` | Notion API version header | `2026-03-11` |
| `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD` | PostgreSQL connection | Spring Boot defaults |

**Notion token is BYOK, not env-configured.** `NOTION_TOKEN` and `NOTION_ROOT_PARENT_PAGE_ID` are
not read from the environment or any config file — the caller passes them on every `workspace
create` invocation (CLI options or REST body fields below). The server never stores them: they
live only in a call-scoped holder (`NotionCredentialsHolder`) for the duration of that one request
and are cleared immediately after, whether it succeeds or fails.

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
java -jar target/lifeos-backend-0.0.1-SNAPSHOT.jar \
  workspace create --name "Personal" --person-id 00000000-0000-0000-0000-000000000001 \
  --notion-token secret_xxx --notion-root-parent-page-id <notion-page-id>

# with sample data
java -jar target/lifeos-backend-0.0.1-SNAPSHOT.jar \
  workspace create --name "Personal" --person-id <uuid> --sample-data true \
  --notion-token secret_xxx --notion-root-parent-page-id <notion-page-id>
```

**Interactive** (start with no command; a `shell:>` prompt opens):

```bash
java -jar target/lifeos-backend-0.0.1-SNAPSHOT.jar
shell:> workspace create --name "Personal" --person-id <uuid> --notion-token secret_xxx --notion-root-parent-page-id <notion-page-id>
```

### `workspace create` options

| Option | Required | Default | Description |
|---|---|---|---|
| `--name` | yes | — | Workspace name (also the idempotency key together with `--person-id`) |
| `--person-id` | yes | — | Owning person's UUID |
| `--sample-data` | no | `false` | Populate example rows after the structure is built |
| `--notion-token` | yes | — | Notion integration token (BYOK — never read from environment or persisted) |
| `--notion-root-parent-page-id` | yes | — | Notion page the workspace root is created under |

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
`infrastructure.adapter.web.WorkspaceController`) with a `CreateWorkspaceRequest` JSON body:

```json
{
  "name": "Personal",
  "personId": "00000000-0000-0000-0000-000000000001",
  "sampleData": false,
  "notionToken": "secret_xxx",
  "notionRootParentPageId": "<notion-page-id>"
}
```

`notionToken` and `notionRootParentPageId` are BYOK fields, same as the CLI options — required on
every request, never stored server-side.

## Containers (Podman/Docker)

`backend/Dockerfile` is a multi-stage build (Maven build stage + JRE runtime stage); the runtime
image runs the app headless (`SPRING_SHELL_INTERACTIVE_ENABLED=false`) so it serves the REST API
instead of opening an interactive shell prompt.

The root-level `docker-compose.yml` runs the app plus a disposable Postgres, using the compose
spec only (no Docker-specific extensions) so it works with either Podman or Docker:

```bash
cp .env.example .env
podman-compose up --build
```

The Notion token stays BYOK inside containers too — pass it to `workspace create` at call time (CLI
option or REST body), not through `.env`.

`docker-compose.prod.yml` is the CD counterpart: a standalone file that pulls a pre-built image
(`IMAGE=ghcr.io/<owner>/lifeos-backend:<tag>`) rather than building from source — see
`.github/workflows/cd.yml`.

## CI/CD

- `.github/workflows/ci.yml` — runs on every push/PR to `main`: `./mvnw verify` (unit +
  Testcontainers integration tests), then a build-only validation of `backend/Dockerfile`.
- `.github/workflows/cd.yml` — **placeholder**, manual-dispatch only until an Oracle Cloud VM and
  its SSH/registry secrets are configured. See the workflow file's header comment for the exact
  secrets it expects.
