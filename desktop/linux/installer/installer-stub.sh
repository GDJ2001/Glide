#!/usr/bin/env bash
# Glide universal Linux installer (self-extracting).
# Version @VERSION@  |  commit @COMMIT@  |  arch @ARCH@
#
# Installs the Glide desktop app and CLI/daemon on any glibc Linux distribution
# without needing .deb/.rpm support. Default is a per-user install under
# ~/.local; run as root (or pass --system) for /usr/local. User settings and
# received files are never removed.
set -euo pipefail

GLIDE_VERSION="@VERSION@"
GLIDE_COMMIT="@COMMIT@"
GLIDE_ARCH="@ARCH@"
GLIDE_PAYLOAD_SHA256="@PAYLOAD_SHA256@"
GLIDE_APP_ID="dev.glide.app"
MANIFEST_NAME="install-manifest.txt"

usage() {
  cat <<'EOF'
Glide installer

Usage: ./glide-installer.run [options]

  --user              Install for the current user (default when not root)
                      prefix: ~/.local
  --system            Install for all users (default when root)
                      prefix: /usr/local   (requires root)
  --prefix DIR        Install under DIR (overrides --user/--system)
  --uninstall         Remove a Glide installation made by this installer
  --enable-service    Also install the optional systemd user service file
                      (it is never enabled or started automatically)
  --no-deps-check     Skip native library checks
  --no-cache-refresh  Skip desktop/icon cache refresh
  --verify            Verify the embedded payload checksum and exit
  --extract DIR       Extract the payload into DIR and exit
  --info              Print version, commit and architecture
  -h, --help          Show this help

After installing, start Glide from the application menu or run: glide-desktop
Remove it with:  glide-uninstall   (or this installer with --uninstall)
User settings and received files are kept on uninstall.
EOF
}

log()  { printf '[glide-installer] %s\n' "$*"; }
warn() { printf '[glide-installer] warning: %s\n' "$*" >&2; }
die()  { printf '[glide-installer] error: %s\n' "$*" >&2; exit 1; }

self="${BASH_SOURCE[0]}"

payload_offset() {
  awk '/^__GLIDE_PAYLOAD_BELOW__$/ { print NR + 1; exit }' "$1"
}

payload_stream() {
  local offset
  offset="$(payload_offset "$self")"
  [[ -n "$offset" ]] || die "Installer payload marker not found; the file is damaged."
  tail -n +"$offset" "$self"
}

verify_payload() {
  local actual
  actual="$(payload_stream | sha256sum | awk '{print $1}')"
  if [[ "$actual" != "$GLIDE_PAYLOAD_SHA256" ]]; then
    die "Payload checksum mismatch (expected $GLIDE_PAYLOAD_SHA256, got $actual). Re-download the installer."
  fi
}

host_arch() {
  case "$(uname -m)" in
    x86_64|amd64) echo x86_64 ;;
    aarch64|arm64) echo aarch64 ;;
    *) uname -m ;;
  esac
}

# ---------------------------------------------------------------- options ---
mode=install scope=""; prefix=""
deps_check=1; cache_refresh=1; want_service=0; extract_dir=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --user) scope=user ;;
    --system) scope=system ;;
    --prefix) [[ $# -ge 2 ]] || die "--prefix needs a directory"; prefix="$2"; shift ;;
    --prefix=*) prefix="${1#*=}" ;;
    --uninstall) mode=uninstall ;;
    --enable-service) want_service=1 ;;
    --no-deps-check) deps_check=0 ;;
    --no-cache-refresh) cache_refresh=0 ;;
    --verify) mode=verify ;;
    --extract) [[ $# -ge 2 ]] || die "--extract needs a directory"; mode=extract; extract_dir="$2"; shift ;;
    --info) mode=info ;;
    -h|--help) usage; exit 0 ;;
    *) usage >&2; die "Unknown option: $1" ;;
  esac
  shift
done

case "$mode" in
  info)
    echo "Glide $GLIDE_VERSION (commit $GLIDE_COMMIT, $GLIDE_ARCH)"; exit 0 ;;
  verify)
    verify_payload; log "Payload checksum OK ($GLIDE_PAYLOAD_SHA256)"; exit 0 ;;
  extract)
    verify_payload
    mkdir -p "$extract_dir"
    payload_stream | tar -xzf - -C "$extract_dir"
    log "Extracted to $extract_dir"; exit 0 ;;
esac

if [[ -z "$scope" ]]; then
  if [[ "$(id -u)" -eq 0 ]]; then scope=system; else scope=user; fi
fi
if [[ -z "$prefix" ]]; then
  if [[ "$scope" == system ]]; then prefix=/usr/local; else prefix="${HOME:?HOME is not set}/.local"; fi
