# 06 — Remediation Plan: Create Resources Database

> Authored by the orchestrator from `findings.yml` (post QA + Audit). One doc-staleness fix, one accepted Info, one cross-cutting tracked. No human-decision gate.

## Human-decision gate

**None.**

## Auto-cascade

| Finding | Sev | Owner | Action | Result |
|---|---|---|---|---|
| QA-002 | Low | Orchestrator (doc) | Correct the stale test-count statements in `03-tech-spec.md` (15/13→2 → **20/18→2**) to match the actual `NotionProvisioningAdapterDatabaseTest` method count. | **Fixed** (documentation only; the 2 required URL contract tests were already present and correct). No build impact. |

## Accepted / tracked (no code change)

| Finding | Sev | Disposition |
|---|---|---|
| AUD-14 | Info | **Accepted.** Name-only verify won't detect a `url`→`rich_text` retype — inherited ADR-0008 contract, documented in ADR-0013. |
| ADAPTER-AMBIGUITY-MSG | Low | **Tracked follow-up.** The shared `findChildByIdentity` hardcodes "Ambiguous **Projects** database identity…", so every non-Projects DB's `>1` FAILED message misnames the resource. Cosmetic (error string), in an unchanged out-of-scope method. Fix: derive the label from `ExpectedShape.title`. Bundle with the shared-adapter cleanup backlog (with BUILDER-SWEEP). |

## Re-verify

No code changed in remediation (QA-002 was documentation). The feature build remains green: unit `Tests run: 281`, failsafe `*IT` 29/29, **BUILD SUCCESS** on Podman (from the Implementer + QA independent runs).

## Outcome

Findings: QA-PASS + QA-002 + AUD-14 + 1 orchestrator-tracked. **QA-002 fixed, AUD-14 accepted, ADAPTER-AMBIGUITY-MSG tracked.** 0 open non-gated findings. 1 iteration, no up-hops, no human gate.
