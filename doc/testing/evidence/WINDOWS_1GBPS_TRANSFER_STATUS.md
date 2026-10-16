# Windows 1 Gbps transfer optimization: evidence status

This is a development status record, not a physical two-PC acceptance claim. The user-reported 50–60 MB/s ceiling and 4.5–5 GB slowdown remain open until the same two Windows PCs are measured together.

## Implemented in the current source

- The desktop transfer runtime owns background execution and coalesces UI progress to at most five updates per second. It retains terminal results separately from progress.
- Profile 1 DATA framing uses bounded pooled buffers and ordered writes. A timed batch-write failure is delivered to the next transfer write instead of being silently swallowed.
- The receiver writes into private staging, verifies the authenticated digest, independently rereads the file, and uses same-volume publication only when Windows volume and directory checks succeed. The verified source remains exclusively open through publication. The verified-copy fallback remains for ineligible destinations.
- Monotonic phase metrics and decimal MB/s distinguish payload transfer, verification, and commit. The benchmark reports missing quarter measurements as unavailable rather than zero or a fabricated ratio.

## Local automated evidence

- Architecture check: `pwsh -NoProfile -File tools/check-architecture.ps1 -FailFast` passed.
- Full desktop solution: `dotnet test desktop/Glide.slnx -c Release --no-restore --verbosity quiet` passed (382 shared tests and 4 Windows UI tests).
- A single 5,000,000,000-byte backend loopback transfer through staging, verification, and commit completed with independent SHA-256 `1627cbb7990eb7573295d216aab3bdb4bcd5ffec8e6b2a8fabad28e70596a6ae` on both ends. Payload rate was 289.92 MB/s, acceptance-to-verified rate 230.04 MB/s, and final-to-first quarter rate ratio 1.114. This loopback run does not measure LAN capacity, the installed UI, or the other PC's storage.
- A small four-path benchmark also completed with verified output. Path 4 uses a simulated WPF synchronization context; it is not an installed-app result.

## Open physical gate

Run the built installer on both Windows PCs, with the receiving folder on the same drive used by the 110 MB/s comparison. On each PC, start `tools/diagnose-desktop-transfer.ps1` with the same attempt ID and retain its CSV plus Glide diagnostic JSONL. Compare a 1 GB, 5 GB, 10 GB, and 20 GB transfer in each direction, including five repetitions of the principal large-file cases and a 30-minute stability run. Capture source and destination SHA-256, five-second payload windows, final-quarter versus first-quarter rates, phase timing, CPU, retransmissions, disk latency, memory, and terminal UI result. Repeat with the UI visible, minimized, and actively navigated. Record missing counters as unavailable.

The performance target is at least 95% of the independently measured comparison-app bulk rate, with a minimum 100 MB/s where hardware supports it; 95% of five-second windows should exceed 85% of that rate, and the final quarter should sustain at least 90% of the first steady quarter. Do not mark the late slowdown fixed unless matching two-PC traces explain or disprove it. The installer is unsigned development output and preserves existing app data on upgrade; retain a compatible previous installer for rollback.
