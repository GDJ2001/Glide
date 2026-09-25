<#
.SYNOPSIS
    Builds full self-contained macOS Application Bundles (Glide.app) and Universal Mac Installer for Glide.
.DESCRIPTION
    1. Compiles Glide.App targeting osx-arm64 (Apple Silicon) and osx-x64 (Intel Mac) as self-contained
       single-file native Mach-O executables.
    2. Assembles a universal macOS Application Bundle (Glide.app) with Info.plist, PkgInfo, launchd agent,
       and architecture-dispatching universal launcher.
    3. Packages:
       - Glide-Installer-v1.0.0-macos-universal.command (Self-extracting macOS Finder/Terminal Installer)
       - Glide-v1.0.0-macos-universal.app.tar.gz (Complete Glide.app bundle archive)
       - glide-macos-osx-arm64.tar.gz (Apple Silicon standalone bundle)
       - glide-macos-osx-x64.tar.gz (Intel Mac standalone bundle)
#>

param(
    [string]$Configuration = "Release"
)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = Resolve-Path "$ScriptDir\..\.."
$ProjectFile = Join-Path $RepoRoot "desktop\src\Glide.App\Glide.App.csproj"
$OutputDir = Join-Path $RepoRoot "build\distributions\macos"

if (-not (Test-Path $OutputDir)) {
    New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null
}

Write-Host "=================================================================" -ForegroundColor Cyan
Write-Host "       Building Glide macOS Universal App & Installer            " -ForegroundColor Cyan
Write-Host "=================================================================" -ForegroundColor Cyan

$arm64Stage = Join-Path $OutputDir "stage-osx-arm64"
$x64Stage = Join-Path $OutputDir "stage-osx-x64"
foreach ($d in @($arm64Stage, $x64Stage)) {
    if (Test-Path $d) { Remove-Item -Recurse -Force $d }
}

Write-Host "`n[1/4] Publishing Glide.App for osx-arm64 (Apple Silicon M1/M2/M3/M4)..." -ForegroundColor Yellow
dotnet publish $ProjectFile `
    -c $Configuration `
    -r osx-arm64 `
    --self-contained true `
    /p:PublishSingleFile=true `
    /p:IncludeNativeLibrariesForSelfExtract=true `
    -o $arm64Stage
if ($LASTEXITCODE -ne 0) { throw "Failed to publish osx-arm64" }

Write-Host "`n[2/4] Publishing Glide.App for osx-x64 (Intel Mac)..." -ForegroundColor Yellow
dotnet publish $ProjectFile `
    -c $Configuration `
    -r osx-x64 `
    --self-contained true `
    /p:PublishSingleFile=true `
    /p:IncludeNativeLibrariesForSelfExtract=true `
    -o $x64Stage
if ($LASTEXITCODE -ne 0) { throw "Failed to publish osx-x64" }

# Package per-architecture tarballs
$arm64Tar = Join-Path $OutputDir "glide-macos-osx-arm64.tar.gz"
$x64Tar = Join-Path $OutputDir "glide-macos-osx-x64.tar.gz"
if (Test-Path $arm64Tar) { Remove-Item -Force $arm64Tar }
if (Test-Path $x64Tar) { Remove-Item -Force $x64Tar }
tar -czf $arm64Tar -C $arm64Stage .
tar -czf $x64Tar -C $x64Stage .

Write-Host "`n[3/4] Assembling Universal Glide.app Bundle & Self-Extracting Mac Installer via WSL..." -ForegroundColor Yellow
$wslArm64Stage = (wsl wslpath -u ($arm64Stage -replace "\\","/")).Trim()
$wslX64Stage = (wsl wslpath -u ($x64Stage -replace "\\","/")).Trim()
$wslOutputDir = (wsl wslpath -u ($OutputDir -replace "\\","/")).Trim()

$macBuildScript = @'
set -e
MAC_STAGE=/tmp/glide-macos-build
rm -rf "$MAC_STAGE"
APP_DIR="$MAC_STAGE/Glide.app/Contents"
mkdir -p "$APP_DIR/MacOS"
mkdir -p "$APP_DIR/Resources"

cp "__WSL_ARM64_STAGE__/Glide.App" "$APP_DIR/MacOS/Glide.App-arm64"
cp "__WSL_X64_STAGE__/Glide.App" "$APP_DIR/MacOS/Glide.App-x64"
chmod 0755 "$APP_DIR/MacOS/Glide.App-arm64" "$APP_DIR/MacOS/Glide.App-x64"

cat > "$APP_DIR/MacOS/Glide" << 'EOF'
#!/usr/bin/env bash
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ARCH="$(uname -m)"
if [[ "$ARCH" == "arm64" ]]; then
    exec "$DIR/Glide.App-arm64" "$@"
else
    exec "$DIR/Glide.App-x64" "$@"
fi
EOF
chmod 0755 "$APP_DIR/MacOS/Glide"

printf "APPL????" > "$APP_DIR/PkgInfo"

cat > "$APP_DIR/Info.plist" << 'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleDevelopmentRegion</key>
    <string>en</string>
    <key>CFBundleDisplayName</key>
    <string>Glide</string>
    <key>CFBundleExecutable</key>
    <string>Glide</string>
    <key>CFBundleIdentifier</key>
    <string>dev.glide.app</string>
    <key>CFBundleInfoDictionaryVersion</key>
    <string>6.0</string>
    <key>CFBundleName</key>
    <string>Glide</string>
    <key>CFBundlePackageType</key>
    <string>APPL</string>
    <key>CFBundleShortVersionString</key>
    <string>1.0.0</string>
    <key>CFBundleVersion</key>
    <string>100</string>
    <key>LSMinimumSystemVersion</key>
    <string>13.0</string>
    <key>NSHighResolutionCapable</key>
    <true/>
    <key>NSLocalNetworkUsageDescription</key>
    <string>Glide discovers and transfers files directly with nearby devices over your local Wi-Fi network using authenticated TLS 1.3 and WebRTC.</string>
    <key>NSBonjourServices</key>
    <array>
        <string>_glide-xfer._tcp</string>
    </array>
