# ADR-0013 — Dependency stability policy

- Status: Accepted (planning); validation pending implementation evidence.
- Date:2026-09-12.
- Authority: [specification](../planning/TECHNOLOGY_RESEARCH.md); [decision index](README.md).

## Context
Newest artifact can be unstable or incompatible with build processors.

## Options considered
Always latest; frozen old tutorial stack; stable pinned/admitted dependencies.

## Decision
Latest suitable stable baseline with documented Room2 choice, no alpha/RC requirement and foundation compatibility gate.

## Reasoning
Balances current APIs and reproducible production risk.

## Consequences
Metadata can outpace prose samples; exact coordinates/source dates recorded.

## Alternatives and migration/fallback
Evidence-backed rollback pair or isolated experimental exception via change control.

Use [change control](../development/CHANGE_CONTROL.md) for deviations. The linked specification supplies detailed requirements and official sources; this ADR records why, not another duplicate version table.

