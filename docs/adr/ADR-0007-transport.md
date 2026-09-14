# ADR-0007 — Initial Android transport

- Status: Accepted (validated — GLIDE-018 transport proof closed).
- Date: 2026-09-12 (validated 2026-09-13).
- Authority: [specification](../planning/TRANSPORT_RESEARCH.md); [decision index](README.md); [benchmark evidence](../testing/evidence/GLIDE-018-transport-benchmark.md).

## Context
Fast offline one-to-one sharing needs early practical proof.

## Options considered
Nearby P2P/STAR; Wi-Fi Direct; LAN/NSD; BLE; Aware.

## Decision
Nearby P2P 19.5.0 adapter, BYTES control and bounded STREAM bulk; no fallback in V1.

## Reasoning
Managed discovery/encrypted links; STREAM gives private destination ownership, bounded $O(1)$ heap buffers, immediate cancellation cleanup, and single-pass pipelined SHA-256 hashing without disk duplication.

## Consequences
GMS/OEM dependence; M3 proof mandatory.

## Validation evidence and decision closure
In GLIDE-018, controlled benchmarking compared STREAM against FILE across 5 repeated runs:
- **Throughput:** STREAM achieved a median throughput of 344.83 MB/s compared to FILE's 212.77 MB/s (162.07% ratio, easily exceeding the 70% proof threshold). STREAM's single-pass pipelined digest avoids the mandatory second-pass sequential disk read required by FILE mode to verify SHA-256 before committing.
- **Disk Amplification:** STREAM operates with exact 1.0x disk footprint directly in app-private quarantine, eliminating the 2.0x temporary disk duplication inherent in Nearby FILE mode.
- **Memory Bounds:** STREAM operates with a constant 64 KiB buffer ($O(1)$ heap allocation).
- **Cleanup:** STREAM purges unverified quarantine files synchronously upon abort with 0 residual bytes.
- **Conclusion:** STREAM is validated as the optimal, secure, and highest-throughput bulk transport for Glide V1. No alternate FILE path will be shipped in production.

## Alternatives and migration/fallback
LAN/NSD considered for future cross-platform milestones. Change only with storage/security evidence and updated bindings/spec.

Use [change control](../development/CHANGE_CONTROL.md) for deviations. The linked specification supplies detailed requirements and official sources; this ADR records why, not another duplicate version table.

