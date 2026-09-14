# ADR-0003 — Compose and Material UI

- Status: Accepted (planning); validation pending implementation evidence.
- Date:2026-09-12.
- Authority: [specification](../design/GLIDE_DESIGN_SYSTEM.md); [decision index](README.md).

## Context
Glide needs adaptive accessible modern UI grounded in accepted branding.

## Options considered
XML/Fragments; Compose Material2; stable Compose Material3.

## Decision
Compose UDF with stable Material3, shared tokens; no unstable Expressive dependency by default.

## Reasoning
Current declarative UI and testable state with restrained premium styling.

## Consequences
Stable APIs may exclude a fashionable experimental control.

## Alternatives and migration/fallback
Wrap components; adopt stable Expressive API later through normal dependency review.

Use [change control](../development/CHANGE_CONTROL.md) for deviations. The linked specification supplies detailed requirements and official sources; this ADR records why, not another duplicate version table.

