# ADR-0002 — Android SDK strategy

- Status: Accepted (planning); validation pending implementation evidence.
- Date:2026-09-12.
- Authority: [specification](../planning/TECHNOLOGY_RESEARCH.md); [decision index](README.md).

## Context
A new app needs modern behavior while supporting older phones.

## Options considered
min23/26/28; target minimum Play policy or current stable.

## Decision
min26, compile37, target37; JDK17 build baseline.

## Reasoning
Balances notification/storage/lifecycle maintenance with older-device access; no invented market share.

## Consequences
API-specific permission/storage tests required; old OS patch status not guaranteed.

## Alternatives and migration/fallback
Raise minimum only with evidence and owner review; never lower target to bypass permissions.

Use [change control](../development/CHANGE_CONTROL.md) for deviations. The linked specification supplies detailed requirements and official sources; this ADR records why, not another duplicate version table.

