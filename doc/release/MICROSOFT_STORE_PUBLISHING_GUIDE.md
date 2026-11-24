# Microsoft Store & Windows Ecosystem Publishing Guide — Glide v1.0.0

**Target Application:** `Glide.Windows.UI` (.NET 10 WPF Desktop Companion & `Glide.exe` CLI Runner)  
**Primary Distribution Channels:** Microsoft Store (Partner Center) & Windows Package Manager (WinGet)  
**Supported Ingestion Models:**
1. **Pathway A (Ready Out-of-the-Box):** Unpackaged Win32 Direct Download Model (`.msi` via WiX v5 or `.exe` via NSIS 3.12+)
2. **Pathway B (Packaged Container):** Desktop Bridge MSIX Model (`.msixupload` with `runFullTrust`)
3. **Pathway C (CLI Package Manager):** Official Microsoft WinGet Community Repository (`microsoft/winget-pkgs`)

**Related Specifications:**
- [`doc/planning/windows-msi-store-submission-plan.md`](../planning/windows-msi-store-submission-plan.md) — Unpackaged Win32 `.msi` Technical Specification
- [`doc/planning/windows-store/MICROSOFT_STORE_DEPLOYMENT_PLAN.md`](../planning/windows-store/MICROSOFT_STORE_DEPLOYMENT_PLAN.md) — Packaged Desktop Bridge `.msixupload` Specification
- [`doc/release/GOOGLE_PLAY_PUBLISHING_GUIDE.md`](GOOGLE_PLAY_PUBLISHING_GUIDE.md) — Companion Google Play Console Publishing Guide
- [`doc/release/RELEASE_PROCEDURE_V1.md`](RELEASE_PROCEDURE_V1.md) — Multi-Platform v1.0.0 Production Release Runbook

---

## 1. Choosing Your Microsoft Store Publishing Pathway

Microsoft Partner Center supports two distinct ingestion models for Windows desktop applications. Glide supports both:

| Feature / Requirement | Pathway A: Win32 Direct Installer (`.msi`) *(Recommended for Immediate Release)* | Pathway B: Desktop Bridge (`.msixupload`) |
| :--- | :--- | :--- |
| **Repository Script** | [`desktop/windows/package-windows-msi.ps1`](../../desktop/windows/package-windows-msi.ps1) | [`desktop/windows/package-msix.ps1`](../../desktop/windows/package-msix.ps1) (see [MSIX Deployment Plan](../planning/windows-store/MICROSOFT_STORE_DEPLOYMENT_PLAN.md)) |
| **Artifact Format** | `Glide-Setup-1.0.0-win-x64.msi` (WiX v5, Per-Machine) | `Glide-1.0.0.0-win-x64.msixupload` (`.msix` + `.appxsym`) |
| **Hosting Model** | Hosted by publisher at a **non-redirecting** HTTPS URL (GitHub Releases 302 redirects are rejected) | Uploaded directly to Microsoft Partner Center & hosted on Microsoft Store CDN |
| **Code Signing Rule** | **Mandatory before upload:** Must be signed with a CA-issued Authenticode certificate trusted by Microsoft Root Program | **Free Microsoft Store Signing:** Upload unsigned `.msixupload` with matching Partner Center identity; Microsoft signs it automatically on certification |
| **Update Mechanism** | Partner Center URL update (`MajorUpgrade` via stable `UpgradeCode`) | Automatic block-level differential updates managed by Microsoft Store |
| **Firewall & Protocol** | Registers `HKCR\glide` & `C:\Program Files\Glide` via Windows Installer | Pre-provisions `desktop2:FirewallRules` (UDP 5353, TCP) & `glide://` in `AppxManifest.xml` |

---

## 2. Local Pre-Flight: Build, Verify, and Sign Windows Artifacts

Before logging into Microsoft Partner Center, build and verify the release binaries from a clean commit on a Windows 11 x64 build host.

### 2.1 Verify Toolchain & Architecture Boundaries

Run the environment doctor, architecture check, and Windows companion conformance suite from the repository root:

```powershell
# 1. Verify .NET 10 SDK, JDK 21+, Git, and Windows build environment
pwsh -File tools/doctor.ps1

# 2. Enforce zero-leakage module boundaries
pwsh -File tools/check-architecture.ps1 -FailFast

# 3. Run full desktop build, 300+ xUnit tests, and security/protocol conformance audit
pwsh -File tools/verify-windows-companion.ps1

# 4. Audit repository for signing key custody (zero committed secrets)
pwsh -File tools/verify-signing-custody.ps1
```

---

### 2.2 Pathway A Build: WiX v5 `.msi` & NSIS `.exe` Packages

#### Step 1: Ensure WiX Toolset v5 & NSIS 3.12+ Are Installed
```powershell
# Install WiX v5 CLI globally if not already present
dotnet tool install --global wix --version 5.0.2

# Verify NSIS compiler (makensis.exe) is available
makensis -VERSION
```

#### Step 2: Build the Portable Archive and NSIS Per-User Installer (`.exe`)
> **Important Store Policy 10.2.8 Rule:** For any binary distributed through the Microsoft Store, omit `-UpdateFeedUrl` and `-UpdatePublicKey` (leave them empty `""`). This keeps `GlideUpdateCoordinator` (NetSparkle) completely dormant, satisfying Microsoft Store Policy 10.2.8 (*"Products offered in the Store must be updated only through the Store infrastructure"*).

