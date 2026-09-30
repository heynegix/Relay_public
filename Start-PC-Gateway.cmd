@echo off
REM One-click Relay PC Gateway (Windows).
REM Double-click this file from the repo root. First run may build via Gradle (JDK required).
setlocal
cd /d "%~dp0"
title Relay PC Gateway
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\run-pc-gateway.ps1" %*
set EXITCODE=%ERRORLEVEL%
if not "%EXITCODE%"=="0" (
  echo.
  echo Gateway exited with code %EXITCODE%.
  pause
)
exit /b %EXITCODE%
