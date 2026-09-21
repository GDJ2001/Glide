# Glide — Code Quality & Maintainability Report

**Date:** 2026-09-21  
**Scope:** Full codebase (Android/Kotlin, .NET/C#, Apple/Swift)  
**Method:** Read-only static analysis — no files modified

---

## Executive Summary

Glide is a well-structured, multi-platform file-sharing project with strong test coverage (204 test files, ~50K test lines vs ~49K source lines) and a clean modular architecture. The codebase demonstrates mature engineering practices: sealed classes for state modeling, coroutine-based concurrency, Hilt DI on Android, and a clear separation between transfer-core, transports, and platform layers.

**Key strengths:**
- Zero TODOs/FIXMEs/HACKs in production code
- No hardcoded secrets or credentials
- Strong test-to-code ratio (~1:1)
- Consistent use of sealed classes (41) and data classes (290) for domain modeling
- Good documentation (616 KDoc comments, 700+ single-line comments, ADRs, architecture docs)

**Primary concerns:**
- Several god files (>800 lines) with mixed responsibilities
- Inconsistent error handling patterns across platforms
- Magic numbers scattered without named constants
- Inconsistent logging infrastructure

---

## Findings by Severity

### HIGH — Actual Defects

#### H1. Swallowed Exceptions in Production Code
**Files affected:** 15+ files across `app/`, `data/`, `transfer-core/`

Multiple `catch` blocks silently discard exceptions without logging or re-throwing:

```kotlin
// app/src/main/kotlin/dev/glide/app/service/TransferService.kt:323
} catch (_: Throwable) {}

// app/src/main/kotlin/dev/glide/app/ui/discovery/DiscoveryScreen.kt:346
} catch (_: Exception) {}

// app/src/main/kotlin/dev/glide/app/ui/haptics/AndroidGlideHaptics.kt:40
} catch (_: Throwable) {}
```

**Impact:** Failures become invisible. A security exception, I/O error, or cancellation is silently swallowed, making debugging extremely difficult. In a file-transfer app, silent I/O failures could lead to data corruption or incomplete transfers.

**Recommendation:** At minimum, log the exception. Prefer re-throwing or propagating via a result type.

---

#### H2. Empty Catch Blocks in Resource Cleanup
**Files:** `TransferCoordinator.kt:306,322,412,419,494`, `ProductionLanController.kt`

```kotlin
// transfer-core/src/main/kotlin/dev/glide/core/coordinator/TransferCoordinator.kt:306
try { hashingStream.close() } catch (_: Exception) {}
```

**Impact:** While acceptable in `finally` blocks for cleanup, the pattern is overused. Some of these catch real errors (e.g., `close()` on a failed stream may throw the original I/O error).

**Recommendation:** Use Kotlin's `use { }` extension or `runCatching { }.onFailure { log }` for cleanup paths.

---

### MEDIUM — Maintainability Problems

#### M1. God Files with Mixed Responsibilities

| File | Lines | Issue |
|------|-------|-------|
| `app/.../ui/settings/SettingsScreen.kt` | 1,356 | UI + state management + navigation + business logic |
| `app/.../MainActivity.kt` | 1,093 | Activity + debug state injection + navigation + lifecycle |
| `app/.../service/TransferRuntime.kt` | 988 | Service lifecycle + transfer coordination + pairing + state |
| `app/.../ui/selection/SelectionScreen.kt` | 938 | UI + file staging + batch logic + web share |
| `app/.../ui/transfer/TransferResultCard.kt` | 896 | 4 distinct result states in one composable |
| `transfer-core/.../coordinator/TransferCoordinator.kt` | 896 | Protocol + transport + stream + history + staging |
| `transfer-core/.../coordinator/TransferReducer.kt` | 839 | 20+ event handlers, pure but enormous |
| `windows/.../DesktopTransferEngine.cs` | 891 | Send + receive + session management |
| `windows/.../QrCodeGenerator.cs` | 876 | QR generation + ANSI rendering + validation |
| `windows/.../ViewModels/MainViewModel.cs` | 821 | ViewModel + navigation + state + commands |

**Impact:** High cognitive load, difficult to test in isolation, prone to merge conflicts.

**Recommendation:** Extract business logic from UI files. Split `TransferCoordinator` into protocol handler, stream manager, and history recorder. Break `TransferResultCard` into per-state composables.

---

#### M2. Inconsistent Error Handling Patterns

The codebase uses **three distinct error-handling strategies** without clear boundaries:

1. **Exceptions** — `ProtocolViolationException`, `SourceAccessException`, `ReceiveStoreException`, `InvitationException`
2. **Result<T> type** — `Result<Unit>`, `Result<String>`, `Result<Int>` in settings/store ports
3. **Sealed class outcomes** — `ShareIngressResult`, `NearbyHardwareStatus`, `TransferFailure`

```kotlin
// Result type used in settings
suspend fun updateDeviceName(name: String): Result<Unit>

// Exception used in protocol
throw ProtocolViolationException(...)

// Sealed class used in ingress
data class InvalidScheme(val uriString: String, val scheme: String?) : ShareIngressResult
```

**Impact:** Callers must handle three different error paradigms. Easy to miss a path.

**Recommendation:** Standardize on `Result<T>` for recoverable errors and exceptions for unrecoverable/protocol violations. Document the convention.

---

#### M3. Magic Numbers Without Named Constants

```kotlin
// app/.../discovery/ProductionDiscoveryController.kt:132
for (second in 1..120) { delay(1_000L) }  // 120 seconds = 2 minutes

// app/.../MainActivity.kt:109
startedAtMs = now - 180_000L  // 3 minutes ago

// app/.../MainActivity.kt:113
totalBytes = 15_200_000L  // 15.2 MB

// app/.../lan/ProductionLanController.kt:469
private const val NETWORK_READY_TIMEOUT_MS = 5_000L  // good example
```

**Impact:** Unclear intent. `180_000L` could be 3 minutes or a random value. Inconsistent — some files use named constants, others don't.

**Recommendation:** Extract to named constants: `const val DISCOVERY_TIMEOUT_SECONDS = 120`, `const val DEBUG_SESSION_AGE_MS = 180_000L`.

---

#### M4. Inconsistent Logging Infrastructure

| Platform | Pattern | Issue |
|----------|---------|-------|
| Kotlin/Android | `Log.i("GlideReconciler", ...)`, `Log.e("GlideOrphanMaintenance", ...)` | Ad-hoc tag strings, no unified logger |
| C#/.NET | `Console.WriteLine(...)`, `Debug.WriteLine(...)` | No structured logging |
| Swift | `print(...)` or none | No logging framework |

**Impact:** Cannot filter logs by severity or component. No correlation IDs for tracing transfers across platforms.

**Recommendation:** Introduce a lightweight `GlideLogger` interface with platform implementations. Use consistent tags: `Glide.Transfer`, `Glide.Discovery`, `Glide.Security`.

---

#### M5. Tightly Coupled App Module

The `app` module directly imports from **6 other modules**:
- `transfer-core` (coordinator, domain, ports, pairing, protocol)
- `transport-lan` (web, pairing)
- `transport-nearby` (discovery)
- `data` (db, maintenance, source)
- `designsystem` (theme, haptics)
- `core` (routing)

**Impact:** Cannot test app-layer logic without pulling in all transports. Changes to any module cascade.

**Recommendation:** Introduce a `GlideContainer` facade in `app` that exposes only the operations the UI needs, hiding module boundaries.

---

#### M6. Inconsistent State Management

| Pattern | Count | Usage |
|---------|-------|-------|
| `MutableStateFlow` | 47 | ViewModels, services |
| `StateFlow` | 64 | Exposed read-only state |
| `mutableStateOf` + `remember` | 112 | Compose UI local state |
| `LiveData` | 0 | Not used (good) |

The mix of `MutableStateFlow` in services and `mutableStateOf` in Compose is standard, but some ViewModels expose `MutableStateFlow` directly instead of `StateFlow`:

```kotlin
// TransferRuntime.kt:73 — mutable exposed
private val _transferState = MutableStateFlow<TransferUiState>(TransferUiState.Idle)
val transferState: StateFlow<TransferUiState> = _transferState.asStateFlow()
```

This is correct, but the pattern is not consistent — some classes expose the mutable version.

**Recommendation:** Enforce `val state: StateFlow<T> = _state.asStateFlow()` everywhere. Add a lint rule.

---

#### M7. Test-Only Code in Production

```kotlin
// TransferRuntime.kt:50
@Suppress("unused") private val testInstance: Boolean

// TransferRuntime.kt:54-58
@Inject constructor(...) : this(sessionFactory, discoveryController, lanController, false)
```

The `testInstance` flag and secondary constructor exist solely for testing. This leaks test concerns into production.

**Recommendation:** Use a test-specific Hilt module or `@TestInstallIn` to provide test doubles, keeping production code clean.

---

### LOW — Stylistic Preferences

#### L1. Naming Conventions Are Consistent
Classes use PascalCase, functions use camelCase, constants use UPPER_SNAKE_CASE. No violations found. Test methods use `snake_case_with_underscores` (e.g., `receiveQr_mustNotExposeHistoricalPlaceholderEndpoint`) — this is a deliberate, consistent choice.

#### L2. Class Naming Patterns
The codebase uses a consistent set of suffixes: `Controller`, `Manager`, `Service`, `Store`, `Factory`, `Builder`, `Handler`, `Provider`, `Coordinator`, `Repository`. No misleading names found.

#### L3. Sealed Class Usage
41 sealed classes/interfaces used appropriately for state modeling and result types. This is a strength.

#### L4. Data Class Usage
290 data classes — appropriate for DTOs, records, and immutable state.

#### L5. Object Singletons
209 `object` declarations — used for pure functions, constants, and stateless utilities. Appropriate.

---

## Top 10 Largest Files — Refactoring Priority

| Rank | File | Lines | Verdict |
|------|------|-------|---------|
| 1 | `SettingsScreen.kt` | 1,356 | **Refactor** — extract settings sections into sub-composables |
| 2 | `MainActivity.kt` | 1,093 | **Refactor** — extract debug state injection, move navigation to NavHost |
| 3 | `TransferRuntime.kt` | 988 | **Refactor** — split into TransferSessionManager + PairingManager |
| 4 | `SelectionScreen.kt` | 938 | **Refactor** — extract file list, batch actions, web share into sub-composables |
| 5 | `TransferResultCard.kt` | 896 | **Refactor** — split into CompletedCard, FailedCard, CancelledCard |
| 6 | `TransferCoordinator.kt` | 896 | **Refactor** — split into ProtocolHandler + StreamManager + HistoryRecorder |
| 7 | `DesktopTransferEngine.cs` | 891 | **Refactor** — split Send/Receive into separate classes |
| 8 | `QrCodeGenerator.cs` | 876 | **Keep** — single responsibility (QR generation), acceptable size |
| 9 | `MainViewModel.cs` | 821 | **Refactor** — extract commands, split state |
| 10 | `TransferReducer.kt` | 839 | **Keep** — pure reducer, size is acceptable for a state machine |

---

## High-Priority Cleanup Areas

1. **Exception handling audit** — Replace all `catch (_: Throwable) {}` with at least logging
2. **God file decomposition** — Start with `SettingsScreen.kt` and `TransferCoordinator.kt`
3. **Magic number extraction** — Create a `GlideConstants` file for timeouts, sizes, limits
4. **Logging standardization** — Introduce `GlideLogger` interface
5. **Error handling unification** — Pick one paradigm per layer

---

## Files Future Agents Must Treat as High-Blast-Radius

| File | Reason |
|------|--------|
| `transfer-core/.../coordinator/TransferCoordinator.kt` | Core transfer protocol — bugs affect all transfers |
| `transfer-core/.../coordinator/TransferReducer.kt` | State machine — incorrect transitions corrupt sessions |
| `transfer-core/.../protocol/ProtocolCodec.kt` | Wire format — breaks interoperability |
| `app/.../service/TransferRuntime.kt` | Service lifecycle — affects foreground service |
| `app/.../service/TransferService.kt` | Android service — process death, ANR risk |
| `app/.../lan/ProductionLanController.kt` | LAN transport — network security |
| `transport-lan/.../tls/PinnedTlsConnector.kt` | TLS pinning — security critical |
| `windows/.../DesktopTransferEngine.cs` | Desktop transfer — cross-platform interop |
| `windows/.../ProtocolCodec.cs` | Wire format — must match Kotlin version |
| `data/.../store/AndroidReceiveStore.kt` | File storage — data loss risk |

---

## What Was Read

- `MainActivity.kt` (100 lines)
- `TransferRuntime.kt` (100 lines)
- `TransferCoordinator.kt` (700 lines)
- `TransferReducer.kt` (500 lines)
- `SettingsScreen.kt` (100 lines)
- `SelectionScreen.kt` (100 lines)
- `TransferResultCard.kt` (100 lines)
- `DesktopTransferEngine.cs` (100 lines)
- `ProtocolCodec.cs` (100 lines)
- `QrCodeGenerator.cs` (100 lines)
- `GlideWebGateway.cs` (100 lines)
- Full codebase grep analysis (all modules)

## Assumptions Marked "Needs Verification"

- The `catch (_: Throwable) {}` blocks in `AndroidGlideHaptics.kt` and `ReducedMotionResolver.kt` may be intentional for non-critical UI feedback — **needs verification** with the original author.
- The `testInstance` flag in `TransferRuntime.kt` may be required by Hilt's injection graph — **needs verification** before removal.
- The magic numbers in `MainActivity.kt` debug state injection are test-only and may be acceptable — **needs verification**.

---

**No files were modified. This is a read-only diagnostic report.**
