# Google Play Data Safety Declaration — Glide

**Application Name:** Glide  
**Target Submission:** Google Play Console  
**Standard Version:** 2026 Revision  
**Document Status:** Approved for Release Preparation (GLIDE-061)  

---

## 1. Executive Data Safety Summary

Google Play's Data Safety section requires developers to disclose how their application collects, shares, and protects user data. 

Because Glide operates exclusively as an **offline, peer-to-peer file sharing utility**:
- **Data Collection:** **No user data is collected by the developer or transmitted to external servers.**
- **Data Sharing:** **No user data is shared with third parties or data brokers.**
- **In-Transit Encryption:** All peer-to-peer data streams between devices are encrypted using **TLS/DTLS** via Google Nearby Connections.
- **Account Requirement:** Glide does not use or support user accounts.
- **Data Deletion:** Users can purge all received files, staging partials, and transfer history at any time directly within the application settings.

---

## 2. Play Console Questionnaire Answers

### 2.1 Overview Section
| Question | Response | Developer Justification |
|---|:---:|---|
| **Does your app collect or share any of the required user data types?** | **No** | Files and metadata are transferred directly between peer devices over local Wi-Fi Direct and Bluetooth. No data is collected, stored, or processed by the developer or any external server. |
| **Is all user data handled by your app encrypted in transit?** | **Yes** | All local peer-to-peer transmissions use authenticated TLS/DTLS cryptographic tunnels provided by the Nearby Connections protocol. |
| **Do you provide a way for users to request data deletion?** | **Yes** | Users have direct, complete control to delete all transfer logs, cached files, and received files inside **Settings > Storage**. |

---

### 2.2 Data Types Breakdown

| Data Category | Specific Data Type | Collected? | Shared? | Explanation |
|---|---|:---:|:---:|---|
| **Location** | Approximate Location | **No** | **No** | Location permissions on Android 12 and below are required by the OS strictly for Wi-Fi scanning. Glide never retrieves, reads, or transmits GPS or network coordinates. |
| | Precise Location | **No** | **No** | Never accessed. |
| **Personal Info** | Name, Email, Address, Phone, Government ID | **No** | **No** | Glide requires no accounts, sign-ins, or identity forms. |
| **Financial Info** | Credit card, bank account, purchase history | **No** | **No** | Glide is 100% free with no payments or purchases. |
| **Health & Fitness** | Health / fitness data | **No** | **No** | Not applicable. |
| **Messages** | Emails, SMS, in-app messages | **No** | **No** | Glide contains no messaging services. |
| **Photos & Videos** | Photos, Videos | **No** | **No** | Files selected by the user are streamed directly from device to device over local radio. They are never ingested, collected, or uploaded to any cloud service. |
| **Audio Files** | Voice recordings, music, audio files | **No** | **No** | Transferred peer-to-peer only; never collected. |
| **Files & Docs** | Documents, PDFs, archives, files | **No** | **No** | Transferred peer-to-peer only; never collected. |
| **Calendar** | Calendar events | **No** | **No** | Not accessed. |
| **Contacts** | Contacts | **No** | **No** | Not accessed. |
| **App Activity** | Page views, interactions, search history | **No** | **No** | Zero analytics SDKs are bundled. |
| **Web Browsing** | Web browsing history | **No** | **No** | Not accessed. |
| **App Performance** | Crash logs, diagnostics | **No** | **No** | Glide does not integrate Firebase Crashlytics or third-party diagnostic services. Standard OS-level crash reporting is governed by Google Play Vitals. |
| **Device or other IDs** | Device ID, advertising ID | **No** | **No** | Nearby Connections uses ephemeral Bluetooth MAC addresses and advertising tokens for local handshakes; Glide never collects or persists hardware IDs. |

---

## 3. Security Practices Declaration

1. **Encryption in Transit:**
   - Declared as: **Yes**.
   - Technical detail: High-bandwidth Wi-Fi Direct and Bluetooth communication channels enforce authenticated cryptographic handshakes with 4-digit SAS (Short Authentication String) mutual verification.
2. **Independent Security Review:**
   - Security hardening gate closed in **GLIDE-050** using the STRIDE threat model (T01–T18).
3. **Commitment to Play Families Policy:**
   - Glide does not target children specifically, but conforms to all Play Families policy standards by maintaining zero trackers, zero commercial advertisements, and zero unverified external communication.
