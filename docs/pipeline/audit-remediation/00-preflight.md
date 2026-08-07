# Preflight — audit-remediation

**Detected:** 2026-08-06

## Facts
| Concern | Value |
|---|---|
| Build tool | maven-wrapper (`backend/mvnw`) |
| Build root | `backend/` (contains `backend/pom.xml`, single-module — no `<modules>` element) |
| Modules | single |
| JDK | local JDK is 23.0.2 (OpenJDK); project targets Java 21. `pom.xml` already declares annotation processors explicitly for JDK 23+, so no action needed — build with the local JDK 23 toolchain. |
| Container engine | podman — **up** (podman machine `podman-machine-default` running; `podman info` succeeds). Docker CLI/daemon not available (`docker info` fails). |
| Testcontainers | needs-env (podman is not a drop-in docker socket; `DOCKER_HOST` must point at the podman socket) |

Podman socket resolved via `podman machine inspect`:
```
/var/folders/7j/1y40850161s7gh17s6s9k5rh0000gn/T/podman/podman-machine-default-api.sock
```
This path is specific to the current podman machine instance on this host and may differ on
another machine — re-resolve with the command below if `verify` reports a connection error.

## Commands
> Downstream agents run these verbatim. cd is scoped with subshells. Container-engine env vars
> are only needed for commands that spin up Testcontainers (integration tests / `verify` /
> `@DataJpaTest`, `@SpringBootTest` slices that touch a real DB via Testcontainers).

```bash
# resolve the podman socket fresh if needed (path can change across machine restarts)
podman machine inspect --format '{{.ConnectionInfo.PodmanSocket.Path}}'

# env required for any Testcontainers-backed test run (podman, not docker)
export DOCKER_HOST="unix:///var/folders/7j/1y40850161s7gh17s6s9k5rh0000gn/T/podman/podman-machine-default-api.sock"
export TESTCONTAINERS_RYUK_DISABLED=true
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock

# fast unit tests (no container engine required — safe even if podman/docker is down)
(cd backend && ./mvnw test)

# single test (TDD loop) — substitute the class/method
(cd backend && ./mvnw test -Dtest=<Class>#<method>)

# full verify (unit + integration; requires the Testcontainers env above and a running engine)
DOCKER_HOST="unix:///var/folders/7j/1y40850161s7gh17s6s9k5rh0000gn/T/podman/podman-machine-default-api.sock" \
TESTCONTAINERS_RYUK_DISABLED=true \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
(cd backend && ./mvnw verify)

# unit-test-only fallback that skips integration/failsafe + Testcontainers-dependent slices
# (use this if the container engine is ever confirmed down — safe on any machine state)
(cd backend && ./mvnw test -DskipITs=true)
```

## Blocking issues
- None. Podman is installed and its machine (`podman-machine-default`) is already up
  (`podman info` succeeds), so `./mvnw verify` (Testcontainers-backed ITs and DB slice tests)
  can run **provided** the `DOCKER_HOST`/`TESTCONTAINERS_*` env vars above are exported first —
  Testcontainers does not autodetect podman the way it does Docker. Docker itself remains down
  (`docker info` fails), which is expected/irrelevant since podman is serving as the engine.
- If a future run finds podman also down, re-run `podman machine start` (or
  `podman machine init` first if `podman machine list -q` is empty), poll `podman info` until
  it succeeds, then re-resolve the socket path — it can change across machine restarts.
