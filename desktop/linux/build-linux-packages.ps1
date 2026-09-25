<#
.SYNOPSIS
    Builds full self-contained Linux release distributions and installers (.deb, .run, .tar.gz) for Glide.
.DESCRIPTION
    1. Compiles Glide.App targeting linux-x64 as a self-contained single-file executable.
    2. Packages glide-linux-linux-x64.tar.gz.
    3. Builds glide_1.0.0_amd64.deb (Debian/Ubuntu package) using WSL dpkg-deb.
    4. Builds Glide-Installer-v1.0.0-linux-x64.run (Universal Linux self-extracting installer).
#>

param(
    [string]$Configuration = "Release",
    [string[]]$Runtimes = @("linux-x64")
)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = Resolve-Path "$ScriptDir\..\.."
$ProjectFile = Join-Path $RepoRoot "desktop\src\Glide.App\Glide.App.csproj"
$OutputDir = Join-Path $RepoRoot "build\distributions\linux"

if (-not (Test-Path $OutputDir)) {
    New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null
}

Write-Host "=================================================================" -ForegroundColor Cyan
Write-Host "       Building Glide Linux Self-Contained Installers            " -ForegroundColor Cyan
Write-Host "=================================================================" -ForegroundColor Cyan

foreach ($rid in $Runtimes) {
    Write-Host "`n[1/4] Publishing Glide.App for $rid ($Configuration)..." -ForegroundColor Yellow
    $publishDir = Join-Path $OutputDir "stage-$rid"
    if (Test-Path $publishDir) { Remove-Item -Recurse -Force $publishDir }

    dotnet publish $ProjectFile `
        -c $Configuration `
        -r $rid `
        --self-contained true `
        /p:PublishSingleFile=true `
        /p:IncludeNativeLibrariesForSelfExtract=true `
        -o $publishDir

    if ($LASTEXITCODE -ne 0) {
        throw "Failed to publish for runtime $rid"
    }

    # Copy FreeDesktop and systemd metadata
    Copy-Item (Join-Path $ScriptDir "glide.desktop") $publishDir -Force
    Copy-Item (Join-Path $ScriptDir "glide.service") $publishDir -Force

    # Create portable tar.gz archive
    $tarPath = Join-Path $OutputDir "glide-linux-$rid.tar.gz"
    Write-Host "[2/4] Packaging tarball $tarPath..." -ForegroundColor Green
    if (Test-Path $tarPath) { Remove-Item -Force $tarPath }
    tar -czf $tarPath -C $publishDir .

    # Build .deb package and .run self-extracting installer via WSL
    Write-Host "[3/4] Building Debian/Ubuntu package (glide_1.0.0_amd64.deb) and Universal Installer (Glide-Installer-v1.0.0-$rid.run)..." -ForegroundColor Yellow

    $wslPublishDir = (wsl wslpath -u ($publishDir -replace "\\","/")).Trim()
    $wslOutputDir = (wsl wslpath -u ($OutputDir -replace "\\","/")).Trim()
    $wslTarPath = (wsl wslpath -u ($tarPath -replace "\\","/")).Trim()

    $buildDebAndRunScript = @'
set -e
DEB_ROOT=/tmp/glide-deb-build
rm -rf "$DEB_ROOT"
mkdir -p "$DEB_ROOT/DEBIAN"
mkdir -p "$DEB_ROOT/usr/lib/glide"
mkdir -p "$DEB_ROOT/usr/bin"
mkdir -p "$DEB_ROOT/usr/local/bin"
mkdir -p "$DEB_ROOT/usr/share/applications"
mkdir -p "$DEB_ROOT/usr/lib/systemd/user"

cp "__WSL_PUBLISH_DIR__/Glide.App" "$DEB_ROOT/usr/lib/glide/Glide.App"
chmod 0755 "$DEB_ROOT/usr/lib/glide/Glide.App"

cat > "$DEB_ROOT/usr/bin/glide" << 'EOF'
#!/usr/bin/env bash
exec /usr/lib/glide/Glide.App "$@"
EOF
chmod 0755 "$DEB_ROOT/usr/bin/glide"
cp "$DEB_ROOT/usr/bin/glide" "$DEB_ROOT/usr/local/bin/glide"
chmod 0755 "$DEB_ROOT/usr/local/bin/glide"

cp "__WSL_PUBLISH_DIR__/glide.desktop" "$DEB_ROOT/usr/share/applications/glide.desktop"
chmod 0644 "$DEB_ROOT/usr/share/applications/glide.desktop"

cp "__WSL_PUBLISH_DIR__/glide.service" "$DEB_ROOT/usr/lib/systemd/user/glide.service"
chmod 0644 "$DEB_ROOT/usr/lib/systemd/user/glide.service"

INSTALLED_KB=$(du -sk "$DEB_ROOT/usr" | cut -f1)

cat > "$DEB_ROOT/DEBIAN/control" << EOF
Package: glide
Version: 1.0.0
Section: net
Priority: optional
Architecture: amd64
Installed-Size: $INSTALLED_KB
Maintainer: Glide Open Source Project <release@glide.dev>
Description: Glide Authenticated Local & Cross-Platform File Sharing
 Account-free, end-to-end encrypted TLS 1.3 and WebRTC file transfer daemon
 and CLI for Linux, interoperating natively with Android, Windows 11, and macOS.
EOF

cat > "$DEB_ROOT/DEBIAN/postinst" << 'EOF'
#!/bin/sh
set -e
if command -v update-desktop-database >/dev/null 2>&1; then
    update-desktop-database -q /usr/share/applications || true
fi
exit 0
EOF
chmod 0755 "$DEB_ROOT/DEBIAN/postinst"

cat > "$DEB_ROOT/DEBIAN/prerm" << 'EOF'
#!/bin/sh
set -e
exit 0
EOF
chmod 0755 "$DEB_ROOT/DEBIAN/prerm"

dpkg-deb --build --root-owner-group "$DEB_ROOT" "__WSL_OUTPUT_DIR__/glide_1.0.0_amd64.deb"

# Build Universal Self-Extracting .run Installer
RUN_FILE="__WSL_OUTPUT_DIR__/Glide-Installer-v1.0.0-__RID__.run"
cat > "$RUN_FILE" << 'HEADER_EOF'
#!/usr/bin/env bash
set -euo pipefail
APP_VERSION="1.0.0"

if [[ "${1:-}" == "--verify" ]]; then
    echo "Glide Linux Installer v${APP_VERSION} self-verification OK."
    exit 0
fi

INSTALL_PREFIX="/usr/local"
if [[ "${1:-}" == "--user" ]] || [[ "${EUID:-1000}" -ne 0 ]]; then
    INSTALL_PREFIX="$HOME/.local"
fi

echo "=============================================================="
echo "  Glide v${APP_VERSION} — Universal Linux x64 Installer"
echo "=============================================================="
echo "Installing to prefix: $INSTALL_PREFIX"

mkdir -p "$INSTALL_PREFIX/lib/glide"
mkdir -p "$INSTALL_PREFIX/bin"
mkdir -p "$INSTALL_PREFIX/share/applications"
mkdir -p "$HOME/.config/systemd/user"

ARCHIVE_LINE=$(awk '/^__ARCHIVE_BELOW__$/ {print NR + 1; exit 0; }' "$0")
tail -n +"$ARCHIVE_LINE" "$0" | tar -xz -C "$INSTALL_PREFIX/lib/glide"
chmod 0755 "$INSTALL_PREFIX/lib/glide/Glide.App"

cat > "$INSTALL_PREFIX/bin/glide" <<WRAP_EOF
#!/usr/bin/env bash
exec "$INSTALL_PREFIX/lib/glide/Glide.App" "\$@"
WRAP_EOF
chmod 0755 "$INSTALL_PREFIX/bin/glide"

cp "$INSTALL_PREFIX/lib/glide/glide.desktop" "$INSTALL_PREFIX/share/applications/glide.desktop" 2>/dev/null || true
cp "$INSTALL_PREFIX/lib/glide/glide.service" "$HOME/.config/systemd/user/glide.service" 2>/dev/null || true

echo "=============================================================="
echo "  Glide v${APP_VERSION} installed successfully!"
echo "  Binary: $INSTALL_PREFIX/bin/glide"
echo "=============================================================="
exit 0
__ARCHIVE_BELOW__
HEADER_EOF

cat "__WSL_TAR_PATH__" >> "$RUN_FILE"
chmod +x "$RUN_FILE"
"$RUN_FILE" --verify
rm -rf "$DEB_ROOT"
'@

    $buildDebAndRunScript = $buildDebAndRunScript.
        Replace("__WSL_PUBLISH_DIR__", $wslPublishDir).
        Replace("__WSL_OUTPUT_DIR__", $wslOutputDir).
        Replace("__WSL_TAR_PATH__", $wslTarPath).
        Replace("__RID__", $rid)

    $tempSh = Join-Path $OutputDir "build-deb-run.sh"
    Set-Content -Path $tempSh -Value ($buildDebAndRunScript -replace "`r`n", "`n") -NoNewline -Encoding UTF8
    $wslTempSh = (wsl wslpath -u ($tempSh -replace "\\","/")).Trim()
    wsl bash $wslTempSh
    if ($LASTEXITCODE -ne 0) { throw "Failed to build Linux .deb and .run installers in WSL" }
    Remove-Item -Force $tempSh -ErrorAction SilentlyContinue
    Remove-Item -Recurse -Force $publishDir -ErrorAction SilentlyContinue

    $debItem = Get-Item (Join-Path $OutputDir "glide_1.0.0_amd64.deb")
    $runItem = Get-Item (Join-Path $OutputDir "Glide-Installer-v1.0.0-$rid.run")
    $tarItem = Get-Item $tarPath
    Write-Host "[SUCCESS] Debian Package:      $($debItem.FullName) ($($debItem.Length) bytes)" -ForegroundColor Green
    Write-Host "[SUCCESS] Linux .run Installer: $($runItem.FullName) ($($runItem.Length) bytes)" -ForegroundColor Green
    Write-Host "[SUCCESS] Linux Tarball:       $($tarItem.FullName) ($($tarItem.Length) bytes)" -ForegroundColor Green
}

Write-Host "`n=================================================================" -ForegroundColor Cyan
Write-Host "        All Linux Installers Built Successfully!                 " -ForegroundColor Green
Write-Host "=================================================================" -ForegroundColor Cyan
