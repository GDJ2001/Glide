# Screens and user flows
[Design system](GLIDE_DESIGN_SYSTEM.md) · [Failures](../architecture/GLIDE_FAILURE_MODEL.md) · [Protocol](../protocol/GLIDE_PROTOCOL_V1.md).

## Screen inventory
| Destination / surface | Content and actions | Required states |
| --- | --- | --- |
| Home | Glide identity, Send primary, Receive secondary, recent/active summary | First use explanation inline, ready, active, unavailable |
| Selection route | System picker entry, selected file rows/count/total, add/remove, Continue | Empty, preparing unknown sizes, unreadable, oversized |
| Discovery route | “Open Receive on the other device”, peer list, stop/retry | Searching, no results120s, lost peer, permission/radio/GMS blocked |
| Verification dialog/sheet | Both names (untrusted), full digits, Match / Don't match | Waiting, confirmed locally, rejected, timed out |
| Receive readiness route | Visible name,120s discoverability countdown, stop | Advertising, incoming connection, unavailable |
| Offer sheet | Peer label, item list, total size, private destination, Accept/Decline | Metadata loading, validated, insufficient storage, timeout |
| Transfer route | Phase, bytes, item and batch progress, speed/ETA, Cancel | Preparing, sending/receiving, checking, interrupted, partial completion |
| Result on transfer route | Verified count, Open/Export received items, Done; retry unfinished sender items | Complete, partial, failed, cancelled, receipt unconfirmed |
| History list/detail | Sent/received filters, peer/time/status/count, per-item result, open/export received | Empty, missing local file, failed, clear-history confirmation |
| Settings | Device name, appearance, receive storage explanation/manage files, notification privacy, privacy/about/licenses | Validation, system setting disabled, delete confirmation |
| About/privacy/licenses subroutes | Version, local-sharing explanation, SDK diagnostics disclosure, licenses | Accessible long text |

Do not add a full-screen onboarding carousel or a destination per transient transfer phase. Trusted devices, folder transfer and pause controls do not appear in V1. Settings “transfer preferences” initially contains keep-screen-awake off, notification privacy on and storage explanation; no unsupported transport selector.

## Send
1. Home Send → system document/Photo Picker → selection. Picker cancellation returns safely.
2. Validate accessible sources, stage unknown/transient ones, show preparation. Do not discover while a long cloud source prepares.
3. Continue → nearby explanation/permissions at point of use → discovery. Select peer once; disable repeat connect.
4. Both devices compare SDK digits. Match on both is required. Back or Don't match rejects.
5. Send validated offer; sender sees “Waiting for acceptance”. Receiver separately inspects and accepts.
6. Sequential stream with visible current item and overall bytes. At bytes complete show “Checking file”; completion requires receipt.
7. Sender success provides Done; receiver Open/Export. No automatic file opening or app install.
8. On partial failure show verified items retained, unattempted/failed items distinct; retry starts a new authenticated session and offer for unfinished items.

## Receive
Home Receive explains nearby visibility, grants requested if needed, advertises for120s. Auto-stop if user leaves readiness before an active link. Incoming connection opens verification only while visible; locked/background receiver never auto-confirms. After matching, show offer sheet with scrollable names/types/count/total and “Saved in Glide; export to keep outside app”. Accept only after complete validation and space check. Decline allocates no destination. Active accepted transfer may continue under service when backgrounded.

## Android Share Sheet
ACTION_SEND/SEND_MULTIPLE opens Glide's validated ingress, not an instant send. Restrict to granted content URIs, cap count/metadata, deduplicate identical URI references within request, preserve intent grant lifetime during staging. Bring an existing active session to foreground and show Busy; do not replace it. Preview selection then same discover/verify/offer path. Forged or unreadable inputs show safe error; never fetch arbitrary HTTP/file URLs. Sending text is outside V1; explain unsupported share type.

## Errors and recovery
| Trigger | UX and next action |
| --- | --- |
| Permission denied/permanent | Inline blocked discovery with reason and explicit retry/Settings; no startup prompt loop |
| Wi-Fi/Bluetooth disabled | Named connectivity state, system settings panel; no forced toggle |
| Peer disappears | Remove/disable stale row without shifting focused selection; rediscover |
| Authentication mismatch | Close attempt, “Codes don't match”; no override bypass |
| Receiver rejects | Calm declined result, preserve source selection; no automatic reoffer |
| Connection/offer timeout | State-specific explanation and manual retry |
| Insufficient storage | Required/free space, decline disabled Accept, free-space instructions |
| Source deleted/revoked | Mark item unavailable; reselect/remove; no broad storage request |
| Network/process interruption | Verified count retained; unfinished require retry, no resume label |
| Integrity mismatch | Explicit failed check, partial deleted, no Open; fresh retry only |
| User cancellation | Confirmation during active bytes: “Stop transfer?”; list already received files retained |
| Unsupported protocol | Update guidance; no unsafe downgrade |
| Export fails / no viewer | Receive remains verified; offer another export/viewer, never mark whole transfer corrupt |
| Receipt missing | Explain other device may have file; retry warns about duplicates |

## Navigation/lifecycle contracts
Back from selection/discovery returns without sharing. Back during active transfer navigates Home with active summary; it does not silently cancel. Cancel is a separate labeled action. Back from unconfirmed pairing/offer declines. Predictive back previews the same destination/decision; commit destructive effect only on completed gesture. Notification tap restores current session route; cancel action targets exact session. Rotation/resize never duplicates dialogs or acceptance. Incoming UI never steals focus from another active batch.

Every route needs loading/empty/failure/success representations, keyboard focus restoration, screen-reader labels, large-text wrapping and stable item keys. Attach light/dark/dynamic/adaptive screenshots to each UI task's evidence.

