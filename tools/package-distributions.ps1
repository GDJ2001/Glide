<#
.SYNOPSIS
    Legacy all-platform distribution entry point, retired during native recovery.
.DESCRIPTION
    The former script copied any matching files from build/distributions, including stale
    packages, then emitted a misleading release checksum catalog. Native packages now need
    clean, commit-keyed builds on their required Windows, Android, Linux, and macOS hosts.
    Release assembly remains open in recovery task R09.
#>
param([switch]$SkipBuild)

throw 'The legacy all-platform packager is retired. Run target-host native packaging jobs from one clean commit and verify their manifests before assembling a release.'