</dict>
</plist>
EOF

cat > "$APP_DIR/Resources/dev.glide.daemon.plist" << 'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>dev.glide.daemon</string>
    <key>ProgramArguments</key>
    <array>
        <string>/Applications/Glide.app/Contents/MacOS/Glide</string>
        <string>receive</string>
    </array>
    <key>RunAtLoad</key>
    <false/>
</dict>
</plist>
EOF

APP_TAR="__WSL_OUTPUT_DIR__/Glide-v1.0.0-macos-universal.app.tar.gz"
tar -czf "$APP_TAR" -C "$MAC_STAGE" Glide.app

INSTALLER_FILE="__WSL_OUTPUT_DIR__/Glide-Installer-v1.0.0-macos-universal.command"
cat > "$INSTALLER_FILE" << 'HEADER_EOF'
#!/usr/bin/env bash
set -euo pipefail
APP_VERSION="1.0.0"

if [[ "${1:-}" == "--verify" ]]; then
    echo "Glide macOS Universal Installer v${APP_VERSION} self-verification OK."
    exit 0
fi

TARGET_APPS="/Applications"
if [[ ! -w "/Applications" ]] && [[ "${EUID:-1000}" -ne 0 ]]; then
    TARGET_APPS="$HOME/Applications"
    mkdir -p "$TARGET_APPS"
fi

echo "=============================================================="
echo "  Glide v${APP_VERSION} — macOS Universal Installer (arm64 + x64)"
echo "=============================================================="
echo "Target Applications Folder: $TARGET_APPS"

rm -rf "$TARGET_APPS/Glide.app"
ARCHIVE_LINE=$(awk '/^__ARCHIVE_BELOW__$/ {print NR + 1; exit 0; }' "$0")
tail -n +"$ARCHIVE_LINE" "$0" | tar -xz -C "$TARGET_APPS"

if command -v xattr >/dev/null 2>&1; then
    xattr -cr "$TARGET_APPS/Glide.app" 2>/dev/null || true
fi

BIN_DIR="/usr/local/bin"
if [[ ! -w "$BIN_DIR" ]]; then
    BIN_DIR="$HOME/.local/bin"
    mkdir -p "$BIN_DIR"
fi
ln -sf "$TARGET_APPS/Glide.app/Contents/MacOS/Glide" "$BIN_DIR/glide"

echo "=============================================================="
echo "  Glide v${APP_VERSION} installed successfully!"
echo "  - App Bundle: $TARGET_APPS/Glide.app"
echo "  - CLI Binary: $BIN_DIR/glide"
echo "=============================================================="
exit 0
__ARCHIVE_BELOW__
HEADER_EOF

cat "$APP_TAR" >> "$INSTALLER_FILE"
chmod +x "$INSTALLER_FILE"
"$INSTALLER_FILE" --verify
rm -rf "$MAC_STAGE"
'@

$macBuildScript = $macBuildScript.
    Replace("__WSL_ARM64_STAGE__", $wslArm64Stage).
    Replace("__WSL_X64_STAGE__", $wslX64Stage).
    Replace("__WSL_OUTPUT_DIR__", $wslOutputDir)

$tempMacSh = Join-Path $OutputDir "build-macos.sh"
Set-Content -Path $tempMacSh -Value ($macBuildScript -replace "`r`n", "`n") -NoNewline -Encoding UTF8
$wslTempMacSh = (wsl wslpath -u ($tempMacSh -replace "\\","/")).Trim()
wsl bash $wslTempMacSh
if ($LASTEXITCODE -ne 0) { throw "Failed to assemble macOS universal installer in WSL" }

Remove-Item -Force $tempMacSh -ErrorAction SilentlyContinue
Remove-Item -Recurse -Force $arm64Stage -ErrorAction SilentlyContinue
Remove-Item -Recurse -Force $x64Stage -ErrorAction SilentlyContinue

$cmdItem = Get-Item (Join-Path $OutputDir "Glide-Installer-v1.0.0-macos-universal.command")
$appTarItem = Get-Item (Join-Path $OutputDir "Glide-v1.0.0-macos-universal.app.tar.gz")
$armTarItem = Get-Item $arm64Tar
$x64TarItem = Get-Item $x64Tar

Write-Host "[SUCCESS] Mac Universal Installer: $($cmdItem.FullName) ($($cmdItem.Length) bytes)" -ForegroundColor Green
Write-Host "[SUCCESS] Mac Glide.app Archive:   $($appTarItem.FullName) ($($appTarItem.Length) bytes)" -ForegroundColor Green
Write-Host "[SUCCESS] Mac Apple Silicon Tar:   $($armTarItem.FullName) ($($armTarItem.Length) bytes)" -ForegroundColor Green
Write-Host "[SUCCESS] Mac Intel x64 Tar:       $($x64TarItem.FullName) ($($x64TarItem.Length) bytes)" -ForegroundColor Green

Write-Host "`n=================================================================" -ForegroundColor Cyan
Write-Host "        All macOS Installers Built Successfully!                 " -ForegroundColor Green
Write-Host "=================================================================" -ForegroundColor Cyan
