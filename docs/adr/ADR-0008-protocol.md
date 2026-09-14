# ADR-0008 — Application protocol

- Status: Accepted (planning); validation pending implementation evidence.
- Date:2026-09-12.
- Authority: [specification](../protocol/GLIDE_PROTOCOL_V1.md); [decision index](README.md).

## Context
Future native clients need stable semantics independent of Nearby.

## Options considered
SDK-only payload conventions; custom binary schema; bounded JSON protocol.

## Decision
Versioned Glide Protocol V1 with explicit consent, stream mapping, SHA256, per-item receipts and bounded JSON.

## Reasoning
Human-reviewable fixtures, cross-language exact byte counts and strict limits.

## Consequences
No pause/resume, directories or parallel transfers in V1; parser/order tests essential.

## Alternatives and migration/fallback
Extensions bounded and negotiated; incompatible semantics require major/ADR change.

Use [change control](../development/CHANGE_CONTROL.md) for deviations. The linked specification supplies detailed requirements and official sources; this ADR records why, not another duplicate version table.

