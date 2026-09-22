@echo off
REM HARD-035: Glide Lint Script (batch wrapper)
REM Usage: lint.cmd [All|Android|Windows] [-Fix]
set TARGET=%1
if "%TARGET%"=="" set TARGET=All
set FIX=%2
if "%FIX%"=="" (
    pwsh -ExecutionPolicy Bypass -File "%~dp0lint.ps1" -Target %TARGET%
) else (
    pwsh -ExecutionPolicy Bypass -File "%~dp0lint.ps1" -Target %TARGET% -Fix
)
exit /b %ERRORLEVEL%
