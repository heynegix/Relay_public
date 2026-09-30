# Structural + behavioral checks for the one-click PC Gateway launcher path.
# Run: powershell -ExecutionPolicy Bypass -File .\scripts\tests\run-pc-gateway-launcher.tests.ps1
$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$failed = 0

function Assert-True($cond, $msg) {
    if (-not $cond) {
        Write-Host "FAIL: $msg" -ForegroundColor Red
        $script:failed++
    } else {
        Write-Host "PASS: $msg"
    }
}

$cmd = Join-Path $Root 'Start-PC-Gateway.cmd'
$ps1 = Join-Path $Root 'scripts\run-pc-gateway.ps1'
$sh = Join-Path $Root 'scripts\run-pc-gateway.sh'
$developmentPs1 = Join-Path $Root 'scripts\start-pc-gateway-development.ps1'
$developmentCmd = Join-Path $Root 'scripts\Start-Relay-PC-Gateway-Development.cmd'

Assert-True (Test-Path -LiteralPath $cmd) 'Start-PC-Gateway.cmd exists at repo root (double-click entry)'
Assert-True (Test-Path -LiteralPath $ps1) 'scripts/run-pc-gateway.ps1 exists'
Assert-True (Test-Path -LiteralPath $sh) 'scripts/run-pc-gateway.sh exists (Mac/Linux parity)'
Assert-True (Test-Path -LiteralPath $developmentPs1) 'development preview launcher exists'
Assert-True (Test-Path -LiteralPath $developmentCmd) 'development preview double-click launcher exists'

$cmdText = Get-Content -LiteralPath $cmd -Raw
Assert-True ($cmdText -match 'run-pc-gateway\.ps1') 'Start-PC-Gateway.cmd invokes run-pc-gateway.ps1'

$ps1Text = Get-Content -LiteralPath $ps1 -Raw
Assert-True ($ps1Text -match 'installDist') 'launcher builds installDist when missing'
Assert-True ($ps1Text -match 'pc-gateway\.bat') 'launcher starts real installDist entry (pc-gateway.bat)'
Assert-True ($ps1Text -match 'RELAY_GATEWAY_DB') 'launcher sets DB under user profile defaults'
Assert-True ($ps1Text -match 'api/health') 'launcher documents health URL'
Assert-True ($ps1Text -match "RELAY_PROFILE = 'production'") 'launcher explicitly defaults to production profile'
Assert-True ($ps1Text -match "127\.0\.0\.1") 'launcher default bind is loopback'
Assert-True ($ps1Text -match 'bootstrap-admin') 'launcher documents named administrator bootstrap'

$developmentText = Get-Content -LiteralPath $developmentPs1 -Raw
$developmentCmdText = Get-Content -LiteralPath $developmentCmd -Raw
Assert-True ($developmentText -match "RELAY_PROFILE = 'development'") 'development launcher explicitly isolates the development profile'
Assert-True ($developmentText -match 'Read-Host') 'development launcher prompts for local administrator credentials'
Assert-True ($developmentText -match 'SecureStringToBSTR') 'development launcher handles bootstrap password without a command-line argument'
Assert-True ($developmentText -match 'Remove-Item Env:RELAY_GATEWAY_BOOTSTRAP_CLI_SECRET') 'development launcher clears the bootstrap secret environment variable'
Assert-True ($developmentText -match 'Get-NetTCPConnection') 'development launcher avoids starting a duplicate process on an occupied port'
Assert-True ($developmentText -match 'RelayPcGateway\\RelayPcGateway\.exe') 'development launcher locates the installed executable without a hard-coded user profile'
Assert-True ($developmentText -match "RELAY_GATEWAY_REMOTE_MANAGEMENT = 'false'") 'development launcher keeps staff management loopback-only'
Assert-True ($developmentText -match "RELAY_GATEWAY_ENABLE_LEGACY_ADMIN_KEY = 'false'") 'development launcher uses only named username/password login'
Assert-True ($developmentText -match 'RELAY_BLE_BRIDGE_SECRET_FILE') 'development launcher isolates BLE-sidecar material from other profiles'
Assert-True ($developmentCmdText -match 'start-pc-gateway-development\.ps1') 'development cmd invokes the development PowerShell launcher'

# Parser / param surface: -SkipBuild and -NoBrowser must be accepted by the script AST.
$errors = $null
$tokens = $null
[System.Management.Automation.Language.Parser]::ParseFile($ps1, [ref]$tokens, [ref]$errors) | Out-Null
Assert-True ($null -eq $errors -or $errors.Count -eq 0) 'run-pc-gateway.ps1 parses without syntax errors'
$errors = $null
$tokens = $null
[System.Management.Automation.Language.Parser]::ParseFile($developmentPs1, [ref]$tokens, [ref]$errors) | Out-Null
Assert-True ($null -eq $errors -or $errors.Count -eq 0) 'start-pc-gateway-development.ps1 parses without syntax errors'
Assert-True ($ps1Text -match 'NoBrowser') 'supports -NoBrowser'
Assert-True ($ps1Text -match 'SkipBuild') 'supports -SkipBuild'

if ($failed -gt 0) {
    Write-Host "$failed assertion(s) failed" -ForegroundColor Red
    exit 1
}
Write-Host 'All launcher assertions passed'
exit 0
