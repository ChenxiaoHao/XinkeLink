param(
  [ValidateRange(1, 65535)][int]$Port = 8080,
  [switch]$Build
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$serverDirectory = Join-Path $repositoryRoot 'apps\server'
$jar = Join-Path $serverDirectory 'target\xinkelink-server-0.1.0-SNAPSHOT.jar'

if ($Build -or -not (Test-Path -LiteralPath $jar)) {
  Push-Location $serverDirectory
  try {
    & mvn -B package
    if ($LASTEXITCODE -ne 0) { throw 'Server build failed.' }
  } finally { Pop-Location }
}

# Windows JDK selectors can fail with the current user's short TEMP path.
# Keep this override local to this JVM; do not change user/system settings.
$socketDirectory = Join-Path $repositoryRoot '.local\jvm-sockets'
New-Item -ItemType Directory -Path $socketDirectory -Force | Out-Null
$javaExecutable = 'java'
if ($env:JAVA_HOME) {
  $configuredJava = Join-Path $env:JAVA_HOME 'bin\java.exe'
  if (Test-Path -LiteralPath $configuredJava) { $javaExecutable = $configuredJava }
}

& $javaExecutable "-Djdk.net.unixdomain.tmpdir=$socketDirectory" -jar $jar "--server.port=$Port"
if ($LASTEXITCODE -ne 0) { throw "Server exited with code $LASTEXITCODE." }
