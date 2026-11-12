# Guide coverage and evidence — 2026-11-12

The supplied collection was rechecked after the added Android screenshots: Windows 17, Android 19 files (17 unique hashes), and Linux 16 imported screenshots. The VM reported Ubuntu 26.04.1 LTS and Glide 1.0.0. Every published asset matches its source hash and has no blur or mask. The manifest accounts for all 52 input files; two Android files are exact duplicate captures.

## Illustrated from supplied screenshots

- Windows: Send, Receive, Activity, Pair devices (find, code, link, QR, address, approval), Settings, paired-device selection, enlarged QR, incoming offer, transfer progress, cancellation.
- Linux: Send, Receive, Activity, Pair devices (find, code, link, QR, address, retry, success), Settings, incoming offer, send progress.
- Android: Home, file selection, discovery failures, QR invitation confirmation, sending progress, Nearby verification, incoming offer, completed receive, history, appearance, trusted devices, save location and About.

## Capture or physical-validation gaps

- Android Receive readiness and pair-only approval/result were not supplied. Their controls are described from current app flow; capture them on a connected phone before claiming illustrated or physical validation.
- Some desktop success, failure/retry, trust details, and minimum-size/light-theme states are not supplied as distinct captures. The guide uses the nearest observed screens and text where appropriate.
- The nine ordered platform routes have actor-specific instructions. Screenshots and current source support those instructions, but this documentation pass did not perform nine physical transfers. Physical interoperability remains a separate test gate.
- macOS and iOS target-host and device validation remain unavailable; the guide marks them pending.

The source screenshot collection contains live values. The user explicitly authorized publishing those images without concealment. Users should enter fresh codes and scan their own current QR images when following the guide.
