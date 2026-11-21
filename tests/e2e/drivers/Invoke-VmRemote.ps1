[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet("Probe", "Deploy", "DeploySuite", "Exec", "HashFile", "Cleanup", "InstallDeb", "UninstallDeb", "RunSystemdSmoke")]
    [string]$Action,

    [string]$VmHost = $env:GLIDE_E2E_VM_HOST,
    [string]$VmUser = $env:GLIDE_E2E_VM_USER,
    [int]$SshPort = 22,
    [string]$SshKeyPath = $env:GLIDE_E2E_VM_KEY,
    [string]$SandboxId = "default",
    [string]$LocalPath = "",
    [string]$RemotePath = "",
    [string]$Command = "",
    [int]$TimeoutSeconds = 30,
    [switch]$UseSystemPath
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

if ([string]::IsNullOrWhiteSpace($VmHost)) { $VmHost = "192.168.0.245" }
if ([string]::IsNullOrWhiteSpace($VmUser)) { $VmUser = "Ubuntu 26" }

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

function Get-SshBaseArgs {
    $sshArgs = @(
        "-o", "BatchMode=yes",
        "-o", "ConnectTimeout=5",
        "-o", "StrictHostKeyChecking=accept-new"
    )
    if (-not [string]::IsNullOrWhiteSpace($SshKeyPath)) {
        if (-not (Test-Path -LiteralPath $SshKeyPath)) {
            throw "SSH key path not found: $SshKeyPath"
        }
        $sshArgs += @("-i", $SshKeyPath)
    }
    return $sshArgs
}

function Test-TcpPort {
    param([string]$TargetHost, [int]$Port, [int]$TimeoutMs = 2000)
    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        $iar = $client.BeginConnect($TargetHost, $Port, $null, $null)
        if (-not $iar.AsyncWaitHandle.WaitOne($TimeoutMs, $false)) {
            return $false
        }
        $client.EndConnect($iar)
        return $true
    }
    catch {
        return $false
    }
    finally {
        $client.Dispose()
    }
}

$targetSpec = "$VmUser@$VmHost"
$sandboxRoot = "/tmp/glide-e2e/$SandboxId"

function Invoke-VmBashBase64 {
    param([string]$ScriptBody, [int]$TimeoutSec)
    $normalized = ($ScriptBody -replace "`r`n", "`n")
    $b64 = [Convert]::ToBase64String([System.Text.Encoding]::UTF8.GetBytes($normalized))
    $remoteCmd = "bash -c 'timeout $TimeoutSec bash <(printf %s $b64 | base64 -d)'"
    $sshArgs = (Get-SshBaseArgs) + @("-p", "$SshPort", $targetSpec, $remoteCmd)
    $prevPref = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $rawOut = & ssh @sshArgs 2>&1
    $code = $LASTEXITCODE
    $ErrorActionPreference = $prevPref
    return [pscustomobject]@{
        ExitCode = $code
        Output   = (Redact-Secrets ($rawOut | Out-String))
    }
}

