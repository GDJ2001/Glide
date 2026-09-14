# Privacy Policy for Glide

**Effective Date:** 2026-09-14  
**Last Updated:** 2026-09-14  

Glide ("the Application") is developed and maintained as a privacy-first, local-only peer-to-peer file sharing utility for Android devices. This Privacy Policy outlines our principles and practices regarding your privacy when using Glide.

---

## 1. Summary of Core Privacy Principles

- **No User Accounts:** You do not need to create an account, register, or provide any personal information to use Glide.
- **Zero Cloud Storage:** Glide does not operate external file servers, databases, or cloud relay services. All file transfers occur directly between the sending and receiving devices over local peer-to-peer radios (Wi-Fi Direct and Bluetooth).
- **Zero Tracking or Advertising:** Glide contains no advertising SDKs, no tracking libraries, and no analytics frameworks.
- **No Data Collection or Monetization:** We do not collect, store, sell, or rent your personal data, transfer history, or file contents to third parties.

---

## 2. Information Handled by Glide

### 2.1 File Content and Metadata
When you select files to send, Glide reads the file name, MIME type, file size, and computes a cryptographic SHA-256 hash strictly for integrity verification. This information is transmitted directly to the proximate peer device over an encrypted local peer-to-peer channel.
- **In Transit:** Data is encrypted end-to-end between peer devices using TLS/DTLS via Google Nearby Connections.
- **On Device:** Received files are placed in an app-private sandbox directory (`/data/user/0/dev.glide.app/files/received/`). They are accessible only within Glide unless you explicitly choose to export them to your device's public Downloads folder.

### 2.2 Device Name and Identifiers
During local discovery, Glide broadcasts a customizable device display name (defaults to your device model, e.g., "Pixel 10") and an ephemeral session ID to nearby devices. This name is visible only to proximate devices searching for a transfer partner and is never transmitted over the internet.

---

## 3. Device Permissions and Why They Are Needed

Glide requests only the minimum permissions necessary to establish local peer-to-peer connectivity:

| Permission | Android Versions | Purpose |
|---|:---:|---|
| **NEARBY_WIFI_DEVICES** | Android 13+ (API 33+) | Discovers and connects to nearby peer devices over Wi-Fi Direct without requesting device location. Declared with `usesPermissionFlags="neverForLocation"`. |
| **BLUETOOTH_SCAN, BLUETOOTH_ADVERTISE, BLUETOOTH_CONNECT** | Android 12+ (API 31+) | Discovers nearby devices and performs initial connection negotiation. Declared with `usesPermissionFlags="neverForLocation"`. |
| **ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION** | Android 12 and below | Required by the Android operating system to scan for Wi-Fi and Bluetooth devices. **Glide never queries, records, or shares your physical GPS location.** |
| **POST_NOTIFICATIONS** | Android 13+ (API 33+) | Displays an ongoing progress notification while a file transfer is actively in flight, allowing you to monitor progress and cancel the transfer if needed. |
| **FOREGROUND_SERVICE (connectedDevice)** | All Supported | Keeps active peer-to-peer Wi-Fi Direct transfer sockets alive when you switch apps or lock the screen. Terminates immediately once transfers finish or are cancelled. |

Glide **does not** request `MANAGE_EXTERNAL_STORAGE`, `READ_MEDIA_IMAGES`, `READ_CONTACTS`, or `RECEIVE_BOOT_COMPLETED`.

---

## 4. Third-Party Services and SDKs

Glide strictly limits external libraries to essential system components:
- **Google Play Services (Nearby Connections):** Powers local Bluetooth Low Energy discovery and Wi-Fi Direct socket negotiation. Google Play Services may collect standard diagnostic metrics in accordance with the [Google Privacy Policy](https://policies.google.com/privacy). Glide does not collect or receive these diagnostics.
- **No Third-Party Analytics:** We do NOT include Google Analytics, Firebase Analytics, Mixpanel, Segment, or Flurry.
- **No Ad Networks:** We do NOT include Google AdMob, Unity Ads, AppLovin, or any other commercial ad network.

---

## 5. User Control and Data Deletion

You have complete control over all data stored locally on your device:
1. **Transfer History:** You can clear individual transfer records or your entire session history at any time in **Settings > Transfer History**.
2. **Received Files:** You can delete all locally received files or individual items directly from **Settings > Storage**.
3. **App Uninstall:** Because received files reside in Glide's app-private storage by default, uninstalling Glide will automatically delete all unexported received files from your device.

---

## 6. Children's Privacy

Glide is a general utility that does not collect any personal information from any user, including children under the age of 13.

---

## 7. Changes to This Privacy Policy

If we make updates to this Privacy Policy, the revised version will be published in the application and in this repository with an updated effective date.

---

## 8. Contact Information

If you have any questions or concerns regarding this Privacy Policy or Glide's privacy practices, please open an issue in the official project repository:
- **Repository:** `https://github.com/GDJ2001/Glide`
