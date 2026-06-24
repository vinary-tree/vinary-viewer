// Headless harness for src/sidebar.js: mocks electron + window/document so the renderer-side logic
// can be exercised without a real Electron window. Repo-agnostic — it builds the tree from any git
// repo (default: this repo). Override with VV_TEST_REPO=/path/to/some/git/repo.
//   R1 tree builds: one <a> per tracked file                         (file-tree)
//   R2 click a panel link to a tracked file → vmd.setFilePath(abs)   (open-in-viewer routing)
//   R3 mouse back (mousedown btn3) → emit vmd's history-back IPC      (native history nav)
//   R4 click a folder summary → <details>.open toggles               (folder expand/collapse)
//   R5 a named theme is injected as <style id="vv-theme">            (theming)
'use strict';
const assert = require('assert');
const path = require('path');
const Module = require('module');
const { execFileSync } = require('child_process');

const REPO = process.env.VV_TEST_REPO || path.resolve(__dirname, '..');
const SIDEBAR = path.resolve(__dirname, '..', 'src', 'sidebar.js');
process.env.VV_THEME = 'spacemacs-dark';                  // make R5 deterministic regardless of host config

// ---- spies + fake electron (injected via Module._load) ----
const calls = { setFilePath: [], openItem: [], emit: [] };
const fakeWC = { findInPage() {}, stopFindInPage() {}, on() {} };
const fakeElectron = {
  remote: {
    getCurrentWindow: () => ({ id: 1 }),
    getCurrentWebContents: () => fakeWC,
    shell: { openItem: p => calls.openItem.push(p) },
  },
  ipcRenderer: { on() {}, emit: (...a) => calls.emit.push(a) },
};
const origLoad = Module._load;
Module._load = function (req, ...rest) { return req === 'electron' ? fakeElectron : origLoad.call(this, req, ...rest); };

// ---- DOM mock ----
function matches(el, sel) { return sel.charAt(0) === '.' ? (el._cs && el._cs.has(sel.slice(1))) : el.tagName === sel.toUpperCase(); }
function makeEl(tag) {
  const el = {
    tagName: String(tag).toUpperCase(), children: [], attrs: {}, _l: {},
    id: '', title: '', textContent: '', open: false, parentNode: null, className: '', src: '', dataset: {}, style: {},
    get firstChild() { return this.children[0] || null; },
    setAttribute(k, v) { this.attrs[k] = v; if (k === 'src') this.src = v; if (k === 'id') this.id = v; },
    getAttribute(k) { return k in this.attrs ? this.attrs[k] : null; },
    appendChild(c) { c.parentNode = this; this.children.push(c); return c; },
    insertBefore(c, ref) { c.parentNode = this; const i = ref ? this.children.indexOf(ref) : 0; this.children.splice(i < 0 ? this.children.length : i, 0, c); return c; },
    addEventListener(t, f) { (this._l[t] = this._l[t] || []).push(f); },
    scrollIntoView() {},
    contains(n) { for (let x = n; x; x = x.parentNode) if (x === this) return true; return false; },
    closest(sel) { for (let n = this; n; n = n.parentNode) if (matches(n, sel)) return n; return null; },
    querySelector(sel) {
      const m = sel.match(/^(\w+)\[data-abs="(.*)"\]$/);
      const pred = m ? (n => n.tagName === m[1].toUpperCase() && n.getAttribute('data-abs') === m[2].replace(/\\(.)/g, '$1'))
                     : (n => matches(n, sel));
      const st = [...this.children];
      while (st.length) { const n = st.shift(); if (pred(n)) return n; st.unshift(...n.children); }
      return null;
    },
    querySelectorAll() { return []; },
  };
  const set = new Set();
  el.classList = {
    add: c => set.add(c), remove: c => set.delete(c), contains: c => set.has(c),
    toggle: (c, f) => { const h = set.has(c); if (f === true || (f === undefined && !h)) set.add(c); else set.delete(c); },
  };
  el._cs = set;
  return el;
}
function byId(root, id) { if (!root) return null; if (root.id === id) return root; for (const c of root.children) { const r = byId(c, id); if (r) return r; } return null; }
global.window = {
  CSS: { escape: s => s }, _l: {},
  vmd: { setFilePath: (id, p) => calls.setFilePath.push(p) },
  addEventListener(t, f) { (this._l[t] = this._l[t] || []).push(f); },
  getComputedStyle: () => ({ paddingLeft: '45px', paddingRight: '45px', fontSize: '16px' }),
  innerHeight: 800, scrollY: 0, scrollTo() {},
};
global.CSS = global.window.CSS;
global.requestAnimationFrame = fn => fn();
global.MutationObserver = class { constructor(c) { this.c = c; global.__obs = this; } observe() {} disconnect() {} };
const head = makeEl('head');
global.document = {
  head,
  createElement: t => makeEl(t),
  createDocumentFragment: () => makeEl('#fragment'),
  createTreeWalker: () => ({ nextNode: () => null }),
  querySelector: () => null, querySelectorAll: () => [],
  getElementById(id) { return byId(this.head, id) || byId(this.body, id); },
  body: makeEl('body'),
};
global.NodeFilter = { SHOW_TEXT: 4 };
function fireWindow(type, ev) { (global.window._l[type] || []).forEach(f => f(ev)); }
const noopEv = extra => Object.assign({ preventDefault() {}, stopPropagation() {}, stopImmediatePropagation() {} }, extra);

