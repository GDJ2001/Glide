# Planning sources

All sources checked live on2026-09-12. Official documentation/repositories/registry metadata only used for technical conclusions; community search hits were not decision evidence. This is a research snapshot, not an installed lockfile. Release dates are reported only where directly established; date checked is not release date. Revalidate at foundation and release gates.

| ID | Source / subject | Date checked | Relevant conclusion | Reference |
| --- | --- | --- | --- | --- |
| S01 | AndroidX release index | 2026-09-12 | Stable library baseline | [Official source](https://developer.android.com/jetpack/androidx/versions) |
| S02 | AGP9.4 release/compatibility | 2026-09-12 | 9.4.0, Gradle9.6.0, JDK17, API37 | [Official source](https://developer.android.com/build/releases/agp-9-4-0-release-notes) |
| S03 | AGP9.3 release notes | 2026-09-12 | 9.3.2 fallback only after reproduced incompatibility | [Official source](https://developer.android.com/build/releases/agp-9-3-0-release-notes) |
| S04 | Built-in Kotlin migration | 2026-09-12 | AGP9 Android modules use built-in Kotlin; avoid old plugin recipe | [Official source](https://developer.android.com/build/migrate-to-built-in-kotlin) |
| S05 | Kotlin2.4.20 | 2026-09-12 | Stable compiler; Gradle compatibility through9.7 | [Official source](https://kotlinlang.org/docs/whatsnew2420.html) |
| S06 | Kotlin releases | 2026-09-12 | Release dates/stability checked;2.4.20 September2026 | [Official source](https://kotlinlang.org/docs/releases.html) |
| S07 | Compose BOM guide | 2026-09-12 | Use BOM alignment, not independent Compose artifact guesses | [Official source](https://developer.android.com/develop/ui/compose/bom) |
| S08 | BOM Google Maven metadata | 2026-09-12 | 2026.09.00 stable; metadata newer than guide example | [Official source](https://dl.google.com/dl/android/maven2/androidx/compose/compose-bom/maven-metadata.xml) |
| S09 | BOM2026.09.00 actual POM | 2026-09-12 | UI/runtime/foundation1.12.1; Material3 1.4.0 | [Official source](https://dl.google.com/dl/android/maven2/androidx/compose/compose-bom/2026.09.00/compose-bom-2026.09.00.pom) |
| S10 | AGP Google Maven metadata | 2026-09-12 | Stable9.4.0 publication confirmed | [Official source](https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/maven-metadata.xml) |
| S11 | Nearby Google Maven metadata | 2026-09-12 | Stable19.5.0, metadata updated2026-08-24 | [Official source](https://dl.google.com/dl/android/maven2/com/google/android/gms/play-services-nearby/maven-metadata.xml) |
| S12 | Coroutines official release | 2026-09-12 | 1.11.0 stable, May2026 | [Official source](https://github.com/Kotlin/kotlinx.coroutines/releases/tag/1.11.0) |
| S13 | Serialization Maven metadata | 2026-09-12 | 1.11.0 stable;1.12.0-RC excluded | [Official source](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-serialization-json/maven-metadata.xml) |
| S14 | Dagger official release | 2026-09-12 | 2.60.1 stable, July2026 | [Official source](https://github.com/google/dagger/releases/tag/dagger-2.60.1) |
| S15 | KSP official release | 2026-09-12 | 2.3.12 stable, September2026 | [Official source](https://github.com/google/ksp/releases/tag/2.3.12) |
| S16 | Coil official release | 2026-09-12 | Optional3.6.2 stable, September2026 | [Official source](https://github.com/coil-kt/coil/releases/tag/3.6.2) |
| S17 | Room3 release | 2026-09-12 | Stable3.0.3 evaluated; KMP/package transition not required for Android-only persistence | [Official source](https://developer.android.com/jetpack/androidx/releases/room3) |
| S18 | Android17 overview | 2026-09-12 | Current platform/API37 planning baseline | [Official source](https://developer.android.com/about/versions/17) |
| S19 | Android17 target behavior | 2026-09-12 | Target37 behavior must be tested | [Official source](https://developer.android.com/about/versions/17/behavior-changes-17) |
| S20 | Local-network permission | 2026-09-12 | API37 target local access runtime gating, API36 opt-in context | [Official source](https://developer.android.com/privacy-and-security/local-network-permission) |
| S21 | Nearby overview | 2026-09-12 | Offline encrypted peer exchange; BYTES/FILE/STREAM; SDK diagnostics disclosure | [Official source](https://developers.google.com/nearby/connections/overview) |
| S22 | Nearby strategies | 2026-09-12 | P2P one-to-one throughput strategy | [Official source](https://developers.google.com/nearby/connections/strategies) |
| S23 | Nearby get started | 2026-09-12 | SDK permission requirements; sample/platform API32 discrepancy documented | [Official source](https://developers.google.com/nearby/connections/android/get-started) |
| S24 | Nearby connection management | 2026-09-12 | Both users authenticate/accept; reject mismatch | [Official source](https://developers.google.com/nearby/connections/android/manage-connections) |
| S25 | Nearby data exchange | 2026-09-12 | Payload callback ordering and transport completion; stream/file tradeoff | [Official source](https://developers.google.com/nearby/connections/android/exchange-data) |
| S26 | Nearby Payload API | 2026-09-12 | Stream input/descriptor lifecycle API | [Official source](https://developers.google.com/android/reference/com/google/android/gms/nearby/connection/Payload) |
| S27 | Wi-Fi Direct | 2026-09-12 | P2P group/permission complexity | [Official source](https://developer.android.com/develop/connectivity/wifi/wifi-direct) |
| S28 | Wi-Fi Aware | 2026-09-12 | API/hardware availability limits | [Official source](https://developer.android.com/develop/connectivity/wifi/wifi-aware) |
| S29 | NSD | 2026-09-12 | Future LAN discovery, not authentication | [Official source](https://developer.android.com/develop/connectivity/wifi/use-nsd) |
| S30 | BLE | 2026-09-12 | Discovery/low-energy context, not selected bulk path | [Official source](https://developer.android.com/develop/connectivity/bluetooth/ble/ble-overview) |
| S31 | Wi-Fi permissions | 2026-09-12 | NEARBY_WIFI_DEVICES API33; location compatibility through32 | [Official source](https://developer.android.com/develop/connectivity/wifi/wifi-permissions) |
| S32 | Bluetooth permissions | 2026-09-12 | API31 runtime model and neverForLocation implications | [Official source](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions) |
| S33 | Notification permission | 2026-09-12 | API33 denial and FGS visibility behavior | [Official source](https://developer.android.com/develop/ui/compose/notifications/notification-permission) |
| S34 | Foreground service types | 2026-09-12 | connectedDevice type/prerequisites for live external-device interaction | [Official source](https://developer.android.com/develop/background-work/services/fgs/service-types) |
| S35 | Foreground timeouts | 2026-09-12 | dataSync limits; cannot misuse types to evade policy | [Official source](https://developer.android.com/develop/background-work/services/fgs/timeout) |
| S36 | Transfer execution choices | 2026-09-12 | Select execution model for actual work | [Official source](https://developer.android.com/develop/background-work/background-tasks/data-transfer-options) |
| S37 | User-initiated data transfer jobs | 2026-09-12 | Evaluated alternative; does not replace interactive peer session model | [Official source](https://developer.android.com/develop/background-work/background-tasks/uidt) |
| S38 | SAF documents | 2026-09-12 | User-granted URIs, persistable grants and restricted trees | [Official source](https://developer.android.com/training/data-storage/shared/documents-files) |
| S39 | MediaStore | 2026-09-12 | Explicit owned pending Downloads export design | [Official source](https://developer.android.com/training/data-storage/shared/media) |
| S40 | Secure file sharing | 2026-09-12 | Content URI/FileProvider grants instead of file paths | [Official source](https://developer.android.com/training/secure-file-sharing) |
| S41 | Android Keystore | 2026-09-12 | Future persistent nonextractable key storage, not custom identity scheme | [Official source](https://developer.android.com/privacy-and-security/keystore) |
| S42 | Android backup | 2026-09-12 | Explicit exclusions and version-specific extraction behavior | [Official source](https://developer.android.com/identity/data/autobackup) |
| S43 | NIST SHA standard | 2026-09-12 | Standard SHA256, no invented hash | [Official source](https://csrc.nist.gov/pubs/fips/180-4/upd1/final) |
| S44 | Adaptive apps | 2026-09-12 | Window-driven shared layouts | [Official source](https://developer.android.com/develop/adaptive-apps/guides/get-started-with-adaptive-apps) |
| S45 | Compose accessibility | 2026-09-12 | Semantics, scalable content and traversal | [Official source](https://developer.android.com/develop/ui/compose/accessibility) |
| S46 | Android benchmarking | 2026-09-12 | Macrobenchmark/performance measurement approach | [Official source](https://developer.android.com/topic/performance/benchmarking/benchmarking-overview) |
| S47 | Baseline Profile creation | 2026-09-12 | Generate/test packaged profile in release-like build | [Official source](https://developer.android.com/topic/performance/baselineprofiles/create-baselineprofile) |
| S48 | Play target policy | 2026-09-12 | Phone/tablet minimum36 from2026-08-31; chosen target37 | [Official source](https://support.google.com/googleplay/android-developer/answer/11926878) |
| S49 | Play closed-testing policy | 2026-09-12 | Qualifying personal accounts12 testers14 continuous days; recheck Console | [Official source](https://support.google.com/googleplay/android-developer/answer/14151465) |
| S50 | Play FGS declarations | 2026-09-12 | connectedDevice use/declaration evidence | [Official source](https://support.google.com/googleplay/android-developer/answer/13392821) |
| S51 | App signing | 2026-09-12 | Play signing/upload-key separation | [Official source](https://developer.android.com/studio/publish/app-signing) |
| S52 | Bundle upload | 2026-09-12 | AAB/Play artifact process | [Official source](https://developer.android.com/studio/publish/upload-bundle) |
| S53 | GitHub CodeQL compiled languages | 2026-09-12 | Kotlin needs build-aware analysis | [Official source](https://docs.github.com/en/code-security/concepts/code-scanning/codeql/codeql-for-compiled-languages) |

## Interpretation and conflicts
Google Maven metadata and the actual BOM POM resolved newer stable versions than prose examples. The Nearby sample's API32 Wi-Fi permission boundary conflicts with the platform API33 introduction; the permission matrix records the conservative platform-based decision and mandatory device validation. Old sample activity-result/storage code is not permission to use deprecated APIs. Stable Room3 was evaluated but maintained Room2 selected for this Android-only database. Performance targets, stream limits, module count and UI tokens are Glide design decisions, not values asserted by these sources.

[Technology selection](TECHNOLOGY_RESEARCH.md) · [transport research](TRANSPORT_RESEARCH.md) · [repository audit](REPOSITORY_AUDIT.md).

