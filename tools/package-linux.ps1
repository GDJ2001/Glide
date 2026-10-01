<#
.SYNOPSIS
    Build Glide Linux GUI and daemon portable, Debian and RPM packages from a clean commit.
.DESCRIPTION
    Native packaging requires a Linux host with dotnet, dpkg-deb and rpmbuild. The
    package-linux-native.sh script records the source commit and artifact hashes.
#>
param(
    [ValidateSet('Release')][string]$Configuration = 'Release',
    [string[]]$Runtimes = @()
)

$ErrorActionPreference = 'Stop'
if (-not $IsLinux) {
    throw 'Linux .deb/.rpm packaging requires a Linux host. Windows cross-compilation is not a package qualification.'
}
$scriptPath = Join-Path (Split-Path -Parent $MyInvocation.MyCommand.Path) 'package-linux-native.sh'
& bash $scriptPath @Runtimes
if ($LASTEXITCODE -ne 0) { throw "Linux packaging failed with exit code $LASTEXITCODE" }
