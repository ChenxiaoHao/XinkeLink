const fs = require('node:fs');
const path = require('node:path');
const { Resvg } = require('@resvg/resvg-js');
const root = path.dirname(require.resolve('lucide-static/package.json'));
const output = path.resolve(__dirname, '../miniprogram/assets/icons');
const names = ['layout-grid', 'laptop', 'book-open', 'coffee', 'shirt', 'ellipsis', 'search', 'sliders-horizontal', 'map-pin', 'heart', 'message-circle', 'forward', 'messages-square', 'shopping-bag', 'message-square-text', 'user-round'];
for (const name of names) {
  const original = fs.readFileSync(path.join(root, 'icons', name + '.svg'), 'utf8');
  for (const [tone, color] of [['ink', '#202021'], ['white', '#FFFFFF']]) {
    const svg = original.replaceAll('currentColor', color).replace('stroke-width="2"', 'stroke-width="1.8"');
    fs.writeFileSync(path.join(output, 'ref-' + name + '-' + tone + '.png'), new Resvg(svg, { fitTo: { mode: 'width', value: 96 } }).render().asPng());
  }
}
// 用同一套库的路径制作选中填充态，不另画图标。
const bag = fs.readFileSync(path.join(root, 'icons/shopping-bag.svg'), 'utf8').replaceAll('currentColor', '#202021');
const paths = bag.match(/<path[^>]+\/>/g);
const filledBag = bag.replace(/<path[^>]+\/>/g, '').replace('</svg>', paths[2].replace('/>', ' fill="#202021"/>') + paths[0].replace('/>', ' stroke="#FFFFFF"/>') + '</svg>');
fs.writeFileSync(path.join(output, 'ref-shopping-bag-filled.png'), new Resvg(filledBag, { fitTo: { mode: 'width', value: 96 } }).render().asPng());
console.log('Generated 33 additional reference icons; existing icons unchanged.');
