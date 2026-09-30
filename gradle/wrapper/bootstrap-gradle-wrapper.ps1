[CmdletBinding()]
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $GradleArguments
)

$ErrorActionPreference = 'Stop'

function Fail([string] $Message) {
    throw "gradle wrapper bootstrap: $Message"
}

$scriptDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
$propertiesPath = Join-Path $scriptDirectory 'gradle-wrapper.properties'
if (-not (Test-Path -LiteralPath $propertiesPath -PathType Leaf)) {
    Fail "missing $propertiesPath"
}

$properties = @{}
foreach ($line in Get-Content -LiteralPath $propertiesPath) {
    if ($line -match '^(?<name>[^#=]+)=(?<value>.*)$') {
        $properties[$Matches.name.Trim()] = $Matches.value.Trim()
    }
}

$wrapperUrl = $properties['wrapperJarUrl']
$expectedSha256 = $properties['wrapperJarSha256']
if ($wrapperUrl -notmatch '^https://raw\.githubusercontent\.com/gradle/gradle/[0-9a-f]{40}/gradle/wrapper/gradle-wrapper\.jar$') {
    Fail 'wrapperJarUrl must use an immutable Gradle GitHub raw URL'
}
if ($expectedSha256 -notmatch '^[0-9a-f]{64}$') {
    Fail 'wrapperJarSha256 must be a SHA-256 digest'
}

$gradleUserHome = if ($env:GRADLE_USER_HOME) {
    $env:GRADLE_USER_HOME
} elseif ($env:USERPROFILE) {
    Join-Path $env:USERPROFILE '.gradle'
} else {
    Fail 'USERPROFILE is not set'
}

$jarDirectory = Join-Path $gradleUserHome 'wrapper\jars'
$jarPath = Join-Path $jarDirectory "gradle-wrapper-$expectedSha256.jar"
$propertiesCachePath = Join-Path $jarDirectory "gradle-wrapper-$expectedSha256.properties"

function Get-Sha256([string] $Path) {
    $sha256 = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [System.IO.File]::ReadAllBytes($Path)
        ([System.BitConverter]::ToString($sha256.ComputeHash($bytes))).Replace('-', '').ToLowerInvariant()
    } finally {
        $sha256.Dispose()
    }
}

if ((Test-Path -LiteralPath $jarPath -PathType Leaf) -and (Get-Sha256 $jarPath) -ne $expectedSha256) {
    Remove-Item -LiteralPath $jarPath -Force
}

if (-not (Test-Path -LiteralPath $jarPath -PathType Leaf)) {
    New-Item -ItemType Directory -Path $jarDirectory -Force | Out-Null
    $temporaryPath = "$jarPath.download.$PID"
    try {
        Invoke-WebRequest -Uri $wrapperUrl -OutFile $temporaryPath
        if ((Get-Sha256 $temporaryPath) -ne $expectedSha256) {
            Fail 'wrapper JAR SHA-256 verification failed'
        }
        Move-Item -LiteralPath $temporaryPath -Destination $jarPath -Force
    } finally {
        if (Test-Path -LiteralPath $temporaryPath -PathType Leaf) {
            Remove-Item -LiteralPath $temporaryPath -Force
        }
    }
}

Copy-Item -LiteralPath $propertiesPath -Destination $propertiesCachePath -Force

$javaExecutable = if ($env:JAVA_HOME) {
    Join-Path $env:JAVA_HOME 'bin\java.exe'
} else {
    'java.exe'
}

& $javaExecutable '-Dorg.gradle.appname=gradlew' '-jar' $jarPath @GradleArguments
exit $LASTEXITCODE
