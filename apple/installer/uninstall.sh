#!/usr/bin/env bash
# Invoked by Glide Uninstaller.app with macOS administrator authorization.
set -euo pipefail

app=/Applications/Glide.app
uninstaller='/Applications/Glide Uninstaller.app'
receipt=dev.glide.mac.installer

bundle_id() {
  /usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "$1/Contents/Info.plist" 2>/dev/null
}

if [[ -e "$app" || -L "$app" ]]; then
  [[ -d "$app" && ! -L "$app" ]] || { echo 'Glide installation has an unexpected type.' >&2; exit 2; }
  [[ "$(bundle_id "$app")" == dev.glide.mac ]] || {
    echo 'The app in /Applications is not Glide. Nothing was removed.' >&2; exit 2;
  }
  if /usr/bin/pgrep -x GlideMac >/dev/null 2>&1; then
    echo 'Quit Glide before uninstalling it.' >&2
    exit 3
  fi
fi

if [[ -e "$uninstaller" || -L "$uninstaller" ]]; then
  [[ -d "$uninstaller" && ! -L "$uninstaller" ]] || {
    echo 'Glide Uninstaller has an unexpected type.' >&2; exit 2;
  }
  [[ "$(bundle_id "$uninstaller")" == dev.glide.mac.uninstaller ]] || {
    echo 'The uninstaller in /Applications is not Glide Uninstaller.' >&2; exit 2;
  }
fi

# These two verified app bundles are the only paths this package owns.
[[ ! -e "$app" ]] || /bin/rm -rf -- "$app"
/usr/sbin/pkgutil --forget "$receipt" >/dev/null 2>&1 || true
[[ ! -e "$uninstaller" ]] || /bin/rm -rf -- "$uninstaller"
