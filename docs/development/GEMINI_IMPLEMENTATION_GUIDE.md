# Gemini / AI implementation guide

Start every development session from the [documentation portal](../README.md), then read the [current hardening/completion plan](../planning/CODE_REVIEW_IMPROVEMENT_AND_HARDENING_PLAN.md), the [HARD-002 reconciliation](../planning/HARD_002_SOURCE_DOC_CLAIM_RECONCILIATION.md), and the selected task in [TODO_MASTER](../TODO_MASTER.md).

Glide contains substantial Android, Windows/.NET, Linux, browser/LAN, Apple and remote-transfer work. Historical planning and completion records are evidence, not a substitute for inspecting the current checkout.

**Current next READY task:** [HARD-003](../TODO_MASTER.md#hard-003).

## Authority order

For current work, resolve conflicts in this order:

1. Current HARD task in `TODO_MASTER.md`.
2. Current hardening/completion plan and HARD reconciliation reports.
3. Actual source, executable configuration and current tests.
4. Applicable protocol specification and accepted ADR.
5. Historical task/session/release evidence.

Never assume a historical `COMPLETE`, release or certification label applies to later code. HARD-002 explicitly classified post-review Apple and remote code as partial component/library implementation rather than current production/interoperability certification.

## Current scope

The active hardening cycle completes Android local sharing, Windows 11/Linux local sharing, secure browser local sharing, folders, persistent trust, true resume and the quality/release gates required to ship them.

Native Apple applications and remote/Internet transfers are **deferred**, even though implementation material exists. Preserve that material; do not extend it unless a current HARD task or explicit user instruction authorizes it.

## Immediate HARD-003 rule

HARD-003 is a regression task. Before repairing production behavior, add executable tests/fixtures that expose the confirmed defects identified by the review/reconciliation. Prefer tests that exercise production wiring or independently implemented peers. Source-text checks alone do not satisfy HARD-003. Record which tests are red against the defect and which later task is responsible for the fix.

## Task execution protocol

1. Read the exact HARD task, dependencies, acceptance criteria, regression tests and evidence requirements.
2. Inspect current branch/commit, relevant code, config, tests and recent history before editing.
3. Reproduce the problem or prove the missing wiring with a production-path test where practical.
4. Keep scope bounded. Do not combine protocol changes, file moves, refactors and feature behavior into one change unless the task explicitly requires it.
5. Implement through established runtime/service/engine boundaries. Do not bypass them from UI code.
6. Run task-specific tests first, then the appropriate wider build/lint/test gates.
7. Perform physical/independent-platform/manual tests whenever the task gate requires them. Do not substitute loopback, source-text or emulator evidence.
8. Review security, storage, cancellation, privacy, performance and accessibility for every changed production path.
9. Update current documentation and task evidence. Preserve historical records rather than silently rewriting history.
10. Record a session report with commit/diff identity, changed files, commands/results, outstanding manual checks, blockers and the next READY task.

## Architecture rules

### Android

- `transfer-core` remains transport/platform independent.
- The service-owned `TransferRuntime` is the target owner for production discovery/pairing/session/transfer lifetime.
- ViewModels observe immutable state and send typed commands; they do not construct transports or own active transfer scopes.
- Navigation carries opaque IDs, not credentials, sockets, coordinators or live transport objects.
- Room schema changes require migrations, exported schemas and migration tests.

### Desktop

- WPF and CLI must use the same shared transfer/application engine.
- Networking, protocol state, hashing, file I/O and recovery do not belong in ViewModels.
- Hashing/streaming must be bounded-memory; do not use whole-file production reads to calculate digests.
- Windows/Linux security and storage behavior remain explicit platform adapters around shared protocol/application logic.

### Protocol and interoperability

- Kotlin, C# and later browser/native adapters implement the same ordering, pagination, identifiers, consent and terminal-result semantics.
- LAN Profile 1 currently uses the reconciled 5-byte header and canonical record kinds; do not revive stale 4-byte framing from historical Apple documentation.
- Any wire change updates specifications and shared golden fixtures before dependent implementations are considered complete.
- Do not weaken one implementation to accommodate a broken peer. Fix the incompatible peer.

## Security rules

- TLS encryption alone is not peer authentication.
- QR bootstrap uses real live listener endpoints/fingerprints and one atomic invitation lifecycle.
- Unpaired/SAS authentication is not acceptable unless both peers derive the same authenticated value from a correctly exchanged/bound nonce and require explicit confirmation.
- Persistent trust needs explicit enrollment, protected private keys, peer binding and revocation.
- Trusted peers never imply automatic acceptance of arbitrary incoming files.
- Unverified files remain quarantined; path names never directly determine unsafe filesystem locations.
- Resume advances only across contiguous verified chunks and ends with whole-file integrity verification.
- Never commit secrets, signing material or sensitive runtime values.

## Browser rule

The active direction is a trusted static HTTPS client using authenticated local WebRTC DataChannels. The existing plaintext HTTP web-transfer implementation is historical/transition code and must not be treated as release-safe. Do not add new release dependencies on it.

## Verification rules

Passing tests are necessary, not sufficient. Examples:

- A source scanner proving a symbol exists does not prove authentication works.
- A loopback transfer does not prove Android↔Windows interoperability.
- An emulator does not prove physical radio reliability.
- A virtual multi-gigabyte stream does not prove real-device network throughput.
- A built artifact is not a released artifact.
- A TURN packet codec without credential negotiation or live relay proof is not an operational TURN deployment.
- A Swift coordinator with default in-memory providers is not a native Apple runtime.

Use `REVIEW_REQUIRED` or `BLOCKED` rather than manufacturing completion when required evidence is unavailable.

## Repository changes

A future target tree (`android/`, `desktop/`, `web/`, `protocol/fixtures/`) is authorized only under HARD-033. Until then, preserve current paths. Do not opportunistically move/rename source while fixing behavior.

## Session completion

Before ending a task, update `TODO_MASTER.md` only when its evidence contract is met, add/update the relevant session report, and name the next READY task. Deployment, publishing and release operations require their own explicit authorization and are never implied by build/test completion.