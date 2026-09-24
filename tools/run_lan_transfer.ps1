param(
    [Parameter(Mandatory=$true)]
    [string]$SenderSerial,

    [Parameter(Mandatory=$true)]
    [string]$ReceiverSerial,

    [Parameter(Mandatory=$true)]
    [string]$PayloadName,

    [ValidateSet("accept", "reject", "cancel")]
    [string]$Action = "accept",

    [int]$TimeoutSeconds = 180
)

$ErrorActionPreference = "Stop"
$adb = "C:\Users\GDJ\AppData\Local\Android\Sdk\platform-tools\adb.exe"

function Get-NodeCenter($node) {
    if ($node -and $node.bounds -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
        $x = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
        $y = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
        return @{ X = $x; Y = $y }
    }
    return $null
}

function Tap-Element($serial, $pattern, [switch]$ByDesc, [int]$Retries = 10, [int]$DelayMs = 500) {
    for ($i = 0; $i -lt $Retries; $i++) {
        & $adb -s $serial shell "uiautomator dump /data/local/tmp/ui_tap.xml" | Out-Null
        & $adb -s $serial pull /data/local/tmp/ui_tap.xml build/hard-010/1ede5f2/ui_tap.xml | Out-Null
        [xml]$doc = Get-Content build/hard-010/1ede5f2/ui_tap.xml

        $node = $null
        if ($ByDesc) {
            $node = $doc.SelectSingleNode("//*[@content-desc='$pattern']")
            if (-not $node) {
                $node = $doc.SelectSingleNode("//*[contains(@content-desc, '$pattern')]")
            }
        } else {
            $node = $doc.SelectSingleNode("//*[@text='$pattern']")
            if (-not $node) {
                $node = $doc.SelectSingleNode("//*[@content-desc='$pattern']")
            }
            if (-not $node) {
                $node = $doc.SelectSingleNode("//*[contains(@text, '$pattern')]")
            }
            if (-not $node) {
                $node = $doc.SelectSingleNode("//*[contains(@content-desc, '$pattern')]")
            }
        }

        if ($node) {
            $c = Get-NodeCenter $node
            if ($c) {
                Write-Host "Found '$pattern' at ($($c.X), $($c.Y)) on $serial. Tapping..."
                & $adb -s $serial shell "input tap $($c.X) $($c.Y)"
                Start-Sleep -Milliseconds $DelayMs
                return $true
            }
        }
        Start-Sleep -Milliseconds 600
    }
    return $false
}

Write-Host "=== 1. Starting fresh Receiver ($ReceiverSerial) ==="
& $adb -s $ReceiverSerial shell "am force-stop dev.glide.app.debug && am start -n dev.glide.app.debug/dev.glide.app.MainActivity" | Out-Null
Start-Sleep -Milliseconds 1200

# Tap 'Receive files'
if (-not (Tap-Element $ReceiverSerial "Receive files")) {
    & $adb -s $ReceiverSerial shell "input tap 588 913"
}
Start-Sleep -Milliseconds 800

# Tap 'Show QR code'
if (-not (Tap-Element $ReceiverSerial "Show QR code")) {
    & $adb -s $ReceiverSerial shell "input tap 588 2027"
}
Start-Sleep -Milliseconds 1500

Write-Host "=== 2. Extracting LAN invitation URI from Receiver ==="
$pidRecv = (& $adb -s $ReceiverSerial shell "pidof dev.glide.app.debug").Trim()
Write-Host "Receiver PID: $pidRecv"

& $adb -s $ReceiverSerial shell "am dumpheap $pidRecv /data/local/tmp/heap_recv.hprof"
Start-Sleep -Milliseconds 1200
& $adb -s $ReceiverSerial pull /data/local/tmp/heap_recv.hprof build/hard-010/1ede5f2/heap_recv.hprof | Out-Null

$pyOut = python tools/extract_lan_invitation.py build/hard-010/1ede5f2/heap_recv.hprof
$uriLine = ($pyOut | Select-String "EXTRACTED_URI:").Line
if (-not $uriLine) {
    Write-Error "Failed to extract invitation URI: $pyOut"
}
$uri = $uriLine.Substring("EXTRACTED_URI:".Length).Trim()
Write-Host "Extracted URI length: $($uri.Length)"

Write-Host "=== 3. Starting Sender ($SenderSerial) with $PayloadName ==="
& $adb -s $SenderSerial shell "am force-stop dev.glide.app.debug" | Out-Null
Start-Sleep -Milliseconds 500
& $adb -s $SenderSerial shell "am start -a android.intent.action.SEND -t application/octet-stream --eu android.intent.extra.STREAM content://dev.glide.app.debug.fileprovider/shares/$PayloadName -n dev.glide.app.debug/dev.glide.app.MainActivity" | Out-Null
Start-Sleep -Milliseconds 1500

# Tap 'Proceed to send'
Write-Host "Tapping Proceed to send..."
if (-not (Tap-Element $SenderSerial "Proceed to send")) {
    Write-Error "Could not find 'Proceed to send' button on $SenderSerial"
}

# Tap 'Scan QR or paste invite'
Write-Host "Tapping Scan QR or paste invite..."
if (-not (Tap-Element $SenderSerial "Scan QR or paste invite")) {
    Write-Error "Could not find 'Scan QR or paste invite' button on $SenderSerial"
}

# Tap 'Paste invitation instead' or 'Paste invitation'
Write-Host "Tapping Paste invitation..."
if (-not (Tap-Element $SenderSerial "Paste invitation")) {
    & $adb -s $SenderSerial shell "input tap 541 1745"
}
Start-Sleep -Milliseconds 600

