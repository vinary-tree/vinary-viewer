#!/usr/bin/env node
'use strict';

// Hermetic integrity check for the one terminal-graphics binary vendored from the pinned npm dependency.
// Unlike the sync command, this never writes: tests should prove the staged runtime asset is current without
// making their result depend on network access or mutating the worktree.

import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';

const root = path.resolve(path.dirname(new URL(import.meta.url).pathname), '..');
const source = path.join(root, 'node_modules', '@resvg', 'resvg-wasm', 'index_bg.wasm');
const staged = path.join(root, 'resources', 'public', 'js', 'resvg.wasm');

if (!fs.existsSync(source)) {
  console.error(`check-graphics-wasm: missing ${path.relative(root, source)} — run \`npm install\` first`);
  process.exit(1);
}
if (!fs.existsSync(staged)) {
  console.error(`check-graphics-wasm: missing ${path.relative(root, staged)} — run \`npm run graphics:sync\``);
  process.exit(1);
}
if (!fs.readFileSync(source).equals(fs.readFileSync(staged))) {
  console.error('check-graphics-wasm: staged resvg.wasm differs from the pinned @resvg/resvg-wasm dependency');
  process.exit(1);
}

console.log(`check-graphics-wasm: resvg.wasm OK (${(fs.statSync(staged).size / 1024).toFixed(0)} KiB)`);
