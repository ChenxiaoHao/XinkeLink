const { readFileSync, writeFileSync, mkdirSync, copyFileSync } = require('node:fs');
const path = require('node:path');
const { Resvg } = require('@resvg/resvg-js');

const root = path.dirname(require.resolve('lucide-static/package.json'));
const destination = path.resolve(__dirname, '../miniprogram/assets/icons');
mkdirSync(destination, { recursive: true });
const variants = {
  'messages-square': ['primary', 'muted'],
  'shopping-bag': ['primary', 'muted'],
  'message-square-text': ['primary', 'muted'],
  'user-round': ['primary', 'muted', 'white'],
  'plus': ['white', 'primary'],
  'x': ['muted'],
  'notebook-pen': ['primary'],
  'archive': ['muted'],
  'arrow-right': ['primary', 'muted'],
  'leaf': ['primary'],
  'graduation-cap': ['primary'],
  'search': ['primary'],
  'megaphone': ['primary'],
  'chevron-right': ['muted'],
  'heart': ['muted', 'primary-filled'],
  'bookmark': ['muted', 'primary-filled'],
  'message-circle': ['muted'],
  'share-2': ['muted'],
  'ellipsis': ['muted'],
  'chevron-down': ['primary'],
  'chevron-left': ['muted'],
  'arrow-up': ['primary', 'white'],
  'arrow-down': ['primary'],
  'arrow-up-down': ['primary'],
  'sliders-horizontal': ['primary'],
  'map-pin': ['primary'],
  'shield-check': ['primary'],
  'check-check': ['primary'],
  'camera': ['primary'],
  'file': ['muted'],
  'send': ['white'],
  'thumbs-up': ['muted', 'primary-filled'],
};
const colors = { primary: '#6489B8', muted: '#8A93A3', white: '#FFFFFF' };
let count = 0;
for (const [name, tones] of Object.entries(variants)) {
  const source = readFileSync(path.join(root, 'icons', name + '.svg'), 'utf8');
  for (const tone of tones) {
    const color = tone.endsWith('-filled') ? colors.primary : colors[tone];
    let svg = source.replaceAll('currentColor', color);
    if (tone.endsWith('-filled')) svg = svg.replace('fill="none"', 'fill="' + color + '"');
    const png = new Resvg(svg, { fitTo: { mode: 'width', value: 72 } }).render().asPng();
    writeFileSync(path.join(destination, name + '-' + tone + '.png'), png);
    count++;
  }
}
copyFileSync(path.join(root, 'LICENSE'), path.join(destination, 'LUCIDE-LICENSE'));
console.log('Generated ' + count + ' local PNG icons.');
