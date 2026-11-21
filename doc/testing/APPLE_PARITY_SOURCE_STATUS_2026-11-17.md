# Apple parity source status — 2026-11-17

Baseline: `main` at `4a102faddd09ba851a319be1a8b522af9f9d581c`. This report describes the working-tree changes made after that commit. It is not a release or target-host validation record.

## Implemented in source

- macOS and iOS saved-device sends browse `_glide._tcp`, keep distinct candidates with duplicate display names, use current advertised ports and presence IDs, and reserve manual local-address entry as a secondary fallback. The selected saved identity is checked before a file offer. Candidate retries end once a session authenticates.
- iOS advertises `tr=1` only after its listener is ready, keeps receiving across foreground tabs, and withdraws idle availability when backgrounded. An active transfer requests a UIKit background task extension. The app does not promise new incoming connections while backgrounded.
- Profile 2 inbound sessions reject a stale advertised presence ID. Trust is checked again after identity proof and before accepting a file offer. A corrupt trust file cannot be overwritten by an empty replacement, and trust mutations are serialized with persistence.
- A second incoming Profile 2 connection receives a bounded `BUSY` response while a transfer or pairing session is active.
- Incoming approvals appear at the app-window level. Paired sending opens Activity. Activity retains transfer progress or a result, and failed sender selections remain staged. Declines are reported as declines rather than zero-file successes.
- iOS verified received files use the app's Files-visible Documents location (`On My iPhone > Glide`). The unused Photos export setting was removed. macOS defaults to `Downloads/Glide` and stores a user-selected destination as a security-scoped bookmark.
- Debug prints of endpoints, session identifiers, and offer bodies were removed from the Apple transport.

## Local checks

- `tools/check-architecture.ps1 -FailFast`: passed.
- `git diff --check`: passed.
- iOS `Info.plist` and macOS entitlements: parsed successfully.
- `apple/scripts/test.ps1`: blocked because this Windows host has no Swift/Xcode toolchain.

## Open gates

- Compile Swift Package and both Xcode targets on a Mac. Run the new resolver and trust-file tests plus the full Apple suite. Fix any compiler or runtime findings before packaging.
- Verify iPhone/iPad and Mac installations, foreground LAN discovery, duplicate-name candidates, pairing, consent, trust revocation, QR regression, 5 GB transfers, exact SHA-256, cancellation, and network changes on physical hosts.
- An iOS custom Files destination requires document-picker access, a persistent minimal bookmark, and coordinated file writes. The safe default is implemented; the custom-destination option remains open.
- Measure Apple transfer throughput and memory before applying the Windows pipeline optimizations. No performance claim is made from source inspection.
- GitHub Actions remains disabled. There is no Mac build host or Apple signing facility available in this session.

## Build-host preparation — 2026-11-18

- `apple/scripts/run-backend-tests.sh` now fails early without macOS, Xcode 26.6, and an iOS Simulator runtime. It waits for both simulators to boot and treats failed app launches or premature macOS exit as failures instead of reporting a pass.
- `apple/scripts/create-source-handoff.py` packages the current `main` commit, tracked Apple changes, and untracked Apple sources for a Mac without discarding this working tree. The output is a source handoff, not a compiled or tested Apple artifact.
- Local architecture checks, shell syntax, `git diff --check`, and plist parsing pass. Swift compilation, Xcode target builds, simulator launches, and physical Apple verification remain blocked because no Mac is available. Windows or Linux Swift cannot compile this Apple-only package with its `Network`, `Security`, `CryptoKit`, and `UIKit` imports.
