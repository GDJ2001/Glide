# Glide Protocol V1
Status: accepted planning specification; no implementation or interoperability claim. Normative terms MUST/SHOULD describe future implementations. [Architecture](../architecture/GLIDE_ARCHITECTURE.md) · [State machines](../architecture/STATE_AND_DATA_MODEL.md) · [Threat model](../security/GLIDE_THREAT_MODEL.md).

## Boundary and goals
The protocol defines authenticated offer, consent, ordered file streams, integrity and receipts. It does not define Bluetooth discovery, radio encryption, a database or UI. Android Nearby maps this protocol to BYTES and STREAM. A future LAN client must supply an authenticated encrypted channel and reliable framed control plus bulk streams; plain TCP is insufficient.

V1 supports one sender, one receiver, one batch per connection, sequential files, all-or-nothing offer acceptance but per-item completion. No directories, links, compression, archive extraction, resume, pause, multiplexing, remote relay or trust persistence. All file contents are opaque bytes.

## Common envelope and wire rules
UTF-8 JSON, no BOM, one object per control payload, maximum 16,384 encoded bytes. On byte-stream transports, prefix each JSON control with unsigned 32-bit big-endian byte length. Nearby BYTES boundaries already frame the message. No canonical JSON or signatures are invented.

Every message contains:
- `v`: integer major version 1.
- `type`: exact case-sensitive catalog name below.
- `sessionId`: lowercase canonical UUIDv4, initiator generated from cryptographically secure randomness.
- `messageId`: distinct lowercase UUIDv4.
- `seq`: positive integer, starts at 1 independently per direction and increases by one.
- `body`: object with the type's required fields.
- Optional `extensions`: object, limited to 1,024 encoded bytes, namespaced keys. Unknown optional extension keys are ignored; they cannot override required fields.

UUIDs are identifiers, not credentials. No wall-clock time is trusted for replay checks. Peer maps are scoped to the live authenticated connection and session. Control delivery must be reliable and ordered at the port; adapter serializes sends and reorders SDK callbacks only when it has sequence evidence. Core buffers at most 8 out-of-order controls for 2 seconds; otherwise fails PROTOCOL_ORDER. Duplicate messageId with identical bytes and original seq is ignored or gets its cached response; same ID with differing bytes is MALFORMED_MESSAGE. Keep at most 256 response-cache entries; older duplicates are rejected. Gap/replay after eviction fails closed. Terminal sessions never reopen.

JSON duplicate keys, invalid UTF-8, excess nesting (>8), non-finite values, unknown required type, missing fields and wrong types are invalid. Unknown ordinary body fields are rejected in V1; extensibility belongs in extensions. Reject before expensive allocation. Unknown optional extensions remain bounded. Integers representing byte counts are decimal strings matching 0 or a nonzero digit followed by digits, parsed with checked unsigned arithmetic and bounded below signed 64-bit max. This keeps future JavaScript clients exact. IDs and digests are strings; sequence/index/count fields are JSON integers within their bounded ranges.

## Session negotiation
The Nearby discoverer is the V1 sender/initiator; receiver advertises. Both users MUST compare the complete SDK authentication digits and explicitly confirm before either side accepts the channel. Never invent a derived short code or auto-accept from device name. No protocol/file metadata before successful authenticated connection.

HELLO is the first control. All negotiation uses bootstrap envelope v=1. The sender sends supportedMajors=[1], requiredCapabilities=["stream-v1","sha256"], optionalCapabilities=[], role="sender", ephemeralPeerId UUIDv4 and displayName (1–64 Unicode code points, <=256 UTF-8 bytes). Receiver HELLO_ACK returns selectedMajor=1, same required capability set, receiver ephemeralPeerId/name and effective limits. V1 limits may only decrease. Unsupported major/capability causes UNSUPPORTED_PROTOCOL and closes. Both implementations validate that selection belongs to the offer; no plaintext or hash-free fallback. Future major versions need a separately documented bootstrap-compatible negotiation or separate service advertisement. Reject selection not offered.

The discovery service ID for all Android V1 builds is `glide.local.transfer.v1`; debug builds MUST use `.debug` suffix. It is a routing label, not authentication or an owned-domain assertion. Advertisements carry only a bounded user device name; no file names, stable ID or precise hardware identifier.

