# Persistent Identity & True Resume Extension Specification

**Document status:** HARD-018 protocol specification — implementation prepared, executable/cross-platform review still required.  
**Profiles:** `identity-v1`, `resume-v1`  
**Authority:** [ADR-0015](../adr/ADR-0015-persistent-identity-and-true-resume.md) | [HARD task ledger](../TODO_MASTER.md) | [Threat model](../security/GLIDE_THREAT_MODEL.md)  
**Hardening task:** HARD-018  
**Important:** This document specifies the extension. It does **not** certify HARD-019–024 runtime integration or physical interoperability.

---

## 1. Security objective

Persistent trust must prove that the same enrolled cryptographic peer is participating in the **current authenticated connection**, in the expected role, with the expected negotiated features. True resume must additionally prove exactly which verified source/item prefix may be retained.

The following are never sufficient authority by themselves:

- display/device name;
- DNS-SD discovery metadata;
- a stored fingerprint without proof of possession;
- a signature over only a random nonce;
- a byte offset without a verified contiguous chunk journal;
- an old session signature replayed onto a new channel.

The native first-use pairing authority remains the current QR/pinned secure-channel + one-use invitation flow. Persistent enrollment is an explicit additional user decision inside that authenticated session.

---

## 2. Cryptographic profile

### 2.1 Identity key

Each persistent device identity is a P-256 ECDSA key pair.

- Signature algorithm: **SHA-256 with ECDSA**.
- Public-key representation: X.509 SubjectPublicKeyInfo DER.
- Fingerprint: `SHA-256(publicKeyDer)`, exactly 64 lowercase hexadecimal characters.
- Signature representation on the wire: DER ECDSA signature bytes encoded as lowercase hexadecimal.
- Private keys must be protected by platform-appropriate secure storage and must not be exported by Glide.

Android uses Android Keystore. Windows/Linux storage selection and lifecycle are HARD-020.

### 2.2 Channel binding

Every `identity-v1` or `resume-v1` signature contains a **32-byte binding to the active secure channel**, represented as 64 lowercase hexadecimal characters.

For native TLS LAN sessions, HARD-021 defines the binding as:

- scheme: `tls-server-spki-sha256-v1`;
- value: the 32-byte SHA-256 fingerprint of the TLS server certificate SubjectPublicKeyInfo actually used by the active pinned TLS connection;
- no synthetic fallback.

The signature transcript also binds the fresh active session/challenge and negotiated capabilities. A proof therefore cannot be transplanted to another pinned TLS server identity or another session.

If the active transport cannot expose a trustworthy authenticated server-SPKI binding, `identity-v1` and `resume-v1` are unavailable on that transport.

### 2.3 Freshness

Each identity challenge contains a fresh random 32-byte challenge nonce. Resume requests contain a fresh 32-byte reconnect-session nonce. Replay on a different session/channel changes the signed transcript and fails verification.

---

## 3. Capability negotiation and downgrade rules

Known capabilities:

- `stream-v1`
- `sha256`
- `folder-v1`
- `identity-v1`
- `resume-v1`

Rules:

1. `resume-v1` requires `identity-v1`.
2. A HELLO_ACK may select only capabilities offered by HELLO.
3. `identity-v1` is advertised dynamically only when the active native LAN session has persistent identity authority plus the authenticated server-SPKI/BOOTSTRAP channel context required by HARD-021. `resume-v1` remains disabled until durable resume storage is implemented.
4. A trusted policy that requires identity/resume must fail closed if a peer attempts to downgrade the required capability set.
5. Capability comparison is performed over a deterministic hash described below.

### 3.1 Capability hash

Canonical capability bytes are:

1. ASCII `GLIDE-CAPS-V1`;
2. one NUL byte;
3. unsigned 32-bit big-endian count;
4. sorted, distinct capability strings, each encoded as:
   - unsigned 32-bit big-endian UTF-8 byte length;
   - exact UTF-8 bytes.

Only lowercase ASCII capability tokens matching `[a-z0-9][a-z0-9-]{0,63}` are accepted.

`capabilityHashHex = lowercase_hex(SHA-256(canonicalCapabilityBytes))`.

---

## 4. Canonical signature transcript

Protocol JSON is validated strictly but **JSON serialization is never signed**. Kotlin and .NET sign identical binary transcript bytes.

All strings below use unsigned 32-bit big-endian UTF-8 length + exact UTF-8 bytes.

Common prefix:

1. ASCII `GLIDE-SIGN-V1`;
2. NUL;
3. one-byte purpose:
   - `0x01` = identity proof;
   - `0x02` = resume request.

