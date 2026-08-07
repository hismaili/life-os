# Audit Report — Notion Adapter

Scope: `backend/src/main/java/com/lifeos/infrastructure/adapter/notion/**` (adapter, HTTP client, config, properties, exception, DTOs) plus the `application.port.*` contracts for context. Standalone audit; no code modified, nothing staged.

## 1. Summary

| Severity | Count |
|---|---|
| Critical | 0 |
| High | 1 |
| Medium | 1 |
| Low | 5 |

Blocking issues (Critical + High): **1** — `AUD-001`.

Headline: the token-in-exception discipline is solid and test-enforced across every error path I traced, and SSRF/path-traversal is well mitigated by fixed `baseUrl` + URI-template encoding. The one blocking gap is a *latent* secret-exposure vector — the `NotionProperties` record's auto-generated `toString()` prints the raw token. The rest are reliability/robustness/quality nits.

## 2. Findings

### AUD-001 — High — Security (secret exposure)
`NotionProperties.java:9-13`. `NotionProperties` is a Java `record` holding the Notion API token as its first component. Records synthesize a `toString()` that includes **all** components, so the raw bearer token is emitted whenever the object is string-interpolated — a stray `log.debug("props {}", properties)`, a `@ConfigurationProperties` binding/validation error that echoes the bound target, or any exception that references the properties object. The adapter holds the app's single most sensitive secret, and the fix is trivial, so this should not merge as-is even though no *current* call site logs it.
Citation: OWASP Cheat Sheet Series — Logging Cheat Sheet ("Data to exclude": authentication secrets / encryption keys must never be logged); OWASP ASVS 5.0 V7 (Error Handling & Logging) — sensitive data must be excluded from logs and error output.
Fix (one line): override `toString()` (or `@ToString.Exclude`-equivalent) to redact `token`, e.g. return a masked form, so the secret can never be serialized by accident.

### AUD-002 — Medium — Robustness / persistence-of-integration (unguarded external response)
`NotionProvisioningAdapter.java:149` and `:197` call `response.dataSources().get(0)` with no null/empty guard, and `:150-152` / `:198-201` then dereference `dataSource.properties()` from a `client.get(...)` that returns `null` on 404. A database whose `data_sources` array is absent/empty, or a data-source lookup that 404s, yields an unhandled `NullPointerException` / `IndexOutOfBoundsException` surfaced to the caller rather than a domain-meaningful `NotionApiException`. `NotionDataSourceResponse.properties` and `NotionDatabaseResponse.dataSources` are untrusted boundary data and must be validated before use.
Citation: Effective Java, Item 54: Return empty collections or arrays, not nulls (client must defend against null/empty at the trust boundary); OWASP ASVS 5.0 V5 (Validation — validate data received from external services).
Fix (one line): guard for null/empty `dataSources` and a null data-source response, throwing a descriptive `NotionApiException` instead of dereferencing.

### AUD-003 — Low — Robustness (unbounded pagination / null deref)
`NotionProvisioningAdapter.java:96-112` (`findRootByIdentity`) and `:162-173` (`findChildByIdentity`) loop `while (cursor != null)` driven purely by the API's `has_more`/`next_cursor`, and call `response.results().stream()` without a null check. A misbehaving or hostile API response (repeating cursor, or `results: null`) causes an unbounded loop with an ever-growing `matches` list (memory exhaustion) or an NPE.
Citation: OWASP Cheat Sheet Series — Denial of Service Cheat Sheet (bound loops driven by external input; cap resource consumption).
Fix (one line): cap the page count / total matches and null-default `results()` to an empty list.

### AUD-004 — Low — Security (SSRF defense-in-depth)
`NotionProvisioningAdapter.java:58,73,142,150,166-167,188,198` interpolate `pageId` / `parentPageId` / `databaseId` / `cursor` into request paths and query strings. SSRF/path-traversal is **already mitigated** — `baseUrl` is fixed to `https://api.notion.com/v1` (`NotionClient.java:31`) and `RestClient`'s default URI-template expansion (`EncodingMode.TEMPLATE_AND_VALUES`) percent-encodes variable values including `/`. The residual gap is that these identifiers are never validated as well-formed Notion IDs before use, so this rests entirely on the framework's encoding behavior.
Citation: OWASP Cheat Sheet Series — Server-Side Request Forgery Prevention Cheat Sheet (validate/allowlist inputs used to build outbound requests).
Fix (one line): validate `pageId`/`parentPageId`/`databaseId` against the expected Notion-ID format at the boundary as defense-in-depth.

### AUD-005 — Low — Design (over-broad configuration scope)
`NotionClientConfiguration.java:11-14` registers a `RestClientCustomizer` bean. Per Spring Boot, all `RestClientCustomizer` beans are applied to *every* auto-configured `RestClient.Builder` in the context — so the Notion-specific 5s/20s connect/read timeouts silently become the default for any future `RestClient` built elsewhere in the app, coupling unrelated integrations to Notion's tuning.
Citation: Spring Boot Reference — Calling REST Services (RestClient / RestClientCustomizer applies to the auto-configured builder) (docs.spring.io/spring-boot/reference/io/rest-client.html).
Fix (one line): apply the timeout `requestFactory` directly on the builder inside `NotionClient`'s constructor instead of a global customizer.

