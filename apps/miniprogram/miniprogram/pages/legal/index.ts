Page({
  data: {
    tab: 'privacy' as 'terms' | 'privacy',
    authVisible: false,
    authSaving: false,
    authError: '',
  },
  onLoad(query: Record<string, string | undefined>) {
    if (query?.tab === 'terms' || query?.tab === 'privacy') {
      this.setData({ tab: query.tab })
    }
  },
  onTabTap(event: WechatMiniprogram.TouchEvent) {
    const tab = event.currentTarget.dataset.tab
    if (tab === 'terms' || tab === 'privacy') {
      this.setData({ tab })
    }
  },
  onOpenWechatPrivacy() {
    const runtime = wx as unknown as { openPrivacyContract?: (options: { fail?: () => void }) => void }
    if (typeof runtime.openPrivacyContract === 'function') {
      runtime.openPrivacyContract({
        fail() {
          wx.showToast({ title: '暂无法打开微信隐私指引', icon: 'none' })
        },
      })
      return
    }
    wx.showToast({ title: '当前微信版本不支持直接查看官方隐私指引', icon: 'none' })
  },
  onProfileAuthConfirm() {},
  onProfileAuthCancel() {
    this.setData({ authVisible: false, authError: '' })
  },
})
