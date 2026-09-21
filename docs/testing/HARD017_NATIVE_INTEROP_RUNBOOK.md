# HARD-017 Native Cross-Platform Interoperability Runbook

This runbook is the execution contract for HARD-017. It does **not** permit loopback, emulator-only, source-text, unit-test, or historical evidence to substitute for the required real Android, Windows and Linux paths.

## 1. Required real platforms

Use:

- one **physical Android** device running the exact APK captured for this run;
- one **Windows 11** host running the exact Glide desktop/CLI binary captured for this run;
- one supported **Linux** host (Ubuntu 22.04+/Fedora 40+ active scope) running the exact Glide CLI/daemon binary captured for this run.

All three endpoint captures must report the same repository HEAD. Retain the endpoint JSON files under:

```text
<evidence>/
  endpoints/
    android.json
    windows.json
    linux.json
```

Capture each endpoint with:

```powershell
pwsh -NoProfile -File tools/capture-hard017-endpoint.ps1 ...
```

The Android capture rejects emulators.

## 2. Deterministic corpus

Generate the corpus once from the exact checkout:

```powershell
pwsh -NoProfile -File tools/new-hard017-corpus.ps1 -OutputDir <evidence>/corpus
```

Default corpus characteristics:

- 35 total items;
- 32 deterministic small binary items;
- one zero-byte item;
- one 10 MiB item;
- one >=100 MiB item;
- at least two offer pages because desktop V1 pages at 32 items;
- per-item SHA-256 in `corpus-manifest.json`.

Copy the exact corpus to each platform that acts as sender. Verify the copied input hashes before testing.

## 3. Six mandatory success directions

Every direction below must transfer the full corpus and end with truthful successful batch status:

1. `android-to-windows-success`
2. `windows-to-android-success`
3. `android-to-linux-success`
4. `linux-to-android-success`
5. `windows-to-linux-success`
6. `linux-to-windows-success`

For each run retain:

- sender log;
- receiver log;
- run JSON based on `docs/testing/evidence/HARD017_RUN_TEMPLATE.json`;
- exact sender/receiver artifact hashes;
- repository HEAD;
- start/end UTC timestamps;
- item count and observed offer page count;
- receiver-side hashes for every committed item;
- confirmation that every item hash equals the corpus manifest;
- terminal batch status.

A success run is invalid if it only tests one file or one offer page.

## 4. Mandatory negative/recovery matrix

Each unordered platform pair must also prove four behaviors.

### Android ↔ Windows

- `android-windows-reject`
- `android-windows-cancel`
- `android-windows-wrong-auth`
- `android-windows-reconnect`

### Android ↔ Linux

- `android-linux-reject`
- `android-linux-cancel`
- `android-linux-wrong-auth`
- `android-linux-reconnect`

### Windows ↔ Linux

- `windows-linux-reject`
- `windows-linux-cancel`
- `windows-linux-wrong-auth`
- `windows-linux-reconnect`

The direction inside a negative pair may be chosen to exercise the most relevant implementation boundary, but the retained run JSON must name the actual sender and receiver.

### Reject

The receiver must explicitly reject the offer.

Required proof:

- `offerRejected=true`;
- terminal batch status is **not** success;
- `noUnverifiedCommit=true`;
- sender and receiver logs retained.

### Cancel

Cancel after payload streaming begins, preferably during a non-first item.

Required proof:

- `cancelObserved=true`;
- terminal batch status is **not** success;
- no partial/unverified file is visible as a committed result;
- already verified earlier items may remain if protocol semantics permit them.

This case is also used to verify that a failed item/session cannot be reported as a successful batch.

### Wrong authentication

Use a deliberately invalid authentication attempt appropriate to the route, such as a tampered SPKI pin, wrong invitation token/hash, replayed consumed invitation, or Linux daemon connection outside the configured authorization boundary.

Required proof:

