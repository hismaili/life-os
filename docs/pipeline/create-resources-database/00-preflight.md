# 00 — Preflight: Create Resources Database

> Written by the orchestrator, reusing the toolchain facts verified end-to-end this session on the same repo/module (Podman backend, both test tiers green). Deterministic for this repo.

## Build toolchain

| Item | Value |
|---|---|
| Build tool | Maven (via wrapper `backend/mvnw`) |
| Build root / module | `backend/` (single module; `backend/pom.xml`) |
| Language level | Java 21; local JDK OpenJDK 23.0.2 |
| Framework | Spring Boot 3.3.2, Spring Shell 3.3.2 |
| Annotation processors | Lombok, MapStruct 1.5.5 |
| Testcontainers | 1.20.1; `maven-failsafe-plugin` wired (`*IT` run under `verify`) |

## Container engine

| Item | Value |
|---|---|
| Docker | No `docker`/`colima` CLI on this machine |
| **Podman** | **5.6.2, machine running** — Testcontainers backend, verified working this session. |

### Testcontainers on Podman — env for `verify` (set per-invocation, not persisted)

```bash
export DOCKER_HOST="unix://$(podman machine inspect --format '{{.ConnectionInfo.PodmanSocket.Path}}')"
export TESTCONTAINERS_RYUK_DISABLED=true
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
```

## Commands

Run from `backend/`:

```bash
./mvnw test                                        # unit + slice tier (no engine)
./mvnw verify                                      # full tier incl. Testcontainers ITs (Podman env above exported)
./mvnw test -Dtest=CreateResourcesDatabaseServiceTest#methodName
```

## Blocking issues

**None.** Podman up and verified; both tiers runnable now.

## Feature note (for downstream stages)

Resources introduces the Notion **`url`** property type. `NotionPropertyType` currently holds only `{TITLE, RICH_TEXT, SELECT, DATE}` and the adapter's `propertyConfig` has no `url` case — so this feature includes a **bounded port + adapter extension** (add `URL`; add the `url` config branch) plus an adapter contract test, analogous to the earlier `DATE` addition. This is in-scope, not a mirror-only change.
