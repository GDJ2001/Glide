# Linux Platform (Workstation, Server & Headless) Interoperability & Integration Specification

- **Document Version:** 1.0.0
- **Status:** Approved Technical Specification
- **Milestone:** M20 — Linux Platform Architecture & Conformance Specification
- **Task Authority:** [CROSS_PLATFORM_PATH.md](CROSS_PLATFORM_PATH.md) Step 5
- **Architectural Decision:** [ADR-0021](../adr/ADR-0021-linux-platform-architecture.md)
- **Related Documents:** [GLIDE_PROTOCOL_V1.md](../protocol/GLIDE_PROTOCOL_V1.md), [ANDROID_WINDOWS_LAN_SPECIFICATION.md](ANDROID_WINDOWS_LAN_SPECIFICATION.md), [ANDROID_BROWSER_WEB_SPECIFICATION.md](ANDROID_BROWSER_WEB_SPECIFICATION.md), [APPLE_PLATFORMS_SPECIFICATION.md](APPLE_PLATFORMS_SPECIFICATION.md), [GLIDE_THREAT_MODEL.md](../security/GLIDE_THREAT_MODEL.md).

---

## 1. Executive Summary & Architectural Invariants

### 1.1 Purpose
This specification defines the complete architectural design, network wire protocols, FreeDesktop.org integration standards, systemd service management, XDG directory compliance, and quarantine storage rules for **Glide on Linux** (Desktop Workstations, Headless Home Servers, Raspberry Pi, SteamOS, and Containerized Environments).

### 1.2 Core Invariants
1. **Zero Cloud / 100% Offline by Default:**
   All discovery, pairing, and payload transfers execute strictly across the local Wi-Fi or Ethernet subnet. No cloud signaling servers, user account databases, STUN/TURN relays, or external network connections are used or required.
2. **Canonical Protocol Parity:**
   The Linux implementation reuses the **Glide Protocol V1** envelope schema, `LanRecord` wire framing, batch negotiation, and item outcome states without creating proprietary dialect forks.
3. **Shared Cross-Platform .NET 10 Engine:**
   Reuses `Glide.Core` and `Glide.Transport` (`net10.0`) directly, ensuring 100% conformance with golden test fixtures and zero code duplication across Windows and Linux.
4. **FreeDesktop.org & XDG Compliance:**
   Storage locations strictly adhere to the XDG Base Directory specification (`$XDG_DATA_HOME`, `$XDG_CONFIG_HOME`, `$XDG_DOWNLOAD_DIR`).
5. **Headless & SSH First-Class Support:**
   Provides high-contrast ANSI/UTF-8 block character QR rendering directly in terminal emulators, enabling seamless pairing over headless SSH connections.

---

## 2. System Architecture & CLI/Daemon Mapping

```
+-----------------------------------------------------------------------------------+
|                              Linux Host System                                    |
|   +---------------------------------------+   +-------------------------------+   |
|   |         Desktop Environments          |   |      Headless / Server Mode   |   |
|   |       (GNOME, KDE Plasma, XFCE)       |   |      (SSH, NAS, Raspberry Pi) |   |
|   +---------------------------------------+   +-------------------------------+   |
|   |  FreeDesktop .desktop & Notifications  |   |  systemd User Service Unit    |   |
|   +---------------------------------------+   +-------------------------------+   |
|   |                     Glide.App (Unified CLI / Daemon Executable)            |   |
|   |  - Commands: receive, send, pair, list, daemon                             |   |
|   |  - ANSI/UTF-8 Block QR Code Generator for Terminal Pairing                 |   |
|   |  - XDG Base Directory Resolver (Data, Config, Cache, Downloads)           |   |
|   +---------------------------------------------------------------------------+   |
|   |                     Glide.Core & Glide.Transport (.NET 10)                 |   |
|   |  - Protocol V1 Codec & Golden Fixtures (20+ tests)                        |   |
|   |  - SAS Calculator (RFC 5869 HKDF-SHA256) & LanInvitation Codec            |   |
|   |  - LanRecord Framing & Zero-Dependency MdnsDiscoveryService               |   |
|   |  - TlsSocketListener & TlsSocketClient (TLS 1.3 via OpenSSL libcrypto)    |   |
|   |  - QuarantineStagingStore & WindowsPathSanitizer (POSIX-safe superset)    |   |
|   +---------------------------------------------------------------------------+   |
+-----------------------------------------------------------------------------------+
                                         ^
                                         | Local Wi-Fi / Ethernet Subnet
                                         | (TLS 1.3 / mDNS)
                                         v
+-----------------------------------------------------------------------------------+
|                          Interoperating Peer Endpoints                            |
|     +-------------------------+  +----------------------+  +------------------+   |
|     |  Android (dev.glide.app)|  | Windows (Glide.slnx) |  | Apple (iOS/macOS)|   |
|     +-------------------------+  +----------------------+  +------------------+   |
+-----------------------------------------------------------------------------------+
```

