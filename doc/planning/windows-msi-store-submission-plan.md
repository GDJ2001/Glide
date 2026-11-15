# Microsoft Store MSI Submission Plan & Technical Specification for Glide Windows

**Target Application:** Glide Windows Companion (`Glide.exe`, .NET 10 WPF x64)  
**Distribution Channel:** Microsoft Store via Unpackaged Win32 Direct Download Model (.msi)  
**Document Path:** `doc/planning/windows-msi-store-submission-plan.md`  
**UpgradeCode GUID:** `88597E1C-6246-4A51-B833-311D79E78C20`  
**Status:** APPROVED & PRODUCTION-READY  

---

## 1. Executive Summary & Dual-Packaging Strategy

Glide is an offline, peer-to-peer, zero-cloud local file transfer companion for Android and Windows. On Windows, Glide delivers high-speed transfer over local Wi-Fi/Ethernet via TLS 1.3 sockets, zero-dependency mDNS peer discovery, and SAS mutual authentication.

### Dual-Distribution Strategy

To maximize distribution breadth while serving both individual web visitors and enterprise/store users, Glide maintains a dual Windows distribution model:

```
                                  +---------------------------+
                                  |   Glide.Windows.UI (WPF)  |
                                  |    .NET 10 Self-Contained |
                                  +-------------+-------------+
                                                |
                       +------------------------+------------------------+
                       |                                                 |
                       v                                                 v
         +---------------------------+                     +---------------------------+
         |     NSIS EXE Pipeline     |                     |     WiX v5 MSI Pipeline   |
         |  (desktop/windows/        |                     |  (desktop/windows/        |
         |   package-windows.ps1)    |                     |   package-windows-msi.ps1)|
         +-------------+-------------+                     +-------------+-------------+
                       |                                                 |
                       v                                                 v
         +---------------------------+                     +---------------------------+
         | Glide-Setup-1.0.0-win-    |                     | Glide-Setup-1.0.0-win-    |
         | x64.exe (Per-User Scope)  |                     | x64.msi (Per-Machine)     |
         +-------------+-------------+                     +-------------+-------------+
                       |                                                 |
                       v                                                 v
         +---------------------------+                     +---------------------------+
         | Website Direct Download   |                     | Microsoft Store Submission|
         | %LOCALAPPDATA%\Programs   |                     | Enterprise Deployment     |
         | Non-elevated manual setup |                     | Silent /qn /norestart     |
         +---------------------------+                     +---------------------------+
```

1. **NSIS Installer (`Glide-Setup-1.0.0-win-x64.exe`)**:
   - Installed per-user in `%LOCALAPPDATA%\Programs\Glide`.
   - Designed for direct website downloads without requiring administrative privileges.
   - Packaged via `desktop/windows/package-windows.ps1` and maintained in `desktop/windows/installer/Glide.nsi`.
2. **WiX v5 MSI Installer (`Glide-Setup-1.0.0-win-x64.msi`)**:
   - Installed per-machine in `C:\Program Files\Glide` (`[ProgramFiles64Folder]\Glide`).
   - Complies fully with Microsoft Store unpackaged Win32 application ingestion specifications and enterprise deployment systems (Microsoft Intune, SCCM, Group Policy).
   - Packaged via `desktop/windows/package-windows-msi.ps1` using the WiX v5 toolset (`desktop/windows/installer/Glide.wxs`).

---

## 2. Microsoft Store Unpackaged Win32 Direct HTTPS URL Ingestion Model

Microsoft Partner Center supports publishing unpackaged Win32 applications directly to the Microsoft Store without requiring Desktop Bridge (MSIX/AppX) container encapsulation.

### 2.1. Architectural Workflow
1. The publisher hosts the compiled, signed `.msi` package at a public, versioned, immutable HTTPS URL.
2. In Microsoft Partner Center, the publisher selects the **Win32 app** package type and provides the direct HTTPS download URL.
3. When a user clicks **Install** in the Microsoft Store client:
   - The Microsoft Store background service downloads the `.msi` binary from the configured HTTPS endpoint.
   - The Store service verifies the cryptographic hash and Authenticode signature of the installer.
   - The Store service executes the installer silently under elevated context (`msiexec.exe /i <package>.msi /qn /norestart`).
   - The Store client monitors execution and registers the application upon receiving exit code `0`.

### 2.2. Hosting Infrastructure & Immutable URL
- **Release Hosting Endpoint:** GitHub Releases asset endpoint for official version tags.
- **Authoritative v1.0.0 URL:**  
  `https://github.com/GDJ2001/Glide/releases/download/v1.0.0/Glide-Setup-1.0.0-win-x64.msi`
