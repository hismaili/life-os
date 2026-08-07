# 02 — Open Questions: Create Projects Database

Status: **RESOLVED (2026-08-05).** All three open questions have recorded human decisions and are baked into `02-architecture.md` and ADR-0005..0008. Two follow-ups are tracked as future work (`02-architecture.md` §11); neither blocks this step or the SME.

---

## Resolved decisions

### OQ-A — Should `Project` gain `status` / `dueDate` fields? → **YES — extend the domain now.** (spec §9.1)

**Decision (human).** Extend the `Project` aggregate as part of this feature; `Status`/`Due Date` are **not** Notion-view-only. The domain change lands with this step (same Implementer pass), designed per `CLAUDE.md` (no primitive obsession, self-validating factory, immutability, reference-by-UUID).

**Baked into the design:**
- New framework-free enum **`ProjectStatus { PLANNED, ACTIVE, ON_HOLD, DONE }`** with `displayName()` (`02-architecture.md` §5.6). Value-set rationale: PARA's project definition (not-started → in-progress → complete) plus `ON_HOLD` for the common paused state; minimal and closed (YAGNI), extensible additively.
- `Project` gains **`ProjectStatus status`** (non-null invariant; defaults to `PLANNED` at create) and **`LocalDate dueDate`** (nullable — a project may have no deadline yet). `Project.create(...)` takes two new params; both fields thread through the private all-args builder (reconstitution seam). `@Value` immutability and UUID references preserved. (§5.6; finding §8.9.)
- **Notion `Status` stays a `select`, not `status`** (ADR-0006, reconciled): full API-manageability under the idempotency mandate, and its **options are seeded from `ProjectStatus.values()`** so the domain enum is the single source of truth for the column's options. `PropertyDefinition` carries the option list additively (ADR-0007; §5.3). Verification stays name-only, so user-added options survive.
- **Sequencing:** the domain change does not gate the DB schema (this step writes no rows) but is **in-scope** and must be built + unit-tested with this feature (`ProjectTest`/`ProjectStatusTest`, §7).

### OQ-B — Are Areas / Goals databases planned? → **DEFER to the Create Relations phase.** (spec §9.2, §9.3)

**Decision (human).** No relation columns on the Projects database now, regardless of the Areas/Goals roadmap. Whether those databases exist, and any Projects↔Area / Projects↔Goal relation, is owned by the future **Create Relations** phase.

**Baked into the design:** this step remains relation-free (FR-14; the created schema contains no relation-typed property). `Project.areaId`/`goalId` already model the links by UUID for when relations are established later. Tracked follow-up: `02-architecture.md` §11.2.

### OQ-C — Database title, and populating `docs/productivity/*`. → **Title `"Projects"` is FINAL; docs populated LATER.** (spec §9.4, §9.5)

**Decision (human).** The database title is the constant **`"Projects"`** (final). Populating `docs/productivity/PARA.md` & `GTD.md` so future specs cite in-repo sources is a **tracked, non-blocking follow-up**.

**Baked into the design:** title constant `"Projects"` is the single source of truth feeding create/verify/identity (`02-architecture.md` §5.1; ADR-0008 — a child database needs no workspace-name disambiguation because identity is already scoped by the unique Dashboard parent). Tracked follow-up: `02-architecture.md` §11.1.

---

## Architect-resolved (not escalated) — recorded for traceability

- **Database identity heuristic (spec §7 `[ASSUMPTION]`)** → parent-page `child_database` enumeration by title, `> 1` ⇒ `FAILED` (ADR-0008) — stronger than the Dashboard's search-based approach because the parent is known and block children are index-consistent (and `/v1/search` cannot filter to `database` objects under Notion-Version `2025-09-03`).
- **Notion data-source model** → `POST /v1/databases` + `initial_data_source`; schema on the data source; ledger stores the database id (ADR-0005).
- **Status property type** → `select`, options seeded from `ProjectStatus` (ADR-0006).
- **Port shape sufficiency (spec §7/§8; Create Dashboard §8 anticipated finding)** → `DatabaseSpec`/`ExpectedShape` refined to a typed schema; `findChildByIdentity` gains `ExpectedShape` (ADR-0007; findings `02-architecture.md` §8). Architect decisions via ADR, not human escalations.
</content>
