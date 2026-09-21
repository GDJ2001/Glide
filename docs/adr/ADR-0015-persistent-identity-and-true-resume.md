# ADR-0015 — Persistent identity and true resume

- **Status:** Accepted design; HARD-018 protocol definition implemented for review. Runtime trust/resume integration is **not complete**.
- **Date:** 2026-09-14 (initial decision); 2026-09-18 (hardening revision).
- **Authority:** [Persistent identity and resume specification](../future/PERSISTENT_IDENTITY_AND_RESUME_SPECIFICATION.md), [HARD task ledger](../TODO_MASTER.md), and [threat model](../security/GLIDE_THREAT_MODEL.md).
- **Supersedes:** The nonce-only identity proof and mandatory-SAS assumptions previously recorded in this ADR.

## Context

Glide needs remembered peer identity and resumable large transfers without weakening the current local pairing model. The release hardening review established several requirements that the earlier M13 draft did not satisfy:

1. A device name, discovery record, or isolated nonce is not an authentication identity.
2. A persistent-identity proof must bind the two peers, their roles, the active session, negotiated capabilities, a fresh challenge, and the actual secure channel.
3. A resume proof must additionally bind the original transfer/item, contiguous verified progress, chunk parameters, content root, and a fresh active-session nonce.
4. The initial local pairing profile is the QR/pinned-TLS + one-use invitation flow from ADR-0018; the old “mandatory 6-digit SAS every session” description is no longer authoritative.
5. `identity-v1` and `resume-v1` must not be advertised merely because helper classes exist. They become negotiable only after the platform runtime can enforce the complete profile.

Naive offset-only resume remains unacceptable because an altered source, forged offset, stale journal, or substituted partial can otherwise splice incompatible bytes into a destination.

## Decision

Adopt a versioned persistent-identity extension **`identity-v1`** and a dependent true-resume extension **`resume-v1`**.

### Persistent identity

Each device owns a P-256 ECDSA identity key. The public identity is the DER SubjectPublicKeyInfo bytes and its fingerprint is lowercase hexadecimal:

`SHA-256(SPKI_DER)`.

Private-key storage is platform-specific:

- Android: Android Keystore, non-exportable where supported.
- Windows: protected/non-exportable platform storage selected by HARD-020.
- Linux: restricted per-user key storage selected by HARD-020.

Identity proof uses `SHA256withECDSA` over the canonical HARD-018 binary transcript. No API may authenticate a peer by signing only a nonce.

### Channel binding

`identity-v1` requires a 32-byte channel binding represented as 64 lowercase hexadecimal characters.

For the native TLS LAN profile, the binding is the exact SHA-256 SPKI fingerprint of the TLS **server certificate actually used by the active pinned TLS connection**. The transcript labels this scheme `tls-server-spki-sha256-v1`.

This choice is portable across the supported Android/.NET TLS stacks while remaining tied to the authenticated TLS endpoint. The signed transcript also includes the fresh active session and challenge, so a proof cannot be replayed onto a different pinned server/channel or session.

A transport that cannot expose the authenticated TLS server SPKI binding **must not negotiate `identity-v1`**. There is no zero-filled, connection-name, arbitrary certificate-string, or other synthetic fallback.

### First-use enrollment

Initial local pairing continues to use the one-use invitation/pinned secure-channel profile. Inside that authenticated session, peers may exchange persistent public identity keys and prove possession using the channel-bound identity transcript. A key is persisted only after explicit user enrollment/trust authorization.

A display name is presentation metadata only. First-use enrollment does not make discovery metadata authoritative.

### Subsequent trusted sessions

A previously enrolled key is accepted only after a fresh identity proof verifies against the pinned public key and all bound transcript values match the active connection. Key mismatch, role swap, capability downgrade, replay, wrong channel binding, or wrong session fails closed.

### True resume

`resume-v1` requires `identity-v1` in the same negotiated capability set. A resume request is valid only for an authenticated trusted peer and signs the HARD-018 resume transcript, including:

- active reconnect session;
- original transfer session and item;
- requester/responder roles and fingerprints;
- fresh session nonce;
- active channel binding;
- negotiated capability hash;
- chunk size and total source size;
- contiguous verified chunk count and byte offset;
- chunk-root hash.

The offset must be derivable from the contiguous verified chunk journal. Offset-only recovery is prohibited.

### Capability advertisement

`identity-v1` is advertised dynamically only on a native LAN session that has both persistent identity authority and the authenticated server-SPKI/BOOTSTRAP channel context required by HARD-021. Transports without that authority do not advertise it. `resume-v1` remains disabled until HARD-022/HARD-023 provide durable resume state, and it can never be selected without `identity-v1`.

## Canonical signature encoding

Cryptographic transcripts are binary and serialization-independent. JSON bytes are **not** signed.

- Signature domain: ASCII `GLIDE-SIGN-V1` followed by NUL.
- Purpose byte: `0x01` identity or `0x02` resume.
- Strings: unsigned 32-bit big-endian UTF-8 byte length followed by exact bytes.
- Integer fields: big-endian.
- Capability set: sorted distinct lowercase capability tokens encoded under `GLIDE-CAPS-V1\0`, then SHA-256 hashed.
- Hex fields in protocol messages: lowercase canonical hexadecimal.
- ECDSA signatures: DER-encoded signature bytes represented as lowercase hexadecimal.

Shared byte-for-byte vectors are authoritative at:

`protocol/fixtures/identity_resume_v1_vectors.json`.

## Retained-partial requirements

- Only contiguous verified chunks advance the durable resume journal.
- Unverified partial data remains in private staging.
- Explicit cancellation purges retained partials.
- Retained state expires after 24 hours.
- Storage-pressure cleanup must never promote unverified data.
- Final public commit still requires complete-file SHA-256 verification.

## Consequences

- Existing raw-nonce signing helpers are removed in favor of transcript signing.
- Trust/resume support becomes more difficult to implement incorrectly because profile, role, session, capability, peer, and channel values are mandatory inputs.
- Platforms without a usable channel-binding primitive cannot advertise persistent trust.
- HARD-019/020 own persistent identity storage/enrollment.
- HARD-021 owns actual mutually authenticated trusted-session establishment and TLS channel-binding extraction.
- HARD-022/023 own durable resume journals/runtime recovery.
- HARD-024 owns real cross-platform folder/resume verification.

## Rejected alternatives

1. **Device-name or discovery trust:** unauthenticated and spoofable.
2. **Nonce-only signatures:** prove key possession but do not bind the proof to the active session, role, capability set, peer pair, or secure channel.
3. **Offset-only resume:** cannot prove retained content or source continuity.
4. **Automatic trust from a scanned/downloaded QR alone:** invitation possession is temporary pairing authority, not indefinite identity enrollment.
5. **Silent capability downgrade:** trusted sessions fail closed rather than silently losing required trust/resume guarantees.
