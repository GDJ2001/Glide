#!/usr/bin/env bash
# Build simulator and unsigned device archives from one clean source commit.
set -euo pipefail

apple_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
repo="$(cd "$apple_dir/.." && pwd)"
[[ "$(uname -s)" == Darwin ]] || { echo "macOS and Xcode are required." >&2; exit 2; }
command -v xcodebuild >/dev/null || { echo "Xcode is required." >&2; exit 2; }
command -v ditto >/dev/null || { echo "ditto is required." >&2; exit 2; }
git -C "$repo" checkout -- apple/scripts/ 2>/dev/null || true
dirty="$(git -C "$repo" status --porcelain | grep -v 'build/' | grep -v 'xcuserdata' | grep -v '\.swiftpm' | grep -v 'Package.resolved' || true)"
[[ -z "$dirty" ]] || {
  echo "Refusing to package a dirty source tree: $dirty" >&2; exit 2;
}

xcode_version="$(xcodebuild -version)"
[[ "$xcode_version" == *"Xcode 26.6"* ]] || {
  echo "Xcode 26.6 is required for the baseline build." >&2; exit 2;
}
commit="$(git -C "$repo" rev-parse HEAD)"
run_id="$(date -u +%Y%m%dT%H%M%SZ)"
out="$repo/build/distributions/runs/${commit}-${run_id}/apple/ios"
derived="$repo/build/apple-derived/${commit}-${run_id}"
mkdir -p "$out" "$derived"

xcodebuild build \
  -project "$apple_dir/GlideApple.xcodeproj" \
  -scheme GlideIOS -configuration Release \
  -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath "$derived" CODE_SIGNING_ALLOWED=NO

simulator_app="$derived/Build/Products/Release-iphonesimulator/GlideIOS.app"
[[ -d "$simulator_app" ]] || { echo "The iOS simulator app is missing." >&2; exit 1; }
ditto -c -k --sequesterRsrc --keepParent "$simulator_app" \
  "$out/Glide-iOS-simulator-unsigned.zip"

xcodebuild archive \
  -project "$apple_dir/GlideApple.xcodeproj" \
  -scheme GlideIOS -configuration Release \
  -destination 'generic/platform=iOS' \
  -archivePath "$out/Glide-iOS-unsigned.xcarchive" \
  -derivedDataPath "$derived" CODE_SIGNING_ALLOWED=NO

[[ -d "$out/Glide-iOS-unsigned.xcarchive/Products/Applications/GlideIOS.app" ]] || {
  echo "The unsigned iOS device archive is missing its app." >&2; exit 1;
}
ditto -c -k --sequesterRsrc --keepParent \
  "$out/Glide-iOS-unsigned.xcarchive" "$out/Glide-iOS-device-unsigned-archive.zip"
(cd "$out" && shasum -a 256 \
  Glide-iOS-simulator-unsigned.zip Glide-iOS-device-unsigned-archive.zip \
  > SHA256SUMS.txt)
cat > "$out/BUILD-MANIFEST.txt" <<EOF
source_commit=$commit
build_utc=$run_id
toolchain=$(printf '%s' "$xcode_version" | tr '\n' ' ')
simulator_artifact=Glide-iOS-simulator-unsigned.zip
device_artifact=Glide-iOS-device-unsigned-archive.zip
signing_status=unsigned_development
installability=build_only_requires_distribution_signing
EOF
echo "Unsigned iOS builds: $out"
