function Enable-GlideDotnetSdk {
    [CmdletBinding()]
    param([switch]$AllowMissing)

    $candidatePaths = [System.Collections.Generic.List[string]]::new()
    $onPath = Get-Command dotnet -ErrorAction SilentlyContinue
    if ($onPath) { $candidatePaths.Add($onPath.Source) }

    $userDotnet = Join-Path ([Environment]::GetFolderPath('UserProfile')) '.dotnet'
    $userExecutable = Join-Path $userDotnet $(if ($IsWindows) { 'dotnet.exe' } else { 'dotnet' })
    if (Test-Path -LiteralPath $userExecutable) { $candidatePaths.Add($userExecutable) }

    foreach ($candidatePath in ($candidatePaths | Select-Object -Unique)) {
        $installed = try { & $candidatePath --list-sdks 2>$null | Out-String } catch { '' }
        if ($installed -notmatch '(?m)^10\.\d+\.\d+\s') { continue }

        $sdkDirectory = Split-Path $candidatePath -Parent
        $env:DOTNET_ROOT = $sdkDirectory
        $separator = [IO.Path]::PathSeparator
        $env:PATH = "$sdkDirectory$separator$env:PATH"
        return $candidatePath
    }

    if ($AllowMissing) { return $null }
    throw '.NET 10 SDK is required. Install it or place it in the per-user .dotnet directory.'
}
