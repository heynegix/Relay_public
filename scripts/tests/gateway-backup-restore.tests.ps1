<#
.SYNOPSIS
  Real backup/restore round-trip test for the PC Gateway database.

.DESCRIPTION
  Exercises the production scripts/backup-gateway.ps1 end to end:
    1. Seed a temporary SQLite gateway database with a known marker row set (via python sqlite3).
    2. Backup: litestream file replica -> tar -> age encryption -> checksum manifest.
    3. Restore: checksum verify -> age decrypt -> untar -> litestream restore -> fresh database.
    4. Verify the restored database contains the exact marker rows seeded in step 1.

  This is a true round trip through the real backup script, not a mocked copy.

  Exit codes (contract used by validate-windows-development.ps1):
    0 = PASS    round trip completed and restored data verified
    1 = FAIL    a tool was present but the round trip or verification failed
    2 = BLOCKED required external tooling (litestream and/or age) is unavailable

  On Windows this currently reports BLOCKED: the official litestream release ships no Windows
  binary and age has no checksum-verifiable install path here. The same test runs fully on a
  Linux release toolchain where litestream and age are provisioned.

.PARAMETER Python
  Python interpreter used to seed/verify the SQLite database. Default: python

.EXAMPLE
  .\scripts\tests\gateway-backup-restore.tests.ps1
#>
[CmdletBinding()]
param(
    [string]$Python = 'python'
)

$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$backupScript = Join-Path $Root 'scripts\backup-gateway.ps1'

function Resolve-Tool([string]$Name) {
    # Honour the isolated install dir used by scripts/install-security-tools.ps1.
    if ($env:RELAY_SECURITY_TOOL_DIR) {
        $cand = Join-Path $env:RELAY_SECURITY_TOOL_DIR "$Name.exe"
        if (Test-Path -LiteralPath $cand -PathType Leaf) { return $cand }
        $cand = Join-Path $env:RELAY_SECURITY_TOOL_DIR $Name
        if (Test-Path -LiteralPath $cand -PathType Leaf) { return $cand }
    }
    $cmd = Get-Command $Name -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    return $null
}

$litestream = Resolve-Tool 'litestream'
$age = Resolve-Tool 'age'
$ageKeygen = Resolve-Tool 'age-keygen'

$missing = @()
if (-not $litestream) { $missing += 'litestream (official release ships no Windows binary)' }
if (-not $age) { $missing += 'age' }
if (-not $ageKeygen) { $missing += 'age-keygen' }
if ($missing.Count -gt 0) {
    Write-Host "[BLOCKED] gateway-backup-restore: missing tooling: $($missing -join '; ')"
    Write-Host "[BLOCKED] Provision litestream + age (Linux release toolchain) to run this round trip."
    exit 2
}

# Make the resolved tools visible to backup-gateway.ps1, which resolves them via PATH/Get-Command.
$toolDirs = @()
foreach ($t in @($litestream, $age, $ageKeygen)) {
    $d = Split-Path $t -Parent
    if ($toolDirs -notcontains $d) { $toolDirs += $d }
}
$env:PATH = ($toolDirs -join ';') + ';' + $env:PATH

$work = Join-Path ([IO.Path]::GetTempPath()) ('relay-gw-roundtrip-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $work | Out-Null
$sourceDb = Join-Path $work 'gateway.db'
$restoredDb = Join-Path $work 'restored.db'
$backupRoot = Join-Path $work 'backups'
$identity = Join-Path $work 'identity.key'
$seedPy = Join-Path $work 'seed.py'
$verifyPy = Join-Path $work 'verify.py'

# Deterministic marker rows the restore must reproduce exactly.
$marker = 'relay-roundtrip-' + [guid]::NewGuid().ToString('N')
$rowCount = 5

Set-Content -LiteralPath $seedPy -Encoding UTF8 -Value @'
import sqlite3, sys
db, marker, n = sys.argv[1], sys.argv[2], int(sys.argv[3])
c = sqlite3.connect(db)
c.execute("create table messages (id text primary key, payload_hash text not null)")
for i in range(n):
    c.execute("insert into messages values (?, ?)", (f"{marker}-{i}", marker))
c.commit(); c.close()
'@

Set-Content -LiteralPath $verifyPy -Encoding UTF8 -Value @'
import sqlite3, sys
db, marker, n = sys.argv[1], sys.argv[2], int(sys.argv[3])
c = sqlite3.connect(db)
total = c.execute("select count(*) from messages").fetchone()[0]
matched = c.execute("select count(*) from messages where payload_hash = ?", (marker,)).fetchone()[0]
c.close()
ok = (total == n) and (matched == n)
print(f"total={total} matched={matched} expected={n}")
sys.exit(0 if ok else 1)
'@

$exitCode = 1
try {
    # 1. Seed source database.
    & $Python $seedPy $sourceDb $marker $rowCount
    if ($LASTEXITCODE -ne 0) { throw "failed to seed source database" }

    # 2. Generate an age identity and derive its recipient (public key).
    & $ageKeygen -o $identity 2>$null
    if ($LASTEXITCODE -ne 0) { throw "age-keygen failed" }
    $recipient = $null
    foreach ($line in (Get-Content -LiteralPath $identity)) {
        if ($line -match '^#\s*public key:\s*(.+)$') { $recipient = $Matches[1].Trim() }
    }
    if (-not $recipient) { throw "could not derive age recipient from identity" }

    # 3. Backup via the production script.
    & $backupScript -Action Backup -DatabasePath $sourceDb -BackupRoot $backupRoot `
        -AgeRecipient $recipient -Litestream $litestream -SnapshotSeconds 3
    if ($LASTEXITCODE -ne 0) { throw "backup-gateway.ps1 -Action Backup failed" }

    $archive = Get-ChildItem -LiteralPath $backupRoot -Filter '*.tar.age' | Select-Object -First 1
    if (-not $archive) { throw "no encrypted backup archive produced" }
    if (-not (Test-Path -LiteralPath ($archive.FullName + '.json'))) { throw "backup checksum manifest missing" }

    # 4. Restore via the production script to a fresh database path.
    & $backupScript -Action Restore -InputArchive $archive.FullName -AgeIdentity $identity `
        -DatabasePath $restoredDb -Litestream $litestream
    if ($LASTEXITCODE -ne 0) { throw "backup-gateway.ps1 -Action Restore failed" }
    if (-not (Test-Path -LiteralPath $restoredDb -PathType Leaf)) { throw "restored database not created" }

    # 5. Verify restored content matches the seeded marker rows exactly.
    & $Python $verifyPy $restoredDb $marker $rowCount
    if ($LASTEXITCODE -ne 0) { throw "restored database content mismatch" }

    Write-Host "[PASS] gateway-backup-restore round trip: backup -> encrypt -> restore -> verified ($rowCount rows)"
    $exitCode = 0
} catch {
    Write-Host "[FAIL] gateway-backup-restore: $($_.Exception.Message)" -ForegroundColor Red
    $exitCode = 1
} finally {
    Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue
}
exit $exitCode
