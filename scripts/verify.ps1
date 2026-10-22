param([string]$WeChatDevToolsPath = '')

$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path -Parent $PSScriptRoot

Push-Location (Join-Path $repositoryRoot 'apps\miniprogram')
try {
  & npm.cmd run check
  if ($LASTEXITCODE -ne 0) { throw 'Mini program checks failed.' }
  if ($WeChatDevToolsPath) {
    & node scripts/check-wechat.cjs $WeChatDevToolsPath
    if ($LASTEXITCODE -ne 0) { throw 'WeChat offline compilation failed.' }
  } else {
    Write-Output 'WeChat offline compilation skipped: supply -WeChatDevToolsPath to run it.'
  }
} finally { Pop-Location }

Push-Location (Join-Path $repositoryRoot 'apps\admin')
try {
  & npm.cmd run check
  if ($LASTEXITCODE -ne 0) { throw 'Admin checks failed.' }
} finally { Pop-Location }

Push-Location (Join-Path $repositoryRoot 'apps\server')
try {
  & mvn -B verify
  if ($LASTEXITCODE -ne 0) { throw 'Server verification failed.' }
} finally { Pop-Location }

Write-Output 'Static checks, tests and builds passed. Runtime, browser and device checks are separate.'
