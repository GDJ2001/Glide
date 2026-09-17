# ADR-0021 — Linux Platform Architecture, Daemon & CLI Integration Strategy

- **Status:** Accepted
- **Date:** 2026-09-17
- **Authority:** [Cross-Platform Path](../future/CROSS_PLATFORM_PATH.md) Step 5 | [Linux Platform Specification](../future/LINUX_PLATFORM_SPECIFICATION.md) | [ADR-0018](ADR-0018-lan-qr-authentication-and-routing.md)
- **Supersedes / Extends:** Fulfills Step 5 of [CROSS_PLATFORM_PATH.md](../future/CROSS_PLATFORM_PATH.md); extends [ADR-0008 (Protocol)](ADR-0008-protocol.md) and [ADR-0018 (LAN/QR Profile 1)](ADR-0018-lan-qr-authentication-and-routing.md) to Linux workstations, servers, and headless appliances.

---

## Context

Glide V1 established private, high-speed local sharing for Android (Nearby Connections P2P and LAN TLS 1.3), while subsequent milestones delivered native Windows desktop interoperability (`Glide.Windows`, ADR-0017), zero-install browser sharing (ADR-0019), and native Apple platform architecture (ADR-0020).

Linux represents a critical ecosystem spanning multiple distinct deployment surfaces:
1. **Desktop Workstations:** Users running GNOME, KDE Plasma, XFCE, and tiling window managers (i3, Sway) requiring seamless integration with file managers (Nautilus, Dolphin) and desktop notifications.
2. **Headless Servers & NAS:** Home servers, Raspberry Pi appliances, and Network-Attached Storage (NAS) units requiring lightweight daemon execution without graphical displays or X11/Wayland dependencies.
3. **SteamOS & Handhelds:** Gaming devices (e.g. Steam Deck) where rapid local transfer of game media, backups, and ROMs across local Wi-Fi without cloud intermediaries is highly desirable.
4. **Developer Workstations & SSH:** Command-line terminal users transferring build artifacts, disk images, and logs directly across machines over local networks without configuring Samba, NFS, or SSH keys.

Linux environments adhere strictly to FreeDesktop.org standards (XDG Base Directory, Desktop Entry Specification) and modern service management (`systemd`). A cohesive architectural strategy must enable high-speed sharing across all Linux environments while guaranteeing 100% protocol fidelity and zero cloud reliance.

---

## Options Considered

### 1. Complete Rewrite in C or Rust
- *Pros:* Smallest possible binary footprint; native ELF binaries with minimal runtime dependencies.
- *Cons:* Requires re-implementing and maintaining an entire third protocol engine, TLS socket stack, invitation codecs, and golden fixtures. Substantial ongoing maintenance burden with high risk of protocol divergence or subtle wire discrepancies. **Rejected.**

### 2. Python or Electron Wrapper
- *Pros:* Fast GUI development; easily packaged.
- *Cons:* Electron introduces excessive memory bloat (>150 MB idle RAM) and bundle sizes (>100 MB). Python introduces runtime environment fragmentation, package dependency conflicts, and slower file hashing throughput. **Rejected.**

### 3. Leverage Existing Cross-Platform .NET 10 Engine (`Glide.Core`, `Glide.Transport`, `Glide.App`) (Adopted)
- *Pros:*
  - **100% Code & Conformance Reuse:** `Glide.Core` and `Glide.Transport` already target pure `net10.0` without any Windows-specific API bindings. They execute natively on Linux x64 and arm64.
  - **Zero Protocol Divergence:** Validated by the exact same 152+ test suite and golden fixtures that govern Windows.
  - **Premier Performance:** Hardware-accelerated SHA-256 and AES/ChaCha via OpenSSL/libcrypto bindings; throughput exceeds 100+ MB/s.
  - **Flexible Deployment:** Can run via standard `dotnet` runtime or compile to a single-file, self-contained native ELF binary (`dotnet publish -r linux-x64 --self-contained -p:PublishSingleFile=true`) with zero external .NET dependencies.
  - **Unified CLI & Daemon:** `Glide.App` provides a premier console interface for desktop, server, and headless operation.
- *Cons:* GUI frontend requires separate integration (desktop entry / browser gateway / future Avalonia/GTK UI).

