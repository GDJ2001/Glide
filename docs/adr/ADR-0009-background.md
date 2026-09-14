# ADR-0009 — Background transfer lifetime

- Status: Accepted (planning); validation pending implementation evidence.
- Date:2026-09-12.
- Authority: [specification](../android/STORAGE_AND_BACKGROUND.md); [decision index](README.md).

## Context
Live peer connection must survive ordinary backgrounding without false guarantees.

## Options considered
Activity only; WorkManager; UIDT; connectedDevice FGS.

## Decision
User-visible-start connectedDevice FGS for active session; WorkManager maintenance only.

## Reasoning
Matches external-device interaction; platform still controls process survival.

## Consequences
Receiver not always-on; process death interrupts and recovers journal, not stream.

## Alternatives and migration/fallback
Re-evaluate current policies if platform changes; never mislabel offline work to evade quotas.

Use [change control](../development/CHANGE_CONTROL.md) for deviations. The linked specification supplies detailed requirements and official sources; this ADR records why, not another duplicate version table.

