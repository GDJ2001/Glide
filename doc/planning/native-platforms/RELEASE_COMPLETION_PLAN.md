# Glide native release completion plan

Status: **plan only; no release gate is cleared by this document**. Prepared from the repository at `19b54de6fdabfe3f5271edc9914ea3ddc776f19b` on 2026-10-03. The earlier implementation freeze was `45ccb95f07f4b8a139458d573396c8037f7c582a`; corrective work below requires a **new** source freeze and new artifacts.

## Objective and rules

Deliver installable native Glide clients for Windows, Android, Ubuntu/Fedora Linux, macOS, iPhone, and iPad; demonstrate their UI flows and authenticated transfers on the declared target platforms; and publish a release only after the evidence and owner sign-off gates pass. Browser transfer remains retired. Preserve the existing protocol, one-use invitations, explicit interactive consent, private staging, verified SHA-256 commits, and single-active-batch behavior.

The order is mandatory: **correct and finish implementation/packaging → freeze one clean source commit → build every artifact from that commit → run formal validation → fix defects and repeat validation on a new candidate → decide release**. A source fix after freeze creates a new candidate and requires rebuilding and rerunning its required gates. Keep historical reports as history, never rewrite them into evidence for the new candidate.

Use these evidence levels separately: source implemented, target-host built, package produced, installed and launched, automated test passed, observed cross-device transfer passed, accessibility/usability passed, and publicly released. `PASS`, `FAIL`, `BLOCKED`, and `NOT RUN` must describe execution, not intent. An unavailable host, device, signing identity, or tester means `BLOCKED`, not `PASS`.

## Current baseline and corrections required

| Item | Current evidence | Required disposition |
|---|---|---|
| Source | Clean `19b54de`; release manifest refers to `45ccb95` | Treat `45ccb95` as a historical candidate. Select a new candidate after corrections. |
| Windows | NSIS installer and portable ZIP exist; installation lifecycle recorded | Rebuild and repeat on final candidate; preserve existing verified earlier evidence as historical. |
| Android | Manifest lists debug-signed `app-debug.apk` and `app-debug.aab`; release report records zero attached ADB devices for that run | Produce signed **release** APK and AAB; install exact APK on a physical Android device. A debug build is not the distribution candidate. |
| Linux | Ubuntu 24.04 WSL 2 backend and `.deb` lifecycle recorded; RPM metadata inspected; x64/ARM64 CI archives exist | Install and exercise `.deb` on Ubuntu and `.rpm` on Fedora. A separate Linux machine is required for independent LAN qualification; WSL remains VM evidence. Qualify ARM64 on a suitable host before claiming ARM64 support. |
| Apple | Native macOS/iPhone/iPad simulator backend tests and target builds recorded; manifest entry is `apple-backend-test-evidence.zip` | Produce an installable macOS package and provisioned iOS/iPadOS app with embedded Share Extension. Test on a physical Mac, iPhone, and iPad. |
| Release metadata | `tools/assemble-release-manifest.ps1` hard-codes commit, hashes, toolchains, remote artifact sizes, and version; `RELEASE_CATALOG.md` contains unexpanded PowerShell expressions | Make generator data-driven and fail closed; regenerate catalog from verified files and build metadata. |
| Cross-platform report | Calls itself complete while P01 is awaiting a device and most pairs cite engine/unit/simulator evidence | Reclassify each pair by actual observed run. Unit tests are not pairwise transfers. The older HARD-017 WSL host is the same laptop as Windows, not an independent Linux machine. |
| Versioning/docs | Windows/Android artifacts use `0.1.0`, Linux packages use `1.0.0`; validation matrix still says Fedora 41 and contains `docs/` links | Choose one product release identity, distinguish package-specific revisions, and update active `doc/` links and target matrix. |

## Phase A — Finish implementation and prepare one candidate

### A01. Repair release truth and evidence model

