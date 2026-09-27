# PC-to-Android send-flow repair — 2026-09-27

## Reported failure

After adding a file in the Windows application, the user could not find a working normal-flow action for sending it to the Android phone. The only `Send files` button was inside the collapsed manual-address expander. Pairing also required another click after confirmation, and exceptions raised by the asynchronous command could leave the UI looking inactive.

## Repair

- Moved the primary send action into the always-visible nearby-device workflow.
- Added an explicit selected-file count and destination-aware action label.
- Made the normal action open a phone-specific guided pairing sheet when no invitation is available.
- Changed the instructions to the concrete Android path: `Receive files > Show QR code > Copy invite`.
- Continued the pending send automatically after the invitation is confirmed.
- Added visible cancellation and failure states, including a failure dialog for unexpected errors.
- Cleared consumed one-use invitations after every connection attempt so a stale credential is never shown as reusable.
- Added a source contract test preventing the primary send action from returning to the Advanced expander.

## Verification

| Check | Result |
|---|---|
| Windows solution build | PASS — 0 warnings, 0 errors |
| Desktop engine test suite | PASS — 306/306 |
| WPF frontend integration test | PASS — loaded the production `MainWindow`, added a real temporary file, verified the primary button was visible/enabled, invoked it through WPF automation, rendered the pairing sheet, entered a physical-phone invitation, invoked `Connect and send`, and waited for successful completion |
| Windows package | PASS — NSIS installer rebuilt |
| Local installation | PASS — packaged and installed executable SHA-256 matched |
| Physical PC-to-Android transfer from WPF | PASS — the real frontend buttons completed authenticated TLS bootstrap and transfer against the attached phone in 23 seconds |
| Android protocol result | PASS — the 39-byte WPF test item displayed as Transfer complete and Verified |
| End-to-end SHA-256 | PASS — source content and Android private received file both `7510bfe6b76f3c2fe25b5b781b92893b32d65e7359fe147e4a3cee68917361d8` |

The physical test used a non-sensitive synthetic text file and the attached OPPO CPH2371 running the debug application. No pairing invitation or bearer token was retained in this report.

## Installed artifact

- Installer: `build/distributions/windows/Glide-Setup-0.1.0-win-x64.exe`
- Installer SHA-256: `B53B68342A8186EA5B3E809652B664A4868890662B29B47A3536394B509087AF`
- Installed executable: `%LOCALAPPDATA%\Programs\Glide\Glide.exe`
- Installed executable SHA-256: `AEC27272B796CA379F18243B7A3F55B67034733C141E5A222DAD096BE6855B69`

The installer remains an unsigned local development artifact. Publication still requires the protected signing pipeline.
