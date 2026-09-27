# Reliable sharing delivery — 2026-09-27

This record covers the Windows/Android sender-hosted QR flow and reactive Android
recent activity delivery. It is implementation and build evidence, **not** a
claim that every physical-device release gate has passed.

## Delivered behavior

- A Windows sender with selected items now presents a high-contrast QR code.
  Android's **Receive** route provides **Scan PC QR code**, establishes the
  pinned-TLS connection, and starts the existing consent-controlled receive
  flow.
- The LAN bootstrap carries an explicit sender or receiver connection role.
  A PC hosting a send accepts only the receiver role and asks the PC user to
  confirm the connected device before it sends offer metadata or bytes.
- The invitation remains single-use, short-lived and bound to the existing
  certificate pin/token authority. A wrong-role bootstrap is rejected without
  consuming the active invitation.
- Android Home observes the persisted history stream and displays up to five
  terminal sessions. Active sessions stay on the resumable-transfer path;
  completed, failed and cancelled entries open their history details.
- The static acceptance tests now find the repository root correctly whether
  Gradle starts in the repository or its `android/` composite project.

## Automated evidence

| Check | Result |
|---|---|
| `dotnet build desktop/Glide.slnx -c Release --nologo` | Passed, 0 warnings, 0 errors |
| `dotnet test desktop/tests/Glide.Tests/Glide.Tests.csproj -c Release --nologo` | Passed, 308 tests |
| `dotnet test desktop/tests/Glide.Windows.UI.Tests/Glide.Windows.UI.Tests.csproj -c Release --nologo` | Passed, 1 test |
| `android/gradlew.bat :android:app:testDebugUnitTest` | Passed, 649 tests; 3 historical-artifact checks skipped |
| `android/gradlew.bat :android:app:assembleDebug` | Passed |

The desktop regression specifically proves that a sender-hosted invitation
rejects a sender-role bootstrap without consuming it, then accepts the
receiver-role bootstrap exactly once. It does not replace a two-device test.

## Rebuilt local artifacts

| Artifact | SHA-256 | Status |
|---|---|---|
| `build/distributions/windows/Glide-Setup-0.1.0-win-x64.exe` | `6F213B9745AF1C03BBF3199685E7ED442B9DB397301D00B1D990C5EE999742EA` | Unsigned local installer |
| `android/app/build/outputs/apk/debug/app-debug.apk` | `C8AEAE7238BD06F2AC8CD239E7BF0679363ED6F0ED8D3E89C3F364A35C2E648B` | Debug APK |

The installer was run silently against the local per-user installation. The
installed `Glide.exe` matched the staged payload SHA-256
`93E2F61EEE030D983D61E892CBF7F06B8859C04FE58E2F4A4FBF4550A31D0955`.

No ADB device or emulator was connected when the APK was built, so it was not
installed or physically verified in this delivery.

## Remaining release blockers

1. Run the visible Windows-to-Android QR flow on the target PC and phone,
   record receiver consent and matching output hashes.
2. Complete PC-to-PC device selection and mutually authenticated first-contact
   pairing. The planned UKEY2 Windows interop boundary has not been added; do
   not substitute the older public-key hash display for authenticated pairing.
3. Run Windows-to-Windows and Android-to-Android physical matrices on separate
   devices, including Ethernet-to-Wi-Fi, Bluetooth-disabled, cancellation,
   expired codes and network interruption.
4. Sign the Windows installer and Android release artifacts before any public
   distribution.

Related: [QR pairing repair](QR_PAIRING_FIX_2026-09-27.md),
[Windows completion ledger](WINDOWS_COMPLETION_LEDGER.md), and
[LAN authentication ADR](../adr/ADR-0018-lan-qr-authentication-and-routing.md).
