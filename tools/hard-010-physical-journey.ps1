# HARD-010 physical two-device Android acceptance harness.
# This tool prepares/captures evidence only. It never marks HARD-010 complete.

[CmdletBinding()]
param(
    [ValidateSet("Prepare","BeginRun","EndRun","Audit")]
    [string]$Mode = "Prepare",
    [string]$AdbPath = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
    [string]$ApkPath = "$PSScriptRoot\..\app\build\outputs\apk\debug\app-debug.apk",
    [string]$EvidenceDir = "",
    [string]$PackageId = "dev.glide.app.debug",
    [string]$DeviceA = "",
    [string]$DeviceB = "",
    [string]$RunId = "",
    [ValidateSet("nearby","lan","")][string]$Transport = "",
    [ValidateSet("A-to-B","B-to-A","")][string]$Direction = "",
    [ValidateSet("10mb","1gb","none","")][string]$Payload = "",
    [ValidateSet("success","reject","cancel","")][string]$Scenario = "",
    [ValidateSet("pass","fail","not-run","")][string]$ObservedResult = "",
    [switch]$SasOrPinVerified,
    [switch]$OfferConsentVerified,
    [switch]$OpenVerified,
    [switch]$HistoryVerified,
    [string]$Notes = ""
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$FixtureDir = Join-Path $RepoRoot "build\fixtures"

function Resolve-Adb {
    if (Test-Path $AdbPath) { return (Resolve-Path $AdbPath).Path }
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($null -eq $cmd) { throw "ADB not found. Install Android SDK platform-tools or pass -AdbPath." }
    return $cmd.Source
}
$script:Adb = Resolve-Adb

function Invoke-AdbText {
    param([string]$Serial,[Parameter(ValueFromRemainingArguments=$true)][string[]]$Args)
    $out = & $script:Adb -s $Serial @Args 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw ("ADB failed for " + $Serial + ": " + ($Args -join " ") + [Environment]::NewLine + ($out -join [Environment]::NewLine))
    }
    return (($out | Out-String).Trim())
}

function Get-PhysicalDevices {
    $raw = & $script:Adb devices -l
    if ($LASTEXITCODE -ne 0) { throw "adb devices failed." }
    $serials = @(($raw -split "\r?\n") |
        Where-Object { $_ -match "^\S+\s+device\b" } |
        ForEach-Object { ($_ -split "\s+")[0] })
    $physical = @()
    foreach ($serial in $serials) {
        $qemu = (Invoke-AdbText $serial shell getprop ro.kernel.qemu).Trim()
        $hardware = (Invoke-AdbText $serial shell getprop ro.hardware).Trim()
        $model = (Invoke-AdbText $serial shell getprop ro.product.model).Trim()
        if ($qemu -ne "1" -and $hardware -notmatch "^(goldfish|ranchu)$" -and $model -notmatch "(?i)emulator|sdk_gphone") {
            $physical += $serial
        }
    }
    return $physical
}

function Require-TwoPhysicalDevices {
    $physical = @(Get-PhysicalDevices)
    if ($physical.Count -lt 2) {
        throw "HARD-010 requires two physical Android devices. Found $($physical.Count). Emulators are rejected."
    }
    $a = if ($DeviceA) { $DeviceA } else { $physical[0] }
    $b = if ($DeviceB) { $DeviceB } else { $physical[1] }
    if ($a -eq $b) { throw "Device A and Device B must be different." }
    if ($physical -notcontains $a -or $physical -notcontains $b) { throw "Specified serial is not a connected physical device." }
    return @($a,$b)
}

function Get-DeviceInfo {
    param([string]$Serial)
    $gmsDump = Invoke-AdbText $Serial shell dumpsys package com.google.android.gms
    $gmsLine = (($gmsDump -split "\r?\n") | Where-Object { $_ -match "versionName=" } | Select-Object -First 1)
    $gms = if ($gmsLine) { ($gmsLine -replace "^.*versionName=","").Trim() } else { "not-found" }
    $mem = Invoke-AdbText $Serial shell cat /proc/meminfo
    $memLine = (($mem -split "\r?\n") | Where-Object { $_ -match "^MemTotal:" } | Select-Object -First 1)
    $memKb = if ($memLine) { [long](($memLine -replace "[^0-9]","")) } else { 0L }
    [pscustomobject]@{
        serial = $Serial
        manufacturer = (Invoke-AdbText $Serial shell getprop ro.product.manufacturer).Trim()
        model = (Invoke-AdbText $Serial shell getprop ro.product.model).Trim()
        androidRelease = (Invoke-AdbText $Serial shell getprop ro.build.version.release).Trim()
        sdk = (Invoke-AdbText $Serial shell getprop ro.build.version.sdk).Trim()
        securityPatch = (Invoke-AdbText $Serial shell getprop ro.build.version.security_patch).Trim()
        buildFingerprint = (Invoke-AdbText $Serial shell getprop ro.build.fingerprint).Trim()
        gmsVersion = $gms
        ramBytesApprox = $memKb * 1024L
        dataFilesystem = (Invoke-AdbText $Serial shell df -k /data)
    }
}

