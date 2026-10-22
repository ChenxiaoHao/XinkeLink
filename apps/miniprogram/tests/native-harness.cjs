const { readFileSync } = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createRequire } = require('node:module');

function loadComponent(relativePath, { wx = {}, pages = [] } = {}) {
  const filename = path.resolve(__dirname, '../.test-dist', relativePath);
  let definition;
  vm.runInNewContext(readFileSync(filename, 'utf8'), {
    exports: {},
    require: createRequire(filename),
    Component(value) { definition = value; },
    wx,
    getCurrentPages: () => pages,
  }, { filename });
  const events = [];
  const instance = {
    data: JSON.parse(JSON.stringify(definition.data ?? {})),
    setData(patch) { Object.assign(this.data, patch); },
    triggerEvent(name, detail) { events.push({ name, detail }); },
    ...definition.methods,
  };
  return { definition, instance, events };
}

module.exports = { loadComponent };
