import { registerPage } from '../../backend/pages'

const SECTIONS: Record<string, { title: string; description: string }> = {
  subscriptions: { title: '订阅', description: '在 X 上，订阅是用月费支持创作者并解锁专属内容。信科 Link 面向校园，暂时不做付费订阅。' },
  money: { title: 'Money', description: '在 X 上，Money 用于内容打赏与收益结算。信科 Link 没有交易与结算场景，该入口不会开放。' },
  communities: { title: '社群', description: '在 X 上，社群是围绕兴趣主题的多人讨论区。当前校园讨论统一在首页动态中进行。' },
  lists: { title: '列表', description: '在 X 上，列表可以把关注的人分成不同时间线。关注体系暂未开放，列表也随之暂缓。' },
  spaces: { title: '空间', description: '在 X 上，空间是语音直播房。小程序端暂无语音直播能力，该入口保留位置但不开放。' },
  studio: { title: '创作者工作室', description: '在 X 上，创作者工作室展示内容数据与变现。信科 Link 的数据看板目前只在管理后台对管理员开放。' },
  account: { title: '添加账号', description: '在 X 上，这里可以添加并切换多个账号。信科 Link 当前为单账号演示，登录入口在各页面的受保护操作中按需唤起。' },
}
const FALLBACK = { title: '功能建设中', description: '这个入口暂时没有对应的校园功能。' }

registerPage(wx, Page, 'account-feature')({
  data: { title: FALLBACK.title, description: FALLBACK.description, authVisible: false, authSaving: false, authError: '' },
  onLoad(options?: Record<string, string | undefined>) {
    const section = SECTIONS[options?.section || ''] || FALLBACK
    this.setData(section)
  },
  onShow() {},
  onBack() {
    const pages = getCurrentPages()
    if (pages.length > 1) wx.navigateBack()
    else wx.switchTab({ url: '/pages/forum/index' })
  },
})
