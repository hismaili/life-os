# 05 — Audit Report: Create Projects Database (database slice)

Stage: Auditor (6/6) · Scope: the touched surface of the "Create Projects Database" feature under `backend/src/` — `domain/project/{Project,ProjectStatus}`, `application/port/{NotionPropertyType,PropertyDefinition,DatabaseSpec,ExpectedShape,NotionProvisioningPort}`, `application/usecase/project/CreateProjectsDatabaseService`, the four DB-slice methods of `NotionProvisioningAdapter` (`createDatabase`/`verify`/`findChildByIdentity`/`repairShape` + their private helpers) and the new `adapter/notion/dto/*`.

Verdict: **Approve with comments — 0 Critical, 0 High, no blocking issues.** One Medium error-handling robustness gap (AUD-07) and one Info accepted-scope tracking item (AUD-08) are raised; both are non-blocking. Unchanged `NotionClient` internals (Dashboard AUD-01..05) were not re-audited; this audit confirms the feature does **not** reintroduce any of those risks — in particular the AUD-03 unencoded-path-id class is avoided on every new endpoint.

## 1. Executive summary

The database slice is well-layered and safe on every focus area:

- **Token confinement (Security).** The Bearer token is set once as a default header inside `NotionClient` (`NotionClient.java:32`) and never appears in any new code path. Every `NotionApiException` raised by the new adapter methods is built only from Notion status/`code`/`message` or from non-secret identity data (page id / title / match count) — `NotionProvisioningAdapter.java:75,118-119,179-180`. The service's `ProvisioningStepResult.detail` values are fixed literal strings (`CreateProjectsDatabaseService.java:66-110`); no token, no exception text, no request body flows into them. Logs (`:56-57,64,87`) carry only workspace/notion ids and outcomes.
- **URI encoding (Security / AUD-03 non-regression).** Every new endpoint passes ids and the pagination cursor as URI-template *variables*, not string concatenation: `/databases/{id}`, `/data_sources/{id}`, `/blocks/{id}/children`, `/blocks/{id}/children?start_cursor={cursor}` (`NotionProvisioningAdapter.java:58,73,85,90,142,150,166,167,188,191,195,204`), expanded by `RestClient.uri(path, uriVariables)` (`NotionClient.java:45,49,62`). Spring percent-encodes template variables, so the AUD-03 class does not recur.
- **Injection via titles / property names.** Database title and property names travel only inside Jackson-serialized `Map`/`List` request bodies (`createDatabase` `:131-134`, `repairShape` `:190,203`, `titlePropertyBody` `:233-235`, `propertyConfig` `:259-266`); Jackson JSON-escapes values, so a hostile title/name cannot break out of the JSON context. Nothing user-influenced is concatenated into a request line or JSON string.
- **DTO deserialization.** All new DTOs are immutable `record`s annotated `@JsonIgnoreProperties(ignoreUnknown = true)` with no polymorphic/`@JsonTypeInfo` typing (`dto/Notion*.java`), so unknown-field injection and polymorphic-deserialization gadget risks do not apply.
- **Never-duplicate correctness (pagination).** `findChildByIdentity` (`:161-183`) and `findRootByIdentity` (`:95-122`) accumulate matches across *all* pages (`do…while(cursor != null)`) before evaluating `matches.size() > 1`, so a `>1` match split across pages still trips the ambiguity guard and pagination cannot truncate the dedup check — the AUD-04 fix pattern is correctly applied to the new child-block enumeration.
- **Non-destructive repair.** `repairShape` (`:187-206`) only PATCHes the title when drifted and PATCHes *genuinely-missing* properties by name (`:197-201`); it never sends a property key with a `null` value and never re-sends an existing property's config, so it cannot delete or retype an existing property (matches FR-6b / tech-spec §5.4 step 5). Confirmed non-destructive.
- **Data-source model confined to infrastructure.** `data_sources` / `initial_data_source` / `NotionDataSource*` appear only under `infrastructure/adapter/notion` (`:17,134,149-150,194-195,204` and `dto/NotionDataSource*.java`); the application port (`DatabaseSpec`/`ExpectedShape`/`PropertyDefinition`) and domain (`Project`/`ProjectStatus`) carry no `data_source` concept. Clean/Hexagonal boundary holds.
- **DDD.** `ProjectStatus` is a proper closed enum with a display-label accessor (`ProjectStatus.java`); `Project` is `@Value`-immutable with a private builder and a self-validating `create(...)` factory that mints the `UUID`, rejects blank name / null `workspaceId`, and references other aggregates by `UUID` (`Project.java:22-45`) — no anemic leakage. `PropertyDefinition`/`DatabaseSpec`/`ExpectedShape` are validated compact-constructor records with defensive `List.copyOf` immutability; `PropertyDefinition`'s "options only valid for SELECT" rule is sound (`PropertyDefinition.java:15-17`).
- **No comment pollution.** The only comments in the changed adapter are four `// requires Notion-Version >= 2025-09-03 (data-source model, ADR-0005)` lines (`:124,139,159,185`) — genuine, load-bearing "why" documentation tied to an ADR, not noise or AI-generated filler.

