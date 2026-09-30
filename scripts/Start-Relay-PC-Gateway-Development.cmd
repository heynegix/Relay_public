@echo off
REM Development-preview helper. It prompts only for the local administrator username/password.
setlocal
title Relay PC Gateway Development Preview
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-pc-gateway-development.ps1" %*
set EXITCODE=%ERRORLEVEL%
if not "%EXITCODE%"=="0" (
  echo.
  echo Gateway development launcher exited with code %EXITCODE%.
  pause
)
exit /b %EXITCODE%
