#!/usr/bin/env bash
# Build native Linux GUI + daemon packages from one clean source commit.
set -euo pipefail

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
version="${GLIDE_PACKAGE_VERSION:-1.0.0}"
case "$version" in
  *[!0-9.]*|.*|*.) echo "Invalid numeric package version" >&2; exit 2 ;;
esac
if [[ -n "$(git -C "$repo" status --porcelain)" ]]; then
  echo "Refusing to package a dirty working tree; commit the implementation first." >&2
  exit 2
fi
for command in dotnet tar dpkg-deb rpmbuild rpm2cpio cpio sha256sum cmp; do
  command -v "$command" >/dev/null || { echo "Missing packaging tool: $command" >&2; exit 2; }
done

case "$(uname -m)" in
  x86_64) native_rid=linux-x64 ;;
  aarch64|arm64) native_rid=linux-arm64 ;;
  *) echo "Unsupported native Linux architecture" >&2; exit 2 ;;
esac
if [[ "$#" -eq 0 ]]; then set -- "$native_rid"; fi
for rid in "$@"; do
  [[ "$rid" == "$native_rid" ]] || {
    echo "Build $rid packages on a matching native runner; this host is $native_rid." >&2
    exit 2
  }
done

commit="$(git -C "$repo" rev-parse HEAD)"
run_id="$(date -u +%Y%m%dT%H%M%SZ)"
out="$repo/build/distributions/runs/${commit}-${run_id}/linux"
mkdir -p "$out"
work="$(mktemp -d "${TMPDIR:-/tmp}/linux-package.XXXXXXXX")"
trap 'rm -rf -- "$work"' EXIT

for rid in "$@"; do
  case "$rid" in
    linux-x64) deb_arch=amd64; rpm_arch=x86_64 ;;
    linux-arm64) deb_arch=arm64; rpm_arch=aarch64 ;;
    *) echo "Unsupported Linux runtime: $rid" >&2; exit 2 ;;
  esac

  gui="$work/$rid/gui"
  daemon="$work/$rid/daemon"
  root="$work/$rid/root"
  mkdir -p "$gui" "$daemon" "$root/usr/lib/glide/desktop" \
    "$root/usr/lib/glide/daemon" "$root/usr/bin" \
    "$root/usr/share/applications" "$root/usr/share/icons/hicolor/96x96/apps" \
    "$root/usr/lib/systemd/user" "$root/DEBIAN"

  dotnet publish "$repo/desktop/linux/Glide.Linux.UI/Glide.Linux.UI.csproj" \
    -c Release -r "$rid" --self-contained true \
    -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -o "$gui"
  dotnet publish "$repo/desktop/src/Glide.App/Glide.App.csproj" \
    -c Release -r "$rid" --self-contained true \
    -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -o "$daemon"
  test -s "$gui/Glide.Linux.UI" && test -s "$daemon/Glide.App" || {
    echo "A required Linux executable is missing" >&2; exit 1;
  }

  cp -a "$gui/." "$root/usr/lib/glide/desktop/"
  cp -a "$daemon/." "$root/usr/lib/glide/daemon/"
  printf '%s\n' "$commit" > "$root/usr/lib/glide/SOURCE_COMMIT"
  chmod 0755 "$root/usr/lib/glide/desktop/Glide.Linux.UI" \
    "$root/usr/lib/glide/daemon/Glide.App"
  ln -s ../lib/glide/desktop/Glide.Linux.UI "$root/usr/bin/glide-desktop"
  ln -s ../lib/glide/daemon/Glide.App "$root/usr/bin/glide"
  install -m 0644 "$repo/desktop/linux/glide.desktop" \
    "$root/usr/share/applications/glide.desktop"
  install -m 0644 "$repo/desktop/linux/glide.service" \
    "$root/usr/lib/systemd/user/glide.service"
  install -m 0644 "$repo/assets/icons/blue/glide-symbol-blue-96.png" \
    "$root/usr/share/icons/hicolor/96x96/apps/dev.glide.app.png"

  # Detect libicu dependency (libicu78 on Ubuntu 26.04+, libicu74 on Ubuntu 24.04).
  deb_icu="libicu78 | libicu74"
  if [[ -n "${GLIDE_DEB_ICU_DEP:-}" ]]; then
    deb_icu="$GLIDE_DEB_ICU_DEP"
  elif [[ -f /etc/os-release ]]; then
    # shellcheck disable=SC1091
    . /etc/os-release
    if [[ "${VERSION_ID:-}" == "26.04"* ]]; then
      deb_icu="libicu78"
    elif [[ "${VERSION_ID:-}" == "24.04"* ]]; then
      deb_icu="libicu74"
    fi
  fi

  installed_kb="$(du -sk "$root/usr" | cut -f1)"

  cat > "$root/DEBIAN/control" <<EOF