- `authenticationRejected=true`;
- `payloadBytesAuthorized=0`;
- terminal batch status is **not** success;
- logs identify the rejection boundary without exposing the bearer secret.

Do not weaken pin/token validation merely to create this test.

### Reconnect

After a reject/cancel/auth failure, establish a **new session with a fresh invitation** and complete a real transfer.

Required proof:

- `freshInvitation=true`;
- `previousSessionTerminalNotSuccess=true`;
- reconnect terminal status is success;
- committed file hashes match the selected source corpus.

Reusing a consumed invitation is not reconnect success.

## 5. Pairing and transport rules

For unpaired native LAN:

- mDNS is discovery/hint only;
- a fresh invitation/QR is authentication authority;
- TLS must negotiate `glide-lan/1`;
- sender verifies the receiver SPKI from the invitation;
- bootstrap invitation is one-use;
- receiver consent/policy remains separate from authentication.

No raw host/port bypass or plaintext fallback is allowed.

For the HARD-016 Linux daemon:

- use the explicit policy file;
- retain the policy file without any active bearer token;
- do **not** copy the enrollment URI/token into evidence logs;
- record the allowlisted peer IP and destination/size limits separately;
- verify the active enrollment file disappears after use/rotation/shutdown.

## 6. Android preflight that does not count as HARD-017 proof

The repository contains:

`app/src/androidTest/kotlin/dev/glide/app/transfer/LanDeviceTransferSmokeTest.kt`

It exercises real Android TLS/Keystore behavior, pin failure and replay handling, but its transfer path is device-local loopback. It is useful before the physical matrix but **cannot** satisfy Android↔Windows or Android↔Linux evidence.

## 7. Desktop/Linux preflight

Before physical runs, build/test the exact checkout:

```text
dotnet build windows/Glide.slnx -c Release
dotnet test windows/Glide.Tests/Glide.Tests.csproj -c Release --filter "HARD!=003-red"
dotnet test windows/Glide.Windows.UI.Tests/Glide.Windows.UI.Tests.csproj -c Release
pwsh -NoProfile -File tools/verify-windows-companion.ps1
pwsh -NoProfile -File tools/verify-linux-platform-spec.ps1
pwsh -NoProfile -File packaging/linux/build-linux-packages.ps1 -Configuration Release -Runtimes linux-x64
```

These are prerequisites only; green local tests do not replace the real matrix.

## 8. Evidence directory shape

Recommended:

```text
<evidence>/
  endpoints/
    android.json
    windows.json
    linux.json
  corpus/
    corpus-manifest.json
    ... generated payloads ...
  runs/
    android-to-windows-success.json
    android-to-windows-success/
      sender.log
      receiver.log
    ...
```

Run JSON file names must exactly match the 18 required IDs used by the verifier.

## 9. Final audit

After all retained evidence is present:

```powershell
pwsh -NoProfile -File tools/verify-hard017-evidence.ps1 -EvidenceDir <evidence>
```

The verifier fails when:

- an endpoint identity is missing;
- Android is not represented by a physical-device capture;
- repository HEADs differ;
- artifact hashes are malformed/mismatched;
- corpus does not force a multi-page offer;
- any of the six success directions is missing;
- any of the 12 negative/reconnect cases is missing;
- required logs are missing/empty;
- a reject/cancel/auth failure reports successful batch status;
- wrong authentication authorizes payload bytes;
- success/reconnect lacks byte-identical hash proof.

A passing structural audit is still subject to human review of the logs. The script validates completeness/consistency, not authenticity by itself.

## 10. HARD-017 completion rule

HARD-017 can become `COMPLETE` only when:

- HARD-012 through HARD-016 dependencies are certified;
- all six real success directions pass;
- all required negative/reconnect cases pass;
- hashes/logs/build identities are retained;
- no failed item/session yields a successful batch outcome;
- reviewers confirm the captured evidence came from the named real platforms and exact artifacts.

Until then the task remains blocked/review-gated regardless of source implementation or loopback test results.
