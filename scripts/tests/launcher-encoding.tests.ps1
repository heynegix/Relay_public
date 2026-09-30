# CP932 / ASCII regression test for the Windows PC Gateway launchers.
# Run: powershell -ExecutionPolicy Bypass -File .\scripts\tests\launcher-encoding.tests.ps1
#
# Windows PowerShell 5.1 parses BOM-less scripts using the system ANSI code page (CP932 on
# Japanese Windows). Any non-ASCII byte (>= 0x80) is misread and breaks the launcher with
# UnexpectedToken errors. These launchers must stay ASCII-only. This test fails if a non-ASCII
# byte is reintroduced, or if the content does not parse cleanly under both CP932 and UTF-8.
#
# Compatible with Windows PowerShell 5.1 and PowerShell 7.
$ErrorActionPreference = 'Stop'

$Root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
Import-Module (Join-Path $Root 'scripts\test-lib\RelayEncoding.psm1') -Force

$failed = 0

function Assert-True($cond, $msg) {
    if (-not $cond) {
        Write-Host "FAIL: $msg" -ForegroundColor Red
        $script:failed++
    } else {
        Write-Host "PASS: $msg"
    }
}

# The launchers that Windows PowerShell 5.1 executes directly and that must remain ASCII-only.
$targets = @(
    (Join-Path $Root 'scripts\start-pc-gateway-development.ps1')
)

foreach ($target in $targets) {
    $name = Split-Path -Leaf $target
    $r = Test-RelayScriptEncoding -Path $target -IncludePackagedCopies $true

    Assert-True ($r.exists) "$name exists"
    Assert-True ($r.nonAsciiByteCount -eq 0) "$name has no byte >= 0x80 (found $($r.nonAsciiByteCount), bytes=$($r.byteCount), bom=$($r.hasBom))"
    Assert-True ($r.cp932ParseErrors.Count -eq 0) "$name parses under CP932 with 0 errors (found $($r.cp932ParseErrors.Count))"
    Assert-True ($r.utf8ParseErrors.Count -eq 0) "$name parses under UTF-8 with 0 errors (found $($r.utf8ParseErrors.Count))"

    if ($r.packagedCopyChecked) {
        Assert-True ($r.packagedCopyStatus -eq 'PASS') "$name packaged distribution copy is ASCII-only and parses ($($r.packagedCopyStatus) $($r.packagedCopyReason))"
    } else {
        Write-Host "SKIP: $name packaged distribution copy not present ($($r.packagedCopyReason)); source-only check applied"
    }
}

if ($failed -gt 0) {
    Write-Host "$failed assertion(s) failed" -ForegroundColor Red
    exit 1
}
Write-Host 'All CP932/ASCII launcher encoding assertions passed'
exit 0
