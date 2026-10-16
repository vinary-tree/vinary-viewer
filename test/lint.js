'use strict';
// vinary-viewer lint (run by `npm run lint`, and by `npm test`). Four checks:
//   1. every JS source parses (node --check)
//   2. app.css / style.css braces balance
//   3. every --vv-* variable referenced by a stylesheet is defined by EVERY theme of that surface
//   4. the exhaustive reference catalogs match the code in both directions:
//        a. docs/reference/events-effects-subs.md ↔ every re-frame registration (per section)
//        b. docs/reference/ipc-channels.md ↔ every vv:* IPC channel in the preloads + main
// Exits non-zero on any failure, so it gates CI / `npm test`.
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
  'src/vinary/main/daemon_events.js',
  'src/vinary/renderer/find_worker.js',
  'resources/public/js/pdf-loader.js',
  'src/mouse-forward-back/index.js', 'test/test-sidebar.js', 'test/lint.js',
  'test/electron-smoke.js', 'test/extensions-smoke.js', 'test/content-service-smoke.js', 'test/watch-e2e.js',
  'test/git-tree-smoke.js', 'test/git-log-smoke.js', 'test/git-blame-smoke.js', 'test/tree-e2e.js', 'test/cli-smoke.js', 'test/graphics-smoke.js',
  'test/tui-smoke.js', 'test/daemon-smoke.js', 'test/daemon-events-smoke.js',
  'test/headless-window.js', 'test/headless-runner-smoke.mjs',
  'test/remote-daemon-events-e2e-runner.js', 'test/remote-daemon-events-e2e.js',
  // the in-page-find / typist-latency gate, and the benchmark that produced its threshold (ADR-0033)
  'test/find-e2e.js', 'test/find-worker-smoke.js', 'scripts/bench-input-latency.cjs',
  // the daemon client seam: the `vv` launcher, the ping/stop tool install.sh + uninstall.sh call, and the
  // socket path/helpers both import (scripts/vv-open.mjs was previously unchecked here)
  'scripts/vv-open.mjs', 'scripts/vv-daemon.mjs', 'scripts/daemon-socket.mjs',
  'scripts/sync-grammars.mjs', 'scripts/check-grammars.mjs',
  'scripts/sync-graphics-wasm.mjs', 'scripts/check-graphics-wasm.mjs',
  'scripts/sync-pdfjs.mjs', 'scripts/check-pdfjs.mjs',
  'scripts/build-find-wasm.mjs',
  'scripts/screenshots.cjs', 'scripts/run-electron-headless.mjs',
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

// 4. Reference docs ↔ code, both directions — the two exhaustive catalogs drift silently otherwise (before this
//    gate existed, 159 re-frame registrations had no reference row — 99 were never mentioned at all — 7 retired ids
//    still had rows, 3 IPC channels were uncatalogued and a removed one was still listed; see CHANGELOG 0.3.0-dev).
//    A deliberate exception is declared in the doc itself with `<!-- lint:allow <id-or-channel> -->`.
const walkFiles = (dir, exts, out = []) => {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walkFiles(p, exts, out);
    else if (exts.some((x) => p.endsWith(x))) out.push(p);
  }
  return out;
};
const allowed = (doc) => new Set([...doc.matchAll(/<!--\s*lint:allow\s+(\S+)\s*-->/g)].map((m) => m[1]));
const show = (ids) => ids.slice(0, 40).join(', ') + (ids.length > 40 ? ` … (+${ids.length - 40} more)` : '');

//  4a. re-frame: every (rf/reg-<kind> <id> in src/vinary must have a FIRST-COLUMN table row in the doc section for
//      its kind (§1 events, §2 effects/cofx, §3 subscriptions); every first-column row there must be registered.
//      Ids are matched literally, so `::local` subscriptions are written `::name` in the doc as in the source.
{
  const KW = '::?[A-Za-z0-9_.\\-?!*+<>=]+(?:/[A-Za-z0-9_.\\-?!*+<>=]+)?';
  const regRe = new RegExp('\\(rf/reg-(event-db|event-fx|event-ctx|fx|sub|cofx)\\s+(' + KW + ')', 'g');
  const rowRe = new RegExp('^\\|\\s*`(' + KW + ')`');
  const sectionFor = { 'event-db': '1', 'event-fx': '1', 'event-ctx': '1', fx: '2', cofx: '2', sub: '3' };
  const sectionName = { 1: '§1 Events', 2: '§2 Effects', 3: '§3 Subscriptions' };
  const registered = { 1: new Set(), 2: new Set(), 3: new Set() };
  for (const f of walkFiles(path.join(root, 'src/vinary'), ['.cljs'])) {
    for (const m of fs.readFileSync(f, 'utf8').matchAll(regRe)) registered[sectionFor[m[1]]].add(m[2]);
  }
  const docPath = 'docs/reference/events-effects-subs.md';
  const doc = fs.readFileSync(path.join(root, docPath), 'utf8');
  const allow = allowed(doc);
  const rows = { 1: new Set(), 2: new Set(), 3: new Set() };
  let section = '';
  for (const line of doc.split('\n')) {
    const h = line.match(/^##\s+(\d+)\./);
    if (h) section = h[1];
    const m = line.match(rowRe);
    if (m && rows[section]) rows[section].add(m[1]);
  }
  for (const s of ['1', '2', '3']) {
    const missing = [...registered[s]].filter((id) => !rows[s].has(id) && !allow.has(id)).sort();
    const stale = [...rows[s]].filter((id) => !registered[s].has(id) && !allow.has(id)).sort();
    log(missing.length === 0,
      `${docPath} ${sectionName[s]} documents all ${registered[s].size} registered ids` + (missing.length ? ` — MISSING: ${show(missing)}` : ''));
    log(stale.length === 0,
      `${docPath} ${sectionName[s]} has no retired rows` + (stale.length ? ` — NOT REGISTERED: ${show(stale)}` : ''));
  }
}

//  4b. IPC: every `vv:*` channel literal in the two preloads and in main must be catalogued, and every channel the
//      catalog names (as `vv:…` code spans) must still exist in code.
{
  const chanRe = /["'](vv:[a-z0-9][a-z0-9:\-]*)["']/g;
  const sources = [path.join(root, 'resources/preload.js'), path.join(root, 'resources/web-preload.js'),
                   ...walkFiles(path.join(root, 'src/vinary/main'), ['.cljs', '.js'])];
  const inCode = new Set();
  for (const f of sources) for (const m of fs.readFileSync(f, 'utf8').matchAll(chanRe)) inCode.add(m[1]);
  const docPath = 'docs/reference/ipc-channels.md';
  const doc = fs.readFileSync(path.join(root, docPath), 'utf8');
  const allow = allowed(doc);
  const inDoc = new Set([...doc.matchAll(/`(vv:[a-z0-9][a-z0-9:\-]*)`/g)].map((m) => m[1]));
  const missing = [...inCode].filter((c) => !inDoc.has(c) && !allow.has(c)).sort();
  const stale = [...inDoc].filter((c) => !inCode.has(c) && !allow.has(c)).sort();
  log(missing.length === 0,
    `${docPath} documents all ${inCode.size} IPC channels used by the preloads + main` + (missing.length ? ` — MISSING: ${show(missing)}` : ''));
  log(stale.length === 0,
    `${docPath} names no removed channel` + (stale.length ? ` — NOT IN CODE: ${show(stale)}` : ''));
}

console.log(fail ? `\n${fail} lint failure(s).` : '\nlint OK.');
process.exit(fail ? 1 : 0);