Package: glide
Version: $version
Section: net
Priority: optional
Architecture: $deb_arch
Maintainer: Glide Developers
Installed-Size: $installed_kb
Depends: ca-certificates, libc6, libgcc-s1, libgssapi-krb5-2, $deb_icu, libssl3t64, libstdc++6, tzdata, zlib1g, libx11-6, libice6, libsm6, libfontconfig1, xdg-utils
Description: Glide native local file sharing
 Offline authenticated file transfers with a desktop GUI and optional daemon.
EOF

  portable="$out/glide-${version}-${rid}.tar.gz"
  deb="$out/glide_${version}_${deb_arch}.deb"
  rpm="$out/glide-${version}-1.${rpm_arch}.rpm"
  tar -czf "$portable" -C "$root" usr
  dpkg-deb --root-owner-group --build "$root" "$deb"

  rpm_top="$work/$rid/rpm"
  mkdir -p "$rpm_top"/{BUILD,BUILDROOT,RPMS,SOURCES,SPECS,SRPMS}
  spec="$rpm_top/SPECS/glide.spec"
  cat > "$spec" <<EOF
# These are already-published .NET bundles. RPM post-processing must not strip
# or otherwise rewrite their appended managed payloads.
%global __os_install_post %{nil}
%global _build_id_links none
Name: glide
Version: $version
Release: 1
Summary: Native local file sharing
License: LicenseRef-Glide
BuildArch: $rpm_arch
AutoReqProv: no
Requires: ca-certificates, glibc, libgcc, krb5-libs, libicu, openssl-libs, libstdc++, tzdata, zlib, libX11, libICE, libSM, fontconfig, xdg-utils

%description
Glide offline authenticated local file sharing GUI and daemon.

%install
mkdir -p %{buildroot}
cp -a "$root/usr" %{buildroot}/

%files
/usr/lib/glide
/usr/bin/glide
/usr/bin/glide-desktop
/usr/share/applications/glide.desktop
/usr/share/icons/hicolor/96x96/apps/dev.glide.app.png
/usr/lib/systemd/user/glide.service
EOF
  rpmbuild -bb --define "_topdir $rpm_top" --target "$rpm_arch" "$spec"
  cp "$rpm_top/RPMS/$rpm_arch/glide-${version}-1.${rpm_arch}.rpm" "$rpm"

  # Check the packaged executable bytes against the compiler output before
  # generating a manifest. This inspects payloads; it does not install packages.
  for format in portable deb rpm; do
    extracted="$work/$rid/extracted-$format"
    mkdir -p "$extracted"
    case "$format" in
      portable) tar -xzf "$portable" -C "$extracted" ;;
      deb) dpkg-deb --extract "$deb" "$extracted" ;;
      rpm) (cd "$extracted" && rpm2cpio "$rpm" | cpio -idm --quiet) ;;
    esac
    cmp "$gui/Glide.Linux.UI" "$extracted/usr/lib/glide/desktop/Glide.Linux.UI"
    cmp "$daemon/Glide.App" "$extracted/usr/lib/glide/daemon/Glide.App"
  done

  for artifact in "$portable" "$deb" "$rpm"; do test -s "$artifact"; done
  (cd "$out" && sha256sum "$(basename "$portable")" "$(basename "$deb")" "$(basename "$rpm")") \
    > "$out/SHA256SUMS-${rid}.txt"
done

cat > "$out/BUILD-MANIFEST.txt" <<EOF
source_commit=$commit
build_utc=$run_id
dotnet_sdk=$(dotnet --version)
build_arch=$(uname -m)
build_os=$(uname -sr)
rpm_version=$(rpmbuild --version)
package_version=$version
runtimes=$*
signing_status=unsigned_development
EOF
echo "Linux packages built from $commit in $out"
