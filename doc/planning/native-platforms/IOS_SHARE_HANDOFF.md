# iOS share handoff recovery

This is an implementation record, not signed-device or runtime validation. The containing app must never start a transfer just because files arrive from the Share Sheet.

## Implemented source

- `ShareViewController` delegates to one serial provider-import worker. Only one provider load proceeds at a time. The provider URL is copied before its completion callback returns. A stalled provider has a 120-second wait deadline; a file copy checks cancellation between bounded 64 KiB chunks.
- Cancel signals the active provider's `Progress`, interrupts copying, and discards the unpublished batch. Worker ownership prevents cleanup racing a copy. Publication and cancellation are ordered; an already published batch is a completed handoff, not an outbound transfer.
- `AppGroupShareQueue.PendingBatch` opens sources with `O_NOFOLLOW`, checks the open descriptor is a regular file, applies item/batch limits before copying, checks limits during streaming, and rejects a final length that differs from the source's initial length. The manifest appears only after all items are staged.
- `StagedFilesStore` owns selected-document security-scope leases until removal, serializes queue mutations off the main actor, and publishes bounded metadata snapshots. The UI uses cached sizes. The view model orders pending imports/removals so activation cannot race a Clear action.
- Send and Receive cannot start while staging is pending, and selection cannot mutate during an active transfer. Multi-row deletion captures all URLs before mutating the list.

Apple specifies that provider completion callbacks use an internal queue and that temporary file representations must be copied within the callback: [NSItemProvider](https://developer.apple.com/documentation/foundation/nsitemprovider), [file representations](https://developer.apple.com/documentation/foundation/nsitemprovider/loadfilerepresentation%28fortypeidentifier%3Acompletionhandler%3A%29).

## Remaining implementation

1. Replace the Photos picker `Data` load and unconditional `.jpg` temporary filename with bounded file representations preserving the actual media type. Keep copied sources private, clean them up on removal/cancellation, and enforce limits across the selected batch.
2. Wire the authenticated Apple engine, listener, consent, cancellation and verified history before enabling transfers. Document leases alone do not implement a transfer.
3. Add target-host runtime coverage for App Group access using matching signed app/extension provisioning. Unsigned simulator compilation does not establish entitlement behavior.

## Phase 2 cases (not executed)

- Share one file, 1,000 files, an empty file, Unicode names, a large video and a provider-backed document. Reject 1,001 items, oversize items and batch overruns.
- Cancel while waiting for a provider, during copying and immediately before publication; verify no incomplete batch is imported and late callbacks cannot republish it.
- Close the extension during import, relaunch Glide, and verify incomplete batches remain hidden and stale entries are cleaned by the retention policy.
- Exercise a provider that errors, never completes, replaces its source with a link, or changes source length. Verify failure without a transfer or visible partial file.
- Remove multiple nonadjacent rows; Clear during a pending activation import; repeatedly activate the app. Verify no duplicate files or unexpected reappearance after successful removal.
- Send picker-selected files after leaving the picker; verify their security-scope leases survive the transfer and are released afterward. Exercise provider revocation and disk-full recovery.
- Walk the extension and Send screen with VoiceOver, large text and keyboard access. Confirm Cancel stays responsive during a large copy.
