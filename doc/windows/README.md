# Glide Windows delivery authority

This directory is the evidence-led source of truth for the Windows 11 x64 release. It applies to
the native WPF client and Windows↔Windows / Windows↔Android local transfers. Browser sharing,
Windows 10, ARM64, remote transfer, and new Linux work are outside this release.

- [Completion ledger](WINDOWS_COMPLETION_LEDGER.md) — task status, implementation evidence, and the next safe work.
- [Release gates](WINDOWS_RELEASE_GATES.md) — automated, physical-device, security, accessibility, installer, and update qualification.

Status labels have a strict meaning: `IMPLEMENTED` means source exists and has named evidence;
`VERIFIED` means its required automated/manual check ran; `BLOCKED` means an external prerequisite
is missing. Nothing is release-complete until every applicable release gate is verified against the
same signed candidate binary.

## Fixed decisions

- Use C# on .NET 10 LTS and WPF. The distribution is self-contained for Windows 11 x64.
- GUI and CLI go through the desktop transfer runtime. UI must not create sockets, listeners, trust
  stores, or transfer engines directly.
- One active transfer batch is allowed. Incoming competing work receives a busy result; an outgoing
  queue may persist only when durable recovery is complete.
- LAN discovery and authenticated TLS are a transport; the Glide protocol remains portable.
- Receive consent stays explicit. Trust never implies automatic acceptance of files.
- Receive staging is private, output names never overwrite silently, and verification precedes final
  publication.
- NSIS 3.12+ packages per-user installations. Uninstall removes only installer-owned resources and
  preserves received files by default.
- Updates use a pinned Ed25519 public key and HTTPS appcast. No unsigned, downgraded, or active-transfer
  update may install.

## Development commands

```powershell
dotnet build desktop/Glide.slnx -c Release --nologo
dotnet test desktop/Glide.slnx -c Release --no-build --nologo
powershell -ExecutionPolicy Bypass -File desktop/windows/package-windows.ps1 -Configuration Release -Version 0.1.0-dev -SkipInstaller
```

The portable build does **not** prove installer, update, signing, physical-device, or release
readiness. To build the installer, install NSIS 3.12+ and omit `-SkipInstaller`; then sign in the
protected release pipeline.

## Required release configuration

The app activates daily update checks only when the protected release build supplies `-UpdateFeedUrl`
and `-UpdatePublicKey` to `package-windows.ps1`. They are compiled into that specific binary:

- `GLIDE_UPDATE_FEED_URL`: an HTTPS appcast URL in the public release repository.
- `GLIDE_UPDATE_PUBLIC_KEY`: the 32-byte Base64 Ed25519 public key corresponding to the protected
  appcast signing private key.

These are public distribution inputs, not credentials. The private signing key, Authenticode
certificate, GitHub publishing token, and release-repository administration stay outside this
repository. The intended public distribution repository is `GDJ2001/Glide-Releases`, pending owner
creation and access.
