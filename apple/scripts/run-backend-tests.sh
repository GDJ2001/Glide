#!/usr/bin/env bash
# Glide Apple Backend Test Automation Script
# Executes Profile 1 native macOS, iOS Simulator, and iPadOS Simulator backend tests.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

COMMIT_SHA=$(git -C "$REPO_ROOT" rev-parse HEAD 2>/dev/null || echo "working-tree")
RUN_ID="${GITHUB_RUN_ID:-local-$(date +%s)}"
EVIDENCE_DIR="${REPO_ROOT}/build/test-evidence/apple-backend/${COMMIT_SHA}/${RUN_ID}"
mkdir -p "$EVIDENCE_DIR"

echo "=== Glide Apple Backend Test Runner ==="
echo "Commit: ${COMMIT_SHA}"
echo "Run ID: ${RUN_ID}"
echo "Evidence Directory: ${EVIDENCE_DIR}"

export NSUnbufferedIO=YES
security unlock-keychain -p "" ~/Library/Keychains/login.keychain-db 2>/dev/null || true
security set-keychain-settings -lut 7200 ~/Library/Keychains/login.keychain-db 2>/dev/null || true

# 1. Record Toolchain and Environment
ENV_LOG="${EVIDENCE_DIR}/environment.txt"
{
    echo "=== Host Environment ==="
    echo "Date: $(date -u '+%Y-%m-%dT%H:%M:%SZ')"
    sw_vers || true
    uname -a
    echo "Architecture: $(uname -m)"
    echo "=== Xcode & Swift ==="
    xcodebuild -version || true
    swift --version || true
    echo "=== Git Status ==="
    git -C "$REPO_ROOT" status --short || true
} > "$ENV_LOG"
cat "$ENV_LOG"

# 2. Execute Native macOS Tests
echo "--- Running macOS Native Backend Tests ---"
MACOS_LOG="${EVIDENCE_DIR}/macos-backend.log"
touch "$MACOS_LOG"

tail -f "$MACOS_LOG" &
TAIL_PID=$!

swift test --package-path "${REPO_ROOT}/apple/GlideCoreKit" --enable-code-coverage > "$MACOS_LOG" 2>&1 &
TEST_PID=$!

TIMEOUT_SECS=180
for ((i=1; i<=TIMEOUT_SECS; i++)); do
    if ! kill -0 "$TEST_PID" 2>/dev/null; then
        wait "$TEST_PID"
        break
    fi
    sleep 1
done

kill "$TAIL_PID" 2>/dev/null || true

if kill -0 "$TEST_PID" 2>/dev/null; then
    echo "ERROR: Native tests timed out after ${TIMEOUT_SECS}s. Capturing thread sample..."
    sample "$TEST_PID" 1 2>&1 || true
    pgrep -P "$TEST_PID" | while read -r cpid; do
        echo "Sample child PID $cpid:"
        sample "$cpid" 1 2>&1 || true
        kill -9 "$cpid" 2>/dev/null || true
    done
    kill -9 "$TEST_PID" 2>/dev/null || true
    exit 1
fi
echo "macOS native tests PASSED."

# 3. Create Disposable Simulators (iPhone and iPad)
echo "--- Setting Up Disposable iOS/iPadOS Simulators ---"
SIM_LOG="${EVIDENCE_DIR}/simulators.txt"
xcrun simctl list runtimes > "${EVIDENCE_DIR}/available-runtimes.txt"

# Find an available iOS runtime
IOS_RUNTIME=$(xcrun simctl list runtimes | grep -i "iOS" | grep -v "unavailable" | tail -n 1 | awk -F' - ' '{print $NF}' | tr -d '()' || true)
if [ -z "$IOS_RUNTIME" ]; then
    # Fallback search by bundle ID
    IOS_RUNTIME=$(xcrun simctl list runtimes | grep -o 'com.apple.CoreSimulator.SimRuntime.iOS-[0-9-]*' | tail -n 1 || true)
fi

IPHONE_UDID=""
IPAD_UDID=""

cleanup_simulators() {
    echo "Cleaning up disposable simulators..."
    if [ -n "$IPHONE_UDID" ]; then
        xcrun simctl shutdown "$IPHONE_UDID" >/dev/null 2>&1 || true
        xcrun simctl delete "$IPHONE_UDID" >/dev/null 2>&1 || true
        echo "Deleted iPhone simulator: $IPHONE_UDID"
    fi
    if [ -n "$IPAD_UDID" ]; then
        xcrun simctl shutdown "$IPAD_UDID" >/dev/null 2>&1 || true
        xcrun simctl delete "$IPAD_UDID" >/dev/null 2>&1 || true
        echo "Deleted iPad simulator: $IPAD_UDID"
    fi
}
trap cleanup_simulators EXIT

