import { requireLocalSession } from '../../features/profile/repository'
import { registerPage } from '../../backend/pages'
import { readForum } from '../../features/forum/repository'
import { REPORT_REASONS, ReportReason, ReportTargetKind, submitLocalReport } from '../../features/moderation/repository'
import { resolvePeer } from '../../features/contact/repository'

registerPage(wx, Page, 'report')({
  data: { targetKind: 'post' as ReportTargetKind, targetId: '', targetTitle: '', reasons: REPORT_REASONS, reason: '' as ReportReason | '', detail: '', evidence: [] as string[], targetMissing: false, submitting: false },
  onLoad(options: Record<string, string | undefined> = {}) {
    const targetKind: ReportTargetKind = ['post', 'comment', 'user'].includes(options.kind ?? '') ? options.kind as ReportTargetKind : 'post'
    const targetId = options.id ?? ''
    let targetTitle = '', targetMissing = !targetId
    try {
      // 与真机侧保持一致：评论举报不拉取对象详情，只保留 id 与固定标题。
      if (targetKind === 'comment') targetTitle = '举报评论'
      else if (targetKind === 'post') targetTitle = readForum(wx).posts.find(post => post.id === targetId)?.content.slice(0, 40) ?? ''
      else targetTitle = resolvePeer(wx, targetId)?.nickname ?? ''
      if (!targetTitle) targetMissing = true
    } catch { targetMissing = true }
    this.setData({ targetKind, targetId, targetTitle, targetMissing })
  },
  onReasonTap(event: WechatMiniprogram.TouchEvent) {
    const reason = event.currentTarget.dataset.reason as ReportReason
    if (REPORT_REASONS.includes(reason)) this.setData({ reason })
  },
  onDetailInput(event: WechatMiniprogram.CustomEvent<{ value: string }>) { this.setData({ detail: event.detail.value.slice(0, 200) }) },
  onChooseEvidence() {
    const remaining = 3 - this.data.evidence.length
    if (remaining <= 0) { wx.showToast({ title: '最多添加 3 张截图', icon: 'none' }); return }
    wx.chooseMedia({ count: remaining, mediaType: ['image'], sourceType: ['album', 'camera'], sizeType: ['compressed'],
      success: result => this.setData({ evidence: [...this.data.evidence, ...result.tempFiles.map(file => file.tempFilePath).filter(Boolean)].slice(0, 3) }),
      fail: result => { if (!result.errMsg.includes('cancel')) wx.showToast({ title: '截图未添加，请重试', icon: 'none' }) },
    })
  },
  onRemoveEvidence(event: WechatMiniprogram.TouchEvent) {
    const index = Number(event.currentTarget.dataset.index)
    if (Number.isInteger(index)) this.setData({ evidence: this.data.evidence.filter((_, i) => i !== index) })
  },
  _submitted: false,
  onSubmitReport() {
    if (this._submitted || this.data.submitting || this.data.targetMissing || !requireLocalSession(wx)) return
    this.setData({ submitting: true })
    try {
      submitLocalReport(wx, { targetKind: this.data.targetKind, targetId: this.data.targetId, reason: this.data.reason,
        detail: this.data.detail, evidence: this.data.evidence })
      this._submitted = true
      wx.showModal({ title: '举报已记录', content: '当前为本地模拟记录，不会发送到平台处理。', showCancel: false, confirmText: '知道了', success: () => wx.navigateBack({ delta: 1 }) })
    } catch (error) {
      const message = error && typeof error === 'object' && 'message' in error ? String((error as { message: unknown }).message) : '举报未保存，请重试'
      wx.showToast({ title: message, icon: 'none' })
    }
    finally { this.setData({ submitting: false }) }
  },
})
