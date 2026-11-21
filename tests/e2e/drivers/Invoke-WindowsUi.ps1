[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet("PreflightCheck", "StartSession", "UiAction", "ForwardActivation", "ReadClipboard", "StopSession")]
    [string]$Action,

    [string]$ExePath = "",
    [string]$SessionFile = "",
    [string]$BackupDir = "",
    [string]$ReceiveDirectory = "",
    [int]$ListenerPort = 0,
    [string]$DiagnosticLogPath = "",
    [switch]$CleanAppData,
    [switch]$SeedState,

    [ValidateSet("SwitchTab", "Click", "WaitFor", "AssertVisible", "AssertAbsent", "SetText", "SelectCombo", "GetText", "GetValue", "GetComboSelection", "DumpTree")]
    [string]$UiCommand = "DumpTree",
    [string]$AutomationId = "",
    [string]$Name = "",
    [string]$ExpectedControlType = "",
    [string]$Value = "",
    [string]$FailureLogDir = "",
    [string[]]$ActivationArgs = @(),
    [int]$TimeoutSeconds = 15
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

if (-not $IsWindows) {
    throw "Invoke-WindowsUi.ps1 requires Windows."
}

Add-Type -AssemblyName UIAutomationClient, UIAutomationTypes, System.Windows.Forms, System.Drawing

if (-not ("GlideE2EWin32" -as [type])) {
    Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;

public static class GlideE2EWin32 {
    [DllImport("user32.dll", SetLastError = true)]
    public static extern bool SetForegroundWindow(IntPtr hWnd);

    [DllImport("user32.dll", SetLastError = true)]
    public static extern bool SetCursorPos(int X, int Y);

    [DllImport("user32.dll", SetLastError = true)]
    public static extern bool PostMessage(IntPtr hWnd, uint Msg, IntPtr wParam, IntPtr lParam);

    [DllImport("user32.dll")]
    public static extern void mouse_event(uint dwFlags, int dx, int dy, uint dwData, UIntPtr dwExtraInfo);

    public const uint MOUSEEVENTF_LEFTDOWN = 0x0002;
    public const uint MOUSEEVENTF_LEFTUP = 0x0004;
    public const uint WM_KEYDOWN = 0x0100;
    public const uint WM_KEYUP = 0x0101;
    public const int VK_SPACE = 0x20;

    public static bool ClickAt(int x, int y) {
        if (!SetCursorPos(x, y)) {
            return false;
        }
        System.Threading.Thread.Sleep(40);
        mouse_event(MOUSEEVENTF_LEFTDOWN, x, y, 0, UIntPtr.Zero);
        System.Threading.Thread.Sleep(30);
        mouse_event(MOUSEEVENTF_LEFTUP, x, y, 0, UIntPtr.Zero);
        return true;
    }
}
"@
}

function Test-GlideUserInstanceRunning {
    $existingMutex = $null
    $held = [System.Threading.Mutex]::TryOpenExisting("Local\Glide.Windows.UI", [ref]$existingMutex)
    if ($held) {
        if ($null -ne $existingMutex) { $existingMutex.Dispose() }
        return $true
    }
    $procs = @(Get-Process -Name "Glide" -ErrorAction SilentlyContinue)
    return ($procs.Count -gt 0)
}

function Copy-DirectoryContent {
    param([string]$Source, [string]$Destination)
    if (-not (Test-Path -LiteralPath $Source)) { return }
    New-Item -ItemType Directory -Path $Destination -Force | Out-Null
    Get-ChildItem -LiteralPath $Source -Force | ForEach-Object {
        Copy-Item -LiteralPath $_.FullName -Destination $Destination -Recurse -Force
    }
}

