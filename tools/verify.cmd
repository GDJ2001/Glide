@echo off
REM HARD-035: Glide Verify Script (batch wrapper)
REM Usage: verify.cmd [-SkipTests] [-SkipLint] [-SkipArchitecture]
set ARGS=
if "%1" neq "" set ARGS=%ARGS% %1
if "%2" neq "" set ARGS=%ARGS% %2
if "%3" neq "" set ARGS=%ARGS% %3
pwsh -ExecutionPolicy Bypass -File "%~dp0verify.ps1" %ARGS%
exit /b %ERRORLEVEL%
