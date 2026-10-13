# Glide native screens and user flows

Updated 2026-10-13. This is the product behavior specification for Android, Windows, Linux, macOS, and iOS. [Design system](GLIDE_DESIGN_SYSTEM.md) · [Failure model](../architecture/GLIDE_FAILURE_MODEL.md) · [Pair-only and reconnect contract](../protocol/PROFILE_2_PAIRING_AND_RECONNECT.md). Platform controls should feel native; labels, security decisions, and transfer states should agree.

## Core destinations

| Destination | Primary action and content | States that need a visible result |
| --- | --- | --- |
| Send | Select files/folders, review selection, choose Quick share with QR or a paired device on desktop; Android also offers its supported Nearby flow | Empty, preparing, skipped source, no peer, authenticating, awaiting consent, busy, retryable failure |
| Receive | Turn receiving on/off, show transfer QR/link while available, approve each incoming file offer | Off, ready, expired invitation, incoming offer, insufficient storage, declined, active |
| Activity | Current transfer first; bytes, speed, item, progress, verification, Cancel; verified history below | Active, complete, failed, cancelled, declined, missing historical file |
| Pair devices | Pair **without selecting files** and inspect/revoke saved identities | Discovery, no peers, ready-to-pair expiry, PIN/link entry, both-party approval, verified, failed |
| Settings | Appearance, receive destination, trusted-device details, advanced connection details, about | Invalid destination, changed identity, inaccessible system preference |

Use the existing Glide logo and concise action labels. First-use help belongs next to the relevant action. Do not add a mandatory wizard, duplicate Send and Pair actions, or persistent paragraphs that restate what a control already says.

## Send and receive

1. Select files with the system picker, folder picker where supported, drag/drop on desktop, or Android share ingress. Show count, size, individual removals, and specific skipped-item reasons. A cancelled picker leaves the previous selection intact.
2. Desktop Send offers **Quick share with QR** and **Paired device**. Quick share shows a one-use sender-hosted QR and waits for the recipient to connect; the sender confirms the recipient before the offer can carry file data. Paired device chooses a saved cryptographic identity, reconnects without another invitation, verifies the live identity, and sends an offer. If a paired device is unavailable, preserve files and give a retry or pair action.
3. A receiver can show a one-use transfer QR/link while receiving is on. A desktop sender can use the link when scanning is inconvenient. Discovery identifies availability only; it is never proof of identity. Android may use its supported Nearby transport, but still requires authentication and an explicit offer decision.
4. The receiver reviews sender, file count, total size, and destination context and chooses **Accept** or **Decline**. Trusted identity does not waive this decision. Before asking, Glide checks staging capacity and that the selected destination is writable. The destination is changed in Settings, not on Receive.
5. After consent, open Activity automatically once. Progress uses actual transferred bytes; it does not advance during connection or verification. The user may navigate elsewhere while a compact active-transfer indicator remains available. Cancel is explicit and affects only the current attempt.
6. Show **Transfer complete** only after exact length, independent digest, and receiver commit are confirmed. Keep a readable result card for complete, failed, cancelled, or declined attempts in the current session; only verified files enter persistent history. Offer Open folder for verified received files. Recoverable sender failure retains selected sources; Try again starts a new authenticated attempt and new receiver consent.

## Pair-only and trusted devices

- Desktop Pair devices presents **Find devices** and **Ready to pair** as primary tasks, with QR/link and **Pair by address** as secondary paths. A receiver ready-to-pair window lasts two minutes. Desktop users can compare or enter a six-digit PIN without a camera; both people approve before identities are saved. Three wrong PIN attempts lock out that window. Expiry, cancellation, app close, or network change withdraw readiness.
- Android and iOS can scan or open a pair-only QR/link, review the peer, and approve; they do not need a desktop-style PIN entry page. A pair-only session never opens a file offer or sends file bytes.
- The saved list distinguishes devices by cryptographic identity even when names match. Revocation takes effect on the next connection. A changed identity fails closed and offers an explicit re-pair path.

## Platform integration

- Android `ACTION_SEND`/`ACTION_SEND_MULTIPLE` enters a validated selection preview. Retain granted content access through staging, reject unsupported or unreadable input, and bring an existing active session forward as Busy. A share intent is never automatic authorization to send.
- Windows and Linux keep standard window controls, resizing, Snap/tile behavior, keyboard navigation, focus restoration, and a single listener owner. macOS keeps standard window/Dock behavior. iOS and Android follow their native navigation and background-transfer rules.
- All clients preserve one active batch, accessible consent dialogs, source selection on recoverable failure, and private quarantine until verification. No browser transfer flow exists.

## Recovery copy and accessibility

| Trigger | User-facing action |
| --- | --- |
| Peer unavailable or network lost | Keep the selection; explain the local-network step and offer Try again. |
| Invitation expired or PIN incorrect | Request a new code; never silently reuse or bypass it. |
| Identity changed or trust revoked | Stop before the offer and offer re-pairing with explicit approval. |
| Receiver declined | Show **Transfer declined** without implying corruption; preserve sender selection. |
| Destination read-only/full | Decline before consent when detectable; direct the receiver to Settings to choose a writable folder or free space. |
| Source disappeared or changed | Name the affected item; remove/reselect it before retry. |
| Disconnect or cancellation | Retain verified items, purge unverified staging, and show which items did not finish. |
| Integrity failure | Report failed verification, do not commit or offer Open, and require a new authenticated attempt. |
| Missing completion receipt | Explain that the other device may have a verified copy; avoid a false success claim. |

Keyboard, screen readers, large text, contrast, high-contrast themes, RTL/Unicode filenames, narrow windows, reduced motion, and clear focus must be checked on installed builds. Announce phase changes and meaningful progress intervals without flooding assistive technology. Color alone never conveys trust, success, or failure.
