# Linux desktop development

The Avalonia desktop app, CLI, and daemon use the shared .NET projects in `desktop/src/`. This directory contains the GUI, desktop entry, systemd user service, and daemon configuration examples. The current completion gates are tracked in [the recovery ledger](../../doc/planning/native-platforms/RECOVERY_STATUS.md).

From the repository root, build the GUI during implementation:

```sh
dotnet build desktop/linux/Glide.Linux.UI/Glide.Linux.UI.csproj -c Release
```

After the recovery implementation freeze, run the shared tests with `dotnet test desktop/tests/Glide.Tests/Glide.Tests.csproj -c Release` as part of Phase 2.

Build unsigned development `.tar.gz`, `.deb`, and `.rpm` packages from a clean commit on a native Linux host with .NET 10, Bash, tar, dpkg-deb, rpmbuild, rpm2cpio, cpio, and coreutils. Run once on an x64 host and once on an ARM64 host; RPM packaging rejects a mismatched host architecture before publishing. With no runtime argument, the script selects the current host architecture.

```sh
bash tools/package-linux-native.sh
# Or on the matching host:
pwsh -File tools/package-linux.ps1 -Runtimes linux-x64
```

Outputs are under `build/distributions/runs/<commit>-<UTC>/linux/`. `BUILD-MANIFEST.txt` records the source commit, runtime, and build tools. `SHA256SUMS-<runtime>.txt` hashes the three packages. Packaging compares the GUI and CLI bytes extracted from each package with the publish output; it does not launch or install them.

The Debian package declares dependencies for Ubuntu 24.04. The RPM declares .NET and Avalonia native libraries for Fedora qualification. These follow [Microsoft's Ubuntu dependency list](https://learn.microsoft.com/en-us/dotnet/core/install/linux-ubuntu-decision#dependencies), [Fedora dependency list](https://learn.microsoft.com/en-us/dotnet/core/install/linux-fedora#dependencies), and [Avalonia's desktop Linux guidance](https://docs.avaloniaui.net/docs/platform-specific-guides/linux). Fedora 44 installation and GUI runtime remain a separate qualification gate.

During the later installation phase, install a matching architecture package with `sudo apt install ./glide_1.0.0_amd64.deb` or `sudo dnf install ./glide-1.0.0-1.x86_64.rpm`. Remove it with `sudo apt remove glide` or `sudo dnf remove glide`. For portable use, extract the archive and run `./usr/bin/glide-desktop`; native libraries must already be installed. Uninstalling removes application files, while user configuration and received files remain in the user's directories.

The package includes the optional daemon service but does not enable it. It reads the peer-specific settings in `glide-daemon.env.example`; configure a real trusted peer and destination before enabling `glide.service`. GUI and daemon share listener ownership and cannot receive on conflicting listeners.