### 4.1 Identity proof transcript

After the common prefix:

1. `"identity-v1"`
2. `"tls-server-spki-sha256-v1"`
3. active session UUID
4. challenge message UUID
5. signer role
6. verifier role
7. signer identity fingerprint
8. verifier identity fingerprint
9. fresh challenge nonce
10. active channel binding
11. negotiated capability hash

Roles are exactly `sender` or `receiver` and must be opposite.

### 4.2 Resume request transcript

After the common prefix:

1. `"resume-v1"`
2. `"tls-server-spki-sha256-v1"`
3. active reconnect session UUID
4. original transfer session UUID
5. item UUID
6. requester role (`receiver`)
7. responder role (`sender`)
8. requester fingerprint
9. responder fingerprint
10. fresh reconnect-session nonce
11. active channel binding
12. negotiated capability hash
13. chunk size as unsigned 32-bit big-endian
14. total size as unsigned 64-bit big-endian
15. verified contiguous chunk count as unsigned 64-bit big-endian
16. contiguous verified byte offset as unsigned 64-bit big-endian
17. chunk-root hash

The verified chunk count must be exactly derivable from the offset/chunk size, allowing the final partial chunk only when the offset equals the complete source size.

Shared vectors: `protocol/fixtures/identity_resume_v1_vectors.json`.

---

## 5. Identity control messages

All controls remain normal Glide protocol-v1 envelopes. Their envelope `sessionId` is the **active** session.

### 5.1 `IDENTITY_CHALLENGE`

Body:

- `profile`: exactly `identity-v1`
- `challengeNonceHex`: 32-byte fresh nonce
- `verifierFingerprintHex`
- `verifierRole`
- `signerRole`
- `channelBindingHex`
- `capabilityHashHex`

The signer must verify that the challenge values match the active session context before signing.

### 5.2 `IDENTITY_RESPONSE`

Body:

- `profile`: exactly `identity-v1`
- `challengeMessageId`: exact message ID of the challenge
- `challengeNonceHex`: exact nonce from the challenge
- `signerFingerprintHex`
- `publicKeyDerHex`
- `signatureHex`

The verifier:

1. checks `challengeMessageId` and nonce correlation;
2. recomputes the fingerprint from `publicKeyDerHex`;
3. compares the key/fingerprint to the enrolled record when one exists;
4. reconstructs the identity transcript from local active-session state;
5. verifies the ECDSA signature;
6. rejects any session/role/capability/channel/key mismatch.

For first-use enrollment, the proof of possession succeeds before the user is asked whether to persist trust. Trust is never created solely by receiving this response.

---

## 6. Enrollment lifecycle

### 6.1 First use

1. Establish the current one-use invitation-authenticated native channel.
2. Exchange persistent public keys/fingerprints inside that channel.
3. Execute channel-bound identity proofs in both required directions.
4. Present the peer identity to the user.
5. Persist the peer key only after explicit **Trust/Enroll** authorization.

The temporary invitation token is not persisted as a trust credential.

### 6.2 Trusted reconnect

1. Negotiate `identity-v1`.
2. Require a fresh challenge and active channel binding.
3. Verify the proof against the pinned enrolled key.
4. If the key changed, proof fails, or the capability policy downgraded, abort the trusted session.
5. A trusted device still does **not** automatically authorize arbitrary file offers unless a separate headless policy explicitly permits that peer/destination/size combination.

Revocation immediately removes future trusted-session authority.

---

## 7. True resume data model

Offset-only resume is prohibited.

A resumable item has:

- original transfer session UUID;
- item UUID;
- expected total size;
- fixed chunk size;
- per-chunk SHA-256 digests or equivalent authenticated digest manifest;
- deterministic chunk-root hash;
- durable journal of the **contiguous verified prefix**;
- creation/update timestamps and expiry;
- enrolled peer identity.

Only a verified contiguous prefix may advance `verifiedChunkCount` and `contiguousByteOffset`.

### 7.1 `RESUME_REQUEST`

Body:

- `profile`: `resume-v1`
- `originalSessionId`
- `itemId`
- `requesterFingerprintHex`
- `responderFingerprintHex`
- `requesterRole`: `receiver`
- `responderRole`: `sender`
- `verifiedChunkCount`
- `contiguousByteOffset`: decimal byte-count string
- `chunkSize`: integer, maximum 16 MiB
- `totalSizeBytes`: decimal byte-count string
- `chunkRootHashHex`
- `sessionNonceHex`
- `channelBindingHex`
- `capabilityHashHex`
- `signatureHex`