1. In `doc/planning/native-platforms/RECOVERY_STATUS.md`, `doc/testing/CROSS_PLATFORM_RELEASE_REPORT.md`, and `build/distributions/RELEASE_CATALOG.md`, replace “Complete & Authoritative,” “certifies,” and unearned pairwise `PASS` claims with the level actually observed. Keep the old report labeled **historical candidate / superseded** once a new candidate exists.
2. Create one machine-readable gate ledger under `doc/testing/evidence/release/<candidate-sha>/` with fields: `gateId`, `platformOrPair`, `candidateCommit`, `artifactSha256`, `environment`, `status`, `runId`, `evidencePaths`, `blocker`, and `reviewer`. Validate schema and reject a `PASS` without the required evidence paths. Do not store raw pairing links, tokens, full fingerprints, personal filenames, or signing secrets in logs.
3. Correct stale `docs/` references and reconcile `doc/planning/native-platforms/VALIDATION_MATRIX.md` with supported OS targets. Archive outdated web-sharing and old-checksum claims as historical evidence.

**Exit:** every existing release claim maps to a real run, artifact, and source commit; no pairwise transfer is inferred from a unit test or simulator build.

### A02. Finish production packaging and signing routes

1. **Windows:** use `desktop/windows/package-windows.ps1` to build installer and portable ZIP from the same clean commit. Record installer and installed-binary hashes, version, publisher/signature state, single-instance behavior, and uninstall path. For public distribution, sign the executable and installer with the publisher's Windows code-signing identity and verify the signatures; if unavailable, label the output an unsigned private test build.
2. **Android:** build non-debuggable release APK and AAB with a controlled release keystore or Play App Signing route. Keep the key outside Git/CI logs. Verify APK signature, package ID, version code/name, target SDK, and installable generated APKs from the bundle. Retain the R8 mapping file privately for crash decoding. Do not use a debug APK/AAB in the final distribution manifest. [Android bundle testing guidance](https://developer.android.com/guide/app-bundle/test)
3. **Linux:** produce x64/ARM64 `.deb`, `.rpm`, and portable archives using `tools/package-linux-native.sh` in trusted target-architecture jobs. Embed `SOURCE_COMMIT` and package version; declare native dependencies. Add package install/upgrade/uninstall scripts that run on Ubuntu 24.04 and Fedora 44; do not describe `rpm -qip` inspection as installation. If ARM64 execution cannot be tested, keep ARM64 release qualification open.
4. **macOS:** extend `apple/scripts/package-macos.sh` beyond its current unsigned ZIP to a signed, installable `.app` inside a ZIP/DMG/PKG. For direct distribution, use Developer ID signing, hardened runtime as required, notarize, staple, and verify the result on a clean Mac. Keep a separate unsigned development output if useful, clearly labeled. [Apple notarization guidance](https://developer.apple.com/documentation/security/notarizing-macos-software-before-distribution)
5. **iPhone/iPad:** extend `apple/scripts/package-ios.sh` beyond its unsigned device archive. Register the app and Share Extension identifiers and `group.dev.glide.shared`, prepare provisioning for both targets and registered test devices, archive for generic iOS device, export a signed `.ipa`, and verify embedded extension and entitlements before installation. One iOS app may cover both devices, but phone and tablet remain separate UI/runtime gates. Signing material belongs in protected local keychain or trusted CI secrets, never untrusted pull-request jobs. [Apple registered-device distribution](https://developer.apple.com/documentation/xcode/distributing-your-app-to-registered-devices)
6. Pin each build's actual Xcode, .NET, JDK/AGP, OS image, and packaging-tool versions in build metadata. The repository currently pins Xcode 26.6; verify the chosen runner has that exact Xcode build before freezing rather than silently falling back.

**Exit:** each claimed client has a real installable artifact of the intended release class, tied to one source commit; signing and installability are independently verified. A missing Apple provisioning identity leaves Apple packaging blocked.

### A03. Replace the manifest/catalog generator

1. Refactor `tools/assemble-release-manifest.ps1` to accept the candidate SHA, artifact directory, and per-platform build manifests as inputs. Read actual files and compute hashes at assembly time; do not embed prior hashes, sizes, build URLs, versions, or toolchain text in the script.
2. Keep **runnable packages** separate from **test-evidence archives**. A CI ZIP can contain package files, but its hash does not replace individual package hashes. `apple-backend-test-evidence.zip` is evidence, not the macOS/iOS release artifact.
3. Reject dirty source, mismatched embedded commit, absent target artifact, mismatched version, unsigned artifact labeled signed, wrong package architecture, stale build run, or malformed metadata. Verify remote CI artifacts by downloading to a controlled workspace and hashing actual bytes before cataloging them.
4. Generate valid JSON and Markdown from structured data; add a test that parses the JSON, checks Markdown contains no literal `$(` / `\$Commit` expressions, and verifies every local SHA-256 independently. Make artifacts immutable by candidate SHA and run ID.
5. If GitHub artifact attestations are enabled, attest release binaries from trusted build jobs and verify each attestation against repository, workflow, and commit. An attestation establishes provenance; it does not prove runtime correctness. [GitHub attestation guidance](https://docs.github.com/en/actions/concepts/security/artifact-attestations)

**Exit:** a reproducible manifest lists every installable package with exact hash, source commit, build job, toolchain, signing state, and target OS; the catalog renders correctly.

### A04. Implementation review and freeze

1. Reconcile visible Send, Receive, Pair, Activity, Settings, QR, Share Extension, cancellation, retry, and history actions against production handlers on each platform. Remove dead buttons/placeholders. Confirm the sender-hosted path requires recipient confirmation and every interactive offer requires receiver approval, including trusted peers.
2. Review security boundaries: one-time invitation use, pinned listener certificate, SAS confirmation, strict framing/item/batch limits, private staging, exact length and independent digest checks, path/symlink defense, cleanup, process ownership, and redacted logs. Browser transfer must stay absent.
3. Run implementation checks: `pwsh -File tools/doctor.ps1`, `pwsh -File tools/check-architecture.ps1 -FailFast`, targeted protocol/UI tests, and target-host compilation. Record exact commands and exit codes. These are pre-freeze implementation checks, not the formal Phase B validation.
4. Review the full diff, create one clean candidate commit, and tag/record its full SHA in the ledger. Trigger target-host builds only from that SHA. A failure or code fix means a new candidate SHA and a fresh artifact set.

**Exit:** all intended product and packaging work is implemented; one clean commit is frozen; no active source modification is hidden in a build.

## Phase B — Validate the frozen candidate

### B01. Automated and package checks

Run from the frozen SHA: `pwsh -File tools/verify.ps1`, `dotnet test desktop/Glide.slnx -c Release`, Android unit/instrumented tests and lint using the `android/gradlew.bat :android:app:<task>` module path, `apple/scripts/run-backend-tests.sh` on macOS, Linux GUI/runtime tests on Linux, and architecture/security contract fixtures across Kotlin/C#/Swift. Log host, toolchain, command, exit code, test count, failed/skipped count, and artifact hash. Any failure is a defect; fix on source, rebuild, and rerun affected gates.

### B02. Installation and UI lifecycle by target

| Target | Required execution |
|---|---|
| Windows 11 x64 | Fresh install, launch, second-instance file/link activation, one listener owner, tray/window behavior, upgrade, uninstall, retained state, and signature verification. |
| Physical Android | Install exact release APK (and an APK set derived from release AAB where distribution uses it); fresh launch, QR camera, Share Sheet, background/foreground, transfer service, upgrade, uninstall/data policy. Record device model/API and installer hash. |
| Ubuntu 24.04 x64 | Fresh `.deb` install, GUI and daemon launch, receiving/listener conflict behavior, desktop association, upgrade, uninstall, retained trust/history and permissions. |
| Fedora 44 x64 | Fresh `.rpm` install with actual package manager, GUI/daemon launch, upgrade, uninstall, desktop integration, data retention. |
| Linux ARM64 | Install and execute ARM64 package on ARM64 Linux before claiming ARM64 support; otherwise leave this variant blocked or omit it from the release claim. |
| Physical macOS | Install signed package through ordinary Finder flow; verify Gatekeeper/notarization, Dock/window/menu behavior, file access, upgrade/uninstall, retained state. |
| Physical iPhone and iPad | Install signed `.ipa` or test-channel build on each registered device; verify app and Share Extension, App Group queue, camera QR, permissions, background/foreground, and update behavior. |

For each UI, perform keyboard/screen-reader walkthroughs (Narrator, TalkBack, VoiceOver, Orca), light/dark/high-contrast where supported, large text/scaling, narrow/snap windows, long/Unicode filenames, failure/retry copy, and destination opening. A simulator/VM can pre-screen layout; physical-device results must be recorded separately.

### B03. Real cross-platform transfer matrix

Treat Windows, Android, iPhone, iPad, macOS, and Linux as **six client classes**. The existing `VALIDATION_MATRIX.md` has **15 unordered pairs and 30 directions**. For every direction, run both receiver-hosted fresh pairing and sender-hosted Quick QR where supported: **60 successful arrangements**. If a mode is not supported, fix the product or explicitly change the supported-feature claim before approval. Use actual installed UI and packages on the target endpoints; CLI/backend runs add diagnostic evidence but cannot substitute for UI completion.

For each arrangement, use a deterministic corpus with at least 35 items spanning two offer pages, a zero-byte file, a Unicode/long name, a folder, and a large file; compare every destination file's independently measured SHA-256 with the source manifest. Exercise a >=1 GiB transfer on each platform as sender and receiver at least once, within available device storage. Record transfer start/end, peer OS/device, which side hosted, invitation expiry, consent/SAS observations, offered/committed bytes, terminal status, source/destination hashes, and sanitized logs. Never record the live invitation/token or full fingerprint.

For each pair, also run explicit offer rejection, mid-stream cancellation, wrong/expired/replayed invitation or pin, and reconnect with a fresh invitation. Cover trust enrollment/revocation, busy state, disconnect, disk-full, path traversal, digest corruption, and stale staging on representative boundaries for every implementation. Acceptance: zero unauthorized payload bytes on authentication failure, no unverified committed file, no false success, cleanup after restart, and successful reattempt requiring authentication/consent. The existing `doc/testing/HARD017_NATIVE_INTEROP_RUNBOOK.md` provides a starting corpus/evidence pattern, but its WSL result cannot clear independent Linux hardware qualification.

**Scheduling:** Run Windows↔Android first to restore the physical baseline; then Windows↔macOS/iPhone/iPad and Android↔Apple; then Apple↔Apple; then the five Linux pairs on a separate Linux LAN host. Windows↔Linux under WSL is useful prequalification but stays distinct from the final physical gate. Do not label macOS CI runner tests or isolated Swift unit tests as observed Windows/Android↔Apple transfers.

### B04. Usability, security, and correction loop

Five representative first-time participants attempt send, receive, and locate-file tasks on the installed UI without coaching; at least four of five must complete each core task. Record completion and blockers without personal data. Run targeted accessibility review and an adversarial security review of consent, staging, logs, installers, entitlements/exported components, and keys. Classify defects by severity; fix all data-integrity, authentication, consent, install, transfer, and core-usability blockers before release. Every source fix resets the candidate SHA/artifact hashes and requires rerunning the full required validation matrix on the new candidate; keep old runs attached to their old SHA only.

## Phase C — Release decision and delivery

1. Generate the final ledger, run-index, artifact manifest, catalog, checksum file, build logs, signatures/provisioning verification, and independent hash audit from the **same candidate**. Verify all packages are still retrievable; ephemeral CI retention links alone are insufficient release storage.
2. Have a reviewer sample raw run records against the summarized 15-pair table, inspect package signatures/entitlements, and confirm that no release statement promotes `BLOCKED` or `NOT RUN` to `PASS`. The release report must state exact supported platform/architecture/version scope and known limitations.
3. Only after every required gate passes, prepare release notes, install/uninstall instructions, upgrade notes, licenses, and support/rollback procedure. Obtain owner sign-off on the exact manifest and intended distribution channel before publishing. Publish the immutable artifacts and verify download hashes/signatures/attestations after upload. If a required host or device is unavailable, issue a narrower explicitly qualified release or hold the program; do not call the six-client program fully validated.

**Definition of done:** a first-time user can install the native app on every claimed target; Send/Receive/Pair/Activity/Settings and platform share entry points work; 30 directed pair flows and both QR roles have observed end-to-end evidence; every committed file matches source SHA-256; rejection/failure never commits unverified data or reports success; package lifecycle, accessibility/usability, and release provenance gates pass; the final report accurately describes the exact artifacts released.

## First executable work item

Start with **A01 + A03** on a new `codex/` correction branch from the current clean tree: fix the inaccurate report/status language and data-driven manifest/catalog rendering. Add an automated generator regression test that fails on literal template expressions and missing Apple installable artifacts. Then resolve Apple provisioning and independent Linux host availability while A02 proceeds. Do not rerun the formal matrix against the old `45ccb95` packages.