---

## Decision

Adopt **Option 3: Shared Cross-Platform .NET 10 Engine (`Glide.Core`, `Glide.Transport`, `Glide.App`)**, governed by the following architectural, filesystem, and integration contracts:

### 1. CLI & Daemon Architecture (`Glide.App`)
The native Linux executable provides command-driven operation:
- `glide receive [--port <port>]`: Starts the LAN Profile 1 listener on port 42424 (or dynamic), prints local network endpoints, displays the pairing invitation URI, and renders an ASCII QR code in the terminal.
- `glide send <host> <port> <files...>`: Direct TLS 1.3 connection to a known peer.
- `glide send --pair <pairing-uri> <files...>`: Instant QR-bootstrapped connection with SPKI hash pinning and SAS bypass.
- `glide web [--port <port>] <files...>`: Launches the zero-install local web transfer gateway (ADR-0019) to share files with any browser.
- `glide daemon [--port <port>]`: Runs as a persistent background listener suited for `systemd` user services.

### 2. XDG Base Directory Compliance
All filesystem operations strictly adhere to the FreeDesktop.org XDG Base Directory Specification:
- **Quarantine Staging Store:**
  $$\text{StagingPath} = \begin{cases} \$XDG\_DATA\_HOME/\text{glide/staging} & \text{if } \$XDG\_DATA\_HOME \text{ is set} \\ \sim/\text{.local/share/glide/staging} & \text{otherwise} \end{cases}$$
- **Configuration Directory:**
  $$\text{ConfigPath} = \begin{cases} \$XDG\_CONFIG\_HOME/\text{glide} & \text{if } \$XDG\_CONFIG\_HOME \text{ is set} \\ \sim/\text{.config/glide} & \text{otherwise} \end{cases}$$
- **Default Destination (Downloads):**
  Resolved dynamically from `~/.config/user-dirs.dirs` (`XDG_DOWNLOAD_DIR`) falling back to `~/Downloads`.

### 3. POSIX & Linux Filesystem Sanitization
- Incoming filenames are validated through `WindowsPathSanitizer`, which enforces a strict superset of POSIX safety rules.
- Strips directory traversal (`..`), forward slashes (`/`), null bytes (`\0`), leading/trailing periods, and limits filename length to 255 bytes.
- Guarantees that files saved to Linux systems can be copied to external USB drives formatted as FAT32, exFAT, or NTFS without filename collision or corruption.

### 4. Headless Terminal QR Code Rendering
- For headless Linux servers and SSH sessions without a web browser or display server, `Glide.App` renders the `glide://pair/v1#...` invitation QR code directly into the terminal using Unicode block elements (`█`, `▀`, `▄`, ` `).
- Allows instant scanning by Android, iOS, or Windows mobile cameras directly from an SSH terminal window.

### 5. mDNS Discovery & Avahi Coexistence
- `MdnsDiscoveryService` operates via standard POSIX multicast sockets on `224.0.0.251:5353`.
- **Avahi Coexistence:** If `avahi-daemon` or `systemd-resolved` holds port 5353 exclusively, `MdnsDiscoveryService` automatically falls back to an ephemeral port to query and browse peers, and broadcasts announcements via standard multicast routing without crashing.

### 6. Desktop & Service Integration
- **FreeDesktop Desktop Entry (`glide.desktop`):**
  Enables launching from GNOME Shell, KDE KRunner, and application menus with file manager Drag-and-Drop and "Send with Glide" context menu actions.
- **systemd User Service (`glide.service`):**
  Provides managed background execution (`systemctl --user enable --now glide`) with automatic restart on failure and journal logging.

---

## Consequences

- **Ecosystem Completion:** Delivers full native interoperability to Linux desktops, laptops, servers, Raspberry Pi boards, and SteamOS devices.
- **Zero Code Duplication:** Reuses `Glide.Core` and `Glide.Transport` directly, guaranteeing 100% protocol and golden fixture conformance.
- **Zero Cloud Invariant:** Retains Glide's core invariant of 100% offline, local-network-only communication with TLS 1.3 security.
- **Headless First-Class Citizen:** First-class support for terminal-only, SSH, and server workflows via UTF-8 QR rendering and systemd services.
