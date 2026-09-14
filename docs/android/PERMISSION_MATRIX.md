# Android permission matrix
Baseline: min26, target/compile37. Checked2026-09-12. [Research](../planning/TECHNOLOGY_RESEARCH.md) · [Flows](../design/GLIDE_USER_FLOWS.md) · [Execution](STORAGE_AND_BACKGROUND.md).

Permissions are requested only after Send/Receive intent and a short contextual explanation. Group related nearby grants in one request, but do not chain unrelated notifications/storage dialogs. Home/history/settings work without radio grants. Check each permission immediately before use and after foreground return; group membership is not proof of grant.

| Device API | Permission / declaration | Trigger and reason | Denial / permanent denial / fallback |
| --- | --- | --- | --- |
| 26–30 | BLUETOOTH, BLUETOOTH_ADMIN normal, max30 | Nearby radio operation | Availability error; settings/help, no automatic radio toggling |
| 26–28 | ACCESS_COARSE_LOCATION runtime, max28 | “Android needs location permission to find nearby devices. Glide does not track your location.” | Discovery disabled, history usable; one rationale then Settings if no prompt |
| 29–32 | ACCESS_FINE_LOCATION plus COARSE runtime request where OS requires paired request; max32 | Wi-Fi discovery compatibility; explain old Android requirement | Approximate-only insufficient when fine required; do not retry-loop |
| 26–32 | ACCESS_WIFI_STATE, CHANGE_WIFI_STATE normal | Nearby Wi-Fi compatibility | Inspect merged manifest; radio failure offers system panel |
| 31+ | BLUETOOTH_SCAN, CONNECT, ADVERTISE runtime, action-appropriate set | Find/connect or become visible | Stop discovery/advertising and active work safely on revoke; Settings fallback |
| 33+ | NEARBY_WIFI_DEVICES runtime, neverForLocation | Nearby Wi-Fi transfer | Explain nearby access; no silent low-speed fallback |
| 37+ targeting37 | ACCESS_LOCAL_NETWORK runtime | Nearby WIFI_LAN local-network path before discovery/connect | Recheck even if nearby group previously granted; blocked state, Settings fallback |
| 33+ | POST_NOTIFICATIONS runtime | Before first active transfer: explain background progress/cancel notification | Denial does not prohibit FGS; show in-app progress/cancel and system task-manager behavior |
| 28+ | FOREGROUND_SERVICE normal | User-started ongoing peer session | Handle start failure; remain foreground or fail safely |
| 34+ | FOREGROUND_SERVICE_CONNECTED_DEVICE normal; service type connectedDevice | Live external peer communication; granted Bluetooth prerequisite | Never start before prerequisite/visible user action |
| 26+ | INTERNET / ACCESS_NETWORK_STATE normal as required by SDK/availability | Local sockets/runtime network capability, not cloud account | Inspect transitive manifest; no own external endpoint |
| All supported | SAF/Photo Picker URI grants; no broad storage runtime grant | Explicit user selection/export | Cancel picker returns unchanged selection; revoked URI → reselect |
| All supported | No READ_MEDIA_*, MANAGE_EXTERNAL_STORAGE, WRITE_EXTERNAL_STORAGE, READ_EXTERNAL_STORAGE in selected STREAM design | App-private receive + user-selected sources | No permission expansion to fix a provider error |
| All supported | No background location, contacts, phone, camera, microphone or boot receiver | Not needed | Do not request |
| All supported | WAKE_LOCK only if measured active-transfer need justifies it | Optional bounded non-reference-counted lock owned by service | Avoid by default; release in every terminal path and timeout |

The [Nearby guide](https://developers.google.com/nearby/connections/android/get-started) contains a sample with NEARBY_WIFI_DEVICES starting32 and location ending31. The [platform Wi-Fi guide](https://developer.android.com/develop/connectivity/wifi/wifi-permissions) places the permission at33 and location compatibility through32. This plan uses the platform boundary and conservatively retains old Wi-Fi permissions through32. GLIDE-009 must validate the exact19.5 SDK on31/32/33 and record merged-manifest/runtime evidence; narrow grants only with proof. Do not copy sample minSdkVersion permission attributes or legacy file permission examples blindly; API branching belongs in runtime policy.

[Bluetooth guidance](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions) allows neverForLocation assertions but warns scan filtering can change. Glide never derives location. Verify discovery with that assertion on all proof devices; do not silently remove privacy assertions to bypass a failure. Location services themselves may be required by old OS/vendor discovery; explain separately and offer Settings, without collecting coordinates.

[Android17 local access](https://developer.android.com/privacy-and-security/local-network-permission) is a separate platform requirement from internet connectivity. System NSD picker exemptions are relevant to future LAN transport, not automatically to Nearby. [Notification permission](https://developer.android.com/develop/ui/compose/notifications/notification-permission) denial changes visibility, not authorization to omit the required FGS notification.

## Verification matrix
Fresh install, grant, deny, deny repeatedly, approximate-only, settings revoke, auto-reset, upgrade from older target, radio off, location services off, GMS missing/outdated and API37 local access denied. Test via UI and actual two phones, not only mocked permission values. On revoke mid-transfer: close descriptors, cancel SDK work, persist interruption and retain verified files. Never auto-open Settings or display repeated prompts on launch.

