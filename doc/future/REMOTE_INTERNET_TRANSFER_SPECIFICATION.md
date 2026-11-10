# Remote and Internet Transfer (WAN, NAT Traversal & Blind Relays) Specification

- **Document Version:** 1.1.0 (HARD-002 reconciliation)
- **Design Status:** Approved technical target
- **Implementation Status:** **OUT OF ACTIVE SCOPE (Active project scope is restricted to local native sharing across Android, Windows, Linux, and Apple)**
- **Original Milestone:** M21 — Remote & Internet Transfer Architecture & Conformance Specification
- **Current Task Authority:** [HARD program](../TODO_MASTER.md)
- **Architectural Decision:** [ADR-0022](../adr/ADR-0022-remote-internet-transfer.md)
- **Related Documents:** [GLIDE_PROTOCOL_V1.md](../protocol/GLIDE_PROTOCOL_V1.md), [GLIDE_THREAT_MODEL.md](../security/GLIDE_THREAT_MODEL.md), [current hardening plan](../planning/CODE_REVIEW_IMPROVEMENT_AND_HARDENING_PLAN.md).

> **HARD-002 evidence note:** post-review commits added C# remote invitation/STUN work, WebSocket signaling helpers, ICE candidate models/gathering, TURN packet helpers, relay quota/metered-network policy, and a `RemoteTransferSession` state/orchestration model with unit tests. That is **not a live end-to-end remote transfer system**. The reviewed delta does not demonstrate a deployed signaling broker, real connectivity checks/ICE nomination, authenticated end-to-end encryption carrying Glide records, a live TURN service/allocation path integrated into the app, or a production UI/runtime path. Historical session files that called Step 6 `100% complete` are preserved as component-delivery records, not current interoperability or release certification.

---

## 1. Purpose and invariants

This specification defines the **target** architecture for optional remote/WAN transfer. Local Nearby/LAN operation must remain independent of this feature.

Target invariants:

1. **No cloud file storage.** Payloads are direct peer-to-peer or transiently forwarded through blind relays; no object-store upload is required.
2. **End-to-end confidentiality/authentication.** Signaling and relayed payloads must be encrypted so broker/relay infrastructure cannot inspect file content or trusted control data.
3. **Canonical Glide semantics.** Remote transport carries the same bounded Protocol V1/LAN-record/session semantics rather than inventing a second transfer protocol.
4. **Strict local isolation.** Local LAN/Nearby transfers must not depend on signaling/relay availability.
5. **Explicit remote consent and metered-network protection.** Remote/WAN transfer cannot start silently or consume metered data without the required user decision.
6. **No completion by helper classes.** ICE/STUN/TURN codecs, state models, or source-presence auditors are insufficient without a real network path and security proof.

---

## 2. Target remote architecture

```text
Out-of-band invitation
        |
        v
Authenticated encrypted signaling rendezvous
        |
        +--> ICE candidate gathering / connectivity checks / nomination
                    |
          +---------+---------+
          |                   |
      direct path          TURN relay
          |                   |
          +---------+---------+
                    |
      authenticated encrypted Glide record stream
                    |
      normal offer/consent/integrity/receipt semantics
```

### HARD-002 implementation mapping

| Target component | Source evidence at snapshot | Current classification |
| --- | --- | --- |
| Remote invitation/STUN | pre/post-review C# codecs/clients | Implemented components; historical unit-test evidence |
| Signaling client/messages | `RemoteSignalingClient`, `SignalingMessages` | Implemented client/message helpers; no deployed broker proof |
| ICE candidates/gathering | `IceCandidate`, `IceCandidateGatherer` | Implemented gathering/model helpers; no real ICE checklist/nomination proof |
| TURN | `TurnClient` packet/allocation helpers | Implemented component; no live relay integration proof |
| Relay quota/rate policy | `RelayQuotaTracker` | Implemented policy helper |
| Metered protection | `MeteredNetworkProtection` | Implemented policy/prompt abstraction; native platform detectors not integrated here |
| Remote session | `RemoteTransferSession` | In-memory orchestration/state model; does not itself execute live signaling/ICE/TURN/E2EE transfer |
| E2EE payload channel | Design text only in reviewed delta | Not evidenced as production remote data path |
| End-to-end WAN interoperability | Historical unit/conformance tests only | Not physically/network verified |
| Release/deployment | No current HARD evidence; no GitHub release objects | Not verified/released by HARD program |

---

## 3. Out-of-band invitation target

A remote session may be bootstrapped with a short-lived out-of-band invitation containing bounded rendezvous correlation, expected peer key/identity material, signaling endpoint information, authorization token material, and expiry.

Security requirements:

- cryptographically random one-use rendezvous identifier/token;
- strict expiry and replay prevention;
- endpoint/host allow-list or policy appropriate to the deployment model;
- no plaintext long-lived credentials in shared invitation links;
- receiver must still present explicit transfer consent separately from connection establishment.

Existing invitation codecs are reusable inputs, not proof that a broker or live remote session exists.

---

## 4. Signaling target

