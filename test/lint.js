'use strict';
// vinary-viewer lint (run by `npm run lint`). Three checks:
//   1. every JS source parses (node --check)
//   2. style.css braces balance
//   3. every --vv-* variable referenced by style.css is defined by EVERY theme in src/themes/
// Exits non-zero on any failure, so it gates CI / `npm run check`.
const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');

const root = path.resolve(__dirname, '..');
let fail = 0;
const log = (ok, msg) => { console.log((ok ? '✓ ' : '✗ ') + msg); if (!ok) fail++; };

// 1. JS parses
const jsFiles = [
  'src/sidebar.js', 'src/patch-create-window.js', 'src/patch-renderer-main.js',
  'src/mouse-forward-back/index.js', 'test/test-sidebar.js', 'test/lint.js',
  'test/electron-smoke.js', 'test/extensions-smoke.js',
  'scripts/sync-grammars.mjs', 'scripts/check-grammars.mjs',
  'scripts/sync-pdfjs.mjs', 'scripts/check-pdfjs.mjs',
  'resources/ext-chrome-polyfill.js',
];
for (const f of jsFiles) {
  try { execFileSync(process.execPath, ['--check', path.join(root, f)]); log(true, `parses: ${f}`); }
  catch (e) { log(false, `parse error: ${f}\n${(e.stderr || e.message).toString().trim()}`); }
}

// 2. CSS braces balance
const css = fs.readFileSync(path.join(root, 'src/style.css'), 'utf8');
const open = (css.match(/{/g) || []).length;
const close = (css.match(/}/g) || []).length;
log(open === close, `style.css braces balanced (${open} open / ${close} close)`);

// 3. theme completeness — no theme may omit a variable the stylesheet uses
const used = new Set([...css.matchAll(/var\(\s*(--vv-[a-z0-9-]+)/g)].map(m => m[1]));
const themesDir = path.join(root, 'src/themes');
const themes = fs.readdirSync(themesDir).filter(f => f.endsWith('.css'));
log(themes.length > 0, `found ${themes.length} theme(s) in src/themes/`);
for (const tf of themes) {
  const tcss = fs.readFileSync(path.join(themesDir, tf), 'utf8');
  const def = new Set([...tcss.matchAll(/(--vv-[a-z0-9-]+)\s*:/g)].map(m => m[1]));
  const missing = [...used].filter(v => !def.has(v));
  log(missing.length === 0,
    `theme ${tf} defines all ${used.size} used vars` + (missing.length ? ` — MISSING: ${missing.join(', ')}` : ''));
}

console.log(fail ? `\n${fail} lint failure(s).` : '\nlint OK.');
process.exit(fail ? 1 : 0);
