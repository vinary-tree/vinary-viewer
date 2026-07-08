// Sync the @resvg/resvg-wasm WASM binary into resources/public/js/ so the headless terminal graphics layer
// (vinary.terminal.graphics) can load it FROM DISK at runtime — the same res-dir pattern vinary.terminal.syntax
// uses for tree-sitter.wasm (both land in resources/public/js/, found relative to the compiled script's __dirname,
// so the bundled CLI works regardless of where node_modules ends up). Run by `compile:cli` / `release:cli`.
import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';

const root = path.resolve(path.dirname(new URL(import.meta.url).pathname), '..');
const src = path.join(root, 'node_modules', '@resvg', 'resvg-wasm', 'index_bg.wasm');
const destDir = path.join(root, 'resources', 'public', 'js');
const dest = path.join(destDir, 'resvg.wasm');

if (!fs.existsSync(src)) {
  console.error(`sync-graphics-wasm: missing ${path.relative(root, src)} — run \`npm install\` first`);
  process.exit(1);
}
fs.mkdirSync(destDir, { recursive: true });
const changed = !fs.existsSync(dest) || fs.statSync(src).size !== fs.statSync(dest).size;
if (changed) fs.copyFileSync(src, dest);
console.log(`sync-graphics-wasm: resvg.wasm ${changed ? 'copied' : 'up-to-date'} (${(fs.statSync(dest).size / 1024).toFixed(0)} KiB) → ${path.relative(root, dest)}`);
