# Desktop to Android paired transfer status — 2026-11-14

## Implemented

- Android keeps one trusted LAN listener while Glide is foregrounded and has saved peers. The listener remains separate from the QR invitation and current transfer session. Optional background receiving uses a foreground notification with a Stop action.
- Android advertises the bound listener port and a fresh presence value with trusted reconnect capability. It withdraws stale advertisements when the network changes and rejects a stale presence value before an offer.
- Windows and Linux refresh discovery before a paired send, preserve candidate presence metadata, authenticate the saved identity over the live TLS connection, and retry resolution once on a stale presence response. Inbound desktop connections no longer invent a phone listening port.
- File offers still require receiver consent. Failed sender attempts retain staged selections.
- English and Sinhala–English guides now describe saved-device sends to Android without a second QR scan. Reader-facing Apple and glossary sections were removed. Original screenshot pixels remain unchanged.

## Automated evidence

- `pwsh -File tools/check-architecture.ps1 -FailFast`: passed, 0 issues.
- `dotnet test desktop/Glide.slnx -c Debug --no-restore --nologo` with .NET SDK 10.0.404: 401 backend and 4 Windows UI tests passed.
- `android/gradlew.bat :app:testDebugUnitTest :data:testDebugUnitTest :transport-lan:testDebugUnitTest :app:lintDebug :app:assembleDebug --no-daemon`: passed. The app suite has 659 tests with 3 skipped. An unrelated Nearby virtual-time test timed out once under the full suite and passed both isolated and full reruns.
- The offline guide package was rebuilt with 8 bookmarks per edition, 52 accounted-for source captures, 50 unique published screenshots, and byte-for-byte asset matching.

## Open physical gate

No Android device was attached to ADB at this checkpoint. Windows to Android and Linux to Android saved-device transfers have **not** been physically verified. Do not describe this feature as physically accepted until paired sends from Home, History, and Settings, restart and network changes, repeated small and large sends, decline/cancel/busy/revocation cases, and source/destination SHA-256 checks have been recorded on installed devices. The optional background receiving behavior also needs an installed-phone check. The updated Receive switch and pair-only approval/result screens are still missing from the illustrated screenshot set.

GitHub Actions remains disabled.
