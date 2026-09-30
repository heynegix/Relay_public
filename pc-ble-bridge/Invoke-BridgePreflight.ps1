[CmdletBinding()]
param(
    [switch]$RequireReleaseIdentity
)

$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
$manifestPath = Join-Path $projectRoot 'Package.appxmanifest'
$failures = [System.Collections.Generic.List[string]]::new()

function Add-Failure([string]$message) {
    $script:failures.Add($message)
}

if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) {
    Add-Failure 'The BLE bridge can only be built and deployed on Windows.'
}

$dotnet = Get-Command dotnet -ErrorAction SilentlyContinue
if ($null -eq $dotnet) {
    Add-Failure 'The .NET SDK is not installed. Install a supported .NET 8 SDK before building.'
} else {
    $sdks = @(& $dotnet.Source --list-sdks 2>$null)
    if ($LASTEXITCODE -ne 0 -or $sdks.Count -eq 0) {
        Add-Failure 'The dotnet host is present but no .NET SDK is installed. A runtime alone cannot restore or build this project.'
    } elseif (-not ($sdks | Where-Object { $_ -match '^8\.' })) {
        Add-Failure 'A .NET 8 SDK is required by Relay.PcBleBridge.csproj.'
    }
}

if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
    Add-Failure 'Package.appxmanifest is missing.'
} else {
    [xml]$manifest = Get-Content -LiteralPath $manifestPath -Raw
    $identity = $manifest.Package.Identity
    if ($RequireReleaseIdentity -and ($identity.Publisher -match 'REPLACE' -or $identity.Name -match 'REPLACE')) {
        Add-Failure 'Package.appxmanifest still uses the release placeholder identity. Set the organization publisher before packaging.'
    }

    $namespaceManager = [System.Xml.XmlNamespaceManager]::new($manifest.NameTable)
    $namespaceManager.AddNamespace('foundation', 'http://schemas.microsoft.com/appx/manifest/foundation/windows10')
    $namespaceManager.AddNamespace('uap', 'http://schemas.microsoft.com/appx/manifest/uap/windows10')
    $visualElements = $manifest.SelectSingleNode('/foundation:Package/foundation:Applications/foundation:Application/uap:VisualElements', $namespaceManager)
    $assetPaths = @(
        $manifest.Package.Properties.Logo,
        $visualElements.GetAttribute('Square150x150Logo'),
        $visualElements.GetAttribute('Square44x44Logo')
    ) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
    foreach ($assetPath in $assetPaths) {
        if (-not (Test-Path -LiteralPath (Join-Path $projectRoot $assetPath) -PathType Leaf)) {
            Add-Failure "MSIX visual asset is missing: $assetPath"
        }
    }
}

if ($failures.Count -gt 0) {
    $failures | ForEach-Object { [Console]::Error.WriteLine("ERROR: $_") }
    exit 1
}

Write-Host 'Relay PC BLE Bridge preflight passed.'
