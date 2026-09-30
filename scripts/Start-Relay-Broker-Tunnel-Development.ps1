<#
.SYNOPSIS
  Repo-free launcher: Broker + ngrok stable-domain tunnel + the installed PC Gateway EXE, so a
  phone on mobile data (no shared Wi-Fi/LAN) can reach this PC at a URL that survives restarts.

.DESCRIPTION
  This launcher needs only Docker Desktop and the shipped broker bundle (relay-broker-bundle.zip).
  It does NOT clone the repository, run Gradle, or build a Dockerfile. It runs the Broker from the
  public eclipse-temurin:17-jre image with the bundled classes bind-mounted, exposes it through an
  ngrok tunnel bound to your free reserved static domain, issues one short-lived scoped credential
  kept only in this process, then starts the installed RelayPcGateway.exe wired to that tunnel. The
  phone POSTs encrypted envelopes to the stable ngrok URL and this PC Gateway pulls them outbound.

  Because the ngrok domain is stable, the preview APK can bake it once (see -NgrokDomain output) and
  the phone never needs another rebuild.

  Development preview only. The Broker never decrypts rescue envelopes, but a free ngrok tunnel has
  no SLA and must not be used for pilot or emergency operation. The raw credential and the ngrok
  authtoken are never written to the console, a file, or command history.
#>
[CmdletBinding()]
param(
    [string]$Executable,
    [string]$BrokerBundle,
    [string]$BrokerLibDir,
    [ValidateRange(1, 24)]
    [int]$CredentialLifetimeHours = 2,
    [string]$GatewayId = 'development-pc-gateway',
    [ValidateRange(1, 65535)]
    [int]$Port = 8080,
    [string]$NgrokDomain,
    [string]$NgrokAuthToken,
    [string]$Username,
    [securestring]$Password,
    [switch]$ResetAdmin,
    [switch]$EnableLanEnrollment,
    [switch]$NoBrowser,
    [switch]$Down
)

$ErrorActionPreference = 'Stop'

$stateRoot = Join-Path $env:LOCALAPPDATA 'Relay\broker-tunnel'
$libDir = Join-Path $stateRoot 'broker-lib'
$composeFile = Join-Path $stateRoot 'compose.broker-tunnel.yml'

function Assert-Docker {
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
        throw 'Docker was not found. Install Docker Desktop and start it, then run this again.'
    }
    & docker compose version *> $null
    if ($LASTEXITCODE -ne 0) { throw 'Docker Compose v2 is required. Update Docker Desktop, then run this again.' }
    # `docker compose version` is client-only; confirm the engine is actually reachable so the
    # failure is a clear message here rather than a cryptic pipe error during `compose up`.
    & docker info *> $null
    if ($LASTEXITCODE -ne 0) {
        throw 'Docker is installed but its engine is not reachable. Start Docker Desktop, wait until it reports "Engine running", then run this again.'
    }
}

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
Finish the development installer and run this again, or pass the full path with -Executable.
Default install path: $env:ProgramFiles\RelayPcGateway\RelayPcGateway.exe
"@
}

function Resolve-BrokerLibDir {
    param([string]$RequestedLibDir, [string]$RequestedBundle)

    if (-not [string]::IsNullOrWhiteSpace($RequestedLibDir)) {
        if (-not (Get-ChildItem -LiteralPath $RequestedLibDir -Filter '*.jar' -ErrorAction SilentlyContinue)) {
            throw "No broker .jar files were found under -BrokerLibDir: $RequestedLibDir"
        }
        return (Resolve-Path -LiteralPath $RequestedLibDir).Path
    }
    if (Get-ChildItem -LiteralPath $libDir -Filter '*.jar' -ErrorAction SilentlyContinue) {
        return (Resolve-Path -LiteralPath $libDir).Path
    }

    $bundle = $RequestedBundle
    if ([string]::IsNullOrWhiteSpace($bundle)) {
        $bundle = @(
            (Join-Path $PSScriptRoot 'relay-broker-bundle.zip'),
            (Join-Path $PSScriptRoot '..\relay-broker-bundle.zip')
        ) | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1
    }
    if ([string]::IsNullOrWhiteSpace($bundle) -or -not (Test-Path -LiteralPath $bundle -PathType Leaf)) {
        throw @"
relay-broker-bundle.zip was not found.
Download it from the same development preview release as RelayPcGateway, place it beside this
script, or pass its path with -BrokerBundle. Alternatively point -BrokerLibDir at an extracted
broker/lib directory.
"@
    }
    New-Item -ItemType Directory -Force -Path $libDir | Out-Null
    Get-ChildItem -LiteralPath $libDir -ErrorAction SilentlyContinue | Remove-Item -Recurse -Force -ErrorAction SilentlyContinue
    Expand-Archive -LiteralPath $bundle -DestinationPath $libDir -Force
    # The bundle may contain a top-level lib/ folder; flatten to the directory that holds the jars.
    $jarHome = Get-ChildItem -LiteralPath $libDir -Recurse -Filter '*.jar' -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if (-not $jarHome) { throw "The broker bundle contained no .jar files: $bundle" }
    return (Split-Path -Parent $jarHome.FullName)
}

