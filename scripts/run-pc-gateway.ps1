<#
.SYNOPSIS
  One-action PC Gateway launcher (Windows).

.DESCRIPTION
  Ensures installDist exists (builds if needed), applies production-safe loopback defaults, starts the real
  gateway process, and optionally opens the operator console in the browser.
  Double-click entry: repo-root Start-PC-Gateway.cmd

.PARAMETER NoBrowser
  Do not open the operator console automatically.

.PARAMETER Port
  HTTP port (default 8080). Overrides RELAY_GATEWAY_PORT if set via this param.

.PARAMETER SkipBuild
  Fail if installDist is missing instead of running Gradle.
#>
[CmdletBinding()]
param(
    [switch]$NoBrowser,
    [ValidateRange(1, 65535)]
    [int]$Port = 0,
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location $Root

if (-not $env:RELAY_PROFILE) { $env:RELAY_PROFILE = 'production' }
if (-not $env:RELAY_GATEWAY_LAN_MODE) { $env:RELAY_GATEWAY_LAN_MODE = 'disabled' }
if (-not $env:RELAY_GATEWAY_HOST) {
    $env:RELAY_GATEWAY_HOST = if ($env:RELAY_PROFILE -eq 'development') { '0.0.0.0' } else { '127.0.0.1' }
}
if ($Port -gt 0) {
    $env:RELAY_GATEWAY_PORT = "$Port"
} elseif (-not $env:RELAY_GATEWAY_PORT) {
    $env:RELAY_GATEWAY_PORT = '8080'
}
if (-not $env:RELAY_GATEWAY_DB) {
    $env:RELAY_GATEWAY_DB = Join-Path $env:USERPROFILE '.relay\relay-gateway.db'
}
if (-not $env:RELAY_GATEWAY_ID) { $env:RELAY_GATEWAY_ID = 'pc-gateway-local' }
if (-not $env:RELAY_GATEWAY_ANONYMOUS_INGRESS) { $env:RELAY_GATEWAY_ANONYMOUS_INGRESS = if ($env:RELAY_PROFILE -eq 'development') { 'true' } else { 'false' } }
if (-not $env:RELAY_GATEWAY_LAN_DISCOVERY) { $env:RELAY_GATEWAY_LAN_DISCOVERY = if ($env:RELAY_PROFILE -eq 'development') { 'true' } else { 'false' } }
if (-not $env:RELAY_RESCUE_KEY_FILE) { $env:RELAY_RESCUE_KEY_FILE = [IO.Path]::GetFullPath((Join-Path $env:USERPROFILE '.relay\rescue-keys.json')) }
if (-not $env:RELAY_RESCUE_SIGNED_MANIFEST_FILE) { $env:RELAY_RESCUE_SIGNED_MANIFEST_FILE = [IO.Path]::GetFullPath((Join-Path $env:USERPROFILE '.relay\rescue-manifest.json')) }
if (-not $env:RELAY_RESCUE_REGIONAL_ROOT_BUNDLE_FILE) { $env:RELAY_RESCUE_REGIONAL_ROOT_BUNDLE_FILE = [IO.Path]::GetFullPath((Join-Path $env:USERPROFILE '.relay\regional-root.json')) }

$dbDir = Split-Path -Parent $env:RELAY_GATEWAY_DB
if ($dbDir -and -not (Test-Path -LiteralPath $dbDir)) {
    New-Item -ItemType Directory -Force -Path $dbDir | Out-Null
}
$rescueDir = Split-Path -Parent $env:RELAY_RESCUE_KEY_FILE
if ($rescueDir -and -not (Test-Path -LiteralPath $rescueDir)) { New-Item -ItemType Directory -Force -Path $rescueDir | Out-Null }

$bin = Join-Path $Root 'pc-gateway\build\install\pc-gateway\bin\pc-gateway.bat'
if (-not (Test-Path -LiteralPath $bin)) {
    if ($SkipBuild) {
        throw "PC Gateway installDist missing: $bin (run without -SkipBuild to build)"
    }
    Write-Host 'First run: building PC Gateway (installDist)...'
    $gradlew = Join-Path $Root 'gradlew.bat'
    & $gradlew ':pc-gateway:installDist'
    if ($LASTEXITCODE -ne 0) { throw "gradlew installDist failed with exit $LASTEXITCODE" }
    if (-not (Test-Path -LiteralPath $bin)) {
        throw "installDist finished but launcher not found: $bin"
    }
}

$listenPort = [int]$env:RELAY_GATEWAY_PORT
$consoleUrl = "http://127.0.0.1:$listenPort/"
$healthUrl = "http://127.0.0.1:$listenPort/api/health"

Write-Host ''
Write-Host '========================================'
Write-Host '  Relay PC Gateway'
Write-Host '========================================'
Write-Host "  Console : $consoleUrl"
Write-Host "  Health  : $healthUrl"
Write-Host "  DB      : $($env:RELAY_GATEWAY_DB)"
Write-Host "  Profile : $($env:RELAY_PROFILE) / LAN: $($env:RELAY_GATEWAY_LAN_MODE)"
Write-Host "  Host    : $($env:RELAY_GATEWAY_HOST):$listenPort"
Write-Host '========================================'
Write-Host '  Stop with Ctrl+C in this window.'
Write-Host '  First run: create a named administrator with bootstrap-admin; no default password exists.'
Write-Host ''

if (-not $NoBrowser) {
    # Open console once health is up (non-blocking helper).
    $waiter = {
        param($Url, $OpenUrl)
        for ($i = 0; $i -lt 60; $i++) {
            try {
                $r = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 2
                if ($r.StatusCode -eq 200) {
                    Start-Process $OpenUrl | Out-Null
                    return
                }
            } catch {
                Start-Sleep -Milliseconds 500
            }
        }
    }
    Start-Job -ScriptBlock $waiter -ArgumentList $healthUrl, $consoleUrl | Out-Null
}

# Real product entry (installDist). Blocking until process exits.
& $bin
exit $LASTEXITCODE
