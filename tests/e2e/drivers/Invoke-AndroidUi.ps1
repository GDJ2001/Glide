[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet(
        "Probe", "LaunchDebugState", "ShareSend", "ShareSendMultiple", "ShareText",
        "DumpUi", "AssertUiNode", "TapNode", "TapText", "SwipeScroll", "InputText",
        "PushAndIndexFile", "GrantPermissions", "CheckServiceRunning",
        "HashDeviceFile", "RunAsExec", "PushFile", "PullFile", "ClearData", "Cleanup"
    )]
    [string]$Action,

    [string]$Serial = $env:GLIDE_E2E_ANDROID_SERIAL,
    [string]$Package = "dev.glide.app.debug",
    [string]$DebugState = "",
    [string]$DebugTheme = "",
    [string]$DebugPosture = "",
    [string]$MimeType = "*/*",
    [string]$ContentUri = "",
    [string[]]$ContentUris = @(),
    [string]$DevicePath = "",
    [string]$LocalPath = "",
    [string]$Text = "",
    [string]$TextContains = "",
    [string]$TextExact = "",
    [string]$ContentDescContains = "",
    [string]$ResourceId = "",
    [int]$Index = 0,
    [switch]$IgnoreCase,
    [switch]$Absent,
    [switch]$NoRestart,
    [ValidateSet("Down", "Up")]
    [string]$Direction = "Down",
    [int]$DurationMs = 350,
    [string]$Command = "",
    [int]$TimeoutSeconds = 30
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

if ([string]::IsNullOrWhiteSpace($Serial)) { $Serial = "emulator-5554" }

function Get-AdbExe {
    $adb = Get-Command adb -ErrorAction SilentlyContinue
    if ($null -ne $adb) { return $adb.Source }
    $sdkRoot = $env:ANDROID_SDK_ROOT
    if ([string]::IsNullOrWhiteSpace($sdkRoot)) { $sdkRoot = $env:ANDROID_HOME }
    if ([string]::IsNullOrWhiteSpace($sdkRoot)) { $sdkRoot = Join-Path $env:LOCALAPPDATA "Android\Sdk" }
    $candidate = Join-Path $sdkRoot "platform-tools\adb.exe"
    if (Test-Path -LiteralPath $candidate) { return $candidate }
    return $null
}

function Invoke-Adb {
    param([string[]]$AdbArgs)
    $adbExe = Get-AdbExe
    if ($null -eq $adbExe) {
        throw "adb executable not found in PATH or ANDROID_SDK_ROOT."
    }
    $fullArgs = @("-s", $Serial) + $AdbArgs
    $prevPref = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $output = & $adbExe @fullArgs 2>&1
    $code = $LASTEXITCODE
    $ErrorActionPreference = $prevPref
    return [pscustomobject]@{
        ExitCode = $code
        Output   = ($output | Out-String).Trim()
    }
}

function Get-UiXmlWithRetry {
    $maxAttempts = 5
    $lastErr = ""
    for ($attempt = 1; $attempt -le $maxAttempts; $attempt++) {
        $dumpRes = Invoke-Adb @("shell", "uiautomator", "dump", "/sdcard/glide-e2e-ui.xml")
        if ($dumpRes.ExitCode -eq 0 -and $dumpRes.Output -notmatch "ERROR:\s*could not get idle state") {
            $xmlRes = Invoke-Adb @("exec-out", "cat", "/sdcard/glide-e2e-ui.xml")
            if ($xmlRes.ExitCode -eq 0 -and $xmlRes.Output -match "<hierarchy") {
                return $xmlRes.Output
            }
            $lastErr = "cat uiautomator XML invalid (exit $($xmlRes.ExitCode)): $($xmlRes.Output)"
        }
        else {
            $lastErr = "uiautomator dump failed (exit $($dumpRes.ExitCode)): $($dumpRes.Output)"
        }
        if ($attempt -lt $maxAttempts) {
            Start-Sleep -Milliseconds 350
        }
    }
    throw "DumpUi failed after $maxAttempts attempts: $lastErr"
}