---

## 3. FreeDesktop.org & XDG Base Directory Compliance

All filesystem paths on Linux resolve according to standard XDG environment variables with robust fallbacks to `$HOME`:

### 3.1 Directory Resolution Rules
- **Quarantine Staging Directory:**
  ```csharp
  string xdgDataHome = Environment.GetEnvironmentVariable("XDG_DATA_HOME");
  string baseDataDir = !string.IsNullOrWhiteSpace(xdgDataHome)
      ? xdgDataHome
      : Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), ".local", "share");
  string stagingRoot = Path.Combine(baseDataDir, "glide", "staging");
  ```
- **Configuration Directory:**
  ```csharp
  string xdgConfigHome = Environment.GetEnvironmentVariable("XDG_CONFIG_HOME");
  string baseConfigDir = !string.IsNullOrWhiteSpace(xdgConfigHome)
      ? xdgConfigHome
      : Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), ".config");
  string configRoot = Path.Combine(baseConfigDir, "glide");
  ```
- **Destination (Downloads Directory):**
  1. Inspect `~/.config/user-dirs.dirs` for `XDG_DOWNLOAD_DIR="<path>"`.
  2. If unset or invalid, fall back to `$HOME/Downloads`.
  3. If `$HOME/Downloads` does not exist, create it dynamically.

---

## 4. Terminal User Experience & Headless QR Code Rendering

For headless Linux installations, servers, and remote SSH sessions without graphical displays:

### 4.1 UTF-8 Block Character QR Rendering
When `glide receive` starts, the console renders a compact, high-contrast QR code directly to `stdout` using UTF-8 half-block elements:
- Upper half block: `▀` (`\u2580`)
- Lower half block: `▄` (`\u2584`)
- Full block: `█` (`\u2588`)
- Empty space: ` ` (`\u0020`)

By pairing two vertical pixels per character cell, the QR code displays with square aspect ratio in standard $80\times24$ terminal windows.

### 4.2 Interactive CLI Commands
```bash
# Listen for incoming transfers from Android, Windows, or Apple peers:
glide receive [--port <port>]

# Send files using an authenticated out-of-band pairing invitation:
glide send --pair "glide://pair/v1#..." <file-paths...>

# Run as a peer-specific background receiver.
# The fingerprint must already be enrolled by verified persistent-identity proof.
glide daemon --accept-paired-offers \
  --trusted-peer <64-lowercase-hex-fingerprint> \
  --max-offer-bytes <bytes> \
  --destination <absolute-path> \
  [--port <port>]
```

---

## 5. mDNS Service Discovery & Avahi Coexistence

### 5.1 Discovery Specification
- Service Type: `_glide._tcp.local.`
- Multicast Group: `224.0.0.251:5353`
- TXT Records:
  - `v`: Protocol version (`1`)
  - `name`: User-facing machine or custom hostname
  - `spki`: Lowercase hex SHA-256 digest of the server's SubjectPublicKeyInfo
  - `presence`: 16-byte random presence token
  - `cap`: Capability bitmask (`0x01` = LAN Profile 1)

### 5.2 Avahi & systemd-resolved Coexistence Invariant
On standard Linux distributions (Ubuntu, Fedora, Arch, Debian), port `5353` is frequently bound by `avahi-daemon` or `systemd-resolved`.
`MdnsDiscoveryService` implements resilient coexistence:
1. Attempts to bind `224.0.0.251:5353` with `SO_REUSEADDR` / `SO_REUSEPORT`.
2. If port 5353 is locked exclusively, automatically falls back to an ephemeral UDP port (`0`) for sending mDNS queries and receives unicast responses.
3. Broadcasts goodbye packets (`TTL=0`) on orderly SIGINT / SIGTERM shutdown.

---

## 6. POSIX & Linux Filesystem Sanitization

