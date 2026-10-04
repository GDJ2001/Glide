# Glide hands-on UI test: Android, Windows, and Ubuntu VM

This is a **manual application test**, using the visible Glide UI on both ends. It supplements the backend/virtual reports; it does not turn an Ubuntu VirtualBox guest into an independent physical Linux computer. Record Ubuntu 26.x results as **Ubuntu 26 compatibility** because the current qualified Linux baseline in `doc/planning/native-platforms/RECOVERY_STATUS.md` is Ubuntu 24.04. Run a separate 24.04 VM before claiming that baseline from this procedure.

## 1. Name the devices and fix one candidate

| Label | Device | Role |
|---|---|---|
| `W1` | Windows laptop | Primary Windows endpoint; use it for Windows↔Linux if the VM is on the other PC. |
| `W2` | Second Windows PC | Hosts `L1` in VirtualBox, if that is the current setup; also tests Windows↔Windows. |
| `L1` | Ubuntu 26.x VirtualBox VM on `W2` | Linux UI endpoint. Record exact `lsb_release -ds` output. |
| `A1` | Physical Android phone | Android UI, camera scanner, and Share Sheet endpoint. Record model and Android version. |
| `L2` | Future Ubuntu VM on `W1` | Optional second-VM repeat; keep separate from `L1` results. |

If `L1` is actually on `W1`, swap `W1`/`W2` in the run sheet so the main Windows↔Linux test crosses the two physical PCs. Use the **same application candidate** on every endpoint. For the current development artifacts, `build/distributions/RELEASE_MANIFEST.json` identifies candidate `b44d1951d74de6be0f879cbaa31026607ec36039`. Verify each copied installer/APK/DEB against its manifest SHA-256 before installing; do not combine old and new builds. Write the candidate SHA, application version, artifact hashes, OS versions, and host labels at the top of your copy of [the CSV run sheet](MANUAL_UI_TRIAD_RUN_SHEET.csv).

Use the Windows installer on `W1`/`W2`, the **APK** (not the AAB) on `A1`, and the amd64 `.deb` in `L1`. Keep the original package files untouched. Make a distinct empty receive folder for each case, e.g. `Glide-Results/C01`; this prevents old files from appearing as a false pass.

## 2. Prepare the local network and test data once

