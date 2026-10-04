#!/usr/bin/env bash
# Build a native macOS installer containing Glide and its dedicated uninstaller.
# With Developer ID identities and a notary profile, produce a signed and
# notarized distribution package; otherwise produce an unsigned test package.
set -euo pipefail

apple_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
repo="$(cd "$apple_dir/.." && pwd)"
[[ "$(uname -s)" == Darwin ]] || { echo "macOS and Xcode are required." >&2; exit 2; }
for tool in xcodebuild ditto osacompile pkgbuild pkgutil codesign plutil shasum lsbom iconutil; do
  command -v "$tool" >/dev/null || { echo "Missing packaging tool: $tool" >&2; exit 2; }
done
dirty="$(git -C "$repo" status --porcelain | grep -v 'build/' | grep -v 'xcuserdata' | grep -v '\.swiftpm' | grep -v 'Package.resolved' || true)"
[[ -z "$dirty" ]] || { echo "Refusing to package a dirty source tree: $dirty" >&2; exit 2; }

xcode_version="$(xcodebuild -version)"
[[ "$xcode_version" == *"Xcode 26.6"* ]] || {
  echo "Xcode 26.6 is required for the baseline build." >&2; exit 2;
}
version="${GLIDE_PACKAGE_VERSION:-0.1.0}"
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || {
  echo "GLIDE_PACKAGE_VERSION must use major.minor.patch." >&2; exit 2;
}

app_identity="${GLIDE_MAC_APP_SIGNING_IDENTITY:-}"
installer_identity="${GLIDE_MAC_INSTALLER_SIGNING_IDENTITY:-}"
notary_profile="${GLIDE_MAC_NOTARY_PROFILE:-}"
signed=false
if [[ -n "$app_identity" || -n "$installer_identity" || -n "$notary_profile" ]]; then
  [[ -n "$app_identity" && -n "$installer_identity" && -n "$notary_profile" ]] || {
    echo "Signed distribution requires both Developer ID identities and a notarytool keychain profile." >&2
    exit 2
  }
  signed=true
fi

commit="$(git -C "$repo" rev-parse HEAD)"
run_id="$(date -u +%Y%m%dT%H%M%SZ)"
out="$repo/build/distributions/runs/${commit}-${run_id}/apple/macos"
derived="$repo/build/apple-derived/${commit}-${run_id}"
payload="$derived/GlideInstallerPayload"
mkdir -p "$out" "$derived" "$payload/Applications"

xcodebuild build \
  -project "$apple_dir/GlideApple.xcodeproj" \
  -scheme GlideMac -configuration Release \
  -destination 'generic/platform=macOS' \
  -derivedDataPath "$derived" \
  ARCHS='arm64 x86_64' ONLY_ACTIVE_ARCH=NO CODE_SIGNING_ALLOWED=NO

built_app="$derived/Build/Products/Release/GlideMac.app"
[[ -d "$built_app" ]] || { echo "The macOS application bundle is missing." >&2; exit 1; }
app="$payload/Applications/Glide.app"
uninstaller="$payload/Applications/Glide Uninstaller.app"
ditto "$built_app" "$app"
osacompile -o "$uninstaller" "$apple_dir/installer/GlideUninstaller.applescript"
install -m 0755 "$apple_dir/installer/uninstall.sh" "$uninstaller/Contents/Resources/uninstall.sh"

iconset="$derived/Glide.iconset"
mkdir -p "$iconset" "$app/Contents/Resources" "$uninstaller/Contents/Resources"
for size in 16 32 128 256 512; do
  cp "$repo/assets/icons/indigo/glide-symbol-indigo-${size}.png" \
    "$iconset/icon_${size}x${size}.png"
  double_size=$((size * 2))
  cp "$repo/assets/icons/indigo/glide-symbol-indigo-${double_size}.png" \
    "$iconset/icon_${size}x${size}@2x.png"
