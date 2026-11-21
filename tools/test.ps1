<#
.SYNOPSIS
    Glide Test Script — HARD-035.
.DESCRIPTION
    Runs the full test suite across Android (Gradle/JUnit) and Windows (.NET/xUnit).
    Supports filtering by test category and generating TRX/JUnit XML reports.

.PARAMETER Target
    Test target: All, Android, Windows. Default: All.

.PARAMETER Configuration
    Configuration: Debug or Release. Default: Debug.

.PARAMETER Filter
    Optional test filter expression (e.g., "FullyQualifiedName~MyTests" or "dev.glide.core.domain.ProtocolLimitsTest").

.PARAMETER Module
    Optional Android Gradle module when targeting Android (e.g., "app", "core", "data", "designsystem", "transport-lan", "transport-nearby").

.EXAMPLE
    pwsh -File test.ps1
    pwsh -File test.ps1 -Target Android -Filter "dev.glide.core.domain.ProtocolLimitsTest"
    pwsh -File test.ps1 -Target Android -Module core
    test.cmd
#>

[CmdletBinding()]
param(
    [ValidateSet("All", "Android", "Windows", "Desktop")]
    [string]$Target = "All",
    [ValidateSet("Debug", "Release")]
    [string]$Configuration = "Debug",
    [string]$Filter = "",
    [ValidateSet("", "app", ":app", "core", ":core", "data", ":data", "designsystem", ":designsystem", "transport-lan", ":transport-lan", "transport-nearby", ":transport-nearby")]
    [string]$Module = ""
)

$ErrorActionPreference = "Stop"
$projectRoot = (Resolve-Path "$PSScriptRoot\..").Path
. (Join-Path $PSScriptRoot 'resolve-dotnet.ps1')
$dotnetExecutable = if ($Target -in @('All', 'Windows', 'Desktop')) { Enable-GlideDotnetSdk } else { $null }

function Get-AndroidModuleTestTask {
    param(
        [Parameter(Mandatory = $true)][string]$ModuleName,
        [Parameter(Mandatory = $true)][string]$Config
    )
    $normalized = $ModuleName.TrimStart(':')
    if ($normalized -eq "core") {
        return ":core:test"
    }
    return ":${normalized}:test${Config}UnitTest"
}

function Resolve-MatchingAndroidModules {
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][string]$TestFilter
    )
    $allModules = @("app", "core", "data", "designsystem", "transport-lan", "transport-nearby")
    $wildcardPattern = if ($TestFilter.Contains("*") -or $TestFilter.Contains("?")) { $TestFilter } else { "*$TestFilter*" }
    $matched = [System.Collections.Generic.List[string]]::new()

    foreach ($mod in $allModules) {
        $testDir = Join-Path $Root "android\$mod\src\test"
        if (-not (Test-Path $testDir)) { continue }
        $files = Get-ChildItem -Path $testDir -Include "*.kt", "*.java" -Recurse -File -ErrorAction SilentlyContinue
        $modMatched = $false
        foreach ($file in $files) {
            $raw = Get-Content -Path $file.FullName -Raw -ErrorAction SilentlyContinue
            if (-not $raw) { continue }
            $pkg = ""
            if ($raw -match '(?m)^\s*package\s+([A-Za-z0-9_.]+)') {
                $pkg = $Matches[1]
            }
            $baseName = [System.IO.Path]::GetFileNameWithoutExtension($file.Name)
            $fqcn = if ($pkg) { "$pkg.$baseName" } else { $baseName }
            if (($fqcn -like $wildcardPattern) -or ($baseName -like $wildcardPattern) -or ($TestFilter.StartsWith("$fqcn.", [System.StringComparison]::Ordinal))) {
                $modMatched = $true
                break
            }
            foreach ($m in [regex]::Matches($raw, '(?m)^\s*(?:internal\s+|public\s+|private\s+)?(?:class|object)\s+([A-Za-z0-9_]+)')) {
                $cls = $m.Groups[1].Value
                $clsFqcn = if ($pkg) { "$pkg.$cls" } else { $cls }
                if (($clsFqcn -like $wildcardPattern) -or ($cls -like $wildcardPattern) -or ($TestFilter.StartsWith("$clsFqcn.", [System.StringComparison]::Ordinal))) {
                    $modMatched = $true
                    break
                }
            }
            if ($modMatched) { break }
        }
        if ($modMatched) {
            $matched.Add($mod)
        }
    }
    return $matched.ToArray()
}

Write-Host "=================================================================" -ForegroundColor Cyan
Write-Host "   Glide Test Run ($Configuration) — Target: $Target                 " -ForegroundColor Cyan
Write-Host "=================================================================" -ForegroundColor Cyan

$testErrors = [System.Collections.Generic.List[string]]::new()