function Find-MatchingUiNodes {
    param([xml]$XmlDoc)
    $nodes = $XmlDoc.SelectNodes("//node")
    $matched = @()
    foreach ($n in $nodes) {
        $t = [string]$n.GetAttribute("text")
        $cd = [string]$n.GetAttribute("content-desc")
        $rid = [string]$n.GetAttribute("resource-id")

        if (-not [string]::IsNullOrEmpty($TextExact)) {
            if ($IgnoreCase) {
                if ($t -ne $TextExact) { continue }
            }
            else {
                if ($t -cne $TextExact) { continue }
            }
        }
        if (-not [string]::IsNullOrEmpty($TextContains) -and $t.IndexOf($TextContains, [StringComparison]::OrdinalIgnoreCase) -lt 0) { continue }
        if (-not [string]::IsNullOrEmpty($ContentDescContains) -and $cd.IndexOf($ContentDescContains, [StringComparison]::OrdinalIgnoreCase) -lt 0) { continue }
        if (-not [string]::IsNullOrEmpty($ResourceId) -and $rid -ne $ResourceId -and $rid -ne "$Package`:id/$ResourceId") { continue }

        $matched += $n
    }
    return ,$matched
}

function Wait-UiCondition {
    param([int]$TimeoutSec)
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSec)
    $lastXml = ""
    while ($true) {
        try {
            $lastXml = Get-UiXmlWithRetry
            [xml]$doc = $lastXml
            $found = Find-MatchingUiNodes -XmlDoc $doc
            if ($Absent) {
                if ($found.Count -eq 0) {
                    return [pscustomobject]@{
                        satisfied = $true
                        count     = 0
                        nodes     = @()
                        rawXml    = $lastXml
                    }
                }
            }
            else {
                if ($found.Count -gt $Index) {
                    return [pscustomobject]@{
                        satisfied = $true
                        count     = $found.Count
                        nodes     = $found
                        rawXml    = $lastXml
                    }
                }
            }
        }
        catch {
            $lastXml = $_.Exception.Message
        }

        if ([DateTime]::UtcNow -ge $deadline) {
            break
        }
        Start-Sleep -Milliseconds 500
    }

    return [pscustomobject]@{
        satisfied = $false
        count     = 0
        nodes     = @()
        rawXml    = $lastXml
    }
}

