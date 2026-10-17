# Glide 1.0.0 — Linux Discovery, Transfer Reliability, Storage Hardening & Installation Guide

## 1. Overview & Compatibility Guidance

This release hardens Linux and Windows desktop discovery, trusted endpoint candidate resolution, session deadlines, cancellation, large-file finalization, portable Linux storage publication, and bounded rotating diagnostics.

- **Launch Behavior**: Both Windows and Linux desktop applications automatically start in **Ready to receive** when Glide opens (`autoStartReceiving: true`). Every incoming file offer still requires explicit user approval before any payload bytes are accepted. Pair-only availability remains separately controlled by **Show my QR and code** (**Ready to pair**).
- **Wire Protocol Compatibility**: The wire protocol (`GLD1` Profile 1 and `GLD2` Profile 2) is unchanged. Updated 1.0.0 builds interoperate with earlier 1.0.0 builds for pairing and standard transfers.
- **Large-File Finalization Compatibility Note**: Earlier senders enforced a fixed 30-second timeout while waiting for the receiver's `ITEM_END` confirmation. Updating only the receiver does **not** alter an older sender's 30-second finalization wait on multi-gigabyte files. **Install the updated Windows and Linux 1.0.0 packages together** so both peers use the file-size-scaled finalization deadline (`min 120s`, `60s + 3 × fileSize / (10 MiB/s)`, capped at `6 hours`) and surface the **Waiting for receiver confirmation** phase.

---

## 2. Preserved Pre-Change Rollback Packages

Before applying the reliability and storage hardening changes, the prior packages from commit `d8673449f3689b5727aadf89dcbe924fffa89c99` were preserved for immediate rollback:

- **Windows Rollback Installer**:
  - `build/distributions/windows/Glide-Setup-1.0.0-win-x64-pre-d867344.exe`
- **Linux Rollback Packages**:
  - `build/distributions/runs/d8673449f3689b5727aadf89dcbe924fffa89c99-20261016T115121Z/linux/glide_1.0.0_amd64.deb`
  - `build/distributions/runs/d8673449f3689b5727aadf89dcbe924fffa89c99-20261016T115121Z/linux/glide-1.0.0-1.x86_64.rpm`
  - `build/distributions/runs/d8673449f3689b5727aadf89dcbe924fffa89c99-20261016T115121Z/linux/glide-1.0.0-linux-x64.tar.gz`
  - `build/distributions/runs/d8673449f3689b5727aadf89dcbe924fffa89c99-20261016T115121Z/linux/Glide-Linux-Installer-1.0.0-x64.run`

---

## 3. Primary-PC Installation & Upgrade Steps

### A. Windows 10 / 11 (x64)

1. Verify the package SHA-256 against `build/distributions/windows/SHA256SUMS.txt`:
   ```powershell
   Get-FileHash .\build\distributions\windows\Glide-Setup-1.0.0-win-x64.exe -Algorithm SHA256
   ```
2. Close any running `Glide.Windows.UI` instance.
3. Run the installer:
   ```powershell
   .\build\distributions\windows\Glide-Setup-1.0.0-win-x64.exe
   ```
4. Launch Glide. Existing trusted devices (`%LOCALAPPDATA%\Glide\trust-v1.json`), persistent identity keys, receive folder settings, and transfer history are preserved in place.

### B. Ubuntu / Debian Linux (`.deb`, x64)

1. Verify the package SHA-256 against `SHA256SUMS.txt`:
   ```bash
   sha256sum -c SHA256SUMS.txt --ignore-missing
   ```
2. Install or upgrade the `.deb` package:
   ```bash
   sudo apt install --reinstall ./glide_1.0.0_amd64.deb
   ```
3. Launch Glide from the desktop application launcher or run `/opt/glide/Glide.Linux.UI` (or `glide-gui`).
   - Glide automatically enters **Ready to receive** on launch.
   - Existing trusted devices (`~/.config/glide/trust-v1.json`), persistent identity (`~/.config/glide/identity-v1.pk8`), settings (`~/.local/share/glide/desktop-state.json`), and history are preserved.

### C. Fedora / RHEL / openSUSE Linux (`.rpm`, x64)

```bash
sudo dnf install ./glide-1.0.0-1.x86_64.rpm
```

### D. Universal Linux Self-Extracting Installer (`.run`) or Portable Archive (`.tar.gz`)

```bash
chmod +x ./Glide-Linux-Installer-1.0.0-x64.run
sudo ./Glide-Linux-Installer-1.0.0-x64.run
```

---

## 4. Rollback Procedure

### Rolling Back on Windows

1. Close Glide.
2. Run the preserved rollback installer:
   ```powershell
   .\build\distributions\windows\Glide-Setup-1.0.0-win-x64-pre-d867344.exe
   ```

### Rolling Back on Linux (`.deb`)

1. Close Glide.
2. Install the preserved rollback package:
   ```bash
   sudo apt install --allow-downgrades --reinstall ./glide_1.0.0_amd64.deb
   ```
   *(using the `.deb` from `build/distributions/runs/d8673449f3689b5727aadf89dcbe924fffa89c99-20261016T115121Z/linux/`)*

### Clean Removal

- **Ubuntu/Debian**: `sudo apt remove glide` (preserves user data in `~/.config/glide` and `~/.local/share/glide`) or `sudo apt purge glide`.
- **Windows**: Uninstall **Glide** from **Settings → Apps → Installed apps** or run `unins000.exe` in `%LOCALAPPDATA%\Programs\Glide`.

---

## 5. Diagnostic Collection & Sanitized Log Export

Glide writes bounded rotating structured JSONL diagnostics (`<= 5 MiB` per file, up to `4` files) without blocking the transfer pipeline:

- **Linux default log path**: `~/.local/share/Glide/logs/glide-diagnostics.jsonl`
- **Windows default log path**: `%LOCALAPPDATA%\Glide\logs\glide-diagnostics.jsonl`
- **Custom override**: Set `GLIDE_DIAGNOSTIC_LOG_PATH=/path/to/glide-diagnostics.jsonl`

### Exporting a Sanitized Diagnostic Bundle

- **From the Linux UI**: Open **Settings → Diagnostics** and click **Save diagnostic log** (writes `~/Downloads/glide-diagnostics-<timestamp>.jsonl`) or **Copy diagnostic summary**.
- **Verify Secret Redaction**: Exported bundles automatically redact `glide://pair/...` URIs, one-time tokens, PINs, SPKI values, and private key material while preserving build commit, OS details, discovery health, and per-phase transfer timings (`preparationMs`, `consentWaitMs`, `payloadTransferMs`, `verificationMs`, `commitMs`).
