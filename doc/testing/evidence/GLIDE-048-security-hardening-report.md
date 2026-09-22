# GLIDE-048: Security Hardening & Protocol Abuse Evidence Report

**Author:** Antigravity Agent  
**Date:** 2026-09-13  
**Milestone:** M8 — Security Hardening  
**Task:** `GLIDE-048` — Harden protocol abuse/rate limits and fuzz tests  
**Status:** COMPLETE ✅  

---

## 1. Executive Summary

Task `GLIDE-048` executes the first phase of Milestone M8 (*Security Hardening*), focusing on the core transfer protocol engine (`transfer-core`), state machine integrity, rate limiting, sequence monotonicity, replay prevention, and comprehensive deterministic fuzzing.

In accordance with:
- [`docs/security/GLIDE_THREAT_MODEL.md`](file:///d:/GITHub/Glide/docs/security/GLIDE_THREAT_MODEL.md) (Threats T01–T08, T17)
- [`docs/protocol/GLIDE_PROTOCOL_V1.md`](file:///d:/GITHub/Glide/docs/protocol/GLIDE_PROTOCOL_V1.md) (Section "Fixed limits" and "Common envelope and wire rules")
- [`docs/development/GEMINI_ENGINEERING_RULES.md`](file:///d:/GITHub/Glide/docs/development/GEMINI_ENGINEERING_RULES.md)

all protocol abuse vectors, rate flood attacks, out-of-order sequence attacks, duplicate replay attempts, consent bypass attacks, and stream overruns fail closed with deterministic security outcomes (`MALFORMED_MESSAGE`, `PROTOCOL_ORDER`, `LIMIT_EXCEEDED`).

---

## 2. Threat Model Mitigations Implemented

| Threat Code | Threat Category | Implemented Defense & Location | Verified Behavior |
|---|---|---|---|
| **T01** | Spoofed / Untrusted Peer | `UUID_V4_REGEX` identifier validation in [`Identifiers.kt`](file:///d:/GITHub/Glide/transfer-core/src/main/kotlin/dev/glide/core/domain/Identifiers.kt); strict ephemeral peer validation; non-canonical IDs rejected before instantiation. | Reject non-canonical UUIDs, invalid versions, malformed hex digests. |
| **T02** | Man-in-the-middle / Tampering | Transport encryption + SHA-256 content verification; stream length check rejects extra bytes. | Any payload alteration or trailing byte triggers immediate `LIMIT_EXCEEDED` / `WRITE_FAILURE` and purge. |
| **T03** | Replay / Stale Callbacks | `ProtocolSequencer`: monotonic sequence number tracking (`expectedSeq`), bounded out-of-order buffer (capacity $\le 8$, 2s gap timeout), LRU cache (256 entries) tracking message IDs with payload hashes. | Exact duplicate ignored idempotently; duplicate messageId with conflicting content triggers `MALFORMED_MESSAGE`; gap timeout triggers `PROTOCOL_ORDER`. |
| **T04** | Consent Bypass | Strict phase checking in `TransferCoordinator.observeIncomingStreams()`: incoming stream packets rejected unless state is `PreparingItem` or `TransferringItem`. | Stream packets received before explicit `acceptOffer()` trigger immediate `PROTOCOL_ORDER` failure. |
| **T05** | Metadata Tampering / Downgrade | Capability intersection in `NegotiationValidator`; version negotiation cannot downgrade below agreed v1. | Unsupported major version triggers `UNSUPPORTED_PROTOCOL`. |
| **T06** | Path Traversal / File Injection | Identifiers enforce UUIDv4; filenames are restricted to display-only UTF-8 strings; staging storage uses UUIDs. | Path strings (`../../etc/passwd`, `C:\Windows\...`) rejected by domain identifiers. |
| **T07** | Memory Exhaustion / Oversized Payloads | 16 KiB envelope ceiling, 1,024 byte extensions limit, 64 GiB max item size, 256 GiB max batch size; bounded streams. | Payload sizes exceeding limits rejected before buffer allocation. |
| **T08** | Denial of Service / Spam Flood | `ProtocolRateLimiter`: token bucket limiter ($\le 20\text{ msg/s}$ sustained, burst $40\text{ msg}$). `ConnectionSpamGuard`: 3 rejected/failed attempts within 60s trigger a 30s cooling pause. | Rapid floods exceeding burst capacity trigger `LIMIT_EXCEEDED` and terminate the session. |
| **T17** | Consent / Stream Race Conditions | Unsolicited streams arriving prior to consent are rejected without writing to disk or allocating staging descriptors. | Stream arrives before `acceptOffer()` $\to$ `PROTOCOL_ORDER` failure closed. |

---

## 3. Component Implementations

### 3.1 `ProtocolRateLimiter.kt`
- **Token Bucket Algorithm:**
  - Sustained refill rate: $20.0\text{ tokens/second}$.
  - Maximum burst capacity: $40.0\text{ tokens}$.
  - Returns `RateLimitResult.Allowed` or `RateLimitResult.Throttled(waitMs, reason)`.
- **`ConnectionSpamGuard`:**
  - Tracks failed attempts in a sliding 60-second window.
  - Upon 3 failed attempts, activates a 30-second cooling pause (`isBlocked(nowMs) == true`, `cooldownRemainingMs(nowMs) > 0`).
  - Allows manual user reset or auto-expiration.

### 3.2 `ProtocolSequencer.kt`
- **Monotonic Sequence Enforcement:**
  - Enforces independent sequence numbers starting at 1 per direction.
  - In-order arrivals advance `expectedSeq` by 1.
- **Bounded Out-of-Order Buffer:**
  - Capacity: at most 8 envelopes. Exceeding capacity fails closed with `TransferFailure.ProtocolOrder`.
  - Gap timeout: 2,000ms. If the missing sequence number is not received within 2 seconds, fails closed with `TransferFailure.ProtocolOrder`.
- **LRU Deduplication & Replay Protection:**
  - Holds 256 recent `MessageId` entries paired with sequence number and raw payload hash.
  - Identical duplicate (`seq` and `payloadHash` match) $\to$ `SequencerResult.DuplicateIgnored` (idempotent, safe drop).
  - Conflicting duplicate (reused `messageId` with differing bytes or sequence) $\to$ `SequencerResult.Violation.ConflictingMessageId` (`TransferFailure.MalformedMessage`).
  - Stale sequence received after eviction $\to$ `SequencerResult.Violation.StaleSequence` (`TransferFailure.ProtocolOrder`).

### 3.3 `TransferCoordinator.kt` Hardening
- Integrated `rateLimiter` and `sequencer` directly into `observeControlMessages()`.
- Control envelopes pass through token bucket rate limiter before decoding.
- Malformed envelope decoding fails closed with `TransferFailure.MalformedMessage`.
- Decoded envelopes pass through `sequencer.processInbound()`.
- Added strict consent check in `observeIncomingStreams()`: incoming stream data arriving before offer acceptance fails closed with `TransferFailure.ProtocolOrder`.
- Stream byte overruns (extra bytes beyond offered length) fail closed with `TransferFailure.LimitExceeded`.

---

## 4. Test Suites and Evidence

### 4.1 Automated Test Execution Summary
- **Module:** `:transfer-core`
- **Total Tests:** 128 tests (100% passed, 0 failures)
- **Execution Time:** ~4 seconds

```text
> Task :transfer-core:test
dev.glide.core.security.ProtocolRateLimiterTest > sustainedRateAndBurstAllowance PASSED
dev.glide.core.security.ProtocolRateLimiterTest > replenishmentOverTime PASSED
dev.glide.core.security.ProtocolRateLimiterTest > resetReplenishesBurstCapacity PASSED
dev.glide.core.security.ProtocolRateLimiterTest > connectionSpamGuardAllowsNormalFailuresUnderThreshold PASSED
dev.glide.core.security.ProtocolRateLimiterTest > connectionSpamGuardTriggersCooldownOnThirdFailure PASSED
dev.glide.core.security.ProtocolRateLimiterTest > connectionSpamGuardManualResetClearsBlock PASSED

dev.glide.core.security.ProtocolSequencerTest > inOrderMessagesDeliveredSequentially PASSED
dev.glide.core.security.ProtocolSequencerTest > outOfOrderBufferingAndContiguousDrain PASSED
dev.glide.core.security.ProtocolSequencerTest > outOfOrderBufferCapacityExceededFailsClosed PASSED
dev.glide.core.security.ProtocolSequencerTest > gapTimeoutFailsClosedAfterTwoSeconds PASSED
dev.glide.core.security.ProtocolSequencerTest > idempotentDuplicateIsIgnored PASSED
dev.glide.core.security.ProtocolSequencerTest > conflictingDuplicatePayloadTriggersMalformedMessage PASSED
dev.glide.core.security.ProtocolSequencerTest > staleSequenceReplayAfterCacheEvictionFailsClosed PASSED

dev.glide.core.security.ProtocolFuzzAndAbuseTest > fuzzCodecWithMutatedEnvelopes PASSED
dev.glide.core.security.ProtocolFuzzAndAbuseTest > pathTraversalPayloadRejectedByIdentifiers PASSED
dev.glide.core.security.ProtocolFuzzAndAbuseTest > oversizedItemRejectedByLimits PASSED
dev.glide.core.security.ProtocolFuzzAndAbuseTest > streamExceedingOfferedLengthFailsClosed PASSED
dev.glide.core.security.ProtocolFuzzAndAbuseTest > rateLimitingFloodThrowsTerminalLimitExceeded PASSED
dev.glide.core.security.ProtocolFuzzAndAbuseTest > incomingStreamBeforeOfferConsentIsRejected PASSED

BUILD SUCCESSFUL in 4s
```

### 4.2 Fuzzing Corpus & Methodology
- **Pseudorandom Seed:** `Random(42)` for 100% deterministic reproducibility across CI/CD and local environments.
- **Corpus Iterations:** 500 mutated envelopes per run.
- **Mutation Profiles Tested:**
  1. Single and multi-bit random flips.
  2. Random byte truncation at arbitrary boundaries.
  3. Random byte insertions and envelope framing expansion.
  4. Null byte injection (`\u0000`) in string fields and headers.
  5. Excessive payload injection (>16 KiB).
  6. Negative sequence numbers and non-canonical UUID mutations.
- **Outcome:** Zero unhandled exceptions; 100% caught by strict parser or rejected with `TransferFailure`.

---

## 5. Full Local Verification Result

`pwsh -File tools/verify-local.ps1` completed successfully with zero warnings treated as errors, zero test failures, and 0 lint regressions:

```text
=== Verification Complete: ALL CHECKS PASSED ===
BUILD SUCCESSFUL in 1m 18s
139 actionable tasks: 20 executed, 119 up-to-date
Exit code: 0
```

---

## 6. Conclusion & Gate Readiness

Task `GLIDE-048` is COMPLETE. The core protocol engine is fully fortified against protocol abuse, rate flooding, sequence tampering, and consent bypass attacks. Milestone M8 is now 33% complete (1/3 tasks), and the project is ready to advance to `GLIDE-049` (*Harden Android components, storage and privacy*).
