[CmdletBinding()]
param(
    [ValidateSet("DryRun", "LocalLoopback", "LiveVm", "LiveAndroid", "All")]
    [string]$Mode = "DryRun",

    [ValidateSet("All", "1", "2", "3", "4")]
    [string]$Tier = "All",

    [string]$TestId = "",
    [string]$Filter = "",

    [ValidateSet("Debug", "Release")]
    [string]$Configuration = "Debug",

    [string]$VmHost = "",
    [string]$VmUser = "",
    [string]$AndroidSerial = "",
    [switch]$KeepArtifacts,
    [switch]$SkipPreflight,
    [switch]$NoBuild
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Redact-Secrets {
    param([string]$Text)
    if ([string]::IsNullOrEmpty($Text)) { return $Text }
    $redacted = [regex]::Replace($Text, 'glide://pair/(v\d+)#[A-Za-z0-9_-]+', 'glide://pair/$1#[REDACTED]')
    $redacted = [regex]::Replace($redacted, '("token"\s*:\s*")[^"]+(")', '$1[REDACTED]$2')
    $redacted = [regex]::Replace($redacted, '(\\"token\\"\s*:\s*\\")[^"\\]+(\\")', '$1[REDACTED]$2')
    $redacted = [regex]::Replace($redacted, '(&quot;token&quot;\s*:\s*&quot;)[^&<]+(&quot;)', '$1[REDACTED]$2')
    $redacted = [regex]::Replace($redacted, '(&amp;quot;token&amp;quot;\s*:\s*&amp;quot;)[^&<]+(&amp;quot;)', '$1[REDACTED]$2')
    return $redacted
}

function Ensure-DotnetOnPath {
    $userDotnet = Join-Path $env:USERPROFILE ".dotnet"
    $userDotnetExe = Join-Path $userDotnet $(if ($IsWindows) { "dotnet.exe" } else { "dotnet" })

    $cmd = Get-Command dotnet -ErrorAction SilentlyContinue
    $hasNet10 = $false
    if ($null -ne $cmd) {
        $sdks = (& $cmd.Source --list-sdks 2>$null | Out-String)
        if ($sdks -match "(?m)^10\.") {
            $hasNet10 = $true
        }
    }

    if (-not $hasNet10 -and (Test-Path -LiteralPath $userDotnetExe)) {
        $env:DOTNET_ROOT = $userDotnet
        $sep = if ($IsWindows) { ";" } else { ":" }
        $env:PATH = "$userDotnet$sep$env:PATH"
        $cmd = Get-Command dotnet -ErrorAction SilentlyContinue
        if ($null -ne $cmd) {
            $sdks = (& $cmd.Source --list-sdks 2>$null | Out-String)
            if ($sdks -match "(?m)^10\.") {
                $hasNet10 = $true
            }
        }
    }

    if (-not $hasNet10) {
        throw ".NET 10 SDK not found in PATH or $userDotnet."
    }

    if ([string]::IsNullOrWhiteSpace($env:DOTNET_ROOT)) {
        $env:DOTNET_ROOT = Split-Path (Get-Command dotnet).Source -Parent
    }
}

function Invoke-WithBuildLock {
    param([scriptblock]$Action)
    $mutex = [System.Threading.Mutex]::new($false, "Local\GlideE2EBuildLock")
    $acquired = $false
    try {
        try {
            $acquired = $mutex.WaitOne([TimeSpan]::FromSeconds(120))
        }
        catch [System.Threading.AbandonedMutexException] {
            $acquired = $true
        }
        if (-not $acquired) {
            throw "Timed out waiting for Local\GlideE2EBuildLock."
        }
        & $Action
    }
    finally {
        if ($acquired) {
            try { $mutex.ReleaseMutex() } catch { }
        }
        $mutex.Dispose()
    }
}

function Invoke-DotnetWithRetry {
    param([string[]]$DotnetArgs, [string]$Description)
    $maxAttempts = 3
    for ($attempt = 1; $attempt -le $maxAttempts; $attempt++) {
        $prevPref = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        $out = & dotnet @DotnetArgs 2>&1
        $code = $LASTEXITCODE
        $ErrorActionPreference = $prevPref
        $joined = ($out | Out-String)
        if ($code -eq 0) {
            if (-not [string]::IsNullOrWhiteSpace($joined)) {
                Write-Host $joined.TrimEnd()
            }
            return
        }
        if ($attempt -lt $maxAttempts -and ($joined -match "MSB302[67]")) {
            Write-Warning "$Description hit transient file lock (attempt $attempt/$maxAttempts); retrying in 2s..."
            Start-Sleep -Seconds 2
            continue
        }
        Write-Host $joined
        throw "$Description failed with exit code $code."
    }
}

Ensure-DotnetOnPath

$e2eRoot = $PSScriptRoot
$repoRoot = Split-Path (Split-Path $e2eRoot -Parent) -Parent
$testProject = Join-Path $e2eRoot "Glide.E2E.Tests\Glide.E2E.Tests.csproj"
$appProject = Join-Path $repoRoot "desktop\src\Glide.App\Glide.App.csproj"
$driverProject = Join-Path $repoRoot "desktop\tests\Glide.BackendTestDriver\Glide.BackendTestDriver.csproj"
$linuxUiProject = Join-Path $repoRoot "desktop\linux\Glide.Linux.UI\Glide.Linux.UI.csproj"

$configPath = Join-Path $e2eRoot "e2e.config.json"
if (-not (Test-Path -LiteralPath $configPath)) {
    $configPath = Join-Path $e2eRoot "e2e.config.example.json"
}
$config = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json

$resolvedVmHost = if (-not [string]::IsNullOrWhiteSpace($VmHost)) { $VmHost } elseif (-not [string]::IsNullOrWhiteSpace($env:GLIDE_E2E_VM_HOST)) { $env:GLIDE_E2E_VM_HOST } else { [string]$config.vm.host }
$resolvedVmUser = if (-not [string]::IsNullOrWhiteSpace($VmUser)) { $VmUser } elseif (-not [string]::IsNullOrWhiteSpace($env:GLIDE_E2E_VM_USER)) { $env:GLIDE_E2E_VM_USER } else { [string]$config.vm.user }
$resolvedSerial = if (-not [string]::IsNullOrWhiteSpace($AndroidSerial)) { $AndroidSerial } elseif (-not [string]::IsNullOrWhiteSpace($env:GLIDE_E2E_ANDROID_SERIAL)) { $env:GLIDE_E2E_ANDROID_SERIAL } else { [string]$config.android.serial }

$runId = "{0}-{1}" -f ([DateTimeOffset]::UtcNow.ToString("yyyyMMddTHHmmssZ")), ([guid]::NewGuid().ToString("N").Substring(0, 6))
$evidenceRel = [string]$config.paths.evidenceRoot
$evidenceDir = Join-Path $repoRoot (Join-Path $evidenceRel $runId)
New-Item -ItemType Directory -Path $evidenceDir -Force | Out-Null

$commitSha = "unknown"
try {
    $gitExe = Get-Command git -ErrorAction SilentlyContinue
    if ($null -ne $gitExe) {
        $sha = (& $gitExe.Source -C $repoRoot rev-parse HEAD 2>$null | Out-String).Trim()
        if (-not [string]::IsNullOrWhiteSpace($sha)) { $commitSha = $sha }
    }
}
catch { }

# Target reachability status map
$targetStatuses = [ordered]@{
    Loopback        = [ordered]@{ status = "AVAILABLE"; label = "LOOPBACK"; reason = "Local loopback sandbox available." }
    WindowsUi       = [ordered]@{ status = "NOT_RUN"; label = "WINDOWS_UI"; reason = "Not requested in this mode." }
    LinuxVm         = [ordered]@{ status = "NOT_RUN"; label = "VM"; reason = "Not requested in this mode." }
    AndroidEmulator = [ordered]@{ status = "NOT_RUN"; label = "EMULATOR"; reason = "Not requested in this mode." }
    CrossPlatform   = [ordered]@{ status = "NOT_RUN"; label = "CROSS_PLATFORM"; reason = "Not requested in this mode." }
}

$vmDriver = Join-Path $e2eRoot "drivers\Invoke-VmRemote.ps1"
$androidDriver = Join-Path $e2eRoot "drivers\Invoke-AndroidUi.ps1"

$vmReady = $false
$androidReady = $false
$winUiReady = ($env:GLIDE_E2E_WINDOWS_UI_READY -eq "1")

if ($Mode -eq "LiveVm" -or $Mode -eq "All") {
    $vmProbeRaw = & pwsh -NoProfile -File $vmDriver -Action Probe -VmHost $resolvedVmHost -VmUser $resolvedVmUser
    $vmProbe = $vmProbeRaw | ConvertFrom-Json
    if ($vmProbe.reachable) {
        $vmReady = $true
        $targetStatuses["LinuxVm"] = [ordered]@{ status = "AVAILABLE"; label = "VM"; reason = [string]$vmProbe.reason }
    }
    else {
        $statusStr = if ($Mode -eq "LiveVm") { "BLOCKED_TARGET_UNAVAILABLE" } else { "SKIPPED_TARGET_UNAVAILABLE" }
        $targetStatuses["LinuxVm"] = [ordered]@{ status = $statusStr; label = "VM"; reason = [string]$vmProbe.reason }
        if ($Mode -eq "LiveVm") {
            $summary = [ordered]@{
                schemaVersion = 1
                runId         = $runId
                timestampUtc  = [DateTimeOffset]::UtcNow.ToString("O")
                buildCommit   = $commitSha
                mode          = $Mode
                configuration = $Configuration
                status        = "BLOCKED_TARGET_UNAVAILABLE"
                targets       = $targetStatuses
                counts        = [ordered]@{ total = 0; passed = 0; failed = 0; skipped = 0 }
                tests         = @()
            }
            $summaryPath = Join-Path $evidenceDir "summary.json"
            ($summary | ConvertTo-Json -Depth 6) | Set-Content -LiteralPath $summaryPath -Encoding utf8
            Write-Error "LiveVm mode failed closed: $($vmProbe.reason) (Report: $summaryPath)"
            exit 1
        }
    }
}

if ($Mode -eq "LiveAndroid" -or $Mode -eq "All") {
    $andProbeRaw = & pwsh -NoProfile -File $androidDriver -Action Probe -Serial $resolvedSerial
    $andProbe = $andProbeRaw | ConvertFrom-Json
    if ($andProbe.reachable -and $andProbe.packageInstalled) {
        $androidReady = $true
        $targetStatuses["AndroidEmulator"] = [ordered]@{ status = "AVAILABLE"; label = [string]$andProbe.targetLabel; reason = [string]$andProbe.reason }
    }
    else {
        $statusStr = if ($Mode -eq "LiveAndroid") { "BLOCKED_TARGET_UNAVAILABLE" } else { "SKIPPED_TARGET_UNAVAILABLE" }
        $targetStatuses["AndroidEmulator"] = [ordered]@{ status = $statusStr; label = [string]$andProbe.targetLabel; reason = [string]$andProbe.reason }
        if ($Mode -eq "LiveAndroid") {
            $summary = [ordered]@{
                schemaVersion = 1
                runId         = $runId
                timestampUtc  = [DateTimeOffset]::UtcNow.ToString("O")
                buildCommit   = $commitSha
                mode          = $Mode
                configuration = $Configuration
                status        = "BLOCKED_TARGET_UNAVAILABLE"
                targets       = $targetStatuses
                counts        = [ordered]@{ total = 0; passed = 0; failed = 0; skipped = 0 }
                tests         = @()
            }
            $summaryPath = Join-Path $evidenceDir "summary.json"
            ($summary | ConvertTo-Json -Depth 6) | Set-Content -LiteralPath $summaryPath -Encoding utf8
            Write-Error "LiveAndroid mode failed closed: $($andProbe.reason) (Report: $summaryPath)"
            exit 1
        }
    }
}

if ($Mode -eq "All") {
    if ($winUiReady) {
        $targetStatuses["WindowsUi"] = [ordered]@{ status = "AVAILABLE"; label = "WINDOWS_UI"; reason = "Windows UI automation opt-in active." }
    }
    else {
        $targetStatuses["WindowsUi"] = [ordered]@{ status = "SKIPPED_TARGET_UNAVAILABLE"; label = "WINDOWS_UI"; reason = "Live WPF window automation requires GLIDE_E2E_WINDOWS_UI_READY=1." }
    }
    if ($vmReady -and $androidReady) {
        $targetStatuses["CrossPlatform"] = [ordered]@{ status = "AVAILABLE"; label = "CROSS_PLATFORM"; reason = "VM and Android peers reachable; all CrossPlatform tests enabled." }
    }
    elseif ($vmReady -or $androidReady) {
        $which = if ($vmReady) { "VM" } else { "Android" }
        $targetStatuses["CrossPlatform"] = [ordered]@{ status = "PARTIAL"; label = "CROSS_PLATFORM"; reason = "Only $which peer reachable; matching CrossTarget subset enabled." }
    }
    else {
        $targetStatuses["CrossPlatform"] = [ordered]@{ status = "SKIPPED_TARGET_UNAVAILABLE"; label = "CROSS_PLATFORM"; reason = "Cross-platform live peers (VM/Android) are unavailable." }
    }
}

# Build step (serialized via mutex and -m:1 with MSB3026/MSB3027 retry)
if (-not $SkipPreflight -and -not $NoBuild) {
    Invoke-WithBuildLock {
        Write-Host "[E2E] Building Glide.E2E.Tests ($Configuration)..."
        Invoke-DotnetWithRetry -DotnetArgs @("build", $testProject, "--nologo", "--disable-build-servers", "-m:1", "-c", $Configuration, "-v", "minimal") -Description "Build Glide.E2E.Tests"

        if ($Mode -ne "DryRun") {
            $driverBin = Join-Path $repoRoot "desktop\tests\Glide.BackendTestDriver\bin\$Configuration\net10.0\Glide.BackendTestDriver.dll"
            $appBin = Join-Path $repoRoot "desktop\src\Glide.App\bin\$Configuration\net10.0\Glide.App.dll"
            if (-not (Test-Path -LiteralPath $driverBin)) {
                Write-Host "[E2E] Building Glide.BackendTestDriver ($Configuration)..."
                Invoke-DotnetWithRetry -DotnetArgs @("build", $driverProject, "--nologo", "--disable-build-servers", "-m:1", "-c", $Configuration, "-v", "minimal") -Description "Build Glide.BackendTestDriver"
            }
            if (-not (Test-Path -LiteralPath $appBin)) {
                Write-Host "[E2E] Building Glide.App ($Configuration)..."
                Invoke-DotnetWithRetry -DotnetArgs @("build", $appProject, "--nologo", "--disable-build-servers", "-m:1", "-c", $Configuration, "-v", "minimal") -Description "Build Glide.App"
            }
        }

        if (($Mode -eq "LiveVm" -or $Mode -eq "All") -and $vmReady) {
            $linuxBinRoot = Join-Path $repoRoot "build\e2e-linux-bin"
            $appOut = Join-Path $linuxBinRoot "app"
            $drvOut = Join-Path $linuxBinRoot "driver"
            $uiOut = Join-Path $linuxBinRoot "ui"
            Write-Host "[E2E] Publishing linux-x64 suite binaries to $linuxBinRoot..."
            Invoke-DotnetWithRetry -DotnetArgs @("publish", $appProject, "--nologo", "--disable-build-servers", "-m:1", "-c", $Configuration, "-r", "linux-x64", "--self-contained", "true", "-o", $appOut, "-v", "minimal") -Description "Publish Glide.App linux-x64"
            Invoke-DotnetWithRetry -DotnetArgs @("publish", $driverProject, "--nologo", "--disable-build-servers", "-m:1", "-c", $Configuration, "-r", "linux-x64", "--self-contained", "true", "-o", $drvOut, "-v", "minimal") -Description "Publish Glide.BackendTestDriver linux-x64"
            Invoke-DotnetWithRetry -DotnetArgs @("publish", $linuxUiProject, "--nologo", "--disable-build-servers", "-m:1", "-c", $Configuration, "-r", "linux-x64", "--self-contained", "true", "-o", $uiOut, "-v", "minimal") -Description "Publish Glide.Linux.UI linux-x64"
        }
    }
}

# Build xUnit filter expression
$filterParts = @()
if ($Tier -ne "All") { $filterParts += "Tier=$Tier" }
if (-not [string]::IsNullOrWhiteSpace($TestId)) { $filterParts += "Id=$TestId" }
if (-not [string]::IsNullOrWhiteSpace($Filter)) { $filterParts += "($Filter)" }

switch ($Mode) {
    "DryRun" {
        # No target filter unless user passed -Filter/-TestId/-Tier
    }
    "LocalLoopback" {
        $filterParts += "Target=Loopback"
    }
    "LiveVm" {
        $filterParts += "Target=LinuxVm"
    }
    "LiveAndroid" {
        $filterParts += "Target=AndroidEmulator"
    }
    "All" {
        $availTargets = @("Target=Loopback")
        if ($winUiReady) { $availTargets += "Target=WindowsUi" }
        if ($vmReady) {
            $availTargets += "Target=LinuxVm"
            $availTargets += "(Target=CrossPlatform&CrossTarget=Vm)"
        }
        if ($androidReady) {
            $availTargets += "Target=AndroidEmulator"
            $availTargets += "(Target=CrossPlatform&CrossTarget=Android)"
        }
        if ($vmReady -and $androidReady) {
            $availTargets += "(Target=CrossPlatform&CrossTarget=All)"
        }
        $filterParts += ("(" + ($availTargets -join "|") + ")")
    }
}

$combinedFilter = $filterParts -join "&"

# Export environment variables for Glide.E2E.Tests
$env:GLIDE_E2E_MODE = $Mode
$env:GLIDE_E2E_CONFIGURATION = $Configuration
$env:GLIDE_E2E_REPO_ROOT = $repoRoot
$env:GLIDE_E2E_EVIDENCE_DIR = $evidenceDir
$env:GLIDE_E2E_RUN_ID = $runId
$env:GLIDE_E2E_KEEP_ARTIFACTS = if ($KeepArtifacts) { "1" } else { "0" }
$env:GLIDE_E2E_VM_HOST = $resolvedVmHost
$env:GLIDE_E2E_VM_USER = $resolvedVmUser
$env:GLIDE_E2E_ANDROID_SERIAL = $resolvedSerial
$env:GLIDE_E2E_VM_READY = if ($vmReady) { "1" } else { "0" }
$env:GLIDE_E2E_ANDROID_READY = if ($androidReady) { "1" } else { "0" }
$env:GLIDE_E2E_WINDOWS_UI_READY = if ($winUiReady) { "1" } else { "0" }

if ($Mode -eq "DryRun") {
    $listArgs = @("test", $testProject, "--no-build", "-c", $Configuration, "--list-tests")
    if (-not [string]::IsNullOrWhiteSpace($combinedFilter)) {
        $listArgs += @("--filter", $combinedFilter)
    }
    $prevPref = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $listOutput = & dotnet @listArgs 2>&1
    $listCode = $LASTEXITCODE
    $ErrorActionPreference = $prevPref
    $listJoined = ($listOutput | Out-String)
    if ($listJoined -match "No test matches the given testcase filter") {
        $listOutput = @()
    }
    elseif ($listCode -ne 0) {
        Write-Host $listJoined
        throw "dotnet test --list-tests failed with exit code $listCode."
    }

    # Parse discovered test methods after "The following Tests are available:"
    $discoveredTests = @()
    $inList = $false
    foreach ($line in $listOutput) {
        $s = [string]$line
        if ($s -match "The following Tests are available:") {
            $inList = $true
            continue
        }
        if ($inList -and -not [string]::IsNullOrWhiteSpace($s)) {
            $trimmed = $s.Trim()
            if ($trimmed -notmatch "^Test run for" -and
                $trimmed -notmatch "^VSTest" -and
                $trimmed -notmatch "^No test matches" -and
                $trimmed -notmatch "^Additionally," -and
                $trimmed -notmatch "^Determining projects") {
                $discoveredTests += $trimmed
            }
        }
    }

    # Verify trait IDs in source files
    $csFiles = @(Get-ChildItem -Path (Join-Path $e2eRoot "Glide.E2E.Tests") -Filter "Tier*.cs")
    $traitIds = New-Object System.Collections.Generic.List[string]
    foreach ($f in $csFiles) {
        $raw = Get-Content -LiteralPath $f.FullName -Raw
        $idMatches = [regex]::Matches($raw, '\[Trait\("Id",\s*"(E2E-T\d-\d{3})"\)\]')
        foreach ($m in $idMatches) {
            $traitIds.Add($m.Groups[1].Value)
        }
    }
    $uniqueIds = @($traitIds | Sort-Object -Unique)

    $dryStatus = if ($discoveredTests.Count -le 0) { "NO_TESTS_MATCHED" } else { "DRY_RUN_OK" }
    $dryExitCode = if ($dryStatus -ne "DRY_RUN_OK") { 1 } else { 0 }

    $summary = [ordered]@{
        schemaVersion    = 1
        runId            = $runId
        timestampUtc     = [DateTimeOffset]::UtcNow.ToString("O")
        buildCommit      = $commitSha
        mode             = $Mode
        configuration    = $Configuration
        filter           = $combinedFilter
        status           = $dryStatus
        exitCode         = $dryExitCode
        targets          = $targetStatuses
        counts           = [ordered]@{
            discoveredTestMethods = $discoveredTests.Count
            declaredTraitIds      = $uniqueIds.Count
            passed                = 0
            failed                = 0
            skipped               = 0
        }
        declaredIds      = $uniqueIds
        discoveredTests  = $discoveredTests
    }

    $summaryPath = Join-Path $evidenceDir "summary.json"
    ($summary | ConvertTo-Json -Depth 6) | Set-Content -LiteralPath $summaryPath -Encoding utf8

    Write-Host "[E2E] DryRun discovered $($discoveredTests.Count) xUnit tests and $($uniqueIds.Count) unique E2E trait IDs (Status=$dryStatus)."
    Write-Host "[E2E] Summary written to: $summaryPath"
    if ($dryStatus -ne "DRY_RUN_OK") {
        Write-Error "DryRun matched 0 tests for filter '$combinedFilter'."
        exit 1
    }
    exit 0
}

$trxPath = Join-Path $evidenceDir "e2e.trx"
$consoleLogPath = Join-Path $evidenceDir "console.log"
$testArgs = @("test", $testProject, "--no-build", "-c", $Configuration, "--logger", "trx;LogFileName=$trxPath", "--verbosity", "normal")
if (-not [string]::IsNullOrWhiteSpace($combinedFilter)) {
    $testArgs += @("--filter", $combinedFilter)
}

Write-Host "[E2E] Running dotnet test (Mode=$Mode, Filter='$combinedFilter')..."
$prevPref = $ErrorActionPreference
$ErrorActionPreference = "Continue"
$testOutput = & dotnet @testArgs 2>&1
$testExitCode = $LASTEXITCODE
$ErrorActionPreference = $prevPref

$redactedConsole = Redact-Secrets ($testOutput | Out-String)
$redactedConsole | Set-Content -LiteralPath $consoleLogPath -Encoding utf8
Write-Host $redactedConsole

$parsedTests = @()
$totalCount = 0
$passedCount = 0
$failedCount = 0
$skippedCount = 0

if (Test-Path -LiteralPath $trxPath) {
    $rawTrx = Get-Content -LiteralPath $trxPath -Raw -Encoding utf8
    $redactedTrx = Redact-Secrets $rawTrx
    if ($redactedTrx -ne $rawTrx) {
        Set-Content -LiteralPath $trxPath -Value $redactedTrx -Encoding utf8 -NoNewline
    }

    [xml]$trx = $redactedTrx
    $ns = New-Object System.Xml.XmlNamespaceManager($trx.NameTable)
    $ns.AddNamespace("t", "http://microsoft.com/schemas/VisualStudio/TeamTest/2010")
    $counters = $trx.SelectSingleNode("//t:ResultSummary/t:Counters", $ns)
    if ($null -ne $counters) {
        $totalCount = [int]$counters.total
        $passedCount = [int]$counters.passed
        $failedCount = [int]$counters.failed
        $skippedCount = $totalCount - $passedCount - $failedCount
    }
    $resultsNodes = $trx.SelectNodes("//t:Results/t:UnitTestResult", $ns)
    foreach ($node in $resultsNodes) {
        $errMsg = ""
        $msgNode = $node.SelectSingleNode("t:Output/t:ErrorInfo/t:Message", $ns)
        if ($null -ne $msgNode) {
            $errMsg = Redact-Secrets $msgNode.InnerText
        }
        $parsedTests += [ordered]@{
            testName = [string]$node.testName
            outcome  = [string]$node.outcome
            duration = [string]$node.duration
            error    = $errMsg
        }
    }
}

$noTestsMatched = ($totalCount -le 0)
$runStatus = if ($noTestsMatched) {
    "NO_TESTS_MATCHED"
}
elseif ($testExitCode -eq 0 -and $failedCount -eq 0 -and $passedCount -gt 0 -and $skippedCount -eq 0) {
    "PASSED"
}
else {
    "FAILED"
}
$finalExitCode = if ($runStatus -eq "PASSED") { 0 } elseif ($testExitCode -ne 0) { $testExitCode } else { 1 }

$summaryObj = [ordered]@{
    schemaVersion = 1
    runId         = $runId
    timestampUtc  = [DateTimeOffset]::UtcNow.ToString("O")
    buildCommit   = $commitSha
    mode          = $Mode
    configuration = $Configuration
    filter        = $combinedFilter
    status        = $runStatus
    exitCode      = $finalExitCode
    targets       = $targetStatuses
    counts        = [ordered]@{
        total   = $totalCount
        passed  = $passedCount
        failed  = $failedCount
        skipped = $skippedCount
    }
    artifacts     = [ordered]@{
        trx        = $trxPath
        consoleLog = $consoleLogPath
    }
    tests         = $parsedTests
}

$summaryJsonPath = Join-Path $evidenceDir "summary.json"
$redactedJson = Redact-Secrets ($summaryObj | ConvertTo-Json -Depth 6)
$redactedJson | Set-Content -LiteralPath $summaryJsonPath -Encoding utf8

Write-Host "[E2E] Run completed: Status=$runStatus Total=$totalCount Passed=$passedCount Failed=$failedCount Skipped=$skippedCount"
Write-Host "[E2E] Summary written to: $summaryJsonPath"

exit $finalExitCode
