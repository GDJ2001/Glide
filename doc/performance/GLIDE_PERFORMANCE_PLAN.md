# Performance and transfer benchmark plan
Targets below are engineering budgets, not measurements or advertised speeds. [Test strategy](../testing/GLIDE_TEST_STRATEGY.md) · [Storage pipeline](../android/STORAGE_AND_BACKGROUND.md).

## Budgets
| Metric | Initial acceptance target | Measurement |
| --- | --- | --- |
| Cold startup to useful Home | p95<=1.5s midrange; <=2.5s low-end |20 release-like cold starts, no radio startup |
| Warm startup | p95<=700ms midrange |20 warm launches |
| UI rendering | >=95% frames within refresh budget; no sustained jank, no frozen frames in primary journeys | FrameTimingMetric/Perfetto at60/90/120Hz;60Hz budget16.7ms, no artificial cap |
| Transfer app heap growth | <=32MiB incremental over idle, independent of1GB versus5GB file | Heap/PSS traces; SDK/native memory separately tracked |
| App total PSS | Initial<=200MiB midrange during transfer | Compare idle/active and repeated sessions; device exceptions need evidence |
| Explicit stream buffers | <=256KiB copy buffers plus bounded queue | Inspect and profile allocation/backpressure |
| Main thread | Zero file/database/network blocking | StrictMode debug plus traces |
| Progress/UI / notification | <=5Hz / <=1Hz updates | Callback and render traces |
| CPU | Median<1 full core equivalent sustained on midrange, investigate higher | Report process CPU convention; includes hashing |
| Thermal/battery | No sustained severe thermal status or >20% throughput collapse in30min at controlled ambient | Thermal status, battery delta, screen/radio conditions |
| Idle discovery overhead | No advertising/scanning after readiness stop/background; timeout120s | Radio/callback trace |
| Database | History first page p95<100ms at10k sessions; off-main | Instrumented benchmark |
| Size | Initial release download target<30MiB per-device; investigate deltas>2MiB | AAB/bundletool report, no entire asset pack bundled |
| Transfer throughput | No universal MB/s promise; compare controlled baseline | STREAM>=70% FILE median on same pair1GB; gate for investigation |
| Cancellation | User state changes<=250ms; handles close/cleanup normally<=2s | Monotonic traces; document stalled provider exceptions |

Device-specific budget exceptions require reproducible evidence, cause, user impact and reviewer decision; they cannot waive integrity, main-thread I/O or accessible controls.

## Device-to-device benchmark
Use decimal10MB,100MB,1GB and5GB+ datasets (record exact byte count);1000 small files and mixed types. Use incompressible deterministic bytes so optimization cannot fake throughput. V1 max1000 per batch: test thousands as multiple1000-item batches. Record free space and private storage/export overhead independently.

Measure from Send through discovery, authentication user-wait excluded/reported separately, acceptance, first byte, EOF, hash/commit, receipt. Average bulk throughput=actual bytes/(last-first byte); effective throughput=bytes/(accepted-to-confirmed duration). Peak is best1s interval, not marketing headline. Include prep/export time as separate columns. Hash timing is part of pipeline cost, not omitted.

Five repetitions per size/mode/pair initially; report median/p95/min/max and failures, not best run. Reverse roles, test offline nearby and same-LAN opportunities, near/far distance with exact conditions, contention, low/mid/flagship across OEM/API. Capture CPU, PSS/heap, thermal status, battery change, buffer configuration, failures and independent hashes. Allow cool-down between controlled runs; keep screen/brightness/power source consistent. Battery comparisons need longer repeated runs, not a noisy single10MB sample.

## Optimization order
Prove correctness → measure release-like build → remove unbounded copies/leaks → tune buffers and progress sampling → reduce recomposition/thumbnail work → add Baseline Profiles → remeasure. Never remove hashing, consent or private staging to win a synthetic test.

Use Macrobenchmark for startup and UI journeys, Baseline Profile generation/package verification and Perfetto for hot paths. Profile discovery idle work and closed-session scopes. Shared fake transport supports deterministic UI profiling; label it clearly and run real radio tests separately. [Official benchmarking](https://developer.android.com/topic/performance/benchmarking/benchmarking-overview) and [Baseline Profiles](https://developer.android.com/topic/performance/baselineprofiles/create-baselineprofile).

