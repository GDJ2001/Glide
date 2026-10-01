#!/usr/bin/env bash
# Glide Apple Build and Test Automation
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APPLE_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

echo "=== Glide Apple Test Suite ==="
cd "${APPLE_DIR}/GlideCoreKit"

if command -v swift >/dev/null 2>&1; then
    echo "Running Swift Package Manager tests for GlideCoreKit..."
    swift test --enable-code-coverage
    echo "GlideCoreKit tests PASSED."
else
    echo "BLOCKER: swift toolchain not found on PATH. An Xcode/macOS build host is required." >&2
    exit 1
fi
