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
    [ValidateRange(1, 65535)]
    [int]$DiscoveryPort = 42888,
    [string]$GatewayId = 'pc-gateway-local',
    [switch]$EnableAnonymousIngress,
    [switch]$EnableLanDiscovery,
    [switch]$EnableRemoteManagement,
    [switch]$SessionCookieSecure,
    [ValidateRange(5, 120)]
    [int]$HealthTimeoutSeconds = 30,
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'

function Test-IsAdministrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = [Security.Principal.WindowsPrincipal]::new($identity)
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Test-LoopbackHost([string]$Host) {
    $address = $null
    return [Net.IPAddress]::TryParse($Host, [ref]$address) -and [Net.IPAddress]::IsLoopback($address)
}

if (-not (Test-Path -LiteralPath $Executable -PathType Leaf)) {
    throw "Relay PC Gateway is not installed: $Executable"
}
if (-not (Test-LoopbackHost $HostBind) -and $LanMode -eq 'disabled' -and $Profile -ne 'development') {
    throw 'Safety stop: production/lab LAN binding requires -LanMode closed-network or tls-reverse-proxy.'
}
if ($LanMode -eq 'tls-reverse-proxy' -and -not (Test-LoopbackHost $HostBind)) {
    throw 'Safety stop: tls-reverse-proxy mode must keep Relay bound to loopback.'
}
if ($EnableRemoteManagement -and ($LanMode -ne 'tls-reverse-proxy' -or -not $SessionCookieSecure)) {
    throw 'Safety stop: remote management requires -LanMode tls-reverse-proxy and -SessionCookieSecure.'
}
if ($EnableLanDiscovery -and -not $EnableAnonymousIngress) {
    throw 'Safety stop: LAN discovery advertises anonymous sync; enable anonymous ingress too or leave discovery disabled.'
}

$requiresRelayFirewall = -not (Test-LoopbackHost $HostBind)
if ($requiresRelayFirewall) {
    $profiles = @(Get-NetConnectionProfile -ErrorAction Stop)
    if ($profiles.Count -eq 0) {
        throw 'No Windows network profile is active. Connect the approved closed network, then run setup again.'
    }
    $publicProfiles = @($profiles | Where-Object { $_.NetworkCategory -eq 'Public' })
    if ($publicProfiles.Count -gt 0) {
        $names = ($publicProfiles | ForEach-Object { "$($_.InterfaceAlias) [$($_.Name)]" }) -join ', '
        throw @"
Safety stop: Public network profile detected: $names
No firewall rule or scheduled task was changed.
Only after confirming this is the approved closed network, change it to Private in Windows Settings and run setup again.
"@
    }
    $nonPrivateProfiles = @($profiles | Where-Object { $_.NetworkCategory -ne 'Private' })
    if ($nonPrivateProfiles.Count -gt 0) {
        $categories = ($nonPrivateProfiles | ForEach-Object { "$($_.InterfaceAlias)=$($_.NetworkCategory)" }) -join ', '
        throw "Safety stop: every active network must be Private for LAN exposure ($categories). No changes were made."
    }
    Write-Output 'Network profile check: PASS (Private only for requested LAN exposure)'
}

$firewallScript = Join-Path $PSScriptRoot 'configure-pc-gateway-firewall.ps1'
$autostartScript = Join-Path $PSScriptRoot 'register-pc-gateway-autostart.ps1'
if (-not (Test-Path -LiteralPath $firewallScript -PathType Leaf) -or
    -not (Test-Path -LiteralPath $autostartScript -PathType Leaf)) {
    throw 'Required Relay setup scripts are missing.'
}

if ($DryRun) {
    Write-Output 'DRY RUN: no firewall rule, scheduled task, process, database, key, or account was changed.'
    Write-Output "  Profile: $Profile  LAN mode: $LanMode  Host: $HostBind  Port: $Port"
    if ($requiresRelayFirewall) {
        Write-Output "  Would allow Private-profile TCP/$Port$(if ($EnableLanDiscovery) { " and UDP/$DiscoveryPort" }) only."
    } else {
        Write-Output '  No Relay firewall opening: loopback listener or separately operated reverse proxy required.'
    }
    Write-Output "  Would register and start supervised task: $TaskName"
    Write-Output "  Would verify: http://127.0.0.1:$Port/api/health"
    Write-Output '  A named local administrator must be bootstrapped separately; no default password is created.'
    return
}

if (-not (Test-IsAdministrator)) {
    throw 'Run this command once from an elevated Administrator PowerShell. No changes were made.'
}

if ($requiresRelayFirewall) {
    & $firewallScript -Port $Port -DiscoveryPort $DiscoveryPort -EnableLanDiscovery:$EnableLanDiscovery
}

$autostartArguments = @{
    Executable = $Executable
    TaskName = $TaskName
    Profile = $Profile
    LanMode = $LanMode
    HostBind = $HostBind
    Port = $Port
    GatewayId = $GatewayId
    EnableAnonymousIngress = $EnableAnonymousIngress
    EnableLanDiscovery = $EnableLanDiscovery
    EnableRemoteManagement = $EnableRemoteManagement
    SessionCookieSecure = $SessionCookieSecure
}
& $autostartScript @autostartArguments

$task = Get-ScheduledTask -TaskName $TaskName -ErrorAction Stop
if ($task.State -ne 'Running') {
    Start-ScheduledTask -TaskName $TaskName
}

$healthUri = "http://127.0.0.1:$Port/api/health"
$deadline = [DateTime]::UtcNow.AddSeconds($HealthTimeoutSeconds)
$health = $null
$taskState = $null
do {
    $taskState = (Get-ScheduledTask -TaskName $TaskName -ErrorAction Stop).State
    try {
        $candidate = Invoke-RestMethod -Uri $healthUri -Method Get -TimeoutSec 2
        if ($candidate.status -in @('ok', 'bootstrap_required')) {
            $health = $candidate
        }
    } catch {
        $health = $null
    }
    if ($health -and $taskState -eq 'Running') {
        break
    }
    Start-Sleep -Seconds 1
} while ([DateTime]::UtcNow -lt $deadline)

if (-not $health -or $taskState -ne 'Running') {
    $taskInfo = Get-ScheduledTaskInfo -TaskName $TaskName -ErrorAction SilentlyContinue
    $lastResult = if ($taskInfo) { $taskInfo.LastTaskResult } else { 'unknown' }
    throw "Gateway setup did not become healthy within ${HealthTimeoutSeconds}s (task=$taskState, lastResult=$lastResult). Check configuration and Windows Event Viewer."
}

Write-Output 'Relay PC Gateway setup: PASS (process and safe configuration boundary started)'
Write-Output "  Task: $TaskName ($taskState, supervised restart enabled)"
Write-Output "  Health: $healthUri  profile=$($health.profile)"
if ($health.status -eq 'bootstrap_required') {
    Write-Warning 'Gateway is intentionally fail-closed for operator access until a one-time named administrator is bootstrapped.'
}
if ($requiresRelayFirewall) {
    Write-Output '  Firewall: Private profile only (closed-network operator assertion required)'
} else {
    Write-Output '  Firewall: Relay did not open an inbound port.'
}
