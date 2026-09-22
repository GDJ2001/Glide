@echo off
REM HARD-035: Glide Build Script (batch wrapper)
REM Usage: build.cmd [Debug|Release] [All|Android|Windows]
set CONFIG=%1
if "%CONFIG%"=="" set CONFIG=Debug
set TARGET=%2
if "%TARGET%"=="" set TARGET=All
pwsh -ExecutionPolicy Bypass -File "%~dp0build.ps1" -Configuration %CONFIG% -Target %TARGET%
exit /b %ERRORLEVEL%
