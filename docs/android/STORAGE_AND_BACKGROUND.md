# Storage and background execution
[Protocol](../protocol/GLIDE_PROTOCOL_V1.md) · [Permissions](PERMISSION_MATRIX.md) · [Failure model](../architecture/GLIDE_FAILURE_MODEL.md).

## Source selection
Use Activity Result OpenMultipleDocuments with CATEGORY_OPENABLE semantics for arbitrary content, and current system Photo Picker for photo/video convenience. Audio/documents use SAF. Treat content:// as a capability; never resolve a filesystem path from it. Query display name/MIME/size defensively on I/O dispatcher. Check readable stream before offer; null, negative or lying size is not zero.

Known-length readable content can stream directly. Unknown-length/non-repeatable sources are staged into app-private cache with a cancellable64KiB buffer, size limit and free-space reserve before an offer. Stage only one at a time; total selected staging budget is checked before starting. UI shows “Preparing files”, cancellation and bytes when size unknown; no invented percentage. If a cloud provider fetches content, explain that the selected source may require internet even though peer transfer is local. Do not promise offline access to cloud-only documents.

Take persistable URI permission only when the provider offers it and future session use requires it; catch rejection. Share Sheet grants are often transient: stage under the ingress Activity's grant lifetime before handing off long work, especially for nonpersistable sources. Never ask broad storage permission as a fallback. Remove staged sources after terminal session; retry may require reselect.

Direct source bytes can change during reading. V1 promises integrity of bytes actually streamed, not an atomic snapshot of another app's mutable file. Length mismatch/read failure cancels. For a consistent snapshot the source must be staged; offer that future preference only after storage budget review.

## Destination and file lifetime
V1 default is Glide's private received-file library, under internal filesDir, not cacheDir or public Downloads. Receive screen explicitly says “Saved in Glide · export to keep outside the app”. Uninstall removes these files. User can Open or Export verified files. This is a conscious usability/privacy tradeoff; do not change to public unverified Downloads silently.

Use random item-ID filenames for storage; original names only metadata. Private quarantine under noBackupFilesDir/receiving, final files under noBackupFilesDir/received on the same filesystem. Journal stores relative opaque keys, never concatenated remote names. Reject symlinks and directories; use exclusive creation. Safety-normalize display/export names to NFC; replace separators/control/bidi-format characters, trim dangerous trailing dots/spaces, cap UTF-8 length, avoid dot/dotdot and reserved Windows device names for portable export, preserve a safe extension when possible. Fallback “Received file”. Show sanitized preview to user; filenames never prove type.

Preaccept available bytes must exceed sum of unverified offered bytes + max(256MiB, 5% of that sum). Checked arithmetic; no allocation based only on sender claim. Recheck before each file and on write errors; free space may change. One incoming stream only. Do not reserve sparse multi-GB placeholders to pretend space exists.

Write .part, hash incrementally, flush and sync, verify protocol predicate, persist journal COMMITTING including digest, atomic rename within the same private volume to opaque final key, then transactionally record verified item/file catalog and complete journal. Atomicity is file visibility, not a magical joint SQLite/filesystem transaction. On crash, recovery inspects journal and final/part file, recomputes digest if commit uncertain, completes idempotently or deletes unverified data. A durable record cannot claim a missing file.

On startup remove unreferenced .part older24h; active-process owned files excluded. Any session recovered without a live owner is Interrupted and its unverified data cleaned immediately after reconciliation. Deferred WorkManager maintenance can clean abandoned artifacts; app startup must also reconcile so cleanup is not dependent on scheduler timing.

## Export, open and duplicates
Open only verified files with FileProvider content URI, read-only short-lived grant, correct MIME or octet-stream and chooser. No file://, directory-wide provider paths or URI write grants. No auto-open after receive; unknown types show “No app can open this file” with Export. Untrusted images get bounded post-verification thumbnail decoding; no remote HTML execution or archive extraction.

API29+: explicit “Export to Downloads” uses app-owned MediaStore.Downloads entry with IS_PENDING while copying, clears pending only after success; delete failed pending entry. API26–28: ACTION_CREATE_DOCUMENT system destination chooser. “Export elsewhere” uses create-document on all versions. Providers may not offer atomic rename; report export completion separately from verified receive, clean failed output where supported, disclose residual provider partial if deletion fails. Never overwrite without an explicit separate UX; choose “name (n).ext” using exclusive creation/retry, not check-then-write. Original verified private copy survives export failure.

