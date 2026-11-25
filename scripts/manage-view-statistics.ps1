#Requires -Version 7.4
param(
  [ValidateSet('Status','Replay')][string]$Action='Status',
  [Parameter(Mandatory)][ValidatePattern('^(xinkelink_dev|xinkelink_cache_[a-zA-Z0-9_]+_test)$')][string]$Database,
  [ValidatePattern('^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$')][string]$EventId
)
$ErrorActionPreference='Stop'
$repoRoot=Split-Path $PSScriptRoot -Parent
$instance=Get-Content -LiteralPath (Join-Path $repoRoot '.local/backend-day1/private/runtime.json') -Raw | ConvertFrom-Json
$process=Get-Process -Id $instance.mysqlProcessId -ErrorAction SilentlyContinue
$listeners=@(Get-NetTCPConnection -State Listen -LocalPort 13306 -ErrorAction SilentlyContinue)
if($instance.host -ne '127.0.0.1' -or $instance.port -ne 13306 -or -not $process -or
   $process.Path -ne (Join-Path $instance.mysqlHome 'bin/mysqld.exe') -or [string]$process.StartTime.ToUniversalTime().Ticks -ne $instance.mysqlProcessStartedAtTicks -or
   $listeners.Count -ne 1 -or $listeners[0].LocalAddress -ne '127.0.0.1' -or $listeners[0].OwningProcess -ne $process.Id){throw 'Refusing unknown MySQL instance'}
$client=Join-Path $instance.mysqlHome 'bin/mysql.exe'
$defaults=Join-Path $repoRoot '.local/backend-day1/private/client.cnf'
if($Action -eq 'Status'){
  $sql="SELECT state,COUNT(*) AS records,MIN(occurred_at) AS oldest_event FROM $Database.xl_view_receipt GROUP BY state; SELECT COUNT(*) AS sent_without_effect FROM $Database.xl_view_receipt r LEFT JOIN $Database.xl_view_event e ON e.event_id=r.event_id WHERE r.state='SENT' AND e.event_id IS NULL; SELECT state,reason,COUNT(*) AS records FROM $Database.xl_view_failure GROUP BY state,reason; SELECT topic,partition_id,record_offset,reason,attempts,updated_at FROM $Database.xl_view_failure WHERE state='OPEN' ORDER BY topic,partition_id,record_offset LIMIT 50;"
}else{
  if(-not $EventId){throw 'An explicit application event UUID is required'}
  # Re-schedule an immutable trusted original. Never alter event time, payload, dedup or Kafka offsets.
  $sql="UPDATE $Database.xl_view_receipt SET state='READY',next_attempt_at=UTC_TIMESTAMP(3),last_error='MANUAL_REPLAY',updated_at=UTC_TIMESTAMP(3) WHERE event_id='$EventId' AND state='SENT' AND digest=LOWER(SHA2(payload,256)) AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.eventId'))=event_id; SELECT ROW_COUNT();"
}
$result=& $client "--defaults-file=$defaults" --no-login-paths --batch --raw --connect-timeout=3 -e $sql 2>$null
if($LASTEXITCODE -ne 0){throw 'Statistics operation failed; no raw database error or credentials exposed'}
if($Action -eq 'Replay'){
  if(($result | Select-Object -Last 1) -ne '1'){throw 'No eligible trusted SENT record changed; active leases cannot be overwritten'}
  $auditDirectory=Join-Path $repoRoot '.local/view-statistics-operations'
  New-Item -ItemType Directory -Force -Path $auditDirectory | Out-Null
  @{at=[DateTime]::UtcNow.ToString('o');action=$Action;database=$Database;eventId=$EventId;operator=[Security.Principal.WindowsIdentity]::GetCurrent().Name} | ConvertTo-Json -Compress | Add-Content (Join-Path $auditDirectory 'operations.jsonl')
}
$result