```powershell
pwsh -File desktop/windows/package-windows.ps1 `
    -Configuration Release `
    -Version "1.0.0" `
    -Runtime "win-x64"
```

#### Step 3: Build & ICE-Validate the WiX v5 Per-Machine Installer (`.msi`)
```powershell
pwsh -File desktop/windows/package-windows-msi.ps1 `
    -Configuration Release `
    -Version "1.0.0" `
    -Runtime "win-x64"
```

This produces the following verified outputs in `build/distributions/windows/`:
- `Glide-Setup-1.0.0-win-x64.msi` — Per-machine Windows Installer (`UpgradeCode: 88597E1C-6246-4A51-B833-311D79E78C20`, validated with `wix msi validate` for zero ICE errors).
- `Glide-Setup-1.0.0-win-x64.exe` — Per-user NSIS graphical installer (`%LOCALAPPDATA%\Programs\Glide`).
- `glide-windows-win-x64.zip` — Self-contained portable ZIP archive.
- `SHA256SUMS-win-x64.txt` — Lowercase SHA-256 checksum catalog for all three artifacts.
- `BUILD-MANIFEST.txt` — Provenance record (`source_commit`, `dotnet_sdk`, `wix_version`, `nsis_version`).

#### Step 4: Run the 4-Phase Automated MSI Verification Suite
Verify silent installation (`/qn /norestart`), installed binary execution (`Glide.exe --help`), silent uninstallation (`/x /qn /norestart`), and zero leftover files:

```powershell
pwsh -File tools/verify-windows-msi.ps1
```
*(Run from an elevated Administrator PowerShell prompt to test full `perMachine` installation into `C:\Program Files\Glide`, or non-elevated to test `perUser` sandbox redirection).*

#### Step 5: Authenticode Digital Code Signing (Required for Pathway A Win32 Store Ingestion)
Microsoft Partner Center requires unpackaged `.msi` and `.exe` binaries to be signed with an Authenticode certificate issued by a CA in the **Microsoft Trusted Root Program** (e.g., Azure Trusted Signing, DigiCert, Sectigo, SSL.com).

> **Security Guardrail:** Never store `.pfx`, `.p12`, or private keys inside the repository. Sign artifacts in your protected release environment or via hardware token / Azure Key Vault.

1. **Sign the staged `Glide.exe` binary first, then build and sign the `.msi` and `.exe` installers:**
   ```powershell
   # Locate Windows SDK SignTool
   $signTool = Get-ChildItem "${env:ProgramFiles(x86)}\Windows Kits\10\bin\*\x64\signtool.exe" |
       Select-Object -Last 1 -ExpandProperty FullName

   # 1. Sign the staged application executable
   & $signTool sign `
       /tr "http://timestamp.digicert.com" `
       /td sha256 `
       /fd sha256 `
       /d "Glide — Fast Local Share" `
       /du "https://github.com/GDJ2001/Glide" `
       /a "build\distributions\windows\stage-win-x64\Glide.exe"

   # 2. Rebuild the MSI and NSIS packages wrapping the signed Glide.exe (-SkipPublish reuses stage-win-x64)
   pwsh -File desktop/windows/package-windows.ps1 -Configuration Release -Version "1.0.0" -Runtime "win-x64" -SkipPublish
   pwsh -File desktop/windows/package-windows-msi.ps1 -Configuration Release -Version "1.0.0" -Runtime "win-x64"

   # 3. Sign the generated MSI and NSIS Setup installers
   & $signTool sign `
       /tr "http://timestamp.digicert.com" `
       /td sha256 `
       /fd sha256 `
       /d "Glide Windows Installer" `
       /du "https://github.com/GDJ2001/Glide" `
       /a "build\distributions\windows\Glide-Setup-1.0.0-win-x64.msi" `
          "build\distributions\windows\Glide-Setup-1.0.0-win-x64.exe"

   # 4. Verify Authenticode signatures and timestamps
   & $signTool verify /pa /v "build\distributions\windows\Glide-Setup-1.0.0-win-x64.msi"
   & $signTool verify /pa /v "build\distributions\windows\Glide-Setup-1.0.0-win-x64.exe"

   # 5. Recompute final SHA256SUMS-win-x64.txt after signing
   $outDir = "build\distributions\windows"
   @(
       "Glide-Setup-1.0.0-win-x64.exe",
       "Glide-Setup-1.0.0-win-x64.msi",
       "glide-windows-win-x64.zip"
   ) | ForEach-Object {
       $h = (Get-FileHash -Algorithm SHA256 -Path (Join-Path $outDir $_)).Hash.ToLowerInvariant()
       "$h  $_"
   } | Set-Content -Path (Join-Path $outDir "SHA256SUMS-win-x64.txt") -Encoding utf8
   ```

#### Step 6: Upload `.msi` to an Immutable HTTPS Release Endpoint
For Pathway A (Unpackaged Win32), Partner Center downloads your `.msi` from a public HTTPS URL during submission verification and end-user installation. Publish the signed `.msi` to GitHub Releases:

```powershell
git tag -a v1.0.0 -m "Glide v1.0.0 Release"
git push origin v1.0.0

