# Google Play Console Publishing Guide — Glide v1.0.0

This guide provides the complete step-by-step procedure to release **Glide** on the **Google Play Store** via your open Google Play Console workspace (`Developer Account: 7458929157853686023`, `App ID: 4974528153095614274`).

---

## 1. Local Pre-Flight: Build Signed Production Bundle

Before uploading to Play Console, build the release bundle using the dedicated Play release script:

```powershell
# Run from repository root
pwsh -File tools/build-play-release.ps1 -VersionName "1.0.0" -VersionCode 1
```

This script:
1. Prompts securely for your **Production Upload Keystore** credentials (stored safely outside the Git repository at `%USERPROFILE%\.glide-signing\glide-upload-keystore.jks`).
2. Runs architecture and unit test verification gates.
3. Assembles and signs `app-release.aab` with native debug symbols (`ndk.debugSymbolLevel = "FULL"`), 16 KB page-aligned native `.so` files, and R8 obfuscation mapping.
4. Stages the verified bundle and SHA-256 sums in `build/play-store-release/`:
   - `glide-1.0.0-vc1-release.aab`
   - `mapping.txt`
   - `SHA256SUMS.txt`
   - `PLAY_UPLOAD_MANIFEST.json`

---

## 2. Play Console Screen: "Create internal testing release"

On the screen currently open in your browser (`.../releases/1/prepare`):

### 2.1 Understanding "2 of 3 enhancements active"
In the **App bundles** card, click the downward chevron (`v`) on the right:
- **Active Enhancement 1 (Play App Signing):** Active. Google Play protects your app's master signing key in Google's secure cloud infrastructure. You sign bundles with your private Upload Key, and Google re-signs them with the distribution key before serving to users.
- **Active Enhancement 2 (App Bundle Delivery Optimization):** Active. Google generates optimized split APKs tailored to each user's device screen density, CPU architecture (`arm64-v8a`, `armeabi-v7a`, `x86_64`), and language.
- **Inactive Enhancement 3 (Play Integrity / Sideload Protection):** Optional. This prompts you to link the Google Play Integrity API or enable automatic integrity protection. *Recommendation:* You can leave automatic integrity protection off if you plan to also distribute standalone APKs via GitHub releases, or turn it on if you want Play Store install-source enforcement.

### 2.2 Upload the App Bundle
1. Drag and drop `build/play-store-release/glide-1.0.0-vc1-release.aab` into the **Upload app bundles** box (or click **Upload** and browse to it).
2. Wait for the upload progress bar and validation checks to complete.
3. Verify the uploaded bundle card shows:
   - **Version code:** `1`
   - **Version name:** `1.0.0`
   - **API levels:** `26-37`
   - **Target SDK:** `37`
   - **Native platforms:** `arm64-v8a, armeabi-v7a, x86_64`
   - **Native debug symbols:** *Included* (no warning)

### 2.3 Release Details
- **Release name \*:**
  ```text
  1.0.0 (1)
  ```
- **Release notes:**
  Copy and paste the following into the release notes editor:
  ```text
  <en-US>
  Glide 1.0.0 initial release!
  • High-speed peer-to-peer file transfer over Wi-Fi Direct and encrypted local Wi-Fi.
  • 100% offline, zero-cloud architecture with on-the-fly SHA-256 verification.
  • Mutual 6-digit numeric verification and optional QR code pairing.
  • Full Material 3 adaptive design with dynamic theming and tablet support.
  • App-private quarantine staging ensuring only complete, verified files are saved.
  </en-US>
  ```
4. Click **Save as draft** in the bottom right, then click **Next**.
5. On the **Preview and confirm** screen, verify there are **zero errors**. Click **Save** (do not roll out until App Content forms are filled).

---

## 3. Left Sidebar: "Policy and programs" -> "App content"

Complete all mandatory Google Play policy questionnaires in order:

