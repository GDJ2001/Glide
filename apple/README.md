# Glide Apple Workspace

This directory contains all Apple platform development components for Glide across macOS and iOS.

## Structure

```
apple/
├── GlideCoreKit/      # Pure Swift Package containing domain models, Bonjour discovery, TLS framing, and tests
│   ├── Package.swift
│   ├── Sources/GlideCoreKit/
│   │   ├── Models/
│   │   ├── Discovery/
│   │   ├── Framing/
│   │   ├── Security/
│   │   ├── Lifecycle/
│   │   └── UI/
│   ├── Tests/GlideCoreKitTests/
│   └── Resources/
├── GlideIOS/          # iOS Application container
├── GlideMac/          # macOS Companion application container
└── scripts/           # Local build and test runner scripts
    ├── build-and-test.sh
    └── test.ps1
```

## Running Tests

From macOS with Xcode or the Swift toolchain:

```bash
cd apple/GlideCoreKit
swift test
```

Or run the test script:
```bash
./scripts/build-and-test.sh
```
