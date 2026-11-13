#Requires -Version 7.4
param(
  [switch]$Database,
  [string]$JavaHome = ''
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$workspace = Join-Path $repositoryRoot '.local\backend-day1'
if (-not $JavaHome) {
  $jdkRoot = Join-Path $workspace 'tools\jdk21'
  $JavaHome = Get-ChildItem -LiteralPath $jdkRoot -Directory | Where-Object {
    Test-Path -LiteralPath (Join-Path $_.FullName 'bin\java.exe')
  } | Select-Object -First 1 -ExpandProperty FullName
}
if (-not $JavaHome) { throw '请通过 -JavaHome 指定 JDK 21。' }
$java = Join-Path $JavaHome 'bin\java.exe'
$version = (& $java -version 2>&1 | Out-String)
if ($LASTEXITCODE -ne 0 -or $version -notmatch '(openjdk|java) version "21[.\"]') { throw '验证必须使用 JDK 21。' }
$processEnvironment = @{ JAVA_HOME = $JavaHome; PATH = "$(Join-Path $JavaHome 'bin');$env:PATH" }
# 普通回归固定使用本地存储；真实OSS由显式连接检查单独验收。
$processEnvironment.XINKELINK_IMAGE_STORAGE = 'local'
foreach ($name in @('XINKELINK_OSS_BUCKET', 'XINKELINK_OSS_REGION', 'XINKELINK_OSS_ENDPOINT',
    'XINKELINK_OSS_ACCESS_KEY_ID', 'XINKELINK_OSS_ACCESS_KEY_SECRET')) { $processEnvironment[$name] = '' }
$socketDirectory = Join-Path $repositoryRoot '.local\jvm-sockets'
New-Item -ItemType Directory -Path $socketDirectory -Force | Out-Null
# 此机器的用户Temp不支持JDK的UDS连接，测试子进程沿用启动脚本的项目内目录。
$processEnvironment.JDK_JAVA_OPTIONS = "`"-Djdk.net.unixdomain.tmpdir=$socketDirectory`""
$profile = ''
if ($Database) {
  & (Join-Path $PSScriptRoot 'initialize-backend-dev.ps1') -StartExisting | Out-Null
  $state = Get-Content -LiteralPath (Join-Path $workspace 'private\runtime.json') -Raw | ConvertFrom-Json
  $processEnvironment.XINKELINK_DB_URL = 'jdbc:mysql://127.0.0.1:13306/xinkelink_dev?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&allowPublicKeyRetrieval=true&useSSL=false'
  $processEnvironment.XINKELINK_DB_USERNAME = $state.accounts.runtime.username
  $processEnvironment.XINKELINK_DB_PASSWORD = $state.accounts.runtime.password
  $processEnvironment.XINKELINK_MIGRATION_USERNAME = $state.accounts.migrator.username
  $processEnvironment.XINKELINK_MIGRATION_PASSWORD = $state.accounts.migrator.password
  $profile = '-Pdb-integration'
}

# 凭据仅传递给本次 Maven 子进程，完整输出留在忽略目录供复核。
$maven = (Get-Command mvn.cmd -ErrorAction Stop).Source
$stdout = Join-Path $workspace 'verify.stdout.log'
$stderr = Join-Path $workspace 'verify.stderr.log'
$process = Start-Process -FilePath $env:ComSpec -WindowStyle Hidden -Wait -PassThru `
  -WorkingDirectory (Join-Path $repositoryRoot 'apps\server') -Environment $processEnvironment `
  -ArgumentList @('/d', '/s', '/c', "`"`"$maven`" -B -Dxinkelink.build.directory=target/backend-dev $profile verify`"") `
  -RedirectStandardOutput $stdout -RedirectStandardError $stderr
Get-Content -LiteralPath $stdout -Tail 16
if ($process.ExitCode -ne 0) { throw "后端验证失败，请检查 $stdout 和 $stderr。" }
Write-Output '后端验证通过；没有执行 Git 提交或数据库清理。'
