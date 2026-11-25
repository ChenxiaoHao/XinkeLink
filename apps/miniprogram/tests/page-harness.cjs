const { readFileSync } = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createRequire } = require('node:module');

function loadPage(relativePath, { wx = {}, fixture = true } = {}) {
  // Historical UI regression tests deliberately select the local fixture.
  // New backend tests pass fixture:false; production never infers this flag.
  wx.__fixtureMode = fixture;
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
