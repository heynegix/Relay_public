<#
.SYNOPSIS
  Starts the Relay PC Gateway development preview with only a username and password prompt.

.DESCRIPTION
  This launcher is intentionally limited to the unsigned/local development preview. It keeps its
  database and generated rescue keys under LocalAppData, configures local-LAN discovery, creates
  the first named administrator when necessary, then opens the local operator console.

  It never writes a password to a command line, file, log, or the persistent environment.
  Do not use this launcher for production, pilot, or an untrusted/public network.
#>
[CmdletBinding()]
param(
    [string]$Username,
    [securestring]$Password,
    [ValidateRange(1, 65535)]
    [int]$Port = 8080,
    [string]$Executable,
    [switch]$NoBrowser,
    [switch]$AllowLan
)

$ErrorActionPreference = 'Stop'

function Find-RelayPcGatewayExecutable {
    param([string]$RequestedPath)

    $candidates = @(
        $RequestedPath,
        (Join-Path $PSScriptRoot 'RelayPcGateway.exe'),
        (Join-Path $PSScriptRoot '..\RelayPcGateway.exe'),
        (Join-Path ${env:ProgramFiles} 'RelayPcGateway\RelayPcGateway.exe'),
        (Join-Path ${env:LOCALAPPDATA} 'Programs\RelayPcGateway\RelayPcGateway.exe')
    ) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }

    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }
    throw @"
RelayPcGateway.exe was not found.
Finish the development installer and run this again, or pass the full path to RelayPcGateway.exe with -Executable.
Default install path: $env:ProgramFiles\RelayPcGateway\RelayPcGateway.exe
"@
}

function Test-GatewayHealth {
    param([string]$HealthUrl)
    try {
        $response = Invoke-RestMethod -Uri $HealthUrl -Method Get -TimeoutSec 2
        if ($response.status -in @('ok', 'bootstrap_required')) { return $response }
    } catch {
        return $null
    }
    return $null
}

function Test-ListeningPort {
    param([int]$PortNumber)
    $listener = Get-NetTCPConnection -LocalPort $PortNumber -State Listen -ErrorAction SilentlyContinue |
        Select-Object -First 1
    return $null -ne $listener
}

function Read-DevelopmentAdministrator {
    param([string]$InitialUsername, [securestring]$InitialPassword)

    $name = $InitialUsername
    while ([string]::IsNullOrWhiteSpace($name)) {
        $name = Read-Host 'Administrator username (3-64 chars: letters, digits, . _ -)'
    }
    if ($name -notmatch '^[A-Za-z0-9][A-Za-z0-9_.-]{2,63}$') {
        throw 'Username must be 3-64 characters, start with a letter or digit, and use only . _ - as symbols.'
    }
    $secret = $InitialPassword
    if ($null -eq $secret) {
        $secret = Read-Host 'Administrator password (12+ characters)' -AsSecureString
    }
    return [pscustomobject]@{ Username = $name; Password = $secret }
}

$gateway = Find-RelayPcGatewayExecutable -RequestedPath $Executable
$stateRoot = Join-Path $env:LOCALAPPDATA 'Relay\development'
New-Item -ItemType Directory -Force -Path $stateRoot | Out-Null

