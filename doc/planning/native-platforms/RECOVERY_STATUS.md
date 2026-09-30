# Glide native completion recovery ledger

Updated 2026-10-01 on branch `fix/native-completion-hardening`. This file is the restart point for implementation and release decisions. The working tree inherited from the prior delivery remains intact; no prior report establishes completion.

| Gate | Status | Evidence and next action |
|---|---|---|
| R00 Baseline and claims | Implemented, review pending | Historical completion and checksum claims withdrawn; duplicate planning copy archived under `doc/evidence`; target-host evidence still required. |
| R01 Apple invitations and listener identity | In progress | Swift wire hash excluded, epoch seconds/token/schema checks added, raw-key SPKI bug corrected; a real listener `SecIdentity`, reachable address, one-time authority, and Mac build remain. |
| R02 Apple transfer engine | Open | Current source does not implement the authenticated batch control sequence or independent receive digest verification. |
| R03 iPhone/iPad | Open | Listener dispatch and real Share Extension target/staging remain. Mac/Xcode required for build. |
| R04 macOS | Open | Accepted connection handler is empty. Mac/Xcode required for build. |
| R05 Linux | In progress | Fresh-link sends, receiver listener dispatch, Quick Share QR, saved-peer identity pin, and persistent verified history now build on Windows. Linux-host end-to-end runtime remains unverified. |
| R06 Consent and process ownership | In progress | Linux/Apple auto-accept removed, desktop base offer default denies, Linux GUI single-instance lock and sequential listener added. GUI/daemon joint ownership and target-host checks remain. |
| R07 Storage, permissions, privacy | Open | Map production controls and close gaps before security validation. |
| R08 Browser removal and docs | In progress | Historical trust claims labeled, toolchain manifest corrected, quarantine bypass instruction removed; full active-reference audit remains. |
| R09 Packages and CI | In progress | Clean-commit Linux tar/deb/rpm script and macOS/Linux CI jobs authored; no native packages have been generated or installed from this branch. Cross-platform release assembly remains. |
| R10 Implementation freeze | Blocked | All R00–R09 and target-host builds must pass first. |
| Phase 2 validation | Blocked | Starts after R10; historical tests are baseline only. Physical interoperability and five-user usability evidence absent. |

Evidence levels are distinct: source implemented, target-host built, automated test passed, target-OS installed, physical transfer verified, and participant usability passed. A result at one level does not imply the next.

## Baseline known at recovery start

- Desktop .NET solution built and 289 tests passed on Windows before recovery changes; rerun after implementation freeze.
- Recovery-source build: `dotnet build desktop/Glide.slnx -c Release --no-restore` passed with 0 warnings/errors on Windows. `tools/check-architecture.ps1 -FailFast` passed. These are Phase 1 checks, not final validation.
- Graphify was re-extracted and clustered after source edits (12,998 nodes, 32,327 edges); it reported duplicate generic import/namespace node IDs, so community labels remain provisional.
- Apple Swift app targets have no Mac build evidence. The Windows machine cannot certify Swift/Xcode output.
- Linux UI has a build but its sender and receiver actions are not end-to-end authenticated transfers.
- Previous artifact checksum values and “committed” claims in `doc/evidence/FINAL_DELIVERY_REPORT.md` are withdrawn; its artifact table is historical, not a release catalog.
- The earlier `docs/planning/native-platforms/` copy is preserved under `doc/evidence/historical-plan-2026-09-30/`. New authoritative planning changes belong only in `doc/`.

## Exact next implementation work

1. **Apple identity and invitation:** create and store a P-256 `SecIdentity` containing a certificate and private key on iOS/macOS; pass it into `GlideNetworkListener.start`. Publish an invitation only after the listener reports a bound port, using reachable interface addresses and the SPKI of its presented certificate. Add an atomic one-use authority shared with the session engine. `IosTransferViewModel.generateLocalInvitation` and `MacTransferViewModel.generateLocalInvitation` intentionally publish no placeholder code today.
2. **Apple protocol engine:** replace the deliberate failure boundary in `GlideTransferEngine` with full bootstrap, HELLO/ACK, capability negotiation, paged OFFER/decision, ITEM_START/READY, bounded DATA, ITEM_END with independently verified digest and length, ITEM_RESULT, BATCH_END/RESULT, cancellation, timeouts, and one-active-batch ownership. Do not re-enable the old direct-stream code. Use `QuarantineStagingStore.finalizeAndCommit(expectedSha256Hex:expectedLengthBytes:destinationDirectory:rawFilename:)` only after authenticated ITEM_END.
3. **Apple app integration:** connect incoming listener dispatch, user offer consent, sender-hosted recipient confirmation, trusted identity enrollment/revocation, verified history, security-scoped file access, and bounded Share Extension App Group staging. Add the Share Extension as a real Xcode target and matching entitlements. Build app, extension, and package on a Mac before claiming any Apple implementation gate.
4. **Linux qualification:** run the new Avalonia flows on Ubuntu 24.04 and Fedora Workstation 44; verify both QR roles, fresh invitation sends, nearby selection, saved identity pinning, consent, cancel, history persistence, and daemon/GUI listener ownership. The Windows .NET build is only a source compilation gate.
5. **Release engineering:** finish target-host packaging for Windows, Android, and Apple with clean-commit manifests; then assemble exact artifact hashes from those manifests. The retired `tools/package-distributions.ps1` cannot create release evidence. Run Phase 2 only after these implementation slices and R10 pass.

## Release rule

Report **implementation complete** only after R10. Report **program validated** only after all Phase 2 checks have evidence against the final commit and exact artifacts. Missing Mac/Linux hosts, devices, or participants keep the corresponding gates open.
