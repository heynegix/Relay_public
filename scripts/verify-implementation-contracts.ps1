param([string]$DenyFile = '')
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$arguments = @((Join-Path $PSScriptRoot 'verify-public-source.py'), '--root', $root)
if ($DenyFile) { $arguments += @('--deny-file', $DenyFile) }
python @arguments
if ($LASTEXITCODE -ne 0) { throw 'Public source verification failed.' }
