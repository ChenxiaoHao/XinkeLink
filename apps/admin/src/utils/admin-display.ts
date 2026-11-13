import type { AdminSettings } from '../stores/admin-settings'

type ReviewSettings = AdminSettings['contentReview']

// 比较字段内容，避免服务端 JSON 键顺序变化被误认为修改。
export function reviewSettingsChanged(form: ReviewSettings, saved: ReviewSettings) {
  return form.sensitiveWordIntercept !== saved.sensitiveWordIntercept
    || form.nightQueueMode !== saved.nightQueueMode
    || form.imageSensitivity !== saved.imageSensitivity
    || JSON.stringify(form.sensitiveWords) !== JSON.stringify(saved.sensitiveWords)
}

export function hasSeparateTitle(post: { title: string; content: string }) {
  const title = post.title.trim().replace(/[…]+$/, '')
  return Boolean(title && !post.content.trim().startsWith(title))
}

const reportReasons: Record<string, string> = {
  commercial_ad: '商业广告或引流', fraud_or_offsite_payment: '疑似诈骗或诱导站外付款',
  harassment: '骚扰或人身攻击', impersonation: '冒用他人或官方身份', other: '其他违规行为',
}
export const reportReasonLabel = (code: string) => reportReasons[code] ?? code
export const shortReference = (value: string) => value.length > 22 ? `${value.slice(0, 12)}…${value.slice(-6)}` : value
