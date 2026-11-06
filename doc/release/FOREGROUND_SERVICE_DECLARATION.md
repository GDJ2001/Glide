# Google Play Foreground Service (FGS) Declaration — Glide

**Application Name:** Glide  
**Target SDK:** Android 17 (API 37)  
**Foreground Service Class:** `dev.glide.app.service.TransferService`  
**Declared FGS Type:** `connectedDevice` (`android:foregroundServiceType="connectedDevice"`)  
**Document Version:** 1.0.0 (GLIDE-061)  

---

## 1. Foreground Service Overview & Justification

Under Google Play's [Foreground Service Policy](https://support.google.com/googleplay/android-developer/answer/13392821), applications declaring foreground services must demonstrate that the service is user-initiated, noticeable, and essential for ongoing functionality that cannot be performed asynchronously.

### 1.1 Core Functionality Description
Glide utilizes `TransferService` with type `connectedDevice` strictly to maintain active, high-bandwidth peer-to-peer Wi-Fi Direct and Bluetooth sockets between two physically connected Android devices during file streaming.

### 1.2 Why Background Execution (e.g., WorkManager) Is Not Viable
1. **Active Socket Lifetime:** Peer-to-peer Wi-Fi Direct operates stateful TCP sockets directly between devices. If the operating system suspends or pauses the process when the user switches apps or locks the screen, the underlying socket drops, immediately aborting multi-gigabyte file transfers.
2. **Real-Time Synchronous Streaming:** File transfers are user-initiated interactive sessions requiring immediate bandwidth allocation ($30+\text{ MB/s}$) and live SHA-256 cryptographic verification. Transfers cannot be deferred or queued for arbitrary background execution windows.
3. **Peer Group Ownership:** In Wi-Fi Direct connections, one device acts as the Group Owner (GO). Process termination destroys the Wi-Fi Direct group, disconnecting both devices.

---

## 2. User Noticeability & Interactive Controls

1. **Persistent Notification:**
   While `TransferService` is active, Glide displays an ongoing, high-priority notification:
   - **Title:** "Sending files..." or "Receiving files..."
   - **Body:** Displays current item name (e.g., `IMG_2026.heic (3 of 12)`) and live byte counter (e.g., `450 MB / 1.2 GB • 37%`).
   - **Visual Progress Bar:** Dynamic progress bar reflecting exact streaming progress.
2. **Immediate User Cancellation:**
   The notification includes a prominent, accessible **"Cancel"** action button. Tapping "Cancel" terminates the transfer immediately, closes all network sockets, unlinks partial quarantine files, and halts the foreground service.
3. **Strict Lifetime Bounding:**
   - The service is started **only** after the user explicitly accepts an incoming transfer offer or taps "Send".
   - The service calls `stopForeground(true)` and `stopSelf()` **immediately** upon transfer completion, cancellation, or failure. It never runs continuously or starts on device boot (`RECEIVE_BOOT_COMPLETED` is absent).

---

## 3. Google Play Reviewer Demonstration Runbook

Google Play app reviewers can verify compliance with the following steps:

1. **Setup:**
   - Install Glide on two Android devices (Device A and Device B) with Wi-Fi and Bluetooth enabled.
2. **Initiate Transfer:**
   - On Device A: Select 1 or more files and tap **"Find Nearby"**.
   - On Device B: Tap **"Receive"**.
   - Select Device B from the discovery radar on Device A.
   - Confirm the 6-digit SAS pairing code and accept the transfer prompt on Device B.
3. **Observe Active Foreground Service:**
   - Once transfer begins, swipe down the Android notification shade.
   - Verify the ongoing notification: **"Glide — Transferring..."** displaying progress percentage and current byte count.
4. **Multitasking & Screen Off:**
   - Press the **Home** button on Device A or Device B to send Glide to the background, or lock the screen.
   - Observe that the transfer continues uninterrupted at full speed via the active notification.
5. **Verify Clean Termination:**
   - Allow the transfer to finish, or tap **"Cancel"** directly on the notification.
   - Verify that the notification disappears immediately and the background process ceases network operations.

---

## 4. Google Play Console Video Proof Recording Guide

Google Play requires a video demonstration link demonstrating the user-facing foreground service in action:
- **Duration:** 30–60 seconds.
- **Hosting:** Upload as an **Unlisted YouTube video** or public Google Drive link (*Anyone with the link can view*).
- **Video Flow:**
  1. **(0:00–0:15) Transfer Initiation:** Show Device A selecting files and discovering Device B over local Nearby / Wi-Fi Direct.
  2. **(0:15–0:25) Mutual Consent:** Show mutual 6-digit SAS verification and acceptance of incoming files.
  3. **(0:25–0:40) Background & Notification Noticeability:** Press the device Home button or turn screen off. Pull down the notification shade to clearly display the active "Transferring files..." progress notification with live progress bar and "Cancel" button.
  4. **(0:40–0:55) Clean Dismissal:** Show transfer completing successfully with SHA-256 integrity verification, showing the notification cleanly dismissing immediately.
