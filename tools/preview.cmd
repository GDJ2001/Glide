@echo off
REM HARD-035: Glide Preview Script (batch wrapper)
REM Usage: preview.cmd [Android|WindowsUI|WindowsCLI] [Debug|Release]
set TARGET=%1
if "%TARGET%"=="" set TARGET=Android
set CONFIG=%2
if "%CONFIG%"=="" set CONFIG=Debug
pwsh -ExecutionPolicy Bypass -File "%~dp0preview.ps1" -Target %TARGET% -Configuration %CONFIG%
exit /b %ERRORLEVEL%
