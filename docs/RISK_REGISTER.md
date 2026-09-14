# Risk register

Qualitative planning assessments, not observed failure statistics. [Assumptions](ASSUMPTIONS.md) · [tasks](TODO_MASTER.md) · [security threats](security/GLIDE_THREAT_MODEL.md). Owners are accountable roles to assign during implementation; no team membership is assumed.

| ID | Description | Category | Likelihood | Impact | Severity | Mitigation | Fallback | Owner | Trigger |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| R01 | Nearby/OEM discovery variability | Transport | High | High | High | Early physical P2P proof across OEMs | LAN adapter future after ADR | Transport owner | Discovery/connect below95% controlled target |
| R02 | STREAM throughput lower than FILE | Performance | Medium | High | High | Controlled comparison atM3; bounded I/O profiling | Reviewed FILE private destination or defer release | Transport/performance owner | STREAM below70% same-pair FILE median |
| R03 | OS/OEM kills background session | Android | High | High | High | Valid connectedDevice FGS, journal, explicit retry | Foreground-only limitation with owner scope review | Android owner | Reproducible kill/false completion |
| R04 | No true resume for multi-GB files | Product | High | Medium | Medium | Honest retry UX, sequential partial outcomes | P3 reviewed resume extension | Product owner | Users need long interrupted transfers |
| R05 | New SDK/permission policy changes | Platform | Medium | High | High | API37 permission matrix and release recheck | Update adapter/ADR, never unsafe target downgrade | Android owner | SDK release/policy or permission failure |
| R06 | Recent AGP/Kotlin/KSP incompatibility | Build | Medium | High | High | GLIDE-001 effective compiler/processor proof | Documented AGP9.3.2/Gradle9.5 stable pair if needed | Build owner | Clean build fails with reproduced compatibility issue |
| R07 | Mutable/unknown/provider source behavior | Storage | High | High | High | Bounded staging, URI validation/closure, exact counts | Reselect or stage known private snapshot | Storage owner | Provider stalls, revokes or lies about length |
| R08 | Disk full / commit race / orphan leakage | Storage | Medium | Critical | Critical | Private journal, reserve, fsync/rename/reconcile tests | Stop receive, preserve verified files | Storage/security owner | Unverified visibility or lost committed file |
| R09 | Protocol abuse or consent bypass | Security | Medium | Critical | Critical | Strict limits, authenticated channel, separate consent, fuzz | Block release until fixed | Security owner | Threat test or review finding |
| R10 | Raster/native branding gaps | Design | High | Medium | Medium | Inventory, faithful derivatives and mask proof | Use accepted flat assets in internal proof only | Design owner | Launcher/splash/status glyph fails legibility |
| R11 | Missing hardware/test coverage | Delivery | Medium | High | High | Confirm phones early; evidence matrix | Acquire/borrow approved hardware; block unsupported claims | Test owner | M3 or release matrix unavailable |
| R12 | Package/name/legal/signing unresolved | Release | Medium | High | High | Owner decision before public artifact, custody review | Delay publication; never silently rename | Release owner | GLIDE-060/061 starts without identity/license evidence |
| R13 | Play policy/account closed test constraints | Release | Medium | High | High | Current Console checks and early tester preparation | Delay rollout until eligible | Release owner | Console rejects policy/testing submission |
| R14 | Performance/UI effect regressions | Quality | Medium | Medium | Medium | Release traces, reduced motion, delete costly effects | Simplify decoration | UI/performance owner | Frame/RAM/thermal budget exceeded |
| R15 | Privacy claim contradicts Nearby SDK diagnostics | Privacy | Medium | High | High | SDK audit and accurate Data Safety | Change claims/SDK via ADR, no false zero-data promise | Privacy owner | New SDK behavior or policy audit mismatch |
| R16 | Documentation/implementation drift | Process | Medium | High | High | Task evidence, ADR change control, session handoff | Pause affected assumption and reconcile | Implementation reviewer | Code bypasses canonical protocol or task deps |
| R17 | Private receive library surprises users | UX | Medium | Medium | Medium | Offer/result/settings disclose uninstall/export behavior | Reviewed default destination redesign later | Product owner | Usability review shows users expect public Downloads |
| R18 | Receipt loss causes duplicate retry | Protocol | Medium | Medium | Medium | DeliveryUnconfirmed state and warning; no exactly-once claim | User checks recipient then retries | Protocol owner | Disconnect after receiver durable commit |

Critical/High security or integrity defects block release. Hardware, signing and Console facts require external evidence; planning does not resolve them. Update likelihood/severity when measurements change, retaining incident evidence.

