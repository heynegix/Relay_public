[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Publisher,
    [Parameter(Mandatory = $true)][string]$CertificateFile,
    [Parameter(Mandatory = $true)][string]$CertificatePassword,
    [string]$Configuration = 'Release',
    [string]$Runtime = 'win-x64',
    [string]$OutputDirectory = "$PSScriptRoot\..\artifacts\RelayPcBleBridge"
)

$ErrorActionPreference = 'Stop'
$project = Join-Path $PSScriptRoot 'Relay.PcBleBridge.csproj'
$publish = Join-Path $PSScriptRoot "bin\$Configuration\net8.0-windows10.0.19041.0\$Runtime\publish"
$stage = Join-Path $OutputDirectory 'msix-stage'
$msix = Join-Path $OutputDirectory 'Relay.PcBleBridge.msix'
$programFilesX86 = [Environment]::GetFolderPath('ProgramFilesX86')
$windowsKitBin = Join-Path $programFilesX86 'Windows Kits\10\bin'
$makeAppx = Get-ChildItem $windowsKitBin -Recurse -Filter makeappx.exe |
    Where-Object FullName -match '\\x64\\makeappx\.exe$' | Sort-Object FullName -Descending | Select-Object -First 1
$signtool = Get-ChildItem $windowsKitBin -Recurse -Filter signtool.exe |
    Where-Object FullName -match '\\x64\\signtool\.exe$' | Sort-Object FullName -Descending | Select-Object -First 1

if (-not $makeAppx -or -not $signtool) { throw 'Windows SDK makeappx.exe and signtool.exe are required.' }
if ($Publisher -eq 'CN=RELAY-REPLACE-FOR-RELEASE') { throw 'A release publisher identity is required.' }
if (-not (Test-Path -LiteralPath $CertificateFile -PathType Leaf)) { throw 'The signing certificate was not found.' }

dotnet publish $project -c $Configuration -r $Runtime --self-contained true -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true
if (Test-Path $stage) { Remove-Item -LiteralPath $stage -Recurse -Force }
New-Item -ItemType Directory -Force -Path $stage, (Join-Path $stage 'Assets') | Out-Null
Copy-Item (Join-Path $publish 'Relay.PcBleBridge.exe') (Join-Path $stage 'Relay.PcBleBridge.exe')
Copy-Item (Join-Path $PSScriptRoot 'Package.appxmanifest') (Join-Path $stage 'AppxManifest.xml')
foreach ($asset in 'StoreLogo.png', 'Square150x150Logo.png', 'Square44x44Logo.png') {
    $path = Join-Path $PSScriptRoot "Assets\$asset"
    if (-not (Test-Path -LiteralPath $path)) { throw "Missing required MSIX asset: $path" }
    Copy-Item -LiteralPath $path -Destination (Join-Path $stage "Assets\$asset")
}

[xml]$manifest = Get-Content (Join-Path $stage 'AppxManifest.xml')
$identity = $manifest.Package.Identity
$identity.Publisher = $Publisher
$manifest.Save((Join-Path $stage 'AppxManifest.xml'))
if (Test-Path $msix) { Remove-Item -LiteralPath $msix -Force }
& $makeAppx.FullName pack /d $stage /p $msix /o
& $signtool.FullName sign /fd SHA256 /f $CertificateFile /p $CertificatePassword $msix
Write-Output $msix
