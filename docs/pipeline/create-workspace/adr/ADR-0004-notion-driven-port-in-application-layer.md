# ADR-0004: Notion driven port defined in the application layer

## Status
Accepted (Architect stage).

## Context
Provisioning steps must call Notion through an interface (driven/secondary port) so the core stays adapter-agnostic (`01-Vision.md`: "Notion is just the first supported adapter"). `CLAUDE.md` states repository ports live in the domain and that "`infrastructure.port` holds additional port interfaces." Placing a port that the **application** depends on inside `infrastructure` would make an inner layer (application) depend on an outer one (infrastructure), violating the strict inward-only dependency rule that the same document mandates.

## Options considered
1. **Define `NotionProvisioningPort` in `infrastructure.port`** (literal reading of CLAUDE.md).
   - (+) Matches the named package. (−) Application services would import an `infrastructure.*` type, breaking "inner layers never depend on outer ones" (`CLAUDE.md`; hexagonal architecture). (−) Couples the core to a package named after a specific technology.
2. **Define `NotionProvisioningPort` in `application.port`; implement it in `infrastructure.adapter.notion`.**
   - (+) Dependency points inward: application defines the contract, infrastructure depends on application to implement it (classic Ports & Adapters). (+) Consistent with how the domain already owns `WorkspaceRepository`. (−) Slight divergence from the literal `infrastructure.port` wording in CLAUDE.md.
3. **Put the port in the domain** alongside `WorkspaceRepository`.
   - (−) The port is an orchestration/provisioning concern (specs like `DatabaseSpec`), not a domain invariant; it belongs to application use cases, not the pure domain. Rejected.

## Decision
Adopt option 2: driven ports consumed by application services live in `com.lifeos.application.port`; adapters in `infrastructure.adapter.notion` implement them. `infrastructure.port` remains available for adapter-side SPI contracts that infrastructure itself owns, which preserves the intent of the CLAUDE.md note without inverting the dependency rule.

## Consequences
- The Notion adapter depends on the application layer, not vice-versa.
- A future non-Notion adapter implements the same port with no change to the core.
- A minor documentation reconciliation with CLAUDE.md's `infrastructure.port` phrasing should be noted to the team.
