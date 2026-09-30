$ErrorActionPreference = 'Stop'

$scriptsDirectory = Split-Path -Parent $PSScriptRoot
$files = @(
    (Join-Path $scriptsDirectory 'configure-pc-gateway-firewall.ps1'),
    (Join-Path $scriptsDirectory 'register-pc-gateway-autostart.ps1'),
    (Join-Path $scriptsDirectory 'setup-pc-gateway.ps1')
)

function Assert-Condition([bool]$Condition, [string]$Message) {
    if (-not $Condition) {
        throw "ASSERTION FAILED: $Message"
    }
}

foreach ($file in $files) {
    $tokens = $null
    $errors = $null
    [System.Management.Automation.Language.Parser]::ParseFile(
        $file,
        [ref]$tokens,
        [ref]$errors
    ) | Out-Null
    Assert-Condition ($errors.Count -eq 0) "PowerShell syntax must be valid: $file"
}

$autostart = Get-Content -LiteralPath (Join-Path $scriptsDirectory 'register-pc-gateway-autostart.ps1') -Raw
$setup = Get-Content -LiteralPath (Join-Path $scriptsDirectory 'setup-pc-gateway.ps1') -Raw
$firewall = Get-Content -LiteralPath (Join-Path $scriptsDirectory 'configure-pc-gateway-firewall.ps1') -Raw

Assert-Condition ($autostart -notmatch '\bStart-Process\b') 'Gateway launch must remain attached to the scheduled task.'
Assert-Condition ($autostart -match '\[string\]\$Profile = ''production''') 'Autostart must default to the production profile.'
Assert-Condition ($autostart -match '\[string\]\$HostBind = ''127.0.0.1''') 'Autostart must default to loopback.'
Assert-Condition ($autostart -match 'RELAY_GATEWAY_BOOTSTRAP_CLI_SECRET') 'Autostart documentation must direct named one-time bootstrap without a default password.'
Assert-Condition ($autostart -notmatch 'RELAY_GATEWAY_ADMIN_KEY_FILE') 'Production runner must not create or use the legacy shared admin key file.'
Assert-Condition ($autostart -notmatch 'RELAY_GATEWAY_ADMIN_KEY\s*=') 'Task runner must not embed a legacy admin key value.'
Assert-Condition ($autostart -match "New-ScheduledTaskPrincipal -UserId 'SYSTEM'") 'Task must run before user login.'
Assert-Condition ($autostart -match '-RestartCount 999') 'Task must retain supervised restart attempts.'
Assert-Condition ($autostart -match '-ExecutionTimeLimit \(\[TimeSpan\]::Zero\)') 'Long-running task must not have a time limit.'
Assert-Condition ($autostart -match '-AllowStartIfOnBatteries') 'Gateway must start while the fixed site is on UPS/battery.'
Assert-Condition ($autostart -match '-DontStopIfGoingOnBatteries') 'Gateway must remain running on UPS/battery.'

Assert-Condition ($setup -match "NetworkCategory -eq 'Public'") 'Setup must explicitly fail closed on a Public profile.'
Assert-Condition ($setup -match 'if \(\$DryRun\)') 'Setup must support a non-mutating dry run.'
Assert-Condition ($setup -match '& \$firewallScript') 'Setup must configure the firewall through the canonical script when LAN exposure is explicit.'
Assert-Condition ($setup -match '& \$autostartScript') 'Setup must register autostart through the canonical script.'
Assert-Condition ($setup -match 'Start-ScheduledTask') 'Setup must start the registered task.'
Assert-Condition ($setup -match 'Invoke-RestMethod') 'Setup must verify the local health endpoint.'
Assert-Condition ($setup -match 'bootstrap_required') 'Setup must identify a bootstrap-required Gateway as fail-closed, not healthy for staff use.'
Assert-Condition ($firewall -match '-Profile Private') 'Firewall exposure must remain Private-profile only.'
Assert-Condition ($firewall -match 'EnableLanDiscovery') 'Firewall must not open UDP discovery unless explicitly requested.'
Assert-Condition (
    $setup.IndexOf('$publicProfiles') -lt $setup.IndexOf('& $firewallScript')
) 'Public-profile safety gate must run before firewall mutation.'
Assert-Condition (
    $setup.IndexOf('if ($DryRun)') -lt $setup.IndexOf('if (-not (Test-IsAdministrator))')
) 'Dry run must remain available without elevation.'

$requiredSettingsParameters = @(
    'StartWhenAvailable',
    'RestartCount',
    'RestartInterval',
    'ExecutionTimeLimit',
    'MultipleInstances',
    'AllowStartIfOnBatteries',
    'DontStopIfGoingOnBatteries'
)
$availableSettingsParameters = (Get-Command New-ScheduledTaskSettingsSet -ErrorAction Stop).Parameters.Keys
foreach ($parameter in $requiredSettingsParameters) {
    Assert-Condition ($parameter -in $availableSettingsParameters) "ScheduledTasks parameter is unavailable: $parameter"
}

Write-Output 'PC Gateway setup script static tests: PASS'
