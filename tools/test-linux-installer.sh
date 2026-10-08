#!/usr/bin/env bash
# Exercise a built Glide .run installer in a throwaway prefix and HOME.
# It never touches the real system. Usage: test-linux-installer.sh <installer.run>
set -euo pipefail

installer="$(readlink -f "${1:?usage: $0 <installer.run>}")"
test -x "$installer" || { echo "Installer not executable: $installer" >&2; exit 2; }

sandbox="$(mktemp -d "${TMPDIR:-/tmp}/glide-installer-test.XXXXXXXX")"
trap 'rm -rf -- "$sandbox"' EXIT
export HOME="$sandbox/home"; mkdir -p "$HOME"
unset XDG_CONFIG_HOME
prefix="$sandbox/prefix"
fail() { echo "FAIL: $*" >&2; exit 1; }
ok()   { echo "ok: $*"; }

"$installer" --info | grep -q '^Glide ' || fail "--info"
"$installer" --verify >/dev/null || fail "--verify"
ok "info and payload checksum"

# Tampering must be detected.
bad="$sandbox/tampered.run"; cp "$installer" "$bad"; printf 'x' >>"$bad"
if "$bad" --verify >/dev/null 2>&1; then fail "tampered payload was accepted"; fi
ok "tampered payload rejected"

# Unsupported targets and unknown options fail closed.
if "$installer" --prefix /usr --no-deps-check >/dev/null 2>&1; then fail "install into /usr allowed"; fi
if "$installer" --bogus >/dev/null 2>&1; then fail "unknown option accepted"; fi
if "$installer" --prefix relative/path >/dev/null 2>&1; then fail "relative prefix accepted"; fi
ok "unsafe invocations rejected"

# Fresh install.
"$installer" --prefix "$prefix" --no-deps-check --no-cache-refresh --enable-service >/dev/null
for f in lib/glide/desktop/Glide.Linux.UI lib/glide/daemon/Glide.App lib/glide/SOURCE_COMMIT \
         lib/glide/install-manifest.txt lib/glide/uninstall.sh share/applications/glide-desktop.desktop \
         share/icons/hicolor/128x128/apps/dev.glide.app.png share/icons/hicolor/512x512/apps/dev.glide.app.png; do
  test -f "$prefix/$f" || fail "missing $f"
done
test -x "$prefix/bin/glide-desktop" && test -x "$prefix/bin/glide" && test -x "$prefix/bin/glide-uninstall" || fail "launchers not executable"
test -f "$HOME/.config/systemd/user/glide.service" || fail "service unit missing"
grep -Fq "Exec=$prefix/bin/glide-desktop %F" "$prefix/share/applications/glide-desktop.desktop" || fail "desktop Exec not absolute"
grep -Fq "Icon=$prefix/share/icons/hicolor/128x128/apps/dev.glide.app.png" "$prefix/share/applications/glide-desktop.desktop" || fail "desktop Icon not absolute"
grep -Fq 'StartupWMClass=glide-desktop' "$prefix/share/applications/glide-desktop.desktop" || fail "WM class lost"
grep -Fq "ExecStart=$prefix/bin/glide " "$HOME/.config/systemd/user/glide.service" || fail "service ExecStart not rewritten"
grep -Fq 'ExecStart=/usr/bin' "$HOME/.config/systemd/user/glide.service" && fail "service still points at /usr/bin"
ok "fresh install layout"

# Re-run = upgrade, still exactly one copy.
"$installer" --prefix "$prefix" --no-deps-check --no-cache-refresh >/dev/null
test -f "$prefix/lib/glide/install-manifest.txt" || fail "upgrade lost manifest"
ok "upgrade in place"

# User data must survive uninstall.
mkdir -p "$HOME/.config/glide" "$HOME/.local/share/glide"
echo keep >"$HOME/.config/glide/settings.json"; echo keep >"$HOME/.local/share/glide/identity"

"$prefix/bin/glide-uninstall" --no-cache-refresh >/dev/null
leftover="$(find "$prefix" "$HOME/.config/systemd" -mindepth 1 2>/dev/null | head -n 5 || true)"
[[ -z "$leftover" ]] || fail "files left after uninstall: $leftover"
test -f "$HOME/.config/glide/settings.json" && test -f "$HOME/.local/share/glide/identity" || fail "user data removed"
ok "clean uninstall, user data kept"

# Uninstalling nothing is an error, not silent success.
if "$installer" --prefix "$prefix" --uninstall >/dev/null 2>&1; then fail "uninstall of missing install succeeded"; fi
ok "missing install detected"

echo "Installer self-test passed."
