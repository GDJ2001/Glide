# Glide Desktop Workspace

This directory contains all desktop platform development components for Glide across Windows and Linux, implemented using C# and .NET 10.

## Structure

```
desktop/
├── Glide.slnx       # Unified .NET 10 Solution
├── src/             # Cross-platform .NET projects (net10.0)
│   ├── Glide.Core/      # Protocol envelopes, models, crypto, storage, and framing
│   ├── Glide.Desktop/   # Desktop transfer engine, state machine, and daemon policy
│   ├── Glide.Transport/ # Sockets, TLS, mDNS, WebRTC, and browser HTTP/WebSocket gateway
│   └── Glide.App/       # CLI and headless background daemon
├── windows/         # Windows-specific components
│   └── Glide.Windows.UI/ # WPF Desktop Application (net10.0-windows)
├── linux/           # Linux-specific components
│   ├── glide.desktop     # FreeDesktop application desktop entry
│   ├── glide.service     # systemd user service unit
│   ├── build-linux-packages.ps1 # Linux tarball and package generator
│   ├── glide-daemon.env.example
│   └── glide-daemon-policy.example.json
└── tests/           # Desktop test suites
    └── Glide.Tests/     # xUnit test suite (292 tests)
```

## Building and Testing

Build the unified desktop solution:
```bash
dotnet build desktop/Glide.slnx
```

Run all desktop tests:
```bash
dotnet test desktop/Glide.slnx
```
