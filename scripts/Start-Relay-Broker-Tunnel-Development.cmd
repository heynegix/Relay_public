@echo off
REM Development-preview helper. Broker + Cloudflare Quick Tunnel + installed PC Gateway EXE,
REM so a phone on mobile data can reach this PC. Needs Docker Desktop and relay-broker-bundle.zip.
setlocal
title Relay Broker Tunnel Development Preview
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0Start-Relay-Broker-Tunnel-Development.ps1" %*
set EXITCODE=%ERRORLEVEL%
if not "%EXITCODE%"=="0" (
  echo.
  echo Broker tunnel launcher exited with code %EXITCODE%.
  pause
)
exit /b %EXITCODE%
