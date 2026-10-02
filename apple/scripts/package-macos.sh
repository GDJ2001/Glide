#!/usr/bin/env bash
# Build an unsigned native macOS application from one clean source commit.
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
out="$repo/build/distributions/runs/${commit}-${run_id}/apple/macos"
derived="$repo/build/apple-derived/${commit}-${run_id}"
mkdir -p "$out" "$derived"

xcodebuild build \
  -project "$apple_dir/GlideApple.xcodeproj" \
  -scheme GlideMac -configuration Release \
  -destination 'generic/platform=macOS' \
  -derivedDataPath "$derived" \
  ARCHS='arm64 x86_64' ONLY_ACTIVE_ARCH=NO CODE_SIGNING_ALLOWED=NO

app="$derived/Build/Products/Release/GlideMac.app"
[[ -d "$app" ]] || { echo "The macOS application bundle is missing." >&2; exit 1; }
ditto -c -k --sequesterRsrc --keepParent "$app" "$out/Glide-macos-unsigned.zip"
(cd "$out" && shasum -a 256 Glide-macos-unsigned.zip > SHA256SUMS.txt)
cat > "$out/BUILD-MANIFEST.txt" <<EOF
source_commit=$commit
build_utc=$run_id
toolchain=$(printf '%s' "$xcode_version" | tr '\n' ' ')
artifact=Glide-macos-unsigned.zip
signing_status=unsigned_development
installability=requires_local_development_or_signed_release_build
EOF
echo "Unsigned macOS build: $out"
