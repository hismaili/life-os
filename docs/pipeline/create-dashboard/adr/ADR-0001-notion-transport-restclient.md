# ADR-0001: Notion transport is a Spring RestClient against the REST API (no Notion Java SDK)

## Status
Accepted (Architect stage, Create Dashboard branch). First concrete Notion-facing decision in the project. Applies to `NotionProvisioningAdapter` and the new adapter-internal `NotionClient`; the application layer is unaffected (it depends only on `NotionProvisioningPort`, Create Workspace ADR-0004).

## Context
Create Dashboard is the first step to actually call Notion. The adapter must create, retrieve, update, and search pages, send the required `Authorization: Bearer` and `Notion-Version` headers, and handle `429`/`529` rate limiting. We must pick how HTTP is performed, testably and without a live Notion in CI. The project is Spring Boot 3.3.2, Java 21, and already depends on `spring-boot-starter-web` (which supplies `RestClient`).

## Options considered
1. **A community Notion Java SDK** (e.g. `notion-sdk-jvm`).
   - (+) Typed models for Notion objects; less hand-rolled JSON.
   - (−) **Notion publishes no official Java SDK** — only a JavaScript SDK is first-party ([Notion — Authentication](https://developers.notion.com/reference/authentication) shows only JS/SDK examples; there is no Java SDK in the reference). A community SDK is **not** an authoritative/allowlisted dependency, adds supply-chain and maintenance risk, and typically lags Notion's dated API versions.
   - (−) Couples our adapter to a third party's object model and release cadence for a surface we use narrowly (pages: create/get/patch/search).
2. **Spring `RestClient` against the Notion REST API directly** (chosen).
   - (+) `RestClient` is "a synchronous HTTP client that provides a fluent API," the recommended synchronous client in Spring Framework 6.1+, with `RestTemplate` deprecated as of Framework 7.0 ([Spring Framework Reference — REST Clients](https://docs.spring.io/spring-framework/reference/integration/rest-clients.html)).
   - (+) Already on the classpath via `spring-boot-starter-web`; no new dependency, no reactive stack.
   - (+) First-class, no-live-server testing via `MockRestServiceServer`, which supports `RestClient` ([Spring Framework Reference — Client-side REST test support](https://docs.spring.io/spring-framework/reference/testing/spring-mvc-test-client.html)) — see ADR-0003.
   - (−) We map Notion JSON to/from Java by hand (small surface: page id, title, `is_archived`/`in_trash`, search hits).
3. **`RestTemplate` or `WebClient`.**
   - (−) `RestTemplate` is deprecated as of Spring Framework 7.0 (same reference as option 2) — do not start new code on it.
   - (−) `WebClient` pulls in the reactive WebFlux stack for what is a simple blocking call sequence; the reference reserves `WebClient` for "asynchronous and streaming scenarios."

## Decision
Use a **Spring `RestClient`**, wrapped by an adapter-internal `NotionClient`, to call the Notion REST API directly. Configure one `RestClient` with base URL `https://api.notion.com/v1` and default headers `Authorization: Bearer <token>` and `Notion-Version: <pinned>`, both sourced from `NotionProperties` (single process-level token, Create Workspace OQ-7; version pinned per [Notion — Authentication/versioning](https://developers.notion.com/reference/authentication)). `NotionClient` centralises error translation (Notion `code`/`message` → `NotionApiException`, never echoing the token) and `429`/`529` backoff honouring the integer `Retry-After` header, given Notion's documented ~3 req/s-per-connection limit ([Notion — Request limits](https://developers.notion.com/reference/request-limits)). No Notion SDK dependency is added.

## Consequences
- No third-party Notion dependency; `pom.xml` is unchanged (RestClient ships with `spring-boot-starter-web`).
- Hand-written JSON mapping for a deliberately small page surface (create/get/patch/search) lives entirely in the adapter; nothing Notion-shaped leaks past `NotionProvisioningPort` (Create Workspace ADR-0004 preserved).
- Transport is unit-testable with `MockRestServiceServer` (ADR-0003) — no live token, no network in CI.
- The pinned `Notion-Version` becomes a deliberate config upgrade rather than an implicit one.
- If Notion later ships an official Java SDK, this adapter can be swapped behind the unchanged port with no application-layer impact.

## Post-audit refinement (2026-08-05)

Audit finding AUD-01 noted that Spring Boot applies **no** default connect/read timeout to the auto-configured `RestClient.Builder`, so an unbounded outbound call could hang a provisioning thread. Refinement: the Notion `RestClient` is now built with a `ClientHttpRequestFactory` carrying finite connect (5s) and read (20s) timeouts, applied via a `RestClientCustomizer` bean (`NotionClientConfiguration`) rather than inside `NotionClient` — so `MockRestServiceServer`-bound tests still install their own mock factory. The `Retry-After` backoff is also clamped (AUD-02) and page ids flow as encoded URI variables (AUD-03). The transport decision itself is unchanged; these are hardening details on the same RestClient.
