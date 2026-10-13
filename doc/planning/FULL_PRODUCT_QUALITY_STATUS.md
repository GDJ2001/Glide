# Glide full-product quality program

Updated: 2026-10-13. This is the live handoff ledger for the cross-platform quality program. A checked source change is not a target-host build, installed UI inspection, physical transfer, or release approval.

## Product contract

- Native local sharing only. Keep the Glide brand and the same Send, Receive, Activity, Pair devices, and Settings concepts while using each platform's controls and accessibility conventions.
- Keep existing Profile 1 and Profile 2 peers interoperable. Introduce a new negotiated profile only for a demonstrated security or interoperability need. Preserve and migrate trust, verified history, and settings.
- Pairing proves a saved identity; each file offer still needs explicit consent. Completion means the receiver verified exact length and digest and committed the file.
- Keep development artifacts at application version `1.0.0`. GitHub Actions remain disabled by user request.

## Current evidence

| Area | Implemented or observed | Open acceptance gate |
|---|---|---|
| Windows | WPF Send/Receive/Activity/Pair/Settings wired. UI integration test passed three consecutive runs on this Windows host on 2026-10-13. Activity history filtering now has a dedicated view model. | Installed light/dark/high-contrast and scaling audit; current physical pair and transfer matrix; repeat the UI test in a clean environment. |
| Android | Compose app and service-owned transfer runtime are present. Pairing and trust tasks now propagate coroutine cancellation instead of showing a false failure. App, core, and LAN unit tests, app lint, and debug APK build passed on Windows on 2026-10-13. | Installed UI review, measured startup/jank/memory, current physical transfer matrix. No USB device was attached on 2026-10-13. |
| Linux | Receive destination control moved to Settings. Activity history now has a dedicated view model and a distinct no-matches state; 23 focused Linux UI tests passed on Windows. A clean Linux-local snapshot of commit `4e2d7e7` passed 357 desktop core tests and built the Avalonia app with zero warnings on Ubuntu 24.04 WSL on 2026-10-13. | Ubuntu 26 installed UI review, both transfer directions and hashes. The earlier screenshot showed an installer-media destination, a plausible but unproven cause of failed receive. |
| macOS/iOS | Swift sources exist; no new Apple change in this program yet. | Target-host compilation, installed UI, and physical evidence. No Mac runner is connected and GitHub Actions must remain disabled. |
| Shared desktop runtime | Destination writability is checked before offer consent; sender receives a typed storage failure. Consent callbacks no longer report data transfer before the engine does. The Windows solution passed 357 core and one WPF UI test; the Linux-local snapshot passed 357 core tests on 2026-10-13. | Native Linux physical reproduction; storage pressure and destination-change tests. |

The current WPF UI test contains a progress-indicator fill assertion and passed in this checkout. Do not reopen the historic progress defect without a new failing installed build or reproduction.

## Screen and state inventory

For each native client, review these states with the same terminology and an explicit owner for every action:

| Flow | Required states and actions |
|---|---|
| Send | Empty/staged selection, skipped files, QR sharing, paired-device selection, connecting, recipient confirmation, retry after failure. |
| Receive | Off/ready, transfer QR/link, incoming offer details, accept/decline, destination in Settings, verified completion and open folder. |
| Pair devices | Discovery/empty/busy, ready-to-pair expiry, PIN or QR/link as platform-appropriate, both-party approval, saved identity, revocation/identity change. |
| Activity | Active bytes/rate/progress, verification, cancel, complete/failed/declined result, searchable verified history. |
| Settings and OS | Appearance, destination, trusted devices, advanced details, theme/contrast/scaling, activation, foreground/background lifecycle, install/upgrade/uninstall. |

## Ordered work and evidence gates

1. Baseline and design: capture installed screenshots and performance traces by platform; reconcile `doc/design/GLIDE_USER_FLOWS.md` and `doc/design/GLIDE_DESIGN_SYSTEM.md`; log defects with reproduction and expected behavior. Baseline measurements are **not yet collected** for this program.
2. Windows: complete feature-view-model/service extraction, UI state and accessibility review, and repeatable installed UI tests. Preserve WPF Snap/window behavior and existing trust/history data.
3. Android: separate session ownership, pairing, and presentation responsibilities without Android dependencies entering `:transfer-core`; complete Compose state/accessibility review and measured performance work.
4. Linux: complete feature-view-model/service extraction and Windows flow parity; reproduce Windows-to-Linux receiving on an installed Ubuntu 26 client with a writable destination; record both results and file hashes.
5. Apple: complete native source and UI work; use a Mac host for Swift package, macOS, iOS, and extension builds. Do not mark Apple built or validated from Windows source review.
6. Security and optimization: map threat-model controls to production code and adversarial cases; measure startup, idle discovery, rendering, streaming, memory, and cancellation before changing hot paths. Keep security, consent, and integrity limits unchanged unless a reviewed versioned contract replaces them.
7. Final gate: architecture checks, full automated suites, release-like artifacts from one commit, installation/upgrade/uninstall evidence, physical bidirectional transfer matrix with source/destination SHA-256, and five-person usability check. Report unavailable gates as blocked.

Related open Profile 2 gates remain in [PAIR_ONLY_AND_TRUSTED_RECONNECT_CHECKLIST.md](native-platforms/PAIR_ONLY_AND_TRUSTED_RECONNECT_CHECKLIST.md). Historical reports are audit records, not current acceptance evidence.
