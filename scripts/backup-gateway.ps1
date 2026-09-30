[CmdletBinding()]
param([ValidateSet('Backup','Restore')][string]$Action='Backup',[string]$DatabasePath=$env:RELAY_GATEWAY_DB,[string]$BackupRoot=(Join-Path $HOME '.relay\backups'),[string[]]$AgeRecipient,[string]$AgeIdentity,[string]$InputArchive,[string]$InputManifest,[string]$Litestream='litestream',[int]$SnapshotSeconds=8,[switch]$DryRun)
$ErrorActionPreference='Stop'
if (-not $DatabasePath) { $DatabasePath=Join-Path $HOME '.relay\relay-gateway.db' }
function Need($n) { if (-not (Get-Command $n -ErrorAction SilentlyContinue)) { throw "Required tool not found: $n" } }
if ($Action -eq 'Backup') {
  if (-not (Test-Path $DatabasePath -PathType Leaf)) { throw "Gateway database not found: $DatabasePath" }; if (-not $AgeRecipient) { throw 'AgeRecipient is required.' }; Need $Litestream; Need age
  $stamp=(Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ'); New-Item -ItemType Directory -Force -Path $BackupRoot | Out-Null; $out=Join-Path $BackupRoot "relay-gateway-$stamp.tar.age"; $w=Join-Path ([IO.Path]::GetTempPath()) ('relay-backup-'+[guid]::NewGuid()); $r=Join-Path $w 'replica'; New-Item -ItemType Directory -Force $r | Out-Null
  try { if ($DryRun) { "DRY RUN: Litestream replica + age encryption -> $out"; return }; $p=Start-Process $Litestream -ArgumentList @('replicate',$DatabasePath,"file://$r") -PassThru -WindowStyle Hidden; Start-Sleep ([Math]::Max(1,$SnapshotSeconds)); if (-not $p.HasExited) { Stop-Process $p.Id -Force }; $tar=Join-Path $w 'replica.tar'; tar -cf $tar -C $w replica; $ageArgs=@(); foreach($recipient in $AgeRecipient){ $ageArgs += @('-r',$recipient) }; $ageArgs += @('-o',$out,$tar); & age @ageArgs; if ($LASTEXITCODE -ne 0) { throw 'age encryption failed.' }; $h=(Get-FileHash $out -Algorithm SHA256).Hash.ToLowerInvariant(); @{schema=1;createdUtc=$stamp;sha256=$h;source='litestream-file-replica';encryptedWith='age';recipientCount=$AgeRecipient.Count} | ConvertTo-Json | Set-Content ($out+'.json'); "Gateway backup created: $out" } finally { Remove-Item $w -Recurse -Force -ErrorAction SilentlyContinue }; return
}
if (-not $InputArchive -or -not $AgeIdentity) { throw 'InputArchive and AgeIdentity are required for restore.' }; Need age; Need $Litestream
$manifestPath=if($InputManifest){$InputManifest}else{"$InputArchive.json"}
if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) { throw "Backup checksum manifest not found: $manifestPath" }
$manifest=Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
$actualHash=(Get-FileHash -LiteralPath $InputArchive -Algorithm SHA256).Hash.ToLowerInvariant()
if ($actualHash -ne ([string]$manifest.sha256).ToLowerInvariant()) { throw 'Backup checksum mismatch.' }
$w=Join-Path ([IO.Path]::GetTempPath()) ('relay-restore-'+[guid]::NewGuid()); New-Item -ItemType Directory -Force $w | Out-Null
try { $tar=Join-Path $w 'replica.tar'; age --decrypt --identity $AgeIdentity --output $tar $InputArchive; if ($LASTEXITCODE -ne 0) { throw 'age decryption failed.' }; tar -xf $tar -C $w; if ($DryRun) { "DRY RUN: Litestream restore -> $DatabasePath"; return }; New-Item -ItemType Directory -Force (Split-Path $DatabasePath) | Out-Null; & $Litestream restore -o $DatabasePath "file://$(Join-Path $w 'replica')"; if ($LASTEXITCODE -ne 0) { throw 'Litestream restore failed.' }; "Gateway restore completed: $DatabasePath" } finally { Remove-Item $w -Recurse -Force -ErrorAction SilentlyContinue }