gh release create v1.0.0 `
    --title "Glide v1.0.0" `
    --notes-file "doc/release/V1_0_0_RELEASE_NOTES.md" `
    build/distributions/windows/Glide-Setup-1.0.0-win-x64.msi `
    build/distributions/windows/Glide-Setup-1.0.0-win-x64.exe `
    build/distributions/windows/glide-windows-win-x64.zip `
    build/distributions/windows/SHA256SUMS-win-x64.txt
```

Your authoritative, immutable HTTPS installer URL for Partner Center is now:
```text
https://github.com/GDJ2001/Glide/releases/download/v1.0.0/Glide-Setup-1.0.0-win-x64.msi
```

---

### 2.3 Pathway B Build: Desktop Bridge `.msixupload` Package *(Recommended — Free Microsoft Signing)*

Uploading an `.msixupload` container directly to Microsoft Partner Center avoids external HTTPS hosting, avoids GitHub Releases `302` redirect rejections, and includes **free Microsoft Store code signing** upon certification.

1. **Create an MSIX Product in Partner Center (Cannot Convert Existing `/win32apps/` Draft In-Place):**
   - Partner Center does not convert an existing **Win32 / EXE or MSI** draft into an MSIX app in-place.
   - If you want to reuse the exact reserved name (such as `Glide — Fast Local Share`), open your unused Win32 draft -> **Product management** -> **Manage app name** (or **Manage app names**) and delete the unused name reservation first, **or** reserve `Glide` / `Glide — Offline Local Share` on a new product.
   - Click **Apps and games** -> **+ New product** -> **MSIX or PWA app** and reserve your product name.
2. **Retrieve Your Exact Identity Strings from Partner Center:**
   - Open your new MSIX app -> **Product management** -> **Product identity**.
   - Copy the exact 3 strings assigned by Microsoft:
     - **`Package/Identity/Name`:** e.g., `Glide.GlideApp` (or your assigned Store ID)
     - **`Package/Identity/Publisher`:** e.g., `CN=XXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXXXXXX`
     - **`Package/Properties/PublisherDisplayName`:** e.g., `Geethika Dilshan Jayarathna` *(must match case and whitespace character-for-character)*.
3. **Build the Personalized `.msixupload` Container:**
   Run [`desktop/windows/package-msix.ps1`](../../desktop/windows/package-msix.ps1) (which automatically resolves `makeappx.exe`, `makepri.exe`, and `signtool.exe` from Windows Kits 10 or NuGet `Microsoft.Windows.SDK.BuildTools`, compiles `resources.pri`, strips non-Windows native `.so` binaries and `.pdb` symbols from the `.msix`, and packs `.msix` + `.appxsym` into `.msixupload`):
   ```powershell
   pwsh -File desktop/windows/package-msix.ps1 `
       -Configuration Release `
       -Version "1.0.0.0" `
       -Runtime "win-x64" `
       -DisplayName "<Reserved App Name>" `
       -PackageName "<Package/Identity/Name>" `
       -PublisherId "<Package/Identity/Publisher>" `
       -PublisherDisplayName "<Package/Properties/PublisherDisplayName>"
   ```
   - **Quad Versioning Rule (`Major.Minor.Build.0`):** The 4th version segment must always be `0` (`1.0.0.0`).
   - **Architecture (`x64`):** `ProcessorArchitecture="x64"` runs natively on x64 PCs and seamlessly on Windows 11 ARM64 via Prism x64 emulation while keeping `glide_spake2.dll` compatible.
   - Output ready for Partner Center upload: `build/distributions/windows/msix/Glide-1.0.0.0-win-x64.msixupload`.
   - *(Optional local sideload testing)*: Pass `-SignForLocalTest` to also sign the standalone `.msix` with an ephemeral local certificate without modifying the Store `.msixupload`.

---

## 3. Microsoft Partner Center Setup & Account Prerequisites

