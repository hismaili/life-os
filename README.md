# spring-boot-kit

A self-contained, workspace-scoped Claude Code plugin that makes the `awesome-ai-setup` vision **executable and testable** for one concrete stack: **Java + Spring Boot + Spring Data JPA + Spring Security (Maven)**.

Instead of one generalist chat, it ships a **pipeline of six narrow, accountable agents** — Specifier → Architect → SME → Implementer → QA → Auditor — each on the cheapest sufficient Claude tier, grounded in authoritative sources, communicating only through named artifacts on disk.

## What's in the box

```
spring-boot-kit/
  README.md                     # this file
  TESTING.md                    # 4 validation scenarios: prompt → expected result → checklist
  install-local.sh              # registers the marketplace + enables the plugin in THIS repo only
  docs/
    PIPELINE.md                 # the artifact handoff contract between roles
    LOCAL-MODELS.md             # how to route cheap/deterministic roles to Ollama/MLX later
  .claude-plugin/marketplace.json
  plugins/spring-boot-team/
    .claude-plugin/plugin.json
    agents/    spring-{specifier,architect,sme,implementer,qa,auditor}.md
    skills/    spring-boot-conventions, spring-data-jpa, spring-security, spring-testing, authoritative-references
    commands/  spring-feature.md (full pipeline), spring-audit.md (auditor only)
```

## The six agents

| Agent | Model / effort | Input → Output |
|---|---|---|
| `spring-specifier` | sonnet / medium | request → `01-spec.md` (scope, actors, `FR-*`, acceptance criteria, `NFR-*`, out-of-scope) |
| `spring-architect` | opus / high | spec → `02-architecture.md` (C4 + HLD/LLD), `adr/ADR-*.md`, `02-open-questions.md` |
| `spring-sme` | sonnet / medium | architecture → `03-tech-spec.md` (entities, repos, services, controllers, DTOs, validation, test plan) |
| `spring-implementer` | sonnet / medium | tech-spec → code + tests in `src/` (TDD, tests first) |
| `spring-qa` | sonnet / medium | impl + spec + design → `04-qa-report.md` (criteria met? tests pass? violations) |
| `spring-auditor` | opus / high | impl → `05-audit-report.md` (security/OWASP, SOLID/DDD, each finding cites an authority) |

**Model rationale (cost-aware, principle #1):** opus is reserved for the two hardest reasoning roles — design and security audit. Sonnet handles structured transformation and verification. `docs/LOCAL-MODELS.md` documents haiku and local Ollama/MLX as swap targets for the mechanical, deterministic steps.

## How it maps to the 9 workspace goals

| Goal | How the kit delivers it |
|---|---|
| 1. Manage cost | opus only for Architect + Auditor; sonnet elsewhere; local-model swap doc for the rest |
| 2. Optimize context | orchestrator passes each subagent only its one input artifact — never the whole conversation |
| 3. Eliminate hallucination | `authoritative-references` skill + a downstream QA/Auditor that check claims against sources |
| 4. Eliminate comment pollution | Implementer defaults to zero comments; Auditor flags noise and any "AI-generated" comment |
| 5. Quality code + docs | every stage emits a reviewable doc artifact alongside the code |
| 6. Respect principles | SOLID/DDD/YAGNI/TDD embedded in each agent's mandate; Auditor scores adherence |
| 7. Design deliberately | Architect produces C4, UML sequence diagrams, and ADRs at tailored depth |
| 8. Authoritative sources | allowlist skill + explicit NON-authoritative list; Architect/Auditor cite official docs |
| 9. Auditable team | the on-disk artifact contract in `docs/PIPELINE.md` makes every handoff legible |

## Install (workspace-scoped)

The kit activates **only in this repository**. It writes to project-local `.claude/settings.local.json` and touches nothing under `~/.claude`.

```bash
bash spring-boot-kit/install-local.sh
```

The script:
1. Registers the local marketplace: `claude plugin marketplace add ./spring-boot-kit`.
2. Enables the plugin project-scoped: `"enabledPlugins": {"spring-boot-team@spring-boot-kit": true}` in `.claude/settings.local.json`.
3. Adds conservative project-local permissions (see `install-local.sh` for the exact list): `./mvnw` build/test commands and `WebFetch` for the authoritative domains only.

### Manual install (if you prefer not to run the script)

```bash
claude plugin marketplace add ./spring-boot-kit
```

Then add to `.claude/settings.local.json` in this repo:

```json
{
  "enabledPlugins": { "spring-boot-team@spring-boot-kit": true },
  "permissions": {
    "allow": [
      "Bash(./mvnw test)", "Bash(./mvnw verify)", "Bash(./mvnw -q compile)", "Bash(./mvnw dependency:tree)",
      "WebFetch(domain:docs.spring.io)", "WebFetch(domain:spring.io)",
      "WebFetch(domain:owasp.org)", "WebFetch(domain:jakarta.ee)"
    ]
  }
}
```

Restart Claude Code (or run `/plugin`) so the plugin, its six agents, and the two commands load.

## Use

- **Full pipeline:** `/spring-feature <describe the feature>` — runs all six roles in order, stops for your answers on architectural open questions, and never commits.
- **Audit only:** `/spring-audit <path or snippet>` — runs just the Auditor on existing code.

Artifacts land in the **target project** under `docs/pipeline/<feature>/`. See `docs/PIPELINE.md` for the full handoff contract.

## Validate

Work through [`TESTING.md`](./TESTING.md): four scenarios (greenfield CRUD, an architectural decision, auditing a flawed snippet, and comment-pollution cleanup), each with an exact prompt, expected artifacts, and a pass/fail checklist tied to the 9 goals.

## Uninstall

```bash
claude plugin marketplace remove spring-boot-kit
```

Then remove the `enabledPlugins` entry (and any permissions you no longer want) from `.claude/settings.local.json`.