function Restore-AppData {
    param([string]$AppDataDir, [string]$SnapshotDir)
    if (Test-Path -LiteralPath $AppDataDir) {
        Remove-Item -LiteralPath $AppDataDir -Recurse -Force -ErrorAction SilentlyContinue
    }
    if (-not [string]::IsNullOrWhiteSpace($SnapshotDir) -and (Test-Path -LiteralPath $SnapshotDir)) {
        $marker = Join-Path $SnapshotDir ".glide-e2e-existed"
        if (Test-Path -LiteralPath $marker) {
            New-Item -ItemType Directory -Path $AppDataDir -Force | Out-Null
            Get-ChildItem -LiteralPath $SnapshotDir -Force | Where-Object { $_.Name -ne ".glide-e2e-existed" } | ForEach-Object {
                Copy-Item -LiteralPath $_.FullName -Destination $AppDataDir -Recurse -Force
            }
        }
    }
}

function Get-MainWindowElement {
    param([int]$ProcessId, [int]$TimeoutSec)
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSec)
    $cond = New-Object System.Windows.Automation.PropertyCondition(
        [System.Windows.Automation.AutomationElement]::ProcessIdProperty, $ProcessId)
    while ([DateTime]::UtcNow -lt $deadline) {
        try {
            $win = [System.Windows.Automation.AutomationElement]::RootElement.FindFirst(
                [System.Windows.Automation.TreeScope]::Children, $cond)
            if ($null -ne $win) {
                return $win
            }
        }
        catch { }
        Start-Sleep -Milliseconds 250
    }
    return $null
}

function Find-UiElement {
    param(
        [System.Windows.Automation.AutomationElement]$Root,
        [string]$AutoId,
        [string]$ElemName,
        [int]$TimeoutSec
    )
    $conditions = New-Object System.Collections.Generic.List[System.Windows.Automation.Condition]
    if (-not [string]::IsNullOrWhiteSpace($AutoId)) {
        $conditions.Add((New-Object System.Windows.Automation.PropertyCondition(
            [System.Windows.Automation.AutomationElement]::AutomationIdProperty, $AutoId)))
    }
    if (-not [string]::IsNullOrWhiteSpace($ElemName)) {
        $conditions.Add((New-Object System.Windows.Automation.PropertyCondition(
            [System.Windows.Automation.AutomationElement]::NameProperty, $ElemName)))
    }
    if ($conditions.Count -eq 0) {
        throw "UiAction requires -AutomationId or -Name."
    }

    $cond = if ($conditions.Count -eq 1) {
        $conditions[0]
    }
    else {
        New-Object System.Windows.Automation.AndCondition($conditions.ToArray())
    }

    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSec)
    while ([DateTime]::UtcNow -lt $deadline) {
        try {
            $all = $Root.FindAll([System.Windows.Automation.TreeScope]::Descendants, $cond)
            foreach ($el in $all) {
                try {
                    if (-not $el.Current.IsOffscreen) {
                        return $el
                    }
                }
                catch { }
            }
            if ($all.Count -gt 0) {
                return $all[0]
            }
        }
        catch { }
        Start-Sleep -Milliseconds 200
    }
    return $null
}

function Dump-UiTreeText {
    param([System.Windows.Automation.AutomationElement]$Root)
    $sb = New-Object System.Text.StringBuilder
    $all = $Root.FindAll([System.Windows.Automation.TreeScope]::Descendants,
        [System.Windows.Automation.Condition]::TrueCondition)
    foreach ($el in $all) {
        try {
            $c = $el.Current
            if ($c.IsOffscreen) { continue }
            $id = $c.AutomationId
            $nm = $c.Name
            $ct = $c.ControlType.ProgrammaticName
            if (-not [string]::IsNullOrWhiteSpace($id) -or -not [string]::IsNullOrWhiteSpace($nm)) {
                [void]$sb.AppendLine("$ct | Id='$id' | Name='$nm'")
            }
        }
        catch { }
    }
    return $sb.ToString()
}