- **Immutability Guarantee:** GitHub Release assets attached to a release tag are immutable and globally accessible over TLS 1.3 with high availability CDN backing.
- **Store Policy 10.2 Compliance:**
  - The installer must be standalone and completely offline.
  - Zero dynamic runtime asset fetching: all dependencies (.NET 10 runtime, native `glide_spake2.dll`, assets, licenses) are bundled inside `Glide.exe` or staged directly in the `.msi` CAB stream.
  - No web down-loaders, bootstrap loaders, or third-party bundleware.

---

## 3. Silent Installation & Uninstallation Command-Line Architecture

Microsoft Store certification and automated deployment environments require non-interactive, headless execution.

### 3.1. Silent Installation Command
```cmd
msiexec.exe /i "Glide-Setup-1.0.0-win-x64.msi" /qn /norestart
```
- `/i`: Normal installation.
- `/qn`: Quiet unattended execution with no user interface (no wizard pages, dialogs, or confirmation prompts).
- `/norestart`: Prevents the installer from initiating a system reboot upon completion.

### 3.2. Silent Uninstallation Command
The Microsoft Store and Windows Settings app uninstallation handlers invoke the MSI removal command:
```cmd
msiexec.exe /x "Glide-Setup-1.0.0-win-x64.msi" /qn /norestart
```
Or via the assigned ProductCode GUID:
```cmd
msiexec.exe /x {PRODUCT-CODE-GUID} /qn /norestart
```
- `/x`: Uninstallation of the installed package.
- Complete cleanup of installed application files, shortcuts, and registry keys.

---

## 4. Windows Installer Exit Code Matrix

Microsoft Store and Windows Installer runtime rely on standardized Win32 / MSI return codes:

| Exit Code | Meaning | Microsoft Store Interpretation | Glide Packaging Guarantee |
| :--- | :--- | :--- | :--- |
| **`0`** | `ERROR_SUCCESS` | Installation/uninstallation succeeded. | Returned on clean completion of all install/uninstall actions. |
| **`3010`** | `ERROR_SUCCESS_REBOOT_REQUIRED` | Soft reboot required; install succeeded. | Avoided. Glide installs standalone files without replacing locked in-use system components. |
| **`1602`** | `ERROR_INSTALL_USEREXIT` | User cancelled operation. | Not applicable in `/qn` silent mode. |
| **`1603`** | `ERROR_INSTALL_FAILURE` | Fatal error during execution. | Prevented. Ensured by clean directory harvesting, verified permissions, and zero invalid custom actions. |
| **`1618`** | `ERROR_INSTALL_ALREADY_RUNNING` | Another installation in progress. | Store installer service queues installations sequentially. |
| **`1619`** | `ERROR_INSTALL_PACKAGE_OPEN_FAILED` | Package path invalid or inaccessible. | Prevented via validated direct HTTPS downloads. |
| **`1638`** | `ERROR_PRODUCT_VERSION` | Conflicting version already installed. | Managed gracefully by `<MajorUpgrade>` rules. |

---

## 5. Clean Install & Clean Uninstall Guarantees

Store Policy 10.2.7 requires clean, deterministic removal of all installed files and configurations created during installation.

### 5.1. Directory Layout & Storage Allocation
- **Installation Target Directory:**  
  `[ProgramFiles64Folder]\Glide` (`C:\Program Files\Glide`)
- **Staged Application Files:**
  - `Glide.exe` (Standalone self-contained executable bundling WPF UI, CLI runner, and core engine)
  - `glide_spake2.dll` (Native SPAKE2 mutual authentication library)
  - `glide.cmd` (Console wrapper for command-line invocations)
  - `glide.ico` (Application icon)
  - `Glide.App.runtimeconfig.json` (.NET runtime configuration)
  - `QRCoder-LICENSE.txt` (License attribution)
  - `LICENSE.BoringSSL.txt` (BoringSSL/SPAKE2 license attribution)

### 5.2. Shortcut Management
- **Start Menu Shortcut:**  
  `[ProgramMenuFolder]\Glide\Glide.lnk` -> points to `[INSTALLFOLDER]Glide.exe`.  
  On uninstall, `[ProgramMenuFolder]\Glide` directory is removed via `<RemoveFolder>`.
- **Desktop Shortcut:**  
  `[DesktopFolder]\Glide.lnk` -> points to `[INSTALLFOLDER]Glide.exe`.  
  Removed automatically on package removal.

