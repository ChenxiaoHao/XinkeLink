// 逐屏量取页面级节点的盒模型 + 计算样式，替代不可用的 CDP 截图（webview 的 Page.* 命令在本机超时）。
const automator = require('miniprogram-automator')
const PORT = Number(process.argv[2] || 9533)
const wait = ms => new Promise(r => setTimeout(r, ms))
const T = (p, ms, tag) => Promise.race([p, wait(ms).then(() => { throw new Error('timeout:' + tag) })])

const STYLES = ['color', 'background-color', 'font-size', 'font-weight', 'border-radius', 'padding', 'margin', 'border-top-color', 'border-bottom-width', 'width', 'height']

const PLAN = [
  ['02-search', 'tab', '/pages/search/index', ['.recent-title', '.recent-clear', '.recent-row', '.recent-text', '.trends-head', '.trends-title', '.trends-note', '.trend', '.trend-context', '.trend-name', '.trend-meta', '.trend-more']],
  ['03-ai', 'tab', '/pages/ai/index', ['.ai-hero', '.ai-hero-icon', '.ai-hero-title', '.ai-hero-sub', '.ai-suggest', '.ai-suggest-chip', '.ai-compose', '.ai-input', '.ai-send']],
  ['08-publish', 'nav', '/pages/forum-publish/index', ['.compose-cancel', '.compose-draft', '.compose-publish', '.compose-avatar', '.compose-content', '.compose-scope', '.compose-chip', '.compose-hint', '.compose-count']],
  ['07-detail', 'nav', '/pages/forum-detail/index?id=post-001', ['.post-avatar', '.post-header', '.post-author', '.post-stamp', '.detail-comment-order', '.detail-comments-title']],
  ['09-feedback', 'nav', '/pages/feedback/index', ['.fb-title', '.fb-desc', '.fb-chip', '.fb-input', '.fb-contact-input', '.fb-foot', '.fb-submit']],
]

const go = (mp, kind, url) => mp.evaluate((k, u) => new Promise(res => {
  const done = () => res('ok')
  if (k === 'tab') wx.switchTab({ url: u, complete: done })
  else wx.navigateTo({ url: u, complete: done, fail: done })
}), kind, url)

const probe = (mp, selectors) => mp.evaluate((sels) => new Promise(resolve => {
  const q = wx.createSelectorQuery()
  sels.forEach(s => q.selectAll(s).fields({ rect: true, size: true, computedStyle: ['color', 'background-color', 'font-size', 'font-weight', 'border-radius', 'padding', 'margin'] }))
  q.exec(list => resolve(list.map((arr, i) => ({
    sel: sels[i],
    n: (arr || []).length,
    first: arr && arr[0] ? {
      w: Math.round(arr[0].width), h: Math.round(arr[0].height),
      x: Math.round(arr[0].left), y: Math.round(arr[0].top),
      c: arr[0].color, bg: arr[0].backgroundColor, fs: arr[0].fontSize, fw: arr[0].fontWeight,
      r: arr[0].borderRadius, p: arr[0].padding, m: arr[0].margin,
    } : null,
  }))))
}), selectors)

;(async () => {
  const mp = await automator.connect({ wsEndpoint: 'ws://127.0.0.1:' + PORT })
  await wait(2000)
  for (const [name, kind, url, sels] of PLAN) {
    try {
      await T(go(mp, kind, url), 12000, name + ':nav')
      await wait(1500)
      const data = await T(probe(mp, sels), 12000, name + ':probe')
      console.log('### ' + name)
      data.forEach(d => console.log('  ' + d.sel + ' n=' + d.n + ' ' + (d.first ? `w=${d.first.w} h=${d.first.h} @${d.first.x},${d.first.y} fs=${d.first.fs} fw=${d.first.fw} c=${d.first.c} bg=${d.first.bg} r=${d.first.r}` : '-')))
      if (kind === 'nav') { await T(mp.evaluate(() => new Promise(r => wx.navigateBack({ complete: () => r(1) }))), 10000, 'back').catch(() => {}) }
    } catch (e) { console.log('### ' + name + ' FAIL ' + e.message) }
    await wait(400)
  }
  process.exit(0)
})().catch(e => { console.error('ERR ' + e.message); process.exit(1) })
