# Glide full-product quality program

Updated: 2026-10-14. This is the live handoff ledger for the cross-platform quality program. A checked source change is not a target-host build, installed UI inspection, physical transfer, or release approval.

## Product contract

- Native local sharing only. Keep the Glide brand and the same Send, Receive, Activity, Pair devices, and Settings concepts while using each platform's controls and accessibility conventions.
- Keep existing Profile 1 and Profile 2 peers interoperable. Introduce a new negotiated profile only for a demonstrated security or interoperability need. Preserve and migrate trust, verified history, and settings.
- Pairing proves a saved identity; each file offer still needs explicit consent. Completion means the receiver verified exact length and digest and committed the file.
- Keep development artifacts at application version `1.0.0`. GitHub Actions remain disabled by user request.

## Current evidence

| Area | Implemented or observed | Open acceptance gate |
|---|---|---|
| Windows | WPF Send/Receive/Activity/Pair/Settings wired. Activity history now follows the Linux card layout: header actions, search and All/Sent/Received controls, and relaxed verified rows. The `1.0.0` installer from commit `d890014` was installed on this Windows host; the installed executable hash matched the packaged executable. The current desktop run passed 360 core/Linux tests and three of four WPF UI tests. | The remaining WPF hidden-window UI test fails intermittently at layout geometry assertions and needs a reliable harness or visible-window reproduction. Installed light/dark/high-contrast and scaling audit and current physical transfer matrix remain open. |
| Android | Compose app and service-owned transfer runtime are present. Pairing and trust tasks propagate coroutine cancellation instead of showing a false failure. Coordinator-to-UI progress mapping and throttling now share one presenter across the active runtime and legacy session manager, with a focused progression/terminal-state test. On 2026-10-14, all 652 app unit tests and app lint passed on Windows; core and LAN unit tests and a debug APK build passed on 2026-10-13. | Installed UI review, measured startup/jank/memory, current physical transfer matrix. No USB device was attached on 2026-10-13. |
| Linux | Pair devices now uses the Windows action order and card layout. Receive uses separate status and phone-QR cards; the obsolete sender-invitation panel is removed from this page, and the destination remains in Settings. Ubuntu 26 VM package `glide_1.0.0_amd64.deb` was rebuilt from commit `d890014`, reinstalled, and launched. `/usr/lib/glide/SOURCE_COMMIT` matched `d8900142f4f455993b8ceaa746f9b31a056c31f6`; installed Send, Receive (off/on), Activity, and Pair devices were visually inspected in dark mode at the current VM size. The Linux app built with zero warnings and errors; the current Windows-hosted desktop run passed all 360 core/Linux tests. | Review light/high-contrast and minimum-size layouts, keyboard/screen-reader behavior, and perform both physical transfer directions with hashes. The earlier installer-media destination is a plausible but unproven cause of failed receive. |
| macOS/iOS | macOS source layouts now follow the same sidebar order, Windows-style Pair/Receive task flow, and Linux-style Activity history. The macOS Receive view retains a secondary sender-link path. These are source changes only. | Swift target-host compilation, installed UI, and physical evidence. No Mac runner is connected and GitHub Actions must remain disabled. iOS UI parity is outside this desktop pass. |
| Shared desktop runtime | Destination writability and available capacity on both staging and destination volumes are checked before offer consent; sender receives a typed storage failure. Consent callbacks no longer report data transfer before the engine does. The Windows solution passed 358 core and one WPF UI test; the Linux-local snapshot passed 358 core tests on 2026-10-13. | Native Linux physical reproduction; storage pressure and destination-change tests on an installed client. |
| Release evidence | The Windows `Glide-Setup-1.0.0-win-x64.exe` and Linux `glide_1.0.0_amd64.deb` development packages were rebuilt from commit `d8900142f4f455993b8ceaa746f9b31a056c31f6`. Their independently checked SHA-256 hashes are `27ad236ede5209bd44e32296bac8893f53be3bc8f3ad5d01f36f094ed73a1fac` and `bebcb123b1a11396af1cace3d4e2bffaa32aeb83b70e3ccc66f8affa007eb1af`, respectively. Both were installed locally. | Android and Apple artifacts from that same commit, release signing, and physical validation remain open. These packages are unsigned development builds, not a complete release catalog. |

The progress-fill binding now has a deterministic requested-width assertion. One broader WPF UI integration test still fails at varying later geometry checks when run in a hidden window. This is an open automation-harness gate; it is not evidence of a physical transfer failure or an installed UI pass.

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
