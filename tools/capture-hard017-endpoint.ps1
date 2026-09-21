# HARD-017 endpoint identity capture.
# Run this on the actual Windows/Linux host, or against the actual physical Android device via ADB.
# The output is evidence input; it does not certify interoperability by itself.

[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)]
    [ValidateSet("android","windows","linux")]
    [string]$Platform,

    [Parameter(Mandatory=$true)]
    [string]$ArtifactPath,

    [Parameter(Mandatory=$true)]
    [string]$OutputPath,

    [string]$AdbPath = "",
    [string]$AndroidSerial = "",
    [string]$AndroidPackageId = "dev.glide.app.debug"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path

if (-not (Test-Path $ArtifactPath -PathType Leaf)) {
    throw "Artifact not found: $ArtifactPath"
}
$ArtifactPath = (Resolve-Path $ArtifactPath).Path

function Get-GitHead {
    $value = (& git -C $RepoRoot rev-parse HEAD 2>$null | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or $value -notmatch "^[0-9a-f]{40}$") {
        throw "Unable to resolve exact repository HEAD."
    }
    return $value
}

function Resolve-Adb {
    if ($AdbPath -and (Test-Path $AdbPath)) {
        return (Resolve-Path $AdbPath).Path
    }
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($null -eq $cmd) {
        throw "ADB not found. Pass -AdbPath or add adb to PATH."
    }
    return $cmd.Source
}

function Invoke-AdbText {
    param(
        [Parameter(Mandatory=$true)][string]$Adb,
        [Parameter(Mandatory=$true)][string]$Serial,
        [Parameter(ValueFromRemainingArguments=$true)][string[]]$Args
    )

    $output = & $Adb -s $Serial @Args 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw ("ADB failed: " + ($Args -join " ") + [Environment]::NewLine + ($output -join [Environment]::NewLine))
    }
    return (($output | Out-String).Trim())
}

$artifact = Get-Item $ArtifactPath
$base = [ordered]@{
    schema = "glide-hard017-endpoint-v1"
    capturedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
    platform = $Platform
    repositoryHead = Get-GitHead
    artifactPath = $ArtifactPath
    artifactName = $artifact.Name
    artifactSizeBytes = [long]$artifact.Length
    artifactSha256 = (Get-FileHash $ArtifactPath -Algorithm SHA256).Hash.ToLowerInvariant()
    runtimeFramework = [System.Runtime.InteropServices.RuntimeInformation]::FrameworkDescription
}

switch ($Platform) {
    "android" {
        $adb = Resolve-Adb
        if (-not $AndroidSerial) {
            $devices = @(& $adb devices | Select-String -Pattern "^\S+\s+device\s*$" | ForEach-Object {
                (($_.Line -split "\s+")[0]).Trim()
            })
            if ($devices.Count -ne 1) {
                throw "Specify -AndroidSerial. Connected online Android device count: $($devices.Count)."
            }
            $AndroidSerial = $devices[0]
        }

        $qemu = Invoke-AdbText $adb $AndroidSerial shell getprop ro.kernel.qemu
        $hardware = Invoke-AdbText $adb $AndroidSerial shell getprop ro.hardware
        $model = Invoke-AdbText $adb $AndroidSerial shell getprop ro.product.model
        if ($qemu.Trim() -eq "1" -or
            $hardware.Trim() -match "^(goldfish|ranchu)$" -or
            $model.Trim() -match "(?i)emulator|sdk_gphone") {
            throw "HARD-017 Android evidence requires a physical device; emulator detected."
        }

        $packageDump = Invoke-AdbText $adb $AndroidSerial shell dumpsys package $AndroidPackageId
        $versionName = (($packageDump -split "\r?\n") | Where-Object { $_ -match "versionName=" } | Select-Object -First 1)
        $versionCode = (($packageDump -split "\r?\n") | Where-Object { $_ -match "versionCode=" } | Select-Object -First 1)

        $base.serial = $AndroidSerial
        $base.manufacturer = (Invoke-AdbText $adb $AndroidSerial shell getprop ro.product.manufacturer).Trim()
        $base.model = $model.Trim()
        $base.androidRelease = (Invoke-AdbText $adb $AndroidSerial shell getprop ro.build.version.release).Trim()
        $base.sdk = (Invoke-AdbText $adb $AndroidSerial shell getprop ro.build.version.sdk).Trim()
        $base.securityPatch = (Invoke-AdbText $adb $AndroidSerial shell getprop ro.build.version.security_patch).Trim()
        $base.buildFingerprint = (Invoke-AdbText $adb $AndroidSerial shell getprop ro.build.fingerprint).Trim()
        $base.packageId = $AndroidPackageId
        $base.installedVersionName = if ($versionName) { ($versionName -replace "^.*versionName=","").Trim() } else { "not-found" }
        $base.installedVersionCodeLine = if ($versionCode) { $versionCode.Trim() } else { "not-found" }
        $base.deviceEvidenceRule = "physical Android only; loopback/emulator evidence does not satisfy HARD-017"
    }

    "windows" {
        if (-not [OperatingSystem]::IsWindows()) {
            throw "Capture platform is windows but this script is not running on Windows."
        }

        $base.machineName = [Environment]::MachineName
        $base.osDescription = [System.Runtime.InteropServices.RuntimeInformation]::OSDescription
        $base.osArchitecture = [System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture.ToString()
        $base.processArchitecture = [System.Runtime.InteropServices.RuntimeInformation]::ProcessArchitecture.ToString()
        $base.osVersion = [Environment]::OSVersion.VersionString
    }

    "linux" {
        if (-not [OperatingSystem]::IsLinux()) {
            throw "Capture platform is linux but this script is not running on Linux."
        }

        $osRelease = ""
        if (Test-Path "/etc/os-release") {
            $osRelease = (Get-Content "/etc/os-release" -Raw).Trim()
        }
        $uname = (& uname -a 2>&1 | Out-String).Trim()

        $base.machineName = [Environment]::MachineName
        $base.osDescription = [System.Runtime.InteropServices.RuntimeInformation]::OSDescription
        $base.osArchitecture = [System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture.ToString()
        $base.processArchitecture = [System.Runtime.InteropServices.RuntimeInformation]::ProcessArchitecture.ToString()
        $base.uname = $uname
        $base.osRelease = $osRelease
    }
}

$parent = Split-Path -Parent $OutputPath
if ($parent) {
    New-Item -ItemType Directory -Path $parent -Force | Out-Null
}

[pscustomobject]$base |
    ConvertTo-Json -Depth 8 |
    Set-Content -Path $OutputPath -Encoding utf8

Write-Host "Captured HARD-017 endpoint identity: $OutputPath"
Write-Host "Platform: $Platform"
Write-Host "Repository HEAD: $($base.repositoryHead)"
Write-Host "Artifact SHA-256: $($base.artifactSha256)"
