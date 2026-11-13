[CmdletBinding()]
param(
    [string]$MigrationDirectory = [System.IO.Path]::GetFullPath(
        (Join-Path $PSScriptRoot '../../main/resources/db/migration')
    )
)

# 本脚本只检查迁移文本契约；不能替代真实 MySQL 8.4 的迁移与约束验证。
$ErrorActionPreference = 'Stop'
$expectedTables = @(
    'xl_user', 'xl_user_preference', 'xl_user_block', 'xl_admin', 'xl_auth_session',
    'xl_post', 'xl_comment', 'xl_like', 'xl_favorite', 'xl_product',
    'xl_conversation', 'xl_conversation_member', 'xl_message', 'xl_conversation_product',
    'xl_report', 'xl_moderation_action', 'xl_announcement', 'xl_notification_task',
    'xl_user_notification', 'xl_audit_log', 'xl_system_setting', 'xl_image_asset',
    'xl_media_ref', 'xl_user_activity_day', 'xl_beta_stage', 'xl_beta_code', 'xl_feedback'
)
$failures = [System.Collections.Generic.List[string]]::new()
$files = @()
if (Test-Path -LiteralPath $MigrationDirectory) {
    $files = @(Get-ChildItem -LiteralPath $MigrationDirectory -Filter '*.sql' -File | Sort-Object Name)
}
$historicalFiles = @($files | Where-Object Name -Match '^V[1-8]__')
$retirementFiles = @($files | Where-Object Name -eq 'V9__remove_marketplace.sql')
if ($historicalFiles.Count -ne 8 -or $retirementFiles.Count -ne 1 -or $files.Count -ne 9) {
    $failures.Add('预期保留V1-V8并新增唯一V9二手交易移除迁移')
}
$sql = ($historicalFiles | ForEach-Object { Get-Content -LiteralPath $_.FullName -Raw -Encoding UTF8 }) -join "`n"
$statements = [regex]::Replace($sql, '(?s)/\*.*?\*/|(?m)^\s*--[^\r\n]*', '')
$createdTables = @([regex]::Matches($statements, '(?i)\bCREATE\s+TABLE\s+`?(xl_[a-z_]+)`?\s*\(') |
    ForEach-Object { $_.Groups[1].Value.ToLowerInvariant() })

foreach ($table in $expectedTables) {
    if (@($createdTables | Where-Object { $_ -eq $table }).Count -ne 1) {
        $failures.Add("预期表必须恰好创建一次：$table")
    }
}
if ($createdTables.Count -ne 27) {
    $failures.Add("预期27张业务表，实际声明$($createdTables.Count)张")
}
foreach ($table in $createdTables) {
    if ($table -notin $expectedTables) { $failures.Add("未约定的业务表：$table") }
}

# 明文凭据、学号、手机号、学校认证状态均不属于该模型。
$sensitiveColumns = '(?im)^\s*`?(student_?(?:id|no|number)|phone(?:_number)?|mobile(?:_number)?|telephone|school_verified|school_auth_status|password|token|refresh_token|session_key|wechat_code)`?\s+[a-z]'
if ($statements -match $sensitiveColumns) { $failures.Add('迁移包含未允许的敏感字段') }
if ($statements -match '(?i)\bDROP\b(?!\s+CHECK\b)') { $failures.Add('迁移包含禁止的DROP语句') }
if ($statements -match '(?i)\bCREATE\s+(?:DATABASE|SCHEMA|USER)\b|\bINSERT\s+INTO\b') {
    $failures.Add('迁移不得建库、建用户或填充业务数据')
}
if ($statements -match '(?i)\bDATETIME\b(?!\s*\(3\))') {
    $failures.Add('日期时间列必须使用DATETIME(3)')
}
if (@([regex]::Matches($statements, '(?i)ENGINE\s*=\s*InnoDB')).Count -ne 27 -or
    @([regex]::Matches($statements, '(?i)DEFAULT\s+CHARSET\s*=\s*utf8mb4')).Count -ne 27) {
    $failures.Add('每张业务表必须显式声明InnoDB和utf8mb4')
}

# V9是用户授权的数据清理。仅允许删除二手交易表及迁移辅助表，保留历史建表契约。
$retirement = ($retirementFiles | ForEach-Object { Get-Content -LiteralPath $_.FullName -Raw -Encoding UTF8 }) -join "`n"
$retirement = [regex]::Replace($retirement, '(?s)/\*.*?\*/|(?m)^\s*--[^\r\n]*', '')
$helpers = @('retired_market_comments', 'retired_market_reports', 'retired_market_announcements', 'retired_market_tasks', 'retired_market_assets')
$allowedDrops = @('xl_product', 'xl_conversation_product') + $helpers
$drops = @([regex]::Matches($retirement, '(?i)\bDROP\s+TABLE\s+(?:IF\s+EXISTS\s+)?([a-z_]+)') | ForEach-Object { $_.Groups[1].Value })
if ($drops.Count -ne 7 -or @($drops | Where-Object { $_ -notin $allowedDrops }).Count -gt 0) {
    $failures.Add('V9只能删除两张二手交易业务表及五张辅助表')
}
foreach ($helper in $helpers) {
    if ($retirement -notmatch "(?i)\bCREATE\s+TABLE\s+$helper\b" -or $helper -notin $drops) {
        $failures.Add("V9辅助表必须创建并删除：$helper")
    }
}
foreach ($match in [regex]::Matches($retirement, '(?i)\bINSERT\s+(?:IGNORE\s+)?INTO\s+([a-z_]+)')) {
    if ($match.Groups[1].Value -notin $helpers) { $failures.Add('V9不得插入业务数据') }
}
if ($retirement -match '(?i)\b(?:CREATE|DROP)\s+(?:DATABASE|SCHEMA|USER)\b|\b(?:GRANT|TRUNCATE)\b' -or $retirement -match $sensitiveColumns) {
    $failures.Add('V9包含越界建库、账号、权限、截断或敏感字段操作')
}
if ($retirement -match '(?i)\bDELETE\s+FROM\s+xl_(?:user|post|message|conversation|conversation_member)\s') {
    $failures.Add('V9不得删除用户、帖子、文字消息或会话')
}

if ($failures.Count -gt 0) {
    foreach ($failure in $failures) { Write-Host "失败：$failure" -ForegroundColor Red }
    throw "数据库静态契约检查失败：$($failures.Count)项。"
}
Write-Host "数据库静态契约检查通过：$($files.Count)个迁移文件，历史27张业务表，V9移除2张后25张；未连接数据库。"
