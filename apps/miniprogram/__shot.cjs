// 自检截图：连开发者工具自动化端口，逐屏截图，供与 docs/ui/x-redesign 逐屏比对。
const automator = require('miniprogram-automator')
const path = require('path')
const OUT = 'D:/CODE/XinKeLink/.local/selfcheck'
const PORT = Number(process.argv[2] || 9511)
const wait = ms => new Promise(r => setTimeout(r, ms))
const T = (p, ms, tag) => Promise.race([p, wait(ms).then(() => { throw new Error('timeout:' + tag) })])

const SHOTS = [
  ['01-home', 'tab', '/pages/forum/index'],
  ['02-search', 'tab', '/pages/search/index'],
  ['03-ai', 'tab', '/pages/ai/index'],
  ['04-notifications', 'tab', '/pages/notifications/index'],
  ['05-contact', 'tab', '/pages/contact/index'],
  ['06-profile', 'nav', '/pages/profile/index'],
  ['07-detail', 'nav', '/pages/forum-detail/index?id=post-001'],
  ['08-publish', 'nav', '/pages/forum-publish/index'],
  ['09-settings', 'nav', '/pages/settings/index'],
  ['10-user-home', 'nav', '/pages/user-home/index?id=mock-lin'],
]

const log = s => { process.stdout.write(s + '\n') }

;(async () => {
  const mp = await automator.connect({ wsEndpoint: 'ws://127.0.0.1:' + PORT })
  await wait(2000)
  for (const [name, kind, url] of SHOTS) {
    try {
      if (kind === 'tab') await T(mp.switchTab(url), 15000, name + ':switchTab')
      else {
        await T(mp.navigateTo(url), 15000, name + ':navigateTo')
      }
      await wait(1400)
      await T(mp.screenshot({ path: path.join(OUT, name + '.png') }), 25000, name + ':shot')
      log(name + ' OK')
      if (kind === 'nav') await T(mp.navigateBack(), 12000, name + ':back').catch(() => {})
    } catch (e) {
      log(name + ' FAIL ' + (e && e.message))
      try { await T(mp.reLaunch('/pages/forum/index'), 10000, 'reset') } catch (_) {}
    }
    await wait(300)
  }
  log('done')
  process.exit(0)
})().catch(e => { log('ERR ' + (e && e.message)); process.exit(1) })
