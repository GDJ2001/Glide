[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet("Windows", "Linux", "Android")]
    [string]$Platform,

    [ValidateSet("Full", "Install", "Upgrade", "ForceStopAndVerify", "UninstallKeepData", "ReinstallAndVerifyRetention", "Probe")]
    [string]$Action = "Full",

    [string]$PackagePath = "",
    [string]$UpgradePackagePath = "",
    [string]$ZipPackagePath = "",
    [string]$MsiPackagePath = "",
    [string]$SandboxRoot = "",
    [string]$VmHost = $env:GLIDE_E2E_VM_HOST,
    [string]$VmUser = $env:GLIDE_E2E_VM_USER,
    [string]$AndroidSerial = $env:GLIDE_E2E_ANDROID_SERIAL,
    [string]$AndroidPackage = "dev.glide.app.debug",
    [switch]$DryRun,
    [switch]$LiveInstall
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$repoRoot = Split-Path (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent) -Parent

if ($DryRun) {
    switch ($Platform) {
        "Windows" {
            $winNsis = Join-Path $repoRoot "desktop\windows\installer\Glide.nsi"
            $winWix = Join-Path $repoRoot "desktop\windows\installer\Glide.wxs"
            $winPkg = Join-Path $repoRoot "desktop\windows\package-windows.ps1"
            $winMsi = Join-Path $repoRoot "desktop\windows\package-windows-msi.ps1"
            $checks = [pscustomobject]@{
                platform          = "Windows"
                dryRun            = $true
                windowsNsisScript = (Test-Path -LiteralPath $winNsis)
                windowsWixScript  = (Test-Path -LiteralPath $winWix)
                packageScript     = (Test-Path -LiteralPath $winPkg)
                msiPackageScript  = (Test-Path -LiteralPath $winMsi)
            }
            $allOk = $checks.windowsNsisScript -and $checks.windowsWixScript -and $checks.packageScript -and $checks.msiPackageScript
        }
        "Linux" {
            $linuxInstallerStub = Join-Path $repoRoot "desktop\linux\installer\installer-stub.sh"
            $makeInstaller = Join-Path $repoRoot "desktop\linux\installer\make-installer.sh"
            $linuxPkgScript = Join-Path $repoRoot "tools\package-linux-native.sh"
            $linuxTestScript = Join-Path $repoRoot "tools\test-linux-installer.sh"
            $serviceUnit = Join-Path $repoRoot "desktop\linux\glide.service"
            $checks = [pscustomobject]@{
                platform           = "Linux"
                dryRun             = $true
                linuxInstallerStub = (Test-Path -LiteralPath $linuxInstallerStub)
                makeInstaller      = (Test-Path -LiteralPath $makeInstaller)
                linuxPkgScript     = (Test-Path -LiteralPath $linuxPkgScript)
                linuxTestScript    = (Test-Path -LiteralPath $linuxTestScript)
                systemdUnit        = (Test-Path -LiteralPath $serviceUnit)
            }
            $allOk = $checks.linuxInstallerStub -and $checks.makeInstaller -and $checks.linuxPkgScript -and $checks.linuxTestScript -and $checks.systemdUnit
        }
        "Android" {
            $androidGradle = Join-Path $repoRoot "android\app\build.gradle.kts"
            $androidManifest = Join-Path $repoRoot "android\app\src\main\AndroidManifest.xml"
            $checks = [pscustomobject]@{
                platform        = "Android"
                dryRun          = $true
                androidGradle   = (Test-Path -LiteralPath $androidGradle)
                androidManifest = (Test-Path -LiteralPath $androidManifest)
            }
            $allOk = $checks.androidGradle -and $checks.androidManifest
        }
    }
    $checks | ConvertTo-Json -Depth 3
    if (-not $allOk) { exit 1 }
    exit 0
}

function Export-RegTreeIfExists {
    param([string]$RegKey, [string]$OutFile)
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    & reg.exe query $RegKey >$null 2>&1
    $exists = ($LASTEXITCODE -eq 0)
    if ($exists) {
        & reg.exe export $RegKey $OutFile /y >$null 2>&1
    }
    $ErrorActionPreference = $prev
    return $exists
}

function Restore-RegTree {
    param([string]$RegKey, [bool]$ExistedBefore, [string]$BackupFile)
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    & reg.exe delete $RegKey /f >$null 2>&1
    if ($ExistedBefore -and (Test-Path -LiteralPath $BackupFile)) {
        & reg.exe import $BackupFile >$null 2>&1
    }
    $ErrorActionPreference = $prev
}

