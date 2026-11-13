import { SheetTransition } from '../../utils/sheet-transition'
const transitions = new WeakMap<object, SheetTransition>()
Component({
  properties: { visible: { type: Boolean, value: false }, busy: { type: Boolean, value: false }, error: { type: String, value: '' } },
  data: { avatarPath: '', mounted: false, opened: false },
  observers: { visible(value: boolean) { this.syncVisibility(value) } },
  lifetimes: { detached() { transitions.get(this)?.dispose(); transitions.delete(this) } },
  methods: {
    syncVisibility(visible: boolean) {
      if (!transitions.has(this)) transitions.set(this, new SheetTransition(patch => this.setData({ ...patch, ...(patch.mounted === false ? { avatarPath: '' } : {}) })))
      transitions.get(this)!.setVisible(visible)
    },
    stopTap() {},
    onChooseAvatar(event: WechatMiniprogram.CustomEvent<{ avatarUrl: string }>) {
      if (!this.data.busy && event.detail.avatarUrl) this.setData({ avatarPath: event.detail.avatarUrl })
    },
    onCancel() { if (!this.data.busy) this.triggerEvent('cancel') },
    onConfirm(event: WechatMiniprogram.CustomEvent<{ value: { nickname: string } }>) {
      if (this.data.busy) return
      // 使用form收集微信昵称控件最终值，避免绕过原生内容检查后的清空结果。
      const nickname = (event.detail.value.nickname || '').trim()
      if (!this.data.avatarPath || !nickname || nickname.length > 20) { wx.showToast({ title: '请选择头像并填写昵称', icon: 'none' }); return }
      this.triggerEvent('confirm', { nickname, avatarPath: this.data.avatarPath })
    },
  },
})
