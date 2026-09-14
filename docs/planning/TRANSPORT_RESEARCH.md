# Transport evaluation
Decision: Android V1 uses Nearby Connections P2P, with BYTES control and STREAM bulk payloads behind a transport port. GLIDE-010 through GLIDE-018 prove this before broad UI work. This is a research-backed recommendation awaiting actual two-device evidence, not a throughput claim.

| Candidate | Discovery / pairing | Bulk / power / background | Portability / security / recovery | Decision |
| --- | --- | --- | --- | --- |
| Nearby P2P | Managed BLE/Bluetooth/Wi-Fi discovery; token comparison | High-throughput one-to-one strategy; FILE optimized, STREAM available; OEM and GMS variability; FGS still needed | Offline encrypted channel; user authentication essential; GMS on Android; no Glide resume guarantee | Selected adapter |
| Wi-Fi Direct | Group-owner negotiation, permissions and vendor edge cases | Potentially fast IP link; app owns socket lifecycle and power | Android-focused setup; link security does not replace app authentication/TLS | Too much setup complexity for first slice |
| LAN + NSD + sockets | Requires same reachable network; multicast/AP isolation can break discovery | Potentially fast; no hotspot switching; foreground owner required | Strong desktop path; sockets/NSD supply no application authentication; TLS required | P5 first cross-platform transport |
| Bluetooth Classic RFCOMM | Pairing UX/device support variability | Lower practical bulk bandwidth; persistent radio cost | Broad but uneven platform APIs; app framing/auth still needed | Not multi-GB V1 path |
| BLE GATT | Useful discovery; advertising limitations | Small packets, unsuitable bulk throughput; background restrictions | Discovery bridge possible; advertisements not authenticated identity | Do not implement separate hybrid stack |
| Wi-Fi Aware | Nearby peer discovery without AP, supported hardware/API26+ only | Potentially fast data path; availability can change with other Wi-Fi roles | Hardware coverage and platform asymmetry; secure app channel still required | P4 research only |
| WebRTC | Requires signaling and usually network coordination | Data channels, congestion control; integration/binary cost | Browser route, DTLS; identity and offline discovery remain unsolved by it | P5, no V1 dependency |

These comparisons are engineering judgments, not comparative measurements. Primary API references: [Nearby overview](https://developers.google.com/nearby/connections/overview), [strategies](https://developers.google.com/nearby/connections/strategies), [Wi-Fi Direct](https://developer.android.com/develop/connectivity/wifi/wifi-direct), [Aware](https://developer.android.com/develop/connectivity/wifi/wifi-aware), [NSD](https://developer.android.com/develop/connectivity/wifi/use-nsd), [BLE](https://developer.android.com/develop/connectivity/bluetooth/ble/ble-overview).

## Why STREAM initially
Nearby FILE minimizes copying but the documented receive flow uses SDK-managed temporary files and additional destination handling. V1 uses STREAM to write directly into app-private quarantine with bounded buffers and hash exactly the bytes received. This deliberately trades potential FILE optimization for explicit private storage ownership and one-pass hashing. STREAM is a generic byte transport; Glide imposes a known size and EOF contract.

The P0 benchmark must compare STREAM against a small isolated FILE experiment on the same phones. Any FILE switch requires documented SDK 19.5 behavior for private incoming destination, URI access, cancellation cleanup, extra disk capacity and integrity, then ADR update. Do not ship public unverified temporary Downloads content. No transport change is authorized by this documentation run.

## Adapter contract and limits
Only one endpoint and one active batch; files sequential. Stop discovery/advertising while connected. Use P2P, not STAR or CLUSTER. Report unavailable/outdated GMS with an actionable explanation; no “offline support” claim for a device that lacks the required SDK runtime. Internet service is not required for a transfer after the runtime is installed.

The adapter promises authenticated encrypted peer channels, reliable complete control messages, an opaque bulk reference mapped to a readable byte stream, cancellation and disconnect events. It normalizes callback threading into the core event queue. Nearby endpoint/payload IDs never become persistent peer identity or domain item IDs.

A received payload callback is not completion. Core waits for stream EOF, transport SUCCESS, exact byte count and digest verification. Metadata and stream callbacks can arrive in either order; bounded pending maps and timeouts are specified in the [protocol](../protocol/GLIDE_PROTOCOL_V1.md). SDK retransmissions are opaque. V1 reconnect means a fresh authenticated session, never offset continuation.

## Proof gate
On two physical GMS Android devices: offline discovery, compare token, explicit accept, 10 MB then 1 GB transfers with SHA256 equality, screen-lock run, source/provider failure, cancellation and receiver cleanup. Capture latency, throughput, RAM and thermal evidence. STREAM should achieve at least 70% of the controlled FILE median for 1 GB on the same pair; this is an engineering investigation threshold, not a universal speed promise. If missed, investigate buffer/I/O/radio constraints and apply change control before accepting M3.

