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
spec only (no Docker-specific extensions) so it works with either Podman or Docker. Postgres auth
is TLS client-certificate only (see `postgres/pg_hba.conf`) — there is no password fallback, so
generate a dev CA/server/client cert chain once before bringing the stack up:

```bash
cp .env.example .env
./scripts/generate-dev-certs.sh
podman-compose up --build
```

Re-run `generate-dev-certs.sh` any time to rotate the certs (it regenerates the whole chain from
scratch). Changing `POSTGRES_USER` also requires re-running it, since Postgres maps the client
cert's CN directly to the role — see the script's header comment for details.

The Notion token stays BYOK inside containers too — pass it to `workspace create` at call time (CLI
option or REST body), not through `.env`.

`docker-compose.prod.yml` is the CD counterpart: a standalone file that pulls a pre-built image
(`IMAGE=ghcr.io/<owner>/lifeos-backend:<tag>`) rather than building from source, and expects a
*real* (non-dev) cert chain at `CERTS_DIR` — see `.github/workflows/cd.yml`.

## CI/CD

- `.github/workflows/ci.yml` — runs on every push/PR to `main`: `./mvnw verify` (unit +
  Testcontainers integration tests), then a build-only validation of `backend/Dockerfile`.
- `.github/workflows/cd.yml` — **placeholder**, manual-dispatch only until an Oracle Cloud VM and
  its SSH/registry secrets are configured. See the workflow file's header comment for the exact
  secrets it expects.

## Desktop app

The CLI can be packaged as an installable double-clickable app via
[jpackage](https://docs.oracle.com/en/java/javase/21/jpackage/) (bundled with the JDK, no extra
tooling). Double-clicking the installed app opens a terminal window running the same Spring Shell
`shell:>` prompt as `java -jar` — packaging changes nothing about how the CLI behaves or what it
needs (a reachable Postgres via `SPRING_DATASOURCE_*`, a Notion token per `workspace create` call —
see above); it only changes how you launch it.

**macOS** — built and verified locally on real hardware:

```bash
./desktop/macos/package.sh
```

Produces `desktop/build/macos/LifeOS.app` and `LifeOS.dmg`. jpackage's native launcher has no TTY
when double-clicked from Finder, so the app's actual entry point is a `.command` file (macOS's
built-in "run this shell script in Terminal.app" file type — no special permission needed, unlike
scripting Terminal via AppleScript). See the script's comments for the exact mechanics. The build
is unsigned: right-click → Open once to get past Gatekeeper's "unidentified developer" warning.

**Windows (.msi) and Linux (.deb)** — configured in `.github/workflows/package-desktop.yml`
(manual dispatch) but **not yet verified on real hardware**, unlike the macOS path above. Windows
uses `--win-console` for native terminal behavior; Linux best-effort-patches the generated
`.desktop` launcher to set `Terminal=true`. Expect to debug on first run — see the workflow's
per-job comments for the specific known risk in each.
