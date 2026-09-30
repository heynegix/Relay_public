[CmdletBinding()]
param(
    [string]$Executable = "$env:ProgramFiles\RelayPcGateway\RelayPcGateway.exe",
    [string]$TaskName = 'Relay PC Gateway',
    [ValidateSet('production', 'lab', 'development')]
    [string]$Profile = 'production',
    [ValidateSet('disabled', 'closed-network', 'tls-reverse-proxy')]
    [string]$LanMode = 'disabled',
    [string]$HostBind = '127.0.0.1',
    [ValidateRange(1, 65535)]
    [int]$Port = 8080,
    [string]$GatewayId = 'pc-gateway-local',
    [switch]$EnableAnonymousIngress,
    [switch]$EnableLanDiscovery,
    [switch]$EnableRemoteManagement,
    [switch]$SessionCookieSecure,
    [string]$DbPath = '',
    [string]$RescueKeyFile = "$env:ProgramData\RelayPcGateway\rescue-keys.json",
    [string]$SignedManifestFile = "$env:ProgramData\RelayPcGateway\rescue-manifest.json",
    [string]$RegionalRootBundleFile = "$env:ProgramData\RelayPcGateway\regional-root.json",
    [string]$RunnerPath = "$env:ProgramData\RelayPcGateway\run-gateway.ps1"
)

$ErrorActionPreference = 'Stop'

function Test-IsAdministrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = [Security.Principal.WindowsPrincipal]::new($identity)
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function ConvertTo-SingleQuotedLiteral([string]$Value) {
    return "'" + $Value.Replace("'", "''") + "'"
}

function Test-LoopbackAddress([Net.IPAddress]$Address) {
    return [Net.IPAddress]::IsLoopback($Address)
}

if (-not (Test-IsAdministrator)) {
    throw 'Run this script from an elevated Administrator PowerShell.'
}
if (-not (Test-Path -LiteralPath $Executable -PathType Leaf)) {
    throw "Gateway executable not found: $Executable"
}
if ([string]::IsNullOrWhiteSpace($TaskName) -or $TaskName.Length -gt 120) {
    throw 'TaskName must contain 1 to 120 characters.'
}
if ($GatewayId -notmatch '^[A-Za-z0-9._-]{1,64}$') {
    throw 'GatewayId must contain only letters, numbers, dot, underscore or hyphen (1-64 characters).'
}
$parsedAddress = $null
if (-not [Net.IPAddress]::TryParse($HostBind, [ref]$parsedAddress)) {
    throw 'HostBind must be a valid IP address.'
}
$isLoopback = Test-LoopbackAddress $parsedAddress

if ($LanMode -eq 'tls-reverse-proxy' -and -not $isLoopback) {
    throw 'TLS reverse-proxy mode must bind Relay itself to loopback; expose only the separately operated proxy.'
}
if ($Profile -ne 'development' -and -not $isLoopback -and $LanMode -eq 'disabled') {
    throw 'Production/lab non-loopback binding requires -LanMode closed-network or tls-reverse-proxy.'
}
if ($Profile -ne 'development' -and ($EnableAnonymousIngress -or $EnableLanDiscovery) -and $LanMode -eq 'disabled') {
    throw 'Production/lab anonymous ingress or LAN discovery requires an explicit LAN mode.'
}
if ($EnableLanDiscovery -and -not $EnableAnonymousIngress) {
    throw 'LAN discovery advertises anonymous sync; enable anonymous ingress too or leave discovery disabled.'
}
if ($EnableRemoteManagement -and $LanMode -ne 'tls-reverse-proxy') {
    throw 'Remote browser management requires -LanMode tls-reverse-proxy.'
}
if ($EnableRemoteManagement -and -not $SessionCookieSecure) {
    throw 'Remote browser management requires -SessionCookieSecure.'
}

$Executable = [IO.Path]::GetFullPath($Executable)
$RunnerPath = [IO.Path]::GetFullPath($RunnerPath)
$RescueKeyFile = [IO.Path]::GetFullPath($RescueKeyFile)
$SignedManifestFile = [IO.Path]::GetFullPath($SignedManifestFile)
$RegionalRootBundleFile = [IO.Path]::GetFullPath($RegionalRootBundleFile)
$relayDir = Split-Path -Parent $RunnerPath
New-Item -ItemType Directory -Path $relayDir -Force | Out-Null
if ([string]::IsNullOrWhiteSpace($DbPath)) {
    $DbPath = Join-Path $relayDir 'relay-gateway.db'
}
$DbPath = [IO.Path]::GetFullPath($DbPath)

