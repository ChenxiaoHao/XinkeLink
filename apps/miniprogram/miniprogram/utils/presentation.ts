/** 聊天时间按本地日期展示，避免不同设备的 locale 产生冗长格式。 */
export function chatTimeLabel(timestamp: number, now = Date.now()): string {
  if (!Number.isFinite(timestamp)) return ''
  const date = new Date(timestamp), today = new Date(now)
  const time = String(date.getHours()).padStart(2, '0') + ':' + String(date.getMinutes()).padStart(2, '0')
  const start = new Date(today.getFullYear(), today.getMonth(), today.getDate()).getTime()
  if (timestamp >= start && timestamp < start + 86400000) return time
  const yesterday = new Date(today.getFullYear(), today.getMonth(), today.getDate() - 1)
  if (date.getFullYear() === yesterday.getFullYear() && date.getMonth() === yesterday.getMonth() && date.getDate() === yesterday.getDate()) return '昨天 ' + time
  return (date.getFullYear() === today.getFullYear() ? '' : date.getFullYear() + '年') + (date.getMonth() + 1) + '月' + date.getDate() + '日 ' + time
}

const CJK = /^[\u4e00-\u9fa5]+$/

/**
 * 头像占位字：中国人名取名字最后一个字（陈浩 → 浩），
 * 与设计稿「红色圆底 + 浩」的口径一致；其他情况回退首字。
 */
export function avatarInitial(nickname: string): string {
  const name = (nickname || '').trim()
  if (!name) return '我'
  return CJK.test(name) && name.length > 1 ? name.slice(-1) : name.slice(0, 1)
}

/** 帖子详情的时间戳行：下午 2:31 · 2026年11月12日。 */
export function detailStamp(timestamp: number): string {
  if (!Number.isFinite(timestamp) || !timestamp) return ''
  const date = new Date(timestamp)
  const hours = date.getHours()
  const suffix = hours < 6 ? '凌晨' : hours < 12 ? '上午' : hours < 13 ? '中午' : hours < 18 ? '下午' : '晚上'
  const hour12 = hours % 12 === 0 ? 12 : hours % 12
  return suffix + ' ' + hour12 + ':' + String(date.getMinutes()).padStart(2, '0') + ' · ' +
    date.getFullYear() + '年' + (date.getMonth() + 1) + '月' + date.getDate() + '日'
}

/** 通知的时间用相对口径（12分钟 / 1小时 / 3小时 / 昨天），比日期更贴近「刚刚发生」。 */
export function relativeNoticeLabel(timestamp: number, now = Date.now()): string {
  if (!Number.isFinite(timestamp) || !timestamp) return ''
  const minutes = Math.max(0, Math.floor((now - timestamp) / 60000))
  if (minutes < 1) return '刚刚'
  if (minutes < 60) return minutes + '分钟'
  if (minutes < 1440) return Math.floor(minutes / 60) + '小时'
  if (minutes < 2880) return '昨天'
  return Math.floor(minutes / 1440) + '天'
}
