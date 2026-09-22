# Windows Desktop Development

Glide's Windows desktop implementation using WPF on .NET 8.

## Projects

- `Glide.App` — Entry point, CLI, system tray, single-instance enforcement
- `Glide.Core` — Core engine: transport, security, storage, pairing
- `Glide.Desktop` — Desktop transfer engine, session management
- `Glide.Transport` — Transport layer: LAN, WebRTC, browser gateway
- `Glide.Windows.UI` — WPF UI: Views, ViewModels, Services
- `Glide.Tests` — Unit and integration tests

## Building

```powershell
dotnet build Glide.slnx -c Release
```

## Packaging

```powershell
./package-windows.ps1
```