done
iconutil -c icns "$iconset" -o "$app/Contents/Resources/Glide.icns"
cp "$app/Contents/Resources/Glide.icns" "$uninstaller/Contents/Resources/Glide.icns"
plutil -replace CFBundleIconFile -string Glide "$app/Contents/Info.plist"
plutil -replace CFBundleIconFile -string Glide "$uninstaller/Contents/Info.plist"
plutil -replace CFBundleIdentifier -string dev.glide.mac.uninstaller "$uninstaller/Contents/Info.plist"
plutil -replace CFBundleDisplayName -string 'Glide Uninstaller' "$uninstaller/Contents/Info.plist"
plutil -lint "$app/Contents/Info.plist" "$uninstaller/Contents/Info.plist"

if $signed; then
  codesign --force --options runtime --timestamp --entitlements \
    "$apple_dir/GlideMac/GlideMac.entitlements" --sign "$app_identity" "$app"
  codesign --force --options runtime --timestamp --sign "$app_identity" "$uninstaller"
  codesign --verify --deep --strict --verbose=2 "$app"
  codesign --verify --deep --strict --verbose=2 "$uninstaller"
  package_name='Glide-macos-signed-notarized.pkg'
  archive_name='Glide-macos-signed.zip'
  signing_status=developer_id_signed_notarized
  installability=distribution_installable
else
  package_name='Glide-macos-development-unsigned.pkg'
  archive_name='Glide-macos-unsigned.zip'
  signing_status=unsigned_development
  installability=local_development_installable_unsigned
fi

package="$out/$package_name"
components="$derived/GlideComponents.plist"
pkgbuild --analyze --root "$payload" "$components"
for index in 0 1; do
  component_path="$(/usr/libexec/PlistBuddy -c "Print :$index:RootRelativeBundlePath" "$components")"
  case "$component_path" in
    Applications/Glide.app|Applications/Glide\ Uninstaller.app) ;;
    *) echo "Unexpected installer component: $component_path" >&2; exit 1 ;;
  esac
  /usr/libexec/PlistBuddy -c "Set :$index:BundleIsRelocatable false" "$components" || \
    /usr/libexec/PlistBuddy -c "Add :$index:BundleIsRelocatable bool false" "$components"
done
plutil -lint "$components"
package_args=(--root "$payload" --identifier dev.glide.mac.installer \
  --version "$version" --install-location / --component-plist "$components")
if $signed; then package_args+=(--sign "$installer_identity"); fi
pkgbuild "${package_args[@]}" "$package"
[[ -s "$package" ]] || { echo "The macOS installer package is missing." >&2; exit 1; }

inspection="$(mktemp -d "${TMPDIR:-/tmp}/glide-pkg-inspection.XXXXXXXX")"
trap 'rm -rf -- "$inspection"' EXIT
pkgutil --expand "$package" "$inspection/expanded"
[[ -f "$inspection/expanded/PackageInfo" && -f "$inspection/expanded/Bom" ]] || {
  echo "Installer structure is incomplete." >&2; exit 1;
}
lsbom "$inspection/expanded/Bom" | grep -F 'Applications/Glide.app' >/dev/null
lsbom "$inspection/expanded/Bom" | grep -F 'Applications/Glide Uninstaller.app' >/dev/null

if $signed; then
  pkgutil --check-signature "$package"
  xcrun notarytool submit "$package" --keychain-profile "$notary_profile" --wait
  xcrun stapler staple "$package"
  xcrun stapler validate "$package"
  spctl --assess --type install --verbose "$package"
fi

ditto -c -k --sequesterRsrc --keepParent "$app" "$out/$archive_name"
(cd "$out" && shasum -a 256 "$package_name" "$archive_name" > SHA256SUMS.txt)
cat > "$out/BUILD-MANIFEST.txt" <<EOF
source_commit=$commit
build_utc=$run_id
toolchain=$(printf '%s' "$xcode_version" | tr '\n' ' ')
package_version=$version
installer_artifact=$package_name
app_archive=$archive_name
uninstaller=/Applications/Glide Uninstaller.app
package_identifier=dev.glide.mac.installer
signing_status=$signing_status
installability=$installability
EOF
echo "macOS installer and uninstaller: $out"