## Fixed limits
| Limit | V1 maximum / behavior |
| --- | --- |
| Items | 1,000; nonempty batch |
| Item bytes | 68,719,476,736 (64 GiB) |
| Batch bytes | 274,877,906,944 (256 GiB); checked sum |
| Offer page | <=32 items AND full control <=16 KiB; paginate earlier when names are long |
| Whole offer metadata | <=1 MiB encoded item arrays accumulated across pages |
| Display filename | 1–255 Unicode code points AND <=1,024 UTF-8 bytes; display only, never path |
| MIME | <=127 ASCII characters; syntactically valid type/subtype or application/octet-stream |
| Pending bulk references | 2, timeout 5 seconds, unsolicited streams cancelled without reading into memory |
| Concurrent file streams | 1 accepted; transport late events must be handled |
| Control rate | <=20 messages/second sustained, burst 40; legitimate local progress never sent at frame rate |

Limits are engineering safety ceilings, not a promise every device can store such files. Offer acceptance also requires receiver disk budget and sender source access. Empty files are valid; empty batches are not.

## Message catalog
All batch/item fields are UUIDv4. No field can contain a URI or filesystem path.
| Type / direction | Required body and semantics |
| --- | --- |
| HELLO S→R | supportedMajors, requiredCapabilities, optionalCapabilities, role, ephemeralPeerId, displayName |
| HELLO_ACK R→S | selectedMajor, capabilities (intersection), ephemeralPeerId, displayName, limits {maxItems,maxItemBytes,maxBatchBytes,maxControlBytes}; values bounded by table |
| OFFER_BEGIN S→R | batchId, itemCount, totalBytes, pageCount (1..1000). Starts metadata assembly |
| OFFER_PAGE S→R | batchId, pageIndex (0 based, contiguous), items: [{itemId,name,mime,sizeBytes}]. Unique item IDs; preserve order |
| OFFER_END S→R | batchId. Receiver validates page/count/sum/bounds before showing consent |
| OFFER_DECISION R→S | batchId, accepted boolean; rejected requires code=USER_REJECTED or INSUFFICIENT_STORAGE or DESTINATION_UNAVAILABLE. No partial subset in V1 |
| ITEM_START S→R | batchId,itemId,bulkRef,sizeBytes. bulkRef is opaque ASCII <=64 bytes, unique per session; size must equal offer |
| ITEM_READY R→S | batchId,itemId. Destination opened and budget reserved; sender may now send stream |
| ITEM_END S→R | batchId,itemId,actualBytes,sha256 (64 lowercase hex). Sent after sender stream EOF and sender transport success |
| ITEM_RESULT R→S | batchId,itemId,status="verified" or "failed"; verified includes sizeBytes and sha256; failed includes catalog code |
| BATCH_END S→R | batchId. All items must have verified results; never use as substitute for item receipts |
| BATCH_RESULT R→S | batchId,status="completed",verifiedCount,totalVerifiedBytes. Count/bytes must equal accepted offer and already-received item receipts. Receiver sends only after durable completion record; do not repeat1000 UUIDs in this bounded message |
| CANCEL either | batchId optional before offer, code=USER_CANCELLED or SYSTEM_CANCELLED; terminates unfinished items |
| CANCEL_ACK either | acknowledges CANCEL messageId in replyTo; best effort before close |
| ERROR either | code catalog value, optional batchId/itemId; safe bounded detail <=160 characters, never raw exception |
| PING either | nonce UUIDv4 |
| PONG either | same nonce |

No transfer-start/progress field is needed beyond ITEM_START/READY and local byte counters. Sender network progress is not receiver verification. ITEM_RESULT supplies authoritative per-file receipt; sender MUST compare its returned size/digest with its own completed stream before marking verified. HELLO_ACK limits must be positive, at or below offered/V1 maxima; reject maxControlBytes below1024 as unsupported rather than emitting an oversized control. Effective control limits also apply to pagination. No unsupported operation should be inferred from optional capability strings.

## Stream binding and integrity
A bulk stream contains only raw item bytes. Nearby binding bulkRef is the decimal signed payload ID string obtained when sender constructs Payload.fromStream; this transport-specific value is interpreted only by that adapter. Domain references remain opaque. Sender announces ITEM_START before sendPayload, waits ITEM_READY, then begins payload. Map by (session,bulkRef,itemId). Receiver may observe payload callback before the corresponding control callback; keep the stream handle unopened in the bounded pending map until mapped/accepted, cancel on deadline. No metadata-free stream is admitted.

