[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^\d+\.\d+\.\d+$')]
    [string]$AppVersion
)

$ErrorActionPreference = 'Stop'

$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$gradlew = Join-Path $root 'gradlew.bat'
$inputDir = Join-Path $root 'pc-gateway\build\jpackage-input'
$outputDir = Join-Path $root 'artifacts\pc-gateway-exe'
$finalExe = Join-Path $root 'artifacts\relay-pc-gateway.exe'
$developmentLauncher = Join-Path $root 'scripts\start-pc-gateway-development.ps1'
$developmentLauncherCmd = Join-Path $root 'scripts\Start-Relay-PC-Gateway-Development.cmd'
$brokerTunnelLauncher = Join-Path $root 'scripts\Start-Relay-Broker-Tunnel-Development.ps1'
$brokerTunnelLauncherCmd = Join-Path $root 'scripts\Start-Relay-Broker-Tunnel-Development.cmd'
$brokerBundle = Join-Path $root 'artifacts\relay-broker-bundle.zip'
$wixLocal = Join-Path $root 'tools\wix314'
$jdkBin = 'C:\Program Files\Java\jdk-17\bin'

# Prefer project-local WiX binaries and full JDK 17 for jpackage.
if (Test-Path $wixLocal) {
    $env:Path = "$wixLocal;$env:Path"
}
if (Test-Path $jdkBin) {
    $env:Path = "$jdkBin;$env:Path"
}

$jpackage = Get-Command jpackage.exe -ErrorAction SilentlyContinue
if (-not $jpackage) {
    throw 'jpackage.exe is required. Install a full JDK 17+ and add its bin directory to PATH.'
}
if (-not (Get-Command candle.exe -ErrorAction SilentlyContinue) -or -not (Get-Command light.exe -ErrorAction SilentlyContinue)) {
    throw @"
WiX Toolset 3.x (candle.exe and light.exe) is required for Windows EXE packaging.
Expected local binaries at: $wixLocal
Download: https://github.com/wixtoolset/wix3/releases/download/wix3141rtm/wix314-binaries.zip
"@
}

Write-Output "jpackage: $($jpackage.Source)"
Write-Output "candle: $((Get-Command candle.exe).Source)"
Write-Output "light: $((Get-Command light.exe).Source)"

& $gradlew ':pc-gateway:installDist' ':broker:installDist' '--no-daemon' '--console=plain'
if ($LASTEXITCODE -ne 0) { throw "installDist failed with exit $LASTEXITCODE" }

New-Item -ItemType Directory -Force -Path $inputDir, $outputDir, (Split-Path $finalExe) | Out-Null
Get-ChildItem (Join-Path $root 'pc-gateway\build\install\pc-gateway\lib') -File | Copy-Item -Destination $inputDir -Force

# Stop a previous gateway that may lock the destination EXE.
Get-Process -Name 'relay-pc-gateway','RelayPcGateway' -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 1

# Keep the name and vendor stable: published installers derive their Windows upgrade identity
# from this pair. The release version must change so Windows performs a major upgrade.
& $jpackage.Source --type exe --name RelayPcGateway --app-version $AppVersion `
    --input $inputDir --main-jar pc-gateway.jar `
    --main-class com.example.relay.pcgateway.MainKt --dest $outputDir `
    --win-console --win-menu --vendor Relay `
    --description 'Relay offline PC Gateway' `
    --java-options '-cp $APPDIR\*' `
    --java-options "-Drelay.version=$AppVersion"

$built = Join-Path $outputDir "RelayPcGateway-$AppVersion.exe"
if (-not (Test-Path $built)) { throw "jpackage did not create $built" }

try {
    Copy-Item $built $finalExe -Force
} catch {
    $fallback = Join-Path $root 'artifacts\relay-pc-gateway-updated.exe'
    Copy-Item $built $fallback -Force
    Write-Warning "Could not overwrite $finalExe (file locked). Wrote $fallback instead."
    $finalExe = $fallback
}

$hash = Get-FileHash $finalExe -Algorithm SHA256
$hash.Hash | Set-Content (Join-Path $root 'artifacts\relay-pc-gateway.exe.sha256')
# The preview launcher is a separate companion asset. It discovers the installed EXE by the
# documented Program Files path, so it works whether it is run from the download folder or copied
# beside the installed app. Formal-release publishing intentionally selects only the signed EXE.
Copy-Item $developmentLauncher (Join-Path $root 'artifacts\Relay-PC-Gateway-development.ps1') -Force
Copy-Item $developmentLauncherCmd (Join-Path $root 'artifacts\Start-Relay-PC-Gateway-Development.cmd') -Force

# Broker + Quick Tunnel bundle. The standalone launcher runs the Broker from the public
# eclipse-temurin image with these classes bind-mounted, so a phone on mobile data can reach
# this PC without a repository clone. Ship the launcher and the broker installDist lib together.
$brokerLib = Join-Path $root 'broker\build\install\broker\lib'
if (-not (Test-Path $brokerLib)) { throw "broker installDist output is missing: $brokerLib" }
if (Test-Path $brokerBundle) { Remove-Item $brokerBundle -Force }
Compress-Archive -Path (Join-Path $brokerLib '*') -DestinationPath $brokerBundle -Force
Copy-Item $brokerTunnelLauncher (Join-Path $root 'artifacts\Start-Relay-Broker-Tunnel-Development.ps1') -Force
Copy-Item $brokerTunnelLauncherCmd (Join-Path $root 'artifacts\Start-Relay-Broker-Tunnel-Development.cmd') -Force
Write-Output "Created $finalExe"
Write-Output "Size bytes: $((Get-Item $finalExe).Length)"
Write-Output "SHA-256: $($hash.Hash)"
Write-Output "This is a Windows installer (jpackage/WiX). Install then run RelayPcGateway from the Start Menu or Program Files."
Write-Output 'Development preview companion: artifacts\Start-Relay-PC-Gateway-Development.cmd'
Write-Output 'Broker tunnel companion: artifacts\Start-Relay-Broker-Tunnel-Development.cmd + artifacts\relay-broker-bundle.zip'
