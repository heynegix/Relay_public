[CmdletBinding()]
param(
    [string]$ReleaseKeystore = $env:RELAY_RELEASE_KEYSTORE,
    [string]$ReleaseAlias = $env:RELAY_RELEASE_ALIAS,
    [string]$ReleaseStorePassword = $env:RELAY_RELEASE_STORE_PASSWORD,
    [string]$ReleaseKeyPassword = $env:RELAY_RELEASE_KEY_PASSWORD,
    [string]$OutputDirectory
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$OutputDirectory = if ($OutputDirectory) { $OutputDirectory } else { Join-Path $root 'distribution\Relay-User-Package\Android' }
$gradle = Join-Path $root 'gradlew.bat'
$apksigner = Get-ChildItem "$env:LOCALAPPDATA\Android\Sdk\build-tools" -Recurse -Filter apksigner.bat -ErrorAction SilentlyContinue |
    Sort-Object FullName -Descending | Select-Object -First 1
if (-not $apksigner) { throw 'Android SDK build-tools/apksigner.bat was not found.' }
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null

Push-Location $root
try { & $gradle ':app:assembleDebug' ':app:assembleRelease' '--no-daemon' '--console=plain' } finally { Pop-Location }

Copy-Item (Join-Path $root 'app\build\outputs\apk\debug\app-debug.apk') (Join-Path $OutputDirectory 'Relay-debug.apk') -Force
$unsigned = Join-Path $OutputDirectory 'Relay-release-unsigned.apk'
Copy-Item (Join-Path $root 'app\build\outputs\apk\release\app-release-unsigned.apk') $unsigned -Force

if ([string]::IsNullOrWhiteSpace($ReleaseKeystore)) {
    Write-Warning 'No official release keystore supplied; leaving release APK unsigned.'
    exit 0
}
if (-not (Test-Path -LiteralPath $ReleaseKeystore -PathType Leaf)) { throw 'Release keystore does not exist.' }
if ([string]::IsNullOrWhiteSpace($ReleaseAlias) -or [string]::IsNullOrWhiteSpace($ReleaseStorePassword) -or [string]::IsNullOrWhiteSpace($ReleaseKeyPassword)) {
    throw 'Release alias and passwords are required when a release keystore is supplied.'
}
$signed = Join-Path $OutputDirectory 'Relay-release-signed.apk'
& $apksigner.FullName sign --ks $ReleaseKeystore --ks-key-alias $ReleaseAlias --ks-pass "pass:$ReleaseStorePassword" --key-pass "pass:$ReleaseKeyPassword" --out $signed $unsigned
& $apksigner.FullName verify --verbose $signed
Write-Output "Signed release APK: $signed"
