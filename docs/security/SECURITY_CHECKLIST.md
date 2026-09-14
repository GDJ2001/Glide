# Security completion checklist
Use for every sensitive task; attach evidence to [TODO](../TODO_MASTER.md), cite applicable [threat IDs](GLIDE_THREAT_MODEL.md). An unchecked relevant item prevents COMPLETE.

- [x] Peer verification is explicit on both devices; names cannot bypass it. *(Evidence: NearbyConnectionAdapter, TransferCoordinator, PairingVerificationView, SecurityGateAcceptanceTest.threatT01)*
- [x] No metadata or bulk before authenticated channel and offer consent. *(Evidence: TransferCoordinator.observeIncomingStreams, SecurityGateAcceptanceTest.threatT04, ProtocolFuzzAndAbuseTest)*
- [x] Version/capability downgrade rejected; malformed messages fail closed. *(Evidence: NegotiationValidator, SecurityGateAcceptanceTest.threatT02, NegotiationValidatorTest)*
- [x] Byte/metadata/count/time/rate limits enforced before allocation. *(Evidence: ProtocolRateLimiter, ProtocolLimits, OfferAssembler, SecurityGateAcceptanceTest.threatT05/threatT07/threatT08)*
- [x] Every callback/action checked against current session and item. *(Evidence: TransferCoordinator, ProtocolSequencer, SecurityGateAcceptanceTest.threatT03)*
- [x] File names never construct internal paths; export names safely normalized. *(Evidence: Identifiers.kt, FileOpener.kt, SecurityGateAcceptanceTest.threatT06)*
- [x] Private quarantine, exact byte count, SHA256 and SDK success precede commit/open. *(Evidence: StreamHasher, ReceiveCommitJournal, FileOpener, SecurityGateAcceptanceTest.threatT02/threatT14)*
- [x] Cancellation, disk-full and process-death clean unverified output. *(Evidence: ProcessDeathReconciler, OrphanMaintenanceCleaner, SecurityGateAcceptanceTest.threatT17, ProcessDeathReconcilerTest)*
- [x] Lost receipt is not falsely reported as failed delivery or confirmed success. *(Evidence: TransferReducer, ProtocolSequencer, TransferCoordinatorTest)*
- [x] URI grants are read-only/minimal; no file://, broad FileProvider paths or auto-open. *(Evidence: ile_paths.xml, FileOpener.kt, ShareIngressParser.kt, AndroidSecurityHardeningTest, SecurityGateAcceptanceTest.threatT12/threatT14/threatT15)*
- [x] Exported components and PendingIntents reviewed from merged release manifest. *(Evidence: AndroidManifest.xml, TransferNotificationManager.kt, AndroidSecurityHardeningTest, SecurityGateAcceptanceTest.threatT12)*
- [x] Background work has valid type, visible user initiation and bounded lifetime. *(Evidence: TransferService, TransferNotificationManager, ServiceBoundAcceptanceTest)*
- [x] Logs, notifications, backups and diagnostics contain no sensitive payload data. *(Evidence: data_extraction_rules.xml, ackup_rules.xml, TransferNotificationManager, AndroidSecurityHardeningTest, SecurityGateAcceptanceTest.threatT10/threatT11)*
- [x] Random IDs use secure platform randomness; no custom crypto or fake trusted peers. *(Evidence: DefaultIdGenerator, SystemPorts, AndroidSecurityHardeningTest, SecurityGateAcceptanceTest.threatT09/threatT13)*
- [x] Dependencies/signing/CI secrets reviewed; no private signing material in Git. *(Evidence: libs.versions.toml, SecurityGateAcceptanceTest.threatT16, zero keystores committed)*
- [x] Security tests include hostile inputs and races, not just happy-path assertions. *(Evidence: ProtocolFuzzAndAbuseTest, AndroidSecurityHardeningTest, SecurityGateAcceptanceTest)*
- [x] Residual risk documented with owner; Critical/High unresolved issues block release. *(Evidence: GLIDE_THREAT_MODEL.md, GLIDE-050-security-review-gate-report.md)*

V1 does not advertise malware scanning, anonymous radio presence, guaranteed OS survival, or end-to-end identity stronger than the reviewed transport/user verification. [Release gate](../DEFINITION_OF_DONE.md).
