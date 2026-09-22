@echo off
REM HARD-035: Glide Test Script (batch wrapper)
REM Usage: test.cmd [All|Android|Windows] [Debug|Release] [Filter]
set TARGET=%1
if "%TARGET%"=="" set TARGET=All
set CONFIG=%2
if "%CONFIG%"=="" set CONFIG=Debug
set FILTER=%3
if "%FILTER%"=="" (
    pwsh -ExecutionPolicy Bypass -File "%~dp0test.ps1" -Target %TARGET% -Configuration %CONFIG%
) else (
    pwsh -ExecutionPolicy Bypass -File "%~dp0test.ps1" -Target %TARGET% -Configuration %CONFIG% -Filter "%FILTER%"
)
exit /b %ERRORLEVEL%
