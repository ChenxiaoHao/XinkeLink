import { registerPage } from '../../backend/pages'
import { defaultProfile, readLocalSession, readProfile, requireLocalSession, saveProfile, setLocalSession } from '../../features/profile/repository'
import { persistProfileAvatar, releaseProfileAvatar } from '../../features/profile/avatar'
const FIELDS = ['nickname', 'bio', 'major', 'className', 'studentId', 'year'] as const
registerPage(wx, Page, 'profile-edit')({
  data: { form: defaultProfile(), loadFailed: false, saving: false, choosing: false },
  onLoad() {
    // 访客不能编辑资料：弹一键登录，登录成功后原地加载表单继续。
    if (readLocalSession(wx)) { this.reload(); return }
    wx.showModal({ title: '当前为访客浏览', content: '登录后才能编辑个人资料，是否现在登录？', confirmText: '一键登录', success: result => {
      if (!result.confirm) { wx.navigateBack({ delta: 1, fail() { wx.switchTab({ url: '/pages/forum/index' }) } }); return }
      try { setLocalSession(wx, true); this.reload() } catch { wx.showToast({ title: '登录未完成，请重试', icon: 'none' }) }
    } })
  },
  reload() {
    try { this.setData({ form: readProfile(wx), loadFailed: false }) }
    catch { this.setData({ loadFailed: true }) }
  },
  onFieldInput(event: WechatMiniprogram.CustomEvent<{ value: string }>) {
    const field = event.currentTarget.dataset.field
    if (!this.data.saving && !this.data.loadFailed && FIELDS.some(item => item === field)) this.setData({ form: { ...this.data.form, [field]: event.detail.value } })
  },
  onChooseAvatar(event: WechatMiniprogram.CustomEvent<{ avatarUrl: string }>) {
    if (!this.data.saving && event.detail.avatarUrl) this.setData({ form: { ...this.data.form, avatar: event.detail.avatarUrl } })
  },
  onNicknameBlur(event: WechatMiniprogram.CustomEvent<{ value: string }>) {
    if (!this.data.saving) this.setData({ form: { ...this.data.form, nickname: event.detail.value } })
  },
  onAvatarTap() {
    if (!requireLocalSession(wx) || this.data.saving || this.data.choosing || this.data.loadFailed) return
    this.setData({ choosing: true })
    wx.chooseMedia({ count: 1, mediaType: ['image'], sourceType: ['album', 'camera'], sizeType: ['compressed'], success: result => {
      const file = result.tempFiles[0]
      if (!file?.tempFilePath || file.size > 10 * 1024 * 1024) { wx.showToast({ title: '请选择 10MB 以内的头像图片', icon: 'none' }); return }
      this.setData({ form: { ...this.data.form, avatar: file.tempFilePath } })
    }, fail: result => { if (!result.errMsg.includes('cancel')) wx.showToast({ title: '头像未选取，请重试', icon: 'none' }) }, complete: () => { this.setData({ choosing: false }) } })
  },
  onSaveTap() {
    if (!requireLocalSession(wx) || this.data.loadFailed || this.data.saving || this.data.choosing) return
    const form = this.data.form
    if (!form.nickname.trim() || form.nickname.length > 20 || form.bio.length > 40 || form.major.length > 60 || form.className.length > 40 || form.studentId.length > 32 || (form.year && !/^(19|20)\d{2}$/.test(form.year))) {
      wx.showToast({ title: '请检查昵称和资料格式', icon: 'none' }); return
    }
    this.setData({ saving: true }); let created = ''
    try {
      const result = persistProfileAvatar(wx, form.avatar); created = result.created
      saveProfile(wx, { ...form, avatar: result.avatar })
      this.setData({ form: { ...form, avatar: result.avatar } }); wx.showToast({ title: '资料已保存', icon: 'success' })
      wx.navigateBack({ delta: 1, fail() { wx.navigateTo({ url: '/pages/profile/index' }) } })
    } catch { if (created) releaseProfileAvatar(wx, created); wx.showToast({ title: '资料未保存，请检查图片或本地空间', icon: 'none' }) }
    finally { this.setData({ saving: false }) }
  },
})
