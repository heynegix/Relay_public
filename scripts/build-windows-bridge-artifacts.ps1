[CmdletBinding()]
param(
    [string]$Publisher = $env:RELAY_MSIX_PUBLISHER,
    [string]$CertificateFile = $env:RELAY_MSIX_CERTIFICATE,
    [string]$CertificatePassword = $env:RELAY_MSIX_CERTIFICATE_PASSWORD,
    [string]$OutputDirectory
)

$ErrorActionPreference = 'Stop'
$project = Join-Path $PSScriptRoot '..\pc-ble-bridge\Relay.PcBleBridge.csproj'
$bridgeScript = Join-Path $PSScriptRoot '..\pc-ble-bridge\New-BridgeMsix.ps1'
$OutputDirectory = if ($OutputDirectory) { $OutputDirectory } else { Join-Path (Join-Path $PSScriptRoot '..') 'distribution\Relay-User-Package\Windows' }
$resolvedOutput = (Resolve-Path $OutputDirectory).Path

dotnet build $project -c Release
dotnet publish $project -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true
dotnet publish $project -c Debug -r win-x64 --self-contained true -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true

$releasePublish = Join-Path $PSScriptRoot '..\pc-ble-bridge\bin\Release\net8.0-windows10.0.19041.0\win-x64\publish'
$debugPublish = Join-Path $PSScriptRoot '..\pc-ble-bridge\bin\Debug\net8.0-windows10.0.19041.0\win-x64\publish'
New-Item -ItemType Directory -Force (Join-Path $resolvedOutput 'BleBridge-Release'), (Join-Path $resolvedOutput 'BleBridge-Debug') | Out-Null
Copy-Item (Join-Path $releasePublish 'Relay.PcBleBridge.exe') (Join-Path $resolvedOutput 'BleBridge-Release\Relay.PcBleBridge.exe') -Force
Copy-Item (Join-Path $debugPublish 'Relay.PcBleBridge.exe') (Join-Path $resolvedOutput 'BleBridge-Debug\Relay.PcBleBridge.exe') -Force

if ($Publisher -and $CertificateFile -and $CertificatePassword) {
    & powershell -NoProfile -ExecutionPolicy Bypass -File $bridgeScript -Publisher $Publisher -CertificateFile $CertificateFile -CertificatePassword $CertificatePassword -Configuration Release -OutputDirectory $resolvedOutput
    $debugOutput = Join-Path $resolvedOutput 'Debug'
    New-Item -ItemType Directory -Force $debugOutput | Out-Null
    & powershell -NoProfile -ExecutionPolicy Bypass -File $bridgeScript -Publisher $Publisher -CertificateFile $CertificateFile -CertificatePassword $CertificatePassword -Configuration Debug -OutputDirectory $debugOutput
    Copy-Item (Join-Path $debugOutput 'Relay.PcBleBridge.msix') (Join-Path $debugOutput 'Relay.PcBleBridge-debug-local-signed.msix') -Force
} else {
    Write-Warning 'MSIX signing inputs are absent. Executables were built, but signed MSIX packages were not generated.'
}