1. **Open Microsoft Partner Center:**
   - Go to [https://partner.microsoft.com/dashboard](https://partner.microsoft.com/dashboard) and sign in with your Microsoft Account (MSA) or Microsoft Entra ID.
2. **Account Registration & Identity Verification:**
   - **Individual Developer Account:** Free registration for individual developers (no onboarding fee in supported markets; requires government-issued ID verification).
   - **Company / Organization Account:** Requires a D-U-N-S Number (Dun & Bradstreet), corporate domain email verification, and Authorized Signer verification.
   - Check **Account settings** (gear icon in top right) -> **Legal info** -> verify that your **Developer Verification status** shows **Authorized / Verified**.
   - Copy your **Publisher ID (`CN=...`)** and **Publisher display name** from **Account settings -> Organization profile -> Legal info** for reference.

---

## 4. Creating the App & Reserving the Name in Partner Center

1. From the Partner Center home dashboard, click **Apps and games**.
2. Click **+ New product**:
   - **If using Pathway B (`.msixupload` — Recommended):** Select **MSIX or PWA app**.
   - **If using Pathway A (`.msi` or `.exe` Win32 installer):** Select **Win32 or PWA app** (or **EXE or MSI app**).
3. **Reserve Product Name:**
   - Enter **`Glide`** (or **`Glide — Fast Local Share`** if available).
   - Click **Check availability** -> **Reserve product name**.

---

## 5. Step-by-Step Partner Center Submission Walkthrough

In your newly created **Glide** app overview page, click **Start your submission** (Submission 1) and complete each section from top to bottom:

---

### 5.1 Step 1: Pricing and Availability

Click **Pricing and availability**:

1. **Markets:**
   - Leave default: **All available markets (241 markets)** selected.
2. **Base price:**
   - Select **Free** (`0.00 USD`).
3. **Visibility:**
   - Select **Make this product available in the Store**:
     - Discoverability: **Make this product available and discoverable in the Store**.
4. **Schedule:**
   - Release date: **As soon as possible after certification** (default).
5. Click **Save draft**.

---

### 5.2 Step 2: Properties

Click **Properties**:

1. **Category and subcategory:**
   - **Category:** `Productivity` (or `Utilities & tools`)
   - **Subcategory:** `File Management` (or `File managers`)
2. **Support info (Required by Store Policy 10.5):**
   - **Privacy policy URL \*:**
     ```text
     https://gdj2001.github.io/Glide/privacy-policy/
     ```
   - **Website:**
     ```text
     https://github.com/GDJ2001/Glide
     ```
   - **Support contact info (Email):** Enter your developer support email address.
3. **Display mode & Hardware Preferences:**
   - Check **PC / Desktop**.
   - Recommended hardware: Check **Mouse** and **Keyboard** (Touch optional).
4. **System requirements:**
   - **Minimum OS:** Windows 10 version 2004 (Build 19041.0)
   - **Recommended OS:** Windows 11 (64-bit)
   - **Memory:** Minimum `2 GB`, Recommended `4 GB`
   - **Processor:** Minimum `x64 Dual-Core 1.6 GHz`, Recommended `x64 Quad-Core 2.0 GHz+`
5. **Declarations:**
   - Check the box confirming you have authorization to distribute all content and comply with Microsoft Store Policies.
   - *(If asked for Export Compliance / Cryptography)*: Declare standard commercial encryption (**TLS 1.3 and SPAKE2 authenticated key exchange** for local network file transfer only).
6. Click **Save draft**.

---

### 5.3 Step 3: Age Ratings (IARC Questionnaire)

Click **Age ratings**:

1. **Rating Type:** Select **Utility, Productivity, Communication, or Other**.
2. **Complete the IARC Questionnaire with these exact answers:**
   - Does the product contain violence, blood, or gore? -> **No**
   - Does the product contain sexual content or nudity? -> **No**
   - Does the product contain profanity or crude humor? -> **No**
   - Does the product reference or depict controlled substances (alcohol, tobacco, drugs)? -> **No**
   - **User Interaction / Content Sharing:**
     - *Does the app natively allow users to interact or exchange content with other users?* -> **Yes**
     - *Does the app share content publicly or with strangers over the internet?* -> **No** *(Transfers are strictly offline, local device-to-device on the same subnet with explicit mutual 6-digit SAS verification and receiver consent).*
   - Does the app share the user's current physical location with other users? -> **No**
   - Does the app allow users to purchase digital goods or currency? -> **No**
   - Is the app a web browser or search engine? -> **No**
3. Click **Save and generate** -> **Continue** -> **Save draft**.
   - **Expected Output:** `ESRB: Everyone`, `PEGI: 3`, `USK: 0`, `IARC: 3+ (All Ages)`.

---

### 5.4 Step 4: Packages

Click **Packages** and follow the instructions for your chosen pathway:

#### Option A: Unpackaged Win32 App (`.msi` Direct URL Model)
1. Click **Add package** (or configure the Win32 package form):
2. **Package URL (HTTPS) \*:**
   ```text
   https://github.com/GDJ2001/Glide/releases/download/v1.0.0/Glide-Setup-1.0.0-win-x64.msi
   ```
3. **Architecture:** Select **x64**.
4. **Installer Type:** Select **MSI**.
5. **Installer Parameters (Silent Install Switches):**
   ```text
   /qn /norestart
   ```
   *(Partner Center runs `msiexec.exe /i Glide-Setup-1.0.0-win-x64.msi /qn /norestart` under the `NT AUTHORITY\SYSTEM` context).*
6. **Installer Handling / Return Codes:**
   - **`0`**: Success (`ERROR_SUCCESS`)
   - **`3010`**: Soft reboot required / Success (`ERROR_SUCCESS_REBOOT_REQUIRED`)
   - **`1638`**: Another version already installed (`ERROR_PRODUCT_VERSION` — handled automatically by WiX `<MajorUpgrade>`)
   - **`1618`**: Another installation is already in progress (`ERROR_INSTALL_ALREADY_RUNNING`)
7. **Supported Languages:** Select **English (United States) (`en-US`)**.
8. **Installed App Metadata (ARP Match):**
   - **Display Name:** `Glide`
   - **Publisher:** `Glide`
   - **Version:** `1.0.0`
   - **UpgradeCode:** `{88597E1C-6246-4A51-B833-311D79E78C20}`
9. Click **Verify / Save**. Partner Center will verify that the HTTPS URL is reachable and matches the `.msi` format.

*(Note: If you prefer submitting the per-user NSIS `.exe` installer instead of the `.msi`, set Package URL to `https://github.com/GDJ2001/Glide/releases/download/v1.0.0/Glide-Setup-1.0.0-win-x64.exe`, Installer Type to **EXE**, Silent Install Parameter to **`/S`**, and Type to **User installer**).*

#### Option B: Packaged Desktop Bridge App (`.msixupload` Direct Upload)
1. Drag and drop `build/distributions/windows/msix/Glide-1.0.0.0-win-x64.msixupload` into the **Drag your packages here (.msix, .msixbundle, .msixupload)** upload box.
2. Wait for package parsing and validation to finish.
3. Under **Device family availability**, verify **Windows 10/11 Desktop** is checked (and uncheck Xbox, HoloLens, and Hub).
4. Click **Save**.

---

### 5.5 Step 5: Store Listings (`en-US`)

Click **Store listings** -> **English (United States)**:

#### 1. Product Name
Select your reserved product name: **`Glide`** (or **`Glide — Fast Local Share`**).

#### 2. Short Description (Max 100 characters for Win32 / 200 chars general)
Copy and paste (98 characters):
```text
Fast, secure, zero-cloud peer-to-peer file transfer between Windows and Android devices over LAN.
```

#### 3. Description (Max 10,000 characters)
Copy and paste:
```text
Glide is an ultra-fast, zero-cloud, peer-to-peer file sharing application engineered for direct local transfers between Windows PCs and Android devices over your local Wi-Fi or Ethernet network.

Share high-resolution photos, 4K videos, code archives, large documents, and entire multi-file folders directly between devices—completely offline, with zero internet bandwidth consumption, zero cloud uploads, and zero tracking.

⚡ HIGH-THROUGHPUT OFFLINE LAN TRANSFERS
• Gigabit-Class Streaming: Saturates local Wi-Fi 6 and Gigabit Ethernet links using asynchronous, bounded-memory socket streaming.
• 100% Offline & Local: Transfers travel straight from Device A to Device B over your local subnet. No internet connection is required.
• Massive File & Batch Support: Transfer individual files up to 64 GiB and batches of up to 1,000 items (up to 256 GiB per batch) in a single session.

🔒 UNCOMPROMISING SECURITY & CRYPTOGRAPHIC VERIFICATION
• Mutual TLS 1.3 & SPKI Pinning: Every transfer channel is encrypted end-to-end with TLS 1.3 (`glide-lan/1`) and pinned out-of-band SubjectPublicKeyInfo (SPKI) SHA-256 certificates.
• Instant QR, Link & 6-Digit SAS Pairing: Connect devices in seconds via QR codes, `glide://pair/v1` pairing links, or zero-dependency local mDNS discovery (`_glide._tcp.local.`), verified with a 6-digit Short Authentication String (SAS) visual code.
• Unconditional Receiver Consent: Glide never auto-accepts files. Review sender identity, file names, item counts, and total payload size before accepting any transfer.
• Trusted Device Reconnect: Save verified peer identities for rapid authenticated reconnection across sessions, with one-click trust revocation at any time.

🛡️ QUARANTINE STAGING & FILESYSTEM SAFETY
• Zero-Trust Quarantine Sandbox: Inbound bytes stream into an isolated private staging area (`.quarantine` / `.part` files) while calculating an incremental streaming SHA-256 hash.
• Atomic Destination Commit: Files are atomically moved to your Downloads folder only after the computed SHA-256 digest matches the sender's cryptographic manifest. Corrupted or cancelled transfers are purged automatically—zero unverified bytes ever reach your folders.
• Strict Path Sanitization: Built-in defense-in-depth scrubbing blocks directory traversal (`..`), UNC paths, drive roots, and reserved Windows device names.

💻 NATIVE WINDOWS 11 DESKTOP & TERMINAL CLI
• Modern Dark-Themed WPF Interface: Clean, responsive desktop UI with live transfer speed telemetry, QR pairing cards, transfer history, and system tray integration.
• Built-In Command-Line Interface: Includes a full CLI engine (`glide send`, `glide receive`, `glide identity`, `glide trust`) accessible directly from Windows Terminal and PowerShell.

Zero user accounts. Zero cloud relays. Zero advertisements. Zero telemetry.
```

#### 4. What's New in This Version (Release Notes)
Copy and paste:
```text
Glide v1.0.0 Initial Windows Release:
• High-speed peer-to-peer local file and folder transfer between Windows 11/10 (x64) and Android devices.
• End-to-end TLS 1.3 encryption with out-of-band SHA-256 SPKI certificate pinning and SPAKE2 mutual authentication.
• Zero-configuration local mDNS peer discovery (_glide._tcp.local.), QR code invitation generation, and 6-digit SAS verification.
• Private quarantine staging with on-the-fly streaming SHA-256 verification and atomic commit to Downloads.
• Native dark-themed WPF desktop interface plus built-in command-line runner (glide send / glide receive).
```

#### 5. Product Features (Up to 20 bullet points, max 200 chars each)
Add the following feature bullets:
1. `100% offline peer-to-peer file sharing over local Wi-Fi and Ethernet with zero cloud servers.`
2. `End-to-end TLS 1.3 encryption with pinned SHA-256 SPKI certificates and 6-digit SAS verification.`
3. `Instant QR code pairing, glide:// protocol links, and zero-dependency mDNS local device discovery.`
4. `Private quarantine staging with streaming SHA-256 integrity checks before atomic file commit.`
5. `Supports large files up to 64 GiB each and multi-file batches up to 1,000 items (256 GiB total).`
6. `Includes both a modern dark-themed Windows desktop GUI and a full command-line interface (CLI).`
7. `Zero user accounts, zero ads, zero tracking, and zero external telemetry.`

#### 6. Search Terms / Keywords (Max 7 terms, max 30 chars each)
Enter these 7 keywords:
1. `file transfer`
2. `local share`
3. `p2p file sharing`
4. `offline transfer`
5. `lan file share`
6. `android to pc transfer`
7. `quick share alternative`

#### 7. Copyright and Trademark Info / Additional License Terms
- **Copyright and trademark info:**
  ```text
  Copyright © 2026 Glide Contributors. Licensed under the Apache License, Version 2.0.
  ```
- **Additional license terms:**
  ```text
  Licensed under the Apache License, Version 2.0 (https://www.apache.org/licenses/LICENSE-2.0). Third-party open-source attributions (NetSparkle, QRCoder, BoringSSL, Fluent System Icons) are bundled with the application and documented at https://github.com/GDJ2001/Glide/blob/main/doc/release/OPEN_SOURCE_LICENSES.md.
  ```

#### 8. Screenshots & Store Logos
Upload visual assets from [`assets/`](../../assets/) and your Windows desktop captures:
1. **Desktop Screenshots (Required: minimum 1, recommended 4–6 PNG images at `1920x1080` or `1366x768`, 16:9 landscape):**
   - Screenshot 1: **Send / Discovery View** — Main dark-themed window showing discovered local peers and file/folder batch selection.
   - Screenshot 2: **Receive & QR Pairing View** — Ephemeral QR code pairing card and `glide://pair/v1` invitation link.
   - Screenshot 3: **6-Digit SAS Mutual Verification & Receive Consent** — Inbound transfer consent dialog showing file list, size, and 6-digit SAS code.
   - Screenshot 4: **Active High-Speed Transfer & History** — Live throughput progress bar and SHA-256 verified transfer history.
2. **Store Logos:**
   - **1:1 Box Art / App Tile Icon (`300x300` px or `1080x1080` px PNG):** Use [`assets/platforms/desktop/glide-512.png`](../../assets/platforms/desktop/glide-512.png) or [`assets/platforms/desktop/glide-1024.png`](../../assets/platforms/desktop/glide-1024.png) (resized to `300x300` / `1080x1080`).
   - **2:3 Poster Art (`720x1080` px or `1440x2160` px PNG, optional but recommended for Store search cards).**

Click **Save draft**.

---

### 5.6 Step 6: Submission Options & Notes for Certification

Click **Submission options** (or **Publishing options**):

1. **PublishingHold / Release timing:**
   - Select **Publish this product as soon as it passes certification**.
2. **Notes for certification (Crucial — Prevents Reviewer Misunderstanding):**
   Copy and paste the following complete reviewer guide into the **Notes for certification** text box:

   ```text
   APPLICATION OVERVIEW & TESTING INSTRUCTIONS FOR CERTIFICATION REVIEWERS:

   1. Core Functionality & Zero-Account Design:
   Glide is a 100% offline, local-network peer-to-peer file transfer utility for Windows and Android.
   - No login, user account, subscription, or credentials are required. All features are immediately available upon launch.
   - Zero cloud servers or telemetry: All file transfers occur strictly on the local subnet over TLS 1.3 TCP sockets with mDNS discovery (UDP 5353).

   2. How to Test Application Launch & UI:
   - Launch Glide from the Start Menu. The main desktop window opens immediately (< 2 seconds), displaying the Send, Receive, Trusted Devices, History, and Settings tabs.
   - Click "Receive" to generate a live ephemeral local pairing QR code and a one-time `glide://pair/v1#...` invitation URI.
   - Click "Send" to select local files or folders via the standard Windows file picker and scan for nearby devices on the local subnet.
   - Even if the test VM has no peer device on the local subnet or has networking disabled, Glide handles offline states gracefully and renders all UI views cleanly without error.
   - You can also verify the built-in CLI by running `Glide.exe --help` or `Glide.exe identity`.

   3. Justification for `runFullTrust` / Win32 Desktop Execution (Store Policy 10.2 & 10.4 Compliance):
   - Raw Socket Transport & Multicast Discovery: Glide binds a local TLS 1.3 TCP listener (`TcpListener`) for peer-to-peer transfers and a UDP multicast socket (`224.0.0.251:5353`) for zero-dependency RFC 6762 mDNS discovery.
   - Quarantine Staging & Atomic Commit: Inbound files stream into private `%LOCALAPPDATA%\Glide\staging` `.quarantine` files while computing an incremental SHA-256 hash, and are atomically moved (`File.Move`) to the user's Downloads folder only after cryptographic hash verification.
   - Single-Instance Named Pipe Activation: Uses a current-user local named pipe (`Glide.Windows.UI.Activation`) to forward `glide://` pairing protocol links to the active window.
   - Store Update Policy Compliance (Policy 10.2.8): External auto-update checking is completely disabled in Store builds (`GlideUpdateFeedUrl` is unset/empty); all updates are serviced exclusively through the Microsoft Store.
   ```

3. Click **Save**.

---

### 5.7 Step 7: Submit to the Store

1. On the **Submission overview** page, verify all sections (**Pricing and availability**, **Properties**, **Age ratings**, **Packages**, **Store listings**, **Submission options**) display **Complete**.
2. Click **Submit to the Store**.
3. **Certification Timeline:**
   - **Automated Pre-Processing & Security Scan:** 15–60 minutes (verifies binary hashes, Authenticode signatures, malware scans, and silent install/uninstall exit code `0`).
   - **Policy & Manual Certification Review:** Typically **24 to 72 business hours**.
   - **Publishing:** Once approved, Glide goes live worldwide in the Microsoft Store application and at `https://apps.microsoft.com/detail/<STORE-PRODUCT-ID>`.

---

## 6. Channel C: Publishing to Windows Package Manager (WinGet)

Publishing Glide to Microsoft's official **Windows Package Manager (`winget`)** repository ([`microsoft/winget-pkgs`](https://github.com/microsoft/winget-pkgs)) allows any Windows 10/11 user to install Glide from the terminal via:
```powershell
winget install --id Glide.Glide
```

### 6.1 Install `wingetcreate`
```powershell
winget install --id Microsoft.WingetCreate
```

### 6.2 Generate or Author the Multi-File WinGet Manifest (v1.9.0 Schema)

Once `Glide-Setup-1.0.0-win-x64.msi` is published on GitHub Releases and signed, compute its final SHA-256 hash:
```powershell
$msiSha256 = (Get-FileHash -Algorithm SHA256 -Path "build\distributions\windows\Glide-Setup-1.0.0-win-x64.msi").Hash.ToUpperInvariant()
Write-Host "MSI SHA-256: $msiSha256"
```

Create the three manifest files under `manifests/g/Glide/Glide/1.0.0/`:

#### 1. `Glide.Glide.yaml` (Version Manifest)
```yaml
# yaml-language-server: $schema=https://aka.ms/winget-manifest.version.1.9.0.schema.json
PackageIdentifier: Glide.Glide
PackageVersion: 1.0.0
DefaultLocale: en-US
ManifestType: version
ManifestVersion: 1.9.0
```

#### 2. `Glide.Glide.installer.yaml` (Installer Manifest)
```yaml
# yaml-language-server: $schema=https://aka.ms/winget-manifest.installer.1.9.0.schema.json
PackageIdentifier: Glide.Glide
PackageVersion: 1.0.0
InstallerLocale: en-US
MinimumOSVersion: 10.0.19041.0
InstallerType: wix
Scope: machine
InstallModes:
  - interactive
  - silent
  - silentWithProgress
UpgradeBehavior: install
Protocols:
  - glide
Commands:
  - glide
ReleaseDate: 2026-11-24
Installers:
  - Architecture: x64
    InstallerUrl: https://github.com/GDJ2001/Glide/releases/download/v1.0.0/Glide-Setup-1.0.0-win-x64.msi
    InstallerSha256: <REPLACE_WITH_64_HEX_UPPERCASE_SHA256>
    AppsAndFeaturesEntries:
      - DisplayName: Glide
        Publisher: Glide
        DisplayVersion: 1.0.0
        UpgradeCode: '{88597E1C-6246-4A51-B833-311D79E78C20}'
ManifestType: installer
ManifestVersion: 1.9.0
```

#### 3. `Glide.Glide.locale.en-US.yaml` (Default Locale Manifest)
```yaml
# yaml-language-server: $schema=https://aka.ms/winget-manifest.defaultLocale.1.9.0.schema.json
PackageIdentifier: Glide.Glide
PackageVersion: 1.0.0
PackageLocale: en-US
Publisher: Glide
PublisherUrl: https://github.com/GDJ2001/Glide
PublisherSupportUrl: https://github.com/GDJ2001/Glide/issues
PrivacyUrl: https://gdj2001.github.io/Glide/privacy-policy/
Author: Geethika Dilshan Jayarathna
PackageName: Glide
PackageUrl: https://github.com/GDJ2001/Glide
License: Apache-2.0
LicenseUrl: https://github.com/GDJ2001/Glide/blob/main/LICENSE
Copyright: Copyright (c) 2026 Glide Contributors
ShortDescription: Offline, peer-to-peer, zero-cloud local file transfer system for Windows and Android.
Description: >-
  Glide is a high-speed, zero-cloud peer-to-peer file sharing application for Windows and Android.
  It transfers files and folders directly over your local Wi-Fi or Ethernet network using TLS 1.3
  encryption, out-of-band SHA-256 SPKI certificate pinning, mDNS discovery, QR code invitations,
  6-digit SAS verification, and private quarantine staging with streaming SHA-256 verification.
Moniker: glide
Tags:
  - airdrop
  - android
  - file-sharing
  - file-transfer
  - lan
  - local-share
  - offline
  - p2p
  - peer-to-peer
ReleaseNotesUrl: https://github.com/GDJ2001/Glide/releases/tag/v1.0.0
ManifestType: defaultLocale
ManifestVersion: 1.9.0
```

### 6.3 Validate & Submit to `microsoft/winget-pkgs`
```powershell
# 1. Enable local manifest testing in WinGet (Requires Admin)
winget settings --enable LocalManifestFiles

# 2. Validate YAML syntax and schema compliance
winget validate --manifest .\manifests\g\Glide\Glide\1.0.0\

# 3. Test local installation from the manifest
winget install --manifest .\manifests\g\Glide\Glide\1.0.0\

# 4. Submit pull request automatically using wingetcreate
wingetcreate submit --token <YOUR_GITHUB_PAT> .\manifests\g\Glide\Glide\1.0.0\
```

---

## 7. Updating & Rollback Runbook for Subsequent Releases (`v1.0.1+`)

### 7.1 Publishing an Update (`v1.0.1`)
1. **Maintain Invariant `UpgradeCode`:**
   - Never modify `UpgradeCode="88597E1C-6246-4A51-B833-311D79E78C20"` in [`desktop/windows/installer/Glide.wxs`](../../desktop/windows/installer/Glide.wxs). WiX v5's `<MajorUpgrade Schedule="afterInstallExecute" />` uses this GUID to detect and seamlessly upgrade existing installations in place.
2. **Build & Verify New Packages:**
   ```powershell
   pwsh -File desktop/windows/package-windows.ps1 -Configuration Release -Version "1.0.1" -Runtime "win-x64"
   pwsh -File desktop/windows/package-windows-msi.ps1 -Configuration Release -Version "1.0.1" -Runtime "win-x64"
   pwsh -File tools/verify-windows-msi.ps1
   ```
3. **Sign & Upload to GitHub Releases (`v1.0.1`):**
   - Sign `Glide.exe`, rebuild the `.msi` and `.exe` packages, sign both installers, and publish tag `v1.0.1` on GitHub Releases.
4. **Update Partner Center:**
   - Open Partner Center -> **Glide** -> **Update**.
   - Under **Packages**, update the HTTPS URL to:
     `https://github.com/GDJ2001/Glide/releases/download/v1.0.1/Glide-Setup-1.0.1-win-x64.msi`
   - Update **What's new in this version** under **Store listings** and click **Submit to the Store**.
5. **Update WinGet:**
   ```powershell
   wingetcreate update Glide.Glide `
       --version 1.0.1 `
       --urls "https://github.com/GDJ2001/Glide/releases/download/v1.0.1/Glide-Setup-1.0.1-win-x64.msi" `
       --submit
   ```

### 7.2 Emergency Rollback Procedure
If a critical regression (SEV-1/SEV-2) is detected after submission:
1. **In-Flight Certification Cancellation:** If the submission is still in review, click **Cancel submission** in Partner Center overview.
2. **Post-Publication Forward Hotfix (Recommended):** Because Windows Installer and MSIX enforce monotonic version upgrades (`AllowSameVersionUpgrades="no"`), never re-upload a modified binary under an existing version tag (`1.0.0`). Instead, cut a hotfix release (`1.0.1`), verify with `tools/verify-windows-msi.ps1`, and submit `1.0.1` as an expedited update.
3. **Temporary Delisting:** If immediate halting of new installs is required while preparing a fix, go to **Pricing and availability** -> **Visibility** -> select **Stop acquisition (Make this product unavailable in the Store)** and submit.

---

## 8. Master Pre-Submission Checklist

Before clicking **Submit to the Store** in Microsoft Partner Center, confirm every item below is checked:

- [ ] `pwsh -File tools/verify-windows-companion.ps1` passes all 8 checks with 0 violations.
- [ ] `pwsh -File tools/verify-signing-custody.ps1` passes all 5 checks (zero keystores or `.pfx` files in Git).
- [ ] `Glide.Windows.UI` is published with empty `GlideUpdateFeedUrl=""` and `GlideUpdatePublicKey=""` so external NetSparkle self-updating is disabled for Store Policy 10.2.8 compliance.
- [ ] `pwsh -File desktop/windows/package-windows-msi.ps1 -Version 1.0.0` completes with **zero WiX ICE validation errors**.
- [ ] `pwsh -File tools/verify-windows-msi.ps1` passes all 4 phases (`Silent Install`, `Glide.exe --help`, `Silent Uninstall`, `Clean Directory Cleanup` with exit code `0`).
- [ ] Both the inner `Glide.exe` executable and the outer `Glide-Setup-1.0.0-win-x64.msi` installer are digitally signed with a trusted Authenticode certificate and RFC 3161 SHA-256 timestamp (`signtool verify /pa /v` succeeds).
- [ ] For Pathway A (`.msi`), `Glide-Setup-1.0.0-win-x64.msi` is uploaded to an immutable GitHub Release asset URL (`https://github.com/GDJ2001/Glide/releases/download/v1.0.0/Glide-Setup-1.0.0-win-x64.msi`) and returns HTTP `200 OK`.
- [ ] For Pathway B (`.msixupload`), `AppxManifest.xml` uses a 4-part version ending in `.0` (`1.0.0.0`), `ProcessorArchitecture="x64"`, exact Partner Center `Publisher` (`CN=...`) and `PublisherDisplayName`, and passes `appcert.exe` (WACK).
- [ ] Privacy Policy URL (`https://gdj2001.github.io/Glide/privacy-policy/`) is live and reachable over HTTPS.
- [ ] Reviewer instructions in **Notes for certification** clearly state that Glide is an offline local-network P2P app requiring no login credentials and explain the `runFullTrust` local socket/quarantine requirements.
