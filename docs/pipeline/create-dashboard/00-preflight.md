# 00 — Preflight: Create Dashboard

> Reconstructed 2026-08-05 during the pipeline **resume**. The Create Dashboard pipeline was executed to completion without a persisted preflight file; this records the toolchain as it actually stands in the target repo now, so downstream/CI stages have the canonical command block.

## Build toolchain

| Item | Value |
|---|---|
| Build tool | Maven (via wrapper `backend/mvnw`) |
| Build root / module | `backend/` (single module; `backend/pom.xml`) |
| Language level | Java 21 (`<java.version>21</java.version>`) |
| Local JDK observed | OpenJDK 23.0.2 — runs the Java 21 target fine; keep an eye on it if bytecode-level tooling is added |
| Framework | Spring Boot 3.3.2, Spring Shell 3.3.2 |
| Annotation processors | Lombok, MapStruct 1.5.5 (build required for generated code) |
| Testcontainers | 1.20.1 (BOM-managed) |

## Container engine

| Item | Value |
|---|---|
| Docker | No `docker`/`colima` on this machine |
| **Podman** | **5.6.2, machine running** — used as the Testcontainers backend (see env below). No `docker` CLI needed; Testcontainers talks to the Docker-compatible socket directly. |
| Impact | Both tiers run: unit tier needs no engine; the **integration tier** (`*IT`, Testcontainers-Postgres) runs against Podman and was confirmed green 2026-08-05 (5/5). |

### Testcontainers on Podman (env for `verify`)

Set for the shell invocation only (not persisted to profile or repo):

```bash
export DOCKER_HOST="unix://$(podman machine inspect --format '{{.ConnectionInfo.PodmanSocket.Path}}')"
export TESTCONTAINERS_RYUK_DISABLED=true                         # Ryuk unreliable with Podman
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
```

## Commands

Run from `backend/`:

```bash
# Unit + slice tier (no container engine needed — uses embedded Postgres for @DataJpaTest)
./mvnw test

# Full verify INCLUDING Testcontainers integration tests (requires Docker/engine UP)
TESTCONTAINERS_RYUK_DISABLED=true ./mvnw verify

# Single test
./mvnw test -Dtest=CreateDashboardServiceTest#methodName
```

`TESTCONTAINERS_RYUK_DISABLED=true` is required in this environment (Ryuk resource-reaper container is unreliable here).

## Integration test inventory (Testcontainers — need engine UP)

- `com.lifeos.application.usecase.workspace.CreateDashboardServiceIT` (3 scenarios, incl. multi-run convergence)
- `com.lifeos.infrastructure.adapter.web.CreateWorkspaceIT` (2 scenarios, pre-existing)

`JpaWorkspaceRepositoryTest` is a `@DataJpaTest` slice that uses an embedded Postgres and runs in the unit tier (no Docker).

## Verification status

✅ **Both tiers green (2026-08-05).** With Podman as the Testcontainers backend (env above), `./mvnw verify` → **BUILD SUCCESS**: unit **133/133** and failsafe integration **5/5** (`CreateDashboardServiceIT` 3 + `CreateWorkspaceIT` 2). The ITs pulled and ran their Postgres container against Podman with no issue. No Docker/Colima required.