# Focus text field (Find EditText or tap dialog input)
Write-Host "Focusing text input..."
& $adb -s $SenderSerial shell "uiautomator dump /data/local/tmp/ui_edit.xml" | Out-Null
& $adb -s $SenderSerial pull /data/local/tmp/ui_edit.xml build/hard-010/1ede5f2/ui_edit.xml | Out-Null
[xml]$editDoc = Get-Content build/hard-010/1ede5f2/ui_edit.xml
$editNode = $editDoc.SelectSingleNode("//*[@class='android.widget.EditText']")
if ($editNode) {
    $c = Get-NodeCenter $editNode
    & $adb -s $SenderSerial shell "input tap $($c.X) $($c.Y)"
} else {
    & $adb -s $SenderSerial shell "input tap 365 1169"
}
Start-Sleep -Milliseconds 400

# Type URI in 70-char chunks
Write-Host "Typing URI into Sender..."
$chunkSize = 70
for ($i = 0; $i -lt $uri.Length; $i += $chunkSize) {
    $len = [Math]::Min($chunkSize, $uri.Length - $i)
    $chunk = $uri.Substring($i, $len)
    & $adb -s $SenderSerial shell "input text '$chunk'"
    Start-Sleep -Milliseconds 50
}

Write-Host "Tapping Verify & connect..."
Start-Sleep -Milliseconds 400
if (-not (Tap-Element $SenderSerial "Verify & connect")) {
    & $adb -s $SenderSerial shell "input tap 721 1279"
    Start-Sleep -Milliseconds 150
    & $adb -s $SenderSerial shell "input tap 721 1577"
}
Start-Sleep -Milliseconds 800

# Tap Connect on confirmation prompt if present
Write-Host "Tapping Connect confirmation if present..."
Tap-Element $SenderSerial "Connect" -Retries 3 -DelayMs 300 | Out-Null

Write-Host "=== 4. Handling Mutual Trust & Consent Dialogs ==="
# Check UI on both devices for Trust device / Accept / Decline
for ($attempt = 1; $attempt -le 15; $attempt++) {
    & $adb -s $SenderSerial shell "uiautomator dump /data/local/tmp/ui_s.xml" | Out-Null
    & $adb -s $SenderSerial pull /data/local/tmp/ui_s.xml build/hard-010/1ede5f2/ui_s.xml | Out-Null
    & $adb -s $ReceiverSerial shell "uiautomator dump /data/local/tmp/ui_r.xml" | Out-Null
    & $adb -s $ReceiverSerial pull /data/local/tmp/ui_r.xml build/hard-010/1ede5f2/ui_r.xml | Out-Null

    $sXml = Get-Content build/hard-010/1ede5f2/ui_s.xml -Raw
    $rXml = Get-Content build/hard-010/1ede5f2/ui_r.xml -Raw

    # If Connect confirmation is still shown on Sender
    if ($sXml -match 'Connect to') {
        Write-Host "Tapping Connect on Sender confirmation dialog..."
        Tap-Element $SenderSerial "Connect" | Out-Null
    }

    # If Trust device is shown on Sender
    if ($sXml -match 'Trust device') {
        Write-Host "Trusting device on Sender..."
        Tap-Element $SenderSerial "Trust device" | Out-Null
    }

    # If Trust device is shown on Receiver
    if ($rXml -match 'Trust device') {
        Write-Host "Trusting device on Receiver..."
        Tap-Element $ReceiverSerial "Trust device" | Out-Null
    }

    # If Accept file is shown on Receiver
    if ($rXml -match 'Accept file') {
        Write-Host "Offer detected on Receiver!"
        if ($Action -eq "reject") {
            Write-Host "Action is REJECT: Tapping Decline..."
            Tap-Element $ReceiverSerial "Decline" | Out-Null
            Start-Sleep -Seconds 2
            return "rejected"
        } else {
            Write-Host "Action is ${Action}: Tapping Accept file..."
            Tap-Element $ReceiverSerial "Accept file" | Out-Null
            break
        }
    }

    Start-Sleep -Milliseconds 1000
}

if ($Action -eq "cancel") {
    Write-Host "Action is CANCEL: Waiting 4 seconds for transfer to stream, then cancelling..."
    Start-Sleep -Seconds 4
    if (-not (Tap-Element $SenderSerial "Cancel transfer")) {
        Tap-Element $ReceiverSerial "Cancel transfer" | Out-Null
    }
    Write-Host "Cancelled transfer."
    Start-Sleep -Seconds 2
    return "cancelled"
}

Write-Host "=== 5. Monitoring Transfer Progress ==="
$stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
while ($stopwatch.Elapsed.TotalSeconds -lt $TimeoutSeconds) {
    Start-Sleep -Seconds 3
    & $adb -s $ReceiverSerial shell "uiautomator dump /data/local/tmp/ui_r.xml" | Out-Null
    & $adb -s $ReceiverSerial pull /data/local/tmp/ui_r.xml build/hard-010/1ede5f2/ui_r.xml | Out-Null
    $rXml = Get-Content build/hard-010/1ede5f2/ui_r.xml -Raw

    if ($rXml -match 'Transfer complete' -or $rXml -match 'Completed') {
        Write-Host "Transfer complete observed on Receiver!"
        return "complete"
    }

    if ($rXml -match 'Transfer failed' -or $rXml -match 'Interrupted') {
        Write-Host "Transfer failed/interrupted observed on Receiver!"
        return "failed"
    }

    # Wake screen if dimming
    & $adb -s $SenderSerial shell "input keyevent 224" | Out-Null
    & $adb -s $ReceiverSerial shell "input keyevent 224" | Out-Null

    Write-Host "Transfer in progress... ($([int]$stopwatch.Elapsed.TotalSeconds)s elapsed)"
}

Write-Host "Timeout reached waiting for transfer completion."
return "timeout"