switch ($Action) {
    "Probe" {
        $tcpOpen = Test-TcpPort -TargetHost $VmHost -Port $SshPort -TimeoutMs 2500
        $sshAuth = $false
        $reason = ""
        if (-not $tcpOpen) {
            $reason = "TCP port $SshPort on $VmHost is closed or unreachable."
        }
        else {
            $sshExe = Get-Command ssh -ErrorAction SilentlyContinue
            if ($null -eq $sshExe) {
                $reason = "OpenSSH client 'ssh' not found in PATH."
            }
            else {
                $sshArgs = (Get-SshBaseArgs) + @("-p", "$SshPort", $targetSpec, "echo GLIDE_VM_OK")
                $prevPref = $ErrorActionPreference
                $ErrorActionPreference = "Continue"
                $out = & $sshExe.Source @sshArgs 2>&1
                $code = $LASTEXITCODE
                $ErrorActionPreference = $prevPref
                $joined = ($out | Out-String).Trim()
                if ($code -eq 0 -and $joined -match "GLIDE_VM_OK") {
                    $sshAuth = $true
                    $reason = "VM reachable and SSH batch authentication succeeded."
                }
                else {
                    $reason = "SSH authentication/command failed (exit $code): $(Redact-Secrets $joined)"
                }
            }
        }

        [pscustomobject]@{
            targetLabel      = "VM"
            reachable        = ($tcpOpen -and $sshAuth)
            tcpOpen          = $tcpOpen
            sshAuthenticated = $sshAuth
            host             = $VmHost
            port             = $SshPort
            user             = $VmUser
            reason           = $reason
        } | ConvertTo-Json -Depth 4
        exit 0
    }

    "Deploy" {
        if ([string]::IsNullOrWhiteSpace($LocalPath) -or -not (Test-Path -LiteralPath $LocalPath)) {
            throw "Deploy requires an existing -LocalPath."
        }
        if ([string]::IsNullOrWhiteSpace($RemotePath)) {
            $RemotePath = "$sandboxRoot/$(Split-Path $LocalPath -Leaf)"
        }
        $remoteDir = [System.IO.Path]::GetDirectoryName($RemotePath.Replace('/', '\')).Replace('\', '/')
        $mkRes = Invoke-VmBashBase64 -ScriptBody "set -euo pipefail`nmkdir -p '$remoteDir'" -TimeoutSec $TimeoutSeconds
        if ($mkRes.ExitCode -ne 0) { throw "Failed to create remote directory '$remoteDir': $($mkRes.Output)" }

        $scpArgs = (Get-SshBaseArgs) + @("-P", "$SshPort", "-r", $LocalPath, "${targetSpec}:$RemotePath")
        & scp @scpArgs | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "SCP deploy failed for '$LocalPath' -> '$RemotePath'." }

        [pscustomobject]@{
            targetLabel = "VM"
            action      = "Deploy"
            localPath   = $LocalPath
            remotePath  = $RemotePath
            success     = $true
        } | ConvertTo-Json -Depth 3
        exit 0
    }

    "DeploySuite" {
        $repoRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent | Split-Path -Parent
        $linuxBinRoot = if (-not [string]::IsNullOrWhiteSpace($LocalPath)) { $LocalPath } else { Join-Path $repoRoot "build\e2e-linux-bin" }
        $appDir = Join-Path $linuxBinRoot "app"
        $drvDir = Join-Path $linuxBinRoot "driver"
        $uiDir = Join-Path $linuxBinRoot "ui"
        $serviceFile = Join-Path $repoRoot "desktop\linux\glide.service"

        foreach ($req in @($appDir, $drvDir, $uiDir, $serviceFile)) {
            if (-not (Test-Path -LiteralPath $req)) {
                throw "DeploySuite missing artifact: $req"
            }
        }

        $suiteBase = "/tmp/glide-e2e/suite-bin"
        $prepRes = Invoke-VmBashBase64 -ScriptBody @"
set -euo pipefail
rm -rf '$suiteBase'
mkdir -p '$suiteBase/bin' '$suiteBase/app' '$suiteBase/driver' '$suiteBase/ui'
"@ -TimeoutSec $TimeoutSeconds
        if ($prepRes.ExitCode -ne 0) { throw "DeploySuite prep failed: $($prepRes.Output)" }

        foreach ($pair in @(
            @{ src = "$appDir/."; dst = "$suiteBase/app" },
            @{ src = "$drvDir/."; dst = "$suiteBase/driver" },
            @{ src = "$uiDir/."; dst = "$suiteBase/ui" },
            @{ src = $serviceFile; dst = "$suiteBase/glide.service" }
        )) {
            $scpArgs = (Get-SshBaseArgs) + @("-P", "$SshPort", "-r", $pair.src, "${targetSpec}:$($pair.dst)")
            & scp @scpArgs | Out-Null
            if ($LASTEXITCODE -ne 0) { throw "DeploySuite SCP failed for $($pair.src)" }
        }

        $linkRes = Invoke-VmBashBase64 -ScriptBody @"
set -euo pipefail
chmod +x '$suiteBase/app/Glide.App' '$suiteBase/driver/Glide.BackendTestDriver' '$suiteBase/ui/Glide.Linux.UI'
ln -sfn '../app/Glide.App' '$suiteBase/bin/glide'
ln -sfn '../driver/Glide.BackendTestDriver' '$suiteBase/bin/Glide.BackendTestDriver'
ln -sfn '../ui/Glide.Linux.UI' '$suiteBase/bin/glide-desktop'
'$suiteBase/bin/glide' --help >/dev/null
'$suiteBase/bin/Glide.BackendTestDriver' --help >/dev/null
"@ -TimeoutSec $TimeoutSeconds
        if ($linkRes.ExitCode -ne 0) { throw "DeploySuite verification failed: $($linkRes.Output)" }

        [pscustomobject]@{
            targetLabel = "VM"
            action      = "DeploySuite"
            suiteBase   = $suiteBase
            success     = $true
        } | ConvertTo-Json -Depth 3
        exit 0
    }

    "Exec" {
        if ([string]::IsNullOrWhiteSpace($Command)) {
            throw "Exec requires -Command."
        }
        $pathLine = if ($UseSystemPath) {
            'export PATH="/usr/local/bin:/usr/bin:/bin:$PATH"'
        }
        else {
            'export PATH="/tmp/glide-e2e/suite-bin/bin:/usr/local/bin:/usr/bin:/bin:$PATH"'
        }

        $fullScript = @(
            "set -euo pipefail"
            "export HOME='$sandboxRoot/home'"
            "export XDG_CONFIG_HOME='$sandboxRoot/config'"
            "export XDG_DATA_HOME='$sandboxRoot/data'"
            "export XDG_STATE_HOME='$sandboxRoot/state'"
            "export XDG_CACHE_HOME='$sandboxRoot/cache'"
            "export XDG_RUNTIME_DIR='$sandboxRoot/runtime'"
            $pathLine
            "mkdir -p '$sandboxRoot/home' '$sandboxRoot/config' '$sandboxRoot/data' '$sandboxRoot/state' '$sandboxRoot/cache' '$sandboxRoot/runtime'"
            "chmod 700 '$sandboxRoot/runtime'"
            $Command
        ) -join "`n"

        $res = Invoke-VmBashBase64 -ScriptBody $fullScript -TimeoutSec $TimeoutSeconds

        [pscustomobject]@{
            targetLabel = "VM"
            action      = "Exec"
            exitCode    = $res.ExitCode
            output      = $res.Output.TrimEnd()
        } | ConvertTo-Json -Depth 3
        exit $res.ExitCode
    }

    "HashFile" {
        if ([string]::IsNullOrWhiteSpace($RemotePath)) {
            throw "HashFile requires -RemotePath."
        }
        $res = Invoke-VmBashBase64 -ScriptBody @"
set -euo pipefail
stat -c '%s' '$RemotePath'
sha256sum '$RemotePath'
"@ -TimeoutSec $TimeoutSeconds
        if ($res.ExitCode -ne 0) { throw "Remote HashFile failed for '$RemotePath': $($res.Output)" }
        $lines = @($res.Output -split "`r?`n" | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
        $size = [long]$lines[0].Trim()
        $sha = ($lines[1].Trim() -split '\s+')[0].ToLowerInvariant()

        [pscustomobject]@{
            targetLabel = "VM"
            remotePath  = $RemotePath
            sizeBytes   = $size
            sha256      = $sha
        } | ConvertTo-Json -Depth 3
        exit 0
    }

    "InstallDeb" {
        if ([string]::IsNullOrWhiteSpace($RemotePath)) {
            throw "InstallDeb requires -RemotePath to .deb on VM."
        }
        $res = Invoke-VmBashBase64 -ScriptBody @"
set -euo pipefail
sudo -n dpkg -i '$RemotePath'
test -x /usr/bin/glide
test -x /usr/bin/glide-desktop
test -f /usr/lib/systemd/user/glide.service
test -f /usr/share/applications/glide-desktop.desktop
/usr/bin/glide --help >/dev/null
"@ -TimeoutSec $TimeoutSeconds
        if ($res.ExitCode -ne 0) { throw "InstallDeb failed for '$RemotePath': $($res.Output)" }
        [pscustomobject]@{
            targetLabel = "VM"
            action      = "InstallDeb"
            remotePath  = $RemotePath
            success     = $true
        } | ConvertTo-Json -Depth 3
        exit 0
    }

    "UninstallDeb" {
        $res = Invoke-VmBashBase64 -ScriptBody @"
set -euo pipefail
sudo -n dpkg -r glide
test ! -e /usr/bin/glide
test ! -e /usr/bin/glide-desktop
"@ -TimeoutSec $TimeoutSeconds
        if ($res.ExitCode -ne 0) { throw "UninstallDeb failed: $($res.Output)" }
        [pscustomobject]@{
            targetLabel = "VM"
            action      = "UninstallDeb"
            success     = $true
        } | ConvertTo-Json -Depth 3
        exit 0
    }

    "RunSystemdSmoke" {
        $scriptBody = @'
set -euo pipefail
UNIT_PATH=""
if [ -f /usr/lib/systemd/user/glide.service ]; then
    UNIT_PATH="/usr/lib/systemd/user/glide.service"
elif [ -f /tmp/glide-e2e/suite-bin/glide.service ]; then
    UNIT_PATH="/tmp/glide-e2e/suite-bin/glide.service"
else
    echo "glide.service unit file not found on VM." >&2
    exit 1
fi

grep -Fq 'ConditionPathExists=%h/.config/glide/daemon.env' "$UNIT_PATH"
grep -Fq 'EnvironmentFile=%h/.config/glide/daemon.env' "$UNIT_PATH"
grep -Fq 'UMask=0077' "$UNIT_PATH"
grep -Fq 'NoNewPrivileges=true' "$UNIT_PATH"
grep -Fq 'ExecStart=/usr/bin/glide daemon --accept-paired-offers --trusted-peer' "$UNIT_PATH"

VERIFY_UNIT="$UNIT_PATH"
TMP_UNIT=""
if [ ! -x /usr/bin/glide ] && [ -x /tmp/glide-e2e/suite-bin/bin/glide ]; then
    TMP_UNIT="/tmp/glide-e2e-verify-glide.service"
    sed 's|/usr/bin/glide|/tmp/glide-e2e/suite-bin/bin/glide|g' "$UNIT_PATH" > "$TMP_UNIT"
    VERIFY_UNIT="$TMP_UNIT"
fi
systemd-analyze verify "$VERIFY_UNIT"
if [ -n "$TMP_UNIT" ]; then rm -f "$TMP_UNIT"; fi

export XDG_RUNTIME_DIR="/run/user/$(id -u)"
if [ ! -d "$XDG_RUNTIME_DIR" ]; then
    echo "Systemd user runtime directory missing: $XDG_RUNTIME_DIR" >&2
    exit 1
fi

# Verify ConditionPathExists fails closed when daemon.env is absent
TEST_HOME="$(mktemp -d /tmp/glide-systemd-smoke.XXXXXX)"
UNIT_NAME="glide-e2e-smoke-$$.service"
mkdir -p "$HOME/.config/systemd/user"
sed -e "s|%h/.config/glide/daemon.env|$TEST_HOME/missing-daemon.env|g" \
    -e 's|/usr/bin/glide|/bin/true|g' "$UNIT_PATH" > "$HOME/.config/systemd/user/$UNIT_NAME"
trap 'rm -f "$HOME/.config/systemd/user/$UNIT_NAME"; systemctl --user daemon-reload || true; rm -rf -- "$TEST_HOME"' EXIT
systemctl --user daemon-reload
systemctl --user start "$UNIT_NAME"
COND_RES="$(systemctl --user show -p ConditionResult --value "$UNIT_NAME")"
if [ "$COND_RES" != "no" ]; then
    echo "Expected ConditionResult=no when daemon.env is absent, got '$COND_RES'." >&2
    exit 1
fi
echo "SYSTEMD_SMOKE_OK unit=$UNIT_PATH ConditionResult=$COND_RES"
'@
        $res = Invoke-VmBashBase64 -ScriptBody $scriptBody -TimeoutSec $TimeoutSeconds
        if ($res.ExitCode -ne 0) {
            throw "RunSystemdSmoke failed (exit $($res.ExitCode)): $($res.Output)"
        }
        [pscustomobject]@{
            targetLabel = "VM"
            action      = "RunSystemdSmoke"
            exitCode    = $res.ExitCode
            output      = $res.Output.Trim()
        } | ConvertTo-Json -Depth 3
        exit 0
    }

    "Cleanup" {
        $res = Invoke-VmBashBase64 -ScriptBody "rm -rf '$sandboxRoot'" -TimeoutSec $TimeoutSeconds
        [pscustomobject]@{
            targetLabel = "VM"
            action      = "Cleanup"
            sandboxRoot = $sandboxRoot
            success     = ($res.ExitCode -eq 0)
        } | ConvertTo-Json -Depth 3
        exit $res.ExitCode
    }
}
