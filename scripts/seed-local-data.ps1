#Requires -Version 7.4
[CmdletBinding(DefaultParameterSetName = 'Plan')]
param(
  [Parameter(Mandatory, ParameterSetName = 'Apply')][switch]$Apply,
  [Parameter(Mandatory, ParameterSetName = 'Verify')][switch]$Verify,
  [Parameter(Mandatory, ParameterSetName = 'Rollback')][switch]$RollbackTest,
  [ValidateSet('local', 'test')][string]$Environment = 'local',
  [ValidateSet('preview', 'stress')][string]$Dataset = 'preview',
  [string]$DatabaseUrl = 'jdbc:mysql://127.0.0.1:13306/xinkelink_dev?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&allowPublicKeyRetrieval=true&useSSL=false',
  [string]$CredentialFile = '',
  [string]$ImageDirectory = '.local/backend-day5/images',
  [string]$JavaHome = ''
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$output = Join-Path $repositoryRoot '.local/seed-local-data'
New-Item -ItemType Directory -Path $output -Force | Out-Null
$mode = if ($Apply) { '--apply' } elseif ($Verify) { '--verify' } elseif ($RollbackTest) { '--rollback-test' } else { '--plan' }
if ($DatabaseUrl -notmatch '^jdbc:mysql://(127\.0\.0\.1|localhost|\[::1\])(?::[0-9]{1,5})?/[A-Za-z0-9_]+_(dev|test)(\?.*)?$') {
  throw '仅允许本机且名称以 _dev 或 _test 结尾的数据库。'
}
$imagePath = if ([IO.Path]::IsPathRooted($ImageDirectory)) { [IO.Path]::GetFullPath($ImageDirectory) } else { [IO.Path]::GetFullPath((Join-Path $repositoryRoot $ImageDirectory)) }
$localRoot = [IO.Path]::GetFullPath((Join-Path $repositoryRoot '.local')) + [IO.Path]::DirectorySeparatorChar
if (-not $imagePath.StartsWith($localRoot, [StringComparison]::OrdinalIgnoreCase)) { throw '图片目录必须位于本仓库的 .local 下。' }
if (-not $JavaHome) {
  $JavaHome = Get-ChildItem -LiteralPath (Join-Path $repositoryRoot '.local/backend-day1/tools/jdk21') -Directory |
    Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'bin/java.exe') } | Select-Object -First 1 -ExpandProperty FullName
}
if (-not $JavaHome) { throw '请使用 -JavaHome 指定 JDK 21。' }
$java = Join-Path $JavaHome 'bin/java.exe'
$version = & $java -version 2>&1 | Out-String
if ($LASTEXITCODE -ne 0 -or $version -notmatch 'version "21[.\"]') { throw '生成脚本必须使用 JDK 21。' }
$childEnvironment = @{ JAVA_HOME = $JavaHome; PATH = "$(Join-Path $JavaHome 'bin');$env:PATH"; XINKELINK_SEED_ENV = $Environment }
if ($mode -ne '--plan') {
  if (-not $CredentialFile) { $CredentialFile = Join-Path $repositoryRoot '.local/backend-day1/private/runtime.json' }
  $state = Get-Content -LiteralPath $CredentialFile -Raw | ConvertFrom-Json
  if ($state.version -ne 1 -or $state.initialized -ne $true -or -not $state.accounts.runtime.username -or -not $state.accounts.runtime.password) { throw '本地开发凭据格式无效。' }
  $childEnvironment.XINKELINK_DB_URL = $DatabaseUrl
  $childEnvironment.XINKELINK_DB_USERNAME = $state.accounts.runtime.username
  $childEnvironment.XINKELINK_DB_PASSWORD = $state.accounts.runtime.password
}
$maven = (Get-Command mvn.cmd -ErrorAction Stop).Source
$compileLog = Join-Path $output 'compile.stdout.log'
$compile = Start-Process -FilePath $env:ComSpec -WindowStyle Hidden -Wait -PassThru -Environment $childEnvironment `
  -WorkingDirectory (Join-Path $repositoryRoot 'apps/server') `
  -ArgumentList @('/d', '/s', '/c', "`"`"$maven`" -B -Dxinkelink.build.directory=target/local-seed -Dtest=LocalSeedDataTest test`"") `
  -RedirectStandardOutput $compileLog -RedirectStandardError (Join-Path $output 'compile.stderr.log')
if ($compile.ExitCode -ne 0) { throw "编译/安全检查失败，见 $compileLog。" }
$testReport = Join-Path $repositoryRoot 'apps/server/target/local-seed/surefire-reports/TEST-cn.xinkelink.seed.LocalSeedDataTest.xml'
[xml]$xml = Get-Content -LiteralPath $testReport -Raw
$classpath = ($xml.testsuite.properties.property | Where-Object name -eq 'java.class.path').value
if (-not $classpath) { throw '缺少测试类路径。' }
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
$report = Join-Path $output "seed-$stamp.json"
$argumentFile = Join-Path $output "java-$stamp.args"
# 凭据只传入子进程环境；Java参数文件仅包含类路径、模式和输出路径。
[IO.File]::WriteAllLines($argumentFile, @('-cp', ('"' + $classpath.Replace('\', '/') + '"'), 'cn.xinkelink.seed.LocalSeedRunner', $mode,
  '--images', ('"' + $imagePath.Replace('\', '/') + '"'), '--report', ('"' + $report.Replace('\', '/') + '"'), '--dataset', $Dataset), [Text.UTF8Encoding]::new($false))
$run = Start-Process -FilePath $java -WindowStyle Hidden -Wait -PassThru -Environment $childEnvironment `
  -WorkingDirectory (Join-Path $repositoryRoot 'apps/server') -ArgumentList @("`"@$argumentFile`"") `
  -RedirectStandardOutput (Join-Path $output 'seed-command.log') -RedirectStandardError (Join-Path $output 'seed.stderr.log')
if ($run.ExitCode -ne 0) { throw "数据脚本未成功；未提交的事务会回滚，见 $output/seed.stderr.log。" }
Copy-Item -LiteralPath $report -Destination (Join-Path $output 'latest-report.json') -Force
Get-Content -LiteralPath $report -Raw
Write-Output "报告：$report"
