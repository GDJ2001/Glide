# ADR-0001 — Android application architecture

- Status: Accepted (planning); validation pending implementation evidence.
- Date:2026-09-12.
- Authority: [specification](../architecture/GLIDE_ARCHITECTURE.md); [decision index](README.md).

## Context
An Android-first app needs testable transfer behavior without fifteen empty modules.

## Options considered
Single module; feature-per-module; five production modules.

## Decision
Five modules with JVM transfer-core, Android transport/data adapters, shared design system and app composition.

## Reasoning
Tests and future native protocol implementations benefit from clean boundaries; split only when ownership/build evidence warrants.

## Consequences
More explicit port mapping; no Android type leakage. Keep UI feature packages inside app.

## Alternatives and migration/fallback
Can extract feature modules later; do not merge transport into UI.

Use [change control](../development/CHANGE_CONTROL.md) for deviations. The linked specification supplies detailed requirements and official sources; this ADR records why, not another duplicate version table.

