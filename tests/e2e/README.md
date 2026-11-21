# Glide 1.0.0 Opaque-Box E2E Test Suite (`tests/e2e`)

Requirement-driven, opaque-box 4-tier E2E test suite for **Glide 1.0.0** across Windows, Linux (Ubuntu 26 VM), and Android (`Pixel_10a` emulator).

## Architectural Principles

- **Zero Internal API Coupling**: `Glide.E2E.Tests` has **no** `<ProjectReference>` to `Glide.Core`, `Glide.Desktop`, `Glide.Transport`, `Glide.Windows.UI`, `Glide.Linux.UI`, or Android modules.
- **Process & Wire Boundary**: Exercises built binaries (`Glide.App`, `Glide.BackendTestDriver`, `Glide.Windows.UI`, `Glide.Linux.UI`, Android APK) and independent TLS 1.3 (`ALPN: glide-lan/1`) + `LanRecord` + `GLD2` wire framing (`WireClientProbe`).
- **4-Tier Methodology (145 Cases)**:
  - **Tier 1 (`E2E-T1-001`..`E2E-T1-060`)**: Category-Partition feature coverage (5 cases × 12 user-facing features `UF1`–`UF12`).
  - **Tier 2 (`E2E-T2-001`..`E2E-T2-060`)**: Boundary Value Analysis & Adversarial Faults (5 cases × 12 user-facing features `UF1`–`UF12`).
  - **Tier 3 (`E2E-T3-001`..`E2E-T3-016`)**: Pairwise Cross-Feature Combinatorial Scenarios (16 cases).
  - **Tier 4 (`E2E-T4-001`..`E2E-T4-009`)**: Real-World Multi-Platform Workload & Lifecycle Scenarios (9 cases).
- **Strict Honesty & Target Gating**:
  - `DryRun`: Discovers and validates all 145 test IDs without running live transfers.
  - `LocalLoopback`: Runs `Target=Loopback` tests in isolated per-test temp sandboxes with ephemeral ports (`--port 0` / dynamic ports) and independent SHA-256 verification. Marks `WindowsUi`, `LinuxVm`, `AndroidEmulator`, and `CrossPlatform` explicitly as `NOT_RUN`.
  - `LiveVm` / `LiveAndroid`: Probes SSH/ADB reachability and **fails closed** if unreachable.
  - `All`: Runs `Loopback` plus reachable live targets; unavailable targets are explicitly reported as `SKIPPED_TARGET_UNAVAILABLE`.
- **Host Isolation & Token Privacy**:
  - Windows loopback tests never mutate `%LOCALAPPDATA%\Glide` or invoke `Glide.App identity rotate`.
  - Linux VM invocations isolate `HOME`, `XDG_CONFIG_HOME`, `XDG_DATA_HOME`, `XDG_STATE_HOME`, `XDG_CACHE_HOME`, and `XDG_RUNTIME_DIR` (`0700`).
  - Pairing URIs (`glide://pair/v1#...`, `glide://pair/v2#...`) are passed via stdin pipes and redacted from logs and `summary.json`.

## Usage

```powershell
# 1. Dry-run discovery (verifies build + all 145 test IDs)
pwsh -File tests/e2e/run-e2e.ps1 -Mode DryRun

# 2. Local loopback suite (safe on Windows host; zero %LOCALAPPDATA%\Glide mutations)
pwsh -File tests/e2e/run-e2e.ps1 -Mode LocalLoopback

# 3. Run a specific tier or test ID
pwsh -File tests/e2e/run-e2e.ps1 -Mode LocalLoopback -Tier 2
pwsh -File tests/e2e/run-e2e.ps1 -Mode LocalLoopback -TestId E2E-T2-003

# 4. Run live Ubuntu 26 VM tests (requires SSH key auth & TCP:22 reachable)
pwsh -File tests/e2e/run-e2e.ps1 -Mode LiveVm -VmHost 192.168.0.245 -VmUser "Ubuntu 26"

# 5. Run live Android emulator tests (requires adb device ready)
pwsh -File tests/e2e/run-e2e.ps1 -Mode LiveAndroid -AndroidSerial emulator-5554

# 6. Run all available targets (marks offline targets SKIPPED_TARGET_UNAVAILABLE)
pwsh -File tests/e2e/run-e2e.ps1 -Mode All
```

## Outputs

Every run emits TRX results and a redacted summary report at:
`build/test-evidence/e2e/<runId>/summary.json`
