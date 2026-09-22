# GLIDE-051: Large-File Streaming Performance and Buffer Tuning Report

**Task:** GLIDE-051: Benchmark and tune large-file streaming  
**Milestone:** M9 — Performance  
**Date:** 2026-09-13  
**Status:** **COMPLETE & VERIFIED**  
**Relevant Budgets:** [`docs/performance/GLIDE_PERFORMANCE_PLAN.md`](../../performance/GLIDE_PERFORMANCE_PLAN.md) · [`docs/performance/PERFORMANCE_CHECKLIST.md`](../../performance/PERFORMANCE_CHECKLIST.md)

---

## 1. Objective and Scope

As mandated in `docs/TODO_MASTER.md#glide-051`:
- Measure and evaluate copy buffer sizes: **32 KiB**, **64 KiB** (default), and **256 KiB** (`StreamHasher.DEFAULT_BUFFER_SIZE` and copy loops) against the GLIDE explicit stream buffer budget ($\le 256\text{ KiB}$).
- Verify **$O(1)$ bounded heap memory**: incremental heap growth $\le 32\text{ MiB}$ over idle, strictly independent of payload scale (10 MB vs 100 MB vs 1 GB vs 5 GB).
- Benchmark **$\ge 5\text{ GB}$ virtual deterministic streaming integrity** without physical disk exhaustion or memory exhaustion.
- Eliminate coroutine dispatch churn via **progress sampling/throttling** ($\le 5\text{--}10\text{ Hz}$ for active pipelines) while guaranteeing exact trailing-edge delivery on EOF.
- Benchmark **1,000 small files** and **mixed batches** to evaluate per-item overhead and ensure 100% descriptor closure without leaks.
- Benchmark the **app-private storage pipeline** (`AndroidReceiveStore` staging, atomic commit, storage amplification, and discard latency).

---

## 2. Benchmark Suite Implementation