### 3.1 Privacy Policy
- **URL:** `https://gdj2001.github.io/Glide/privacy-policy/`
- Click **Save**.

### 3.2 App Access
- Select: **"All functionality is available without special access"** (Glide requires no login, passwords, or credentials).
- Click **Save**.

### 3.3 Ads
- Select: **"No, my app does not contain ads"**.
- Click **Save**.

### 3.4 Content Rating (IARC Questionnaire)
- Enter contact email.
- Select category: **"Utility, Productivity, Communication, or Other"**.
- Questionnaire answers:
  - Violence: **No**
  - Sexuality: **No**
  - Language: **No**
  - Controlled Substances: **No**
  - Does the app natively allow users to interact or exchange content with other users through voice, text, or sharing photos? -> Select **Yes** -> *Does the app share content publicly?* -> **No** (transfers are strictly local, direct device-to-device with explicit mutual consent).
  - Shares user physical location: **No**.
  - Digital goods purchases: **No**.
- Click **Save** -> **Next** -> **Submit**. This yields an **Everyone / PEGI 3** rating.

### 3.5 Target Audience and Content
- Target age groups: Check **13-15**, **16-17**, and **18 and over**.
- *Do NOT check children under 13* (avoids unnecessary Families Policy restrictions for general utility tools).
- Could the store listing unintentionally appeal to children? -> Select **No**.
- Click **Save**.

### 3.6 News, Financial, Health, and Government Apps
- News app: **No**
- Financial features: **My app doesn't provide any financial features**
- Health features: **My app doesn't provide any health features**
- Government apps: **No**
- Click **Save** on each.

### 3.7 Data Safety Form
Follow the exact declarations documented in `doc/release/DATA_SAFETY_DECLARATION.md`:
1. *Does your app collect or share any of the required user data types?* -> Select **No** (peer-to-peer transfers over local radio are direct between proximate user devices and never collected by the developer).
2. *Is all of the user data handled by your app encrypted in transit?* -> Select **Yes** (TLS 1.3 for LAN; TLS/DTLS for Nearby Connections).
3. *Do you provide a way for users to request that their data is deleted?* -> Select **Yes** (users can delete received files, logs, and trusted pairings at any time in Settings).
4. Click **Save** -> **Submit**.

### 3.8 Advertising ID
- Does your app use advertising ID? -> Select **No** (verified zero `AD_ID` permission in merged manifest).
- Click **Save**.

### 3.9 Foreground Service Permissions (`FOREGROUND_SERVICE_CONNECTED_DEVICE`)
- Select the checkbox for **`Connected device`** (`TransferService`).
- **Use case description:** Paste the following justification:
  ```text
  Glide is an offline, peer-to-peer file transfer utility that transfers files directly between Android devices over Wi-Fi Direct and local network sockets. The connectedDevice foreground service maintains active TCP/Nearby sockets alive when transferring large files so the operating system does not suspend the socket when the user switches apps or turns the screen off. A persistent notification with live byte progress and a prominent Cancel button is shown throughout the transfer, and the service terminates immediately when the transfer finishes or is cancelled.
  ```
- **Video demonstration link:** Provide a 30-60 second unlisted YouTube or public Google Drive link demonstrating file transfer progress in the notification shade (see `doc/release/FOREGROUND_SERVICE_DECLARATION.md` Section 4).
- Click **Save**.

---

## 4. Left Sidebar: "Store presence" -> "Main store listing"

Navigate to **Grow users** -> **Store presence** -> **Main store listing**:

### 4.1 Listing Details
- **App name \*:** `Glide` (or `Glide — Fast Local Share`)
- **Short description \* (74/80 chars):**
  ```text
  Fast, private, offline peer-to-peer file sharing between Android devices.
  ```
- **Full description \*:** Copy the full text from `doc/release/store_assets/STORE_LISTING_METADATA.md`.