switch ($Platform) {
    "Linux" {
        if ([string]::IsNullOrWhiteSpace($PackagePath)) {
            throw "PackagePath is required for Linux lifecycle verification."
        }
        if (-not (Test-Path -LiteralPath $PackagePath)) {
            throw "Linux package not found: $PackagePath"
        }
        $vmDriver = Join-Path $PSScriptRoot "Invoke-VmRemote.ps1"
        if ([string]::IsNullOrWhiteSpace($SandboxRoot)) {
            $SandboxRoot = "/tmp/glide-e2e/pkg-$([guid]::NewGuid().ToString('N').Substring(0,8))"
        }
        $probeJson = & pwsh -NoProfile -File $vmDriver -Action Probe -VmHost $VmHost -VmUser $VmUser | ConvertFrom-Json
        if (-not $probeJson.reachable) {
            throw "VM unreachable for Linux package lifecycle test: $($probeJson.reason)"
        }

        if ($PackagePath -like "*.run") {
            $remoteRun = "$SandboxRoot/glide-installer.run"
            & pwsh -NoProfile -File $vmDriver -Action Deploy -VmHost $VmHost -VmUser $VmUser -SandboxId "pkg" -LocalPath $PackagePath -RemotePath $remoteRun | Out-Null
            $hasUpgrade = $false
            $remoteUpg = "$SandboxRoot/glide-upgrade.run"
            if (-not [string]::IsNullOrWhiteSpace($UpgradePackagePath) -and (Test-Path -LiteralPath $UpgradePackagePath)) {
                & pwsh -NoProfile -File $vmDriver -Action Deploy -VmHost $VmHost -VmUser $VmUser -SandboxId "pkg" -LocalPath $UpgradePackagePath -RemotePath $remoteUpg | Out-Null
                $hasUpgrade = $true
            }

            $runScript = @'
set -euo pipefail
INSTALLER="__REMOTE_RUN__"
UPGRADE_INSTALLER="__REMOTE_UPG__"
PREFIX="__SANDBOX__/prefix"
fail() { echo "FAIL: $*" >&2; exit 1; }
chmod +x "$INSTALLER"
"$INSTALLER" --info | grep -q '^Glide ' || fail "--info"
"$INSTALLER" --verify >/dev/null || fail "--verify"

BAD="__SANDBOX__/tampered.run"
cp "$INSTALLER" "$BAD"
printf 'x' >> "$BAD"
chmod +x "$BAD"
if "$BAD" --verify 2>&1 | tee "__SANDBOX__/tamper.out"; then
    fail "tampered payload passed --verify"
fi
grep -qi "Payload checksum mismatch" "__SANDBOX__/tamper.out" || fail "missing checksum mismatch message"
if "$BAD" --prefix "__SANDBOX__/tampered-prefix" --no-deps-check --no-cache-refresh >/dev/null 2>&1; then
    fail "tampered payload installed"
fi
test ! -e "__SANDBOX__/tampered-prefix/bin/glide" || fail "tampered install created bin/glide"

if "$INSTALLER" --prefix /usr --no-deps-check >/dev/null 2>&1; then fail "/usr allowed"; fi
if "$INSTALLER" --bogus >/dev/null 2>&1; then fail "--bogus allowed"; fi
if "$INSTALLER" --prefix relative/path >/dev/null 2>&1; then fail "relative prefix allowed"; fi

"$INSTALLER" --prefix "$PREFIX" --no-deps-check --no-cache-refresh --enable-service >/dev/null
for f in lib/glide/desktop/Glide.Linux.UI lib/glide/daemon/Glide.App lib/glide/SOURCE_COMMIT \
         lib/glide/install-manifest.txt lib/glide/uninstall.sh share/applications/glide-desktop.desktop \
         share/icons/hicolor/128x128/apps/dev.glide.app.png share/icons/hicolor/512x512/apps/dev.glide.app.png; do
    test -f "$PREFIX/$f" || fail "missing $f"
done
test -x "$PREFIX/bin/glide-desktop" && test -x "$PREFIX/bin/glide" && test -x "$PREFIX/bin/glide-uninstall" || fail "launchers not executable"
test -f "$HOME/.config/systemd/user/glide.service" || fail "service unit missing"
grep -Fq "Exec=$PREFIX/bin/glide-desktop %F" "$PREFIX/share/applications/glide-desktop.desktop" || fail "desktop Exec not absolute"
grep -Fq "Icon=$PREFIX/share/icons/hicolor/128x128/apps/dev.glide.app.png" "$PREFIX/share/applications/glide-desktop.desktop" || fail "desktop Icon not absolute"
grep -Fq "ExecStart=$PREFIX/bin/glide " "$HOME/.config/systemd/user/glide.service" || fail "service ExecStart not rewritten"
grep -Fq "ExecStart=/usr/bin" "$HOME/.config/systemd/user/glide.service" && fail "service still points at /usr/bin"

FP_BEFORE="$("$PREFIX/bin/glide" identity show | awk '/Persistent identity:/ {print $3}')"
test -n "$FP_BEFORE" || fail "empty FP_BEFORE"
test "$(stat -c '%a' "$XDG_CONFIG_HOME/glide/identity-v1.pk8")" = "600" || fail "identity key not 0600"

if [ "__HAS_UPG__" = "1" ]; then
    chmod +x "$UPGRADE_INSTALLER"
    "$UPGRADE_INSTALLER" --prefix "$PREFIX" --no-deps-check --no-cache-refresh >/dev/null
else
    "$INSTALLER" --prefix "$PREFIX" --no-deps-check --no-cache-refresh >/dev/null
fi
test -f "$PREFIX/lib/glide/install-manifest.txt" || fail "upgrade lost manifest"
FP_AFTER_UPG="$("$PREFIX/bin/glide" identity show | awk '/Persistent identity:/ {print $3}')"
test "$FP_BEFORE" = "$FP_AFTER_UPG" || fail "identity fingerprint changed on upgrade"

mkdir -p "$XDG_CONFIG_HOME/glide" "$XDG_DATA_HOME/glide"
echo '{"AppearanceMode":"Dark"}' > "$XDG_CONFIG_HOME/glide/settings.json"
printf 'keep-sentinel-payload' > "$XDG_DATA_HOME/glide/sentinel.bin"
SENTINEL_SHA="$(sha256sum "$XDG_DATA_HOME/glide/sentinel.bin" | awk '{print $1}')"

"$PREFIX/bin/glide-uninstall" --no-cache-refresh >/dev/null
LEFTOVER="$(find "$PREFIX" "$HOME/.config/systemd" -mindepth 1 2>/dev/null | head -n 5 || true)"
test -z "$LEFTOVER" || fail "leftover files: $LEFTOVER"
test -f "$XDG_CONFIG_HOME/glide/identity-v1.pk8" || fail "identity lost on uninstall"
test -f "$XDG_CONFIG_HOME/glide/settings.json" || fail "settings.json lost on uninstall"
test "$(sha256sum "$XDG_DATA_HOME/glide/sentinel.bin" | awk '{print $1}')" = "$SENTINEL_SHA" || fail "sentinel SHA changed"

if "$INSTALLER" --prefix "$PREFIX" --uninstall >/dev/null 2>&1; then fail "second uninstall should fail"; fi
echo "LINUX_RUN_LIFECYCLE_OK fp=$FP_BEFORE sha=$SENTINEL_SHA"
'@
            $runScript = $runScript.Replace("__REMOTE_RUN__", $remoteRun).Replace("__REMOTE_UPG__", $remoteUpg).Replace("__SANDBOX__", $SandboxRoot).Replace("__HAS_UPG__", $(if ($hasUpgrade) { "1" } else { "0" }))
            $execRes = & pwsh -NoProfile -File $vmDriver -Action Exec -VmHost $VmHost -VmUser $VmUser -SandboxId "pkg-run" -UseSystemPath -TimeoutSeconds 120 -Command $runScript
            $execCode = $LASTEXITCODE
            Write-Output ($execRes | Out-String)
            if ($execCode -ne 0) { throw "Linux .run lifecycle failed." }
        }
        elseif ($PackagePath -like "*.deb") {
            $remoteDeb = "$SandboxRoot/glide.deb"
            & pwsh -NoProfile -File $vmDriver -Action Deploy -VmHost $VmHost -VmUser $VmUser -SandboxId "pkg" -LocalPath $PackagePath -RemotePath $remoteDeb | Out-Null
            $remoteUpgDeb = "$SandboxRoot/glide-upg.deb"
            if (-not [string]::IsNullOrWhiteSpace($UpgradePackagePath) -and (Test-Path -LiteralPath $UpgradePackagePath)) {
                & pwsh -NoProfile -File $vmDriver -Action Deploy -VmHost $VmHost -VmUser $VmUser -SandboxId "pkg" -LocalPath $UpgradePackagePath -RemotePath $remoteUpgDeb | Out-Null
            }
            else {
                $remoteUpgDeb = $remoteDeb
            }

            $preCheck = & pwsh -NoProfile -File $vmDriver -Action Exec -VmHost $VmHost -VmUser $VmUser -SandboxId "pkg-deb-pre" -UseSystemPath -Command "dpkg-query -W -f='${Status}' glide 2>/dev/null | grep -q 'install ok installed' && echo INSTALLED || echo ABSENT"
            $wasInstalled = (($preCheck | Out-String) -match "INSTALLED")

            try {
                & pwsh -NoProfile -File $vmDriver -Action InstallDeb -VmHost $VmHost -VmUser $VmUser -RemotePath $remoteDeb | Out-Null
                if ($LASTEXITCODE -ne 0) { throw "Initial InstallDeb failed." }

                $seedScript = @'
set -euo pipefail
FP_BEFORE="$(/usr/bin/glide identity show | awk '/Persistent identity:/ {print $3}')"
test -n "$FP_BEFORE"
mkdir -p "$XDG_CONFIG_HOME/glide" "$HOME/Downloads"
cat > "$XDG_CONFIG_HOME/glide/daemon.env" <<'EOF'
GLIDE_TRUSTED_PEER=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
GLIDE_MAX_OFFER_BYTES=10485760
EOF
chmod 600 "$XDG_CONFIG_HOME/glide/daemon.env"
cat > "$XDG_CONFIG_HOME/glide/trust-v1.json" <<EOF
{
  "version": 1,
  "localIdentityFingerprintHex": "$FP_BEFORE",
  "peers": [],
  "headlessPolicies": []
}
EOF
chmod 600 "$XDG_CONFIG_HOME/glide/trust-v1.json"
printf 'deb-retention-payload-v1' > "$HOME/Downloads/keep.bin"
echo "FP=$FP_BEFORE"
echo "ID_SHA=$(sha256sum "$XDG_CONFIG_HOME/glide/identity-v1.pk8" | awk '{print $1}')"
echo "ENV_SHA=$(sha256sum "$XDG_CONFIG_HOME/glide/daemon.env" | awk '{print $1}')"
echo "TR_SHA=$(sha256sum "$XDG_CONFIG_HOME/glide/trust-v1.json" | awk '{print $1}')"
echo "KEEP_SHA=$(sha256sum "$HOME/Downloads/keep.bin" | awk '{print $1}')"
'@
                $seedOut = (& pwsh -NoProfile -File $vmDriver -Action Exec -VmHost $VmHost -VmUser $VmUser -SandboxId "pkg-deb-state" -UseSystemPath -Command $seedScript | ConvertFrom-Json).output

                # Upgrade .deb
                & pwsh -NoProfile -File $vmDriver -Action InstallDeb -VmHost $VmHost -VmUser $VmUser -RemotePath $remoteUpgDeb | Out-Null
                if ($LASTEXITCODE -ne 0) { throw "Upgrade InstallDeb failed." }

                $verifyScript = @'
set -euo pipefail
FP_NOW="$(/usr/bin/glide identity show | awk '/Persistent identity:/ {print $3}')"
echo "FP=$FP_NOW"
echo "ID_SHA=$(sha256sum "$XDG_CONFIG_HOME/glide/identity-v1.pk8" | awk '{print $1}')"
echo "ENV_SHA=$(sha256sum "$XDG_CONFIG_HOME/glide/daemon.env" | awk '{print $1}')"
echo "TR_SHA=$(sha256sum "$XDG_CONFIG_HOME/glide/trust-v1.json" | awk '{print $1}')"
echo "KEEP_SHA=$(sha256sum "$HOME/Downloads/keep.bin" | awk '{print $1}')"
'@
                $afterUpgrade = (& pwsh -NoProfile -File $vmDriver -Action Exec -VmHost $VmHost -VmUser $VmUser -SandboxId "pkg-deb-state" -UseSystemPath -Command $verifyScript | ConvertFrom-Json).output
                if ($seedOut.Trim() -ne $afterUpgrade.Trim()) {
                    throw "Debian upgrade mutated persisted state!`nBefore:`n$seedOut`nAfter:`n$afterUpgrade"
                }

                # Uninstall .deb and verify user data retained
                & pwsh -NoProfile -File $vmDriver -Action UninstallDeb -VmHost $VmHost -VmUser $VmUser | Out-Null
                if ($LASTEXITCODE -ne 0) { throw "UninstallDeb failed." }

                $postUninst = (& pwsh -NoProfile -File $vmDriver -Action Exec -VmHost $VmHost -VmUser $VmUser -SandboxId "pkg-deb-state" -UseSystemPath -Command @'
set -euo pipefail
test ! -e /usr/bin/glide
echo "ID_SHA=$(sha256sum "$XDG_CONFIG_HOME/glide/identity-v1.pk8" | awk '{print $1}')"
echo "ENV_SHA=$(sha256sum "$XDG_CONFIG_HOME/glide/daemon.env" | awk '{print $1}')"
echo "TR_SHA=$(sha256sum "$XDG_CONFIG_HOME/glide/trust-v1.json" | awk '{print $1}')"
echo "KEEP_SHA=$(sha256sum "$HOME/Downloads/keep.bin" | awk '{print $1}')"
'@ | ConvertFrom-Json).output

                foreach ($line in @("ID_SHA=", "ENV_SHA=", "TR_SHA=", "KEEP_SHA=")) {
                    $b = ($seedOut -split "`r?`n" | Where-Object { $_ -like "$line*" })[0]
                    $a = ($postUninst -split "`r?`n" | Where-Object { $_ -like "$line*" })[0]
                    if ($b -ne $a) { throw "Data retention mismatch after dpkg -r: '$b' vs '$a'" }
                }

                [pscustomobject]@{
                    targetLabel = "VM"
                    platform    = "Linux"
                    packageType = "deb"
                    retained    = $true
                } | ConvertTo-Json -Depth 3
            }
            finally {
                if ($wasInstalled) {
                    & pwsh -NoProfile -File $vmDriver -Action InstallDeb -VmHost $VmHost -VmUser $VmUser -RemotePath $remoteDeb | Out-Null
                }
            }
        }
        else {
            throw "Unsupported Linux package extension: $PackagePath"
        }
        exit 0
    }

    "Windows" {
        if ([string]::IsNullOrWhiteSpace($PackagePath) -or -not (Test-Path -LiteralPath $PackagePath)) {
            throw "Windows package not found: $PackagePath"
        }
        if (-not $LiveInstall) {
            throw "Windows live installation modifies host state; pass -LiveInstall explicitly when running in an isolated Windows UI test environment."
        }
        $winUiDriver = Join-Path $PSScriptRoot "Invoke-WindowsUi.ps1"
        & pwsh -NoProfile -File $winUiDriver -Action PreflightCheck | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw "Refusing Windows package lifecycle test while user Glide.exe is running."
        }

        if ([string]::IsNullOrWhiteSpace($SandboxRoot)) {
            $SandboxRoot = Join-Path $env:TEMP ("glide-e2e-winpkg-" + [guid]::NewGuid().ToString("N").Substring(0, 8))
        }
        New-Item -ItemType Directory -Path $SandboxRoot -Force | Out-Null

        $appDataGlide = Join-Path $env:LOCALAPPDATA "Glide"
        $appDataBackup = Join-Path $SandboxRoot "appdata-backup"
        $appDataExisted = Test-Path -LiteralPath $appDataGlide
        if ($appDataExisted) {
            New-Item -ItemType Directory -Path $appDataBackup -Force | Out-Null
            Copy-Item -Path "$appDataGlide\*" -Destination $appDataBackup -Recurse -Force -ErrorAction SilentlyContinue
        }

        $regGlide = "HKCU\Software\Glide"
        $regUri = "HKCU\Software\Classes\glide"
        $regUninst = "HKCU\Software\Microsoft\Windows\CurrentVersion\Uninstall\Glide"
        $regGlideBak = Join-Path $SandboxRoot "reg-glide.reg"
        $regUriBak = Join-Path $SandboxRoot "reg-uri.reg"
        $regUninstBak = Join-Path $SandboxRoot "reg-uninst.reg"
        $hadRegGlide = Export-RegTreeIfExists -RegKey $regGlide -OutFile $regGlideBak
        $hadRegUri = Export-RegTreeIfExists -RegKey $regUri -OutFile $regUriBak
        $hadRegUninst = Export-RegTreeIfExists -RegKey $regUninst -OutFile $regUninstBak

        $smGlideDir = Join-Path ([Environment]::GetFolderPath("StartMenu")) "Programs\Glide"
        $desktopLnk = Join-Path ([Environment]::GetFolderPath("Desktop")) "Glide.lnk"
        $sendToLnk = Join-Path ([Environment]::GetFolderPath("SendTo")) "Send with Glide.lnk"
        $lnkBakDir = Join-Path $SandboxRoot "shortcuts-backup"
        New-Item -ItemType Directory -Path $lnkBakDir -Force | Out-Null
        $hadSm = Test-Path -LiteralPath $smGlideDir
        $hadDesk = Test-Path -LiteralPath $desktopLnk
        $hadSendTo = Test-Path -LiteralPath $sendToLnk
        if ($hadSm) { Copy-Item -LiteralPath $smGlideDir -Destination (Join-Path $lnkBakDir "SM_Glide") -Recurse -Force }
        if ($hadDesk) { Copy-Item -LiteralPath $desktopLnk -Destination (Join-Path $lnkBakDir "Desktop_Glide.lnk") -Force }
        if ($hadSendTo) { Copy-Item -LiteralPath $sendToLnk -Destination (Join-Path $lnkBakDir "SendTo_Glide.lnk") -Force }

        $installDir = Join-Path $SandboxRoot "nsis-install"
        $msiInstallDir = Join-Path $SandboxRoot "msi-install"

        try {
            if ($PackagePath -like "*.exe") {
                # 1. Silent NSIS Install (/D= must be unquoted and last)
                $p = Start-Process -FilePath $PackagePath -ArgumentList "/S /D=$installDir" -Wait -PassThru
                if ($p.ExitCode -ne 0) { throw "NSIS install failed with exit code $($p.ExitCode)." }

                $installedExe = Join-Path $installDir "Glide.exe"
                $uninstExe = Join-Path $installDir "Uninstall.exe"
                foreach ($f in @($installedExe, (Join-Path $installDir "glide.cmd"), $uninstExe, (Join-Path $installDir "glide-installer-manifest.txt"))) {
                    if (-not (Test-Path -LiteralPath $f)) { throw "Missing NSIS installed artifact: $f" }
                }

                $regIp = (& reg.exe query $regGlide /v InstallPath 2>&1 | Out-String)
                if ($LASTEXITCODE -ne 0 -or $regIp -notmatch [regex]::Escape($installDir)) {
                    throw "HKCU\Software\Glide InstallPath mismatch: $regIp"
                }
                & reg.exe query $regUri /s >$null 2>&1
                if ($LASTEXITCODE -ne 0) { throw "Missing glide:// URI registry registration." }
                & reg.exe query $regUninst >$null 2>&1
                if ($LASTEXITCODE -ne 0) { throw "Missing Uninstall registry key." }
                if (-not (Test-Path -LiteralPath (Join-Path $smGlideDir "Glide.lnk"))) { throw "Missing Start Menu shortcut." }

                # Smoke launch via UIA
                $sessFile = Join-Path $SandboxRoot "nsis-winui.json"
                $uiBak = Join-Path $SandboxRoot "ui-bak"
                & pwsh -NoProfile -File $winUiDriver -Action StartSession -ExePath $installedExe -SessionFile $sessFile -BackupDir $uiBak | Out-Null
                if ($LASTEXITCODE -ne 0) { throw "Installed Glide.exe failed UIA StartSession." }
                & pwsh -NoProfile -File $winUiDriver -Action StopSession -SessionFile $sessFile -BackupDir $uiBak | Out-Null

                # Seed user preference marker & upgrade in place
                New-Item -ItemType Directory -Path $appDataGlide -Force | Out-Null
                $uiPrefPath = Join-Path $appDataGlide "windows-ui.json"
                '{"AppearanceMode":"Dark"}' | Set-Content -LiteralPath $uiPrefPath -Encoding utf8

                $upgExe = if (-not [string]::IsNullOrWhiteSpace($UpgradePackagePath) -and (Test-Path -LiteralPath $UpgradePackagePath)) { $UpgradePackagePath } else { $PackagePath }
                $pUpg = Start-Process -FilePath $upgExe -ArgumentList "/S /D=$installDir" -Wait -PassThru
                if ($pUpg.ExitCode -ne 0) { throw "NSIS upgrade failed with exit code $($pUpg.ExitCode)." }
                if (-not (Test-Path -LiteralPath $installedExe)) { throw "Glide.exe missing after upgrade." }
                if ((Get-Content -LiteralPath $uiPrefPath -Raw) -notmatch "Dark") { throw "windows-ui.json lost on NSIS upgrade." }

                # Synchronous silent uninstall WITHOUT /REMOVEAPPDATA -> user data retained
                $pUn = Start-Process -FilePath $uninstExe -ArgumentList "/S _?=$installDir" -Wait -PassThru
                if ($pUn.ExitCode -ne 0) { throw "NSIS uninstall failed with exit code $($pUn.ExitCode)." }
                if (Test-Path -LiteralPath $installedExe) { throw "Glide.exe still exists after NSIS uninstall." }
                if (-not (Test-Path -LiteralPath $uiPrefPath)) { throw "NSIS uninstall deleted AppData without /REMOVEAPPDATA!" }

                # Re-install and uninstall WITH /REMOVEAPPDATA -> AppData removed
                $p2 = Start-Process -FilePath $PackagePath -ArgumentList "/S /D=$installDir" -Wait -PassThru
                if ($p2.ExitCode -ne 0) { throw "NSIS reinstall failed with exit code $($p2.ExitCode)." }
                $pUn2 = Start-Process -FilePath $uninstExe -ArgumentList "/S /REMOVEAPPDATA _?=$installDir" -Wait -PassThru
                if ($pUn2.ExitCode -ne 0) { throw "NSIS /REMOVEAPPDATA uninstall failed with exit code $($pUn2.ExitCode)." }
                if (Test-Path -LiteralPath $appDataGlide) { throw "AppData still exists after /REMOVEAPPDATA!" }
            }

            if (-not [string]::IsNullOrWhiteSpace($ZipPackagePath) -and (Test-Path -LiteralPath $ZipPackagePath)) {
                $zipDir = Join-Path $SandboxRoot "zip-app"
                Expand-Archive -LiteralPath $ZipPackagePath -DestinationPath $zipDir -Force
                $zipExe = Join-Path $zipDir "Glide.exe"
                if (-not (Test-Path -LiteralPath $zipExe)) { throw "Portable zip missing Glide.exe." }
                $sessZip = Join-Path $SandboxRoot "zip-winui.json"
                $uiZipBak = Join-Path $SandboxRoot "ui-zip-bak"
                & pwsh -NoProfile -File $winUiDriver -Action StartSession -ExePath $zipExe -SessionFile $sessZip -BackupDir $uiZipBak | Out-Null
                if ($LASTEXITCODE -ne 0) { throw "Portable zip Glide.exe failed UIA launch." }
                & pwsh -NoProfile -File $winUiDriver -Action StopSession -SessionFile $sessZip -BackupDir $uiZipBak | Out-Null
                Remove-Item -LiteralPath $zipDir -Recurse -Force
                $prev = $ErrorActionPreference; $ErrorActionPreference = "Continue"
                & reg.exe query $regGlide >$null 2>&1
                $zipWroteReg = ($LASTEXITCODE -eq 0)
                $ErrorActionPreference = $prev
                if ($zipWroteReg) { throw "Portable zip unexpectedly wrote HKCU\Software\Glide." }
            }

            if (-not [string]::IsNullOrWhiteSpace($MsiPackagePath) -and (Test-Path -LiteralPath $MsiPackagePath)) {
                $isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
                $msiLogI = Join-Path $SandboxRoot "msi-install.log"
                $msiLogX = Join-Path $SandboxRoot "msi-uninstall.log"
                $msiArgsI = @("/i", "`"$MsiPackagePath`"")
                if (-not $isAdmin) {
                    $msiArgsI += @("ALLUSERS=2", "MSIINSTALLPERUSER=1", "INSTALLFOLDER=`"$msiInstallDir`"")
                }
                else {
                    $msiInstallDir = "${env:ProgramFiles}\Glide"
                }
                $msiArgsI += @("/qn", "/norestart", "/l*v", "`"$msiLogI`"")
                $pMsiI = Start-Process -FilePath "msiexec.exe" -ArgumentList $msiArgsI -Wait -PassThru
                if ($pMsiI.ExitCode -ne 0) { throw "MSI install failed with exit code $($pMsiI.ExitCode). Log: $msiLogI" }
                if (-not (Test-Path -LiteralPath (Join-Path $msiInstallDir "Glide.exe"))) {
                    throw "MSI install did not place Glide.exe in '$msiInstallDir'."
                }
                $pMsiX = Start-Process -FilePath "msiexec.exe" -ArgumentList @("/x", "`"$MsiPackagePath`"", "/qn", "/norestart", "/l*v", "`"$msiLogX`"") -Wait -PassThru
                if ($pMsiX.ExitCode -ne 0) { throw "MSI uninstall failed with exit code $($pMsiX.ExitCode). Log: $msiLogX" }
                if (Test-Path -LiteralPath (Join-Path $msiInstallDir "Glide.exe")) {
                    throw "MSI uninstall left Glide.exe behind in '$msiInstallDir'."
                }
            }

            [pscustomobject]@{
                targetLabel = "WINDOWS_UI"
                platform    = "Windows"
                success     = $true
            } | ConvertTo-Json -Depth 3
            exit 0
        }
        finally {
            if (Test-Path -LiteralPath (Join-Path $installDir "Uninstall.exe")) {
                Start-Process -FilePath (Join-Path $installDir "Uninstall.exe") -ArgumentList "/S _?=$installDir" -Wait | Out-Null
            }
            if (Test-Path -LiteralPath $appDataGlide) { Remove-Item -LiteralPath $appDataGlide -Recurse -Force -ErrorAction SilentlyContinue }
            if ($appDataExisted) {
                New-Item -ItemType Directory -Path $appDataGlide -Force | Out-Null
                Copy-Item -Path "$appDataBackup\*" -Destination $appDataGlide -Recurse -Force -ErrorAction SilentlyContinue
            }
            Restore-RegTree -RegKey $regGlide -ExistedBefore $hadRegGlide -BackupFile $regGlideBak
            Restore-RegTree -RegKey $regUri -ExistedBefore $hadRegUri -BackupFile $regUriBak
            Restore-RegTree -RegKey $regUninst -ExistedBefore $hadRegUninst -BackupFile $regUninstBak

            if (Test-Path -LiteralPath $smGlideDir) { Remove-Item -LiteralPath $smGlideDir -Recurse -Force -ErrorAction SilentlyContinue }
            if (Test-Path -LiteralPath $desktopLnk) { Remove-Item -LiteralPath $desktopLnk -Force -ErrorAction SilentlyContinue }
            if (Test-Path -LiteralPath $sendToLnk) { Remove-Item -LiteralPath $sendToLnk -Force -ErrorAction SilentlyContinue }
            if ($hadSm) { Copy-Item -LiteralPath (Join-Path $lnkBakDir "SM_Glide") -Destination $smGlideDir -Recurse -Force }
            if ($hadDesk) { Copy-Item -LiteralPath (Join-Path $lnkBakDir "Desktop_Glide.lnk") -Destination $desktopLnk -Force }
            if ($hadSendTo) { Copy-Item -LiteralPath (Join-Path $lnkBakDir "SendTo_Glide.lnk") -Destination $sendToLnk -Force }
        }
    }

    "Android" {
        $androidDriver = Join-Path $PSScriptRoot "Invoke-AndroidUi.ps1"
        if ([string]::IsNullOrWhiteSpace($PackagePath)) {
            if (-not [string]::IsNullOrWhiteSpace($env:GLIDE_E2E_ANDROID_APK)) {
                $PackagePath = $env:GLIDE_E2E_ANDROID_APK
            }
            else {
                $PackagePath = Join-Path $repoRoot "android\app\build\outputs\apk\debug\app-debug.apk"
            }
        }
        $probe = & pwsh -NoProfile -File $androidDriver -Action Probe -Serial $AndroidSerial -Package $AndroidPackage | ConvertFrom-Json
        if (-not $probe.reachable) {
            throw "Android target unreachable: $($probe.reason)"
        }
        if ($Action -ne "Probe" -and -not (Test-Path -LiteralPath $PackagePath)) {
            throw "Android APK not found: $PackagePath"
        }

        switch ($Action) {
            "Probe" {
                $probe | ConvertTo-Json -Depth 3
                if (-not $probe.packageInstalled) { exit 1 }
                exit 0
            }
            "Install" {
                & adb -s $probe.serial install -r $PackagePath
                if ($LASTEXITCODE -ne 0) { throw "adb install -r failed for $PackagePath." }
                $dumpPkg = (& adb -s $probe.serial shell dumpsys package $AndroidPackage | Out-String)
                if ($dumpPkg -notmatch "versionName=1\.0\.0") { throw "Installed APK does not report versionName=1.0.0." }
                & pwsh -NoProfile -File $androidDriver -Action GrantPermissions -Serial $probe.serial -Package $AndroidPackage | Out-Null
                & pwsh -NoProfile -File $androidDriver -Action LaunchDebugState -Serial $probe.serial -Package $AndroidPackage -DebugState "theme_light" | Out-Null
                & pwsh -NoProfile -File $androidDriver -Action AssertUiNode -Serial $probe.serial -Package $AndroidPackage -TextExact "Send files" | Out-Null
                & pwsh -NoProfile -File $androidDriver -Action AssertUiNode -Serial $probe.serial -Package $AndroidPackage -TextExact "Receive files" | Out-Null
                [pscustomobject]@{ targetLabel = $probe.targetLabel; action = "Install"; version = "1.0.0"; verified = $true } | ConvertTo-Json -Depth 3
                exit 0
            }
            "Upgrade" {
                $upgApk = if (-not [string]::IsNullOrWhiteSpace($UpgradePackagePath)) { $UpgradePackagePath } else { $PackagePath }
                $beforeInfo = (& adb -s $probe.serial shell dumpsys package $AndroidPackage | Select-String "versionCode|firstInstallTime|lastUpdateTime" | Out-String)
                & adb -s $probe.serial install -r -d $upgApk
                if ($LASTEXITCODE -ne 0) { throw "adb install -r -d failed for $upgApk." }
                $afterInfo = (& adb -s $probe.serial shell dumpsys package $AndroidPackage | Select-String "versionCode|firstInstallTime|lastUpdateTime" | Out-String)
                [pscustomobject]@{ targetLabel = $probe.targetLabel; action = "Upgrade"; before = $beforeInfo.Trim(); after = $afterInfo.Trim() } | ConvertTo-Json -Depth 3
                exit 0
            }
            "ForceStopAndVerify" {
                & adb -s $probe.serial shell am force-stop $AndroidPackage
                if ($LASTEXITCODE -ne 0) { throw "am force-stop failed." }
                $pidOut = (& adb -s $probe.serial shell pidof $AndroidPackage 2>$null | Out-String).Trim()
                if (-not [string]::IsNullOrWhiteSpace($pidOut)) { throw "Process still alive after force-stop: $pidOut" }
                & pwsh -NoProfile -File $androidDriver -Action LaunchDebugState -Serial $probe.serial -Package $AndroidPackage -DebugState "theme_settings" | Out-Null
                & pwsh -NoProfile -File $androidDriver -Action AssertUiNode -Serial $probe.serial -Package $AndroidPackage -TextExact "Settings" | Out-Null
                [pscustomobject]@{ targetLabel = $probe.targetLabel; action = "ForceStopAndVerify"; verified = $true } | ConvertTo-Json -Depth 3
                exit 0
            }
            "UninstallKeepData" {
                & adb -s $probe.serial shell cmd package uninstall -k $AndroidPackage
                if ($LASTEXITCODE -ne 0) { throw "cmd package uninstall -k failed." }
                $pmCheck = (& adb -s $probe.serial shell pm path $AndroidPackage 2>$null | Out-String).Trim()
                if (-not [string]::IsNullOrWhiteSpace($pmCheck)) { throw "Package still present after uninstall -k: $pmCheck" }
                [pscustomobject]@{ targetLabel = $probe.targetLabel; action = "UninstallKeepData"; removed = $true } | ConvertTo-Json -Depth 3
                exit 0
            }
            "ReinstallAndVerifyRetention" {
                & adb -s $probe.serial install -r $PackagePath
                if ($LASTEXITCODE -ne 0) { throw "Reinstall failed for $PackagePath." }
                & pwsh -NoProfile -File $androidDriver -Action GrantPermissions -Serial $probe.serial -Package $AndroidPackage | Out-Null
                [pscustomobject]@{ targetLabel = $probe.targetLabel; action = "ReinstallAndVerifyRetention"; reinstalled = $true } | ConvertTo-Json -Depth 3
                exit 0
            }
            "Full" {
                & pwsh -NoProfile -File $PSCommandPath -Platform Android -Action Install -PackagePath $PackagePath -AndroidSerial $probe.serial -AndroidPackage $AndroidPackage
                if ($LASTEXITCODE -ne 0) { throw "Android Install failed." }
                & pwsh -NoProfile -File $PSCommandPath -Platform Android -Action Upgrade -PackagePath $PackagePath -UpgradePackagePath $UpgradePackagePath -AndroidSerial $probe.serial -AndroidPackage $AndroidPackage
                if ($LASTEXITCODE -ne 0) { throw "Android Upgrade failed." }
                & pwsh -NoProfile -File $PSCommandPath -Platform Android -Action ForceStopAndVerify -PackagePath $PackagePath -AndroidSerial $probe.serial -AndroidPackage $AndroidPackage
                if ($LASTEXITCODE -ne 0) { throw "Android ForceStopAndVerify failed." }
                exit 0
            }
        }
    }
}
