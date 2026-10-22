const assert = require('node:assert/strict');
const { readFileSync, existsSync, readdirSync } = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '../miniprogram');
const config = JSON.parse(readFileSync(path.resolve(__dirname, '../project.config.json'), 'utf8'));
const app = JSON.parse(readFileSync(path.join(root, 'app.json'), 'utf8'));
assert.equal(config.compileType, 'miniprogram');
assert.ok(config.setting.useCompilerPlugins.includes('typescript'));
assert.equal(app.tabBar.custom, true);
assert.equal(app.tabBar.list.length, 4);
for (const page of app.pages) {
  for (const extension of ['ts', 'json', 'wxml', 'wxss']) {
    assert.ok(existsSync(path.join(root, page + '.' + extension)), page + '.' + extension);
  }
}
for (const tab of app.tabBar.list) assert.ok(app.pages.includes(tab.pagePath));
function walk(directory) {
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    const file = path.join(directory, entry.name);
    if (entry.isDirectory()) walk(file);
    else if (entry.name.endsWith('.json')) {
      const json = JSON.parse(readFileSync(file, 'utf8'));
      for (const component of Object.values(json.usingComponents ?? {})) {
        const base = component.startsWith('/') ? path.join(root, component) : path.resolve(directory, component);
        for (const extension of ['ts', 'json', 'wxml', 'wxss']) {
          assert.ok(existsSync(base + '.' + extension), base + '.' + extension);
        }
      }
    }
  }
}
walk(root);
assert.ok(existsSync(path.join(root, 'assets/icons/plus-white.png')));
console.log('Project JSON, page files, component references and icon entry checked.');
console.log('This does not compile WXML/WXSS or replace WeChat DevTools validation.');