function Ensure-Fixtures {
    $f10 = Join-Path $FixtureDir "glide-test-10mb.bin"
    $f1g = Join-Path $FixtureDir "glide-test-1gb.bin"
    if (-not (Test-Path $f10) -or -not (Test-Path $f1g)) {
        & pwsh -NoProfile -File (Join-Path $PSScriptRoot "generate-slice-fixtures.ps1") -OutputDir $FixtureDir -Generate1GB
        if ($LASTEXITCODE -ne 0) { throw "Fixture generation failed." }
    }
    return @($f10,$f1g)
}

function Get-GitHead {
    try {
        $head = (& git -C $RepoRoot rev-parse HEAD 2>$null).Trim()
        if ($LASTEXITCODE -eq 0 -and $head) { return $head }
    } catch {}
    return "UNKNOWN"
}

function Write-Json {
    param([string]$Path,$Value)
    $Value | ConvertTo-Json -Depth 12 | Set-Content -Path $Path -Encoding utf8
}

function Required-Matrix {
    $rows = @()
    foreach ($t in @("nearby","lan")) {
        foreach ($p in @("10mb","1gb")) {
            foreach ($d in @("A-to-B","B-to-A")) {
                $rows += [pscustomobject]@{ id="$t-$p-$($d.ToLower())"; transport=$t; payload=$p; direction=$d; scenario="success" }
            }
        }
        $rows += [pscustomobject]@{ id="$t-reject-a-to-b"; transport=$t; payload="10mb"; direction="A-to-B"; scenario="reject" }
        $rows += [pscustomobject]@{ id="$t-cancel-a-to-b"; transport=$t; payload="1gb"; direction="A-to-B"; scenario="cancel" }
    }
    return $rows
}

function Read-Environment {
    if (-not $EvidenceDir) { throw "-EvidenceDir is required." }
    $path = Join-Path $EvidenceDir "environment.json"
    if (-not (Test-Path $path)) { throw "Missing environment.json. Run Prepare first." }
    return (Get-Content $path -Raw | ConvertFrom-Json)
}

function Get-PrivateState {
    param([string]$Serial)
    $received = ""
    $staging = ""
    try {
        $received = Invoke-AdbText $Serial shell run-as $PackageId sh -c "if [ -d files/received ]; then find files/received -maxdepth 1 -type f -exec sha256sum {} \;; fi"
    } catch { $received = "UNAVAILABLE: " + $_.Exception.Message }
    try {
        $staging = Invoke-AdbText $Serial shell run-as $PackageId sh -c "if [ -d files/receiving ]; then find files/receiving -maxdepth 1 -type f -print; fi"
    } catch { $staging = "UNAVAILABLE: " + $_.Exception.Message }
    return [pscustomobject]@{ receivedHashes=$received; stagingFiles=$staging }
}

function Save-Screenshot {
    param([string]$Serial,[string]$Path)
    $remote = "/sdcard/Download/hard010-screen.png"
    & $script:Adb -s $Serial shell screencap -p $remote | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Screenshot capture failed on $Serial." }
    & $script:Adb -s $Serial pull $remote $Path | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Screenshot pull failed on $Serial." }
    & $script:Adb -s $Serial shell rm -f $remote | Out-Null
}