fi
[[ "$prefix" == /* ]] || die "Prefix must be an absolute path: $prefix"
prefix="${prefix%/}"

bin_dir="$prefix/bin"
lib_dir="$prefix/lib/glide"
apps_dir="$prefix/share/applications"
icon_root="$prefix/share/icons/hicolor"
manifest="$lib_dir/$MANIFEST_NAME"
if [[ "$scope" == system ]]; then
  unit_dir="$prefix/lib/systemd/user"
else
  unit_dir="${XDG_CONFIG_HOME:-${HOME:-/tmp}/.config}/systemd/user"
fi

refresh_caches() {
  [[ "$cache_refresh" -eq 1 ]] || return 0
  if command -v update-desktop-database >/dev/null 2>&1; then
    update-desktop-database "$apps_dir" >/dev/null 2>&1 || true
  fi
  if command -v gtk-update-icon-cache >/dev/null 2>&1; then
    gtk-update-icon-cache -q -t -f "$icon_root" >/dev/null 2>&1 || true
  fi
  return 0
}

# -------------------------------------------------------------- uninstall ---
do_uninstall() {
  [[ -f "$manifest" ]] || die "No Glide installation found under $prefix (missing $manifest)."
  log "Removing Glide from $prefix"
  if [[ "$scope" == user ]] && command -v systemctl >/dev/null 2>&1; then
    systemctl --user disable --now glide.service >/dev/null 2>&1 || true
  fi
  local entry
  # Files (and symlinks) first, then the directories this installer created,
  # deepest first. Non-empty directories are left alone.
  while IFS= read -r entry; do
    if [[ "$entry" == "F "* ]]; then rm -f -- "${entry#F }"; fi
  done <"$manifest"
  while IFS= read -r entry; do
    if [[ "$entry" == "D "* ]]; then rmdir -- "${entry#D }" 2>/dev/null || true; fi
  done < <(awk '{a[NR]=$0} END{for(i=NR;i>0;i--)print a[i]}' "$manifest")
  rm -f -- "$manifest"
  rmdir "$lib_dir" 2>/dev/null || true
  refresh_caches
  log "Glide removed. Your settings (~/.config/glide, ~/.local/share/glide) and received files were kept."
}

if [[ "$mode" == uninstall ]]; then
  do_uninstall
  exit 0
fi

# ---------------------------------------------------------------- install ---
[[ "$(uname -s)" == Linux ]] || die "This installer only supports Linux."
[[ "$(host_arch)" == "$GLIDE_ARCH" ]] || die "This installer is for $GLIDE_ARCH but this machine is $(host_arch)."
for tool in tar sha256sum awk sed install find; do
  command -v "$tool" >/dev/null 2>&1 || die "Required tool not found: $tool"
done

check_deps() {
  [[ "$deps_check" -eq 1 ]] || return 0
  local cache missing=() lib
  if command -v ldconfig >/dev/null 2>&1; then cache="$(ldconfig -p 2>/dev/null || true)"
  elif [[ -x /sbin/ldconfig ]]; then cache="$(/sbin/ldconfig -p 2>/dev/null || true)"
  else warn "ldconfig not found; skipping native library check."; return 0; fi
  for lib in libX11.so.6 libICE.so.6 libSM.so.6 libfontconfig.so.1 libssl.so.3 \
             libgssapi_krb5.so.2 libz.so.1 libstdc++.so.6; do
    grep -Fq "$lib" <<<"$cache" || missing+=("$lib")
  done
  grep -Eq 'libicuuc\.so\.[0-9]+' <<<"$cache" || missing+=("libicuuc.so.*")
  if [[ ${#missing[@]} -gt 0 ]]; then
    warn "Missing native libraries: ${missing[*]}"
    cat >&2 <<'EOF'
Install the runtime libraries, then re-run this installer:
  Debian/Ubuntu: sudo apt install libx11-6 libice6 libsm6 libfontconfig1 libssl3t64 libgssapi-krb5-2 zlib1g libstdc++6 libicu74   (libicu78 on Ubuntu 26.04)
  Fedora/RHEL:   sudo dnf install libX11 libICE libSM fontconfig openssl-libs krb5-libs zlib libstdc++ libicu
  openSUSE:      sudo zypper install libX11-6 libICE6 libSM6 fontconfig libopenssl3 krb5 libz1 libstdc++6 libicu
  Arch:          sudo pacman -S libx11 libice libsm fontconfig openssl krb5 zlib gcc-libs icu
Use --no-deps-check to install anyway.
EOF
    exit 1
  fi
}

check_target() {
  [[ "$prefix" != /usr ]] || die "Refusing to install into /usr; that tree belongs to the package manager. Use --system (/usr/local) or --prefix."
  local path=/usr/bin/glide-desktop
  if [[ -e "$path" ]]; then
    if { command -v dpkg >/dev/null 2>&1 && dpkg -S "$path" >/dev/null 2>&1; } ||
       { command -v rpm >/dev/null 2>&1 && rpm -qf "$path" >/dev/null 2>&1; }; then
      warn "A packaged Glide is already installed in /usr/bin. Remove it (apt remove glide / dnf remove glide) to avoid running two copies."
    fi
  fi
  mkdir -p "$prefix" 2>/dev/null || true
  [[ -w "$prefix" ]] || die "No write access to $prefix. Re-run with sudo for --system, or choose --user / --prefix."
}

recorded=()
mk_dir() {
  local d="$1"
  [[ -d "$d" ]] && return 0
  mk_dir "$(dirname "$d")"
  mkdir "$d"
  recorded+=("D $d")
}

place() {  # place <mode> <src> <dst>
  mk_dir "$(dirname "$3")"
  install -m "$1" "$2" "$3"
  recorded+=("F $3")
}

link() {  # link <target> <linkpath>
  mk_dir "$(dirname "$2")"
  ln -sfn "$1" "$2"
  recorded+=("F $2")
}

stage=""
do_install() {
  check_deps
  check_target
  log "Verifying installer payload..."
  verify_payload

  stage="$(mktemp -d "${TMPDIR:-/tmp}/glide-install.XXXXXXXX")"
  trap 'rm -rf -- "$stage"' EXIT
  # Keep our own copy: an upgrade may delete the installer we were started from.
  cp -- "$self" "$stage/glide-installer.run"
  payload_stream | tar -xzf - -C "$stage"
  local src="$stage/usr"
  [[ -x "$src/lib/glide/desktop/Glide.Linux.UI" && -x "$src/lib/glide/daemon/Glide.App" ]] \
    || die "Payload is incomplete (missing Glide executables)."

  if [[ -f "$manifest" ]]; then
    log "Existing installation found; upgrading."
    local saved=$cache_refresh
    cache_refresh=0
    do_uninstall >/dev/null
    cache_refresh=$saved
  fi

  log "Installing Glide $GLIDE_VERSION to $prefix"
  local f rel
  while IFS= read -r -d '' f; do
    rel="${f#"$src/lib/glide/"}"
    if [[ -x "$f" ]]; then place 0755 "$f" "$lib_dir/$rel"; else place 0644 "$f" "$lib_dir/$rel"; fi
  done < <(find "$src/lib/glide" -type f -print0)

  link "$lib_dir/desktop/Glide.Linux.UI" "$bin_dir/glide-desktop"
  link "$lib_dir/daemon/Glide.App"       "$bin_dir/glide"

  # Desktop entry with absolute paths so it works outside /usr.
  tr -d '\r' <"$src/share/applications/glide-desktop.desktop" |
    sed -e "s|^Exec=glide-desktop|Exec=$bin_dir/glide-desktop|" \
        -e "s|^Exec=glide receive|Exec=$bin_dir/glide receive|" \
        -e "s|^Icon=.*|Icon=$icon_root/128x128/apps/$GLIDE_APP_ID.png|" \
      >"$stage/glide-desktop.desktop"
  place 0644 "$stage/glide-desktop.desktop" "$apps_dir/glide-desktop.desktop"

  local size
  for size in 16 32 64 128 256 512; do
    place 0644 "$src/share/icons/hicolor/${size}x${size}/apps/$GLIDE_APP_ID.png" \
      "$icon_root/${size}x${size}/apps/$GLIDE_APP_ID.png"
  done

  if [[ "$want_service" -eq 1 ]]; then
    tr -d '\r' <"$src/lib/systemd/user/glide.service" |
      sed -e "s|^ExecStart=/usr/bin/glide |ExecStart=$bin_dir/glide |" >"$stage/glide.service"
    place 0644 "$stage/glide.service" "$unit_dir/glide.service"
    log "Installed the optional service unit (not enabled). Configure ~/.config/glide/daemon.env, then: systemctl --user enable --now glide.service"
  fi

  # Keep the installer for uninstalling, behind a small wrapper.
  place 0755 "$stage/glide-installer.run" "$lib_dir/glide-installer.run"
  printf '#!/usr/bin/env bash\nexec "%s/glide-installer.run" --prefix "%s" --uninstall "$@"\n' \
    "$lib_dir" "$prefix" >"$stage/uninstall.sh"
  place 0755 "$stage/uninstall.sh" "$lib_dir/uninstall.sh"
  link "$lib_dir/uninstall.sh" "$bin_dir/glide-uninstall"

  { printf 'F %s\n' "$manifest"; printf '%s\n' "${recorded[@]}"; } >"$manifest.tmp"
  mv -- "$manifest.tmp" "$manifest"

  refresh_caches

  log "Done. Glide $GLIDE_VERSION is installed."
  case ":$PATH:" in
    *":$bin_dir:"*) ;;
    *) warn "$bin_dir is not on your PATH; start Glide from the application menu or run $bin_dir/glide-desktop." ;;
  esac
  log "Start it:   glide-desktop"
  log "Uninstall:  glide-uninstall"
}

do_install
exit 0
__GLIDE_PAYLOAD_BELOW__
