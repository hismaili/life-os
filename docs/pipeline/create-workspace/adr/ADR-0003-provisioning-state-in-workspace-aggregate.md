# ADR-0003: Provisioning state lives inside the Workspace aggregate

## Status
Accepted (Architect stage).

## Context
Reconciliation (ADR-0002) needs a durable record of which Notion resources have been provisioned and their external ids. The current `Workspace` holds only `id` and `name`. We must decide where the provisioning ledger lives in the domain model. DDD guidance: an aggregate is a consistency boundary and should own the state whose invariants it must protect (`docs/architecture/03-Domain-Model.md`; `CLAUDE.md`).

## Options considered
1. **Separate `ProvisioningState` aggregate**, referencing `workspaceId`.
   - (+) Keeps `Workspace` tiny. (−) Two aggregates mutate together on every step, needing cross-aggregate coordination — but ADR-0001 already forbids a spanning transaction, so their consistency would be even harder to reason about. (−) The provisioning ledger has no meaning or lifecycle independent of its Workspace; splitting it violates aggregate cohesion.
2. **Ledger inside the `Workspace` aggregate** as a collection of `ProvisionedResource` value objects, mutated through aggregate methods (`record`, `resource`).
   - (+) One consistency boundary; the invariant "a workspace has at most one resource of each type" is enforced on the root. (+) Matches "reference other aggregates by id, but own your own internals" (`CLAUDE.md`). (−) `Workspace` grows; must keep `@Value` immutability via copy-on-write `record(...)` returning new state.
3. **State only in the persistence adapter** (e.g. a provisioning table), not in the domain.
   - (−) Business rule (idempotency) leaks into infrastructure, contradicting the rich-domain principle and NFR-3. Rejected.

## Decision
Adopt option 2. `Workspace` holds `List<ProvisionedResource>`; identity is minted in `Workspace.create(name, personId)`; the all-args builder is reserved for repository reconstitution (`CLAUDE.md`). The persistence adapter maps this to JPA entities so the domain stays framework-free (NFR-3).

## Consequences
- `WorkspaceRepository.save` persists the whole aggregate including the ledger.
- Optimistic `@Version` on the JPA entity guards concurrent re-runs (`spring-data-jpa`).
- `Workspace` gains `personId`, satisfying FR-3/FR-4 ownership; commands must supply it. Many workspaces per Person are allowed (OQ-2, resolved), so a Person may own several `Workspace` aggregates distinguished by `name`.