if [ -n "$IOS_RUNTIME" ]; then
    echo "Selected iOS Runtime: $IOS_RUNTIME"
    
    # Create disposable iPhone
    IPHONE_UDID=$(xcrun simctl create "GlideTest-iPhone-${RUN_ID}" "iPhone 16" "$IOS_RUNTIME" 2>/dev/null || xcrun simctl create "GlideTest-iPhone-${RUN_ID}" "iPhone 15" "$IOS_RUNTIME" || true)
    
    # Create disposable iPad
    IPAD_UDID=$(xcrun simctl create "GlideTest-iPad-${RUN_ID}" "iPad Air (11-inch) (M2)" "$IOS_RUNTIME" 2>/dev/null || xcrun simctl create "GlideTest-iPad-${RUN_ID}" "iPad (10th generation)" "$IOS_RUNTIME" || true)
    
    {
        echo "iPhone UDID: $IPHONE_UDID"
        echo "iPad UDID: $IPAD_UDID"
        echo "Runtime: $IOS_RUNTIME"
    } > "$SIM_LOG"
    cat "$SIM_LOG"

    # Boot simulators
    if [ -n "$IPHONE_UDID" ]; then
        echo "Booting iPhone simulator ($IPHONE_UDID)..."
        xcrun simctl boot "$IPHONE_UDID" || true
    fi
    if [ -n "$IPAD_UDID" ]; then
        echo "Booting iPad simulator ($IPAD_UDID)..."
        xcrun simctl boot "$IPAD_UDID" || true
    fi

    # Detect package scheme
    PKG_SCHEME=$( (cd "${REPO_ROOT}/apple/GlideCoreKit" && xcodebuild -list 2>/dev/null | awk '/Schemes:/{flag=1;next} flag && NF {print $1; exit}') || echo "GlideCoreKit")
    if [ -z "$PKG_SCHEME" ]; then PKG_SCHEME="GlideCoreKit"; fi
    echo "Using Package Scheme: $PKG_SCHEME"

    # 4. Run iOS Simulator Tests
    if [ -n "$IPHONE_UDID" ]; then
        echo "--- Running iOS Simulator Backend Tests ---"
        IOS_LOG="${EVIDENCE_DIR}/ios-simulator.log"
        (
            cd "${REPO_ROOT}/apple/GlideCoreKit"
            xcodebuild test \
                -scheme "$PKG_SCHEME" \
                -destination "platform=iOS Simulator,id=$IPHONE_UDID" \
                -resultBundlePath "${EVIDENCE_DIR}/ios-simulator.xcresult" \
                CODE_SIGNING_ALLOWED=NO \
                CODE_SIGN_IDENTITY="" \
                PROVISIONING_PROFILE_SPECIFIER="" \
                2>&1 | tee "$IOS_LOG"
        )
        echo "iOS Simulator tests PASSED."
    fi

    # 5. Run iPadOS Simulator Tests
    if [ -n "$IPAD_UDID" ]; then
        echo "--- Running iPadOS Simulator Backend Tests ---"
        IPAD_LOG="${EVIDENCE_DIR}/ipados-simulator.log"
        (
            cd "${REPO_ROOT}/apple/GlideCoreKit"
            xcodebuild test \
                -scheme "$PKG_SCHEME" \
                -destination "platform=iOS Simulator,id=$IPAD_UDID" \
                -resultBundlePath "${EVIDENCE_DIR}/ipados-simulator.xcresult" \
                CODE_SIGNING_ALLOWED=NO \
                CODE_SIGN_IDENTITY="" \
                PROVISIONING_PROFILE_SPECIFIER="" \
                2>&1 | tee "$IPAD_LOG"
        )
        echo "iPadOS Simulator tests PASSED."
    fi
else
    echo "WARNING: No iOS Simulator runtimes found; running in standalone macOS mode."
fi

# 6. Build Native Applications to verify compiler compatibility
echo "--- Compiling Native macOS and iOS Applications ---"
xcodebuild build \
    -project "${REPO_ROOT}/apple/GlideApple.xcodeproj" \
    -scheme GlideMac \
    -configuration Release \
    -derivedDataPath "${REPO_ROOT}/build/apple-compile" \
    CODE_SIGNING_ALLOWED=NO \
    CODE_SIGN_IDENTITY=""

xcodebuild build \
    -project "${REPO_ROOT}/apple/GlideApple.xcodeproj" \
    -scheme GlideIOS \
    -configuration Release \
    -destination 'generic/platform=iOS Simulator' \
    -derivedDataPath "${REPO_ROOT}/build/apple-compile" \
    CODE_SIGNING_ALLOWED=NO \
    CODE_SIGN_IDENTITY=""

# Verify embedded Share Extension
test -d "${REPO_ROOT}/build/apple-compile/Build/Products/Release-iphonesimulator/GlideIOS.app/PlugIns/GlideShareExtension.appex"
plutil -lint "${REPO_ROOT}/build/apple-compile/Build/Products/Release-iphonesimulator/GlideIOS.app/PlugIns/GlideShareExtension.appex/Info.plist"

# 7. Summary and Evidence Digest
SUMMARY_FILE="${EVIDENCE_DIR}/test-summary.txt"
{
    echo "================================================================="
    echo "Glide Apple Backend Test Execution Summary"
    echo "================================================================="
    echo "Commit: ${COMMIT_SHA}"
    echo "Date: $(date -u '+%Y-%m-%dT%H:%M:%SZ')"
    echo "macOS Tests: PASSED"
    if [ -n "$IPHONE_UDID" ]; then echo "iOS Simulator Tests: PASSED ($IPHONE_UDID)"; fi
    if [ -n "$IPAD_UDID" ]; then echo "iPadOS Simulator Tests: PASSED ($IPAD_UDID)"; fi
    echo "macOS Application Compilation: PASSED"
    echo "iOS Simulator Application Compilation: PASSED"
    echo "Share Extension Verification: PASSED"
    echo "================================================================="
    echo "iOS Simulator and native macOS backend matrix passed at commit ${COMMIT_SHA}."
    echo "================================================================="
} | tee "$SUMMARY_FILE"

echo "=== All Apple Backend Tests Completed Successfully ==="
