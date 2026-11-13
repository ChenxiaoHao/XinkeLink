#Requires -Version 7.4
param(
  [switch]$GrantRuntimePermissions,
  [switch]$StartExisting,
  [string]$MySqlHome = '',
  [ValidateRange(5, 180)][int]$StartupTimeoutSeconds = 60
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$workspace = Join-Path $repositoryRoot '.local\backend-day1'
$mysqlDirectory = Join-Path $workspace 'mysql'
$dataDirectory = Join-Path $mysqlDirectory 'data'
$privateDirectory = Join-Path $workspace 'private'
$runtimeFile = Join-Path $privateDirectory 'runtime.json'
$clientFile = Join-Path $privateDirectory 'client.cnf'
$utf8 = [System.Text.UTF8Encoding]::new($false)
$port = 13306
$database = 'xinkelink_dev'

function New-PrivateDirectory {
  New-Item -ItemType Directory -Path $privateDirectory -Force | Out-Null
  # 凭据与临时 SQL 只允许当前 Windows 用户和 SYSTEM 访问。
  $acl = Get-Acl -LiteralPath $privateDirectory
  $acl.SetAccessRuleProtection($true, $false)
  $inheritance = [System.Security.AccessControl.InheritanceFlags]'ContainerInherit,ObjectInherit'
  foreach ($identity in @([System.Security.Principal.WindowsIdentity]::GetCurrent().User, [System.Security.Principal.SecurityIdentifier]::new('S-1-5-18'))) {
    $rule = [System.Security.AccessControl.FileSystemAccessRule]::new($identity, 'FullControl', $inheritance, 'None', 'Allow')
    $acl.AddAccessRule($rule)
  }
  Set-Acl -LiteralPath $privateDirectory -AclObject $acl
}

function New-Password {
  $bytes = [byte[]]::new(32)
  [System.Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
  return [Convert]::ToHexString($bytes).ToLowerInvariant()
}

function Get-PortListeners {
  return @(Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue)
}

function Assert-ManagedState($State) {
  if ($State.version -ne 1 -or $State.host -ne '127.0.0.1' -or $State.port -ne $port -or $State.database -ne $database -or
      $State.mysqlHome -ne $MySqlHome -or [System.IO.Path]::GetFullPath($State.dataDirectory) -ne [System.IO.Path]::GetFullPath($dataDirectory)) {
    throw '开发实例记录与固定的隔离目录/端口不匹配，拒绝连接。'
  }
}

function Assert-ManagedInstance($State) {
  Assert-ManagedState $State
  $listeners = @(Get-PortListeners)
  $ownedProcess = Get-Process -Id $State.mysqlProcessId -ErrorAction SilentlyContinue
  $expectedExe = Join-Path $State.mysqlHome 'bin\mysqld.exe'
  if ($listeners.Count -ne 1 -or $listeners[0].LocalAddress -ne '127.0.0.1' -or
      $listeners[0].OwningProcess -ne $State.mysqlProcessId -or -not $ownedProcess -or
      $ownedProcess.Path -ne $expectedExe -or [string]$ownedProcess.StartTime.ToUniversalTime().Ticks -ne $State.mysqlProcessStartedAtTicks) {
    throw '13306 未由记录中的独立 MySQL 进程监听；拒绝连接或修改未知实例。'
  }
}

function Stop-StartedMySql($Process) {
  if (-not $Process) { return }
  # 若旧行为仍存在，先停本次启动器，防止它在清理服务子进程时重新派生。
  foreach ($owned in @($Process.LauncherProcess, $Process)) {
    if ($owned -and -not $owned.HasExited) { $owned.Kill(); $owned.WaitForExit() }
  }
}

function Start-IsolatedMySql($State) {
  # 当前 8.4.13 --verbose --help 明确支持 --no-monitor，避免 Windows 监控进程派生实际服务进程。
  $launcher = Start-Process -FilePath $mysqlServer -WindowStyle Hidden -PassThru -ArgumentList @(
    '--no-defaults', '--no-monitor', "--basedir=`"$MySqlHome`"", "--datadir=`"$dataDirectory`"", '--bind-address=127.0.0.1', "--port=$port", '--mysqlx=0',
    '--skip-log-bin', '--general-log=OFF', '--slow-query-log=OFF', "--pid-file=`"$(Join-Path $mysqlDirectory 'mysql.pid')`"", "--log-error=`"$(Join-Path $mysqlDirectory 'mysql.err')`""
  ) -RedirectStandardOutput (Join-Path $mysqlDirectory 'server.stdout.log') -RedirectStandardError (Join-Path $mysqlDirectory 'server.stderr.log')
  $launcherStartedAt = $launcher.StartTime.ToUniversalTime()
  $confirmedChildren = @{}
  function Find-ConfirmedChild {
    foreach ($candidate in @(Get-CimInstance Win32_Process -Filter "ParentProcessId=$($launcher.Id) AND Name='mysqld.exe'" -ErrorAction Stop)) {
      if ($candidate.ExecutablePath -ne $mysqlServer -or $candidate.CreationDate.ToUniversalTime() -lt $launcherStartedAt -or
          $candidate.CommandLine -notmatch '(?:^|\s)--datadir=(?:"([^"]+)"|(\S+))(?=\s|$)') { continue }
      $candidateData = if ($matches[1]) { $matches[1] } else { $matches[2] }
      if ([System.IO.Path]::GetFullPath($candidateData).TrimEnd('\', '/') -ne $dataDirectory.TrimEnd('\', '/')) { continue }
      $child = Get-Process -Id $candidate.ProcessId -ErrorAction SilentlyContinue
      if (-not $child -or $child.Path -ne $mysqlServer -or
          [Math]::Abs(($child.StartTime.ToUniversalTime() - $candidate.CreationDate.ToUniversalTime()).Ticks) -gt [TimeSpan]::TicksPerMillisecond) { continue }
      # 保留进程句柄，失败清理仅操作已核验的本次直接子进程。
      $null = $child.Handle
      $confirmedChildren[$child.Id] = $child
    }
  }
  try {
    $deadline = [DateTime]::UtcNow.AddSeconds($StartupTimeoutSeconds)
    $process = $null
    while ([DateTime]::UtcNow -lt $deadline) {
      $listeners = @(Get-PortListeners)
      if ($listeners.Count -gt 0) {
        if ($listeners.Count -ne 1 -or $listeners[0].LocalAddress -ne '127.0.0.1') { throw '13306 出现非预期监听进程，拒绝采纳。' }
        if ($listeners[0].OwningProcess -eq $launcher.Id -and -not $launcher.HasExited) {
          $process = $launcher
        } else {
          Find-ConfirmedChild
          $process = $confirmedChildren[[int]$listeners[0].OwningProcess]
          if (-not $process) { throw '13306 不属于本次启动器或经严格核验的直接子进程，拒绝采纳。' }
        }
        break
      }
      if ($launcher.HasExited) {
        Find-ConfirmedChild
        if (@($confirmedChildren.Values | Where-Object { -not $_.HasExited }).Count -eq 0) { throw '独立 MySQL 启动进程已退出，请检查 mysql 目录中的启动日志。' }
      }
      Start-Sleep -Milliseconds 250
    }
    if (-not $process) { throw '独立 MySQL 启动超时，请检查 mysql 目录中的启动日志。' }
    $State.mysqlProcessId = $process.Id
    # JSON 日期会被 PowerShell 自动转换；使用 ticks 保证跨进程比较稳定。
    $State.mysqlProcessStartedAtTicks = [string]$process.StartTime.ToUniversalTime().Ticks
    [System.IO.File]::WriteAllText($runtimeFile, ($State | ConvertTo-Json -Depth 5), $utf8)
    Assert-ManagedInstance ([pscustomobject]$State)
    if ($process.Id -ne $launcher.Id) { $process | Add-Member -NotePropertyName LauncherProcess -NotePropertyValue $launcher }
    return $process
  } catch {
    # 启动器可能已经退出；补查它亲自派生且路径/目录/时间均匹配的子进程。
    try { Find-ConfirmedChild } catch { }
    foreach ($owned in @($launcher) + @($confirmedChildren.Values)) {
      if (-not $owned.HasExited) { $owned.Kill(); $owned.WaitForExit() }
    }
    throw
  }
}

function Invoke-PrivateMySql([string]$Sql, [string]$DefaultsFile) {
  $prefix = Join-Path $privateDirectory ([Guid]::NewGuid().ToString('N'))
  $sqlFile = "$prefix.sql"
  $outputFile = "$prefix.out"
  $errorFile = "$prefix.err"
  try {
    [System.IO.File]::WriteAllText($sqlFile, $Sql, $utf8)
    $arguments = @("--defaults-file=`"$DefaultsFile`"", '--no-login-paths', '--batch', '--raw', '--skip-column-names', '--silent', '--connect-timeout=3')
    $client = Start-Process -FilePath $mysqlClient -ArgumentList $arguments -WindowStyle Hidden -PassThru -Wait `
      -RedirectStandardInput $sqlFile -RedirectStandardOutput $outputFile -RedirectStandardError $errorFile
    if ($client.ExitCode -ne 0) { throw 'MySQL 客户端执行失败。' }
    return [System.IO.File]::ReadAllText($outputFile, $utf8).Trim()
  } catch {
    # MySQL 错误可能引用含密码的 SQL，禁止透传原始错误或临时文件。
    throw 'MySQL 操作失败，原始 SQL/错误已隐藏；请检查隔离实例状态。'
  } finally {
    foreach ($file in @($sqlFile, $outputFile, $errorFile)) {
      if (Test-Path -LiteralPath $file) { Remove-Item -LiteralPath $file -Force }
    }
  }
}

function Write-RootClient([string]$Password) {
  $content = "[client]`nuser=root`nhost=127.0.0.1`nport=$port`nprotocol=tcp`ndefault-character-set=utf8mb4`n"
  if ($Password) { $content += "password=$Password`n" }
  [System.IO.File]::WriteAllText($clientFile, $content, $utf8)
}

if (-not $MySqlHome) {
  $MySqlHome = Join-Path $workspace 'tools\mysql84\mysql-8.4.13-winx64'
}
$MySqlHome = [System.IO.Path]::GetFullPath($MySqlHome)
$mysqlServer = Join-Path $MySqlHome 'bin\mysqld.exe'
$mysqlClient = Join-Path $MySqlHome 'bin\mysql.exe'
if (-not (Test-Path -LiteralPath $mysqlServer -PathType Leaf) -or -not (Test-Path -LiteralPath $mysqlClient -PathType Leaf)) {
  throw '未找到项目内的 MySQL 8.4 ZIP 程序，请先准备 tools\mysql84。'
}
$version = (& $mysqlServer --no-defaults --version 2>&1 | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $version -notmatch '\bVer 8\.4\.[0-9]+\b') { throw '仅允许使用 MySQL 8.4 程序。' }

if ($GrantRuntimePermissions -and $StartExisting) { throw '-StartExisting 与 -GrantRuntimePermissions 请分开运行。' }
# 初始化和恢复都不允许目录联接把数据重定向至其他实例。
foreach ($path in @((Join-Path $repositoryRoot '.local'), $workspace, $mysqlDirectory, $dataDirectory, $privateDirectory, $MySqlHome)) {
  if ((Test-Path -LiteralPath $path) -and ((Get-Item -LiteralPath $path -Force).Attributes -band [System.IO.FileAttributes]::ReparsePoint)) {
    throw '隔离目录包含目录联接/符号链接，拒绝操作。'
  }
}
if ($GrantRuntimePermissions -or $StartExisting) {
  if (-not (Test-Path -LiteralPath $runtimeFile -PathType Leaf) -or -not (Test-Path -LiteralPath $clientFile -PathType Leaf)) {
    throw '缺少本脚本创建的私有实例记录，请先初始化。'
  }
  try { $state = Get-Content -LiteralPath $runtimeFile -Raw | ConvertFrom-Json } catch { throw '开发实例记录无法读取。' }
  Assert-ManagedState $state
  if ($state.initialized -ne $true -or -not (Test-Path -LiteralPath (Join-Path $dataDirectory 'auto.cnf') -PathType Leaf) -or
      -not (Test-Path -LiteralPath (Join-Path $dataDirectory 'mysql') -PathType Container)) {
    throw '未找到完成初始化的独立数据目录，拒绝恢复或授权。'
  }
}
if ($StartExisting) {
  if (@(Get-PortListeners).Count -gt 0) {
    Assert-ManagedInstance $state
    if ((Invoke-PrivateMySql 'SELECT 1;' $clientFile) -ne '1') { throw '独立实例认证验证失败。' }
    Write-Output "独立 MySQL 已在 127.0.0.1:$port 运行，PID=$($state.mysqlProcessId)。"
    return
  }
  $previous = Get-Process -Id $state.mysqlProcessId -ErrorAction SilentlyContinue
  if ($previous -and $previous.Path -eq $mysqlServer -and [string]$previous.StartTime.ToUniversalTime().Ticks -eq $state.mysqlProcessStartedAtTicks) {
    throw '记录中的 MySQL 进程仍存在但未监听端口，拒绝另启实例。'
  }
  $lockPath = Join-Path $mysqlDirectory 'initialize.lock'
  try { $lock = [System.IO.File]::Open($lockPath, 'CreateNew', 'Write', 'None') } catch { throw '已有实例操作锁，拒绝并行启动。' }
  $serverProcess = $null
  $succeeded = $false
  try {
    if (@(Get-PortListeners).Count -gt 0) { throw '13306 已被占用，拒绝启动。' }
    $serverProcess = Start-IsolatedMySql $state
    if ((Invoke-PrivateMySql 'SELECT 1;' $clientFile) -ne '1') { throw '隔离实例认证验证失败。' }
    $succeeded = $true
    Write-Output "独立 MySQL 8.4 已恢复：127.0.0.1:$port，PID=$($serverProcess.Id)，数据目录=$dataDirectory。"
  } finally {
    if (-not $succeeded) { Stop-StartedMySql $serverProcess }
    $lock.Dispose()
    Remove-Item -LiteralPath $lockPath -Force
  }
  return
}
if ($GrantRuntimePermissions) {
  Assert-ManagedInstance $state
  $identity = Invoke-PrivateMySql 'SELECT @@version, @@port, @@datadir;' $clientFile
  $columns = $identity -split "`t"
  if ($columns.Count -ne 3 -or $columns[0] -notmatch '^8\.4\.' -or $columns[1] -ne "$port" -or
      [System.IO.Path]::GetFullPath($columns[2]).TrimEnd('\', '/') -ne $dataDirectory.TrimEnd('\', '/')) {
    throw '数据库返回的版本/端口/数据目录不符，拒绝授权。'
  }
  $tableOutput = Invoke-PrivateMySql "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = '$database' AND TABLE_TYPE = 'BASE TABLE' AND LEFT(TABLE_NAME, 3) = 'xl_' ORDER BY TABLE_NAME;" $clientFile
  $tables = @($tableOutput -split '\r?\n' | Where-Object { $_ })
  $history = Invoke-PrivateMySql "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = '$database' AND TABLE_NAME = 'flyway_schema_history' AND TABLE_TYPE = 'BASE TABLE';" $clientFile
  if ($tables.Count -ne 27 -or 'xl_feedback' -notin $tables -or 'xl_audit_log' -notin $tables -or 'xl_beta_stage' -notin $tables -or 'xl_beta_code' -notin $tables -or @($tables | Where-Object { $_ -notmatch '^xl_[a-z0-9_]+$' }).Count -gt 0 -or $history -ne '1') {
    throw '必须先迁移出完整的 27 张 xl_ 业务表及 Flyway 历史表，再授运行账号写权限。'
  }
  # 先清除该专用账号既有权限，再逐表授权，绝不授库级写权限。
  $grants = @("REVOKE ALL PRIVILEGES, GRANT OPTION FROM 'xinkelink_runtime'@'127.0.0.1';", "GRANT SELECT ON $database.* TO 'xinkelink_runtime'@'127.0.0.1';")
  foreach ($table in $tables) {
    $permissions = if ($table -eq 'xl_audit_log') { 'INSERT' } elseif ($table -eq 'xl_feedback') { 'INSERT, UPDATE (id)' } else { 'INSERT, UPDATE, DELETE' }
    $grants += "GRANT $permissions ON $database.$table TO 'xinkelink_runtime'@'127.0.0.1';"
  }
  Invoke-PrivateMySql ($grants -join "`n") $clientFile | Out-Null
  Write-Output "已在 127.0.0.1:$port 按表授予运行账号权限；xl_audit_log 仅可查询/插入，Flyway 历史表仅可查询。"
  return
}

if (@(Get-PortListeners).Count -gt 0) { throw '13306 已被占用，拒绝初始化或停止现有进程。' }
if ((Test-Path -LiteralPath $runtimeFile) -or (Test-Path -LiteralPath $clientFile)) {
  throw '已有开发凭据，拒绝覆盖；迁移后请使用 -GrantRuntimePermissions。'
}
if ((Test-Path -LiteralPath $dataDirectory) -and @(Get-ChildItem -LiteralPath $dataDirectory -Force).Count -gt 0) {
  throw '数据目录不是空目录，拒绝初始化。'
}
& git -C $repositoryRoot check-ignore -q -- '.local/backend-day1/private/runtime.json'
if ($LASTEXITCODE -ne 0) { throw '私有凭据路径未被 Git 忽略，拒绝写入。' }
New-PrivateDirectory
New-Item -ItemType Directory -Path $mysqlDirectory -Force | Out-Null
$lockPath = Join-Path $mysqlDirectory 'initialize.lock'
try { $lock = [System.IO.File]::Open($lockPath, 'CreateNew', 'Write', 'None') } catch { throw '已有初始化锁，拒绝并行初始化；请先核实独立实例状态。' }
$serverProcess = $null
$succeeded = $false
try {
  if (@(Get-PortListeners).Count -gt 0 -or ((Test-Path -LiteralPath $dataDirectory) -and @(Get-ChildItem -LiteralPath $dataDirectory -Force).Count -gt 0)) {
    throw '端口或数据目录已发生变化，拒绝覆盖。'
  }
  $state = [ordered]@{
    version = 1; host = '127.0.0.1'; port = $port; database = $database
    mysqlHome = $MySqlHome; dataDirectory = $dataDirectory; mysqlProcessId = 0; mysqlProcessStartedAtTicks = ''; initialized = $false
    accounts = [ordered]@{
      root = @{ username = 'root'; password = (New-Password) }
      migrator = @{ username = 'xinkelink_migrator'; password = (New-Password) }
      runtime = @{ username = 'xinkelink_runtime'; password = (New-Password) }
    }
  }
  [System.IO.File]::WriteAllText($runtimeFile, ($state | ConvertTo-Json -Depth 5), $utf8)
  $initialize = Start-Process -FilePath $mysqlServer -WindowStyle Hidden -PassThru -Wait -ArgumentList @(
    '--no-defaults', '--initialize-insecure', "--basedir=`"$MySqlHome`"", "--datadir=`"$dataDirectory`"",
    "--log-error=`"$(Join-Path $mysqlDirectory 'initialize.err')`""
  ) -RedirectStandardOutput (Join-Path $mysqlDirectory 'initialize.stdout.log') -RedirectStandardError (Join-Path $mysqlDirectory 'initialize.stderr.log')
  if ($initialize.ExitCode -ne 0) { throw '隔离 MySQL 数据目录初始化失败；保留现场，不覆盖重试。' }
  $serverProcess = Start-IsolatedMySql $state
  Write-RootClient ''
  $sql = @"
CREATE DATABASE $database CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER 'xinkelink_migrator'@'127.0.0.1' IDENTIFIED BY '$($state.accounts.migrator.password)';
CREATE USER 'xinkelink_runtime'@'127.0.0.1' IDENTIFIED BY '$($state.accounts.runtime.password)';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, DROP, INDEX, REFERENCES ON $database.* TO 'xinkelink_migrator'@'127.0.0.1';
GRANT SELECT ON $database.* TO 'xinkelink_runtime'@'127.0.0.1';
ALTER USER 'root'@'localhost' IDENTIFIED BY '$($state.accounts.root.password)';
"@
  Invoke-PrivateMySql $sql $clientFile | Out-Null
  Write-RootClient $state.accounts.root.password
  if ((Invoke-PrivateMySql 'SELECT 1;' $clientFile) -ne '1') { throw '隔离实例认证验证失败。' }
  $state.initialized = $true
  [System.IO.File]::WriteAllText($runtimeFile, ($state | ConvertTo-Json -Depth 5), $utf8)
  $succeeded = $true
  Write-Output "MySQL 8.4 隔离实例已就绪：127.0.0.1:$port，PID=$($serverProcess.Id)，数据库=$database。"
  Write-Output "私有凭据路径：$runtimeFile；根账号客户端配置：$clientFile。"
  Write-Output '运行账号当前只读；Flyway 迁移完成后运行本脚本 -GrantRuntimePermissions。'
} finally {
  # 失败时仅关闭本次亲自启动的进程，不按端口/未知 PID 杀进程。
  if (-not $succeeded) { Stop-StartedMySql $serverProcess }
  $lock.Dispose()
  Remove-Item -LiteralPath $lockPath -Force
}
