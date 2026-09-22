@echo off
REM HARD-035: Glide Developer Environment Doctor (batch wrapper)
REM Runs the PowerShell doctor script for convenience on Windows.
pwsh -ExecutionPolicy Bypass -File "%~dp0doctor.ps1" %*
exit /b %ERRORLEVEL%