Clearing history removes history records only. “Manage received files” is a separate confirmation with count/size; active files cannot be deleted. Exported copies are outside Glide's deletion authority. Folder/tree transfer is P3: SAF trees can be slow/restricted (including root/Downloads restrictions); needs protocol relative-path extension and symlink/path threat review. V1 accepts files, not folder semantics.

## Streaming and calculations
Initial64KiB read/write buffers, at most4 buffers per active pipeline (<256KiB explicit application copy buffers), bounded pipe/channel capacity2. These are tunable engineering starting points, not optimality claims. GLIDE-051 compares32/64/256KiB without loading whole files. Nearby/internal/provider memory is additional and measured.

No app chunk/retransmit protocol in V1; transport handles delivery. Byte counters checked against offer and updated from actual reads/writes. Speed uses monotonic sample deltas every250ms with EWMA alpha0.2; ignore first1s, show ETA only after3s positive rate and known total. ETA=(remaining bytes)/smoothed rate, rounded to useful units; stalled output says “Waiting for connection”, not infinity. Progress <=99% until verification/commit/receipt; show separate “Checking file” state rather than artificial transfer speed. Zero-byte files use phase text. Cancellation closes descriptors to release backpressure.

SHA-256 via platform MessageDigest; no custom algorithm. SHA-256 is standardized by [NIST](https://csrc.nist.gov/pubs/fips/180-4/upd1/final). CRC is insufficient against deliberate alteration, MD5/SHA1 rejected, BLAKE3 adds dependency/cross-platform integration without a demonstrated V1 need.

## Execution decision
Use connectedDevice FGS only for user-started active external-peer session, launched from visible Send/Receive/connection UI with prerequisites satisfied. Post the required notification immediately within platform deadline; service nonexported, explicit intents, immutable PendingIntents, stable session action IDs. Do not use sticky auto-restart to reconnect after process death.

Source preparation before an active connection is Activity-owned and cancellable when user leaves; no disguised connectedDevice service for offline hashing/export. Explicit export runs foreground UI with visible cancellable progress; background export is deferred future work unless a separately justified dataSync/UIDT design is approved. During a live peer session, short verification/commit completes as session work, then stop service promptly. No indefinitely idle service.

[FGS types](https://developer.android.com/develop/background-work/services/fgs/service-types) classify connected external devices separately from dataSync. [UIDT](https://developer.android.com/develop/background-work/background-tasks/uidt) and [transfer options](https://developer.android.com/develop/background-work/background-tasks/data-transfer-options) were evaluated; they do not simplify an interactive peer connection requiring human consent. WorkManager is for deferrable maintenance, not session continuity. Do not abuse type selection to evade dataSync limits.

| Event | Defined V1 behavior / guarantee |
| --- | --- |
| Background / screen locks after accepted live session | FGS continues best effort; notification/in-app status; no guarantee against OS/OEM kill |
| Receiver waiting/discoverable leaves UI | Stop advertising; no always-on listener |
| Lock before verification or consent | Do not auto-accept; timer may expire; unlock/foreground needed |
| Rotation / resize | Rebind to existing state, no second transfer |
| Wi-Fi/network changes / Bluetooth off | SDK may survive underlying switch; otherwise interruption and explicit retry |
| Battery saver / Doze / thermal | Observe actual outcome, reduce decoration; platform may throttle/stop |
| Swipe recents away | Service may continue on compliant devices; OEM behavior tested, not guaranteed |
| Force-stop / system task-manager stop / process reclaimed | No resume guarantee; journal reconciliation on next launch |
| Notifications denied | Still submit FGS notification, in-app cancel remains; explain reduced drawer visibility |
| Android rejects service start | Safe failure “Keep Glide open and try again”; do not start hidden work |
| Permission revoked | Abort unfinished work, preserve verified files |
| Phone restart | No boot-triggered transfer; recover journal on explicit launch |

See [test strategy](../testing/GLIDE_TEST_STRATEGY.md) for physical-device evidence required before these behaviors are described as verified. Platform guidance: [SAF](https://developer.android.com/training/data-storage/shared/documents-files), [MediaStore](https://developer.android.com/training/data-storage/shared/media), [secure sharing](https://developer.android.com/training/secure-file-sharing).

