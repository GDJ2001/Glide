# Windows release gates

All checks must run against the same versioned, Authenticode-signed Windows 11 x64 candidate.
Attach command output, artifact hash, device/VM identity, date, tester, and result to the release
dossier. A failure, missing result, or substituted binary blocks release.

## Automated gate

- Build the full `desktop/Glide.slnx` Release solution with warnings triaged.
- Run every desktop test without filters that suppress failures.
- Add tests for runtime ownership, queues, folder paths, reparse points, bounded streaming,
  hash mismatch, state migration, corruption recovery, resume journals, and trust revocation.
- Exercise update failures: malformed/wrong-signed appcast, modified installer, stale/downgraded
  version, unavailable feed, interrupted download, and installer failure.
- Execute Windows UI automation for send, receive consent, cancellation, trust, history, settings,
  URI/file activation, and second-instance forwarding.

## Native transfer gate

Run Windows↔Windows and Windows↔Android, each direction, on independently running devices:

| Case | Required observation |
|---|---|
| Empty, 10 MB, 100 MB, 1 GB, 5+ GB | Output SHA-256 matches; UI progress/history truthful. |
| Nested folders, Unicode, duplicates, thousands of files | Relative paths preserved; collision policy keeps both; no traversal or memory growth. |
| Wi-Fi loss, sleep/wake, application restart | State is clear; actual supported resume validates source, peer, chunk progress and final hash. |
| Disk exhaustion/source modification | Fail closed, stage cleanup/retention behavior correct, no false success. |
| Peer change/revocation | Pairing/trust fails closed and requires conscious recovery. |

## Windows lifecycle gate

On clean standard-user and administrator Windows 11 VMs test: default/custom paths, spaces and
non-ASCII paths, denied firewall change, active transfer during exit/update, upgrade from prior
version, rerun-installer repair, and uninstall. Uninstall must retain received data and unrelated
files placed in the install directory. Record installer logs and exit codes.

## Security and privacy gate

- Review exported components, URI handling, named-pipe bounds/current-user restriction, invitation
  redaction, log/diagnostic redaction, staging permissions, identity/trust protection, and update
  signature chain.
- Verify no appcast/download accepts HTTP, unknown public keys, missing signatures, a tampered
  package, or a downgrade.
- Verify no cloud account, tracker, contact/location collection, or unsolicited telemetry is added.

## Experience and performance gate

- Keyboard-only, Narrator, high-contrast, 200% DPI, dark/light/system theme, focus order and
  transfer status that does not rely on color alone.
- Cold launch usable within three seconds on the named reference PC; ordinary input under 100 ms.
- No UI-thread network/file I/O; progress at roughly five updates per second; buffer memory bounded
  independent of input size; repeated transfer/connect cycles show no sustained resource growth.

## External release blockers

NSIS 3.12+, an Authenticode identity, Ed25519 update key, publisher metadata, and administrative
access to the public release repository are mandatory. Keep their secret material outside source
control. Until those prerequisites and the gates above are complete, the release remains a
development candidate rather than a shippable Windows application.
