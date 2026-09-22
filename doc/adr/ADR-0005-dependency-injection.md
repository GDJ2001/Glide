# ADR-0005 — Dependency injection

- Status: Accepted (planning); validation pending implementation evidence.
- Date:2026-09-12.
- Authority: [specification](../planning/TECHNOLOGY_RESEARCH.md); [decision index](README.md).

## Context
Service/VM/adapters need explicit lifetime and replaceable tests.

## Options considered
Manual DI; Koin; Hilt.

## Decision
Hilt2.60.1, AndroidX integration1.4.0; compile-time bindings at app root.

## Reasoning
Compile-time graph checks and Android lifecycle integration outweigh small processor overhead.

## Consequences
KSP/compiler compatibility must be proven in foundation; no global service locator.

## Alternatives and migration/fallback
Manual composition fallback only if reproduced incompatibility and updated ADR.

Use [change control](../development/CHANGE_CONTROL.md) for deviations. The linked specification supplies detailed requirements and official sources; this ADR records why, not another duplicate version table.

