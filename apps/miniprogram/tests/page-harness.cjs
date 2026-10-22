const { readFileSync } = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createRequire } = require('node:module');

function loadPage(relativePath, { wx = {} } = {}) {
  const filename = path.resolve(__dirname, '../.test-dist', relativePath);
  let definition;
  vm.runInNewContext(readFileSync(filename, 'utf8'), {
    exports: {}, require: createRequire(filename),
    Page(value) { definition = value; }, wx,
  }, { filename });
  return {
    ...definition,
    data: JSON.parse(JSON.stringify(definition.data)),
    setData(patch) { Object.assign(this.data, patch); },
    getTabBar() { return { setData() {} }; },
  };
}

module.exports = { loadPage };
