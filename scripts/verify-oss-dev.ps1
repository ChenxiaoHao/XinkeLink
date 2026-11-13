#Requires -Version 5.1
[CmdletBinding()]
param([string]$ExpectedBucket = 'xinkelink-images-dev-7316')

$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$workDirectory = Join-Path $repositoryRoot '.local\oss-integration-20261031'
New-Item -ItemType Directory -Path $workDirectory -Force | Out-Null
$names = @('XINKELINK_OSS_BUCKET', 'XINKELINK_OSS_REGION', 'XINKELINK_OSS_ENDPOINT',
    'XINKELINK_OSS_ACCESS_KEY_ID', 'XINKELINK_OSS_ACCESS_KEY_SECRET', 'XINKELINK_OSS_LIVE_TEST', 'JAVA_HOME', 'PATH')
$original = @{}
foreach ($name in $names) { $original[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }

try {
  foreach ($name in $names[0..4]) {
    $value = [Environment]::GetEnvironmentVariable($name, 'User')
    if ([string]::IsNullOrWhiteSpace($value)) { throw "$name 未配置。" }
    Write-Host "$name 已配置：True"
  }
  if ([Environment]::GetEnvironmentVariable('XINKELINK_OSS_BUCKET', 'User') -ne $ExpectedBucket) { throw '开发桶名称与预期不一致，不执行云端写入。' }
  $jdkRoot = Join-Path $repositoryRoot '.local\backend-day1\tools\jdk21'
  $env:JAVA_HOME = Get-ChildItem -LiteralPath $jdkRoot -Directory | Where-Object {
    Test-Path -LiteralPath (Join-Path $_.FullName 'bin\java.exe')
  } | Select-Object -First 1 -ExpandProperty FullName
  if (-not $env:JAVA_HOME) { throw '未找到项目JDK21。' }
  $env:PATH = (Join-Path $env:JAVA_HOME 'bin') + ';' + $env:PATH
  $classpathFile = Join-Path $workDirectory 'classpath.txt'
  $compileLog = Join-Path $workDirectory 'live-compile.log'
  Push-Location (Join-Path $repositoryRoot 'apps\server')
  try {
    # 此步骤只编译连接检查入口；完整测试由verify-backend.ps1单独执行。
    & (Get-Command mvn.cmd -ErrorAction Stop).Source -B '-Dxinkelink.build.directory=target/backend-dev' `
      '-DskipTests' test-compile dependency:build-classpath "-Dmdep.outputFile=$classpathFile" *> $compileLog
    if ($LASTEXITCODE -ne 0) { throw '连接检查编译失败，请检查本地live-compile.log。' }
    $classpath = (Join-Path $repositoryRoot 'apps\server\target\backend-dev\test-classes') + ';' +
      (Join-Path $repositoryRoot 'apps\server\target\backend-dev\classes') + ';' +
      ([IO.File]::ReadAllText($classpathFile)).Trim()
    foreach ($name in $names[0..4]) { [Environment]::SetEnvironmentVariable($name, [Environment]::GetEnvironmentVariable($name, 'User'), 'Process') }
    $env:XINKELINK_OSS_LIVE_TEST = 'true'
    $socketDirectory = Join-Path $repositoryRoot '.local\jvm-sockets'
    New-Item -ItemType Directory -Path $socketDirectory -Force | Out-Null
    & (Join-Path $env:JAVA_HOME 'bin\java.exe') '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' `
      "-Djdk.net.unixdomain.tmpdir=$socketDirectory" -cp $classpath cn.xinkelink.storage.OssStorageLiveCheck
    if ($LASTEXITCODE -ne 0) { throw '真实OSS检查未通过，不切换运行中的图片存储。' }
  } finally { Pop-Location }
} finally {
  foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $original[$name], 'Process') }
  $original = $null
}
