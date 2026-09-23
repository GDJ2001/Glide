# Linux Desktop Development

Glide's Linux desktop implementation shares the .NET Generic Host codebase with Windows.
The shared desktop lives in `windows/` (Glide.App, Glide.Desktop, Glide.Core).

## Linux-specific files

This folder contains Linux-only packaging and configuration:

- `build-linux-packages.ps1` — Build .deb/.rpm packages
- `glide.desktop` — XDG desktop entry
- `glide.service` — systemd user service unit
- `glide-daemon.env.example` — Environment config template
- `glide-daemon-policy.example.json` — Security policy template

## Building for Linux

```bash
cd windows
dotnet build Glide.slnx -c Release -r linux-x64
```

## Packaging

```powershell
./build-linux-packages.ps1
```
