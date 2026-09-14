# ADR-0004 — Navigation ownership

- Status: Accepted (planning); validation pending implementation evidence.
- Date:2026-09-12.
- Authority: [specification](../architecture/GLIDE_ARCHITECTURE.md); [decision index](README.md).

## Context
One Activity must handle routes, dialogs, adaptive panes and predictive back.

## Options considered
Navigation2; Navigation3; custom router.

## Decision
Navigation3 stable1.1.7 with app-owned state/back stack and saved safe keys.

## Reasoning
Fits Compose and window-aware navigation without Fragment-first architecture.

## Consequences
Saved route state cannot restore live transport or consent.

## Alternatives and migration/fallback
Navigation wrapper permits migration; no RC required.

Use [change control](../development/CHANGE_CONTROL.md) for deviations. The linked specification supplies detailed requirements and official sources; this ADR records why, not another duplicate version table.