The one material item is a Medium **error-handling robustness** gap: `repairShape`'s first read can return `null` (the shared client maps HTTP 404 → `null`) and is dereferenced without the null guard that the parallel `repairPage` already has.

### Severity counts

| Severity | Count | IDs |
|---|---|---|
| Critical | 0 | — |
| High | 0 | — |
| Medium | 1 | AUD-07 |
| Low | 0 | — |
| Info | 1 | AUD-08 |

**Blocking issues: 0.**

## 2. Findings

### AUD-07 — Medium — Robustness / Error handling — `repairShape` dereferences a possibly-`null` database read without a guard
`backend/src/main/java/com/lifeos/infrastructure/adapter/notion/NotionProvisioningAdapter.java:188-189` (and `:194`), against the client contract at `backend/src/main/java/com/lifeos/infrastructure/adapter/notion/NotionClient.java:51-52`.

`repairShape` opens with `NotionDatabaseResponse current = client.get("/databases/{id}", …)` and immediately calls `titleOf(current)` (`:189`) with no null check. But the shared `NotionClient.get` **deliberately returns `null` on HTTP 404** (`NotionClient.java:51-52`). If the database is deleted between the `verify` that reported `PRESENT_DRIFTED` and this repair (a real TOCTOU window in the reconcile flow), the GET 404s, `current` is `null`, and `titleOf(null)` throws an uncaught `NullPointerException` — aborting `execute()` with a raw NPE instead of a controlled `NotionApiException` / recreate path. The parallel page-slice method `repairPage` guards exactly this case (`:73-76`, `if (current == null) throw new NotionApiException(...)`); `repairShape` drops that established guard, so the two repair methods handle the same 404→null contract inconsistently. `verify` (`:143`) also guards its `null` read, making `repairShape` the sole unguarded consumer. (The subsequent `current.dataSources().get(0)` at `:194` shares the single-data-source assumption documented in ADR-0005/tech-spec §5.4 and is out of this finding's scope.)

Authoritative citation: OWASP ASVS v4.0.3 §V7.4 — Error Handling (an application must handle unexpected conditions in a controlled manner and not fail with uncaught exceptions) — https://owasp.org/www-project-application-security-verification-standard/. Effective Java, Item 54: because a null-returning API forces the caller to defend, the caller must null-check before dereferencing.

Recommended fix: mirror `repairPage` — after the initial `client.get`, `if (current == null) throw new NotionApiException("… database not found during repair (id=" + databaseId + ")")` (or fall through to the create path), before any `titleOf`/`dataSources()` access; add a `MockRestServiceServer` test that a 404 on the repair GET surfaces a `NotionApiException`, not an NPE.

### AUD-08 — Info — Design / Reconcile-completeness (accepted v0 scope) — shape verify/repair is name-presence-only; `Status` option-set and property-type drift are not detected or reconciled
`backend/src/main/java/com/lifeos/infrastructure/adapter/notion/NotionProvisioningAdapter.java:151-155` (`verify` checks only `dataSource.properties().containsKey(required.name())`) and `:197-201` (`repairShape` adds only missing-by-name).

The `Status` property carries a domain-meaningful closed value set (`Planned/Active/On hold/Done`, seeded from `ProjectStatus` at `CreateProjectsDatabaseService.java:121`), and the live data source's option list is available on the response (`dto/NotionPropertyConfig.select.options`), but `verify` ignores both option-set and property *type*. So if a user retypes `Status` from select→text, or edits its options, `verify` still returns `PRESENT_MATCHING` and the drift is neither reported nor repaired. This is **documented, intended v0 scope** — ADR-0006/ADR-0008 (title-only identity, name-only shape check) and tech-spec §5.4 with the explicit test `verify_ignoresExtraUserOptionsAndUnrelatedProperties` (§9.2 case 7, "name-only check, ADR-0006/0008"). Raised as **Info only** to track the reconcile-completeness risk for the one property whose value set encodes domain rules, so it is revisited if/when `Status` value fidelity must be guaranteed. It is the intended trade-off that makes repair provably non-destructive (AUD-07 aside), so it is not a defect against the current spec.

Authoritative citation: this is a documented scope decision, not a standard violation — authority is the feature's own `adr/ADR-0006`, `adr/ADR-0008` and `03-tech-spec.md` §5.4 (which define name-only verification as the accepted contract). No OWASP/Jakarta/Spring requirement is implicated; parallels the Dashboard audit's informational AUD-04 tracking style.

Recommended fix: none required for v0. When option/type fidelity becomes a requirement, extend `verify` to compare `NotionPropertyConfig.type` and (for SELECT) the option-name set, and extend `repairShape` additively — still never issuing a `null`-valued or retyping PATCH.

## 3. Principle scorecard

| Principle | Rating | Justification |
|---|---|---|
| SOLID | Good, one standing caveat | DIP clean: `CreateProjectsDatabaseService` depends only on the `NotionProvisioningPort` / `WorkspaceRepository` interfaces (`:35-37`); the adapter owns its `NotionClient`. **ISP caveat (scorecard-only, no allowlist authority for the SOLID/ISP principle):** `NotionProvisioningPort` remains a fat 13-method port mixing page-, database-, relation-, rollup-, formula- and record-seeding responsibilities, with 5 methods still `UnsupportedOperationException` (`NotionProvisioningAdapter.java:208-231`). This feature **did not worsen** it — it added no methods and converted 4 existing stubs (`createDatabase`/`verify`/`findChildByIdentity`/`repairShape`) to real implementations, improving the implemented/stub ratio. The fat port stays a latent smell to split into per-capability ports as slices land (same observation as the Dashboard audit scorecard). |
| Clean / Hexagonal architecture | Excellent | No Notion/HTTP type and no `data_source` concept leak past the adapter; `data_sources`/`initial_data_source` resolved only in `NotionProvisioningAdapter`; DTOs confined to `adapter.notion.dto`; the application port speaks `DatabaseSpec`/`ExpectedShape`/`PropertyDefinition` only. |
| DDD | Excellent | `ProjectStatus` a proper closed enum; `Project` `@Value`-immutable, private builder, self-validating `create(...)` that mints identity and enforces invariants, aggregates referenced by `UUID`; port records are validated, defensively-copied immutable value objects. No anemic/primitive-obsession leakage. |
| Security | Good | Token confined to one default header, provably absent from new exceptions / `detail` / logs; new endpoint ids + pagination cursor passed as percent-encoded URI variables (AUD-03 class not reintroduced); titles/names JSON-escaped by Jackson (no injection); DTOs `ignoreUnknown`, no polymorphic deserialization. Only non-confidentiality item is AUD-07 (uncaught NPE on a 404 race — availability/robustness, bounded blast radius). |
| DRY / YAGNI | Good | Schema authored once in `projectsSpec()`; `projectsExpectedShape()` reuses `projectsSpec().properties()` verbatim (`:114-127`); `propertyConfig`/`titleOf`/`parentPageId` helpers shared across methods; `propertyConfig` uses an exhaustive enum `switch` (compile-time totality). No speculative abstraction; `Project` is not dead code (consumed by pre-existing `ProjectProgressService`/`GoalAlignmentService`). |

## 4. Blocking issues

**None.** No Critical or High findings; the feature is safe to merge on the audited surface.

Recommended (non-blocking) to bundle in a follow-up:
- **AUD-07 (Medium)** — add the missing `null` guard in `repairShape` so a 404 during repair surfaces a controlled `NotionApiException` (or recreate) instead of an uncaught NPE, matching `repairPage`.
- **AUD-08 (Info)** — tracked only; revisit `Status` option/type drift detection when value fidelity becomes a requirement.

---
Routing: AUD-07 → Implementer (`NotionProvisioningAdapter.repairShape` + non-regression test). AUD-08 → tech-spec/architecture backlog (tracked, no action for v0). ISP caveat → architecture backlog (scorecard note, consistent with Dashboard). No source files were modified by this audit.