The envelope session is the new active reconnect session. The body `originalSessionId` identifies the interrupted transfer.

The sender must verify:

- authenticated enrolled requester;
- profile/capabilities and no downgrade;
- exact active channel binding and fresh nonce;
- signature over the canonical resume transcript;
- source still maps to the same item/size/root;
- journal offset/count alignment and bounds.

### 7.2 `RESUME_ACCEPT`

Body:

- `profile`: `resume-v1`
- `requestMessageId`
- `originalSessionId`
- `itemId`
- `startChunkIndex`
- `startByteOffset`: decimal string

The values must exactly match the accepted verified prefix.

### 7.3 `RESUME_REJECT`

Body:

- `profile`: `resume-v1`
- `requestMessageId`
- `originalSessionId`
- `itemId`
- `code`

Stable codes:

- `FILE_CHANGED`
- `JOURNAL_MISMATCH`
- `EXPIRED`
- `UNAUTHORIZED`
- `INVALID_OFFSET`
- `CAPABILITY_MISMATCH`

Free-form remote rejection reasons are not protocol authority.

---

## 8. Chunk integrity and final verification

True resume requires content continuity, not merely an authenticated offset.

1. Source is divided into fixed negotiated chunks.
2. Each chunk has a SHA-256 digest.
3. The digest manifest produces the agreed chunk-root hash.
4. Receiver verifies a chunk before journal advancement.
5. Journal advancement is atomic and contiguous.
6. On resume, retained chunks and journal state are revalidated before reuse.
7. A changed source/root rejects resume.
8. After the final chunk, the complete assembled file still requires whole-file SHA-256 verification before public commit.

A resume optimization may not weaken final integrity.

---

## 9. Retained-partial lifecycle

| Phase | Requirement |
| --- | --- |
| Staging | Private application staging only; never public destination |
| TTL | Maximum 24 hours |
| Explicit cancel | Immediate purge of retained item/session |
| Revoked peer | Retained authority is unusable; policy determines purge |
| Storage pressure | Evict resumable state safely without public promotion |
| Final commit | Only after complete-file verification |
| Concurrency | Must remain bounded by platform storage/runtime policy |

No retained journal may reconstruct an active network connection after process death.

---

## 10. Threat invariants

| Threat | Required defense |
| --- | --- |
| Identity-name spoofing | Names are never trust authority |
| Public-key substitution | Fingerprint/key comparison + proof of possession |
| Replay | Fresh challenge/nonce + active session + channel binding |
| Role swap | Roles are part of signed transcript |
| Capability downgrade | Negotiated capability hash is signed; policy fails closed |
| Cross-connection signature reuse | Active channel binding is signed |
| Resume offset tampering | Count/offset/chunk alignment + signed transcript |
| Stale/corrupt partial | Durable contiguous verified journal + digest revalidation |
| Source substitution | Size/root/source revalidation |
| Unauthorized headless receive | Separate enrolled-peer destination/size policy |
| Private-key extraction | Platform protected key storage |
| Partial-file exposure | Private quarantine until final verification |

---

## 11. Implementation ownership

HARD-018 owns:

- versioned message schema;
- canonical signature/capability transcripts;
- dependency/downgrade rules;
- shared Kotlin/.NET golden vectors;
- documentation/ADR agreement.

HARD-019 owns Android persistent identity storage, enrollment, revocation, and presentation.

HARD-020 owns equivalent Windows/Linux identity persistence and peer-specific headless policy.

HARD-021 owns live mutually authenticated trusted sessions, channel-binding extraction, downgrade/replay enforcement.

HARD-022/023 own durable Android/desktop true-resume journals.

HARD-024 owns real folder/resume interoperability and failure validation.

Until HARD-019–021 complete, production must not advertise `identity-v1` or `resume-v1`.

---

## 12. Verification contract

HARD-018 is not complete merely because source files exist. At minimum:

- Kotlin and .NET generate exactly the shared canonical transcript bytes.
- Codec tests reject wrong profile, malformed/uppercase canonical fields, role swaps, unknown rejection codes, invalid count/offset alignment, and unsupported capability downgrade.
- Transcript signatures fail when session, role, peer fingerprint, capability hash, nonce, or channel binding changes.
- Current ordinary Android and .NET tests remain green.
- Later HARD-021 runtime tests prove an actual active transport channel binding, not a fixture or zero value.

Physical trust/resume interoperability belongs to HARD-024.
