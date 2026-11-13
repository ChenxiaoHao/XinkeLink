const test = require('node:test'), assert = require('node:assert/strict'), fs = require('node:fs'), path = require('node:path')
const root = path.join(__dirname, '../miniprogram')
const read = p => fs.readFileSync(path.join(root, p), 'utf8')
test('tablet page frame stays phone-width and fixed controls share that frame', () => {
  assert.match(read('app.wxss'), /@media\s*\(min-width:\s*500px\)[\s\S]*page\s*\{[^}]*max-width:\s*460px/)
  for (const [page, cls] of [['forum-detail', 'detail-composer']]) {
    const css = read(`pages/${page}/index.wxss`)
    assert.match(css, new RegExp(`@media\\s*\\(min-width:\\s*500px\\)[\\s\\S]*\\.${cls}\\s*\\{[^}]*max-width:\\s*460px`), page)
  }
})
test('secondary pages and reusable cards keep tablet rules inside a phone-width frame', () => {
  // 设计稿口径已统一为 375 基准 px；平板上由 app.wxss 的 460 窄栏兜住，页面只保留 ≥500px 的覆盖块。
  for (const file of ['pages/search/index.wxss', 'pages/feedback/index.wxss', 'components/forum-post-card/index.wxss', 'styles/chat.wxss']) {
    assert.match(read(file), /@media\s*\(min-width:\s*500px\)/, file)
  }
  // 含固定底栏的页面需要各自收窄，避免浮层横跨整块屏幕。
  for (const file of ['pages/profile/index.wxss', 'pages/user-home/index.wxss', 'pages/forum-publish/index.wxss']) {
    assert.match(read(file), /@media\s*\(min-width:\s*500px\)[\s\S]*max-width:\s*460px/, file)
  }
})
const {menuRightSpace} = require('../.test-dist/utils/layout.js')
test('native menu spacing tracks overlap with centered tablet content', () => {
  assert.equal(menuRightSpace(430, 322), 120)
  assert.equal(menuRightSpace(1000, 890), 12)
  assert.equal(menuRightSpace(550, 440), 77)
})
