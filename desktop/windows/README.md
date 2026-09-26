# Windows Desktop Development

Glide's Windows 11 x64 desktop implementation using WPF on .NET 10 LTS.

## Projects

- `Glide.App` — Thin CLI adapter over the shared desktop runtime
- `Glide.Core` — Protocol, validation, identities and storage contracts
- `Glide.Desktop` — Desktop transfer engine, session management
- `Glide.Transport` — LAN discovery and authenticated TLS transport
- `Glide.Windows.UI` — WPF UI: Views, ViewModels, Services
- `Glide.Tests` — Unit and integration tests

## Building

```powershell
dotnet build Glide.slnx -c Release
```

## Packaging

```powershell
.\package-windows.ps1
```

`package-windows.ps1 -SkipInstaller` creates a self-contained portable archive. A complete
installer build requires NSIS 3.12 or later. See [the Windows completion ledger](../../doc/windows/WINDOWS_COMPLETION_LEDGER.md)
for what is implemented and what still requires release evidence.
