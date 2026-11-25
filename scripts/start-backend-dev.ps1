#Requires -Version 7.4
param(
  [ValidateRange(1, 65535)][int]$Port = 18081,
  [ValidatePattern('^[A-Za-z0-9_-]+(,[A-Za-z0-9_-]+)*$')][string]$Profiles = 'db,dev,auth',
  [string]$JavaHome = '',
  [switch]$Build,
  [switch]$RequireAi
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$workspace = Join-Path $repositoryRoot '.local\backend-day1'
$runtimeFile = Join-Path $workspace 'private\runtime.json'
$serverDirectory = Join-Path $repositoryRoot 'apps\server'
$jar = Join-Path $serverDirectory 'target\backend-dev\xinkelink-server-0.1.0-SNAPSHOT.jar'
if ($Port -in 8080, 3306, 6379, 13306) { throw '该端口保留给既有服务或隔离数据库，请使用 18081 等独立端口。' }
if (@(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue).Count -gt 0) {
  throw "端口 $Port 已有监听进程，拒绝替换或停止该进程。"
}
if (-not (Test-Path -LiteralPath $runtimeFile -PathType Leaf)) { throw '缺少开发数据库凭据，请先运行 initialize-backend-dev.ps1。' }
try { $state = Get-Content -LiteralPath $runtimeFile -Raw | ConvertFrom-Json } catch { throw '无法读取开发数据库实例记录。' }
if ($state.version -ne 1 -or $state.initialized -ne $true -or $state.host -ne '127.0.0.1' -or $state.port -ne 13306 -or $state.database -ne 'xinkelink_dev') {
  throw '开发数据库实例记录不符合隔离要求。'
}
$mysqlProcess = Get-Process -Id $state.mysqlProcessId -ErrorAction SilentlyContinue
$listeners = @(Get-NetTCPConnection -State Listen -LocalPort 13306 -ErrorAction SilentlyContinue)
if (-not $mysqlProcess -or $mysqlProcess.Path -ne (Join-Path $state.mysqlHome 'bin\mysqld.exe') -or
    [string]$mysqlProcess.StartTime.ToUniversalTime().Ticks -ne $state.mysqlProcessStartedAtTicks -or
    $listeners.Count -ne 1 -or $listeners[0].LocalAddress -ne '127.0.0.1' -or $listeners[0].OwningProcess -ne $state.mysqlProcessId) {
  throw '独立 MySQL 实例未运行或与私有记录不匹配；不会连接未知数据库。'
}

# 只在项目工具目录寻找 JDK 21，不修改用户/系统 JAVA_HOME。
if (-not $JavaHome) {
  $candidates = foreach ($folder in @('tools\jdk21', 'tools\jdk21-direct')) {
    $root = Join-Path $workspace $folder
    if (Test-Path -LiteralPath $root -PathType Container) {
      $root
      Get-ChildItem -LiteralPath $root -Directory | Select-Object -ExpandProperty FullName
    }
  }
  $JavaHome = $candidates | Where-Object { Test-Path -LiteralPath (Join-Path $_ 'bin\java.exe') -PathType Leaf } | Select-Object -First 1
}
if (-not $JavaHome) { throw '未找到项目内的 JDK 21，请准备 tools\jdk21 或传入 -JavaHome。' }
$JavaHome = [System.IO.Path]::GetFullPath($JavaHome)
$java = Join-Path $JavaHome 'bin\java.exe'
if (-not (Test-Path -LiteralPath $java -PathType Leaf)) { throw '指定目录不包含 java.exe。' }
$javaVersion = (& $java -version 2>&1 | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $javaVersion -notmatch '(openjdk|java) version "21[.\"]') { throw '开发后端必须使用 JDK 21。' }

$processEnvironment = @{ JAVA_HOME = $JavaHome; PATH = "$(Join-Path $JavaHome 'bin');$env:PATH" }
$aiRequired = @('XINKELINK_AI_CHAT_URL', 'XINKELINK_AI_CHAT_KEY', 'XINKELINK_AI_CHAT_MODEL',
  'XINKELINK_AI_EMBEDDING_URL', 'XINKELINK_AI_EMBEDDING_KEY', 'XINKELINK_AI_EMBEDDING_MODEL',
  'XINKELINK_AI_RERANK_URL', 'XINKELINK_AI_RERANK_KEY', 'XINKELINK_AI_RERANK_MODEL', 'XINKELINK_AI_CHROMA_URL')
$aiNames = $aiRequired + @('XINKELINK_AI_CHAT_ENABLE_THINKING', 'XINKELINK_AI_ALLOWED_HOSTS', 'XINKELINK_AI_SETTINGS_KEY_BASE64',
  'XINKELINK_AI_RERANK_PROTOCOL', 'XINKELINK_AI_CHROMA_TENANT', 'XINKELINK_AI_CHROMA_DATABASE',
  'XINKELINK_AI_INPUT_CHARS', 'XINKELINK_AI_CONTEXT_CHARS', 'XINKELINK_AI_OUTPUT_CHARS', 'XINKELINK_AI_OUTPUT_TOKENS',
  'XINKELINK_AI_TIMEOUT_SECONDS', 'XINKELINK_AI_REQUESTS_PER_MINUTE', 'XINKELINK_AI_GLOBAL_CONCURRENCY',
  'XINKELINK_AI_KNOWLEDGE_RERANK_MIN_SCORE')
$aiFile = Join-Path $workspace 'private\link-ai-environment.json'
if (Test-Path -LiteralPath $aiFile -PathType Leaf) {
  try { $aiLocal = Get-Content -LiteralPath $aiFile -Raw | ConvertFrom-Json -AsHashtable }
  catch { throw '无法读取本地 Link AI 配置文件。' }
  if ($aiLocal -isnot [System.Collections.IDictionary]) { throw '本地 Link AI 配置必须是由环境变量名和字符串组成的 JSON 对象。' }
  foreach ($name in $aiLocal.Keys) {
    if ($name -cnotin $aiNames -or $aiLocal[$name] -isnot [string]) { throw '本地 Link AI 配置含有未知配置项或非字符串值。' }
    if (-not [string]::IsNullOrWhiteSpace($aiLocal[$name])) { $processEnvironment[$name] = $aiLocal[$name].Trim() }
  }
}
# 桌面应用可能早于用户环境变量启动；读取新配置后只传给后端子进程。
foreach ($name in (@('XINKELINK_WECHAT_APP_ID', 'XINKELINK_WECHAT_APP_SECRET', 'XINKELINK_ADMIN_ALLOWED_ORIGINS',
    'XINKELINK_IMAGE_STORAGE', 'XINKELINK_OSS_BUCKET', 'XINKELINK_OSS_REGION',
    'XINKELINK_OSS_ENDPOINT', 'XINKELINK_OSS_ACCESS_KEY_ID', 'XINKELINK_OSS_ACCESS_KEY_SECRET') + $aiNames)) {
  $value = [Environment]::GetEnvironmentVariable($name, 'Process')
  if ([string]::IsNullOrWhiteSpace($value)) { $value = [Environment]::GetEnvironmentVariable($name, 'User') }
  if ([string]::IsNullOrWhiteSpace($value)) { $value = [Environment]::GetEnvironmentVariable($name, 'Machine') }
  if (-not [string]::IsNullOrWhiteSpace($value)) { $processEnvironment[$name] = $value.Trim() }
}
if ($RequireAi -and $Profiles.Split(',') -notcontains 'auth') { throw 'Link AI 启动检查需要 auth profile。' }
if ($Profiles.Split(',') -contains 'auth') {
  $missing = @($aiRequired | Where-Object { -not $processEnvironment.ContainsKey($_) })
  if ($missing.Count -gt 0) {
    $message = 'Link AI 缺少配置项：' + ($missing -join ', ')
    if ($RequireAi) { throw $message }
    Write-Warning $message
  } elseif ($RequireAi) {
    try {
      $heartbeat = Invoke-RestMethod -Uri ($processEnvironment.XINKELINK_AI_CHROMA_URL.TrimEnd('/') + '/api/v2/heartbeat') -TimeoutSec 5 -MaximumRedirection 0
      if (-not $heartbeat.PSObject.Properties['nanosecond heartbeat']) { throw 'Invalid Chroma heartbeat.' }
    } catch { throw 'Link AI 的 Chroma 服务未通过心跳检查。' }
    Write-Output 'Link AI 模型配置项齐全，Chroma 心跳正常。'
  }
}
Write-Output "微信配置存在：AppID=$($processEnvironment.ContainsKey('XINKELINK_WECHAT_APP_ID'))，AppSecret=$($processEnvironment.ContainsKey('XINKELINK_WECHAT_APP_SECRET'))。"
# 本地后台使用 HTTPS 安全 Cookie；自定义来源配置优先，不放宽服务端来源校验。
if ($Profiles.Split(',') -contains 'auth' -and -not $processEnvironment.ContainsKey('XINKELINK_ADMIN_ALLOWED_ORIGINS')) {
  $processEnvironment.XINKELINK_ADMIN_ALLOWED_ORIGINS = 'https://localhost:5173'
}
if ($Build -or -not (Test-Path -LiteralPath $jar -PathType Leaf)) {
  # 删除源码后增量编译会留下旧类；清理范围固定在后端专用构建目录。
  $buildRoot = [System.IO.Path]::GetFullPath((Join-Path $serverDirectory 'target'))
  $buildDirectory = [System.IO.Path]::GetFullPath((Join-Path $buildRoot 'backend-dev'))
  if (-not $buildDirectory.StartsWith($buildRoot + [System.IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw '构建清理目录超出后端 target 范围。'
  }

  $maven = (Get-Command mvn.cmd -ErrorAction Stop).Source
  # 构建和普通回归不携带真实云密钥，不因运行模式为OSS而写入云端。
  $buildEnvironment = @{} + $processEnvironment
  $buildEnvironment.XINKELINK_IMAGE_STORAGE = 'local'
  foreach ($name in @('XINKELINK_OSS_BUCKET', 'XINKELINK_OSS_REGION', 'XINKELINK_OSS_ENDPOINT',
      'XINKELINK_OSS_ACCESS_KEY_ID', 'XINKELINK_OSS_ACCESS_KEY_SECRET')) { $buildEnvironment[$name] = '' }
  foreach ($name in $aiNames) { $buildEnvironment[$name] = '' }
  $buildProcess = Start-Process -FilePath $env:ComSpec -WorkingDirectory $serverDirectory -WindowStyle Hidden -Wait -PassThru `
    -ArgumentList @('/d', '/s', '/c', "`"`"$maven`" -B -Dxinkelink.build.directory=target/backend-dev clean package`"") -Environment $buildEnvironment `
    -RedirectStandardOutput (Join-Path $workspace 'server-build.stdout.log') -RedirectStandardError (Join-Path $workspace 'server-build.stderr.log')
  if ($buildProcess.ExitCode -ne 0) { throw '后端构建失败，请检查 .local/backend-day1/server-build 日志。' }
}

$processEnvironment.XINKELINK_DB_URL = 'jdbc:mysql://127.0.0.1:13306/xinkelink_dev?useUnicode=true&characterEncoding=UTF-8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&allowPublicKeyRetrieval=true&useSSL=false'
$processEnvironment.XINKELINK_DB_USERNAME = $state.accounts.runtime.username
$processEnvironment.XINKELINK_DB_PASSWORD = $state.accounts.runtime.password
$processEnvironment.XINKELINK_MIGRATION_USERNAME = $state.accounts.migrator.username
$processEnvironment.XINKELINK_MIGRATION_PASSWORD = $state.accounts.migrator.password
$socketDirectory = Join-Path $repositoryRoot '.local\jvm-sockets'
New-Item -ItemType Directory -Path $socketDirectory -Force | Out-Null
# 每次执行独立副本，Windows 上运行中的 JVM 不会锁住下一次构建产物。
$runDirectory = Join-Path $workspace (Join-Path 'run' ([Guid]::NewGuid().ToString('N')))
New-Item -ItemType Directory -Path $runDirectory -Force | Out-Null
$runJar = Join-Path $runDirectory 'server.jar'
Copy-Item -LiteralPath $jar -Destination $runJar
$stdout = Join-Path $workspace 'backend.stdout.log'
$stderr = Join-Path $workspace 'backend.stderr.log'
# Start-Process 的 Environment 只影响子进程，凭据不进入命令行或全局环境。
$backend = $null
$healthy = $false
try {
$backend = Start-Process -FilePath $java -WorkingDirectory $serverDirectory -WindowStyle Hidden -PassThru -Environment $processEnvironment `
  -ArgumentList @("`"-Djdk.net.unixdomain.tmpdir=$socketDirectory`"", '-jar', "`"$runJar`"", '--server.address=127.0.0.1', "--server.port=$Port", "--spring.profiles.active=$Profiles") `
  -RedirectStandardOutput $stdout -RedirectStandardError $stderr
[System.IO.File]::WriteAllText((Join-Path $workspace 'backend.pid'), [string]$backend.Id)
$deadline = [DateTime]::UtcNow.AddSeconds(30)
while ([DateTime]::UtcNow -lt $deadline) {
  if ($backend.HasExited) { throw '开发后端提前退出，请检查 .local/backend-day1/backend 日志。' }
  $owners = @(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue)
  if (@($owners | Where-Object { $_.OwningProcess -ne $backend.Id }).Count -gt 0) { throw '目标端口被其他进程占用，不将其健康检查视为本次启动成功。' }
  try {
    $health = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/actuator/health" -TimeoutSec 1
    $identity = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/api/v1/health" -TimeoutSec 1
    if ($owners.Count -gt 0 -and $health.status -eq 'UP' -and $identity.application -eq 'xinkelink-server' -and $identity.status -eq 'UP' -and -not $backend.HasExited) {
      $healthy = $true
      break
    }
  } catch { }
  Start-Sleep -Milliseconds 250
}
if (-not $healthy) { throw "开发后端在 30 秒内未通过健康检查（PID=$($backend.Id)），请检查日志；不会停止未知进程。" }
Write-Output "JDK 21 开发后端健康检查通过：PID=$($backend.Id)，端口=$Port，profiles=$Profiles。"
Write-Output "日志路径：$stdout；$stderr。"
} finally {
  # 不按端口查杀；失败时只清理本次 Start-Process 返回的 Java 实例。
  if (-not $healthy -and $backend -and -not $backend.HasExited) {
    $backend.Kill()
    $backend.WaitForExit()
  }
}
