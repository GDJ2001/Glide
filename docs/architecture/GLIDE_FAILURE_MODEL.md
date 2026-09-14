# Failure model
Canonical domain failures map transport exceptions into stable categories. Wire codes are separately defined in the [protocol](../protocol/GLIDE_PROTOCOL_V1.md). UI strings below are guidance to localize, not raw exception messages.

| Failure | Category | Recoverable / retry | UI guidance and remediation | Logging / telemetry |
| --- | --- | --- | --- | --- |
| PermissionDenied | Prerequisite | Yes after grant | “Allow nearby access to find devices”; Settings after permanent denial | Permission category only; no upload |
| DiscoveryUnavailable | Environment | After radio/GMS fix | “Nearby sharing isn't available”; enable radios/update GMS | API and safe cause code |
| PeerLost | Link | Fresh discovery | “Device is no longer nearby”; rediscover | Session-local event count |
| ConnectionRejected | Consent | Only sender action later | “Connection declined”; return to selection | No peer name |
| AuthenticationMismatch | Security | New verified attempt only | “Codes don't match. Connection stopped.” | Security category, no code/token |
| Timeout | Lifecycle | Phase-specific new attempt | “Connection timed out” or “No response”; preserve selections | Phase + duration |
| SourceUnavailable | Storage | Reselect | “This file is no longer available”; remove/reselect item | No URI/path |
| DestinationUnavailable | Storage | After repair/export choice | “Glide couldn't save this file”; retained verified copies shown | Safe provider category |
| InsufficientStorage | Storage | Free space then new offer | “Not enough space”; show required/available bytes locally | Rounded size class only |
| ReadFailure | I/O | Reselect/retry | “Couldn't read this file”; stop unfinished batch | Safe code, no content |
| WriteFailure | I/O | After destination fix | “Couldn't save this file”; cleanup partial | Safe code |
| TransferInterrupted | Link/system | Fresh session, no resume | “Transfer interrupted”; show verified and unfinished counts | Phase/duration only |
| IntegrityMismatch | Integrity | Explicit fresh retry | “File check failed. The incomplete file was removed.” | Error category; no digest |
| UnsupportedProtocol | Compatibility | Update app | “These Glide versions can't share yet”; no downgrade | Supported major numbers |
| UserCancelled | User | New user action | “Transfer cancelled”; show retained verified files | No error alert/upload |
| SystemCancelled | OS | Foreground retry | “Android stopped the transfer”; no blame/fake guarantee | OS/API/cause if known |
| MalformedMessage / LimitExceeded | Protocol/security | No same-session retry | “Sharing stopped because the request wasn't valid” | Redacted category; no payload |
| Busy | Capacity | After active session | “Finish the current transfer first” | Counter only |
| DeliveryUnconfirmed | Receipt ambiguity | Warn before retry | “Receipt not confirmed. The other device may have the file.” | Distinct from transfer failure |
| ExportFailure / NoViewer | Post-receive | Retry export/install viewer | “File is safely saved in Glide”; receive remains successful | No URI/name |

No automatic transfer retries, duplicate prompts or indefinite reconnect loop in V1. A recoverable failure means user remediation is possible, not that the coordinator should replay bytes. Verify terminal partial-batch outcomes against [state model](STATE_AND_DATA_MODEL.md).

Logs are bounded rotating debug support data, release warning/error categories only; diagnostics remain local and disabled unless required for explicit support. No telemetry SDK is selected. User-requested report export must be previewed and redacted. Failure mapping tests must cover unknown SDK codes and avoid throwing from UI formatting.