# Do not create an admin key or a default password. The first local administrator is created
# once with RelayPcGateway.exe bootstrap-admin --username <id>, using an interactive console or
# RELAY_GATEWAY_BOOTSTRAP_CLI_SECRET only for that command.
$runnerDirectory = Split-Path -Parent $RunnerPath
New-Item -ItemType Directory -Path $runnerDirectory -Force | Out-Null
$executableDirectory = Split-Path -Parent $Executable
$runnerLines = @(
    "`$ErrorActionPreference = 'Stop'"
    "`$env:RELAY_PROFILE = $(ConvertTo-SingleQuotedLiteral $Profile)"
    "`$env:RELAY_GATEWAY_LAN_MODE = $(ConvertTo-SingleQuotedLiteral $LanMode)"
    "`$env:RELAY_GATEWAY_HOST = $(ConvertTo-SingleQuotedLiteral $HostBind)"
    "`$env:RELAY_GATEWAY_PORT = $(ConvertTo-SingleQuotedLiteral $Port.ToString())"
    "`$env:RELAY_GATEWAY_ID = $(ConvertTo-SingleQuotedLiteral $GatewayId)"
    "`$env:RELAY_GATEWAY_DB = $(ConvertTo-SingleQuotedLiteral $DbPath)"
    "`$env:RELAY_RESCUE_KEY_FILE = $(ConvertTo-SingleQuotedLiteral $RescueKeyFile)"
    "`$env:RELAY_RESCUE_SIGNED_MANIFEST_FILE = $(ConvertTo-SingleQuotedLiteral $SignedManifestFile)"
    "`$env:RELAY_RESCUE_REGIONAL_ROOT_BUNDLE_FILE = $(ConvertTo-SingleQuotedLiteral $RegionalRootBundleFile)"
    "`$env:RELAY_GATEWAY_ANONYMOUS_INGRESS = '$(if ($EnableAnonymousIngress) { 'true' } else { 'false' })'"
    "`$env:RELAY_GATEWAY_LAN_DISCOVERY = '$(if ($EnableLanDiscovery) { 'true' } else { 'false' })'"
    "`$env:RELAY_GATEWAY_REMOTE_MANAGEMENT = '$(if ($EnableRemoteManagement) { 'true' } else { 'false' })'"
    "`$env:RELAY_GATEWAY_SESSION_COOKIE_SECURE = '$(if ($SessionCookieSecure) { 'true' } else { 'false' })'"
    "Set-Location -LiteralPath $(ConvertTo-SingleQuotedLiteral $executableDirectory)"
    "& $(ConvertTo-SingleQuotedLiteral $Executable)"
    '# Treat every unexpected gateway exit as a task failure so Task Scheduler restarts it.'
    "if (`$null -eq `$LASTEXITCODE -or `$LASTEXITCODE -eq 0) { exit 1 }"
    "exit `$LASTEXITCODE"
)
$runnerLines | Set-Content -LiteralPath $RunnerPath -Encoding UTF8

$powerShellExe = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
$actionArguments = "-NoProfile -NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$RunnerPath`""
$action = New-ScheduledTaskAction -Execute $powerShellExe -Argument $actionArguments
$trigger = New-ScheduledTaskTrigger -AtStartup -RandomDelay (New-TimeSpan -Seconds 30)
$settings = New-ScheduledTaskSettingsSet `
    -StartWhenAvailable `
    -RestartCount 999 `
    -RestartInterval (New-TimeSpan -Minutes 1) `
    -ExecutionTimeLimit ([TimeSpan]::Zero) `
    -MultipleInstances IgnoreNew `
    -AllowStartIfOnBatteries `
    -DontStopIfGoingOnBatteries
$principal = New-ScheduledTaskPrincipal -UserId 'SYSTEM' -LogonType ServiceAccount -RunLevel Highest

$existingTask = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
if ($existingTask -and $existingTask.State -eq 'Running') {
    Stop-ScheduledTask -TaskName $TaskName
    for ($attempt = 0; $attempt -lt 20; $attempt++) {
        if ((Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue).State -ne 'Running') {
            break
        }
        Start-Sleep -Milliseconds 250
    }
    if ((Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue).State -eq 'Running') {
        throw "Existing task did not stop safely: $TaskName"
    }
}
Register-ScheduledTask `
    -TaskName $TaskName `
    -Action $action `
    -Trigger $trigger `
    -Settings $settings `
    -Principal $principal `
    -Force | Out-Null

Write-Output "Registered supervised Gateway task: $TaskName"
Write-Output "  Profile: $Profile  LAN mode: $LanMode"
Write-Output "  Host: $HostBind  Port: $Port"
Write-Output "  DB: $DbPath"
Write-Output "  Anonymous ingress: $([bool]$EnableAnonymousIngress)  LAN discovery: $([bool]$EnableLanDiscovery)"
Write-Output '  No shared admin key was created. Bootstrap a named local administrator before operator use.'
