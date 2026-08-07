# 00 — Preflight: Create People Database

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

Run from `/Users/hismaili/perso/applications/life-os/backend`:

```bash
./mvnw test                                     # unit + slice tier (no engine)
./mvnw verify                                   # full tier incl. Testcontainers ITs (Podman env above exported)
./mvnw test -Dtest=CreatePeopleDatabaseServiceTest#methodName
```

## Blocking issues

**None.** Podman up and verified; both tiers runnable now.

## Feature note (for downstream stages)

People introduces the Notion **`email`** property type. `NotionPropertyType` currently holds `{TITLE, RICH_TEXT, SELECT, DATE, URL}` and the adapter's `propertyConfig` has no `email` case — so this feature includes a **bounded port + adapter extension** (add `EMAIL`; add the `email` config branch emitting `{"type":"email","email":{}}`) plus an adapter contract test, exactly analogous to the `URL` addition (Resources) and `DATE` (Projects). In-scope, not mirror-only. No domain change (`Person` already has `name` + `email`).

**All downstream agents: use ABSOLUTE paths.** Agent CWD may be `backend/`; repo files live under `/Users/hismaili/perso/applications/life-os/`.