function Run-Folder {
    if (-not $RunId) { throw "-RunId is required." }
    return (Join-Path $EvidenceDir ("runs\" + $RunId))
}

switch ($Mode) {
    "Prepare" {
        if (-not (Test-Path $ApkPath)) { throw "APK not found at $ApkPath. Build the exact candidate first." }
        $devices = Require-TwoPhysicalDevices
        $a = $devices[0]; $b = $devices[1]
        $fixtures = Ensure-Fixtures
        $f10 = $fixtures[0]; $f1g = $fixtures[1]
        if (-not $EvidenceDir) {
            $EvidenceDir = Join-Path $RepoRoot ("build\hard-010\" + (Get-Date -Format "yyyyMMdd-HHmmss"))
        }
        New-Item -ItemType Directory -Path (Join-Path $EvidenceDir "runs") -Force | Out-Null

        $apkHash = (Get-FileHash $ApkPath -Algorithm SHA256).Hash.ToLowerInvariant()
        $h10 = (Get-FileHash $f10 -Algorithm SHA256).Hash.ToLowerInvariant()
        $h1g = (Get-FileHash $f1g -Algorithm SHA256).Hash.ToLowerInvariant()

        foreach ($serial in @($a,$b)) {
            & $script:Adb -s $serial install -r -d $ApkPath
            if ($LASTEXITCODE -ne 0) { throw "APK install failed on $serial." }
            & $script:Adb -s $serial push $f10 /sdcard/Download/glide-test-10mb.bin | Out-Null
            if ($LASTEXITCODE -ne 0) { throw "10 MiB fixture push failed on $serial." }
            & $script:Adb -s $serial push $f1g /sdcard/Download/glide-test-1gb.bin | Out-Null
            if ($LASTEXITCODE -ne 0) { throw "1 GiB fixture push failed on $serial." }
            if ((Invoke-AdbText $serial shell sha256sum /sdcard/Download/glide-test-10mb.bin).ToLowerInvariant() -notmatch $h10) { throw "10 MiB hash mismatch on $serial." }
            if ((Invoke-AdbText $serial shell sha256sum /sdcard/Download/glide-test-1gb.bin).ToLowerInvariant() -notmatch $h1g) { throw "1 GiB hash mismatch on $serial." }
        }

        $environment = [pscustomobject]@{
            preparedAtUtc=(Get-Date).ToUniversalTime().ToString("o")
            repositoryHead=Get-GitHead
            apkPath=(Resolve-Path $ApkPath).Path
            apkSha256=$apkHash
            packageId=$PackageId
            deviceA=Get-DeviceInfo $a
            deviceB=Get-DeviceInfo $b
            fixtures=[pscustomobject]@{
                tenMiB=[pscustomobject]@{ path=(Resolve-Path $f10).Path; sizeBytes=(Get-Item $f10).Length; sha256=$h10; devicePath="/sdcard/Download/glide-test-10mb.bin" }
                oneGiB=[pscustomobject]@{ path=(Resolve-Path $f1g).Path; sizeBytes=(Get-Item $f1g).Length; sha256=$h1g; devicePath="/sdcard/Download/glide-test-1gb.bin" }
            }
            requiredMatrix=@(Required-Matrix)
            evidenceRule="Two physical devices only. Emulator/loopback evidence cannot satisfy HARD-010."
        }
        Write-Json (Join-Path $EvidenceDir "environment.json") $environment
        Required-Matrix | Export-Csv (Join-Path $EvidenceDir "required-matrix.csv") -NoTypeInformation
        Write-Host "Prepared HARD-010 evidence directory: $EvidenceDir"
        Write-Host "Device A: $a"
        Write-Host "Device B: $b"
        Write-Host "APK SHA-256: $apkHash"
        Write-Host "10 MiB SHA-256: $h10"
        Write-Host "1 GiB SHA-256: $h1g"
    }

    "BeginRun" {
        $envData = Read-Environment
        if (-not $Transport -or -not $Direction -or -not $Payload -or -not $Scenario) {
            throw "BeginRun requires -Transport, -Direction, -Payload, and -Scenario."
        }
        $folder = Run-Folder
        if (Test-Path $folder) { throw "Run folder already exists: $folder" }
        New-Item -ItemType Directory -Path $folder -Force | Out-Null

        $sender = if ($Direction -eq "A-to-B") { $envData.deviceA.serial } else { $envData.deviceB.serial }
        $receiver = if ($Direction -eq "A-to-B") { $envData.deviceB.serial } else { $envData.deviceA.serial }

        foreach ($serial in @($sender,$receiver)) { & $script:Adb -s $serial logcat -c | Out-Null }
        Write-Json (Join-Path $folder "private-state-before.json") ([pscustomobject]@{ sender=Get-PrivateState $sender; receiver=Get-PrivateState $receiver })
        Save-Screenshot $sender (Join-Path $folder "sender-before.png")
        Save-Screenshot $receiver (Join-Path $folder "receiver-before.png")

        $expected = if ($Payload -eq "10mb") { $envData.fixtures.tenMiB.sha256 } elseif ($Payload -eq "1gb") { $envData.fixtures.oneGiB.sha256 } else { $null }
        Write-Json (Join-Path $folder "run.json") ([pscustomobject]@{
            runId=$RunId; status="started"; startedAtUtc=(Get-Date).ToUniversalTime().ToString("o")
            transport=$Transport; direction=$Direction; payload=$Payload; scenario=$Scenario
            senderSerial=$sender; receiverSerial=$receiver
            repositoryHead=$envData.repositoryHead; apkSha256=$envData.apkSha256
            expectedPayloadSha256=$expected
        })
        Write-Host "Started evidence capture for $RunId. Execute the production UI journey now."
    }

    "EndRun" {
        $envData = Read-Environment
        $folder = Run-Folder
        $runPath = Join-Path $folder "run.json"
        if (-not (Test-Path $runPath)) { throw "Missing run.json for $RunId." }
        $run = Get-Content $runPath -Raw | ConvertFrom-Json
        $sender=$run.senderSerial; $receiver=$run.receiverSerial

        foreach ($entry in @(@($sender,"sender"),@($receiver,"receiver"))) {
            $serial=$entry[0]; $label=$entry[1]
            (& $script:Adb -s $serial logcat -d -v threadtime 2>&1 | Out-String) | Set-Content (Join-Path $folder ($label + "-logcat.txt")) -Encoding utf8
            Save-Screenshot $serial (Join-Path $folder ($label + "-after.png"))
        }

        $senderAfter=Get-PrivateState $sender
        $receiverAfter=Get-PrivateState $receiver
        Write-Json (Join-Path $folder "private-state-after.json") ([pscustomobject]@{ sender=$senderAfter; receiver=$receiverAfter })

        $expected=[string]$run.expectedPayloadSha256
        $hashMatched=$false
        if ($expected -and ([string]$receiverAfter.receivedHashes) -notmatch "^UNAVAILABLE:") {
            $hashMatched=([string]$receiverAfter.receivedHashes).ToLowerInvariant().Contains($expected.ToLowerInvariant())
        }

        $run.status="recorded"
        $run | Add-Member endedAtUtc ((Get-Date).ToUniversalTime().ToString("o")) -Force
        $run | Add-Member observedResult $(if ($ObservedResult) { $ObservedResult } else { "not-run" }) -Force
        $run | Add-Member sasOrPinVerified ([bool]$SasOrPinVerified) -Force
        $run | Add-Member offerConsentVerified ([bool]$OfferConsentVerified) -Force
        $run | Add-Member openVerified ([bool]$OpenVerified) -Force
        $run | Add-Member historyVerified ([bool]$HistoryVerified) -Force
        $run | Add-Member receiverPrivateHashMatched $hashMatched -Force
        $run | Add-Member notes $Notes -Force
        Write-Json $runPath $run
        Write-Host "Recorded $RunId. Receiver SHA-256 matched expected payload: $hashMatched"
    }

    "Audit" {
        $envData=Read-Environment
        $problems=@()
        $summary=@()
        foreach ($req in @(Required-Matrix)) {
            $path=Join-Path $EvidenceDir ("runs\"+$req.id+"\run.json")
            if (-not (Test-Path $path)) { $problems += "Missing run: $($req.id)"; continue }
            $run=Get-Content $path -Raw | ConvertFrom-Json
            $ok=$run.observedResult -eq "pass"
            if ($req.scenario -eq "success") {
                $ok=$ok -and [bool]$run.sasOrPinVerified -and [bool]$run.offerConsentVerified -and [bool]$run.receiverPrivateHashMatched -and [bool]$run.openVerified -and [bool]$run.historyVerified
            } elseif ($req.scenario -eq "reject") {
                $ok=$ok -and [bool]$run.sasOrPinVerified
            } elseif ($req.scenario -eq "cancel") {
                $ok=$ok -and [bool]$run.sasOrPinVerified -and [bool]$run.offerConsentVerified
            }
            $summary += [pscustomobject]@{ runId=$req.id; transport=$req.transport; direction=$req.direction; payload=$req.payload; scenario=$req.scenario; pass=$ok }
            if (-not $ok) { $problems += "Incomplete/failed evidence: $($req.id)" }
        }
        $summary | Export-Csv (Join-Path $EvidenceDir "audit-summary.csv") -NoTypeInformation
        if ($envData.repositoryHead -eq "UNKNOWN") { $problems += "Repository HEAD was not captured." }
        if ($problems.Count -gt 0) {
            Write-Host "HARD-010 audit: NOT COMPLETE"
            $problems | ForEach-Object { Write-Host (" - " + $_) }
            exit 2
        }
        Write-Host "HARD-010 evidence is structurally complete. Human review is still required before marking the task COMPLETE."
    }
}
