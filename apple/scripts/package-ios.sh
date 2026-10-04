#!/usr/bin/env bash
# Build iOS/iPadOS simulator and device archives. Export a registered-device IPA
# only when protected signing and provisioning inputs are available on the Mac.
set -euo pipefail

apple_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
repo="$(cd "$apple_dir/.." && pwd)"
[[ "$(uname -s)" == Darwin ]] || { echo "macOS and Xcode are required." >&2; exit 2; }
for tool in xcodebuild ditto plutil unzip codesign shasum; do
  command -v "$tool" >/dev/null || { echo "Missing packaging tool: $tool" >&2; exit 2; }
done
dirty="$(git -C "$repo" status --porcelain | grep -v 'build/' | grep -v 'xcuserdata' | grep -v '\.swiftpm' | grep -v 'Package.resolved' || true)"
[[ -z "$dirty" ]] || { echo "Refusing to package a dirty source tree: $dirty" >&2; exit 2; }

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

ipa_artifact=none
signing_status=unsigned_development
installability=simulator_only_device_archive_requires_signing
team_id="${GLIDE_IOS_TEAM_ID:-}"
export_options="${GLIDE_IOS_EXPORT_OPTIONS_PLIST:-}"
if [[ -n "$team_id" || -n "$export_options" ]]; then
  [[ -n "$team_id" && -f "$export_options" ]] || {
    echo 'A signed IPA requires GLIDE_IOS_TEAM_ID and an existing protected export-options plist.' >&2
    exit 2
  }
  plutil -lint "$export_options"
  method="$(/usr/libexec/PlistBuddy -c 'Print :method' "$export_options")"
  case "$method" in
    ad-hoc|debugging|development) ;;
    *) echo "Export method $method is not a registered-device installation method." >&2; exit 2 ;;
  esac

  signed_archive="$out/Glide-iOS-signed.xcarchive"
  xcodebuild archive \
    -project "$apple_dir/GlideApple.xcodeproj" \
    -scheme GlideIOS -configuration Release \
    -destination 'generic/platform=iOS' \
    -archivePath "$signed_archive" \
    -derivedDataPath "$derived" \
    DEVELOPMENT_TEAM="$team_id" CODE_SIGNING_ALLOWED=YES
  export_dir="$out/signed-export"
  xcodebuild -exportArchive -archivePath "$signed_archive" \
    -exportPath "$export_dir" -exportOptionsPlist "$export_options"
  ipa_count="$(find "$export_dir" -maxdepth 1 -type f -name '*.ipa' | wc -l | tr -d ' ')"
  [[ "$ipa_count" -eq 1 ]] || { echo "Expected one signed IPA, found $ipa_count." >&2; exit 1; }
  exported_ipa="$(find "$export_dir" -maxdepth 1 -type f -name '*.ipa' -print -quit)"
  ipa_artifact=Glide-iOS-device-signed.ipa
  cp "$exported_ipa" "$out/$ipa_artifact"

  inspection="$(mktemp -d "${TMPDIR:-/tmp}/glide-ipa-inspection.XXXXXXXX")"
  trap 'rm -rf -- "$inspection"' EXIT
  unzip -q "$out/$ipa_artifact" -d "$inspection"
  app_count="$(find "$inspection/Payload" -maxdepth 1 -type d -name '*.app' | wc -l | tr -d ' ')"
  [[ "$app_count" -eq 1 ]] || { echo 'The IPA must contain one app.' >&2; exit 1; }
  ios_app="$(find "$inspection/Payload" -maxdepth 1 -type d -name '*.app' -print -quit)"
  extension="$ios_app/PlugIns/GlideShareExtension.appex"
  [[ -d "$extension" && -f "$ios_app/embedded.mobileprovision" && \
     -f "$extension/embedded.mobileprovision" ]] || {
    echo 'The signed app or Share Extension lacks its provisioning profile.' >&2; exit 1;
  }
  codesign --verify --deep --strict "$ios_app"
  codesign --verify --strict "$extension"
  for target in "$ios_app" "$extension"; do
    codesign -d --entitlements :- "$target" > "$inspection/entitlements.plist" 2>/dev/null
    group="$(/usr/libexec/PlistBuddy -c 'Print :com.apple.security.application-groups:0' \
      "$inspection/entitlements.plist")"
    [[ "$group" == group.dev.glide.shared ]] || {
      echo "App Group entitlement missing from $target" >&2; exit 1;
    }
  done
  signing_status=signed_registered_device
  installability=registered_iphone_ipad_installable
fi

hash_files=(Glide-iOS-simulator-unsigned.zip Glide-iOS-device-unsigned-archive.zip)
if [[ "$ipa_artifact" != none ]]; then hash_files+=("$ipa_artifact"); fi
(cd "$out" && shasum -a 256 "${hash_files[@]}" > SHA256SUMS.txt)
cat > "$out/BUILD-MANIFEST.txt" <<EOF
source_commit=$commit
build_utc=$run_id
toolchain=$(printf '%s' "$xcode_version" | tr '\n' ' ')
simulator_artifact=Glide-iOS-simulator-unsigned.zip
device_artifact=Glide-iOS-device-unsigned-archive.zip
signed_ipa_artifact=$ipa_artifact
signing_status=$signing_status
installability=$installability
EOF
echo "iOS/iPadOS builds: $out"
