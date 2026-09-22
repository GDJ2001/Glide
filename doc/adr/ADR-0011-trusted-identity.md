# ADR-0011 — Persistent peer trust

- Status: Accepted (planning); validation pending implementation evidence.
- Date:2026-09-12.
- Authority: [specification](../security/GLIDE_THREAT_MODEL.md); [decision index](README.md).

## Context
Convenience trust can introduce tracking and impersonation risks before need is proven.

## Options considered
Name/endpoint trust; custom keys; per-session verification; standard persistent identity later.

## Decision
Per-session ephemeral identity and full SDK token comparison every time; no TrustedPeer V1.

## Reasoning
Reduces security surface while preserving explicit correct-device confirmation.

## Consequences
Repeat users compare each connection; history peer label is not trusted identity.

## Alternatives and migration/fallback
P3 reviewed identity protocol with Keystore, key possession, revocation and rotation; no custom crypto.

Use [change control](../development/CHANGE_CONTROL.md) for deviations. The linked specification supplies detailed requirements and official sources; this ADR records why, not another duplicate version table.