function Write-ComposeFile {
    param([string]$ResolvedLibDir, [string]$NgrokDomain)
    $mountPath = ($ResolvedLibDir -replace '\\', '/')
    $content = @"
# Generated by Start-Relay-Broker-Tunnel-Development.ps1. Development preview only.
# Uses only public images; the Broker runs the bundled classes and never decrypts envelopes.
name: relay-broker-tunnel
services:
  broker:
    image: eclipse-temurin:17-jre
    working_dir: /opt/relay
    command: ["java", "-cp", "/opt/relay/lib/*", "com.example.relay.broker.MainKt"]
    environment:
      RELAY_BROKER_PROFILE: development
      RELAY_BROKER_HOST: 0.0.0.0
      RELAY_BROKER_PORT: "8443"
      RELAY_BROKER_DB_PATH: /var/lib/relay/broker.db
    volumes:
      - "${mountPath}:/opt/relay/lib:ro"
      - relay-broker-tunnel-data:/var/lib/relay
    expose:
      - "8443"
    restart: unless-stopped
  ngrok:
    image: ngrok/ngrok:latest
    command: ["http", "--domain=$NgrokDomain", "--log=stdout", "--log-format=logfmt", "broker:8443"]
    environment:
      - NGROK_AUTHTOKEN
    depends_on:
      - broker
    restart: unless-stopped
volumes:
  relay-broker-tunnel-data:
"@
    Set-Content -LiteralPath $composeFile -Value $content -Encoding utf8
}

function Resolve-NgrokDomain {
    param([string]$Requested)
    $domain = $Requested
    if ([string]::IsNullOrWhiteSpace($domain)) { $domain = $env:RELAY_NGROK_DOMAIN }
    # This project's reserved free static domain, baked into the preview APK as broker_endpoint.
    # Override with -NgrokDomain or RELAY_NGROK_DOMAIN if you reserve a different one.
    if ([string]::IsNullOrWhiteSpace($domain)) {
        throw 'An ngrok static domain is required. Pass -NgrokDomain <your-name.ngrok-free.dev> or set RELAY_NGROK_DOMAIN. Reserve one free static domain at https://dashboard.ngrok.com/domains.'
    }
    $domain = $domain.Trim() -replace '^https?://', ''
    $domain = $domain.TrimEnd('/')
    if ($domain -notmatch '^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$' -or $domain -notmatch '\.') {
        throw "The ngrok domain must be a bare hostname such as your-name.ngrok-free.app, got: $domain"
    }
    return $domain
}

function Resolve-NgrokAuthToken {
    # Accept a plaintext token as an argument for quick verification. Note: an authtoken passed on
    # the command line is visible in PowerShell history; omit -NgrokAuthToken to be prompted securely.
    param([string]$Requested)
    if (-not [string]::IsNullOrWhiteSpace($Requested)) { return $Requested }
    if (-not [string]::IsNullOrWhiteSpace($env:NGROK_AUTHTOKEN)) { return $env:NGROK_AUTHTOKEN }
    $secure = Read-Host 'ngrok authtoken (https://dashboard.ngrok.com/get-started/your-authtoken)' -AsSecureString
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
}