switch ($Action) {
    "Probe" {
        $adbExe = Get-AdbExe
        if ($null -eq $adbExe) {
            [pscustomobject]@{
                targetLabel      = "EMULATOR"
                reachable        = $false
                serial           = $Serial
                packageInstalled = $false
                reason           = "adb executable not found."
            } | ConvertTo-Json -Depth 3
            exit 0
        }

        $prevPref = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        $stateOut = & $adbExe -s $Serial get-state 2>&1
        $stateCode = $LASTEXITCODE
        $ErrorActionPreference = $prevPref
        $stateStr = ($stateOut | Out-String).Trim()

        if ($stateCode -ne 0 -or $stateStr -ne "device") {
            [pscustomobject]@{
                targetLabel      = "EMULATOR"
                reachable        = $false
                serial           = $Serial
                packageInstalled = $false
                reason           = "ADB device '$Serial' is not in 'device' state ($stateStr)."
            } | ConvertTo-Json -Depth 3
            exit 0
        }

        $qemuRes = Invoke-Adb @("shell", "getprop", "ro.kernel.qemu")
        $targetLabel = if ($qemuRes.Output -eq "1" -or $Serial -like "emulator-*") { "EMULATOR" } else { "PHYSICAL" }
        $pkgRes = Invoke-Adb @("shell", "pm", "path", $Package)
        $installed = ($pkgRes.ExitCode -eq 0 -and $pkgRes.Output -match "package:")

        [pscustomobject]@{
            targetLabel      = $targetLabel
            reachable        = $true
            serial           = $Serial
            package          = $Package
            packageInstalled = $installed
            reason           = if ($installed) { "ADB device ready and package '$Package' installed." } else { "ADB device ready; package '$Package' not installed." }
        } | ConvertTo-Json -Depth 3
        exit 0
    }

    "LaunchDebugState" {
        if ([string]::IsNullOrWhiteSpace($DebugState)) {
            throw "LaunchDebugState requires -DebugState."
        }
        $cmdArgs = @("shell", "am", "start")
        if (-not $NoRestart) { $cmdArgs += "-S" }
        $cmdArgs += @("-W", "-n", "$Package/dev.glide.app.MainActivity", "--es", "debug_state", $DebugState)
        if (-not [string]::IsNullOrWhiteSpace($DebugTheme)) {
            $cmdArgs += @("--es", "debug_theme", $DebugTheme)
        }
        if (-not [string]::IsNullOrWhiteSpace($DebugPosture)) {
            $cmdArgs += @("--es", "debug_posture", $DebugPosture)
        }
        $res = Invoke-Adb $cmdArgs
        if ($res.ExitCode -ne 0) { throw "am start failed: $($res.Output)" }
        [pscustomobject]@{
            targetLabel = "EMULATOR"
            action      = "LaunchDebugState"
            debugState  = $DebugState
            restarted   = (-not $NoRestart)
            output      = $res.Output
        } | ConvertTo-Json -Depth 3
        exit 0
    }

    "ShareSend" {
        if ([string]::IsNullOrWhiteSpace($ContentUri)) {
            throw "ShareSend requires -ContentUri."
        }
        $cmdArgs = @("shell", "am", "start")
        if (-not $NoRestart) { $cmdArgs += "-S" }
        $cmdArgs += @(
            "-W",
            "-a", "android.intent.action.SEND",
            "-t", $MimeType,
            "-d", $ContentUri,
            "--eu", "android.intent.extra.STREAM", $ContentUri,
            "--grant-read-uri-permission",
            "-n", "$Package/dev.glide.app.MainActivity"
        )
        $res = Invoke-Adb $cmdArgs
        if ($res.ExitCode -ne 0) { throw "ShareSend failed: $($res.Output)" }
        [pscustomobject]@{
            targetLabel = "EMULATOR"
            action      = "ShareSend"
            restarted   = (-not $NoRestart)
            output      = $res.Output
        } | ConvertTo-Json -Depth 3
        exit 0
    }

    "ShareText" {
        if ([string]::IsNullOrWhiteSpace($Text)) {
            throw "ShareText requires -Text."
        }
        $cmdArgs = @("shell", "am", "start")
        if (-not $NoRestart) { $cmdArgs += "-S" }
        $cmdArgs += @(
            "-W",
            "-a", "android.intent.action.SEND",
            "-t", "text/plain",
            "--es", "android.intent.extra.TEXT", $Text,
            "-n", "$Package/dev.glide.app.MainActivity"
        )
        $res = Invoke-Adb $cmdArgs
        if ($res.ExitCode -ne 0) { throw "ShareText failed: $($res.Output)" }
        [pscustomobject]@{
            targetLabel = "EMULATOR"
            action      = "ShareText"
            output      = $res.Output
        } | ConvertTo-Json -Depth 3
        exit 0
    }

    "ShareSendMultiple" {
        $normalizedUris = @($ContentUris | ForEach-Object { $_ -split ',' } | ForEach-Object { $_.Trim() } | Where-Object { $_ })
        if ($normalizedUris.Count -lt 1) {
            throw "ShareSendMultiple requires at least one URI in -ContentUris."
        }
        $testPkg = "$Package.test"
        $pkgCheck = Invoke-Adb @("shell", "pm", "path", $testPkg)
        if ($pkgCheck.ExitCode -eq 0 -and $pkgCheck.Output -match "package:") {
            $instRes = Invoke-Adb @("shell", "am", "instrument", "-w", "-e", "class", "dev.glide.app.ShareIngressInstrumentationTest#actionSendMultiple_multipleFiles_routesToSelectionScreen", "$testPkg/androidx.test.runner.AndroidJUnitRunner")
            if ($instRes.ExitCode -ne 0 -or $instRes.Output -match "FAILURES!!!") {
                throw "ShareIngressInstrumentationTest failed: $($instRes.Output)"
            }
            [pscustomobject]@{
                targetLabel = "EMULATOR"
                action      = "ShareSendMultiple"
                mode        = "instrumentation"
                count       = $normalizedUris.Count
                output      = $instRes.Output
            } | ConvertTo-Json -Depth 3
            exit 0
        }
        throw "AOSP 'am start' has no ArrayList<Uri> CLI flag and '$testPkg' is not installed. Use LaunchDebugState -DebugState share_test_multiple or install androidTest APK."
    }

    "DumpUi" {
        $xmlText = Get-UiXmlWithRetry
        Write-Output $xmlText
        exit 0
    }

    "AssertUiNode" {
        if ([string]::IsNullOrEmpty($TextExact) -and [string]::IsNullOrEmpty($TextContains) -and [string]::IsNullOrEmpty($ContentDescContains) -and [string]::IsNullOrEmpty($ResourceId)) {
            throw "AssertUiNode requires at least one matcher (-TextExact, -TextContains, -ContentDescContains, -ResourceId)."
        }
        $res = Wait-UiCondition -TimeoutSec $TimeoutSeconds
        if (-not $res.satisfied) {
            throw "AssertUiNode timed out after ${TimeoutSeconds}s (Absent=$Absent, TextExact='$TextExact', TextContains='$TextContains', ContentDescContains='$ContentDescContains', ResourceId='$ResourceId'). Last UI: $($res.rawXml.Substring(0, [Math]::Min(1200, $res.rawXml.Length)))"
        }
        $sample = if ($res.nodes.Count -gt $Index) { $res.nodes[$Index] } else { $null }
        [pscustomobject]@{
            targetLabel = "EMULATOR"
            action      = "AssertUiNode"
            satisfied   = $true
            absent      = [bool]$Absent
            matchCount  = $res.count
            text        = if ($null -ne $sample) { [string]$sample.GetAttribute("text") } else { "" }
            contentDesc = if ($null -ne $sample) { [string]$sample.GetAttribute("content-desc") } else { "" }
            resourceId  = if ($null -ne $sample) { [string]$sample.GetAttribute("resource-id") } else { "" }
        } | ConvertTo-Json -Depth 3
        exit 0
    }

    "TapNode" {
        if ([string]::IsNullOrEmpty($TextExact) -and [string]::IsNullOrEmpty($TextContains) -and [string]::IsNullOrEmpty($ContentDescContains) -and [string]::IsNullOrEmpty($ResourceId)) {
            throw "TapNode requires at least one matcher (-TextExact, -TextContains, -ContentDescContains, -ResourceId)."
        }
        $res = Wait-UiCondition -TimeoutSec $TimeoutSeconds
        if (-not $res.satisfied) {
            throw "TapNode could not find target after ${TimeoutSeconds}s (TextExact='$TextExact', TextContains='$TextContains', ContentDescContains='$ContentDescContains', ResourceId='$ResourceId')."
        }
        $node = $res.nodes[$Index]
        $bounds = [string]$node.GetAttribute("bounds")
        $m = [regex]::Match($bounds, '\[(\d+),(\d+)\]\[(\d+),(\d+)\]')
        if (-not $m.Success) {
            throw "Matched node had invalid bounds '$bounds'."
        }
        $x1 = [int]$m.Groups[1].Value
        $y1 = [int]$m.Groups[2].Value
        $x2 = [int]$m.Groups[3].Value
        $y2 = [int]$m.Groups[4].Value
        $cx = [int](($x1 + $x2) / 2)
        $cy = [int](($y1 + $y2) / 2)
        $tapRes = Invoke-Adb @("shell", "input", "tap", "$cx", "$cy")
        if ($tapRes.ExitCode -ne 0) { throw "adb input tap failed: $($tapRes.Output)" }

        [pscustomobject]@{
            targetLabel = "EMULATOR"
            action      = "TapNode"
            found       = $true
            text        = [string]$node.GetAttribute("text")
            contentDesc = [string]$node.GetAttribute("content-desc")
            resourceId  = [string]$node.GetAttribute("resource-id")
            bounds      = $bounds
            cx          = $cx
            cy          = $cy
        } | ConvertTo-Json -Depth 3
        exit 0
    }

    "TapText" {
        if ([string]::IsNullOrWhiteSpace($Text)) {
            throw "TapText requires -Text."
        }
        $TextExact = $Text
        $res = Wait-UiCondition -TimeoutSec 3
        if (-not $res.satisfied) {
            $TextExact = ""
            $TextContains = $Text
            $res = Wait-UiCondition -TimeoutSec $TimeoutSeconds
        }
        if (-not $res.satisfied) {
            throw "TapText could not find '$Text'."
        }
        $node = $res.nodes[$Index]
        $bounds = [string]$node.GetAttribute("bounds")
        $m = [regex]::Match($bounds, '\[(\d+),(\d+)\]\[(\d+),(\d+)\]')
        if (-not $m.Success) { throw "Invalid bounds '$bounds'." }
        $cx = [int](([int]$m.Groups[1].Value + [int]$m.Groups[3].Value) / 2)
        $cy = [int](([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2)
        $tapRes = Invoke-Adb @("shell", "input", "tap", "$cx", "$cy")
        if ($tapRes.ExitCode -ne 0) { throw "adb input tap failed: $($tapRes.Output)" }
        [pscustomobject]@{
            targetLabel = "EMULATOR"
            action      = "TapText"
            text        = [string]$node.GetAttribute("text")
            bounds      = $bounds
            cx          = $cx
            cy          = $cy
        } | ConvertTo-Json -Depth 3
        exit 0
    }

    "SwipeScroll" {
        $sizeRes = Invoke-Adb @("shell", "wm", "size")
        $w = 1080; $h = 2400
        $m = [regex]::Match($sizeRes.Output, '(\d+)x(\d+)')
        if ($m.Success) {
            $w = [int]$m.Groups[1].Value
            $h = [int]$m.Groups[2].Value
        }
        $x = [int]($w / 2)
        if ($Direction -eq "Down") {
            $y1 = [int]($h * 0.75); $y2 = [int]($h * 0.25)
        }
        else {
            $y1 = [int]($h * 0.25); $y2 = [int]($h * 0.75)
        }
        $swRes = Invoke-Adb @("shell", "input", "swipe", "$x", "$y1", "$x", "$y2", "$DurationMs")
        if ($swRes.ExitCode -ne 0) { throw "SwipeScroll failed: $($swRes.Output)" }
        [pscustomobject]@{
            targetLabel = "EMULATOR"
            action      = "SwipeScroll"
            direction   = $Direction
            x           = $x
            y1          = $y1
            y2          = $y2
        } | ConvertTo-Json -Depth 3
        exit 0
    }

    "InputText" {
        if ([string]::IsNullOrEmpty($Text)) {
            throw "InputText requires -Text."
        }

        $editDeadline = [DateTime]::UtcNow.AddSeconds(5)
        $targetEdit = $null
        while ([DateTime]::UtcNow -lt $editDeadline) {
            try {
                [xml]$preDoc = Get-UiXmlWithRetry
                $preEdits = $preDoc.SelectNodes("//node[@class='android.widget.EditText']")
                if ($null -ne $preEdits -and $preEdits.Count -gt 0) {
                    $targetEdit = $preEdits[0]
                    break
                }
            }
            catch { }
            Start-Sleep -Milliseconds 300
        }
        if ($null -eq $targetEdit) {
            throw "InputText could not find an EditText element within 5s."
        }
        if ($targetEdit.GetAttribute("focused") -ne "true") {
            $bounds = [string]$targetEdit.GetAttribute("bounds")
            $bm = [regex]::Match($bounds, '\[(\d+),(\d+)\]\[(\d+),(\d+)\]')
            if ($bm.Success) {
                $cx = [int](([int]$bm.Groups[1].Value + [int]$bm.Groups[3].Value) / 2)
                $cy = [int](([int]$bm.Groups[2].Value + [int]$bm.Groups[4].Value) / 2)
                $tapRes = Invoke-Adb @("shell", "input", "tap", "$cx", "$cy")
                if ($tapRes.ExitCode -ne 0) { throw "InputText focus tap failed: $($tapRes.Output)" }
                Start-Sleep -Milliseconds 300
            }
        }

        $escapedText = $Text.Replace("'", "'\''")
        $inRes = Invoke-Adb @("shell", "input text '$escapedText'")
        if ($inRes.ExitCode -ne 0) { throw "InputText failed: $($inRes.Output)" }

        Start-Sleep -Milliseconds 300
        $xmlText = Get-UiXmlWithRetry
        [xml]$doc = $xmlText
        $editNodes = $doc.SelectNodes("//node[@class='android.widget.EditText']")
        $observed = ""
        $verified = $false
        foreach ($en in $editNodes) {
            $val = [string]$en.GetAttribute("text")
            if (-not [string]::IsNullOrEmpty($val)) {
                $observed = $val
                if ($val.Contains($Text, [StringComparison]::Ordinal)) {
                    $verified = $true
                    break
                }
            }
        }
        if (-not $verified) {
            throw "InputText verification failed: EditText text '$observed' did not contain full expected text (len=$($Text.Length))."
        }

        [pscustomobject]@{
            targetLabel = "EMULATOR"
            action      = "InputText"
            verified    = $true
            length      = $Text.Length
        } | ConvertTo-Json -Depth 3
        exit 0
    }

    "PushAndIndexFile" {
        if ([string]::IsNullOrWhiteSpace($LocalPath) -or -not (Test-Path -LiteralPath $LocalPath)) {
            throw "PushAndIndexFile requires an existing -LocalPath."
        }
        if ([string]::IsNullOrWhiteSpace($DevicePath)) {
            $DevicePath = "/sdcard/Download/$(Split-Path $LocalPath -Leaf)"
        }
        $parentDir = [System.IO.Path]::GetDirectoryName($DevicePath.Replace('/', '\')).Replace('\', '/')
        Invoke-Adb @("shell", "mkdir", "-p", $parentDir) | Out-Null
        $pushRes = Invoke-Adb @("push", $LocalPath, $DevicePath)
        if ($pushRes.ExitCode -ne 0) { throw "adb push failed: $($pushRes.Output)" }

        Invoke-Adb @("shell", "content", "call", "--uri", "content://media/external/file", "--method", "scan_volume", "--arg", "external_primary") | Out-Null
        Invoke-Adb @("shell", "am", "broadcast", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE", "-d", "file://$DevicePath") | Out-Null

        $mediaId = $null
        $deadline = [DateTime]::UtcNow.AddSeconds(10)
        while ([DateTime]::UtcNow -lt $deadline) {
            $qRes = Invoke-Adb @("shell", "content query --uri content://media/external/file --projection _id:_data --where ""_data='$DevicePath'""")
            $m = [regex]::Match($qRes.Output, '_id=(\d+)')
            if ($m.Success) {
                $mediaId = $m.Groups[1].Value
                break
            }
            Start-Sleep -Milliseconds 400
        }
        if ([string]::IsNullOrWhiteSpace($mediaId)) {
            throw "MediaStore did not index '$DevicePath' within 10s."
        }

        [pscustomobject]@{
            targetLabel = "EMULATOR"
            action      = "PushAndIndexFile"
            devicePath  = $DevicePath
            mediaId     = $mediaId
            contentUri  = "content://media/external/file/$mediaId"
        } | ConvertTo-Json -Depth 3
        exit 0
    }

    "GrantPermissions" {
        $perms = @(
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.READ_MEDIA_IMAGES",
            "android.permission.READ_MEDIA_VIDEO",
            "android.permission.READ_MEDIA_AUDIO",
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.BLUETOOTH_SCAN",
            "android.permission.BLUETOOTH_ADVERTISE",
            "android.permission.BLUETOOTH_CONNECT",
            "android.permission.NEARBY_WIFI_DEVICES",
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.ACCESS_LOCAL_NETWORK"
        )
        $granted = @()
        foreach ($perm in $perms) {
            $res = Invoke-Adb @("shell", "pm", "grant", $Package, $perm)
            if ($res.ExitCode -eq 0) { $granted += $perm }
        }
        [pscustomobject]@{
            targetLabel = "EMULATOR"
            action      = "GrantPermissions"
            granted     = $granted
        } | ConvertTo-Json -Depth 3
        exit 0
    }

    "CheckServiceRunning" {
        $svcRes = Invoke-Adb @("shell", "dumpsys", "activity", "services", "$Package/.service.TransferService")
        $notRes = Invoke-Adb @("shell", "dumpsys", "notification", "--noredact")
        $svcActive = ($svcRes.ExitCode -eq 0 -and $svcRes.Output -match "ServiceRecord\{.*$Package")
        $notFound = $false
        $notText = ""
        if ($notRes.ExitCode -eq 0) {
            $lines = @($notRes.Output -split "`r?`n")
            $pkgLines = @($lines | Select-String -Pattern ([regex]::Escape($Package)) -Context 0, 8)
            if ($pkgLines.Count -gt 0) {
                $notFound = $true
                $notText = ($pkgLines | ForEach-Object { $_.ToString() }) -join "`n"
            }
        }
        [pscustomobject]@{
            targetLabel       = "EMULATOR"
            action            = "CheckServiceRunning"
            serviceActive     = $svcActive
            notificationFound = $notFound
            notificationText  = $notText
        } | ConvertTo-Json -Depth 3
        exit 0
    }

    "HashDeviceFile" {
        if ([string]::IsNullOrWhiteSpace($DevicePath)) {
            throw "HashDeviceFile requires -DevicePath."
        }
        $isPrivate = $DevicePath.StartsWith("/data/data/$Package/", [StringComparison]::Ordinal) -or
                     $DevicePath.StartsWith("/data/user/0/$Package/", [StringComparison]::Ordinal)
        if ($isPrivate) {
            $sizeRes = Invoke-Adb @("shell", "run-as", $Package, "sh", "-c", "wc -c < '$DevicePath'")
            $shaRes = Invoke-Adb @("shell", "run-as", $Package, "sha256sum", $DevicePath)
        }
        else {
            $sizeRes = Invoke-Adb @("shell", "sh", "-c", "wc -c < '$DevicePath'")
            $shaRes = Invoke-Adb @("shell", "sha256sum", $DevicePath)
        }
        if ($sizeRes.ExitCode -ne 0 -or $shaRes.ExitCode -ne 0) {
            throw "Device file hash failed for '$DevicePath': size='$($sizeRes.Output)' sha='$($shaRes.Output)'"
        }
        $size = [long]$sizeRes.Output.Trim()
        $sha = ($shaRes.Output.Trim() -split '\s+')[0].ToLowerInvariant()
        [pscustomobject]@{
            targetLabel = "EMULATOR"
            devicePath  = $DevicePath
            sizeBytes   = $size
            sha256      = $sha
        } | ConvertTo-Json -Depth 3
        exit 0
    }

    "RunAsExec" {
        if ([string]::IsNullOrWhiteSpace($Command)) {
            throw "RunAsExec requires -Command."
        }
        $res = Invoke-Adb @("shell", "run-as", $Package, "sh", "-c", $Command)
        [pscustomobject]@{
            targetLabel = "EMULATOR"
            action      = "RunAsExec"
            exitCode    = $res.ExitCode
            output      = $res.Output
        } | ConvertTo-Json -Depth 3
        exit $res.ExitCode
    }

    "PushFile" {
        if ([string]::IsNullOrWhiteSpace($LocalPath) -or [string]::IsNullOrWhiteSpace($DevicePath)) {
            throw "PushFile requires -LocalPath and -DevicePath."
        }
        $res = Invoke-Adb @("push", $LocalPath, $DevicePath)
        if ($res.ExitCode -ne 0) { throw "adb push failed: $($res.Output)" }
        exit 0
    }

    "PullFile" {
        if ([string]::IsNullOrWhiteSpace($LocalPath) -or [string]::IsNullOrWhiteSpace($DevicePath)) {
            throw "PullFile requires -LocalPath and -DevicePath."
        }
        $res = Invoke-Adb @("pull", $DevicePath, $LocalPath)
        if ($res.ExitCode -ne 0) { throw "adb pull failed: $($res.Output)" }
        exit 0
    }

    "ClearData" {
        $res = Invoke-Adb @("shell", "pm", "clear", $Package)
        if ($res.ExitCode -ne 0) { throw "pm clear failed: $($res.Output)" }
        exit 0
    }

    "Cleanup" {
        if (-not [string]::IsNullOrWhiteSpace($DevicePath)) {
            Invoke-Adb @("shell", "rm", "-rf", $DevicePath) | Out-Null
        }
        Invoke-Adb @("shell", "rm", "-f", "/sdcard/glide-e2e-ui.xml") | Out-Null
        exit 0
    }
}