### 4.2 Graphics
Upload the validated 24-bit RGB PNG assets from `doc/release/store_assets/` (all images are 24-bit RGB with no alpha channel, strictly compliant with Play Store requirements):
1. **App icon (512x512 px):** Upload `doc/release/store_assets/icon_512.png`.
2. **Feature graphic (1024x500 px):** Upload `doc/release/store_assets/feature_graphic_1024x500.png`.
3. **Phone screenshots (1080x1920 px, 9:16 portrait):** Upload all 6 images from `doc/release/store_assets/screenshots/phone/`:
   - `01_home_discovery.png`
   - `02_file_selection.png`
   - `03_active_transfer.png`
   - `04_receive_consent.png`
   - `05_transfer_history.png`
   - `06_settings_privacy.png`
4. **7-inch tablet screenshots (1920x1200 px, 16:10 landscape):** Upload all 6 images from `doc/release/store_assets/screenshots/tablet_7inch/` (or `screenshots/tablet/`):
   - `01_home_discovery.png`
   - `02_file_selection.png`
   - `03_active_transfer.png`
   - `04_receive_consent.png`
   - `05_transfer_history.png`
   - `06_settings_privacy.png`
5. **10-inch tablet screenshots (2560x1600 px, 16:10 landscape):** Upload all 6 images from `doc/release/store_assets/screenshots/tablet_10inch/`:
   - `01_home_discovery.png`
   - `02_file_selection.png`
   - `03_active_transfer.png`
   - `04_receive_consent.png`
   - `05_transfer_history.png`
   - `06_settings_privacy.png`
6. Click **Save**.

### 4.3 Store Settings
Navigate to **Store presence** -> **Store settings**:
- **App category:** `Tools`
- **Manage tags:** `File sharing`, `Productivity`, `Tools`
- **Contact email:** Enter your developer contact email.
- **Website:** `https://github.com/GDJ2001/Glide`
- Click **Save**.

---

## 5. Roll Out Testing Tracks

### 5.1 Internal Testing Track (Immediate)
1. Go back to **Testing** -> **Internal testing**.
2. Click **Edit release** on your draft `1.0.0 (1)`.
3. Navigate to **Testers** tab:
   - Create an email list (e.g., "Core Testers") and add your Google account email.
   - Copy the **Join on the web** or **Join on Android** invite link.
4. Click **Review release** -> **Start rollout to Internal testing**.
5. Open the invite link on your Android phone, accept the invitation, and install Glide from the Google Play Store!
6. Open Glide, go to **Settings > About Glide**, and verify it displays **`Version 1.0.0 (Build 1)`**.

### 5.2 Closed Testing Track (Required for Personal Accounts)
If your developer account is a personal account created after November 13, 2023, Google requires:
- **At least 12 testers opted in continuously for 14 days** on Closed Testing before applying for production access.
- **Procedure:**
  1. Navigate to **Testing** -> **Closed testing** -> Click **Manage track** on **Alpha**.
  2. Click **Create new release** -> **Add from library** -> Select bundle `1.0.0 (1)` (no rebuild necessary!).
  3. Under **Testers**, invite at least 12 people (friends, colleagues, or community testers).
  4. Ensure all 12 testers click your opt-in link and install the app.
  5. Keep testing active for 14 consecutive days. Monitor crashes and feedback in the Play Console dashboard.
  6. Once the 14-day requirement is met, a button **"Apply for production"** will unlock on your Dashboard.

### 5.3 Production Rollout
1. Navigate to **Release** -> **Production**.
2. Click **Create new release** -> **Add from library** -> Select bundle `1.0.0 (1)`.
3. If this is your first release, rollout will be 100%. For subsequent updates, choose a staged rollout ($10\% \rightarrow 50\% \rightarrow 100\%$).
4. Click **Review release** -> **Start rollout to Production**.
5. Google will review the submission (typically 1–3 business days). Once approved, Glide `1.0.0` will be live worldwide on Google Play!
