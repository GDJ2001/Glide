# ADR-0010 — Integrity verification

- Status: Accepted (planning); validation pending implementation evidence.
- Date:2026-09-12.
- Authority: [specification](../protocol/GLIDE_PROTOCOL_V1.md); [decision index](README.md).

## Context
Large files need cross-platform streaming checks without custom cryptography.

## Options considered
CRC32; MD5/SHA1; SHA256; BLAKE3.

## Decision
SHA256 on actual raw stream bytes, exact count and authenticated digest exchange; verify before commit.

## Reasoning
Standard Android/JVM support and independent vectors; no new dependency.

## Consequences
Hash CPU cost measured; not malware detection or recipient authentication.

## Alternatives and migration/fallback
Only negotiate a new algorithm after cross-platform/security/performance review.

Use [change control](../development/CHANGE_CONTROL.md) for deviations. The linked specification supplies detailed requirements and official sources; this ADR records why, not another duplicate version table.