### AUD-006 — Low — Quality (variable shadowing)
`NotionProvisioningAdapter.java:127` declares a local `Map<String, Object> properties` that shadows the `private final NotionProperties properties` field (`:39`). No bug today, but any future reference to the field inside `createDatabase` (e.g. `properties.rootParentPageId()`) would silently bind to the map — an error-prone trap.
Citation: Effective Java, Item 57: Minimize the scope of local variables (avoid names that shadow fields).
Fix (one line): rename the local to `propertyConfigs`.

### AUD-007 — Low — Quality (magic status literals)
`NotionClient.java:51` (`404`), `:84` (`429`, `529`) use bare numeric HTTP-status literals inline. `404`/`429` have named constants in `org.springframework.http.HttpStatus`; `529` is non-standard and warrants a named constant with a comment.
Citation: Spring Framework — `org.springframework.http.HttpStatus` (canonical named status constants).
Fix (one line): replace `404`/`429` with `HttpStatus.NOT_FOUND.value()` / `TOO_MANY_REQUESTS.value()` and name `529` as a private constant.

### Design observations (not raised as cited findings — `[ASSUMPTION]`, no allowlist authority mandates them)
- **Retry scope.** `executeWithRetry` (`NotionClient.java:65-80`) retries only `429`/`529`; transient `5xx` (500/502/503/504) and network `ResourceAccessException` are not retried. Test `client_mapsGenericErrorStatusToNotionApiExceptionWithCodeAndMessage` shows 500→exception is *intended*, so this is a deliberate design choice, not a defect. Retrying `5xx`/connect-resets and adding backoff jitter would improve resilience — a preference, not an authority-backed requirement.
- **Non-idempotent POST retry.** A `429` retry of `POST /pages` / `POST /databases` is safe because `429` is pre-processing; duplicate-creation risk is further covered by the app-layer find-by-identity idempotency. Worth a note only.
- **Worst-case blocking.** With `MAX_BACKOFF_SECONDS=30` and 2 inter-attempt sleeps, a caller thread can block ~60s of backoff plus per-attempt read timeouts; acceptable for a CLI, worth revisiting if invoked on a web request thread.

### Categories with no material findings
- **Injection (SQL/command):** N/A — no datastore/OS interaction in this adapter; JSON bodies built via typed `Map`/DTO, not string concatenation.
- **AuthN/AuthZ, password storage, CORS/CSRF:** N/A for an outbound API client (no inbound auth surface here).
- **Token in exceptions/logs:** verified clean across all four throw sites in `NotionClient` (`:73`, `:89`, `:97`, `:121`) and the adapter's `NotionApiException`s — none include the header or token; enforced by `client_neverLeaksTokenInExceptionMessage` (`NotionProvisioningAdapterTest.java:297`). Only the record `toString` gap remains (AUD-001).
- **Thread interruption:** handled correctly — `NotionClient.java:116-123` restores the interrupt flag (`Thread.currentThread().interrupt()`) before wrapping and rethrowing.
- **Timeouts:** connect (5s) and read (20s) are configured and regression-tested (`NotionClientTest.java:16`).
- **Comment pollution / AI-generated comments:** none found; the `// requires Notion-Version >= 2025-09-03 (... ADR-0005)` comments are load-bearing and cite an ADR.
- **`UnsupportedOperationException` stubs:** compliant with the workspace convention "no silent no-op use cases — an unimplemented use case must fail explicitly" (CLAUDE.md). Not a defect.
- **Persistence/JPA (N+1, `@Transactional`, entity-as-DTO):** N/A — HTTP adapter, no persistence; DTOs are separate Jackson records, no domain-entity leakage.

## 3. Principle scorecard

| Principle | Rating | Justification |
|---|---|---|
| SOLID | Good, minor | Clean DIP (adapter implements `NotionProvisioningPort`, depends on it). Note: `NotionProvisioningPort` bundles 14 methods across provisioning/relation/rollup/record concerns (ISP tension), forcing 5 `UnsupportedOperationException` stubs — acceptable per project convention but a candidate for segregation. |
| Clean Architecture | Excellent | Correct dependency direction: adapter (outer) depends on `application.port` (inner) and domain types; no inner→outer leak; DTOs confined to the adapter package. |
| DDD | Good | Notion wire DTOs are kept distinct from domain/port value types; mapping stays inside the adapter; identity reconciliation lives at the boundary. |
| Security | Good, 1 blocker | Fixed baseURL + URI-template encoding neutralize SSRF; token never leaks into exceptions (test-enforced). Blocked only by the latent `record` `toString` token exposure (AUD-001); AUD-004 is defense-in-depth. |
| DRY / YAGNI | Good | Retry/backoff/error handling centralized in `NotionClient`; no premature abstraction. Minor: repeated inline `Map.of("text", Map.of("content", ...))` title-body construction. |

## 4. Blocking issues

- **AUD-001 (High)** — `NotionProperties` record `toString()` exposes the raw Notion token. Must be redacted before merge.

No Critical issues. All Medium/Low items are non-blocking but should be scheduled; AUD-002 is the strongest reliability candidate to fix next.
