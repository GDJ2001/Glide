# ADR-0015 — Persistent identity and true resume

- Status: Implemented (Milestone M13, GLIDE-070); verified with comprehensive test suites across :transfer-core, :data, and :app.
- Date: 2026-09-14 (Accepted), 2026-09-17 (Implemented).
- Authority: [Persistent identity and resume specification](../future/PERSISTENT_IDENTITY_AND_RESUME_SPECIFICATION.md); [decision index](README.md); [threat model](../security/GLIDE_THREAT_MODEL.md).

## Context
Glide V1 employs ephemeral session identities with mandatory 6-digit Short Authentication String (SAS) verification for every transfer. While this prevents tracking and impersonation, frequent collaborators and multi-device users desire remembered device trust. Additionally, multi-gigabyte transfers over local radio can experience temporary socket disruptions; restarting from byte zero wastes time and battery. However, naive "offset-only resume" (e.g. requesting `resume_from = X`) exposes users to silent file corruption, byte splicing, and file substitution attacks.

## Options considered
1. **Offset-only resume with device name trust:** Naive resumption based on file size and byte offset. Rejected because an altered sender file or malicious peer could inject arbitrary corrupted data into a partially received file without detection.
2. **Cryptographic persistent identity with chunk Merkle digest tree verification:** Android Keystore hardware-backed asymmetric keys for persistent peer authentication, combined with per-chunk SHA-256 hash trees and signed resume tokens.
3. **Cloud-coordinated session state:** Storing resume state and peer identities on a remote server. Rejected because it violates Glide's fundamental invariant of 100% offline, local peer-to-peer operation.

## Decision
Adopt **Option 2: Cryptographic persistent identity with chunk Merkle digest tree verification**, strictly banning offset-only fake resume.

## Reasoning
- **Hardware-backed security:** Android Keystore (`KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY`) ensures private keys never touch unencrypted process memory.
- **True cryptographic resume:** Receiver verifies the integrity of preceding chunks via a Merkle digest tree before accepting resumption. Corrupted or modified source files fail validation immediately.
- **Replay & impersonation defense:** Mutual cryptographic challenge-response nonces prevent replay of old resume tokens.

## Consequences
- **Capability flags:** Gated behind `CAP_PERSISTENT_IDENTITY = 0x02` and `CAP_TRUE_RESUME = 0x04`.
- **Trust-On-First-Use (TOFU):** Initial pairing requires manual 6-digit mutual SAS confirmation. Subsequent sessions authenticate via signed challenge-response without manual SAS prompt unless key changes.
- **Key pinning & revocation:** Paired peer public keys are pinned in local Room database. If a peer's key changes, trust is revoked immediately and the user is warned of potential MITM.
- **Retained-partial lifecycle:** Staged partial chunks are stored in sandboxed `cacheDir/staging/resumable/<session_id>/` with a strict $\mathbf{24\text{-hour TTL}}$, automatic purge on low disk space ($< 10\%$), and immediate purge on user decline.

## Alternatives and migration/fallback
If peers do not support persistent identity or resume capabilities, the connection gracefully falls back to standard V1 ephemeral SAS authentication and non-resumable discrete streaming.
