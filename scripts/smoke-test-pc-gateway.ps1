<#
.SYNOPSIS
  Short-lived PC Gateway smoke test for Windows CI and local verification.

.DESCRIPTION
  Builds installDist when missing, starts the real gateway on loopback with an isolated
  temporary database and development-generated rescue keys, waits for /api/health to answer
  HTTP 200, asserts the process stays alive, then terminates the whole process tree and removes
  the temporary state. Exits 0 on a healthy round trip, 1 on any failure or unclean shutdown.

  The gateway runs in the development profile so no production key material, broker credential,
  or signed manifest is required. Nothing secret is written outside the per-run temp directory,
  and that directory is deleted on exit.

.PARAMETER Port
  HTTP port for the gateway. Default 0 selects a free ephemeral port automatically.

.PARAMETER HealthTimeoutSeconds
  How long to wait for /api/health to return HTTP 200 before failing.

.PARAMETER SkipBuild
  Fail instead of building when installDist is missing.

.PARAMETER WorkRoot
  Parent directory for the isolated per-run state. Defaults to the user temp directory. The
  per-run folder name intentionally contains a space to exercise Windows path handling.
#>
[CmdletBinding()]
param(
    [int]$Port = 0,
    [int]$HealthTimeoutSeconds = 90,
    [switch]$SkipBuild,
    [string]$WorkRoot = ''
)

$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path

function Find-FreePort {
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
    $listener.Start()
    try { return [int]$listener.LocalEndpoint.Port }
    finally { $listener.Stop() }
}

if ($Port -le 0) { $Port = Find-FreePort }
$blePort = Find-FreePort
while ($blePort -eq $Port) { $blePort = Find-FreePort }

$bin = Join-Path $Root 'pc-gateway\build\install\pc-gateway\bin\pc-gateway.bat'
if (-not (Test-Path -LiteralPath $bin)) {
    if ($SkipBuild) { throw "PC Gateway installDist missing: $bin (run without -SkipBuild to build)" }
    Write-Host 'Smoke test: building PC Gateway (installDist)...'
    & (Join-Path $Root 'gradlew.bat') ':pc-gateway:installDist' '--no-daemon' '--console=plain'
    if ($LASTEXITCODE -ne 0) { throw "gradlew installDist failed with exit $LASTEXITCODE" }
}
if (-not (Test-Path -LiteralPath $bin)) { throw "installDist finished but launcher not found: $bin" }

if (-not $WorkRoot) { $WorkRoot = [IO.Path]::GetTempPath() }
# Space in the folder name exercises Windows path-with-spaces handling end to end.
$work = Join-Path $WorkRoot ('relay gw smoke ' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $work | Out-Null
$db = Join-Path $work 'relay-gateway.db'
$keys = Join-Path $work 'rescue-keys.json'
$manifest = Join-Path $work 'rescue-manifest.json'
$stdoutLog = Join-Path $work 'gateway-stdout.log'
$stderrLog = Join-Path $work 'gateway-stderr.log'
$healthUrl = "http://127.0.0.1:$Port/api/health"

$env:RELAY_PROFILE = 'development'
$env:RELAY_GATEWAY_HOST = '127.0.0.1'
$env:RELAY_GATEWAY_PORT = "$Port"
$env:RELAY_GATEWAY_DB = $db
$env:RELAY_GATEWAY_ID = 'pc-gateway-ci-smoke'
$env:RELAY_SHELTER_ID = 'ci-smoke-shelter'
$env:RELAY_RESCUE_KEY_FILE = $keys
$env:RELAY_RESCUE_SIGNED_MANIFEST_FILE = $manifest
$env:RELAY_GATEWAY_ENABLE_LEGACY_ADMIN_KEY = 'false'
$env:RELAY_GATEWAY_REMOTE_MANAGEMENT = 'false'
$env:RELAY_GATEWAY_LAN_DISCOVERY = 'false'
$env:RELAY_BLE_BRIDGE_PORT = "$blePort"
# No RELAY_BROKER_URL: the broker cloud relay stays disabled for an isolated smoke test.

$proc = $null
$healthy = $false
try {
    $proc = Start-Process -FilePath $bin -WorkingDirectory $Root -PassThru `
        -RedirectStandardOutput $stdoutLog -RedirectStandardError $stderrLog -WindowStyle Hidden
    Write-Host "Smoke test: gateway pid=$($proc.Id) port=$Port db=$db"

    $deadline = (Get-Date).AddSeconds($HealthTimeoutSeconds)
    $body = $null
    while ((Get-Date) -lt $deadline) {
        if ($proc.HasExited) {
            throw "Gateway process exited early with code $($proc.ExitCode) before becoming healthy"
        }
        try {
            $r = Invoke-WebRequest -Uri $healthUrl -UseBasicParsing -TimeoutSec 2
            if ($r.StatusCode -eq 200) { $body = $r.Content; break }
        } catch {
            Start-Sleep -Milliseconds 500
        }
    }
    if (-not $body) { throw "Gateway did not become healthy within $HealthTimeoutSeconds seconds" }

    $json = $body | ConvertFrom-Json
    if ($json.gatewayId -ne 'pc-gateway-ci-smoke') { throw "Unexpected gatewayId in health response: $($json.gatewayId)" }
    if ($json.profile -ne 'development') { throw "Unexpected profile in health response: $($json.profile)" }
    if (-not $json.database) { throw 'Health response is missing the database field' }
    Write-Host "Smoke test: /api/health -> 200 status=$($json.status) profile=$($json.profile) gatewayId=$($json.gatewayId)"

    if ($proc.HasExited) { throw "Gateway process exited after becoming healthy (code $($proc.ExitCode))" }
    $healthy = $true
    Write-Host 'Smoke test: PASS (healthy round trip, process alive)'
}
finally {
    $treeKilled = $false
    if ($proc -and -not $proc.HasExited) {
        & taskkill.exe /PID $proc.Id /T /F 2>&1 | Out-Null
        try { $proc.WaitForExit(10000) | Out-Null } catch { }
        if ($proc.HasExited) { $treeKilled = $true }
    } elseif ($proc) {
        $treeKilled = $true
    }
    if ($proc -and -not $proc.HasExited) {
        try { Stop-Process -Id $proc.Id -Force -ErrorAction Stop; $treeKilled = $true } catch { }
    }
    if ($proc -and -not $proc.HasExited) {
        Write-Host 'Smoke test: WARNING gateway process did not terminate cleanly' -ForegroundColor Yellow
    }
    Start-Sleep -Milliseconds 500
    if (-not $healthy -and (Test-Path -LiteralPath $stderrLog)) {
        $tail = Get-Content -LiteralPath $stderrLog -Tail 40 -ErrorAction SilentlyContinue
        if ($tail) { Write-Host '----- gateway stderr (tail) -----'; $tail | ForEach-Object { Write-Host $_ } }
    }
    Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue
    if ($healthy -and -not $treeKilled) { exit 1 }
}

if ($healthy) { exit 0 } else { exit 1 }
