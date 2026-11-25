import { registerPage } from '../../backend/pages'
import { Runtime } from '../../backend/client'
import { previewForumImages } from '../../features/forum/media'
import { emptyPostDraft, persistForumImages, releaseForumImages, validatePostDraft } from '../../features/forum/publish'
import { publishForumPost, readLocalPosts, updateOwnPost } from '../../features/forum/repository'
import { FORUM_CATEGORIES, PostCategory } from '../../features/forum/types'
import { localIdentity, requireLocalSession } from '../../features/profile/repository'
import { getViewerRole } from '../../features/profile/viewer'
import { readPostDraft, savePostDraft, clearPostDraft } from '../../features/forum/drafts'
import { menuRightSpace } from '../../utils/layout'

const CATEGORIES = FORUM_CATEGORIES.filter(item => item.key !== 'recommended' && item.key !== 'latest')
const DEFAULT_CATEGORY_INDEX = Math.max(0, CATEGORIES.findIndex(item => item.key === emptyPostDraft().category))

registerPage(wx, Page, 'forum-publish')({
  data: {
    form: emptyPostDraft(),
    categories: CATEGORIES,
    categoryIndex: DEFAULT_CATEGORY_INDEX,
    categoryLabel: CATEGORIES[DEFAULT_CATEGORY_INDEX].label,
    publishing: false, loadFailed: false, keyboardHeight: 0,
    selfAvatar: '', selfNickname: '', statusHeight: 20, rightSpace: 104, headerTop: 66, editId: '',
  },
  onLoad(options: Record<string, string | undefined> = {}) {
    try {
      const info = wx.getWindowInfo(), menu = wx.getMenuButtonBoundingClientRect(), statusHeight = info.statusBarHeight || 20
      const valid = menu.height > 0 && menu.top >= statusHeight
      // 取消/发布整行放在胶囊下方，避免和右上角胶囊挤在一起。
      this.setData({ statusHeight, rightSpace: valid ? menuRightSpace(info.windowWidth, menu.left) : 104, headerTop: valid ? menu.bottom + 8 : statusHeight + 50 })
    } catch { /* 取不到系统几何时保持默认值。 */ }
    this.setData({ editId: options.editId || '' })
    if (getViewerRole(wx) === 'guest') { this.setData({ form: emptyPostDraft(), selfAvatar: '', selfNickname: '', loadFailed: true }); return }
    try { const self = localIdentity(wx); this.setData({ selfAvatar: self.avatar, selfNickname: self.nickname }) } catch { /* 资料读不到时显示本人。 */ }
    if ((wx as Runtime).__fixtureMode === true && options.editId) {
      try {
        const post = readLocalPosts(wx).find(item => item.id === options.editId)
        if (!post) { this.setData({ loadFailed: true }); wx.showToast({ title: '帖子不存在', icon: 'none' }); return }
        const draft = readPostDraft(wx, post.id)
        const form = { content: post.content, category: post.category, images: [...post.images], ...draft, anonymous: !!post.anonymousNumber }
        if (form.category === 'study') this.setData({ categories: [...CATEGORIES, { key: 'study', label: '学习交流（历史）', short: '学习交流' }] })
        const index = this.data.categories.findIndex(item => item.key === form.category)
        this.setData({ form, categoryIndex: index >= 0 ? index : DEFAULT_CATEGORY_INDEX,
          categoryLabel: index >= 0 ? this.data.categories[index].label : CATEGORIES[DEFAULT_CATEGORY_INDEX].label })
      } catch { this.setData({ loadFailed: true }); wx.showToast({ title: '帖子读取失败', icon: 'none' }) }
    } else if (options.draft === '1') {
      try {
        const draft = readPostDraft(wx)
        if (draft) {
          const index = CATEGORIES.findIndex(item => item.key === draft.category)
          this.setData({ form: draft, categoryIndex: index >= 0 ? index : DEFAULT_CATEGORY_INDEX,
            categoryLabel: index >= 0 ? this.data.categories[index].label : CATEGORIES[DEFAULT_CATEGORY_INDEX].label })
        }
      } catch { wx.showToast({ title: '草稿读取失败', icon: 'none' }) }
    }
  },
  onShow() {
    if (getViewerRole(wx) === 'guest') this.setData({ form: emptyPostDraft(), selfAvatar: '', selfNickname: '', loadFailed: true })
  },
  onKeyboardHeightChange(event: WechatMiniprogram.CustomEvent<{ height: number }>) {
    this.setData({ keyboardHeight: Math.max(0, event.detail.height || 0) })
  },
  onSaveDraft() {
    if (!requireLocalSession(wx) || this.data.publishing || this._published || this.data.loadFailed) return
    try { if (savePostDraft(wx, this.data.form, this.data.editId)) wx.showToast({ title: '文字草稿已保存', icon: 'success' }) }
    catch { wx.showToast({ title: '草稿未保存，请重试', icon: 'none' }) }
  },
  _choosing: false, _published: false, _leaving: false,
  onContentInput(event: WechatMiniprogram.CustomEvent<{ value: string }>) {
    if (!this.data.publishing) this.setData({ form: { ...this.data.form, content: event.detail.value.slice(0, 500) } })
  },
  setCategory(key: PostCategory) {
    const index = this.data.categories.findIndex(item => item.key === key)
    if (index < 0 || this.data.publishing) return
    this.setData({ form: { ...this.data.form, category: key }, categoryIndex: index, categoryLabel: this.data.categories[index].label })
  },
  onCategoryTap(event: WechatMiniprogram.TouchEvent) { this.setCategory(event.currentTarget.dataset.category as PostCategory) },
  onCategoryPickerChange(event: WechatMiniprogram.CustomEvent<{ value: string }>) {
    const selected = this.data.categories[Number(event.detail.value)]
    if (selected) this.setCategory(selected.key as PostCategory)
  },
  onAnonymousTap() {
    if (!this.data.publishing && !this.data.editId) this.setData({ form: { ...this.data.form, anonymous: !this.data.form.anonymous } })
  },
  onAnonymousChange(event: WechatMiniprogram.CustomEvent<{ value: boolean }>) {
    if (!this.data.publishing && !this.data.editId) this.setData({ form: { ...this.data.form, anonymous: event.detail.value } })
  },
  onAddImages() {
    if (this._choosing || this.data.publishing || this._published) return
    const count = 9 - this.data.form.images.length
    if (count <= 0) { wx.showToast({ title: '最多选择 9 张图片', icon: 'none' }); return }
    this._choosing = true
    wx.chooseMedia({ count, mediaType: ['image'], sourceType: ['album', 'camera'], sizeType: ['compressed'], success: result => {
      this._choosing = false
      const images = result.tempFiles.filter(file => file.size <= 10 * 1024 * 1024 && !!file.tempFilePath).slice(0, count).map(file => file.tempFilePath)
      this.setData({ form: { ...this.data.form, images: [...this.data.form.images, ...images].slice(0, 9) } })
      if (result.tempFiles.some(file => file.size > 10 * 1024 * 1024)) wx.showToast({ title: '单张图片不能超过 10MB', icon: 'none' })
    }, fail: result => { this._choosing = false; if (!result.errMsg.includes('cancel')) wx.showToast({ title: '图片未选取，请重试', icon: 'none' }) } })
  },
  onRemoveImage(event: WechatMiniprogram.TouchEvent) {
    const index = Number(event.currentTarget.dataset.index)
    if (this.data.publishing || !Number.isInteger(index) || !this.data.form.images[index]) return
    this.setData({ form: { ...this.data.form, images: this.data.form.images.filter((_, position) => position !== index) } })
  },
  onPreviewImage(event: WechatMiniprogram.TouchEvent) { previewForumImages(wx, this.data.form.images, Number(event.currentTarget.dataset.index)) },
  leave() { wx.navigateBack({ delta: 1, fail() { wx.switchTab({ url: '/pages/forum/index' }) } }) },
  onCancelTap() {
    if (this.data.publishing || this._leaving) return
    if (!this.data.form.content.trim() && !this.data.form.images.length) { this.leave(); return }
    this._leaving = true
    wx.showModal({ title: '放弃本次编辑？', content: '退出后，本次输入的正文和图片不会保留。', confirmText: '放弃编辑', success: result => {
      this._leaving = false; if (result.confirm) this.leave()
    }, fail: () => { this._leaving = false } })
  },
  onPublishTap() {
    if (!requireLocalSession(wx)) return
    if (this.data.publishing || this._choosing || this._published || this.data.loadFailed) return
    const error = validatePostDraft(this.data.form)
    if (error) { wx.showToast({ title: error, icon: 'none' }); return }
    this.setData({ publishing: true }); let copied: string[] = []
    try {
      const result = persistForumImages(wx, this.data.form.images); copied = result.created
      const post = this.data.editId
        ? updateOwnPost(wx, this.data.editId, this.data.form, result.images)
        : publishForumPost(wx, this.data.form, result.images)
      this._published = true
      try { clearPostDraft(wx, this.data.editId) } catch { /* 发布已成功，清理失败不重复发布。 */ }
      this.setData({ form: { ...this.data.form, images: result.images } })
      wx.showToast({ title: this.data.editId ? '修改已保存' : '发布成功', icon: 'success' })
      wx.redirectTo({ url: '/pages/forum-detail/index?id=' + encodeURIComponent(post.id), fail() {
        wx.showModal({ title: '发布成功', content: '动态已保存，可以返回论坛查看。', showCancel: false, confirmText: '返回论坛', success: () => wx.switchTab({ url: '/pages/forum/index' }) })
      } })
    } catch { releaseForumImages(wx, copied); wx.showToast({ title: '发布未完成，请检查图片和本地空间', icon: 'none' }) }
    finally { this.setData({ publishing: false }) }
  },
})