Sender wraps its bounded source stream in a byte counter and SHA-256 digest, hashing exactly the bytes supplied to Nearby. At EOF, total must equal offered length; one extra byte is an error. The sender then waits for transport success before ITEM_END. Receiver hashes raw bytes while writing private quarantine, rejects >offered length immediately, and requires EOF, matching exact length, transport SUCCESS and ITEM_END digest before committing. Either callback may arrive first; coordinator records flags and evaluates the same completion predicate. SHA-256 is a standard content checksum; authenticated channel protects its exchange. It is not malware detection and does not authenticate a person.

Zero-byte item: normal zero-length stream, EOF and standard empty SHA-256 digest. Core never treats an initial zero counter as completion. An item digest mismatch deletes its unverified partial, records INTEGRITY_MISMATCH and terminates the remainder of the batch. Retry requires fresh consent/session/source read and hashes again.

## Normal sequence
```mermaid
sequenceDiagram
 participant S as Sender
 participant T as Authenticated transport
 participant R as Receiver
 S->>T: Request connection
 T->>S: SDK authentication digits
 T->>R: Same authentication digits
 Note over S,R: Both users compare and explicitly confirm
 S->>R: HELLO
 R->>S: HELLO_ACK
 S->>R: OFFER_BEGIN / PAGE(s) / END
 Note over R: Inspect names, total, destination; accept
 R->>S: OFFER_DECISION accepted
 loop Sequential items
 S->>R: ITEM_START
 R->>S: ITEM_READY
 S->>R: Raw bulk stream
 S->>R: ITEM_END length + SHA256
 Note over R: EOF + SDK SUCCESS + hash match; private durable commit
 R->>S: ITEM_RESULT verified
 end
 S->>R: BATCH_END
 Note over R: Commit batch history
 R->>S: BATCH_RESULT completed
```

## Deadlines and cancellation
Use monotonic time. Discovery window120s (UI restart); link30s; human code confirmation60s; HELLO10s; each offer-page gap10s; offer decision120s; ITEM_READY30s; first bulk bytes30s; no byte progress60s; post-EOF verification/receipt120s; BATCH_RESULT10s. Total file duration has no fixed cutoff while progress continues. Disk flush is cancellable where platform permits; blocked provider operations have a bounded wrapper with descriptor closure and worker isolation. Idle connected peers PING after10s, PONG within10s; byte activity counts as liveness but not receipt. Human timeout stops the session; no automatic repeat prompts.

Cancellation can occur during every nonterminal phase. Close local streams immediately, request SDK payload cancellation, remove unverified output and persist terminal reason. A best-effort ACK never delays local cleanup beyond2s. A committed item cannot be “unreceived”; receiver keeps it and reports the partial batch. CANCEL racing commit is serialized: before commit prevents publication; after commit preserves verified file.

## Failure and receipt ambiguity
| Wire code | Scope / action |
| --- | --- |
| UNSUPPORTED_PROTOCOL, MALFORMED_MESSAGE, PROTOCOL_ORDER, AUTHENTICATION_FAILED | Close session, no retry on same channel |
| USER_REJECTED, USER_CANCELLED, SYSTEM_CANCELLED | Terminal, no automatic retry |
| SOURCE_UNAVAILABLE, READ_FAILURE, DESTINATION_UNAVAILABLE, WRITE_FAILURE, INSUFFICIENT_STORAGE | Fail unfinished batch; remediation before new offer |
| TRANSFER_INTERRUPTED, TIMEOUT, PEER_LOST | New session permitted after user action |
| INTEGRITY_MISMATCH | Delete partial, fail; never open it |
| LIMIT_EXCEEDED, BUSY | Reject request without allocation; close/rate-limit |

Authentication failure normally occurs before protocol start and cannot safely be sent over an unverified channel. Unknown codes map to generic safe protocol failure.

Lost final receipt does not mean the receiver lost the file. Receiver may show completed; sender shows “Transfer finished; receipt not confirmed” with DELIVERY_UNCONFIRMED local outcome. No distributed exactly-once guarantee. Retrying warns of possible duplicate received files and uses new IDs. Within a live session duplicate controls never trigger another disk write. Cross-session content deduplication is not V1.

## Future compatibility and conformance
Future resume requires authenticated durable peer identity, persisted content identity, byte-range/chunk negotiation, verified retained partials, expiry and independent threat review. Offset fields cannot simply be added and presumed safe. Cross-platform clients must implement these byte, name, ID and error rules; they need not use Kotlin or Room.

GLIDE-006/007 must create language-neutral valid/invalid JSON fixtures, empty and binary SHA256 vectors, paged offers, reordered callback traces, duplicate/replay traces, cancellation races and unknown extension cases. Golden fixtures are version-controlled documentation/test inputs; never derive expected outputs from the same codec being tested.