function Save-FailureArtifacts {
    param([System.Windows.Automation.AutomationElement]$Root, [string]$OutDir, [string]$Tag)
    if ([string]::IsNullOrWhiteSpace($OutDir)) { return }
    try {
        New-Item -ItemType Directory -Path $OutDir -Force | Out-Null
        if ($null -ne $Root) {
            $tree = Dump-UiTreeText -Root $Root
            Set-Content -LiteralPath (Join-Path $OutDir "winui-tree-$Tag.txt") -Value $tree -Encoding utf8
            $rect = $Root.Current.BoundingRectangle
            if (-not $rect.IsEmpty -and $rect.Width -gt 10 -and $rect.Height -gt 10) {
                $bmp = New-Object System.Drawing.Bitmap([int]$rect.Width, [int]$rect.Height)
                $g = [System.Drawing.Graphics]::FromImage($bmp)
                $g.CopyFromScreen([int]$rect.X, [int]$rect.Y, 0, 0, $bmp.Size)
                $bmp.Save((Join-Path $OutDir "winui-screen-$Tag.png"), [System.Drawing.Imaging.ImageFormat]::Png)
                $g.Dispose()
                $bmp.Dispose()
            }
        }
    }
    catch { }
}

function Invoke-ElementClick {
    param([System.Windows.Automation.AutomationElement]$Root, [System.Windows.Automation.AutomationElement]$El)
    $pat = $null
    if ($El.TryGetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern, [ref]$pat)) {
        ([System.Windows.Automation.InvokePattern]$pat).Invoke()
        return "InvokePattern"
    }
    if ($El.Current.ControlType -eq [System.Windows.Automation.ControlType]::RadioButton) {
        $elName = $El.Current.Name
        try {
            $El.SetFocus()
        }
        catch {
            throw "SetFocus() failed for RadioButton '$elName': $($_.Exception.Message)"
        }
        $focused = $false
        $focusDeadline = [DateTime]::UtcNow.AddMilliseconds(300)
        while ([DateTime]::UtcNow -lt $focusDeadline) {
            try {
                if ($El.Current.HasKeyboardFocus) {
                    $focused = $true
                    break
                }
            }
            catch { }
            Start-Sleep -Milliseconds 30
        }
        if (-not $focused) {
            throw "RadioButton '$elName' did not acquire keyboard focus within 300ms after SetFocus()."
        }
        $hwnd = [IntPtr]$El.Current.NativeWindowHandle
        if ($hwnd -eq [IntPtr]::Zero) {
            $hwnd = [IntPtr]$Root.Current.NativeWindowHandle
        }
        if ($hwnd -eq [IntPtr]::Zero) {
            throw "Cannot activate RadioButton '$elName': both element and root NativeWindowHandle are zero."
        }
        $vkSpace = [IntPtr][GlideE2EWin32]::VK_SPACE
        $downOk = [GlideE2EWin32]::PostMessage($hwnd, [GlideE2EWin32]::WM_KEYDOWN, $vkSpace, [IntPtr]::Zero)
        if (-not $downOk) {
            $err = [System.Runtime.InteropServices.Marshal]::GetLastWin32Error()
            throw "PostMessage(WM_KEYDOWN, VK_SPACE) failed for RadioButton '$elName' (hwnd=$hwnd, Win32Error=$err)."
        }
        Start-Sleep -Milliseconds 20
        $upOk = [GlideE2EWin32]::PostMessage($hwnd, [GlideE2EWin32]::WM_KEYUP, $vkSpace, [IntPtr]::Zero)
        if (-not $upOk) {
            $err = [System.Runtime.InteropServices.Marshal]::GetLastWin32Error()
            throw "PostMessage(WM_KEYUP, VK_SPACE) failed for RadioButton '$elName' (hwnd=$hwnd, Win32Error=$err)."
        }
        Start-Sleep -Milliseconds 50
        return "PostMessageSpace(RadioButton)"
    }
    if ($El.TryGetCurrentPattern([System.Windows.Automation.SelectionItemPattern]::Pattern, [ref]$pat)) {
        ([System.Windows.Automation.SelectionItemPattern]$pat).Select()
        return "SelectionItemPattern"
    }
    try { $El.SetFocus() } catch { }
    $hwnd = [IntPtr]$Root.Current.NativeWindowHandle
    $fgOk = $true
    if ($hwnd -ne [IntPtr]::Zero) {
        $fgOk = [GlideE2EWin32]::SetForegroundWindow($hwnd)
        Start-Sleep -Milliseconds 100
    }
    $rect = $El.Current.BoundingRectangle
    if ($rect.IsEmpty) {
        throw "Element has empty BoundingRectangle and no Invoke/SelectionItem pattern."
    }
    $cx = [int]($rect.Left + ($rect.Width / 2.0))
    $cy = [int]($rect.Top + ($rect.Height / 2.0))
    if (-not [GlideE2EWin32]::ClickAt($cx, $cy)) {
        $err = [System.Runtime.InteropServices.Marshal]::GetLastWin32Error()
        throw "Win32Click($cx,$cy) failed: SetCursorPos returned false (SetForegroundWindow=$fgOk, Win32Error=$err)."
    }
    return "Win32Click($cx,$cy)"
}

