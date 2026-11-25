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
$categoryFiles = @($files | Where-Object Name -eq 'V10__forum_categories.sql')
$openFiles = @($files | Where-Object Name -eq 'V11__open_access.sql')
$retirementFiles = @($files | Where-Object Name -eq 'V9__remove_marketplace.sql')
$deliveryFiles = @($files | Where-Object Name -eq 'V12__notification_delivery.sql')
$viewFiles = @($files | Where-Object Name -eq 'V13__post_view_statistics.sql')
$luckyFiles = @($files | Where-Object Name -eq 'V14__lucky_bags.sql')
$retiredNoticeFiles = @($files | Where-Object Name -eq 'V19__retire_lucky_bag_notifications.sql')
$aiManagementFiles = @($files | Where-Object Name -eq 'V20__ai_service_management.sql')
if ($historicalFiles.Count -ne 8 -or $retirementFiles.Count -ne 1 -or $files.Count -ne 20 -or $categoryFiles.Count -ne 1 -or $openFiles.Count -ne 1 -or $deliveryFiles.Count -ne 1 -or $viewFiles.Count -ne 1 -or $luckyFiles.Count -ne 1 -or $retiredNoticeFiles.Count -ne 1 -or $aiManagementFiles.Count -ne 1) {
    $failures.Add('预期保留V1–V19并新增V20 AI服务配置和用量迁移')
}
if ($retiredNoticeFiles.Count -eq 1) {
    $retiredNotices = (Get-Content -LiteralPath $retiredNoticeFiles[0].FullName -Raw -Encoding UTF8) -replace '(?m)^--[^\r\n]*','' -replace '\s',''
    if ($retiredNotices -cne "UPDATExl_user_notificationSETactive=0,updated_at=UTC_TIMESTAMP(3),version=version+1WHEREevent_kind='lucky_bag_win'ANDactive=1;") {
        $failures.Add('V19应只停用仍活跃的历史中奖通知，保留既有记录')
    }
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


$categories = ($categoryFiles | ForEach-Object { Get-Content -LiteralPath $_.FullName -Raw -Encoding UTF8 }) -join "`n"
$categories = [regex]::Replace($categories, '(?m)^\s*--[^\r\n]*', '')
if ($categories -notmatch "CHECK\s*\(category IN \('campus', 'lost_found', 'study', 'help', 'secondhand', 'complain', 'buddy'\)\)" -or
    $categories -notmatch 'ALTER TABLE xl_post DROP CHECK ck_post_category' -or
    $categories -match '(?i)\b(?:CREATE|INSERT|UPDATE|DELETE|TRUNCATE)\b|DROP\s+TABLE') {
    $failures.Add('V10 must only expand forum categories, retaining study without restoring marketplace tables')
}
$open = ($openFiles | ForEach-Object { Get-Content -LiteralPath $_.FullName -Raw -Encoding UTF8 }) -join "`n"
$open = [regex]::Replace($open, '(?m)^\s*--[^\r\n]*', '')
if ($open -notmatch "UPDATE xl_beta_stage SET phase = 'open', version = version \+ 1, updated_at = UTC_TIMESTAMP\(3\)\s+WHERE id = 1 AND phase = 'beta'" -or
    $open -match '(?i)\b(?:CREATE|DROP|DELETE|INSERT|TRUNCATE|GRANT)\b') {
    $failures.Add('V11 must open existing beta stages and increment version, preserving historical bindings')
}
$contractPath = Join-Path $PSScriptRoot '../../../../../scripts/database-table-contract.json'
$currentTables = @(Get-Content -LiteralPath $contractPath -Raw | ConvertFrom-Json)
$delivery = ($deliveryFiles | ForEach-Object { Get-Content -LiteralPath $_.FullName -Raw -Encoding UTF8 }) -join "`n"
$delivery = [regex]::Replace($delivery, '(?m)^\s*--[^\r\n]*', '')
$deliveryTables = @([regex]::Matches($delivery, '(?i)\bCREATE\s+TABLE\s+(xl_[a-z_]+)\s*\(') | ForEach-Object { $_.Groups[1].Value })
$expectedDelivery = @('xl_notification_stream','xl_notification_outbox','xl_notification_inbox','xl_notification_failure')
if ($deliveryTables.Count -ne $expectedDelivery.Count -or @(Compare-Object $expectedDelivery $deliveryTables).Count -gt 0 -or
    @([regex]::Matches($delivery, '(?i)ENGINE\s*=\s*InnoDB')).Count -ne $expectedDelivery.Count -or
    @([regex]::Matches($delivery, '(?i)DEFAULT\s+CHARSET\s*=\s*utf8mb4')).Count -ne $expectedDelivery.Count -or
    $delivery -match '(?i)\b(?:DROP|DELETE|INSERT|TRUNCATE|GRANT|REVOKE)\b|\b(?:CREATE)\s+(?:DATABASE|USER)\b' -or
    $delivery -match $sensitiveColumns -or $delivery -match '(?i)\bDATETIME\b(?!\s*\(3\))') {
    $failures.Add('V12 must only create four InnoDB/utf8mb4 delivery tables with UTC millisecond dates')
}
$views = ($viewFiles | ForEach-Object { Get-Content -LiteralPath $_.FullName -Raw -Encoding UTF8 }) -join "`n"
$views = [regex]::Replace($views, '(?m)^\s*--[^\r\n]*', '')
$viewTables = @([regex]::Matches($views, '(?i)\bCREATE\s+TABLE\s+(xl_[a-z_]+)\s*\(') | ForEach-Object { $_.Groups[1].Value })
$expectedViews = @('xl_view_receipt','xl_view_event','xl_view_daily','xl_view_visitor_day','xl_view_projection','xl_view_failure')
if ($viewTables.Count -ne $expectedViews.Count -or @(Compare-Object $expectedViews $viewTables).Count -gt 0 -or
    @([regex]::Matches($views, '(?i)ENGINE\s*=\s*InnoDB')).Count -ne $expectedViews.Count -or
    @([regex]::Matches($views, '(?i)DEFAULT\s+CHARSET\s*=\s*utf8mb4')).Count -ne $expectedViews.Count -or
    $views -match '(?i)\b(?:DROP|DELETE|INSERT|TRUNCATE|GRANT|REVOKE)\b|\b(?:CREATE)\s+(?:DATABASE|USER)\b|\bFOREIGN\s+KEY\b' -or
    $views -match $sensitiveColumns -or $views -match '(?i)\bDATETIME\b(?!\s*\(3\))') {
    $failures.Add('V13 must only create six InnoDB/utf8mb4 statistics tables without identity or lifecycle FK coupling')
}
foreach ($required in @('PRIMARY KEY (event_id)','PRIMARY KEY (post_id,view_date,visitor_key)',
        'PRIMARY KEY (topic,partition_id,record_offset)','idx_view_receipt_due','idx_view_receipt_lease',
        'idx_view_daily_window','idx_view_visitor_window','ck_view_receipt_lease','ck_view_receipt_payload',
        'ck_view_projection_singleton','ck_view_failure_state')) {
    if (-not $views.Contains($required)) { $failures.Add("V13 missing exact deduplication, repair or projection contract: $required") }
}
$lucky = ($luckyFiles | ForEach-Object { Get-Content -LiteralPath $_.FullName -Raw -Encoding UTF8 }) -join "`n"
$lucky = [regex]::Replace($lucky, '(?s)/\*.*?\*/|(?m)^\s*--[^\r\n]*', '')
$luckyTables = @([regex]::Matches($lucky, '(?i)\bCREATE\s+TABLE\s+(xl_[a-z_]+)\s*\(') | ForEach-Object { $_.Groups[1].Value })
$expectedLucky = @('xl_lucky_bag','xl_lucky_display','xl_lucky_entry','xl_lucky_result','xl_lucky_claim','xl_lucky_operation')
$luckyWithoutSingleton = [regex]::Replace($lucky, '(?i)INSERT\s+INTO\s+xl_lucky_display\s*\(id,\s*activity_id\)\s*VALUES\s*\(1,\s*NULL\)\s*;', '')
$luckyWithoutAllowedDrops = [regex]::Replace($luckyWithoutSingleton, '(?i)DROP\s+CHECK\s+(?:ck_notice_kind|ck_notice_target|ck_audit_module)\b', '')
$luckyWithoutAllowedDrops = [regex]::Replace($luckyWithoutAllowedDrops, '(?i)\bON\s+DELETE\s+RESTRICT\b', '')
if ($luckyTables.Count -ne 6 -or @(Compare-Object $expectedLucky $luckyTables).Count -gt 0 -or
    @([regex]::Matches($lucky, '(?i)ENGINE\s*=\s*InnoDB')).Count -ne 6 -or
    @([regex]::Matches($lucky, '(?i)DEFAULT\s+CHARSET\s*=\s*utf8mb4')).Count -ne 6 -or
    $luckyWithoutAllowedDrops -match '(?i)\b(?:DROP|DELETE|INSERT|UPDATE|TRUNCATE|GRANT|REVOKE)\b|\bCREATE\s+(?:DATABASE|SCHEMA|USER)\b' -or
    $lucky -match $sensitiveColumns -or $lucky -match '(?i)\bDATETIME\b(?!\s*\(3\))') {
    $failures.Add('V14 must create exactly six InnoDB/utf8mb4 lucky-bag tables, seed only the empty display singleton, and preserve data/account privileges')
}
foreach ($required in @('uq_lucky_entry_activity_user','uq_lucky_claim_activity','uq_lucky_claim_code',
        'idx_lucky_bag_due','idx_lucky_entry_draw','idx_lucky_operation_activity',
        'ck_lucky_display_singleton','ck_lucky_bag_status','ck_lucky_bag_prize',
        'ck_lucky_claim_status','ck_lucky_claim_lifecycle','ck_lucky_result_participants')) {
    if (-not $lucky.Contains($required)) { $failures.Add("V14 missing unique participation/result/claim, lifecycle or due scan contract: $required") }
}
$luckyNormalized = [regex]::Replace($lucky, '\s+', '')
foreach ($required in @('PRIMARYKEY(activity_id)','UNIQUEKEYuq_lucky_entry_activity_user(activity_id,user_id)',
        'UNIQUEKEYuq_lucky_claim_activity(activity_id)','UNIQUEKEYuq_lucky_claim_code(code)',
        'KEYidx_lucky_bag_due(status,draw_at,id)','KEYidx_lucky_entry_draw(activity_id,id)',
        'KEYidx_lucky_operation_activity(activity_id,id)','CHECK(id=1)',"CHECK(prize_cents=1000)")) {
    if (-not $luckyNormalized.Contains($required)) { $failures.Add("V14 missing exact key or guard definition: $required") }
}
$luckyBodies = @{}
foreach ($match in [regex]::Matches($lucky, '(?is)\bCREATE\s+TABLE\s+(xl_[a-z_]+)\s*\((.*?)\)\s*ENGINE\s*=')) {
    $luckyBodies[$match.Groups[1].Value] = $match.Groups[2].Value
}
$luckyColumnContracts = @{
    xl_lucky_display = @('id\s+TINYINT\s+UNSIGNED\s+NOT\s+NULL', 'activity_id\s+BIGINT\s+UNSIGNED\s+NULL')
    xl_lucky_entry = @('id\s+BIGINT\s+UNSIGNED\s+NOT\s+NULL\s+AUTO_INCREMENT', 'activity_id\s+BIGINT\s+UNSIGNED\s+NOT\s+NULL', 'user_id\s+BIGINT\s+UNSIGNED\s+NOT\s+NULL')
    xl_lucky_result = @('PRIMARY\s+KEY\s*\(activity_id\)', 'winner_user_id\s+BIGINT\s+UNSIGNED\s+NULL', 'participant_count\s+BIGINT\s+UNSIGNED\s+NOT\s+NULL', 'scheduled_at\s+DATETIME\(3\)\s+NOT\s+NULL', 'drawn_at\s+DATETIME\(3\)\s+NOT\s+NULL', 'algorithm\s+VARCHAR\(')
    xl_lucky_claim = @('code\s+CHAR\(32\)\s+CHARACTER\s+SET\s+ascii\s+COLLATE\s+ascii_bin\s+NOT\s+NULL', 'version\s+INT\s+UNSIGNED\s+NOT\s+NULL', 'started_by\s+BIGINT\s+UNSIGNED\s+NULL', 'started_at\s+DATETIME\(3\)\s+NULL', 'paid_by\s+BIGINT\s+UNSIGNED\s+NULL', 'paid_at\s+DATETIME\(3\)\s+NULL', 'start_operation_id\s+VARCHAR\(64\)', 'paid_operation_id\s+VARCHAR\(64\)')
    xl_lucky_operation = @('activity_id\s+BIGINT\s+UNSIGNED\s+NOT\s+NULL', 'claim_id\s+BIGINT\s+UNSIGNED\s+NULL', 'admin_id\s+BIGINT\s+UNSIGNED\s+NULL', 'action\s+VARCHAR\(', 'reason\s+VARCHAR\(', 'created_at\s+DATETIME\(3\)\s+NOT\s+NULL')
}
foreach ($table in $luckyColumnContracts.Keys) {
    foreach ($column in $luckyColumnContracts[$table]) {
        if ($luckyBodies[$table] -notmatch "(?i)$column") { $failures.Add("V14 missing column contract on ${table}: $column") }
    }
}
if (@([regex]::Matches($lucky, '(?i)INSERT\s+INTO\s+xl_lucky_display\b')).Count -ne 1 -or
    @([regex]::Matches($lucky, '(?i)\bALTER\s+TABLE\s+')).Count -ne 2 -or
    $lucky -notmatch '(?i)ALTER\s+TABLE\s+xl_user_notification\b' -or $lucky -notmatch '(?i)ALTER\s+TABLE\s+xl_audit_log\b' -or
    $lucky -notmatch "'lucky_bag_win'" -or $lucky -notmatch "'lucky_bag'" -or $lucky -notmatch "'lucky_bags'") {
    $failures.Add('V14 must initialize the singleton and extend only notification destinations/events and the audit module')
}
$expectedLuckyAlters = @(
    "ALTERTABLExl_user_notificationDROPCHECKck_notice_kind,DROPCHECKck_notice_target,ADDCONSTRAINTck_notice_kindCHECK(event_kindIN('comment','reply','post_like','comment_like','post_favorite','system','announcement','moderation_warning','lucky_bag_win')),ADDCONSTRAINTck_notice_targetCHECK((target_typeISNULLANDtarget_idISNULL)OR(target_typeISNOTNULLANDtarget_typeIN('post','comment','user','message','announcement','lucky_bag')ANDtarget_idISNOTNULLANDtarget_id>0));",
    "ALTERTABLExl_audit_logDROPCHECKck_audit_module,ADDCONSTRAINTck_audit_moduleCHECK(moduleIN('posts','comments','reports','users','announcements','notifications','settings','lucky_bags'));"
)
$actualLuckyAlters = @([regex]::Matches($lucky, '(?is)\bALTER\s+TABLE\b.*?;') | ForEach-Object { [regex]::Replace($_.Value, '\s+', '') })
if ($actualLuckyAlters.Count -ne 2 -or @(Compare-Object $expectedLuckyAlters $actualLuckyAlters).Count -gt 0) {
    $failures.Add('V14 may only expand the existing notification/audit CHECK sets, preserving every previous event, target and module')
}
$validityFiles = @($files | Where-Object Name -eq 'V17__link_ai_knowledge_validity.sql')
if ($validityFiles.Count -ne 1 -or (Get-Content -LiteralPath $validityFiles[0].FullName -Raw) -notmatch 'valid_until') { $failures.Add('V17 validity contract missing') }
$expectedAi = @('xl_ai_knowledge', 'xl_ai_knowledge_version', 'xl_ai_knowledge_chunk', 'xl_ai_knowledge_index', 'xl_ai_session', 'xl_ai_request')
$aiFiles = @($files | Where-Object Name -Match '^V1[56]__')
$aiSql = ($aiFiles | ForEach-Object { Get-Content -LiteralPath $_.FullName -Raw -Encoding UTF8 }) -join "`n"
$actualAi = @([regex]::Matches($aiSql,'(?i)CREATE\s+TABLE\s+(xl_[a-z_]+)') | ForEach-Object { $_.Groups[1].Value })
if ($aiFiles.Count -ne 2 -or $actualAi.Count -ne 6 -or @(Compare-Object $expectedAi $actualAi).Count -ne 0 -or $aiSql -match '(?i)INSERT\s+INTO|DROP\s+TABLE') { $failures.Add('AI迁移必须恰好创建六张表且不得发布业务数据') }
$aiManagementTables = @('xl_ai_runtime', 'xl_ai_usage', 'xl_ai_reindexed_vector')
$aiManagementSql = ($aiManagementFiles | ForEach-Object { Get-Content -LiteralPath $_.FullName -Raw -Encoding UTF8 }) -join "`n"
$actualAiManagement = @([regex]::Matches($aiManagementSql,'(?i)CREATE\s+TABLE\s+(xl_[a-z_]+)') | ForEach-Object { $_.Groups[1].Value })
if ($actualAiManagement.Count -ne 3 -or @(Compare-Object $aiManagementTables $actualAiManagement).Count -ne 0) {
    $failures.Add('V20须创建AI配置、用量和重建向量表')
}
$derivedCurrent = @($expectedTables | Where-Object { $_ -notin @('xl_product','xl_conversation_product') }) + $expectedDelivery + $expectedViews + $expectedLucky + $expectedAi + $aiManagementTables
if ($currentTables.Count -ne $derivedCurrent.Count -or @(Compare-Object $derivedCurrent $currentTables).Count -gt 0) {
    $failures.Add('Runtime table manifest must exactly match the post-migration table set and count')
}
$grantScript = Get-Content -LiteralPath (Join-Path $PSScriptRoot '../../../../../scripts/initialize-backend-dev.ps1') -Raw -Encoding UTF8
if ($grantScript -notmatch 'database-table-contract.json' -or $grantScript -notmatch '\$tables.Count -ne \$expectedTables.Count' -or $grantScript -match 'REVOKE ALL PRIVILEGES') {
    $failures.Add('Runtime grants must use the exact table manifest with additive grants')
}
if ($grantScript -match '--skip-log-bin' -or $grantScript -notmatch '--binlog-format=ROW' -or $grantScript -notmatch '--binlog-row-image=FULL' -or $grantScript -notmatch '--sync-binlog=1') {
    $failures.Add('Official MySQL recovery must preserve ROW/FULL durable binlog')
}
if ($grantScript -notmatch "'xl_view_event', 'xl_view_visitor_day'" -or
    $grantScript -notmatch "'xl_view_receipt', 'xl_view_daily', 'xl_view_projection', 'xl_view_failure'") {
    $failures.Add('Statistics grants must retain insert-only deduplication/membership and forbid runtime deletion of retained evidence')
}
if ($grantScript -notmatch "elseif\s*\(\`$table -in @\('xl_lucky_entry', 'xl_lucky_result', 'xl_lucky_operation'\)\)\s*\{ 'INSERT' \}" -or
    $grantScript -notmatch "elseif\s*\(\`$table -in @\('xl_lucky_bag', 'xl_lucky_display', 'xl_lucky_claim'\)\)\s*\{ 'INSERT, UPDATE' \}") {
    $failures.Add('Lucky-bag entries/results/operations must be insert-only; bags/display/claims may update, with no runtime DELETE')
}

if ($failures.Count -gt 0) {
    foreach ($failure in $failures) { Write-Host "失败：$failure" -ForegroundColor Red }
    throw "数据库静态契约检查失败：$($failures.Count)项。"
}
Write-Host "数据库静态契约检查通过：$($files.Count)个迁移文件，当前$($currentTables.Count)张业务表，精确表名/数量/授权及binlog恢复契约；未连接数据库。"
