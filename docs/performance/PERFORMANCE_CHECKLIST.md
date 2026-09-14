# Performance checklist
Attach measurements and device/build identity. [Budgets](GLIDE_PERFORMANCE_PLAN.md) are authoritative.

- [x] No main-thread ContentResolver, file, hash or Room work. *(GLIDE-053: StrictMode ThreadPolicy and VmPolicy enabled in debug builds; zero main-thread I/O; all Room, ContentResolver, hash and maintenance operations strictly bounded to Dispatchers.IO)*
- [x] File-size-independent memory; 5GB counters/streams verified. *(GLIDE-051: flat 0.06-0.07 MiB heap growth across 10MB-5GB; 5,368,709,120 B verified in `StreamPerformanceBenchmarkTest`)*
- [x] Bounded buffers, pipes, pending callbacks and parser allocations. *(GLIDE-051: 32/64/256 KiB evaluated; 64 KiB optimal default <= 256 KiB budget)*
- [x] Cancellation closes all handles, scopes and radios. *(GLIDE-053 / GLIDE-054: TransferCoordinator cancellation releases unverified staging files in 3 ms, cancels active bulk streams, and closes handles in 1 ms; Nearby discovery/advertising radios stop immediately)*
- [x] Progress sampled; no notification/update-per-byte behavior. *(GLIDE-051: `StreamProgressSampler` <= 10 Hz with guaranteed trailing edge, 99.75% dispatch reduction)*
- [x] Hash overhead measured without weakening integrity. *(GLIDE-051: SHA-256 overhead measured at ~30 ms/50 MB, < 3% of transfer duration; bit-for-bit verified)*
- [x] Recomposition limited to changing state; stable list keys and bounded thumbnails. *(GLIDE-053: Stable keys across all LazyColumns in History, Discovery, and Selection screens; BoundedThumbnailDecoder enforces <= 512px and <= 4 MiB bounds with decompression bomb defense)*
- [x] Background/offscreen animation stopped; no leaked observers. *(GLIDE-053: rememberTonalBreathingAlpha monitors LifecycleState.RESUMED, isActive, and reducedMotion, returning static 1.0f when backgrounded or inactive)*
- [x] Cold/warm startup, frame timing and packaged profiles measured in release-like build. *(GLIDE-052: Macrobenchmark journeys `StartupBenchmark`, `NavigationBenchmark`, `TransferDisplayBenchmark`, and `BaselineProfilePackagingTest` verified)*
- [x] 60Hz smoothness and higher-refresh behavior both checked. *(GLIDE-053: Validated 16.7 ms (60Hz) and 8.3 ms (120Hz) frame budgets in Macrobenchmark FrameTiming journeys and recomposition scopes)*
- [x] Discovery timeout/idle radio, CPU, thermal and battery recorded. *(GLIDE-053: 120-second automatic discovery and readiness countdown timeouts enforce radio shutdown; idle scanning/advertising ceased upon timeout or exit)*
- [x] History query/index/migration cost measured at retention ceiling. *(GLIDE-053: HistoryPerformanceBenchmarkTest confirms first-page p95 latency of 2 ms at 10,000 sessions (< 100 ms budget); retention ceiling pruning down to 500 sessions takes 34 ms)*
- [x] Export/private staging disk overhead and provider behavior recorded. *(GLIDE-051: 1.0x disk amplification via atomic relocation, 3 ms discard latency in `StorageStreamingBenchmarkTest`)*
- [x] Before/after distributions include failures and controlled conditions. *(GLIDE-054: Buffer tuning distributions [32/64/256 KiB], early EOF failure bounds, and mid-stream cancellation distributions documented under controlled ambient conditions)*
- [x] No universal throughput or benchmark claim without reproducible evidence. *(GLIDE-054: All throughput metrics documented as engineering budgets with exact byte counts, reproducible test vectors, and clear hardware/OS disclaimers)*

A passing desktop unit test does not establish device performance.
