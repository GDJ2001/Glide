# Technology research and selected baseline
Checked 2026-09-12 using primary sources in [SOURCES](SOURCES.md). Versions below are a planning lock, not an installed or build-proven dependency graph. GLIDE-001 must resolve and compile the combination before feature work; preserve its dependency report. No dynamic versions, snapshots or automatic “latest” upgrades.

## Selected versions
All entries are stable unless stated otherwise. AndroidX values were checked against the [release index](https://developer.android.com/jetpack/androidx/versions); Compose BOM was also checked against its actual Google Maven POM.

| Technology | Selected version | Purpose / rationale | Alternative, risk and isolation |
| --- | --- | --- | --- |
| Android SDK | min 26; compile/target 37 | Modern platform baseline with Android 8+ compatibility | Lower min increases old-device maintenance; no market-share percentage assumed |
| JDK | 17 toolchain and Gradle runtime | AGP supported reproducibility | IDE JDK 25 is not build authority |
| AGP / Gradle | 9.4.0 / 9.6.0 | Current stable supported pair | Very recent AGP; build-only compatibility gate, 9.3.2/9.5 fallback only through ADR |
| Kotlin / Compose compiler plugin | 2.4.20 / 2.4.20 | Shared compiler baseline | AGP built-in Kotlin; no org.jetbrains.kotlin.android on Android modules |
| KSP | 2.3.12 | Room/Hilt processing | Match KSP2 processor compatibility in foundation gate |
| Compose BOM | 2026.09.00 | Aligned UI artifacts | Actual POM maps UI/runtime/foundation to 1.12.1 and Material3 to 1.4.0 |
| Material 3 | BOM-managed 1.4.0 | Stable design components | Expressive APIs only when stable in this version; alpha is not required |
| Material adaptive | 1.3.0 | Shared window-aware layouts | UI wrapper isolates API evolution |
| Navigation 3 | 1.1.7 | Compose-owned back stack | 1.2 RC excluded; navigation stays in app shell |
| Activity / Lifecycle | 1.13.0 / 2.11.0 | Results, ViewModel, lifecycle-aware Flow | Platform bridge only |
| Core / Window | 1.19.0 / 1.5.1 | Android helpers / fold posture | Add Window only for posture task |
| Coroutines / serialization JSON | 1.11.0 / 1.11.0 | Cancellation/Flow and protocol codec | serialization 1.12 RC excluded; codec isolated |
| Dagger Hilt / AndroidX Hilt | 2.60.1 / 1.4.0 | Compile-time DI / Android integrations | Manual DI viable smaller alternative; bindings remain at composition roots |
| Room | 2.8.5 | Small Android history/journal | Room3 3.0.3 is also stable, but changes package/KMP focus; no KMP database need in V1 |
| DataStore | 1.2.1 | Small preferences | SharedPreferences rejected for new state |
| WorkManager | 2.11.2 | Deferred orphan maintenance only | Not live peer transfer owner |
| Nearby Connections | play-services-nearby 19.5.0 | Android P2P adapter | GMS requirement; no hidden fallback |
| Coil | 3.6.2, optional P2 | Local bounded thumbnails | No image-loading Glide library, no network loader required |
| Macrobenchmark / Baseline Profile plugin | 1.5.0 | Performance verification | Test-only module |
| ProfileInstaller / UI Automator | 1.4.1 / 2.4.0 | Profile delivery / system UI tests | Test/runtime scopes explicitly separated |

The [AGP 9.4 compatibility table](https://developer.android.com/build/releases/agp-9-4-0-release-notes) supports API 37, Gradle 9.6 and JDK 17. [Kotlin 2.4.20](https://kotlinlang.org/docs/whatsnew2420.html) supports Gradle through 9.7. This does not independently prove all processors work together. Pin the effective Kotlin compiler, standard library and Compose compiler consistently following [built-in Kotlin guidance](https://developer.android.com/build/migrate-to-built-in-kotlin); record dependency insight instead of copying pre-AGP9 plugin recipes.

[Room3](https://developer.android.com/jetpack/androidx/releases/room3) was evaluated. Retaining the maintained Room2 line is a deliberate lower-migration-risk Android-only persistence decision. Database entities do not define the portable protocol. Revisit if Room2 maintenance ends; do not silently mix package families.

## SDK support matrix
| Device API | Supported behavior and verification |
| --- | --- |
| 26–28 | Notification channels, SAF/private storage, legacy Bluetooth discovery permissions; low-memory baseline |
| 29–30 | Scoped storage behavior, modern location gating for nearby discovery; no legacy storage escape hatch |
| 31–32 | Bluetooth runtime permissions, FGS launch restrictions, splash behavior |
| 33 | Nearby Wi-Fi / notifications runtime grants, themed launcher icon |
| 34 | Type-specific FGS permission and background-start checks |
| 35 | Edge-to-edge and current execution limits |
| 36 | Adaptive behavior and local-network restriction opt-in test |
| 37 | Target behavior, local-network runtime permission, large-window/adaptive testing |

Minimum 26 is a product tradeoff: broad older-device coverage, notification channels and manageable lifecycle/storage support. It is not a promise that old OS security patches are current. Required libraries' minimum API and manifest merge must be checked in GLIDE-001. Raise minSdk only with compatibility evidence and a changed ADR. Android 17 is current stable in this research snapshot; compile and target 37 proactively rather than merely meeting the Play minimum.

## Stability and dependency admission
Each addition needs exact coordinates, version, license, size, maintenance status, transitive dependency diff, security review and reason. Android platform APIs handle SAF, cryptographic hashing, notifications and Keystore; no SDK substitutes without need. No backend, HTTP client, WebRTC, Bluetooth wrapper, custom crypto, analytics or crash SDK is required in V1.

Experimental Expressive controls, experimental layout primitives and navigation RCs are excluded. If a later requirement cannot use stable APIs, isolate behind a UI/adapter boundary, identify the precise API and migration plan, add an ADR, and retain a stable fallback. Static-analysis/test adjunct versions not selected here must be primary-source verified in their own task; do not invent compatible pins.

[Transport evaluation](TRANSPORT_RESEARCH.md) · [Permissions](../android/PERMISSION_MATRIX.md) · [ADR index](../adr/README.md) · [Change control](../development/CHANGE_CONTROL.md).

