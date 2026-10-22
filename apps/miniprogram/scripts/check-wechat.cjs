const { readdirSync, mkdirSync, statSync } = require('node:fs');
const { spawnSync } = require('node:child_process');
const path = require('node:path');

if (!process.argv[2]) {
  console.error('Usage: node scripts/check-wechat.cjs <WeChat DevTools installation directory>');
  process.exit(1);
}
const devTools = path.resolve(process.argv[2]);
const compilerRoot = path.join(devTools, 'code/package.nw/node_modules/wcc-exec');
const root = path.resolve(__dirname, '../miniprogram');
const output = path.resolve(__dirname, '../../../.local/wechat-compile');
mkdirSync(output, { recursive: true });
function files(directory, extension) {
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const absolute = path.join(directory, entry.name);
    return entry.isDirectory() ? files(absolute, extension)
      : entry.name.endsWith(extension) ? [path.relative(root, absolute).replaceAll('\\', '/')] : [];
  });
}
for (const [compiler, extension, flags] of [
  ['wcc.exe', '.wxml', []],
  ['wcsc.exe', '.wxss', ['-lc']],
]) {
  const sources = files(root, extension);
  const destination = path.join(output, compiler + '.js');
  const result = spawnSync(path.join(compilerRoot, compiler), [...flags, '-o', destination, ...sources], {
    cwd: root, encoding: 'utf8', windowsHide: true,
  });
  if (result.error || result.status !== 0) {
    console.error(result.error ?? result.stderr ?? result.stdout);
    process.exit(1);
  }
  if (result.stderr) console.log(result.stderr.trim());
  if (!statSync(destination).size) throw new Error('Compiler emitted an empty file: ' + compiler);
  console.log(compiler + ': compiled ' + sources.length + ' ' + extension + ' files.');
}
console.log('Offline syntax compilation only; simulator and device rendering are separate checks.');