function Invoke-StaClipboardRead {
    $raw = & pwsh -NoProfile -Sta -Command 'Get-Clipboard -Raw' 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "Clipboard read failed: $($raw | Out-String)"
    }
    return ($raw | Out-String).Trim()
}

$appDataGlide = Join-Path $env:LOCALAPPDATA "Glide"

switch ($Action) {
    "PreflightCheck" {
        $busy = Test-GlideUserInstanceRunning
        [pscustomobject]@{
            targetLabel = "WINDOWS_UI"
            mutexHeld   = $busy
            safeToStart = (-not $busy)
        } | ConvertTo-Json -Depth 3
        if ($busy) { exit 1 }
        exit 0
    }

    "StartSession" {
        if ([string]::IsNullOrWhiteSpace($ExePath) -or -not (Test-Path -LiteralPath $ExePath)) {
            throw "StartSession requires an existing -ExePath."
        }
        if ([string]::IsNullOrWhiteSpace($SessionFile) -or [string]::IsNullOrWhiteSpace($BackupDir)) {
            throw "StartSession requires -SessionFile and -BackupDir."
        }
        if (Test-GlideUserInstanceRunning) {
            throw "Refusing to start WindowsUi test: a user-owned Glide.exe instance (Local\Glide.Windows.UI) is already running."
        }

        New-Item -ItemType Directory -Path $BackupDir -Force | Out-Null
        if (Test-Path -LiteralPath $appDataGlide) {
            Copy-DirectoryContent -Source $appDataGlide -Destination $BackupDir
            Set-Content -LiteralPath (Join-Path $BackupDir ".glide-e2e-existed") -Value "1" -Encoding utf8
        }

        try {
            if ($CleanAppData -and (Test-Path -LiteralPath $appDataGlide)) {
                Remove-Item -LiteralPath $appDataGlide -Recurse -Force
            }
            if ($SeedState) {
                New-Item -ItemType Directory -Path $appDataGlide -Force | Out-Null
                $recvDir = if (-not [string]::IsNullOrWhiteSpace($ReceiveDirectory)) { $ReceiveDirectory } else { Join-Path $appDataGlide "e2e-recv" }
                New-Item -ItemType Directory -Path $recvDir -Force | Out-Null
                $portToUse = if ($ListenerPort -gt 0) { $ListenerPort } else { 42424 }
                $stateObj = [ordered]@{
                    Settings = [ordered]@{
                        ReceiveDirectory = $recvDir
                        ListenerPort     = $portToUse
                    }
                    History       = @()
                    SchemaVersion = 1
                }
                ($stateObj | ConvertTo-Json -Depth 4) | Set-Content -LiteralPath (Join-Path $appDataGlide "desktop-state.json") -Encoding utf8
            }

            $psi = New-Object System.Diagnostics.ProcessStartInfo($ExePath)
            $psi.UseShellExecute = $false
            $psi.WorkingDirectory = Split-Path $ExePath -Parent
            if (-not [string]::IsNullOrWhiteSpace($DiagnosticLogPath)) {
                $psi.Environment["GLIDE_DIAGNOSTIC_LOG_PATH"] = $DiagnosticLogPath
            }
            if ($ActivationArgs.Count -gt 0) {
                foreach ($a in $ActivationArgs) { $psi.ArgumentList.Add($a) }
            }

            $proc = [System.Diagnostics.Process]::Start($psi)
            if ($null -eq $proc) { throw "Failed to launch $ExePath." }

            $win = Get-MainWindowElement -ProcessId $proc.Id -TimeoutSec $TimeoutSeconds
            if ($null -eq $win) {
                try { $proc.Kill($true) } catch { }
                throw "Glide.exe (PID $($proc.Id)) did not expose UIA MainWindow within ${TimeoutSeconds}s."
            }

            $sessionObj = [ordered]@{
                pid        = $proc.Id
                exePath    = $ExePath
                backupDir  = $BackupDir
                appDataDir = $appDataGlide
                startedUtc = [DateTimeOffset]::UtcNow.ToString("O")
            }
            New-Item -ItemType Directory -Path (Split-Path $SessionFile -Parent) -Force | Out-Null
            ($sessionObj | ConvertTo-Json -Depth 4) | Set-Content -LiteralPath $SessionFile -Encoding utf8

            [pscustomobject]@{
                targetLabel = "WINDOWS_UI"
                action      = "StartSession"
                pid         = $proc.Id
                windowTitle = $win.Current.Name
                backupDir   = $BackupDir
            } | ConvertTo-Json -Depth 3
            exit 0
        }
        catch {
            Restore-AppData -AppDataDir $appDataGlide -SnapshotDir $BackupDir
            throw
        }
    }

    "UiAction" {
        if ([string]::IsNullOrWhiteSpace($SessionFile) -or -not (Test-Path -LiteralPath $SessionFile)) {
            throw "UiAction requires existing -SessionFile."
        }
        $sess = Get-Content -LiteralPath $SessionFile -Raw | ConvertFrom-Json
        $targetPid = [int]$sess.pid
        $win = Get-MainWindowElement -ProcessId $targetPid -TimeoutSec $TimeoutSeconds
        if ($null -eq $win) {
            throw "UIA MainWindow for PID $targetPid is unavailable."
        }

        try {
            switch ($UiCommand) {
                "DumpTree" {
                    $tree = Dump-UiTreeText -Root $win
                    [pscustomobject]@{
                        targetLabel = "WINDOWS_UI"
                        action      = "DumpTree"
                        tree        = $tree
                    } | ConvertTo-Json -Depth 3
                    exit 0
                }
                "SwitchTab" {
                    $tabMap = @{
                        "NavSend"        = "SendPage"
                        "NavReceive"     = "ReceivePage"
                        "NavActivity"    = "ActivityPage"
                        "NavPairDevices" = "PairDevicesPage"
                        "NavSettings"    = "SettingsPage"
                    }
                    $btn = Find-UiElement -Root $win -AutoId $AutomationId -ElemName $Name -TimeoutSec $TimeoutSeconds
                    if ($null -eq $btn) { throw "Tab button not found (AutomationId='$AutomationId', Name='$Name')." }
                    $method = Invoke-ElementClick -Root $win -El $btn
                    Start-Sleep -Milliseconds 250
                    $pageId = if (-not [string]::IsNullOrWhiteSpace($AutomationId) -and $tabMap.ContainsKey($AutomationId)) { $tabMap[$AutomationId] } else { "" }
                    if (-not [string]::IsNullOrWhiteSpace($pageId)) {
                        $page = Find-UiElement -Root $win -AutoId $pageId -ElemName "" -TimeoutSec 3
                        if ($null -eq $page -or $page.Current.IsOffscreen) {
                            # Win32 click fallback when SelectionItem.Select does not invoke RelayCommand
                            $hwnd = [IntPtr]$win.Current.NativeWindowHandle
                            $fgOk = $true
                            if ($hwnd -ne [IntPtr]::Zero) { $fgOk = [GlideE2EWin32]::SetForegroundWindow($hwnd); Start-Sleep -Milliseconds 100 }
                            $rect = $btn.Current.BoundingRectangle
                            $cx = [int]($rect.Left + $rect.Width / 2.0)
                            $cy = [int]($rect.Top + $rect.Height / 2.0)
                            if (-not [GlideE2EWin32]::ClickAt($cx, $cy)) {
                                $err = [System.Runtime.InteropServices.Marshal]::GetLastWin32Error()
                                throw "Win32ClickFallback($cx,$cy) failed for '$AutomationId': SetCursorPos returned false (SetForegroundWindow=$fgOk, Win32Error=$err)."
                            }
                            Start-Sleep -Milliseconds 250
                            $page = Find-UiElement -Root $win -AutoId $pageId -ElemName "" -TimeoutSec 5
                            if ($null -eq $page -or $page.Current.IsOffscreen) {
                                throw "Target page '$pageId' did not become visible after clicking '$AutomationId'."
                            }
                            $method = "Win32ClickFallback"
                        }
                    }
                    [pscustomobject]@{ targetLabel = "WINDOWS_UI"; action = "SwitchTab"; method = $method; pageId = $pageId } | ConvertTo-Json -Depth 3
                    exit 0
                }
                "Click" {
                    $el = Find-UiElement -Root $win -AutoId $AutomationId -ElemName $Name -TimeoutSec $TimeoutSeconds
                    if ($null -eq $el) { throw "Element not found to click (AutomationId='$AutomationId', Name='$Name')." }
                    $method = Invoke-ElementClick -Root $win -El $el
                    [pscustomobject]@{ targetLabel = "WINDOWS_UI"; action = "Click"; method = $method } | ConvertTo-Json -Depth 3
                    exit 0
                }
                "WaitFor" {
                    $el = Find-UiElement -Root $win -AutoId $AutomationId -ElemName $Name -TimeoutSec $TimeoutSeconds
                    if ($null -eq $el) { throw "Element did not appear within ${TimeoutSeconds}s (AutomationId='$AutomationId', Name='$Name')." }
                    [pscustomobject]@{ targetLabel = "WINDOWS_UI"; action = "WaitFor"; found = $true; name = $el.Current.Name } | ConvertTo-Json -Depth 3
                    exit 0
                }
                "AssertVisible" {
                    $el = Find-UiElement -Root $win -AutoId $AutomationId -ElemName $Name -TimeoutSec $TimeoutSeconds
                    if ($null -eq $el -or $el.Current.IsOffscreen) {
                        throw "Expected visible element (AutomationId='$AutomationId', Name='$Name')."
                    }
                    [pscustomobject]@{ targetLabel = "WINDOWS_UI"; action = "AssertVisible"; visible = $true; name = $el.Current.Name } | ConvertTo-Json -Depth 3
                    exit 0
                }
                "AssertAbsent" {
                    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
                    $stillPresent = $true
                    while ([DateTime]::UtcNow -lt $deadline) {
                        $el = Find-UiElement -Root $win -AutoId $AutomationId -ElemName $Name -TimeoutSec 1
                        if ($null -eq $el -or $el.Current.IsOffscreen) {
                            $stillPresent = $false
                            break
                        }
                        Start-Sleep -Milliseconds 250
                    }
                    if ($stillPresent) {
                        throw "Expected element to be absent/offscreen (AutomationId='$AutomationId', Name='$Name')."
                    }
                    [pscustomobject]@{ targetLabel = "WINDOWS_UI"; action = "AssertAbsent"; absent = $true } | ConvertTo-Json -Depth 3
                    exit 0
                }
                "SetText" {
                    $el = Find-UiElement -Root $win -AutoId $AutomationId -ElemName $Name -TimeoutSec $TimeoutSeconds
                    if ($null -eq $el) { throw "TextBox not found (AutomationId='$AutomationId', Name='$Name')." }
                    $pat = $null
                    if (-not $el.TryGetCurrentPattern([System.Windows.Automation.ValuePattern]::Pattern, [ref]$pat)) {
                        throw "Element lacks ValuePattern (AutomationId='$AutomationId', Name='$Name')."
                    }
                    ([System.Windows.Automation.ValuePattern]$pat).SetValue($Value)
                    [pscustomobject]@{ targetLabel = "WINDOWS_UI"; action = "SetText"; valueLength = $Value.Length } | ConvertTo-Json -Depth 3
                    exit 0
                }
                "SelectCombo" {
                    $combo = Find-UiElement -Root $win -AutoId $AutomationId -ElemName $Name -TimeoutSec $TimeoutSeconds
                    if ($null -eq $combo) { throw "ComboBox not found (AutomationId='$AutomationId', Name='$Name')." }
                    $exp = $null
                    if ($combo.TryGetCurrentPattern([System.Windows.Automation.ExpandCollapsePattern]::Pattern, [ref]$exp)) {
                        ([System.Windows.Automation.ExpandCollapsePattern]$exp).Expand()
                        Start-Sleep -Milliseconds 200
                    }
                    $itemCond = New-Object System.Windows.Automation.PropertyCondition(
                        [System.Windows.Automation.AutomationElement]::NameProperty, $Value)
                    $item = $combo.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $itemCond)
                    if ($null -eq $item) {
                        $item = [System.Windows.Automation.AutomationElement]::RootElement.FindFirst(
                            [System.Windows.Automation.TreeScope]::Descendants, $itemCond)
                    }
                    if ($null -eq $item) { throw "ComboBox item '$Value' not found." }
                    $sel = $null
                    if (-not $item.TryGetCurrentPattern([System.Windows.Automation.SelectionItemPattern]::Pattern, [ref]$sel)) {
                        throw "ComboBox item '$Value' lacks SelectionItemPattern."
                    }
                    ([System.Windows.Automation.SelectionItemPattern]$sel).Select()
                    Start-Sleep -Milliseconds 150
                    if ($null -ne $exp) {
                        try { ([System.Windows.Automation.ExpandCollapsePattern]$exp).Collapse() } catch { }
                    }
                    [pscustomobject]@{ targetLabel = "WINDOWS_UI"; action = "SelectCombo"; selected = $Value } | ConvertTo-Json -Depth 3
                    exit 0
                }
                "GetText" {
                    $el = Find-UiElement -Root $win -AutoId $AutomationId -ElemName $Name -TimeoutSec $TimeoutSeconds
                    if ($null -eq $el) { throw "Element not found for GetText (AutomationId='$AutomationId', Name='$Name')." }
                    [pscustomobject]@{ targetLabel = "WINDOWS_UI"; action = "GetText"; text = $el.Current.Name } | ConvertTo-Json -Depth 3
                    exit 0
                }
                "GetValue" {
                    $el = Find-UiElement -Root $win -AutoId $AutomationId -ElemName $Name -TimeoutSec $TimeoutSeconds
                    if ($null -eq $el) { throw "Element not found for GetValue (AutomationId='$AutomationId', Name='$Name')." }
                    $pat = $null
                    $valText = $el.Current.Name
                    if ($el.TryGetCurrentPattern([System.Windows.Automation.ValuePattern]::Pattern, [ref]$pat)) {
                        $valText = ([System.Windows.Automation.ValuePattern]$pat).Current.Value
                    }
                    [pscustomobject]@{ targetLabel = "WINDOWS_UI"; action = "GetValue"; value = $valText } | ConvertTo-Json -Depth 3
                    exit 0
                }
                "GetComboSelection" {
                    $combo = Find-UiElement -Root $win -AutoId $AutomationId -ElemName $Name -TimeoutSec $TimeoutSeconds
                    if ($null -eq $combo) { throw "ComboBox not found for GetComboSelection (AutomationId='$AutomationId', Name='$Name')." }
                    $selPat = $null
                    if (-not $combo.TryGetCurrentPattern([System.Windows.Automation.SelectionPattern]::Pattern, [ref]$selPat)) {
                        throw "Element lacks SelectionPattern for GetComboSelection (AutomationId='$AutomationId', Name='$Name')."
                    }
                    $selectedItems = @(([System.Windows.Automation.SelectionPattern]$selPat).Current.GetSelection())
                    if ($selectedItems.Count -eq 0) {
                        $expPat = $null
                        if ($combo.TryGetCurrentPattern([System.Windows.Automation.ExpandCollapsePattern]::Pattern, [ref]$expPat)) {
                            ([System.Windows.Automation.ExpandCollapsePattern]$expPat).Expand()
                            try {
                                Start-Sleep -Milliseconds 150
                                $selectedItems = @(([System.Windows.Automation.SelectionPattern]$selPat).Current.GetSelection())
                            }
                            finally {
                                try { ([System.Windows.Automation.ExpandCollapsePattern]$expPat).Collapse() } catch { }
                            }
                        }
                    }
                    $selectedText = if ($selectedItems.Count -gt 0) { [string]$selectedItems[0].Current.Name } else { "" }
                    [pscustomobject]@{ targetLabel = "WINDOWS_UI"; action = "GetComboSelection"; selected = $selectedText } | ConvertTo-Json -Depth 3
                    exit 0
                }
            }
        }
        catch {
            Save-FailureArtifacts -Root $win -OutDir $FailureLogDir -Tag ([DateTimeOffset]::UtcNow.ToString("HHmmssfff"))
            throw
        }
    }

    "ForwardActivation" {
        if ([string]::IsNullOrWhiteSpace($ExePath) -or -not (Test-Path -LiteralPath $ExePath)) {
            throw "ForwardActivation requires -ExePath."
        }
        $psi = New-Object System.Diagnostics.ProcessStartInfo($ExePath)
        $psi.UseShellExecute = $false
        $psi.WorkingDirectory = Split-Path $ExePath -Parent
        foreach ($a in $ActivationArgs) { $psi.ArgumentList.Add($a) }
        $secProc = [System.Diagnostics.Process]::Start($psi)
        if ($null -eq $secProc) { throw "Failed to start secondary Glide.exe." }
        $exited = $secProc.WaitForExit($TimeoutSeconds * 1000)
        if (-not $exited) {
            try { $secProc.Kill($true) } catch { }
            throw "Secondary Glide.exe (PID $($secProc.Id)) did not forward activation and exit within ${TimeoutSeconds}s."
        }
        [pscustomobject]@{
            targetLabel = "WINDOWS_UI"
            action      = "ForwardActivation"
            exitCode    = $secProc.ExitCode
            secondaryPid = $secProc.Id
        } | ConvertTo-Json -Depth 3
        exit 0
    }

    "ReadClipboard" {
        $clip = Invoke-StaClipboardRead
        [pscustomobject]@{
            targetLabel = "WINDOWS_UI"
            action      = "ReadClipboard"
            text        = $clip
        } | ConvertTo-Json -Depth 3
        exit 0
    }

    "StopSession" {
        if ([string]::IsNullOrWhiteSpace($SessionFile) -or -not (Test-Path -LiteralPath $SessionFile)) {
            if (-not [string]::IsNullOrWhiteSpace($BackupDir)) {
                Restore-AppData -AppDataDir $appDataGlide -SnapshotDir $BackupDir
            }
            exit 0
        }
        $sess = Get-Content -LiteralPath $SessionFile -Raw | ConvertFrom-Json
        $targetPid = [int]$sess.pid
        $proc = Get-Process -Id $targetPid -ErrorAction SilentlyContinue
        if ($null -ne $proc) {
            try {
                if ($proc.CloseMainWindow()) {
                    $null = $proc.WaitForExit(3000)
                }
            }
            catch { }
            if (-not $proc.HasExited) {
                try { $proc.Kill($true) } catch { }
                $null = $proc.WaitForExit(4000)
            }
        }

        # Wait briefly for Local\Glide.Windows.UI mutex release
        $deadline = [DateTime]::UtcNow.AddSeconds(5)
        while ([DateTime]::UtcNow -lt $deadline) {
            $m = $null
            $held = [System.Threading.Mutex]::TryOpenExisting("Local\Glide.Windows.UI", [ref]$m)
            if ($null -ne $m) { $m.Dispose() }
            if (-not $held) { break }
            Start-Sleep -Milliseconds 150
        }

        Restore-AppData -AppDataDir ([string]$sess.appDataDir) -SnapshotDir ([string]$sess.backupDir)
        Remove-Item -LiteralPath $SessionFile -Force -ErrorAction SilentlyContinue

        [pscustomobject]@{
            targetLabel = "WINDOWS_UI"
            action      = "StopSession"
            pid         = $targetPid
            restored    = $true
        } | ConvertTo-Json -Depth 3
        exit 0
    }
}