### 5.3. Registry & Protocol Registration
- **URL Protocol Registration (`HKCR\glide`):**  
  Associates `glide://pair/v1#...` pairing invitation links with `Glide.exe`:
  - `HKCR\glide` -> `URL:Glide Protocol`
  - `HKCR\glide\URL Protocol` -> `""`
  - `HKCR\glide\shell\open\command` -> `"[INSTALLFOLDER]Glide.exe" "%1"`
- **Application Registry Metadata (`HKLM\Software\Glide`):**
  - `InstallPath` = `"[INSTALLFOLDER]"`
  - `Version` = `"[ProductVersion]"`
  - `Installed` = `1`
- **Uninstall Behavior:**  
  All registry keys and values are completely purged during uninstallation.
- **User Data Isolation:**  
  Per-user runtime configuration and quarantine staging (`%LOCALAPPDATA%\Glide`) are created dynamically at runtime by unprivileged user processes, not by the MSI installer. Per Windows Installer best practices, user data is isolated from machine binaries and protected from accidental loss during machine-wide updates.

---

## 6. Elevation & UAC Considerations

### 6.1. Store Installation Context
In the Microsoft Store unpackaged Win32 execution model:
- The installation process runs under the **NT AUTHORITY\SYSTEM** context via the Store installation service.
- The installation possesses full administrative rights to write to `ProgramFiles64Folder` and `HKLM`.
- Because the install runs under SYSTEM, no UAC elevation dialog appears to the user, satisfying the silent install requirement.

### 6.2. Local Development & Automated CI Testing
During local developer testing or CI runs on non-elevated runners:
- Attempting to silently install a per-machine package to `C:\Program Files` without elevation returns `1603` because `/qn` suppresses the elevation prompt.
- To enable robust automated verification without requiring interactive elevation:
  - The verification runner (`desktop/windows/verify-windows-msi.ps1`) automatically detects administrator elevation.
  - When elevated, it tests full per-machine installation into `ProgramFiles64Folder`.
  - When non-elevated, it verifies the MSI using Windows Installer per-user testing parameters:  
    `ALLUSERS=2 MSIINSTALLPERUSER=1 INSTALLFOLDER="<user-test-dir>"`.
  - This guarantees that both enterprise per-machine installation and continuous integration environments can thoroughly test the exact same MSI binary.

---

## 7. Add/Remove Programs (ARP) Metadata

The MSI package registers a single, clean entry in Windows **Installed Apps** (Add/Remove Programs):

| Property | Value | Purpose |
| :--- | :--- | :--- |
| `ARPPRODUCTICON` | `AppIcon` (`glide.ico`) | High-resolution icon in Windows Settings. |
| `ARPNOREPAIR` | `1` | Disables ineffective repair button; clean reinstall is supported path. |
| `ARPNOMODIFY` | `1` | Disables modify/maintenance UI. |
| `ARPHELPLINK` | `https://github.com/GDJ2001/Glide` | Direct help link to GitHub repository. |
| `ARPURLINFOABOUT`| `https://github.com/GDJ2001/Glide` | About information link. |
| `Manufacturer` | `Glide` | Publisher display name in Apps list. |
| `ProductVersion`| `1.0.0` | Exact version string matching binary release. |

---

## 8. UpgradeCode GUID Stability & Lifecycle

Windows Installer uses the `UpgradeCode` GUID to identify related family versions of the product across updates.

### 8.1. Stable UpgradeCode GUID
```
88597E1C-6246-4A51-B833-311D79E78C20
```
- **Rule:** This GUID must NEVER change across any future Glide release. Changing this GUID breaks automated in-place upgrades and causes side-by-side duplicate installations.
- **ProductCode:** In WiX v5, `<Package>` generates a new dynamic `ProductCode` GUID for each build, which is the required practice for `<MajorUpgrade>`.

### 8.2. MajorUpgrade Scheduling
```xml
<MajorUpgrade DowngradeErrorMessage="A newer version of Glide is already installed."
              AllowSameVersionUpgrades="no"
              Schedule="afterInstallExecute" />
```
- `Schedule="afterInstallExecute"`: Ensures that if an upgrade fails during execution, the existing operational version is rolled back safely.
- Automatic removal of prior versions: when a newer version is deployed via Store or msiexec, the previous version is cleanly uninstalled before the new version is finalized.

---

## 9. Authenticode Digital Code Signing Requirements

