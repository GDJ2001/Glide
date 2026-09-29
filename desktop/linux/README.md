# Linux desktop development

The Linux CLI and daemon use the shared .NET projects in `desktop/src/`. This directory contains the Linux desktop entry, systemd user service, daemon configuration examples, and packaging script.

From the repository root, build and test the shared code:

```sh
dotnet build desktop/Glide.slnx -c Release
dotnet test desktop/tests/Glide.Tests/Glide.Tests.csproj -c Release --no-build
```

Build the Linux tarball, Debian package, and self-extracting `.run` installer with PowerShell 7 and the native Linux `tar`, `bash`, and `dpkg-deb` tools. On Windows, the script uses WSL for those Linux tools:

```sh
pwsh -File desktop/linux/build-linux-packages.ps1 -Configuration Release -Runtimes linux-x64
```

The daemon reads the peer-specific settings in `glide-daemon.env.example`; configure a real trusted peer and destination before enabling `glide.service`.