Inbound filenames received over the wire are validated through `WindowsPathSanitizer`, which functions as a strict superset of POSIX safety rules:
- **Directory Traversal:** Strips `..` and relative navigation sequences.
- **Root & Absolute Prefixes:** Rejects leading `/` and `//` UNC-style roots.
- **Null Byte Injection:** Strips or rejects `\0` bytes.
- **Length Ceilings:** Enforces 255-byte UTF-8 filename cap and 1024-byte path cap.
- **Filesystem Cross-Compatibility:** Because `WindowsPathSanitizer` also cleans characters illegal on Windows (`< > : " | ? *`), files saved on Linux can be safely copied to FAT32/exFAT/NTFS external drives without naming errors.

---

## 7. Desktop Environment Integration (FreeDesktop.org)

### 7.1 Desktop Entry (`glide.desktop`)
Installed to `~/.local/share/applications/glide.desktop` or `/usr/share/applications/glide.desktop`:
```ini
[Desktop Entry]
Name=Glide
Comment=High-Speed Zero-Cloud Local File Sharing
Exec=glide-desktop %F
Icon=dev.glide.app
Terminal=false
Type=Application
Categories=Network;FileTransfer;Utility;
MimeType=all/allfiles;
Actions=Receive;

[Desktop Action Receive]
Name=Receive Files
Exec=glide receive
```

### 7.2 Desktop Notifications
On Linux desktops supporting the FreeDesktop Notification Specification (via `libnotify` or D-Bus `org.freedesktop.Notifications`), Glide emits native desktop notifications on transfer start, completion, and error.

---

## 8. Service Management with systemd

For home servers, NAS units, and background operation on workstations, Glide provides a standard systemd user service unit. **Headless offer acceptance is never global.** It requires a previously enrolled persistent peer identity plus a peer-specific destination and byte ceiling. The live session must also carry that same authenticated fingerprint; until HARD-021 wires `identity-v1` proof into the session engine, offers remain fail-closed even when a policy exists.

The unit intentionally requires `~/.config/glide/daemon.env`. If that file is absent, empty, or references an unenrolled fingerprint, the service does not become a permissive receiver.

Example `daemon.env`:

```bash
GLIDE_TRUSTED_PEER=<64-lowercase-hex-enrolled-fingerprint>
GLIDE_MAX_OFFER_BYTES=1073741824
GLIDE_DESTINATION=/home/<user>/Downloads
```

Protect the file with `chmod 600 ~/.config/glide/daemon.env`.


### 8.1 User Unit File (`~/.config/systemd/user/glide.service`)
```ini
[Unit]
Description=Glide Local File Sharing Daemon
After=network.target network-online.target
Wants=network-online.target

[Service]
Type=simple
EnvironmentFile=%h/.config/glide/daemon.env
Environment=XDG_DATA_HOME=%h/.local/share
Environment=XDG_CONFIG_HOME=%h/.config
ExecStart=/usr/local/bin/glide daemon --accept-paired-offers --trusted-peer ${GLIDE_TRUSTED_PEER} --max-offer-bytes ${GLIDE_MAX_OFFER_BYTES} --destination ${GLIDE_DESTINATION}
Restart=on-failure
RestartSec=5s
KillMode=process

[Install]
WantedBy=default.target
```

Commands:
```bash
systemctl --user daemon-reload
systemctl --user enable --now glide.service
journalctl --user -u glide.service -f
```

---

## 9. Packaging & Deployment Targets

1. **Self-Contained Single-File Binary:**
   `dotnet publish windows/Glide.App/Glide.App.csproj -c Release -r linux-x64 --self-contained -p:PublishSingleFile=true -o dist/linux-x64/`
   Yields a single standalone ELF executable (`glide`, ~35 MB) with zero runtime dependencies.
2. **Linux ARM64 Binary:**
   `dotnet publish windows/Glide.App/Glide.App.csproj -c Release -r linux-arm64 --self-contained -p:PublishSingleFile=true -o dist/linux-arm64/`
   Direct deployment for Raspberry Pi (3/4/5), Asahi Linux (Apple Silicon), and ARM64 servers.
3. **Container (OCI / Docker):**
   Lightweight Alpine or Chiseled Ubuntu base image running `glide daemon`.

---

## 10. Conformance Test Matrix & Golden Fixture Validation

All Linux distributions execute the same automated test suite (`windows/Glide.Tests`):
- 20+ Golden Envelope Serialization and Deserialization fixtures.
- LAN Profile 1 framing (`LanRecordCodec`) with 5-byte header validation.
- Short Authentication String (SAS) calculation matching Android and Windows.
- QR Invitation URI encoding and decoding (`glide://pair/v1#...`).
- Quarantine staging, incremental SHA-256 verification, and atomic commitment.
