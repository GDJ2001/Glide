# Persistent Identity & True Resume Extension Specification

**Document Status:** Proposed / Milestone M13 Future Extension  
**Authority:** [ADR-0015](../adr/ADR-0015-persistent-identity-and-true-resume.md) | [Threat Model](../security/GLIDE_THREAT_MODEL.md) | [Cross-Platform Path](CROSS_PLATFORM_PATH.md)  
**Milestone:** M13 — Future work (outside V1)  
**Task:** GLIDE-070  
**Version:** `0.1.0-draft`  

---

## 1. Executive Summary & Objectives

This specification defines the cryptographic persistent peer identity and chunk-verified true resume extension for **Glide**. In Glide V1, every transfer session establishes an ephemeral cryptographic key exchange requiring users to visually compare and accept a 6-digit Short Authentication String (SAS). While this design guarantees zero inter-session tracking, it introduces operational friction for repeated transfers between a user's own devices (e.g. phone to tablet) or trusted collaborators. Furthermore, an abrupt radio link loss during multi-gigabyte transfers requires restarting the transfer from byte zero.

This extension introduces:
1. **Hardware-Backed Persistent Identity:** Secure device key pairs generated inside Android Keystore (`PURPOSE_SIGN | PURPOSE_VERIFY`), eliminating manual SAS prompts for paired peers.
2. **True Cryptographic Resume:** Rejection of naive "offset-only" resume in favor of chunk-level SHA-256 Merkle digest verification, preventing silent data corruption, byte splicing, and file substitution attacks.
3. **Replay & Impersonation Defense:** Mutual ephemeral nonce challenge-response signatures binding resume tokens to active transport sessions.
4. **Governed Retained-Partial Lifecycle:** Sandboxed staging with strict 24-hour expiration (TTL), storage pressure auto-evacuation, and zero unverified byte exposure to public storage.

---

## 2. Cryptographic Persistent Identity Architecture

```
                       TRUST-ON-FIRST-USE (TOFU) LIFECYCLE
┌────────────────────────┐                               ┌────────────────────────┐
│     Sender Device      │                               │    Receiver Device     │
│  (Keystore Key Pair)   │                               │  (Keystore Key Pair)   │
└───────────┬────────────┘                               └───────────┬────────────┘
            │                                                        │
            │ 1. Advertise Identity Fingerprint & Ephemeral PubKey   │
            ├───────────────────────────────────────────────────────►│
            │                                                        │
            │ 2. Manual Mutual 6-Digit SAS Confirmation (First Time) │
            │◄──────────────────────────────────────────────────────►│
            │                                                        │
            │ 3. Pin Peer Identity in Local Encrypted Store          │
            │    (Store Peer Fingerprint, PubKey, Name, Timestamp)   │
            │                                                        │
            │─── SUBSEQUENT SESSIONS: CHALLENGE-RESPONSE AUTH ───────│
            │                                                        │
            │ 4. Issue Random Challenge Nonce (32 bytes)             │
            ├───────────────────────────────────────────────────────►│
            │ 5. Return Keystore Signature of Nonce                  │
            │◄───────────────────────────────────────────────────────┤
            │ 6. Verify Signature against Pinned Public Key          │
            │    ==> Seamless Authenticated Transfer Granted         │
            ▼                                                        ▼
```

### A. Android Keystore Hardware Isolation
- **Key Generation:** Keys are generated via Android Keystore:
  ```kotlin
  val keyGenParameterSpec = KeyGenParameterSpec.Builder(
      "glide_device_identity",
      KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
  ).apply {
      setDigests(KeyProperties.DIGEST_SHA256)
      setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
      setUserAuthenticationRequired(false) // Background transport availability
      setIsStrongBoxBacked(true) // StrongBox hardware HSM where available
  }.build()
  ```
- **Private Key Sanctity:** Private keys never leave the secure hardware enclave (TEE/StrongBox) and cannot be dumped from user memory.
- **Identity Fingerprint:** Each device derives a permanent 32-byte hexadecimal fingerprint from the SHA-256 hash of its encoded public key (`SHA-256(PublicKey.encoded)`).

### B. Trust-On-First-Use (TOFU) Pairing Flow
1. **Initial Pairing (Session 1):**
   - Sender and receiver exchange their permanent public keys during initial connection.
   - Users are presented with the mandatory 6-digit mutual SAS confirmation dialog.
   - Upon explicit acceptance, both devices persist the peer's `(fingerprint, publicKey, displayName, pairedAt)` in a local Room `trusted_peers` table.
2. **Subsequent Connections (Session 2+):**
   - The devices exchange ephemeral 32-byte nonces ($N_s$ and $N_r$).
   - Each peer signs the partner's nonce using its Keystore private key.
   - If the cryptographic signatures match the pinned public keys, the connection is marked **Authenticated** without interrupting the user with manual SAS prompts.
3. **Key Mismatch / MITM Alert:**
   - If an advertised identity fingerprint matches a known peer but presents a modified public key, the connection is instantly aborted with an emergency `IdentityMismatchException`.
   - The UI alerts the user: *"Security Warning: Trusted device identity has changed. Possible impersonation attempt."*

---

## 3. True Resume Protocol Architecture

### A. The "Offset-Only" Vulnerability
A naive resume protocol accepts a request to `resume_from_offset = X`. If an attacker truncates, substitutes, or modifies the sender's file, or if the sender edited the file between sessions, the receiver will append new bytes onto an incompatible base, resulting in corrupted files, invalid checksums, or malicious code injection.