1. Connect `W1`, `W2`, and `A1` to the same non-guest LAN. Keep Wi-Fi enabled on the phone; do not use cellular or a VPN for the test. If your router isolates Wi-Fi clients, move the devices to a normal local network rather than disabling Glide authentication.
2. In VirtualBox, power off `L1`, open **Settings → Network → Adapter 1**, and select **Bridged Adapter** attached to the host's active Ethernet/Wi-Fi adapter. Boot Ubuntu and confirm it has a LAN-reachable address. VirtualBox's default NAT makes a guest listener unreachable from other LAN devices without forwarding; bridged mode presents the guest to the LAN. [Oracle VirtualBox networking guide](https://docs.oracle.com/en/virtualization/virtualbox/7.2/user/networkingdetails.html)
3. Record the Windows addresses with `ipconfig`, the VM address with `ip -4 addr`, and the phone's Wi-Fi address in Android Settings. A successful ping is helpful but does not prove that Glide's dynamic TCP listener or mDNS works. Allow Glide through the normal **Private network** firewall prompt if it appears; do not turn off the firewall globally.
4. Generate the pack on the laptop: `pwsh -NoProfile -File tools/new-manual-ui-test-pack.ps1`. The output is `build/test-packs/glide-manual-ui-v1/Glide-Manual-UI-Test-Pack-v1.zip` (under 1 MiB because the synthetic patterns compress well; it expands to about 110 MiB). Copy that ZIP by USB or local file copy to `W2`, `L1`, and `A1`, verify the ZIP hash from its companion `.zip.sha256`, and extract it on each endpoint. Copying the fixture by USB is setup; **Glide must perform every scored transfer**.
5. Keep an untouched extracted master on each endpoint. `Quick` has 4 small files (zero byte, Unicode, and spaced name); `FolderTree` has 4 files, including two same-named leaves in different folders; `Batch35` crosses the 32-item offer-page boundary; `Progress` contains 10 MiB and 100 MiB files. No personal data is included. Do not accidentally select `MANIFEST.json`, `SHA256SUMS.txt`, or `START-HERE.txt` as payloads.
6. On Windows, verify the master with `pwsh -NoProfile -File tools/verify-manual-ui-test-pack.ps1 -PackRoot <extracted-pack-folder>`. On Ubuntu, run `sha256sum -c SHA256SUMS.txt` **inside** the extracted pack folder. On Android, use the phone's file manager to confirm the extracted folders and visible names; SHA-256 of received files can be checked later using ADB/USB copy to a PC.

If Bridged Adapter fails on Wi-Fi, note `NETWORK BLOCKED`, test `W1↔W2` and `A1↔W1` first, then retry `L1` on wired Ethernet or another LAN adapter. Do not call a failed mDNS discovery test an authentication failure: when a peer is not listed, use its **fresh QR/link** to determine whether direct authenticated connection works.

## 3. One repeatable procedure for each success row

Use the [CSV run sheet](MANUAL_UI_TRIAD_RUN_SHEET.csv) in order: `C01–C06` first, `C07–C12` next, `C13–C18` after trust enrollment, then `B01–B06`. These are three peer pairs (Windows↔Android, Android↔Linux, Windows↔Linux), both directions, and the three applicable UI sending routes. `W1↔W2`, `W2↔L1` on one host, and later `L1↔L2` are useful supplementary checks, not substitutes for the cross-host core rows.

For every row:

1. Start the **installed application UI** on sender and receiver. Confirm the sender's queue is empty and the case's receive folder is empty. Select only the specified data set. On Windows use **Add files/Add folder**; on Ubuntu use **Add Files/Add Folder**; on Android use its file/folder picker. Confirm item count and total size before connecting.
2. Put the receiver in **Receive/Ready to receive**. For `receiver-link`, display/copy its current one-time invitation, then use the sender's **Nearby device/Pair devices/Paste link** or corresponding Android/Ubuntu pairing control. Nearby discovery only shows availability: the fresh invitation and authentication are still required.
3. For `sender-quick-qr`, choose **Quick share with QR** on the sender. Android can scan a QR displayed by Windows/Ubuntu. A desktop receiver uses its **Connect to sender invitation** field; obtain a current link with the sender's **Copy link** control and convey it locally without publishing it or retaining it in evidence. The sender must approve the recipient and the receiver must approve the offer before data starts. If the desktop has no usable way to read an Android-hosted QR/link, record a UI interoperability blocker rather than claiming the route passed.
4. For `saved-device`, first complete a verified fresh transfer and explicitly enroll trust if offered. Select the saved peer, supply its **new** invitation, and check that the stored identity is verified. A saved name alone must never authorize a transfer or bypass the receiver's offer consent.
5. Read the six-digit verification code on both ends where shown and confirm **only if they match**. Approve the incoming offer. Observe progress on both UIs, navigate to Activity and back, and wait for a terminal state. Success must appear only after verification, not when the socket merely closes.
6. Open the received-file location from Glide. Confirm files are visible and open normally. Record the visible sender/receiver result, item count, date/time, and any warning. Do not include a live QR, invitation URI, token, or full fingerprint in screenshots or logs.
7. Prove bytes, not just the green success label. On Windows, run `pwsh -NoProfile -File tools/verify-manual-ui-test-pack.ps1 -PackRoot <master> -ReceivedDir <case-folder> -Set Quick` (or `Progress10`/`Bulk`). For Ubuntu, copy the received case folder to Windows for this verifier, or calculate each SHA-256 against `MANIFEST.json` with `sha256sum`. For Android, copy the received case folder to a PC by USB/ADB and run the same verifier. If you cannot inspect the destination bytes, mark `UI PASS / HASH BLOCKED`, not full `PASS`.
8. Update the CSV row to `PASS`, `FAIL`, or `BLOCKED`; give the reason and evidence-folder path. Keep screenshots of queue, consent, progress, terminal result, and received folder when relevant, with pairing secrets cropped. One failed case stays failed until a fresh rerun with a new case folder.

For `Bulk`, select all `Batch35` files, the entire `FolderTree` folder, and `Progress/100MiB.bin` in **one batch** (40 payload files total). The verifier checks the hash multiset even if the app adjusts received relative paths; inspect the folder structure and collision handling separately in the UI. A silent overwrite is a failure.

## 4. Failure and recovery rows

Run `N01–N12` after the success cases. Use fresh invitations for every new attempt.

| Route | Manual action | Expected visible and filesystem result |
|---|---|---|
| `decline` | When the receiver shows the offer, press **Decline**. | Sender sees rejection; neither UI says completed; no selected file appears in destination. |
| `cancel-mid-stream` | Send `Progress/10MiB.bin`; press **Cancel** after progress begins. If it finishes too quickly, repeat with `Progress/100MiB.bin`. | Both sides stop; no unverified file is shown as complete; the sender can retry with a fresh invitation. |
| `expired-or-altered-link` | Copy an invitation, wait beyond its displayed expiry (normally 120 seconds), or change one character of the copied link before use. | Clear invalid/expired message; no file bytes authorized. Never change a stored invitation or disable security to force the case. |
| `disconnect-and-retry` | While sending 100 MiB, turn off only the **sender's Wi-Fi** or VM network adapter, then restore it. | Both UIs show interruption, not success; partial data is not visible as a finished file; a new invitation and consent allow retry. |

Also run one **busy** check (attempt a second batch while the first 100 MiB transfer is active), one **trust-revocation** check (remove a saved peer, then require fresh pairing), and one **receiving-off** check on each UI. Record these as additional rows in the CSV. Protocol-level digest tampering and malformed record tests cannot be produced honestly through normal UI controls; retain their automated adversarial results separately.

## 5. What to hand back for review

Send the completed CSV plus one folder per case containing sanitized screenshots, the destination hash-verification output, endpoint OS/build identity, and a short note of exactly what you clicked. For a failure, capture the app's visible error and whether a file appeared. The reviewer can diagnose that evidence without needing every raw log.

The three pairs are complete only when the six baseline directions and relevant QR/saved-device routes work through the UI, the six `Bulk` transfers have matching hashes, and all twelve failure/recovery rows show truthful results. A Linux **VM** result is still VM evidence; it does not clear the separate physical-Linux-host gate. Ubuntu 26.x compatibility does not replace an Ubuntu 24.04 qualification run. A second Ubuntu VM on the laptop improves cross-host VM coverage but remains virtualized Linux.

### Automation option

Most preparation and proof can be automated: package/hash checks, copying the fixture to the two PCs and VM, launching the UI, taking screenshots, and verifying received bytes. Windows UI Automation, Android UIAutomator/ADB, and Avalonia-accessibility/UI automation can drive repeatable controls, with a human watching consent and camera/QR behavior. I can automate those on the laptop/VM once their UI sessions and the phone's USB-debugging connection are accessible; the other PC requires remote access or a local test agent. No automation should silently click **Accept** or claim a visual/physical pass from backend transport logs alone.
