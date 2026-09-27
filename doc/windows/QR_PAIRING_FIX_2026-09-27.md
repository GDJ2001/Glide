# Windows QR recognition repair — 2026-09-27

Related: [Windows completion ledger](WINDOWS_COMPLETION_LEDGER.md).

## Report and diagnosis

The Android sender remained on its camera preview when scanning the Windows receiver's QR.
The PC used Ethernet (`192.168.0.224`) and the phone used Wi-Fi (`192.168.0.125`) on the
same router. The advertised endpoint matched the PC, its listener bound port 42424, and
ADB ping/TCP probes reached that endpoint. These probes establish network reachability,
not a completed Glide handshake or transfer.

A synthetic invitation-sized payload (`glide://pair/v1#` followed by 650 ASCII bytes)
encoded by the previous Windows `QrCodeGenerator` produced a version-18 symbol that
the independent Java ZXing 3.5.4 decoder rejected with `ChecksumException`.
The previous desktop QR tests inspected dimensions and finder patterns but never decoded
the output. Custom format-bit order, data-placement logic, and approximate block tables
made the implementation unsuitable as a standards authority.

## Repair

- Keep Glide's `QrCode`, `EncodeText`, `EncodeBinary`, and renderer contracts.
- Delegate encoding to pinned **QRCoder 1.8.0**; remove the custom encoder, including
  its approximate high-version error-correction tables.
- Strip QRCoder's four-module quiet zone before returning the matrix because existing
  Glide renderers supply their own quiet zone. Preserve UTF-8 byte-mode encoding.
- Add **ZXing.Net 0.16.11** only to the test project. Independently decode six payload
  cases spanning small, invitation-sized, larger symbols and all four ECC levels.
- Ship QRCoder's MIT notice with the application and remove only that owned notice on uninstall.
- Preserve invitation format, certificate pinning, TLS, consent and existing branding.

Dependency reference: [QRCoder releases](https://github.com/Shane32/QRCoder/releases)
and [NuGet QRCoder 1.8.0](https://www.nuget.org/packages/QRCoder/1.8.0), checked 2026-09-27.

## Evidence

- `dotnet test desktop/tests/Glide.Tests/Glide.Tests.csproj -c Release --nologo`:
  **305 passed, 0 failed, 0 skipped**.
- QR subset: **13 passed**, including six independent decoding regressions.
- The same synthetic version-18 matrix from the replacement decoded successfully with
  Java ZXing 3.5.4, recovering all 666 characters.
- Self-contained WPF publish and NSIS compilation passed. The unsigned candidate is
  `build/distributions/windows/Glide-Setup-0.1.0-win-x64.exe` (also `setup.exe`).
  Installer SHA-256: `67762C2945E1BAD9F97C6C2A1001E8E3ED9B8F6D72E62AD53C4223FE94DB2615`.
- Local installation returned exit code 0. Installed `Glide.exe` matches the staged
  executable SHA-256 `75276C20F2BB23CA1E62AE036CF742E9B114F16ED1254CEABF80DC411EE83C2A`.
  The dependency license is installed alongside it. The updated app launched and
  its Receive screen advertised the port-42424 listener with an enlarged QR.

This candidate comes from working changes on base `44ba2b9`, including pre-existing
pairing presentation changes; it is not a signed or published release.

Physical camera recognition, consent, transfer and SHA-256 validation must be recorded
separately; automated decoding alone does not mark WIN-007 or WIN-025 verified.
