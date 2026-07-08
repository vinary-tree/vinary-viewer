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
  'src/vinary/main/content_service.js',
  'resources/public/js/pdf-loader.js',
  'src/mouse-forward-back/index.js', 'test/test-sidebar.js', 'test/lint.js',
  'test/electron-smoke.js', 'test/extensions-smoke.js', 'test/content-service-smoke.js',
  'test/git-tree-smoke.js', 'test/cli-smoke.js', 'test/graphics-smoke.js', 'test/tui-smoke.js',
  'scripts/sync-grammars.mjs', 'scripts/check-grammars.mjs',
  'scripts/sync-graphics-wasm.mjs',
  'scripts/sync-pdfjs.mjs', 'scripts/check-pdfjs.mjs',
  'scripts/screenshots.cjs',
  'resources/ext-chrome-polyfill.js',
];
for (const f of jsFiles) {
  try { execFileSync(process.execPath, ['--check', path.join(root, f)]); log(true, `parses: ${f}`); }
  catch (e) {
    const stderr = e.stderr && e.stderr.toString().trim();
    log(false, `parse error: ${f}\n${stderr || e.message}`);
  }
}

// 2 & 3. CSS — brace balance + theme-var completeness, for BOTH surfaces. Each stylesheet uses the same
//    --vv-* palette but a different class system, paired with its own theme dir:
//      • resources/public/css/app.css + resources/public/css/themes/ — the standalone v0.2 app (.vv-*),
//        linked by index.html; THE SHIPPED PRODUCT (previously NOT linted — that was the gap).
//      • src/style.css + src/themes/ — the legacy v0.1.0 vmd-patch sidebar (src/sidebar.js, .vmd-*),
//        injected into vmd's renderer; kept for users still on that tool.
//    A theme must define every --vv-* var its stylesheet references, EXCEPT vars the stylesheet defines
//    itself in :root (the font defaults, overridden at runtime by Settings).
const cssTargets = [
  { css: 'resources/public/css/app.css', themes: 'resources/public/css/themes' },
  { css: 'src/style.css',                themes: 'src/themes' },
];
for (const t of cssTargets) {
  const css = fs.readFileSync(path.join(root, t.css), 'utf8');
  const open = (css.match(/{/g) || []).length, close = (css.match(/}/g) || []).length;
  log(open === close, `${t.css} braces balanced (${open} open / ${close} close)`);

  const used = new Set([...css.matchAll(/var\(\s*(--vv-[a-z0-9-]+)/g)].map(m => m[1]));
  const selfDefined = new Set([...css.matchAll(/(--vv-[a-z0-9-]+)\s*:/g)].map(m => m[1])); // :root defaults
  const themed = [...used].filter(v => !selfDefined.has(v));
  const themesDir = path.join(root, t.themes);
  const themeFiles = fs.readdirSync(themesDir).filter(f => f.endsWith('.css'));
  log(themeFiles.length > 0, `${t.css}: found ${themeFiles.length} theme(s) in ${t.themes}/`);
  for (const tf of themeFiles) {
    const tcss = fs.readFileSync(path.join(themesDir, tf), 'utf8');
    const def = new Set([...tcss.matchAll(/(--vv-[a-z0-9-]+)\s*:/g)].map(m => m[1]));
    const missing = themed.filter(v => !def.has(v));
    log(missing.length === 0,
      `${t.themes}/${tf} defines all ${themed.length} themed vars used by ${t.css}` + (missing.length ? ` — MISSING: ${missing.join(', ')}` : ''));
  }
}

console.log(fail ? `\n${fail} lint failure(s).` : '\nlint OK.');
process.exit(fail ? 1 : 0);
