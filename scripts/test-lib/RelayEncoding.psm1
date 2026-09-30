# Relay encoding guard for Windows PowerShell launchers.
#
# Windows PowerShell 5.1 reads a script that has no byte-order mark using the system ANSI code
# page. On Japanese Windows that is CP932 (Shift-JIS), so any UTF-8 multibyte sequence (for
# example Japanese text) is mis-parsed and produces UnexpectedToken parse failures. The durable
# fix adopted by this repository is ASCII-only launcher content. This module verifies that
# guarantee so a future edit cannot silently reintroduce non-ASCII bytes.
#
# The module itself is ASCII-only and uses only Windows PowerShell 5.1 compatible constructs.

Set-StrictMode -Version Latest

function Test-RelayScriptEncoding {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path,
        [bool]$IncludePackagedCopies = $false
    )

    $result = [ordered]@{
        path                 = $Path
        exists               = $false
        status               = 'NOT_RUN'
        reason               = $null
        byteCount            = 0
        hasBom               = $false
        nonAsciiByteCount    = 0
        firstNonAsciiOffset  = $null
        cp932ParseErrors     = $null
        utf8ParseErrors      = $null
        packagedCopyChecked  = $false
        packagedCopyStatus   = 'NOT_RUN'
        packagedCopyReason   = $null
    }

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        $result.status = 'BLOCKED'
        $result.reason = 'file-missing'
        return [pscustomobject]$result
    }
    $result.exists = $true

    $bytes = [System.IO.File]::ReadAllBytes($Path)
    $result.byteCount = $bytes.Length
    $result.hasBom = ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF)

    # Requirement: no byte >= 0x80 may exist anywhere in the file.
    $nonAscii = 0
    $firstOffset = $null
    for ($i = 0; $i -lt $bytes.Length; $i++) {
        if ($bytes[$i] -ge 0x80) {
            $nonAscii++
            if ($null -eq $firstOffset) { $firstOffset = $i }
        }
    }
    $result.nonAsciiByteCount = $nonAscii
    $result.firstNonAsciiOffset = $firstOffset

    # Parse the same bytes the way Windows PowerShell 5.1 would on Japanese Windows (CP932) and
    # the way UTF-8 tooling would. Both must yield zero syntax errors.
    $cp932 = [System.Text.Encoding]::GetEncoding(932)
    $utf8 = New-Object System.Text.UTF8Encoding($false, $false)

    $cpText = $cp932.GetString($bytes)
    $cpErrors = $null
    [System.Management.Automation.Language.Parser]::ParseInput($cpText, [ref]$null, [ref]$cpErrors) | Out-Null
    $result.cp932ParseErrors = @($cpErrors)

    $u8Text = $utf8.GetString($bytes)
    $u8Errors = $null
    [System.Management.Automation.Language.Parser]::ParseInput($u8Text, [ref]$null, [ref]$u8Errors) | Out-Null
    $result.utf8ParseErrors = @($u8Errors)

    $ok = ($nonAscii -eq 0) -and ($result.cp932ParseErrors.Count -eq 0) -and ($result.utf8ParseErrors.Count -eq 0)
    $result.status = if ($ok) { 'PASS' } else { 'FAIL' }
    if (-not $ok) {
        if ($nonAscii -gt 0) {
            $result.reason = "non-ascii-bytes=$nonAscii firstOffset=$firstOffset"
        } elseif ($result.cp932ParseErrors.Count -gt 0) {
            $result.reason = "cp932-parse-errors=$($result.cp932ParseErrors.Count)"
        } else {
            $result.reason = "utf8-parse-errors=$($result.utf8ParseErrors.Count)"
        }
    }

    # The build copies the development launcher into artifacts/ for distribution. Those copies
    # must satisfy the identical ASCII + parse guarantees when they are present.
    if ($IncludePackagedCopies) {
        $fileName = [System.IO.Path]::GetFileName($Path)
        $root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
        $copy = Join-Path (Join-Path $root 'artifacts') $fileName
        if (Test-Path -LiteralPath $copy -PathType Leaf) {
            $copyResult = Test-RelayScriptEncoding -Path $copy
            $result.packagedCopyChecked = $true
            $result.packagedCopyStatus = $copyResult.status
            $result.packagedCopyReason = $copyResult.reason
            if ($copyResult.status -ne 'PASS') {
                $result.status = 'FAIL'
                if (-not $result.reason) { $result.reason = 'packaged-copy-not-ascii' }
            }
        } else {
            $result.packagedCopyStatus = 'SKIPPED'
            $result.packagedCopyReason = 'packaged-copy-absent'
        }
    }

    return [pscustomobject]$result
}

Export-ModuleMember -Function Test-RelayScriptEncoding
