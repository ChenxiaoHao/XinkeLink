const { readFileSync } = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createRequire } = require('node:module');

const openFixtures = new WeakSet();
function loadPage(relativePath, { wx = {}, fixture = true, betaPhase } = {}) {
  // Historical UI regression tests deliberately select the local fixture.
  // New backend tests pass fixture:false; production never infers this flag.
  wx.__fixtureMode = fixture;
  // Existing non-beta controller tests explicitly declare an open-phase server.
  // New beta tests and live HTTP runtimes use the real endpoint without this fixture.
  if (betaPhase === 'open' && !openFixtures.has(wx)) {
    const request = wx.request;
    wx.request = function(options) {
      if (new URL(options.url).pathname === '/api/v1/beta/access') {
        options.success({ statusCode: 200, data: { code: 'OK', data: {
          phase: 'open', authenticated: !!options.header?.Authorization, activated: false,
        } } }); return;
      }
      return request.call(this, options);
    };
    openFixtures.add(wx);
  }
  const filename = path.resolve(__dirname, '../.test-dist', relativePath);
  let definition;
  vm.runInNewContext(readFileSync(filename, 'utf8'), {
    exports: {}, require: createRequire(filename),
    Page(value) { definition = value; }, wx,
  }, { filename });
  return {
    ...definition,
    data: JSON.parse(JSON.stringify(definition.data)),
    setData(patch) {
      for (const [path, value] of Object.entries(patch)) {
        const parts = path.replace(/\[(\d+)\]/g, '.$1').split('.');
        const last = parts.pop(); let target = this.data;
        for (const part of parts) target = target[part];
        target[last] = value;
      }
    },
    getTabBar() { return { setData() {} }; },
  };
}

module.exports = { loadPage };
