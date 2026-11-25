#Requires -Version 7.4
param(
  [ValidateSet('Status','Replay','Pause')][string]$Action='Status',
  [Parameter(Mandatory)][ValidatePattern('^(xinkelink_dev|xinkelink_cache_[a-zA-Z0-9_]+_test)$')][string]$Database,
  [ValidatePattern('^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')][string]$EventId
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
  $sql="SELECT state,COUNT(*) AS records,MIN(created_at) AS oldest FROM $Database.xl_notification_outbox GROUP BY state; SELECT COUNT(*) AS sent_waiting_inbox FROM $Database.xl_notification_outbox o LEFT JOIN $Database.xl_notification_inbox i ON i.event_id=o.event_id WHERE o.state='SENT' AND i.event_id IS NULL; SELECT state,COUNT(*) AS records FROM $Database.xl_notification_failure GROUP BY state;"
}else{
  if(-not $EventId){throw 'Explicit business EventId is required; Broker message IDs are not accepted'}
  $sql=if($Action -eq 'Replay'){
    "UPDATE $Database.xl_notification_outbox SET state='READY',attempts=0,next_attempt_at=UTC_TIMESTAMP(3),last_result='REPLAY' WHERE event_id='$EventId' AND state IN ('PAUSED','SENT'); SELECT ROW_COUNT();"
  }else{"UPDATE $Database.xl_notification_outbox SET state='PAUSED',last_result='MANUAL_PAUSE' WHERE event_id='$EventId' AND state='READY'; SELECT ROW_COUNT();"}
}
$result=& $client "--defaults-file=$defaults" --no-login-paths --batch --raw --connect-timeout=3 -e $sql 2>$null
if($LASTEXITCODE -ne 0){throw 'Notification outbox operation failed; no credentials or raw database error exposed'}
if($Action -ne 'Status'){
  if(($result | Select-Object -Last 1) -ne '1'){throw 'No eligible record changed; active leases cannot be overwritten'}
  $auditDirectory=Join-Path $repoRoot '.local/notification-operations';New-Item -ItemType Directory -Force -Path $auditDirectory | Out-Null
  @{at=[DateTime]::UtcNow.ToString('o');action=$Action;database=$Database;eventId=$EventId;operator=[Security.Principal.WindowsIdentity]::GetCurrent().Name} | ConvertTo-Json -Compress | Add-Content (Join-Path $auditDirectory 'operations.jsonl')
}
$result
