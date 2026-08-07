# 00 — Preflight: Create Projects Database

> Written 2026-08-05 by the orchestrator, reusing the toolchain facts verified end-to-end this session on the same repo/module (Podman backend, both test tiers green — see `../create-dashboard/00-preflight.md`). Deterministic for this repo; no re-derivation needed.

## Build toolchain

| Item | Value |
|---|---|
| Build tool | Maven (via wrapper `backend/mvnw`) |
| Build root / module | `backend/` (single module; `backend/pom.xml`) |
| Language level | Java 21 (`<java.version>21</java.version>`) |
| Local JDK observed | OpenJDK 23.0.2 — runs the Java 21 target fine |
| Framework | Spring Boot 3.3.2, Spring Shell 3.3.2 |
| Annotation processors | Lombok, MapStruct 1.5.5 (build required for generated code) |
| Testcontainers | 1.20.1 (BOM-managed); `maven-failsafe-plugin` wired (`*IT` run under `verify`) |

## Container engine

| Item | Value |
|---|---|
| Docker | No `docker`/`colima` CLI on this machine |
| **Podman** | **5.6.2, machine running** — used as the Testcontainers backend. Verified working this session: `CreateDashboardServiceIT` + `CreateWorkspaceIT` ran green against it. |

### Testcontainers on Podman — env for `verify` (set per-invocation, not persisted)

```bash
export DOCKER_HOST="unix://$(podman machine inspect --format '{{.ConnectionInfo.PodmanSocket.Path}}')"
export TESTCONTAINERS_RYUK_DISABLED=true
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
```

## Commands

Run from `backend/`:

```bash
# Unit + slice tier (no container engine needed)
./mvnw test

# Full verify INCLUDING Testcontainers ITs (Podman env above must be exported)
./mvnw verify

# Single test
./mvnw test -Dtest=CreateProjectsDatabaseServiceTest#methodName
```

## Blocking issues

**None.** Container engine (Podman) is up and verified. Both tiers runnable now.
