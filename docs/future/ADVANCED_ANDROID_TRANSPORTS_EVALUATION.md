# Advanced Android Transports Evaluation Specification

- **Document Version:** 1.0.0
- **Status:** Approved Future Evaluation Specification
- **Milestone:** M13 — Future work (outside V1)
- **Task Authority:** [GLIDE-071](../TODO_MASTER.md#glide-071)
- **Architectural Decision:** [ADR-0016: Advanced Android transports evaluation](../adr/ADR-0016-advanced-android-transports.md)
- **Related Documents:** [ADR-0007 (Initial Android transport)](../adr/ADR-0007-transport.md), [PERMISSION_MATRIX.md](../android/PERMISSION_MATRIX.md), [GLIDE_THREAT_MODEL.md](../security/GLIDE_THREAT_MODEL.md), [CROSS_PLATFORM_PATH.md](CROSS_PLATFORM_PATH.md).

---

## 1. Executive Summary & Problem Formulation

### 1.1 Context
In Glide V1, peer discovery, link negotiation, and bulk payload transfer are powered by Google Play Services (GMS) Nearby Connections P2P (`Strategy.P2P_POINT_TO_POINT`) with STREAM payloads (ADR-0007). In physical benchmarking (GLIDE-018), STREAM achieved a median throughput of 344.83 MB/s with exact 1.0x disk footprint and synchronous quarantine cleanup upon cancellation.

However, exclusive reliance on GMS Nearby Connections imposes strategic and operational constraints:
1. **GMS Dependency & De-Googled Hardware:** Devices running open-source AOSP, LineageOS, GrapheneOS, CalyxOS, or region-specific non-GMS operating systems (e.g. Huawei HarmonyOS, Chinese domestic ROMs, specialized enterprise Android hardware) lack the Google Play Services framework and cannot use Glide V1.
2. **Opaque Link Negotiation:** GMS Nearby manages Wi-Fi Direct and SoftAP link upgrades inside proprietary binary blobs. When group owner negotiations fail across disparate OEM chipsets, applications receive opaque connection failure codes without recourse.
3. **Cross-Platform Barrier:** Nearby Connections P2P is fundamentally an Android-to-Android technology (with experimental, non-standard desktop ports). It does not provide a standard IP-based transport for interoperability with Windows (GLIDE-072), Linux, or macOS.

### 1.2 Core Invariants
To evaluate advanced Android transports responsibly without compromising the shipping V1 release:
- **Zero Silent Expansion of V1 Permissions:** No new permissions shall be added to the production V1 manifest (`docs/android/PERMISSION_MATRIX.md`). Any non-GMS transport permissions remain strictly segregated to post-V1 modular plugins.
- **Application-Layer Zero-Trust Security:** Link-layer encryption (e.g., WPA2-PSK, WPA3-SAE, or Wi-Fi Aware PMK) does not replace application-layer security. All socket-based transports mandate **TLS 1.3 mutual authentication** bound to Glide's 6-digit SAS or Android Keystore persistent identity (ADR-0015).
- **TransportPort Abstraction Conformance:** Every transport candidate must conform to the Glide `TransportPort` streaming contract: $O(1)$ bounded heap buffers (64 KiB), private quarantine staging, and pipelined single-pass SHA-256 digest computation.
- **Radio Coexistence Hygiene:** The application must never silently terminate an active station (STA) Wi-Fi internet connection without explicit user notification and consent.

---

## 2. Comprehensive Candidate Transport Evaluation Matrix

The following evaluation contrasts five candidate transport mechanisms under standard Android platform conditions:

| Evaluation Dimension | 1. GMS Nearby P2P (V1 Baseline) | 2. Wi-Fi Aware (NAN) | 3. Standalone Wi-Fi Direct (Autonomous GO) | 4. Local-Only Hotspot (`startLocalOnlyHotspot`) | 5. LAN + NSD (mDNS) + TLS 1.3 |
|:---|:---|:---|:---|:---|:---|
| **Underlying API** | `com.google.android.gms.nearby.connection` | `android.net.wifi.aware.WifiAwareManager` | `android.net.wifi.p2p.WifiP2pManager` | `android.net.wifi.WifiManager` | `android.net.nsd.NsdManager` + Sockets |
| **Android API Level** | Min API 26 (GMS client) | Min API 26 (matured API 29+) | Min API 14 (matured API 29+) | Min API 26 (client API 29+) | Min API 16+ (Local network API 37+) |
| **Ecosystem Coverage** | ~85% (GMS devices only) | **< 40%** (Severe HW fragmentation) | **> 95%** of all Android devices | ~90% (SoftAP supported devices) | **100%** of networked devices |
| **Access Point Needed?** | No (ad-hoc P2P) | No (ad-hoc clustering) | No (ad-hoc P2P) | No (device creates SoftAP) | **Yes** (Shared Wi-Fi or Ethernet router) |
| **Discovery Mechanism** | BLE + Bluetooth Classic + mDNS | Wi-Fi Aware Publish/Subscribe | BLE / QR Out-Of-Band Signaling | BLE / QR Out-Of-Band Signaling | Multicast DNS (mDNS) / DNS-SD |
| **Discovery Latency** | 2.5 – 5.0 seconds | 0.8 – 2.0 seconds | 0.5 – 1.5 seconds (BLE/QR) | 0.5 – 1.5 seconds (BLE/QR) | **0.2 – 0.8 seconds** |
| **Connection Setup Time** | 4.0 – 8.0 seconds | 2.0 – 4.0 seconds | 3.0 – 5.0 seconds | 5.0 – 9.0 seconds | **0.5 – 1.2 seconds** |
| **Practical Throughput** | 250 – 380 MB/s (Wi-Fi upgrade) | 120 – 280 MB/s (Channel width dependent) | **300 – 450 MB/s** (Direct 802.11ac/ax) | 200 – 350 MB/s | **300 – 600+ MB/s** (Router dependent) |
| **STA Wi-Fi Preservation** | Handled by OS/GMS | Often drops STA on 1-PHY | Preserved on 2-PHY; drops on 1-PHY | **Forcibly dropped** on 1-PHY devices | **Fully Preserved** (Uses active link) |
| **Cross-Platform Feasibility** | None (Proprietary GMS) | Poor (Linux limited, iOS none) | Poor (Windows/iOS incompatible) | Moderate (Client connects as normal Wi-Fi) | **Native Universal** (Win, Linux, Mac, iOS) |
| **Required Permissions** | `NEARBY_WIFI_DEVICES`, `BLUETOOTH_*` | `NEARBY_WIFI_DEVICES`, `ACCESS_FINE_LOCATION` (API<33) | `NEARBY_WIFI_DEVICES`, `ACCESS_FINE_LOCATION` (API<33) | `NEARBY_WIFI_DEVICES`, `CHANGE_WIFI_STATE` | `ACCESS_LOCAL_NETWORK` (API 37+), `INTERNET` |
| **Architecture Tier** | **Tier 1 (Default Android P2P)** | **Opportunistic Probe** | **Tier 3 (GMS-Free Offline P2P)** | **Emergency Fallback** | **Tier 2 (Cross-Platform & LAN)** |

---

## 3. Detailed Technical Analysis of Candidates

### 3.1 Wi-Fi Aware (Neighbor Awareness Networking - NAN)
Wi-Fi Aware (standardized as Wi-Fi Alliance NAN) enables devices to discover each other and form ad-hoc peer-to-peer data links directly without an intermediary access point or cellular connectivity.

#### Advantages:
1. **Low Discovery Power:** Discovers nearby peers using background synchronized cluster beacons without waking high-power Wi-Fi radios.
2. **Immediate Datapath Initiation:** Once peers match via a publish/subscribe discovery session, an `AwareNetworkSpecifier` can instantiate a direct L2/L3 datapath.
3. **No AP Required:** Works completely off-grid.

#### Disabling Constraints:
1. **Severe Hardware Fragmentation:** Unlike Wi-Fi Direct, Wi-Fi Aware requires dedicated MAC/PHY firmware support (`PackageManager.FEATURE_WIFI_AWARE`). Fleet hardware analysis indicates **less than 40%** of active Android devices have Aware enabled. It is supported on Google Pixel (Pixel 3+) and select Qualcomm Snapdragon flagship/mid-range chipsets (FastConnect 6700+), but is widely disabled or missing in MediaTek Dimensity, Unisoc, and lower-tier OEM devices.
2. **Single-PHY Radio Teardown:** On devices equipped with a single physical Wi-Fi radio (single-PHY), establishing a Wi-Fi Aware data path frequently forces the radio off its home infrastructure channel, terminating active station (STA) Wi-Fi and dropping active internet connections.
3. **OS Evolution Gaps:** Although introduced in API 26 (Android 8.0), socket datapath stability did not mature until API 29 (Android 10), and instant pairing features were only standardized in API 33 (Android 13).
4. **Conclusion:** Wi-Fi Aware cannot serve as a reliable primary or fallback transport. It is designated as an **opportunistic probe**: used only when both sender and receiver explicitly advertise hardware Aware support.

---

### 3.2 Standalone Wi-Fi Direct (P2P) — Autonomous Group Owner Pattern
Wi-Fi Direct (`WifiP2pManager`) has been supported since Android 4.0 (API 14) and is present on virtually all Android smartphones (> 95% fleet coverage).

#### The Standard Negotiation Flaw:
Traditional Wi-Fi Direct implementations rely on two-party Group Owner (GO) negotiation (`connect()` with `WpsInfo.PBC`). In this flow, devices negotiate which peer will act as the soft AP (GO) and which will be the client. In real-world multi-vendor deployments, cross-OEM negotiation (e.g. Samsung vs. Xiaomi vs. OnePlus) exhibits high failure rates (15%–30%) due to WPS timeout races, incompatible DHCP server implementations, and conflicting GO intent values.

#### The Autonomous GO Architecture:
To eliminate negotiation deadlocks, Glide specifies the **Autonomous Group Owner Pattern**:
1. **Role Pre-assignment:** The receiving device autonomously designates itself as the Group Owner via `WifiP2pManager.createGroup()`.
2. **Zero-Handshake Credential Exchange:** The receiver obtains its generated network credentials (randomized SSID, e.g., `DIRECT-xy-Glide-9A7F`, and random WPA2-PSK passphrase) and IP configuration (`192.168.49.1`).
3. **Out-of-Band (OOB) Pairing:** These credentials and the receiver's TLS certificate digest are transmitted to the sender via Bluetooth Low Energy (BLE) advertisement or displayed on-screen as a high-density QR code.
4. **Direct Client Join:** The sender connects directly to the specified SSID and passphrase using `WifiP2pConfig.Builder` or `WifiNetworkSpecifier`.
5. **Outcome:** Connection reliability improves from ~70% (negotiated) to > 98% (autonomous), matching GMS Nearby performance while remaining 100% independent of Google Play Services.
6. **Conclusion:** Selected as **Tier 3 (GMS-Free Offline P2P)**.

---

### 3.3 Local-Only Hotspot (`startLocalOnlyHotspot`)
Introduced in Android 8.0 (API 26), `WifiManager.startLocalOnlyHotspot` allows an application to spawn a temporary Wi-Fi access point without cellular tethering entitlements.

#### Evaluation:
- **Client Join:** On Android 10+ (API 29+), the client joins programmatically via `ConnectivityManager.requestNetwork` with `WifiNetworkSpecifier`.
- **User Friction:** On many Android OEM versions, starting a Local-Only Hotspot triggers system-level dialogs warning the user that Wi-Fi sharing is active.
- **Radio Conflict:** Forcibly disconnects the hosting device from regular Wi-Fi on single-radio devices.
- **Conclusion:** Designated as a **Tier 4 Emergency Fallback** when Wi-Fi Direct is disabled by device management or OEM bugs.

---

### 3.4 Local Area Network (LAN) + NSD (mDNS) + TLS 1.3
When both devices are connected to the same local Wi-Fi router, office intranet, or mobile hotspot, transferring data over the existing network infrastructure avoids all radio reconfiguration.

#### Advantages:
1. **Zero Radio Disruption:** Neither device needs to disconnect from its Wi-Fi network, preserving background internet access and battery.
2. **Superior Throughput:** On modern 802.11ax (Wi-Fi 6/6E/7) routers, local LAN transfers achieve 500 – 900+ MB/s without radio interference.
3. **Universal Cross-Platform Foundation:** mDNS/DNS-SD (`_glide._tcp`) and standard TCP sockets operate identically across Android, Windows, Linux, macOS, and iOS. This establishes the architectural foundation for **GLIDE-072 (Android-to-Windows LAN slice)**.
4. **Fast Discovery:** mDNS discovery completes in under 500 milliseconds.

#### Constraints:
1. **AP Client Isolation:** Enterprise, university, and hotel Wi-Fi networks frequently enable AP isolation, blocking peer-to-peer multicast discovery and direct TCP traffic.
2. **Android 17 (API 37) Local Network Permission:** Starting in Android 17, querying local network peers requires the `ACCESS_LOCAL_NETWORK` runtime permission.
3. **Conclusion:** Selected as **Tier 2 (Cross-Platform & LAN)**. Whenever a shared local network is available, LAN + NSD is prioritized over Wi-Fi Direct.

---

## 4. Radio Coexistence & Concurrency Analysis

### 4.1 PHY Coexistence Architectures
Mobile Wi-Fi chips operate under one of two architectures:
1. **Single-PHY Radios:** A single baseband and RF chain. If the device is connected to a 5 GHz home Wi-Fi network and a P2P or Aware session initiates on a 2.4 GHz channel, the chip must use **Multi-Channel Concurrency (MCC)** (time-slicing between channels) or drop the home connection. MCC reduces effective transfer throughput by 50% to 65% and increases packet jitter.
2. **Dual-PHY / Dual-Band Simultaneous (DBS):** Found in premium chipsets (Qualcomm FastConnect 6800+, Broadcom BCM4389+), allowing the chip to sustain a 5 GHz station connection while simultaneously running a 5 GHz or 6 GHz Wi-Fi Direct connection on an independent radio chain (**Single-Channel Concurrency / SCC** or **True DBS**).

### 4.2 Coexistence Rules for Glide
To protect user experience and throughput:
- **Probe Preferred Channel:** The receiver creating an Autonomous GO should query `WifiP2pManager.setWifiP2pChannels()` to match the operating channel of the active STA connection, forcing **Single-Channel Concurrency (SCC)** and avoiding MCC time-slicing.
- **Consent on STA Teardown:** If the device must drop active Wi-Fi to establish an offline link, the UI must inform the user before initiating the group.
- **Automatic Post-Transfer Recovery:** Once the transfer terminates or fails, any temporary P2P group must be immediately dissolved (`removeGroup()`), releasing the radio to re-associate with the user's home Wi-Fi network.

---

## 5. Security & Cryptographic Proof Plan

### 5.1 The Link-Layer Fallacy
A common vulnerability in peer-to-peer applications is relying on Wi-Fi link encryption (WPA2-PSK or Wi-Fi Aware PMK) for application security. In Wi-Fi Direct, all devices sharing the group passphrase can sniff unencrypted socket traffic.

### 5.2 Mandatory Application-Layer TLS 1.3
For all non-GMS transports (LAN, Wi-Fi Direct, Local Hotspot), Glide mandates **Application-Layer TLS 1.3**:
```
+-------------------------------------------------------------+
|               Glide Application Protocol (V1)               |
|  (Envelopes, Manifests, Chunk Verification, Receipts)       |
+-------------------------------------------------------------+
|            Application TLS 1.3 Session Layer                |
|  - Mutual Authentication (Client & Server Certificates)     |
|  - Ephemeral ECDHE Key Exchange (X25519)                    |
|  - Cipher Suites: TLS_AES_128_GCM_SHA256, TLS_CHACHA20...   |
+-------------------------------------------------------------+
|              TCP Framing / Length-Prefixed Stream           |
+-------------------------------------------------------------+
|       Physical Transport: LAN Sockets / Wi-Fi Direct        |
+-------------------------------------------------------------+
```

### 5.3 Authentication & SAS / TOFU Binding
1. **Ephemeral Self-Signed X.509:** Each device generates an ephemeral in-memory EC P-256 keypair upon transport startup.
2. **SAS Derivation:** The 6-digit Short Authentication String (SAS) is derived cryptographically from the SHA-256 hash of the concatenated client and server TLS certificate public keys:
   $$\text{SAS} = \text{ExtractDecimal6}(\text{SHA-256}(K_{\text{client\_pub}} \mathbin{\Vert} K_{\text{server\_pub}} \mathbin{\Vert} \text{Nonce}_{\text{session}}))$$
3. **Hardware Keystore TOFU (ADR-0015):** When persistent identity is active, the TLS handshake validates the peer's Android Keystore public key signature, bypassing the manual 6-digit SAS prompt once trust is established.

### 5.4 Threat Model Coverage Matrix

| Threat ID | Threat Description | Non-GMS Transport Mitigation |
|:---|:---|:---|
| **T01** | Transport Spoofing & Impersonation | TLS 1.3 mutual auth bound to 6-digit SAS or Keystore hardware identity. |
| **T02** | Man-In-The-Middle (MITM) Eavesdropping | End-to-end TLS 1.3 encryption prevents snooping even on open Wi-Fi Direct groups. |
| **T03** | Replay & Stale Connection Attacks | Ephemeral TLS sessions and fresh 32-byte mutual session nonces. |
| **T04** | Unauthorized Payload Injection | Raw socket data passes through TLS framing before protocol deserialization. |
| **T05** | Denial of Service via Connection Flooding | Strict single-active-session concurrency limit; unknown peers dropped before TLS completion. |
| **T06** | Radio Fingerprinting & Tracking | Randomized Wi-Fi Direct MAC addresses (`WIFI_DEVICE_INFO`) and ephemeral mDNS service names. |
| **T07** | Network Port Scanning Abuse | TCP server socket binds exclusively to the private P2P interface (`192.168.49.1`) or local subnet. |
| **T08** | Incomplete Transfer Truncation | End-of-stream EOF validated against manifest total byte count and SHA-256 root digest. |
| **T10** | Public Storage Pollution | Identical private quarantine staging; unverified files never committed to public storage. |
| **T11** | Staging Directory Path Traversal | Bounded sanitizeFilename and canonical Path isolation inside app-private cache. |
| **T12** | Partial Download Infiltration | Zero unverified partial bytes committed to destination folder. |
| **T13** | File Splicing / Collision Overwrite | Non-destructive monotonic collision rename e.g. "file (1).ext". |
| **T14** | Zombie File Leaks on Abort | Synchronous purge of quarantine file on transport disconnect or cancel. |
| **T15** | Concurrent Transfer Race Conditions | Single active transfer session enforced at transport and coordinator levels. |
| **T17** | Resource Exhaustion (Memory/CPU) | Bounded $O(1)$ 64 KiB socket I/O buffers; zero in-memory buffering of full payload files. |
| **T18** | Silent Permission Leakage | Non-GMS transport permissions are isolated; V1 production manifest remains completely untouched. |

---

## 6. V1 Permission Isolation Guarantee

### 6.1 Strict Preservation of V1 Manifest
In accordance with `docs/android/PERMISSION_MATRIX.md`:
- **Current V1 Permissions:** Restricted to GMS Nearby Connections requirements (`BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE`, `NEARBY_WIFI_DEVICES`, `FOREGROUND_SERVICE`, `POST_NOTIFICATIONS`).
- **No Expansion in V1:** Neither `ACCESS_FINE_LOCATION`, `CHANGE_WIFI_STATE`, nor `ACCESS_LOCAL_NETWORK` shall be added to the production V1 manifest.
- **Future Plugin Architecture:** Post-V1 non-GMS transports will be distributed as optional modular packages (e.g. `:transport-wifi-direct`, `:transport-lan`), with permissions declared solely within those isolated library manifests.

---

## 7. Implementation Roadmap & Milestones

1. **GLIDE-071 (Current):** Architectural evaluation, feasibility specification, ADR-0016, pure JVM capability models, and compliance testing.
2. **GLIDE-072 (Next):** Specification and implementation plan for the Android-to-Windows interoperable LAN transport slice using LAN + NSD + TLS 1.3.
3. **Future (Post-V1 M14+):** Prototype Autonomous Wi-Fi Direct transport plugin for AOSP devices.