### B. Merkle Chunk-Digest Tree Verification
To guarantee file integrity, Glide mandates **True Resume**:
1. **Chunk Digest Manifest:**
   - The file is divided into uniform chunks (default $1\text{ MB}$ for multi-GB transfers, or $64\text{ KB}$ for standard transfers).
   - The sender generates a chunk digest list: $H_0, H_1, \dots, H_{n-1}$, where $H_i = \text{SHA-256}(\text{Chunk}_i)$.
   - A Merkle root hash $H_{\text{root}} = \text{MerkleTree}(H_0, \dots, H_{n-1})$ is included in the initial offer manifest.
2. **Receiver Chunk Journal:**
   - As chunks arrive in internal staging (`cacheDir/staging/resumable/<session_id>/`), the receiver verifies each chunk against $H_i$ before writing it to disk.
   - The receiver commits verified chunk indexes into an atomic disk journal (`resume_journal`).
3. **Resume Request Negotiation:**
   Upon reconnecting, the receiver issues a signed `ResumeRequest`:
   - `sessionId`: Original transfer UUID.
   - `fileId`: Target file UUID.
   - `verifiedChunkCount`: Number of verified contiguous chunks ($K$).
   - `contiguousByteOffset`: Verified byte offset ($K \times \text{ChunkSize}$).
   - `chunkRootHash`: Expected root hash ($H_{\text{root}}$).
   - `sessionNonce`: Fresh 32-byte nonce binding the request to the active socket.
   - `signature`: Keystore signature over the serialized resume parameters.
4. **Sender Verification & Resumption:**
   - The sender verifies the signature using the receiver's pinned public key.
   - The sender verifies that its current file still yields the identical $H_{\text{root}}$.
   - The sender verifies that `contiguousByteOffset` aligns with chunk boundary $K$.
   - The sender skips chunks $0$ through $K-1$ and begins transmitting from chunk $K$.

---

## 4. Retained-Partial File Lifecycle & Garbage Collection

To prevent unverified partial files from accumulating and exhausting device storage, strict retention governance is enforced:

| Lifecycle Phase | Rule / Threshold | Description |
|:---|:---:|:---|
| **Staging Isolation** | Sandboxed internal cache | Chunks written to `cacheDir/staging/resumable/<session_id>/`. Never written to public storage until 100% verified. |
| **Expiration (TTL)** | $\mathbf{24\text{ hours}}$ | Disrupted partial transfers expire after 24 hours. The cleanup worker automatically purges expired folders. |
| **Storage Pressure Clean** | Space $< 10\%$ or $< 1\text{ GB}$ | If device storage runs critically low, resumable staging is evicted on a FIFO (First-In, First-Out) basis. |
| **Explicit User Purge** | Immediate | If user cancels transfer or declines resumption, all associated staging chunks and journals are immediately deleted. |
| **Public Commit** | Atomic two-stage | Only after the final chunk verifies and the whole-file SHA-256 matches is the file promoted to public storage. |

In strict accordance with Glide's **User Data Sanctity** invariant, unverified or in-flight partial bytes remain fully isolated within `cacheDir/staging/resumable/` and are never exposed to public user directories.


---

## 5. Threat Model Analysis & Invariants

| Threat ID | Threat Category | Attack Vector / Mechanism | Mitigation Strategy |
|:---|:---|:---|:---|
| **T01** | Identity Spoofing | Rogue device broadcasts a trusted peer's device name. | Name is never used for trust; only cryptographic signatures from Android Keystore keys are accepted. |
| **T02** | Replay Attack | Attacker captures a valid `ResumeRequest` and replays it later. | Mutual ephemeral 32-byte nonces ($N_s, N_r$) ensure each resume token is valid only for the active live socket. |
| **T03** | Offset Tampering | Attacker tampers with `contiguousByteOffset` to skip data. | Offset must strictly align with chunk index and chunk hash journal; out-of-bounds offsets are rejected. |
| **T04** | File Substitution | Sender replaces file between disconnect and resume. | Merkle root hash $H_{\text{root}}$ must match exactly; any file modification causes resumption rejection. |
| **T05** | Private Key Theft | Malware on device attempts to steal persistent identity key. | Android Keystore with hardware TEE/StrongBox isolation; private keys are non-exportable. |
| **T06** | Disk Exhaustion | Attacker initiates transfers, disconnects, and leaves partial files. | Strict 24-hour TTL; storage pressure auto-purge; max concurrent resumable sessions capped at 5. |
| **T07** | Partial File Exposure | User browses Downloads folder and finds corrupt partial files. | Partial files remain isolated in internal `cacheDir/staging/resumable/`; zero bytes touch public storage. |
| **T08** | Downgrade Attack | Malicious peer requests legacy non-resumable mode to bypass checks. | Pinned trusted peers enforce negotiated capabilities; unexpected downgrades alert the user. |

---

## 6. Capability Flags & Protocol Envelopes

- **`CAP_PERSISTENT_IDENTITY`**: `0x00000002` (Bit 1)
- **`CAP_TRUE_RESUME`**: `0x00000004` (Bit 2)

### New Control Envelopes for V2 Phase
- `0x09` `IDENTITY_CHALLENGE`: Contains 32-byte challenge nonce.
- `0x0A` `IDENTITY_RESPONSE`: Contains Keystore digital signature.
- `0x0B` `RESUME_REQUEST`: Contains verified chunk offset, root hash, session nonce, and signature.
- `0x0C` `RESUME_ACCEPT`: Sender acknowledges resumption start from chunk index $K$.
- `0x0D` `RESUME_REJECT`: Sender rejects resumption (file changed, invalid offset, or expired).