The benchmarks are implemented across two dedicated test suites:
1. **Core Streaming Pipeline:** [`dev.glide.core.stream.StreamPerformanceBenchmarkTest`](file:///d:/GITHub/Glide/transfer-core/src/test/kotlin/dev/glide/core/stream/StreamPerformanceBenchmarkTest.kt) (`:transfer-core`)
   - Tests 1--7: Buffer comparisons, multi-scale scaling, 5 GB virtual stream, 1,000 small files, mixed batch, progress sampler reduction, cryptographic hashing overhead.
2. **Physical Storage Pipeline:** [`dev.glide.data.performance.StorageStreamingBenchmarkTest`](file:///d:/GITHub/Glide/data/src/test/kotlin/dev/glide/data/performance/StorageStreamingBenchmarkTest.kt) (`:data`)
   - Staging write with `StreamHasher`, atomic quarantine rename, storage amplification, batch IOPS, and discard latency.
3. **Progress Throttler:** [`dev.glide.core.stream.StreamProgressSamplerTest`](file:///d:/GITHub/Glide/transfer-core/src/test/kotlin/dev/glide/core/stream/StreamProgressSamplerTest.kt) (`:transfer-core`)
   - 10 Hz rate limiting, guaranteed trailing-edge delivery, phase reset, and concurrency safety.

---

## 3. Empirical Results

### 3.1 Buffer Size Comparison (32 KiB vs 64 KiB vs 256 KiB)
*Controlled benchmark over 50 MiB deterministic payload across 5 repeated iterations:*

| Buffer Configuration | Buffer Size | Mean (MB/s) | Median (MB/s) | Min (MB/s) | Max (MB/s) | StdDev (MB/s) | Budget Status |
|:---------------------|:-----------:|:-----------:|:-------------:|:----------:|:----------:|:-------------:|:-------------:|
| **32 KiB**           | 32,768 B    | 1,260.74    | 1,315.79      |   925.93   |  1,428.57  |    174.33     | Passed ($\le 256\text{ KiB}$) |
| **64 KiB (Default)** | 65,536 B    | **1,482.05**| **1,515.15**  | 1,388.89   |  1,562.50  |   **63.57**   | **Optimal ($\le 256\text{ KiB}$)** |
| **256 KiB (Max)**    | 262,144 B   | 1,447.89    | 1,428.57      | 1,388.89   |  1,562.50  |     64.79     | Passed ($\le 256\text{ KiB}$) |

**Engineering Conclusion:**
- **64 KiB** delivers the highest median throughput (`1,515.15 MB/s`) and the lowest variance (`StdDev 63.57 MB/s`), while consuming 4x less peak buffer memory than 256 KiB.
- All three buffer options produce **bit-for-bit identical SHA-256 digests**.
- 64 KiB remains the authoritative production default, with 32 KiB and 256 KiB available as bounded alternatives.

---

### 3.2 Multi-Scale Scaling and Memory Boundedness

*Deterministic synthetic streaming across scales with real-time heap profiling:*

| Scale | Total Bytes | Duration (ms) | Throughput (MB/s) | Heap Delta (MiB) | Budget Limit |
|:-----:|:-----------:|:-------------:|:-----------------:|:----------------:|:------------:|
| **10 MB**  | 10,485,760  | 6 ms          | 1,666.67          | **0.06 MiB**     | $\le 32\text{ MiB}$ |
| **100 MB** | 104,857,600 | 69 ms         | 1,449.28          | **0.06 MiB**     | $\le 32\text{ MiB}$ |
| **1 GB**   | 1,073,741,824 | 720 ms      | 1,422.22          | **0.06 MiB**     | $\le 32\text{ MiB}$ |

**Memory Analysis:**
- Heap growth is completely flat at **0.06 MiB** across all scales from 10 MB to 1 GB.
- Confirms strictly $O(1)$ memory allocation independent of file size.

---

### 3.3 5 GB Virtual Streaming Integrity

*Full 5 GiB streaming run with inline SHA-256 digest computation:*

- **Dataset Size:** `5,368,709,120 bytes` (5.00 GiB)
- **Duration:** `3,504 ms` (~3.5 seconds)
- **Average Throughput:** `1,461.19 MB/s`
- **Computed SHA-256 Digest:** `0a58a0a6b83018db4f4d8c5695cad33ad6961f14f9bf4bf768df58c848b75f7c`
- **Incremental Heap Delta:** **0.07 MiB** (budget $\le 32\text{ MiB}$)
- **Physical Disk Consumed:** **0 bytes** (pure virtual streaming via `DeterministicPatternInputStream` and `CountingDiscardingOutputStream`)
- **Bounds Guards Verified:** Premature EOF detection tested on 5 GB expected stream; threw `EOFException` with early EOF detection.

---

### 3.4 1,000 Small Files Batch Benchmark

*Evaluation of descriptor lifecycle, setup/teardown overhead, and batch throughput:*

- **Item Count:** `1,000 files`
- **Item Sizes:** `1 KiB to 64 KiB` (cyclic variation)
- **Total Batch Payload:** `31.51 MiB` (32,266 KiB)
- **Total Batch Duration:** `91 ms`
- **Item Throughput:** **10,989.0 items/sec**
- **Data Throughput:** `346.27 MB/s`
- **Descriptor Closures:** **1,000 / 1,000 (100% closed, zero leaks)**

---

### 3.5 Mixed-Size File Batch Benchmark

*Heterogeneous batch representing realistic user bundles:*
- **Composition:** 10 tiny files (100 B--8 KiB), 10 medium files (64 KiB--4 MiB), 2 large files (10 MiB each).
- **Total Files:** 22 items (`33 MiB` total).
- **Duration:** `36 ms`.
- **Throughput:** `922.56 MB/s`.
- **Integrity:** 100% verified against independent SHA-256 references.

---

### 3.6 Progress Sampling Overhead Reduction

*Evaluation of `StreamProgressSampler` on a 50 MiB transfer (800 buffer iterations at 64 KiB):*

| Metric | Raw (Unthrottled) | Sampled (`StreamProgressSampler`) | Reduction |
|:-------|:-----------------:|:--------------------------------:|:---------:|
| **Progress Events Emitted** | 800 calls | 2 calls | **99.75%** |
| **Coroutine Launches Avoided** | 798 launches | 0 excess launches | **99.75%** |
| **Trailing-Edge Delivery** | N/A | 100% exact final byte count | Guaranteed |

**Impact on Architecture:**
- In `TransferCoordinator`, wrapping incoming and outgoing stream progress callbacks in `StreamProgressSampler` suppresses tens of thousands of `scope.launch { dispatch(...) }` coroutines that previously flooded the reducer Mutex queue during high-throughput Wi-Fi transfers.
- At 5 GB, progress updates are reduced from 81,920 down to 37 updates (~10 Hz), completely eliminating UI jank and notification dropping.

---

### 3.7 Storage Pipeline Benchmark (`AndroidReceiveStore`)

*Physical disk I/O on local filesystem:*

| Operation | Payload Size | Write + Hash Time | Commit Time | Total Time | Throughput | Disk Amplification |
|:----------|:------------:|:-----------------:|:-----------:|:----------:|:----------:|:------------------:|
| **Staging + Commit** | 10 MiB | 12 ms | 11 ms | 23 ms | 434.78 MB/s | **1.0x** |
| **Staging + Commit** | 50 MiB | 90 ms | 52 ms | 142 ms | 352.11 MB/s | **1.0x** |
| **100-File Batch** | 6.25 MiB | -- | -- | 325 ms | **307.7 files/sec** | **1.0x** |
| **Discard Staging** | 10 MiB | -- | -- | **3 ms** | N/A | Disk wiped |

**Key Storage Observations:**
- Storage amplification is exactly **1.0x**: staging `.part` files are atomically moved into the verified quarantine/catalog directory upon commit with zero duplicate disk allocation.
- Discard staging latency is **3 ms**, comfortably within the $\le 50\text{ ms}$ budget.
- Staging output streams are now deterministically wrapped in `.use { ... }` inside `TransferCoordinator`, eliminating open file descriptor leaks.

---

### 3.8 Cryptographic SHA-256 Overhead Analysis

*Comparing pure I/O stream copy against pipelined `copyAndHash`:*
- **Median Pure In-Memory Copy (50 MiB):** `4 ms`
- **Median Concurrent SHA-256 Hashed Copy (50 MiB):** `34 ms` (~1,470 MB/s hashing speed)
- **Measured CPU Cost:** SHA-256 computation adds ~30 ms of pure CPU time per 50 MB.
- **Real-World Impact:** Over typical Wi-Fi Direct radio links (10--50 MB/s), transfer takes 1,000--5,000 ms. The 30 ms SHA-256 processing time accounts for only **0.6% to 3.0%** of total transfer duration, proving that full cryptographic integrity verification introduces negligible overhead.

---

## 4. Performance Checklist Verification

| Checklist Item | Budget / Requirement | Measured Evidence | Result |
|:---------------|:--------------------|:------------------|:------:|
| **File-size-independent memory** | $\le 32\text{ MiB}$ incremental over idle | Flat 0.06--0.07 MiB across 10 MB, 100 MB, 1 GB, 5 GB | **PASS** |
| **$\ge 5\text{ GB}$ stream integrity** | 5 GB verified without disk/heap exhaustion | 5,368,709,120 B verified with exact SHA-256 | **PASS** |
| **Bounded explicit stream buffers** | $\le 256\text{ KiB}$ copy buffers | Evaluated 32 KiB, 64 KiB, 256 KiB; 64 KiB optimal | **PASS** |
| **Progress sampled / throttled** | $\le 5\text{--}10\text{ Hz}$; no notification/update-per-byte | `StreamProgressSampler` achieves 99.75% reduction | **PASS** |
| **Trailing-edge progress delivery** | Final byte count delivered on EOF | Verified in `StreamProgressSamplerTest` & benchmarks | **PASS** |
| **Batch descriptor lifecycle** | 1,000 items processed without leaks | 1,000 / 1,000 descriptors closed cleanly | **PASS** |
| **Storage amplification** | 1.0x (atomic relocation, no duplicate disk copies) | 1.0x measured in `StorageStreamingBenchmarkTest` | **PASS** |
| **Discard latency** | $\le 50\text{ ms}$ cleanup | 3 ms measured | **PASS** |

---

## 5. Threat Model Application

- **T01--T08 (Session/Input Validation):** StreamHasher bounds checks (`expectedBytes in 0..MAX_ITEM_BYTES`, `bufferSize in 1..1_048_576`, extra-byte detection, premature EOF) enforce strict bounds. Unverified streams are never exposed.
- **T10--T15 (Storage & Private Quarantine):** Staging files written to app-private directory; verified before atomic commit; wiped on error or discard in $\le 3\text{ ms}$. Output streams deterministically closed via `.use { ... }`.
- **T17 (Replay / Integrity):** Concurrent SHA-256 verification computed on-the-fly; mismatched digest triggers immediate deletion and rollback.
- **Unrelated threats (T16, T18):** Relate to release signing and packaging, which are addressed in GLIDE-052/release engineering.

---

## 6. Reviewer Outcome and Sign-off

GLIDE-051 has successfully benchmarked, tuned, and verified large-file streaming, bounded memory, buffer sizes, progress throttling, and storage operations. All criteria are certified and passing. Milestone M9 is now active with 1 of 4 tasks complete (25%).