# --- Android Test ---
if ($Target -eq "All" -or $Target -eq "Android") {
    Write-Host "`n[Android] Running unit tests via Gradle..." -ForegroundColor Yellow

    $androidInvocations = [System.Collections.Generic.List[object]]::new()
    if (-not [string]::IsNullOrWhiteSpace($Module)) {
        $normMod = $Module.TrimStart(':')
        $task = Get-AndroidModuleTestTask -ModuleName $normMod -Config $Configuration
        $argsList = @($task, "--no-daemon")
        if ($Filter) {
            $argsList += "--tests", "`"$Filter`""
        }
        $androidInvocations.Add([PSCustomObject]@{ Module = $normMod; Args = $argsList })
    } elseif (-not [string]::IsNullOrWhiteSpace($Filter)) {
        $matchingModules = @(Resolve-MatchingAndroidModules -Root $projectRoot -TestFilter $Filter)
        if ($matchingModules.Count -eq 0) {
            $testErrors.Add("No Android module under android/{app,core,data,designsystem,transport-lan,transport-nearby}/src/test matched filter '$Filter'.")
        } else {
            foreach ($mod in $matchingModules) {
                $task = Get-AndroidModuleTestTask -ModuleName $mod -Config $Configuration
                $androidInvocations.Add([PSCustomObject]@{ Module = $mod; Args = @($task, "--tests", "`"$Filter`"", "--no-daemon") })
            }
        }
    } else {
        $androidInvocations.Add([PSCustomObject]@{ Module = "all"; Args = @("test", "--no-daemon") })
    }

    foreach ($inv in $androidInvocations) {
        Write-Host "  Running: gradlew.bat $($inv.Args -join ' ')" -ForegroundColor Gray
        $pinfo = New-Object System.Diagnostics.ProcessStartInfo
        $pinfo.FileName = "$projectRoot\android\gradlew.bat"
        $pinfo.Arguments = $inv.Args -join " "
        $pinfo.WorkingDirectory = "$projectRoot\android"
        $pinfo.UseShellExecute = $false
        $pinfo.RedirectStandardOutput = $true
        $pinfo.RedirectStandardError = $true

        $process = [System.Diagnostics.Process]::Start($pinfo)
        $stdout = $process.StandardOutput.ReadToEnd()
        $stderr = $process.StandardError.ReadToEnd()
        $process.WaitForExit()

        if ($process.ExitCode -ne 0) {
            Write-Host $stdout
            Write-Host $stderr -ForegroundColor Red
            $testErrors.Add("Android unit tests failed for '$($inv.Module)' (exit code $($process.ExitCode))")
        } else {
            Write-Host "  [OK]   Android unit tests passed ($($inv.Module))." -ForegroundColor Green
            $reportSubdir = if ($inv.Module -eq "core") {
                "android\core\build\reports\tests\test"
            } elseif ($inv.Module -eq "all") {
                "android\app\build\reports\tests\test${Configuration}UnitTest"
            } else {
                "android\$($inv.Module)\build\reports\tests\test${Configuration}UnitTest"
            }
            $reportDir = Join-Path $projectRoot $reportSubdir
            if (Test-Path $reportDir) {
                Write-Host "  Report: $reportDir" -ForegroundColor Gray
            }
        }
    }
}

# --- Desktop (Windows / Linux) Test ---
if ($Target -eq "All" -or $Target -eq "Windows" -or $Target -eq "Desktop") {
    Write-Host "`n[Desktop] Running tests via dotnet test..." -ForegroundColor Yellow
    $testTarget = if (Test-Path "$projectRoot\desktop\Glide.slnx") {
        "$projectRoot\desktop\Glide.slnx"
    } else {
        "$projectRoot\desktop\tests\Glide.Tests\Glide.Tests.csproj"
    }
    $dotnetArgs = @("test", "`"$testTarget`"", "-c", $Configuration, "--nologo", "--logger", "console;verbosity=detailed")
    if ($Filter) {
        $dotnetArgs += "--filter", "`"$Filter`""
    }

    Write-Host "  Running: dotnet $($dotnetArgs -join ' ')" -ForegroundColor Gray
    $pinfo = New-Object System.Diagnostics.ProcessStartInfo
    $pinfo.FileName = $dotnetExecutable
    $pinfo.Arguments = $dotnetArgs -join " "
    $pinfo.WorkingDirectory = $projectRoot
    $pinfo.UseShellExecute = $false
    $pinfo.RedirectStandardOutput = $true
    $pinfo.RedirectStandardError = $true

    $process = [System.Diagnostics.Process]::Start($pinfo)
    $stdout = $process.StandardOutput.ReadToEnd()
    $stderr = $process.StandardError.ReadToEnd()
    $process.WaitForExit()

    if ($process.ExitCode -ne 0) {
        Write-Host $stdout
        Write-Host $stderr -ForegroundColor Red
        $testErrors.Add("Desktop tests failed (exit code $($process.ExitCode))")
    } else {
        Write-Host "  [OK]   Desktop tests passed." -ForegroundColor Green
    }
}

# --- Summary ---
Write-Host "`n=================================================================" -ForegroundColor Cyan
if ($testErrors.Count -eq 0) {
    Write-Host "   TEST PASSED                                                       " -ForegroundColor Green
    Write-Host "=================================================================" -ForegroundColor Cyan
    exit 0
} else {
    Write-Host "   TEST FAILED — $($testErrors.Count) error(s)                          " -ForegroundColor Red
    foreach ($e in $testErrors) { Write-Host "     - $e" -ForegroundColor Red }
    Write-Host "=================================================================" -ForegroundColor Cyan
    exit 1
}