// ---- load ----
process.chdir(REPO);
const body = makeEl('body'); body.setAttribute('data-filepath', path.join(REPO, 'README.md'));
global.document.body = body;
require(SIDEBAR);
const panel = body.children.find(c => c.id === 'vmd-sidebar');
assert(panel, 'sidebar panel was appended to <body> (is REPO a git repo?)');

const files = execFileSync('git', ['ls-files'], { cwd: REPO, encoding: 'utf8' }).split('\n').filter(Boolean);
assert(files.length > 0, `REPO ${REPO} has tracked files (run inside a git repo, or set VV_TEST_REPO)`);
const textFile = files.find(f => /\.(js|css|md|sh|json|txt)$/.test(f)) || files[0];

// R1 — one <a> per tracked file
let links = 0; (function w(n) { for (const c of n.children) { if (c.tagName === 'A') links++; w(c); } })(panel);
assert.strictEqual(links, files.length, `R1 links ${links} == files ${files.length}`);
console.log(`R1 tree builds: ${links} links ✓`);

// R2 — clicking a tracked file routes to the in-app viewer
const a = panel.querySelector(`a[data-abs="${path.join(REPO, textFile)}"]`);
assert(a, `R2 link for ${textFile} exists`);
fireWindow('click', noopEv({ target: a }));
assert(calls.setFilePath.includes(path.join(REPO, textFile)), 'R2 click → vmd.setFilePath');
console.log(`R2 file click → setFilePath(${textFile}) ✓`);

// R3 — mouse back drives vmd's native history (button 3 → 'history-back'; forward is the same path)
calls.emit.length = 0;
fireWindow('mousedown', noopEv({ button: 3 }));
assert(calls.emit.some(e => e[0] === 'history-back'), 'R3 mouse-back (btn3) → emit history-back');
console.log('R3 mouse back → emit vmd history IPC ✓');

// R4 — folder summary toggles its <details>
const summary = (function find(n) { for (const c of n.children) { if (c.tagName === 'SUMMARY') return c; const r = find(c); if (r) return r; } return null; })(panel);
assert(summary, 'R4 a folder summary exists (REPO has subdirectories)');
const det = summary.parentNode;
assert.strictEqual(det.open, false, 'R4 starts collapsed');
fireWindow('click', noopEv({ target: summary }));
assert.strictEqual(det.open, true, 'R4 click expands');
fireWindow('click', noopEv({ target: summary }));
assert.strictEqual(det.open, false, 'R4 second click collapses');
console.log('R4 folder summary click toggles ✓');

// R5 — the selected named theme is injected as <style id="vv-theme"> with the variable palette
const themeEl = global.document.getElementById('vv-theme');
assert(themeEl, 'R5 <style id="vv-theme"> was injected');
assert.strictEqual(themeEl.getAttribute('data-vv-theme'), 'spacemacs-dark', 'R5 active theme = spacemacs-dark');
assert(/--vv-bg1\s*:/.test(themeEl.textContent), 'R5 injected theme defines the --vv-* palette');
console.log('R5 theme injected (spacemacs-dark, --vv-* palette) ✓');

Module._load = origLoad;
console.log('\nALL TESTS PASSED ✓');
