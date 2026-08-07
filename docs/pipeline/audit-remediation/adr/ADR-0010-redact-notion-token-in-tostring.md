# ADR-0010: Fully redact the Notion token in `NotionProperties` string representation

- **Status:** Accepted (implements resolved stakeholder decision #1)
- **Feature:** audit-remediation — FR-1 / NFR-1 (closes AUD-001, High)
- **Owner:** spring-architect → SME (`infrastructure.adapter.notion`)

## Context
`NotionProperties` is a `record` holding the Notion integration token as its first component. A
record's implicitly derived `toString()` includes **every** component, so any interpolation,
`log.debug("... {}", properties)`, exception referencing the instance, or `@ConfigurationProperties`
binding-validation message that echoes the bound object would emit the raw Bearer token in
cleartext. OWASP forbids writing secrets to logs or error output. The stakeholder has resolved that
masking must be **full, non-reversible redaction** — reveal nothing of the secret, not even the
last 4 characters.

## Options considered
1. **Override `toString()` on the record; render `token` as a fixed marker (`****`), other fields
   as-is.**
   - + One change at the single source of truth closes the leak for every current and future
     string path (interpolation, logging, exceptions, binding errors).
   - + Keeps `token()` returning `String`, so `NotionClient` (`"Bearer " + token`) is unchanged →
     zero blast radius, satisfies NFR-4. A record may legally declare an explicit `toString()` that
     overrides the derived one.
   - − Redaction is a convention on the type, not enforced by the compiler; a future field addition
     must be added to the manual `toString`.
2. **Wrap the token in a dedicated `Secret`/`MaskedString` value object whose `toString()` returns
   `****`; change the component type to `Secret`.**
   - + Encapsulates the secret; the mask travels with the value.
   - − Ripples to every caller: `properties.token()` now returns `Secret`, `NotionClient` needs
     `.value()`; `@ConfigurationProperties` constructor binding of `String → Secret` needs a
     converter. Larger blast radius for a remediation pass; still leaks if a caller logs `.value()`.
   - − Does not remove the primary leak surface (the record's own `toString`) unless combined with
     option 1 anyway.
3. **Partial mask (e.g. last-4 visible).** — **Rejected by the stakeholder decision** (full
   redaction required); last-4 of a short-lived integration token narrows brute-force space for no
   operational benefit here.

## Decision
Adopt **Option 1**: override `toString()` on the `NotionProperties` record to render `token` as the
fixed literal `****` (non-reversible), while `version` and `rootParentPageId` render as-is. Keep the
`token()` accessor returning the raw `String` for the `Authorization` header.

## Consequences
- Any string representation of `NotionProperties` — logs, exceptions, interpolation, Boot
  binding-error messages — now shows `****` for the token. NFR-1 met at the boundary that owns the
  secret.
- Happy-path behavior and the `Bearer` header are unchanged (NFR-4).
- A regression test (`NotionPropertiesTest`) asserts the raw token never appears in `toString()` /
  interpolation and that `version`/`rootParentPageId` still render.
- Residual: adding a new secret field later requires updating the manual `toString`; noted for the
  maintainer. `@NotBlank` field-level binding errors are not a leak vector because the only invalid
  token is blank/empty.

## References
- OWASP Cheat Sheet Series — *Logging Cheat Sheet* (never log secrets/credentials).
- OWASP ASVS v4.0 — V7 (Error Handling and Logging).
- *Effective Java* (Bloch), Item 12: Always override `toString`.
- Oracle Java SE 21 / *Java Language Specification* — Records: an explicitly declared `toString()`
  overrides the implicitly derived one.
