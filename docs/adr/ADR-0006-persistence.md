# ADR-0006 — Persistence and storage models

- Status: Accepted (planning); validation pending implementation evidence.
- Date:2026-09-12.
- Authority: [specification](../android/STORAGE_AND_BACKGROUND.md); [decision index](README.md).

## Context
History, received-file catalog and crash journal need transactions; preferences do not.

## Options considered
Files/JSON; Room2; Room3; DataStore-only.

## Decision
Room2.8.5 for records/journal; DataStore1.2.1 preferences; separate private file store.

## Reasoning
Maintained Android Room line meets V1 needs without Room3 KMP/package migration.

## Consequences
Separate domain/entity mapping and filesystem reconciliation required.

## Alternatives and migration/fallback
Migrate to Room3 if maintenance/requirements justify; never destructive migration.

Use [change control](../development/CHANGE_CONTROL.md) for deviations. The linked specification supplies detailed requirements and official sources; this ADR records why, not another duplicate version table.

