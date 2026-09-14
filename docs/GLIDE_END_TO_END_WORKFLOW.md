# Glide end-to-end workflow
[Master](GLIDE_MASTER_PLAN.md) · [roadmap](ROADMAP.md) · [tasks](TODO_MASTER.md). Astra plans/reviews; Gemini implements later authorized tasks; product/release owner makes publication/legal decisions. No implementation is performed by this planning document.

| Stage | Inputs | Outputs | Responsible role | Required docs | Completion gate / next |
| --- | --- | --- | --- | --- | --- |
| Planning | Existing repo/assets/user scope | Audit, sources, explicit scope and gaps | Astra planner | planning/*, master | M0 completeness → foundation |
| Architecture | Research and risks | Modules/ports/state/protocol/ADRs | Architect/reviewer | architecture/*, adr/* | Accepted contracts → task decomposition |
| Foundation | READY001 and stack pins | Reproducible build/CI/minimal shell | Gemini | technology, engineering guide | M1 clean build → contracts |
| Minimal design foundation | Canonical assets/tokens | Accessible basic shell, no brand replacement | Gemini/UI reviewer | design system | Useful proof UI → protocol |
| Protocol/domain | Wire/state contracts | Validated codec/reducer/fixtures | Gemini | protocol, models, threat model | M2 adversarial tests → discovery |
| Discovery | Permissions + transport port | Real P2P peer appearance/loss | Gemini/test owner | transport, permission matrix | Physical peers → pairing |
| Pairing | SDK authentication event | Explicit verified channel | Gemini/security reviewer | protocol/flows | Both-side match, reject tests → transfer |
| Transfer | Granted source + accepted offer | Private streamed verified file | Gemini | storage/protocol | Exact bytes/hash → persistence |
| Persistence/open | Verified commit | Durable history/catalog and safe Open | Gemini | models/storage | First real M3 slice → batches |
| Foreground MVP | Proven one-file path | Batch/share/export/settings/history | Gemini/UI reviewer | flows/task ledger | M4 complete → background |
| Background | User-started active session | Service/notification/rebind behavior | Gemini/Android reviewer | storage/background/permissions | M5 physical lifecycle → reliability |
| Reliability | Failure traces/journal | Honest retry/partial/receipt outcomes | Gemini/test owner | failure model/testing | Chaos gate → polish |
| Premium/adaptive UI | Complete state flows | Branded light/dark/dynamic, motion, windows/accessibility | Gemini/design reviewer | design/* | M6/M7 UI/accessibility → hardening |
| Security | Implemented code/threats | Tested mitigations/release manifest/privacy | Gemini/security reviewer | security/* | M8 no blocking findings → performance |
| Performance | Release-like app and devices | Profiles/benchmarks/bounded tuning | Gemini/performance reviewer | performance/* | M9 measured gate → full test |
| Integration testing | Feature builds and migrations | CI/emulator/physical/soak evidence | Test/review roles | testing/CI/DoD | M10 quality → RC |
| Release candidate | All engineering gates | Signed exact AAB/APK, provenance | Release engineer/owner | release plan | RC artifact verified → store |
| Store preparation | Actual feature/screens/privacy | Listing, declarations, closed testing | Owner + Gemini assistance | release/brand gaps | M11 approval → production |
| Production launch | Approved version/hash | Authorized publication/install proof | Release owner | release gate record | M12 health → maintenance |
| Maintenance | Vitals/support/policy updates | Bounded patches, incident response, updated handoffs | Owner/Gemini/reviewers | risks/change control/session reports | Repeat verification → future decision |
| Cross-platform preparation | Shipped Android protocol evidence | Independent adapter/conformance plan | Architect, later platform implementer | future path/protocol | Separate authorization only |

At every implementation stage: read → inspect → check dependencies → implement scope → build → test → verify UI/architecture/security/performance → update docs → handoff. A failed prerequisite blocks dependent work; independent READY tasks may continue. Release operations require explicit owner authorization and are never inferred from a coding session. [Templates](development/GEMINI_PROMPT_TEMPLATES.md) make the cycle repeatable.

