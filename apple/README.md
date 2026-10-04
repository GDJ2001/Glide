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

## Installable packages

On a clean Mac with Xcode 26.6, `bash apple/scripts/package-macos.sh` creates a
development `.pkg` that installs `Glide.app` and `Glide Uninstaller.app` into
`/Applications`. The uninstaller asks for administrator authorization, checks
both bundle identifiers, removes those two installed apps and the package
receipt, and preserves received files, transfer history, and settings. The
development package is unsigned; it is for local installation testing, not
normal internet distribution.

For normal direct distribution, configure the Mac's keychain with a Developer
ID Application identity, a Developer ID Installer identity, and a `notarytool`
keychain profile. Then set `GLIDE_MAC_APP_SIGNING_IDENTITY`,
`GLIDE_MAC_INSTALLER_SIGNING_IDENTITY`, and `GLIDE_MAC_NOTARY_PROFILE` before
running the same packaging script. It signs the app and uninstaller, signs the
installer, submits it for notarization, staples the ticket, and verifies the
package. Keep certificates and notary credentials outside Git and untrusted CI.

`bash apple/scripts/package-ios.sh` always produces an iPhone/iPad simulator
bundle and an unsigned device archive. To also export an IPA for registered
physical devices, install the app and Share Extension signing identities and
provisioning profiles on the Mac, register their shared App Group
`group.dev.glide.shared`, and set `GLIDE_IOS_TEAM_ID` and
`GLIDE_IOS_EXPORT_OPTIONS_PLIST` to a protected Xcode export-options plist.
The export method must support registered-device installation, and profiles
must cover both `dev.glide.ios` and `dev.glide.ios.ShareExtension`. The script
verifies signatures, profiles, and the App Group entitlement before publishing
the IPA. Without those signing inputs, it does not label an unsigned archive
as installable on a physical iPhone or iPad.
