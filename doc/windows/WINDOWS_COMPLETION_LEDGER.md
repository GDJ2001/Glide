# Windows completion ledger

Last reviewed: 2026-09-26. This ledger starts from the current checkout, not prior completion
claims. A task moves to `VERIFIED` only with its named evidence in
[release gates](WINDOWS_RELEASE_GATES.md). The count below is deliberately **not 28/28**.

## Current evidence

- `dotnet build desktop/Glide.slnx -c Release --nologo` passed with zero warnings and errors.
- `dotnet test desktop/Glide.slnx -c Release --no-build --nologo` passed: **297/297**.
- The self-contained portable archive build passed at
  `build/distributions/windows/glide-windows-win-x64.zip` using `package-windows.ps1 -SkipInstaller`
  with SHA-256 `20EB88DB61CB1EA57B01254E5867BC00D6A00B96732EF549C6C13F73446B22E4`.
- NSIS 3.12+ was not available on the development machine, so no installer binary has been compiled
  or exercised.
- No physical Windows↔Windows or Windows↔Android journey has been verified for this checkout.

## Task ledger

| ID | Status | Scope and current evidence | Next completion evidence |
|---|---|---|---|
| WIN-001 | VERIFIED | Current desktop source/build baseline captured above; concurrent root/Android changes are intentionally untouched. | Re-capture after each release-candidate branch cut. |
| WIN-002 | VERIFIED | This ledger and release-gate matrix establish Windows-only authority. | Keep status/evidence current. |
| WIN-003 | IMPLEMENTED | Windows CI now builds the full solution, runs every test without a filter, builds the portable artifact, and archives its report/artifact. | Verify the first remote workflow run and preserve its URL/commit evidence. |
| WIN-004 | IN PROGRESS | `DesktopTransferRuntime` owns one active batch; WPF now calls it rather than directly constructing the engine for send/receive. | Extract remaining networking/listener/trust lifecycle from `MainViewModel`; add WPF-facing tests. |
| WIN-005 | IMPLEMENTED | Per-user mutex and current-user named-pipe activation are in `WindowsSingleInstance`; no process-name termination. | UI test second launch, malformed payload, and separate-user rejection. |
| WIN-006 | VERIFIED | State schema versioning preserves legacy documents and archives malformed state before safe defaults; two focused tests pass. | Extend migrations as settings/history fields evolve. |
| WIN-007 | IN PROGRESS | LAN discovery/pairing foundations are present. | Physical discovery, QR/link pairing, expiry, network-change, and firewall tests. |
| WIN-008 | IMPLEMENTED | File/folder expansion now preserves validated nested paths and rejects duplicates/reparse points. Empty folders are visibly unsupported because the current protocol has no directory entry. | Physical nested-folder and Unicode transfer tests; protocol extension if empty directories are required. |
| WIN-009 | IN PROGRESS | Streaming send/receive foundations, progress, cancellation, and single-batch ownership exist. | Bounded-memory and cancellation recovery tests with real peers. |
| WIN-010 | IN PROGRESS | Destination and history foundations exist. | Verify collision policy, open/reveal actions, failed/cancelled history, and staging cleanup. |
| WIN-011 | IN PROGRESS | Persistent trust components exist. | Device list, revocation, rotation, and re-enrollment UI plus security tests. |
| WIN-012 | NOT STARTED | Resume journal/storage exists but production session wiring is incomplete. | Verified pause/restart/identity/source revalidation. |
| WIN-013 | NOT STARTED | Compatibility requires independent Android device evidence. | Windows↔Android bidirectional and resume conformance tests. |
| WIN-014 | IN PROGRESS | Branded WPF shell exists. | Keyboard, Narrator, high-contrast, DPI, dark/light, and adaptive-window qualification. |
| WIN-015 | IN PROGRESS | File/URI activation works through the single-instance handoff. | Tray, notifications, taskbar progress, Explorer integration tests. |
| WIN-016 | NOT STARTED | Settings/about/privacy/licenses are incomplete as feature areas. | Functional settings and redacted diagnostic export. |
| WIN-017 | IMPLEMENTED | Maintained `desktop/windows/installer/Glide.nsi` replaced generated installer logic. | Compile with NSIS and execute clean-install qualification. |
| WIN-018 | IN PROGRESS | Re-running NSIS setup is the documented repair path; per-user upgrade overwrites owned payload. | Old-version migration and repair matrix. |
| WIN-019 | IMPLEMENTED | Uninstall invokes `Uninstall.exe`; no app-side recursive install-directory deletion. NSIS deletes known owned resources only. | Clean-uninstall test proving adjacent and received files survive. |
| WIN-020 | IMPLEMENTED | `GlideUpdateCoordinator` uses strict Ed25519 verification and refuses unconfigured/non-HTTPS feeds. | Signed feed/download tests using the real public release infrastructure. |
| WIN-021 | IN PROGRESS | Update coordinator prohibits updates while runtime reports active work. | Download/stage/install/restart and interrupted-update recovery tests. |
| WIN-022 | BLOCKED | Public release repository, publisher identity, signing cert, and protected publishing credentials are absent. | Owner creates/accesses `GDJ2001/Glide-Releases`; signed pipeline publishes a test channel. |
| WIN-023 | IN PROGRESS | 297 desktop tests include runtime/folder selection coverage. | Add transition, history migration, trust, resume, and update negative tests. |
| WIN-024 | NOT STARTED | No Windows UI automation project/journeys exist. | Automated send/receive/consent/cancel/settings/history/trust flows. |
| WIN-025 | NOT STARTED | No physical native transfer matrix evidence. | Named-device matrix and hashes for both directions. |
| WIN-026 | NOT STARTED | No completed security/accessibility/performance qualification. | Gate reports and no release-blocking findings. |
| WIN-027 | NOT STARTED | Installer source exists but no NSIS compiler/test VM evidence. | Clean/install/upgrade/repair/uninstall matrix. |
| WIN-028 | NOT STARTED | No signed candidate or release dossier. | Signed artifacts and every release-gate record. |

## Next recommended task

**WIN-004 / WIN-006:** finish the application composition boundary before adding more UI features:
move listener, discovery, persistent trust, and state migration ownership from `MainViewModel` into
application services. This prevents new Settings, tray, resume, and update work from adding to the
current presentation-layer networking coupling.

## Non-negotiable implementation rules

1. Do not put a complete file in memory; stream with bounded buffers.
2. Do not silently flatten a folder, overwrite a destination, follow a reparse point, or mark a
   transfer complete before hash verification and final publication.
3. Do not let an update install while a batch, consent, preparation, or queued work exists.
4. Do not remove an arbitrary directory during uninstall; delete only installer-owned paths and
   offer application-data deletion separately.
5. Do not mark a task verified from source inspection, loopback tests, or a portable archive alone.