# Keep preview state isolated from production/lab data and provisioning. Do not inherit a prior
# shell's production root/manifests into a generated-key development run.
$env:RELAY_PROFILE = 'development'
$env:RELAY_GATEWAY_LAN_MODE = 'disabled'
$env:RELAY_GATEWAY_HOST = if ($AllowLan) { '0.0.0.0' } else { '127.0.0.1' }
$env:RELAY_GATEWAY_PORT = "$Port"
$env:RELAY_GATEWAY_PUBLIC_PORT = "$Port"
$env:RELAY_GATEWAY_PUBLIC_SCHEME = 'http'
$env:RELAY_GATEWAY_DB = Join-Path $stateRoot 'relay-gateway.db'
$env:RELAY_GATEWAY_ID = 'development-pc-gateway'
$env:RELAY_SHELTER_ID = $env:RELAY_GATEWAY_ID
$env:RELAY_GATEWAY_REMOTE_MANAGEMENT = 'false'
$env:RELAY_GATEWAY_ENABLE_LEGACY_ADMIN_KEY = 'false'
$env:RELAY_GATEWAY_ANONYMOUS_INGRESS = 'true'
$env:RELAY_GATEWAY_LAN_DISCOVERY = if ($AllowLan) { 'true' } else { 'false' }
$env:RELAY_LOCAL_PILOT_INGRESS = 'true'
$env:RELAY_RESCUE_KEY_FILE = Join-Path $stateRoot 'rescue-keys.json'
$env:RELAY_BLE_BRIDGE_SECRET_FILE = Join-Path $stateRoot 'ble-bridge.key'
Remove-Item Env:RELAY_RESCUE_SIGNED_MANIFEST_FILE -ErrorAction SilentlyContinue
Remove-Item Env:RELAY_RESCUE_REGIONAL_ROOT_BUNDLE_FILE -ErrorAction SilentlyContinue
Remove-Item Env:RELAY_BROKER_URL -ErrorAction SilentlyContinue
Remove-Item Env:RELAY_BROKER_CREDENTIAL -ErrorAction SilentlyContinue
Remove-Item Env:RELAY_BROKER_API_KEY -ErrorAction SilentlyContinue
Remove-Item Env:RELAY_BROKER_GATEWAY_API_KEY -ErrorAction SilentlyContinue

$consoleUrl = "http://127.0.0.1:$Port/"
$healthUrl = "http://127.0.0.1:$Port/api/health"
$health = Test-GatewayHealth -HealthUrl $healthUrl
if ($health) {
    Write-Host "Relay PC Gateway is already running: $consoleUrl"
    if (-not $NoBrowser) { Start-Process $consoleUrl | Out-Null }
    return
}
if (Test-ListeningPort -PortNumber $Port) {
    throw "TCP port $Port is already in use by another program. Stop the existing gateway or choose a different port with -Port."
}

$admin = Read-DevelopmentAdministrator -InitialUsername $Username -InitialPassword $Password
$bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($admin.Password)
try {
    $env:RELAY_GATEWAY_BOOTSTRAP_CLI_SECRET = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
    & $gateway bootstrap-admin --username $admin.Username
    $bootstrapExitCode = $LASTEXITCODE
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
    Remove-Item Env:RELAY_GATEWAY_BOOTSTRAP_CLI_SECRET -ErrorAction SilentlyContinue
}

if ($bootstrapExitCode -ne 0) {
    # A nonzero result normally means an existing named administrator. Starting continues; health
    # below still fails closed if this was actually a first-run validation failure.
    Write-Host 'An existing administrator may be present, so first-run creation was skipped. Starting the gateway to verify its state.' -ForegroundColor Yellow
}

$process = Start-Process -FilePath $gateway -WorkingDirectory (Split-Path -Parent $gateway) -PassThru
$deadline = [DateTime]::UtcNow.AddSeconds(30)
do {
    Start-Sleep -Milliseconds 500
    $health = Test-GatewayHealth -HealthUrl $healthUrl
    if ($health) { break }
} while (-not $process.HasExited -and [DateTime]::UtcNow -lt $deadline)

if (-not $health) {
    if (-not $process.HasExited) { Stop-Process -Id $process.Id -ErrorAction SilentlyContinue }
    throw 'The gateway did not become healthy within 30 seconds. Check the gateway window that opened and the Windows Event Log.'
}
if ($health.status -eq 'bootstrap_required') {
    if (-not $process.HasExited) { Stop-Process -Id $process.Id -ErrorAction SilentlyContinue }
    throw 'Could not create the administrator account. Verify the username and a 12+ character password, then run again.'
}

Write-Host "Relay PC Gateway development preview started: $consoleUrl"
Write-Host "Local pilot page: http://127.0.0.1:$Port/local-pilot"
if ($AllowLan) { Write-Warning 'LAN exposure is enabled for development/training only. Restrict Windows Firewall manually to a trusted private network.' }
Write-Host 'In the browser, sign in with the same username and password you entered here.'
if (-not $NoBrowser) { Start-Process $consoleUrl | Out-Null }
$process.Id