function Wait-NgrokReady {
    param([string]$Domain)
    $publicUrl = "https://$Domain"
    $deadline = [DateTime]::UtcNow.AddSeconds(90)
    do {
        Start-Sleep -Seconds 2
        $log = & docker compose -f $composeFile logs --no-log-prefix ngrok 2>&1
        $text = ($log -join "`n")
        # Hard failures: a bad authtoken or a domain not reserved to this account. Bail out early.
        if ($text -match 'authentication failed|ERR_NGROK_105|ERR_NGROK_108|ERR_NGROK_324|failed to start tunnel') {
            throw "ngrok failed to start. Verify the authtoken and that the domain is reserved to your account.`n$text"
        }
        # Ready signal 1: the agent logged the tunnel URL (requires --log=stdout on the container).
        if ($text -match [regex]::Escape($Domain)) { return }
        # Ready signal 2: some ngrok images do not log the URL to stdout. Probe the public endpoint;
        # any HTTP response other than "endpoint offline" (ERR_NGROK_3200) proves an agent is connected.
        try {
            Invoke-WebRequest -Uri $publicUrl -Headers @{ 'ngrok-skip-browser-warning' = 'true' } -TimeoutSec 5 -UseBasicParsing -ErrorAction Stop | Out-Null
            return
        } catch {
            $resp = $_.Exception.Response
            if ($null -ne $resp) {
                $code = $null
                try { $code = [string]$resp.Headers['Ngrok-Error-Code'] } catch { $code = $null }
                if ($code -ne 'ERR_NGROK_3200') { return }
            }
        }
    } while ([DateTime]::UtcNow -lt $deadline)
    throw 'ngrok did not report the tunnel within 90 seconds. Run "docker compose logs ngrok" to inspect.'
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

function Test-BrokerPullReachable {
    # Confirms the mobile-reachable cloud path is healthy BEFORE we start the gateway: the ngrok
    # tunnel reaches the broker AND the freshly issued gateway credential authenticates. This turns an
    # opaque "the SOS never arrived" into a precise, early diagnosis. The skip header opts out of the
    # free-ngrok browser interstitial exactly as the phone and the gateway pull agent do.
    param([string]$BrokerUrl, [string]$ShelterId, [string]$GatewayId, [string]$Credential)
    $url = "$BrokerUrl/v1/gateways/$ShelterId/pull?limit=1"
    $headers = @{
        'Authorization'              = "Bearer $Credential"
        'X-Gateway-Id'               = $GatewayId
        'ngrok-skip-browser-warning' = 'true'
    }
    try {
        $response = Invoke-WebRequest -Uri $url -Headers $headers -TimeoutSec 10 -UseBasicParsing -ErrorAction Stop
        $ngrokError = [string]$response.Headers['Ngrok-Error-Code']
        if (-not [string]::IsNullOrWhiteSpace($ngrokError)) {
            throw "The tunnel returned an ngrok error ($ngrokError) instead of the broker. Inspect 'docker compose logs ngrok'."
        }
        return
    } catch {
        $resp = $_.Exception.Response
        if ($null -eq $resp) {
            throw "Could not reach the broker through the tunnel at $BrokerUrl. Check the network and 'docker compose logs ngrok'.`n$($_.Exception.Message)"
        }
        $ngrokError = $null
        try { $ngrokError = [string]$resp.Headers['Ngrok-Error-Code'] } catch { $ngrokError = $null }
        if (-not [string]::IsNullOrWhiteSpace($ngrokError)) {
            throw "The tunnel returned an ngrok error ($ngrokError) instead of the broker. Inspect 'docker compose logs ngrok'."
        }
        $status = [int]$resp.StatusCode
        if ($status -eq 401 -or $status -eq 403) {
            throw "The broker rejected the freshly issued gateway credential (HTTP $status). This is a broker/credential fault, not a network problem."
        }
        throw "The broker pull self-test failed with HTTP $status through $BrokerUrl."
    }
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

Assert-Docker
New-Item -ItemType Directory -Force -Path $stateRoot | Out-Null

if ($Down) {
    if (Test-Path -LiteralPath $composeFile) {
        & docker compose -f $composeFile down -v
    }
    Write-Host 'Broker and ngrok tunnel stopped. The installed gateway EXE, if running, was not stopped.'
    return
}

$ngrokDomain = Resolve-NgrokDomain -Requested $NgrokDomain
$ngrokAuthToken = Resolve-NgrokAuthToken -Requested $NgrokAuthToken

$resolvedLibDir = Resolve-BrokerLibDir -RequestedLibDir $BrokerLibDir -RequestedBundle $BrokerBundle
Write-ComposeFile -ResolvedLibDir $resolvedLibDir -NgrokDomain $ngrokDomain
$gateway = Find-RelayPcGatewayExecutable -RequestedPath $Executable

# The authtoken reaches only the ngrok container through a process-scoped env var (compose passes
# it through by name). It is never written to the compose file, the console, or command history,
# and is cleared in the finally block below.
$env:NGROK_AUTHTOKEN = $ngrokAuthToken
$ngrokAuthToken = $null
& docker compose -f $composeFile up -d --remove-orphans
if ($LASTEXITCODE -ne 0) { throw 'Failed to start the broker/tunnel containers.' }

Wait-NgrokReady -Domain $ngrokDomain
$brokerUrl = "https://$ngrokDomain"
if ($brokerUrl -notmatch '^https://[^/]+$') { throw "ngrok domain did not form a bare HTTPS origin: $brokerUrl" }

$shelterId = $GatewayId
$expiry = [DateTimeOffset]::UtcNow.AddHours($CredentialLifetimeHours).ToUnixTimeMilliseconds()
$issued = & docker compose -f $composeFile exec -T broker java -cp '/opt/relay/lib/*' com.example.relay.broker.MainKt issue-gateway-credential --gateway-id $GatewayId --shelter-id $shelterId --expires-at $expiry 2>&1
if ($LASTEXITCODE -ne 0) { throw "Broker credential issuance failed:`n$($issued -join "`n")" }
$credentialLine = $issued | Where-Object { $_ -match '^RELAY_BROKER_CREDENTIAL=' } | Select-Object -First 1
if ([string]::IsNullOrWhiteSpace($credentialLine)) { throw 'Broker returned no credential.' }
$credential = $credentialLine.Substring('RELAY_BROKER_CREDENTIAL='.Length)

# Fail fast with a precise reason if the mobile-reachable cloud path is unhealthy, before committing
# to gateway startup. A pass here means phone -> ngrok -> broker -> gateway pull is fully wired.
Test-BrokerPullReachable -BrokerUrl $brokerUrl -ShelterId $shelterId -GatewayId $GatewayId -Credential $credential
Write-Host 'Cloud path self-test passed: the tunnel reaches the broker and the gateway credential authenticates.' -ForegroundColor Green

$consoleUrl = "http://127.0.0.1:$Port/"
$healthUrl = "http://127.0.0.1:$Port/api/health"
if (Test-GatewayHealth -HealthUrl $healthUrl) {
    throw "A gateway is already serving $consoleUrl. Stop it first so it can restart wired to the broker tunnel."
}

# Keep broker-tunnel state isolated from the LAN-only development launcher.
$env:RELAY_PROFILE = 'development'
$env:RELAY_GATEWAY_ID = $GatewayId
$env:RELAY_SHELTER_ID = $shelterId
$env:RELAY_BROKER_URL = $brokerUrl
$env:RELAY_BROKER_CREDENTIAL = $credential
$env:RELAY_GATEWAY_DB = Join-Path $stateRoot 'relay-gateway.db'
$env:RELAY_RESCUE_KEY_FILE = Join-Path $stateRoot 'rescue-keys.json'
$env:RELAY_BLE_BRIDGE_SECRET_FILE = Join-Path $stateRoot 'ble-bridge.key'
$env:RELAY_GATEWAY_PORT = "$Port"
$env:RELAY_GATEWAY_PUBLIC_PORT = "$Port"
$env:RELAY_GATEWAY_PUBLIC_SCHEME = 'http'
if ($EnableLanEnrollment) {
    $env:RELAY_GATEWAY_HOST = '0.0.0.0'
    $env:RELAY_GATEWAY_LAN_MODE = 'closed-network'
    $env:RELAY_GATEWAY_LAN_DISCOVERY = 'true'
    $env:RELAY_GATEWAY_ANONYMOUS_INGRESS = 'true'
    Write-Host 'Temporary private-LAN enrollment is enabled. Keep the Windows network profile Private.' -ForegroundColor Yellow
} else {
    $env:RELAY_GATEWAY_HOST = '127.0.0.1'
    $env:RELAY_GATEWAY_LAN_MODE = 'disabled'
    $env:RELAY_GATEWAY_LAN_DISCOVERY = 'false'
    $env:RELAY_GATEWAY_ANONYMOUS_INGRESS = 'false'
}

$gatewayDbPath = $env:RELAY_GATEWAY_DB
try {
    if ($ResetAdmin -and (Test-Path -LiteralPath $gatewayDbPath)) {
        # Development-only reset: clear this profile's gateway database so the next bootstrap can
        # create the administrator you enter below. This discards locally queued development data;
        # rescue keys (rescue-keys.json) are preserved, so previously encrypted envelopes still open.
        Get-ChildItem -LiteralPath $stateRoot -Filter 'relay-gateway.db*' -ErrorAction SilentlyContinue |
            Remove-Item -Force -ErrorAction SilentlyContinue
        Write-Host 'Reset: cleared the existing gateway database for this development profile.' -ForegroundColor Yellow
    }

    # bootstrap-admin only creates the FIRST administrator; on a re-run it throws and any name or
    # password entered here would be silently ignored (a frequent "my login does not work" trap).
    # Only prompt and bootstrap when there is no existing database for this profile.
    if (Test-Path -LiteralPath $gatewayDbPath) {
        Write-Host 'An administrator already exists for this development profile.' -ForegroundColor Yellow
        Write-Host 'Sign in with the username and password created on the first run.'
        Write-Host 'To set a different username/password, re-run with -ResetAdmin (clears this dev gateway database).'
    } else {
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
            throw 'Could not create the administrator. Use a 3-64 character username and a 12+ character password, then run again.'
        }
    }

    $process = Start-Process -FilePath $gateway -WorkingDirectory (Split-Path -Parent $gateway) -PassThru
} finally {
    Remove-Item Env:RELAY_BROKER_CREDENTIAL -ErrorAction SilentlyContinue
    Remove-Item Env:NGROK_AUTHTOKEN -ErrorAction SilentlyContinue
}

$deadline = [DateTime]::UtcNow.AddSeconds(30)
do {
    Start-Sleep -Milliseconds 500
    $health = Test-GatewayHealth -HealthUrl $healthUrl
    if ($health) { break }
} while (-not $process.HasExited -and [DateTime]::UtcNow -lt $deadline)

if (-not $health) {
    if (-not $process.HasExited) { Stop-Process -Id $process.Id -ErrorAction SilentlyContinue }
    throw 'The gateway did not become healthy within 30 seconds. Check the gateway window that opened.'
}
if ($health.status -eq 'bootstrap_required') {
    if (-not $process.HasExited) { Stop-Process -Id $process.Id -ErrorAction SilentlyContinue }
    throw 'No administrator exists yet. Re-run with -ResetAdmin to (re)create one with a 3-64 character username and a 12+ character password.'
}

Write-Host ''
Write-Host 'Relay broker tunnel is live (development preview only).' -ForegroundColor Green
Write-Host "  Phone endpoint (mobile data): $brokerUrl"
Write-Host "  Operator console (this PC)  : $consoleUrl"
Write-Host "  Credential lifetime         : $CredentialLifetimeHours hour(s)"
Write-Host ''
Write-Host 'This ngrok domain is stable across restarts. Bake it into the preview APK once so the'
Write-Host 'phone never needs another rebuild:'
Write-Host "  - Set the GitHub Actions repo variable RELAY_BROKER_ENDPOINT = $brokerUrl"
Write-Host '  - Re-run the "Publish Relay development preview" workflow, then install that APK.'
Write-Host 'Or build locally against this endpoint (needs the repo):'
Write-Host "  .\gradlew.bat :app:assembleLocalDev -Prelay.broker.endpoint=$brokerUrl"
Write-Host ''
Write-Host 'Stop the broker and tunnel when finished:'
Write-Host '  Start-Relay-Broker-Tunnel-Development.cmd -Down'
if (-not $NoBrowser) { Start-Process $consoleUrl | Out-Null }
