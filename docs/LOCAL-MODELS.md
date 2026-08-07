# Local & cheaper-model routing

The kit ships on Claude tiers (opus for Architect + Auditor, sonnet for the rest). This document describes the **swap path** to route the cheap, deterministic roles to smaller hosted models (haiku) or to **local inference (Ollama / MLX)** — in service of workspace principle #1 (cost-aware model routing) and goal #9 (prefer local inference whenever it's sufficient).

This is a roadmap and a how-to. The kit does **not** install or require a local runtime; it works as delivered on Claude tiers.

## Which roles can move cheaper — and which can't

Route each role to the cheapest model that can do its job *correctly and deterministically*. The more a role does mechanical transformation of a well-specified input, the cheaper it can go; the more it does open-ended reasoning or security judgment, the more it needs a strong model.

| Role | Shipped model | Swap candidate | Rationale |
|---|---|---|---|
| `spring-specifier` | sonnet | haiku, then local | Structured extraction of a request into a spec template. Deterministic once the template is fixed. |
| `spring-architect` | **opus** | keep on opus | Open-ended design, trade-off reasoning, C4/ADR. Weak models hallucinate architecture and citations. |
| `spring-sme` | sonnet | haiku | Transforms an approved design into an implementation checklist. Mostly mechanical. |
| `spring-implementer` | sonnet | sonnet (hold) / capable local coder | Code generation + TDD. A local code model (e.g. Qwen2.5-Coder, Codestral) can work for small features; verify against `./mvnw verify` before trusting it. |
| `spring-qa` | sonnet | haiku / local | Checks output against a checklist and runs tests. Verification is more deterministic than generation. |
| `spring-auditor` | **opus** | keep on opus | Security reasoning + authoritative citation. The most expensive role to get wrong; do not downgrade. |

**Rule of thumb:** downgrade Specifier / SME / QA first (they transform or verify against fixed contracts). Hold Architect and Auditor on the strongest tier — a wrong design or a missed injection is far costlier than the tokens saved.

## Option A — swap the Claude tier per agent

Each agent's model is set in its frontmatter (`model:`). To make a role cheaper, edit the one line:

```yaml
# plugins/spring-boot-team/agents/spring-sme.md
model: haiku      # was: sonnet
```

Valid Claude tiers for agent frontmatter: `haiku`, `sonnet`, `opus` (or a pinned model id). Re-run the relevant TESTING.md scenario after any downgrade and confirm the checklist still passes before keeping it.

## Option B — route a role to a local model (Ollama / MLX)

Claude Code can point at any OpenAI- or Anthropic-compatible endpoint via environment variables, which lets a local server stand in for a hosted model. The exact variables and whether per-agent local routing is supported depend on your Claude Code version — **confirm against the official Claude Code settings/model documentation (code.claude.com/docs) before relying on this**; treat the sketch below as the shape of the solution, not a verified recipe.

### 1. Run a local server

**Ollama** (simplest):

```bash
# https://ollama.com/download
ollama pull qwen2.5-coder:7b        # coder for the implementer
ollama pull llama3.1:8b             # general for specifier/sme/qa
ollama serve                        # exposes an OpenAI-compatible API on :11434
```

**MLX** (Apple Silicon, faster on-device):

```bash
pip install mlx-lm
mlx_lm.server --model mlx-community/Qwen2.5-Coder-7B-Instruct-4bit --port 8080
```

### 2. Point Claude Code at it

Set the base URL / model for the session (or via `.claude/settings.local.json` `env`), then verify the model responds:

```bash
export ANTHROPIC_BASE_URL="http://localhost:11434"   # example — verify var name against docs
export ANTHROPIC_MODEL="qwen2.5-coder:7b"
```

Because the kit is a plugin of prompts and skills — not code bound to a specific provider — the same agent definitions run against a local model. The agents' operating rules (TDD, no comment pollution, cite authorities) still apply; the local model's *ability* to honor them is what you validate.

### 3. Validate before trusting

Local and smaller models are more prone to skipping tests, hallucinating citations, and ignoring formatting contracts. For any role you move local:

1. Re-run its TESTING.md scenario end-to-end.
2. Confirm the pass/fail checklist still fully passes — especially the security and hard-rule items.
3. For the Implementer specifically: require `./mvnw verify` green and a clean audit before accepting local-generated code.

If a local model can't hold the contract, move that role back up a tier. The point is cost *without* losing correctness — never the reverse.

## What NOT to route locally (for now)

- **The Auditor.** Security findings must be trustworthy and citation-backed; a hallucinated "all clear" is worse than no audit.
- **The Architect** on anything non-trivial. Design errors propagate through every downstream stage.

Revisit these as local models improve, but re-validate with scenarios 2 and 3 before changing them.
