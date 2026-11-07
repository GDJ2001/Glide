# Security Policy — Glide

Security and privacy are core to the design of Glide. As an offline, peer-to-peer file transfer system handling local device-to-device streaming, Glide maintains strict cryptographic and architectural invariants to protect user data and device integrity.

---

## Supported Versions

Only the latest active release branch receives security updates. We strongly encourage all users to run the latest released version.

| Version | Supported | Notes |
| :--- | :---: | :--- |
| **1.0.x** | :white_check_mark: | Current release line (Android, Windows, Linux) |
| **< 1.0.0** | :x: | Development / pre-release builds unsupported |

---

## Reporting a Vulnerability

We appreciate the responsible disclosure of security vulnerabilities. If you discover a security issue or potential vulnerability in Glide, please report it privately rather than opening a public issue.

### Preferred Reporting Channels

1. **GitHub Private Vulnerability Reporting**:
   - Navigate to the repository's **Security** tab $\rightarrow$ **Advisories** $\rightarrow$ **Report a vulnerability**.
2. **Direct Security Email**:
   - Send an encrypted or confidential email to **security@glide.dev**.

### What to Include

To help us triage and resolve the issue quickly, please include:
- A clear description of the vulnerability and its potential impact.
- The affected component or platform: Android (`android/`), Windows (`desktop/windows/`), Linux (`desktop/linux/`), Core Protocol (`Glide.Core` / `:transfer-core`), or Apple (`apple/`).
- Step-by-step instructions or proof-of-concept code to reproduce the vulnerability.
- Applicable Threat ID from [doc/security/GLIDE_THREAT_MODEL.md](doc/security/GLIDE_THREAT_MODEL.md) if related to identified threat vectors (e.g., T01–T18).
- Any proposed remediation or mitigation.

> **Security Warning:** Never publish or email private cryptographic keys, active pairing tokens, or real user files.

---

## Response Timeline & SLAs

- **Initial Acknowledgment**: Within **48 hours** of receiving the report.
- **Triage & Assessment**: Within **5 business days**, detailing validity, severity rating, and expected remediation timeline.
- **Remediation & Patch**: Security fixes are developed privately and deployed as hotfix releases.
- **Coordinated Disclosure**: Public advisories (and CVE assignment if appropriate) will be published simultaneously with or following the fix release, with credit given to the reporter.

---

## Core Security Invariants

All platform implementations in Glide adhere to the following non-negotiable security controls:

1. **Zero-Cloud & Local-Only**:
   - Glide operates completely offline. No telemetry, user accounts, remote telemetry relays, or external cloud infrastructure exist.
2. **Mutual Cryptographic Authentication**:
   - Connection establishment requires ephemeral SPAKE2 key exchange or pinned TLS 1.3 self-signed certificates with SHA-256 SPKI fingerprint verification.
   - SAS (Short Authentication String) 6-digit visual comparison must be explicitly confirmed by user action on both devices.
3. **Strict Path Sanitization**:
   - Remote filenames are never used directly as local file paths.
   - All inbound names are sanitized via `WindowsPathSanitizer` / `FilenameSanitizer` to reject path traversal (`..`), path separators (`/`, `\`), drive roots (`C:`), UNC paths (`\\server\share`), and DOS device names (`CON`, `PRN`, `AUX`, `NUL`, `COM1-9`, `LPT1-9`).
4. **Quarantine Staging & Verified Atomic Commit**:
   - Inbound byte streams are written strictly to private quarantine staging (`.part` files inside `QuarantineStagingStore`).
   - The streaming SHA-256 hash is computed in real time and verified against the peer's signed `ITEM_END` digest.
   - Files are committed to the user's destination folder via an atomic rename only upon verified digest match.
5. **Bounded Protocol Framing**:
   - Framing header: 32-bit big-endian unsigned length prefix.
   - Control frames capped at $\le 16\text{ KiB}$.
   - Bulk stream chunks bounded to $\le 64\text{ KiB}$.
   - Batch limits: maximum 1,000 items, maximum 64 GiB per file, maximum 256 GiB per batch.

---

## Out of Scope / Non-Vulnerabilities

The following scenarios are outside Glide's security model:
- **Compromised Operating System / Root / Jailbreak**: If the host OS or device kernel is compromised, local process memory and sandboxes are not guaranteed.
- **Physical Device Theft without Screen Lock**: If an unlocked device is physically possessed by an attacker, physical access controls supersede application-layer protections.
- **Radio Frequency Denial of Service**: RF jamming of Wi-Fi or Bluetooth radio spectrum is a physical-layer limitation.
- **Malware Scanning**: Glide verifies cryptographic integrity (that bytes received match bytes sent), not the semantic safety or executable malware status of transferred files.

---

## Security Documentation & References

- [Security Checklist](doc/security/SECURITY_CHECKLIST.md)
- [Glide Threat Model](doc/security/GLIDE_THREAT_MODEL.md)
- [Native App Threat Model](doc/security/NATIVE_APP_THREAT_MODEL.md)
- [Protocol Specification V1](doc/protocol/GLIDE_PROTOCOL_V1.md)