### 9.1. Store Certification Signing Mandate
- Microsoft Store certification requires all executables and installer packages (`.msi` and `Glide.exe`) to be digitally signed with an Authenticode certificate issued by a trusted Certificate Authority in the **Microsoft Trusted Root Program**.
- Self-signed certificates are rejected during Store ingestion.
- The signature must use SHA-256 with an RFC 3161 compliant timestamp server.

### 9.2. Repository Secret Isolation
- In accordance with Glide repository security guardrails:
  - Private code signing certificates and credentials are NEVER committed to the Git repository.
  - Local development and test builds produce packages with `signing_status=unsigned_development`.
  - The production code signing step is executed exclusively in an isolated, authenticated CI/CD release pipeline using Hardware Security Module (HSM) or Azure Key Vault / Trusted Signing before submission to Partner Center.

### 9.3. Signing Command Specification (Production Gate)
```powershell
signtool.exe sign `
    /tr http://timestamp.digicert.com `
    /td sha256 `
    /fd sha256 `
    /d "Glide Local File Sharing" `
    /du "https://github.com/GDJ2001/Glide" `
    /v "build\distributions\windows\Glide-Setup-1.0.0-win-x64.msi"
```

---

## 10. Partner Center Submission Steps

### Step 1: Partner Center Account & App Reservation
1. Log in to [Microsoft Partner Center](https://partner.microsoft.com/dashboard).
2. Navigate to **Apps and games** -> **New product** -> select **Win32 or PWA app**.
3. Reserve the application name: **Glide**.

### Step 2: Product Identity & Properties
1. Set Category to **Productivity** / **File Management**.
2. Select target architecture: **x64**.
3. Specify Minimum OS Version: **Windows 10 Version 2004 (Build 19041)** or Windows 11.

### Step 3: Package Configuration (Direct URL Model)
1. In the **Packages** configuration section:
   - Package Type: Select **Win32 application (.exe or .msi)**.
   - Installer URL: Enter the immutable GitHub release URL:  
     `https://github.com/GDJ2001/Glide/releases/download/v1.0.0/Glide-Setup-1.0.0-win-x64.msi`
   - Installer Architecture: Select **x64**.
   - Silent Install Command Line: Leave default or specify `/qn /norestart`.
   - Silent Uninstall Command Line: Leave default (`msiexec /x [ProductCode] /qn /norestart`).
2. Click **Verify Installer URL**; Partner Center checks availability and downloads the package for automated scanning.

### Step 4: Store Listings & Visual Assets
1. **App Title:** `Glide — Fast Local Share`
2. **Short Description:** Fast, zero-cloud peer-to-peer file transfer over local Wi-Fi and Ethernet.
3. **App Icon:** Provide 512x512 PNG icon derived from `glide.ico`.
4. **Screenshots:** Minimum 4 high-resolution 1920x1080 / 1280x720 PNG screenshots showing:
   - Primary dark-themed transfer window.
   - QR code pairing invitation.
   - High-speed active transfer progress.
   - Send/Receive context integration.
5. **Privacy Policy URL:**  
   `https://github.com/GDJ2001/Glide/blob/main/doc/security/PRIVACY_POLICY.md`

### Step 5: Content Declarations & Ratings
1. Age Ratings: Complete the IARC questionnaire (rating: General / All Ages; zero violence, zero user-generated broadcast content).
2. Export Compliance: Disclose standard TLS 1.3 cryptography for local transfer.

### Step 6: Certification & Release Publication
1. Submit the package for certification.
2. Microsoft automated validation executes:
   - Antivirus & antimalware scans.
   - Silent installation and uninstallation verification.
   - Digital signature verification against Microsoft Trusted Root.
   - Policy compliance check (offline capabilities, no bundled adware).
3. Upon certification approval, the listing is published to the Microsoft Store.

---

## 11. Update Lifecycle & Maintenance Workflow

When publishing subsequent versions (e.g., `1.0.1`):
1. Increment version in project and packaging scripts: `$Version = "1.0.1"`.
2. Build the new MSI: `desktop/windows/package-windows-msi.ps1 -Version 1.0.1`.
3. Sign the MSI with production Authenticode certificate.
4. Publish GitHub release tag `v1.0.1` and upload `Glide-Setup-1.0.1-win-x64.msi`.
5. In Partner Center, create a new submission, update the Installer URL to `https://github.com/GDJ2001/Glide/releases/download/v1.0.1/Glide-Setup-1.0.1-win-x64.msi`, and submit.
6. Existing users receive automatic background updates via the Microsoft Store service using Windows Installer MajorUpgrade mechanics.
