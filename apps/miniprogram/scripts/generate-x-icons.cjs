const fs = require('node:fs');
const path = require('node:path');
const { Resvg } = require('@resvg/resvg-js');
const root = path.dirname(require.resolve('lucide-static/package.json'));
const destination = path.resolve(__dirname, '../miniprogram/assets/icons');
const variants = {
  house: ['ink', 'active'], search: ['ink', 'active', 'white'], sparkle: ['ink', 'white'],
  bell: ['ink', 'active'], mail: ['ink'], 'message-square': ['ink', 'active'],
  'user-round': ['ink'], 'shopping-bag': ['ink'], bookmark: ['ink', 'muted', 'blue'],
  file: ['ink'], 'notebook-pen': ['ink'], package: ['ink'], x: ['ink', 'white'],
  // 撰写页底部工具条：加图与话题用蓝色描边。
  image: ['ink'], hash: ['ink'],
  'chevron-down': ['ink'], info: ['ink'], 'message-circle': ['ink', 'muted'], plus: ['ink'],
  // 个人主页封面上的浮层导航需要白色图标。
  'chevron-left': ['white'], ellipsis: ['white'],
  // 私信页 FAB 是「新对话」，用方框铅笔而不是加号。
  'square-pen': ['white'],
  'repeat-2': ['muted', 'reposted'], heart: ['muted', 'liked'], upload: ['muted'],
};
// 侧栏与二级页用到的别名：输出文件名与 Lucide 图标名不一致时在此声明。
// 关键词：别名 名称沿用页面已引用的文件名，避免改动 WXML。
const aliases = {
  'x-chat-ink': ['message-circle', 'ink'],
  'x-circle-help-ink': ['circle-help', 'ink'],
  'x-ellipsis-muted': ['ellipsis', 'muted'],
  'x-grok-ink': ['sparkle', 'ink'],
  'x-muted': ['x', 'ink'],
  'x-search-muted': ['search', 'muted'],
  'x-settings-ink': ['settings', 'ink'],
  'x-badge-check-ink': ['badge-check', 'ink'],
  'x-dollar-sign-ink': ['dollar-sign', 'ink'],
  'x-users-ink': ['users', 'ink'],
  'x-list-ink': ['list', 'ink'],
  'x-mic-ink': ['mic', 'ink'],
  'x-rocket-ink': ['rocket', 'ink'],
  'x-history-ink': ['history', 'ink'],
  'x-sparkle-purple': ['sparkles', 'purple'],
  'x-chat-blue': ['message-circle', 'blue'],
  // 通知列表左侧的类型符号：全站黑白化后统一 ink 描边，与头像区分开。
  'x-megaphone-purple': ['megaphone', 'purple'],
  'x-megaphone-ink': ['megaphone', 'ink'],
};
const tones = { muted: '#536471', blue: '#1d9bf0', liked: '#f91880', reposted: '#00ba7c', ink: '#0f1419', purple: '#7856ff', white: '#ffffff' };
let count = 0;
for (const [name, keys] of Object.entries(variants)) {
  for (const tone of keys) {
    const color = tones[tone] || tones.ink;
    let svg = fs.readFileSync(path.join(root, 'icons', name + '.svg'), 'utf8').replaceAll('currentColor', color);
    if (tone === 'liked' || (tone === 'active' && ['house', 'bell', 'message-square'].includes(name))) svg = svg.replace('fill="none"', 'fill="' + color + '"');
    if (tone === 'active') svg = svg.replace('stroke-width="2"', 'stroke-width="2.8"');
    fs.writeFileSync(path.join(destination, 'x-' + name + '-' + tone + '.png'), new Resvg(svg, { fitTo: { mode: 'width', value: 72 } }).render().asPng());
    count++;
  }
}
for (const [file, [name, tone]] of Object.entries(aliases)) {
  const color = tones[tone] || tones.ink;
  const svg = fs.readFileSync(path.join(root, 'icons', name + '.svg'), 'utf8').replaceAll('currentColor', color);
  fs.writeFileSync(path.join(destination, file + '.png'), new Resvg(svg, { fitTo: { mode: 'width', value: 72 } }).render().asPng());
  count++;
}
// 底栏「当前页」的 Link AI 星标：Lucide 的 sparkle 填充后像一个胖加号，
// 这里按 X 的四角星几何单独画一枚（内凹曲线，2..22 撑满画布）。
const sparkleActive = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">'
  + '<path fill="' + tones.ink + '" d="M12 2c.9 6 4.1 9.1 10 10-5.9.9-9.1 4-10 10-.9-6-4.1-9.1-10-10 5.9-.9 9.1-4 10-10Z"/></svg>';
fs.writeFileSync(path.join(destination, 'x-sparkle-active.png'), new Resvg(sparkleActive, { fitTo: { mode: 'width', value: 72 } }).render().asPng());
count++;
// 旧版私信激活态是 Lucide mail 填充出来的黑方块，已改用 message-square + 星标同款实心规则。
const staleActiveMail = path.join(destination, 'x-mail-active.png');
if (fs.existsSync(staleActiveMail)) fs.unlinkSync(staleActiveMail);
// 首页顶栏的品牌标识：mark 原图带品牌蓝，全站黑白化后这里按 alpha 通道重着色为墨黑。
{
  const { PNG } = require('pngjs');
  const mark = PNG.sync.read(fs.readFileSync(path.resolve(__dirname, '../miniprogram/assets/brand/xinkelink-mark.png')));
  const inkR = 0x0f, inkG = 0x14, inkB = 0x19;
  for (let i = 0; i < mark.data.length; i += 4) {
    if (mark.data[i + 3] > 0) { mark.data[i] = inkR; mark.data[i + 1] = inkG; mark.data[i + 2] = inkB; }
  }
  fs.writeFileSync(path.join(destination, 'x-logo-ink.png'), PNG.sync.write(mark));
}
count++;
console.log('Generated ' + count + ' X-style icons from the existing Lucide dependency.');

