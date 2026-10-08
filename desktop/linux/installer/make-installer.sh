#!/usr/bin/env bash
# Assemble the self-extracting Glide installer from a portable payload.
# Usage: make-installer.sh <payload.tar.gz> <output.run> <version> <commit> <x86_64|aarch64>
set -euo pipefail

[[ $# -eq 5 ]] || { echo "usage: $0 <payload.tar.gz> <output.run> <version> <commit> <arch>" >&2; exit 2; }
payload="$1" out="$2" version="$3" commit="$4" arch="$5"

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
test -s "$payload" || { echo "Payload not found: $payload" >&2; exit 2; }
case "$version" in *[!0-9.]*|.*|*.|"") echo "Invalid version" >&2; exit 2 ;; esac
case "$commit" in *[!0-9a-f]*|"") echo "Invalid commit" >&2; exit 2 ;; esac
case "$arch" in x86_64|aarch64) ;; *) echo "Invalid arch" >&2; exit 2 ;; esac

sha="$(sha256sum "$payload" | awk '{print $1}')"
# Strip CR so Windows checkouts still produce a valid shell script.
tr -d '\r' <"$here/installer-stub.sh" |
  sed -e "s|@VERSION@|$version|g" -e "s|@COMMIT@|$commit|g" \
      -e "s|@ARCH@|$arch|g" -e "s|@PAYLOAD_SHA256@|$sha|g" >"$out.tmp"
bash -n "$out.tmp"
grep -c '^__GLIDE_PAYLOAD_BELOW__$' "$out.tmp" | grep -qx 1 || { echo "Marker must appear exactly once" >&2; exit 1; }
cat "$payload" >>"$out.tmp"
chmod 0755 "$out.tmp"
mv -- "$out.tmp" "$out"
echo "Built $out (payload sha256 $sha)"
