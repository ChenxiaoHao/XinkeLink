#Requires -Version 5.1
[CmdletBinding()]
param(
  [string]$Bucket = 'xinkelink-images-dev-7316',
  [string]$Region = 'cn-beijing',
  [string]$Endpoint = 'https://oss-cn-beijing.aliyuncs.com',
  [switch]$CheckOnly
)

$ErrorActionPreference = 'Stop'

function Show-OssConfiguration {
  foreach ($name in @('XINKELINK_OSS_BUCKET', 'XINKELINK_OSS_REGION', 'XINKELINK_OSS_ENDPOINT',
      'XINKELINK_OSS_ACCESS_KEY_ID', 'XINKELINK_OSS_ACCESS_KEY_SECRET')) {
    $exists = -not [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name, 'User'))
    Write-Host "$name 已配置：$exists"
  }
}

function Read-PrivateValue([string]$Prompt) {
  # Windows PowerShell 5.1也支持安全输入，字符不会以明文回显。
  $secure = Read-Host -Prompt $Prompt -AsSecureString
  $pointer = [IntPtr]::Zero
  try {
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
  } finally {
    if ($pointer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
    $secure.Dispose()
  }
}

if ($CheckOnly) { Show-OssConfiguration; return }

if ($Bucket -notmatch '^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$') {
  throw 'Bucket名称格式不正确。'
}
$endpointUri = $null
if (-not [Uri]::TryCreate($Endpoint, [UriKind]::Absolute, [ref]$endpointUri) -or
    $endpointUri.Scheme -ne 'https' -or $endpointUri.Host -ne "oss-$Region.aliyuncs.com" -or
    $endpointUri.UserInfo -or $endpointUri.Query -or $endpointUri.Fragment -or
    $endpointUri.AbsolutePath -ne '/') {
  throw '开发配置必须使用对应地域的官方HTTPS外网Endpoint。'
}

Write-Host '配置信科 Link 开发桶。请输入专用RAM用户的AccessKey，不使用主账号密钥。'
Write-Host "Bucket：$Bucket；地域：$Region；Endpoint：$Endpoint"
Write-Host '两个输入框都使用掩码；密钥不会写入项目文件，也不会输出到控制台。'

# 交互输入不会进入命令历史，不把密钥放进脚本参数或进程命令行。
$accessKeyId = (Read-PrivateValue 'AccessKey ID').Trim()
$accessKeySecret = (Read-PrivateValue 'AccessKey Secret').Trim()
if ([string]::IsNullOrWhiteSpace($accessKeyId) -or [string]::IsNullOrWhiteSpace($accessKeySecret)) {
  throw '输入不能为空，尚未保存配置。'
}

try {
  $configuration = [ordered]@{
    XINKELINK_OSS_BUCKET = $Bucket
    XINKELINK_OSS_REGION = $Region
    XINKELINK_OSS_ENDPOINT = $Endpoint.TrimEnd('/')
    XINKELINK_OSS_ACCESS_KEY_ID = $accessKeyId
    XINKELINK_OSS_ACCESS_KEY_SECRET = $accessKeySecret
  }
  foreach ($name in $configuration.Keys) {
    # 用户级变量供以后启动的后端读取，不改变现有运行中的本地存储模式。
    [Environment]::SetEnvironmentVariable($name, $configuration[$name], 'User')
  }
  Show-OssConfiguration
  Write-Host '配置已保存。当前只验证存在性，尚未验证OSS连接或权限，也没有切换图片存储。'
} finally {
  $accessKeyId = $null
  $accessKeySecret = $null
  $configuration = $null
}
