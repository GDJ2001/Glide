# ADR-0016 — Advanced Android transports evaluation

- Status: Accepted (future evaluation); validation pending implementation evidence.
- Date: 2026-09-14.
- Authority: [Advanced Android transports evaluation](../future/ADVANCED_ANDROID_TRANSPORTS_EVALUATION.md); [decision index](README.md); [initial transport ADR](ADR-0007-transport.md); [permission matrix](../android/PERMISSION_MATRIX.md); [threat model](../security/GLIDE_THREAT_MODEL.md).

## Context
Glide V1 established a high-throughput, private-quarantine transport engine using Google Play Services (GMS) Nearby Connections P2P (`Strategy.P2P_POINT_TO_POINT`) with STREAM payloads (ADR-0007). While Nearby Connections provides zero-friction discovery and automatic BLE-to-Wi-Fi upgrade, it creates hard dependencies on proprietary Google Play Services, excluding de-Googled Android devices (AOSP, LineageOS, GrapheneOS), enterprise hardware, and platforms where GMS is unavailable. Furthermore, Nearby Connections cannot natively bridge Android to non-Android ecosystems (Windows, Linux, macOS, iOS).

To support de-Googled Android environments and establish a clean path toward cross-platform interoperability, alternative native Android transports must be evaluated without regressing V1 stability or expanding runtime permissions.

## Options considered
1. **Wi-Fi Aware (Neighbor Awareness Networking - NAN):** Ad-hoc discovery and data path without an access point (`android.net.wifi.aware`). Evaluated as high potential, but rejected as a primary transport due to severe hardware fragmentation (< 40% fleet support across active devices; predominantly Google Pixel and select Qualcomm chipsets; absent on MediaTek, Unisoc, and low-cost OEM lines) and frequent radio teardown of active station (STA) Wi-Fi on single-PHY chipsets.
2. **Standard Negotiated Wi-Fi Direct (Wi-Fi P2P):** Traditional P2P group owner negotiation (`android.net.wifi.p2p.WifiP2pManager`). While supported on > 95% of devices, dynamic Group Owner (GO) negotiation is empirically prone to cross-OEM negotiation failures (e.g. Samsung vs. Xiaomi vs. OnePlus handshakes timing out or deadlocking).
3. **Autonomous Wi-Fi Direct (P2P):** Wi-Fi Direct where the receiving device autonomously initializes as Group Owner (`createGroup()`), serving fixed local IP endpoints, with Wi-Fi Direct SSID and WPA2-PSK credentials exchanged out-of-band via BLE advertisements or high-density QR code.
4. **Local-Only Hotspot (`startLocalOnlyHotspot`):** SoftAP managed by `WifiManager` (API 26+) with client connection via `WifiNetworkSpecifier` (API 29+). Highly reliable, but forcibly terminates user station Wi-Fi on single-radio hardware and requires system dialog prompts.
5. **LAN + Network Service Discovery (NSD / mDNS) + TLS 1.3:** Discovery over shared local Wi-Fi or Ethernet networks using mDNS/DNS-SD (`android.net.nsd.NsdManager`) with application-layer TLS 1.3 mutual authentication. High throughput, zero radio switching, and completely cross-platform.

## Decision
Adopt a **Multi-Tier Transport Architecture** for post-V1:
- **Tier 1 (Default Android P2P):** GMS Nearby Connections P2P remains the default transport for standard Android devices with Google Play Services.
- **Tier 2 (Cross-Platform & LAN):** LAN + NSD + TLS 1.3 is selected as the primary transport for shared local networks, enabling high-speed cross-platform sharing with Windows (GLIDE-072), Linux, and macOS.
- **Tier 3 (GMS-Free Offline P2P):** Autonomous Wi-Fi Direct (P2P) with out-of-band BLE/QR credential exchange is selected as the fallback transport for de-Googled AOSP devices when no local network exists.
- **Opportunistic Probe:** Wi-Fi Aware (NAN) is categorized strictly as an opportunistic probe for compatible peer pairs, never a mandatory fallback.

## Reasoning
- **Empirical feasibility:** Autonomous Wi-Fi Direct eliminates the failure-prone two-party GO negotiation handshake that plagues standard Wi-Fi Direct across heterogeneous Android OEMs.
- **Zero-trust transport independence:** In accordance with Glide's security invariants, network link-layer encryption (WPA2/WPA3 or Wi-Fi Aware PMK) does not replace application-layer security. All socket-based transports (LAN and Wi-Fi Direct) mandate TLS 1.3 with mutual authentication and SAS/TOFU verification.
- **Cross-platform alignment:** LAN + NSD + TLS 1.3 provides an identical wire protocol and transport abstraction that can be implemented natively on Windows without reverse-engineering proprietary GMS stacks.

## Consequences
- **Zero silent expansion of V1 permissions:** V1 production manifest and runtime permissions remain completely untouched (`docs/android/PERMISSION_MATRIX.md`). New permissions (`NEARBY_WIFI_DEVICES` on API 33+, `ACCESS_LOCAL_NETWORK` on API 37+) will be scoped exclusively to post-V1 modular transport plugins.
- **Transport port abstraction:** All advanced transports must implement the Glide Core `TransportPort` interface, ensuring bounded $O(1)$ heap buffers, private quarantine staging, and pipelined SHA-256 verification.
- **Radio coexistence hygiene:** Transports must respect active station Wi-Fi; any operation that disrupts station Wi-Fi requires explicit user authorization.

## Alternatives and migration/fallback
When GMS Nearby is unavailable or disabled, the application evaluates available transports in order: Tier 2 (LAN + NSD if both peers share a local subnet) -> Tier 3 (Autonomous Wi-Fi Direct via BLE/QR out-of-band pairing). If neither is feasible, the user is presented with clear, actionable diagnostics.
