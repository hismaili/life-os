# ADR-0003: Test the Notion integration without a live Notion

## Status
Accepted (Architect stage, Create Dashboard branch). Governs the test tiers for `CreateDashboardService` and the `NotionProvisioningAdapter` Dashboard slice.

## Context
This is the first step that talks to an external SaaS. CI must not require a live Notion token or network egress (NFR-3, NFR-6), yet must prove: (a) the service's verify/create/repair/adopt/record **sequencing** (the §4.2 decision table), and (b) the adapter's **transport contract** (correct endpoints/verbs/headers/JSON, and `429`/`529` handling). Docker/Podman + Testcontainers are available.

## Options considered
1. **Live Notion in CI.** (−) Needs a real token (secret in CI), makes tests non-hermetic/flaky, and mutates a real Notion workspace. Rejected.
2. **WireMock (standalone or Testcontainers) as a Notion stand-in for everything.**
   - (+) High-fidelity HTTP simulation, network conditions.
   - (−) Heavier than needed for the transport contract; a full Notion behaviour model is a lot of stub upkeep for a one-page surface. Keep in reserve for later multi-step end-to-end Notion simulation.
3. **Tiered: `MockRestServiceServer` for the adapter contract + an in-memory fake `NotionProvisioningPort` for service/wiring tiers** (chosen).
   - (+) `MockRestServiceServer` binds directly to the adapter's `RestClient.Builder` and lets tests "set up expected requests and define stub responses … test client code in isolation without running an actual server" ([Spring Framework Reference — Client-side REST test support](https://docs.spring.io/spring-framework/reference/testing/spring-mvc-test-client.html)). Exactly matches "assert we send the right request and decode the right response."
   - (+) The service logic is pure port-orchestration → plain Mockito unit tests, the narrowest sufficient type ([spring-testing]).
   - (+) A hand-written fake port (a `Map`-backed create/verify/adopt model) drives `@SpringBootTest` wiring + Testcontainers-Postgres ledger tests with zero Notion calls.
   - (−) `MockRestServiceServer` does not exercise a real socket / timeout behaviour — acceptable; that fidelity is deferred to an optional WireMock tier.

## Decision
Three tiers, no live Notion, no token in CI:
1. **Service unit tests** (`CreateDashboardServiceTest`, plain Mockito): one test per §4.2 decision-table row + the failure/no-op-scope paths. Mock `NotionProvisioningPort`, `WorkspaceRepository`, `WorkspaceLedgerWriter`; assert outcomes and interaction/no-interaction (e.g. `verifyNoInteractions(ledger)` on the Notion-failure path; no database-method calls for FR-12).
2. **Adapter contract tests** (`NotionProvisioningAdapterTest`, `MockRestServiceServer` bound to the `RestClient.Builder`): assert request path/verb, `Authorization: Bearer` + `Notion-Version` headers, request JSON, and response decoding for `createRootPage`/`verify`(200/404/trashed/drifted)/`repairShape`/`findRootByIdentity`, plus `429`→`Retry-After`→retry and token-never-leaked-in-exception.
3. **Wiring IT** (optional `@SpringBootTest` + Testcontainers Postgres): `NotionProvisioningPort` supplied by an in-memory fake via `@TestConfiguration`; proves controller/orchestrator → `CreateDashboardService` → `WorkspaceLedgerWriter` → JPA is wired and that a re-run converges to a single `DASHBOARD` ledger row (FR-11). Integration classes end in `IT` so Failsafe runs them under `./mvnw verify` ([spring-testing]).

WireMock remains available and is the chosen tool **only** if/when a later branch needs realistic multi-call Notion behaviour or network-fault injection; it is not used for this step.

## Consequences
- CI is hermetic: no token, no egress; unit + slice tiers run under `./mvnw test`, the IT under `./mvnw verify`.
- The transport contract is pinned by tests, so a wrong endpoint/header/JSON change fails fast.
- The fake port doubles as documentation of the port's expected semantics for the next step's implementer.
- Real socket/timeout fidelity is intentionally out of scope here (deferred to a possible WireMock tier), consistent with YAGNI.
