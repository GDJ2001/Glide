# Glide full-product quality program

Updated: 2026-10-15. This is the live handoff ledger for the cross-platform quality program. A checked source change is not a target-host build, installed UI inspection, physical transfer, or release approval.

## Product contract

- Native local sharing only. Keep the Glide brand and the same Send, Receive, Activity, Pair devices, and Settings concepts while using each platform's controls and accessibility conventions.
- Keep existing Profile 1 and Profile 2 peers interoperable. Introduce a new negotiated profile only for a demonstrated security or interoperability need. Preserve and migrate trust, verified history, and settings.
- Pairing proves a saved identity; each file offer still needs explicit consent. Completion means the receiver verified exact length and digest and committed the file.
- Keep development artifacts at application version `1.0.0`. GitHub Actions remain disabled by user request.

## Current evidence

| Area | Implemented or observed | Open acceptance gate |
|---|---|---|
| Windows | WPF Send/Receive/Activity/Pair/Settings wired. Activity history follows the Linux card layout. The WPF UI test now isolates its startup from the installed app's single-instance lock; the full desktop solution passed 360 core/Linux and four WPF UI tests, and the WPF suite passed five further repetitions while the installed app was open. The `1.0.0` installer from commit `7b28eaf` was installed on this Windows host; the installed executable hash matched the packaged executable. | Installed light/dark/high-contrast and scaling audit and current physical transfer matrix remain open. |
| Android | Compose app and service-owned transfer runtime are present. Pairing and trust tasks propagate coroutine cancellation instead of showing a false failure. Coordinator-to-UI progress mapping and throttling share one presenter. On 2026-10-15, `:android:app:testDebugUnitTest` and `:android:app:assembleDebug` passed. The debug APK from commit `7b28eaf` installed on connected CPH2371 through ADB; Android reported package `dev.glide.app.debug`, version `1.0.0`, and launch succeeded. Earlier app lint evidence remains dated 2026-10-14. | Installed UI review, measured startup/jank/memory, and current physical transfer matrix remain open. APK install and launch do not prove transfer behavior. |
| Linux | Pair devices uses the Windows action order and card layout. Receive uses separate status and phone-QR cards; the obsolete sender-invitation panel is removed from this page, and the destination remains in Settings. Ubuntu 26 VM package `glide_1.0.0_amd64.deb` from commit `7b28eaf` was reinstalled and launched; `/usr/lib/glide/SOURCE_COMMIT` matched `7b28eaff0508f14e2772a9e1bb74e38fd2bd6c85`. The installed Send screen was inspected in dark mode. Earlier Receive, Activity, and Pair devices inspection was on commit `d890014`. | Recheck all other screens on the current build, review light/high-contrast and minimum-size layouts and keyboard/screen-reader behavior, and perform both physical transfer directions with hashes. The earlier installer-media destination is a plausible but unproven cause of failed receive. |
| macOS/iOS | macOS source layouts now follow the same sidebar order, Windows-style Pair/Receive task flow, and Linux-style Activity history. The macOS Receive view retains a secondary sender-link path. These are source changes only. | Swift target-host compilation, installed UI, and physical evidence. No Mac runner is connected and GitHub Actions must remain disabled. iOS UI parity is outside this desktop pass. |
| Shared desktop runtime | Destination writability and available capacity on both staging and destination volumes are checked before offer consent; sender receives a typed storage failure. Consent callbacks no longer report data transfer before the engine does. The Windows solution passed 358 core and one WPF UI test; the Linux-local snapshot passed 358 core tests on 2026-10-13. | Native Linux physical reproduction; storage pressure and destination-change tests on an installed client. |
| Release evidence | Windows, Linux x64, and Android development packages were built from commit `7b28eaff0508f14e2772a9e1bb74e38fd2bd6c85`. The Windows installer, Ubuntu `.deb`, and Android debug APK SHA-256 values are `e204df4c4aa16044953609aa9696aefaa0c160354e61746c18e563167507e8fc`, `d81262269309ab1881f550a536bb5c6c792b7f09d1e59d5be723b7850b26f2f6`, and `fda2abb8a357147f41d7d6c66a083b7c02287ea7ccf9c7309ddc40f03e98c161`, respectively. Each was installed on its respective Windows, Ubuntu VM, or Android device. A Windows/Linux primary-PC handoff ZIP was created and its contents verified against included checksums. The recovery branch fast-forwarded into `main` and was deleted after `main` reached this commit. | Apple artifacts, release signing, current physical transfer validation, and the five-person usability gate remain open. The packages are development builds, not a release catalog. |

The progress-fill binding has a deterministic requested-width assertion. The broader WPF UI integration test now bypasses production single-instance startup in its test host and passed five repetitions with the installed app open. This closes that automation-harness defect; it does not establish physical transfer or full installed UI acceptance.

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