A production signaling channel must use authenticated TLS (`wss://`) and carry only the minimum rendezvous data needed to establish the peer path. Sensitive peer/session material should be end-to-end encrypted where the architecture requires a zero-knowledge broker.

A complete implementation must demonstrate:

- a real signaling broker/service contract;
- connect/listen/rendezvous lifecycle and expiry;
- authenticated endpoint verification;
- bounded messages and rate limits;
- peer disconnect/reconnect/error behavior;
- broker state retention/deletion policy;
- no signaling dependency during ordinary local transfers.

`RemoteSignalingClient` is a useful client abstraction but does not by itself satisfy those operational requirements.

---

## 5. ICE/STUN target

A standards-based ICE implementation must gather candidates, perform connectivity checks, maintain candidate-pair state, nominate a successful pair, and react to network changes/failure.

Candidate categories may include:

1. host candidates;
2. server-reflexive candidates obtained via STUN;
3. relayed candidates obtained through TURN.

The HARD-002 reviewed `RemoteTransferSession.SelectCandidatePair` selects candidate *types/priorities* from lists; it is not evidence of RFC 8445 connectivity checks or successful NAT traversal. Future remote work must prove actual candidate checks/nomination across NAT topologies before claiming ICE completion.

No unverified success-rate marketing percentage should be used without measured, dated population/device/network evidence.

---

## 6. TURN relay target and anti-abuse

A production relay path must establish and maintain real TURN allocations/permissions/channels against an authorized relay service and forward only end-to-end encrypted payloads.

Policy targets retained from the design include:

- bounded public/community relay transfer size;
- concurrency/rate limits;
- short-lived authorization tokens;
- no relay decryption keys;
- cancellation/accounting on both peers;
- explicit fallback messaging when a direct path cannot be established.

`TurnClient` and `RelayQuotaTracker` provide protocol/policy component evidence. They are not deployment evidence for a reachable relay or a complete application fallback path.

---

## 7. End-to-end cryptographic target

The remote data path must have one frozen, interoperable security construction—not an unresolved "Noise IK **or** mutual TLS" choice in release code.

Before remote implementation can be considered complete, an ADR/protocol update must freeze:

- authenticated key-establishment mechanism;
- peer identity/key binding to the out-of-band invitation;
- forward-secrecy and rekey/session lifetime behavior;
- transcript/channel binding;
- nonce/counter/replay rules;
- framing of encrypted control/data records;
- failure behavior on authentication/decryption errors;
- key erasure/logging requirements.

The reviewed post-`ea2e811` source does not provide a production end-to-end encrypted payload channel, so statements such as "all remote signaling and payload streams are E2EE" describe the **target invariant**, not current implementation fact.

---

## 8. Metered network and user consent target

Before remote transfer starts, each supported platform should determine whether the active route is metered/expensive/constrained using actual native APIs and present a clear user decision when required.

The existing C# policy/prompt abstractions are useful domain components. Production completion requires real Android, Windows, Linux, and any future Apple detector integration plus UI behavior and tests on the target operating systems.

Remote connection consent does not replace normal incoming file-offer consent.

---

## 9. Remote threat catalog

The remote design continues to track at least:

| ID | Threat | Required control direction |
| --- | --- | --- |
| R01 | unauthorized remote ingress/spam | short-lived invitation + explicit consent + rate limits |
| R02 | signaling snooping/metadata exposure | authenticated TLS + minimized broker data + E2EE where specified |
| R03 | relay bandwidth abuse/DDoS | auth tokens + quotas + concurrency/rate limits |
| R04 | MITM | cryptographic peer binding to invitation/identity |
| R05 | metered-data bill shock | native metered detection + explicit warning |
| R06 | stale/replayed invitations | expiry + one-use consumption + replay cache/policy |
| R07 | malicious file ingress | standard quarantine/integrity/no auto-execution rules |
| R08 | cloud dependency creep | local paths remain fully independent |

Threat-model presence is not proof that the control is implemented or deployed.

---

## 10. Reconciled implementation roadmap

| Phase | Intended result | HARD-002 classification |
| --- | --- | --- |
| R1 invitation + signaling | invitation codec plus real rendezvous service/client | **Partial: codecs/client helpers exist; broker deployment not evidenced** |
| R2 STUN/ICE | real connectivity checks and nominated direct path | **Partial: STUN/candidate/gathering helpers exist; ICE connectivity not proven** |
| R3 TURN fallback | live blind relay with quota/auth | **Partial: TURN/quota components exist; relay integration/deployment not proven** |
| R4 metered UX/platform integration | native detection and explicit consent | **Partial: C# policy abstraction exists; cross-platform product integration incomplete** |
| R5 end-to-end certification | encrypted payload transfer through direct and relay paths with failure/security tests | **Not achieved by the reviewed unit tests** |

Remote/Internet transfer is deliberately **deferred** from HARD-001–043 active release scope. Existing code is retained for future work; no current task may treat remote transfer as production-complete without an explicit scope amendment, frozen security construction, real infrastructure/runtime integration, and end-to-end network evidence.