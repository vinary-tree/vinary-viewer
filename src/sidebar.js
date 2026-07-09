// vinary-viewer — sidebar.js: the renderer-side enhancements (file tree, Files/Contents tabs +
// scroll-spy TOC, in-page find, figure font-matching, history-nav binding, file filter, themes).
// Loaded by an inline require() injected into vmd's renderer/vmd.html; survives vmd npm upgrades.
// Runs in vmd's renderer (Electron, nodeIntegration on). Path-independent: it locates its own dir
// via module __dirname for the theme files.
//
// Provides:
//   • A collapsible file-tree of the git repo containing the open document (current file
//     highlighted; hidden entirely when not in a repo).
//   • Opening EVERY file type in the viewer: text/markdown/html render in place, images
//     (incl. SVG) render as full-width images (via the create-window.js patch); true-binary
//     files open in the external app.
//   • Clicking an image embedded in a rendered doc opens that image's dedicated full-width
//     view (a figure already wrapped in a link uses that existing link).
//   • A working in-page find bar (Ctrl/Cmd+F): a custom highlighter scoped to .markdown-body
//     (so the query in the search box is never itself highlighted), with match cycling.
//   • Mouse back/forward thumb buttons drive vmd's file history.
//   • A tabbed sidebar (Files / Contents): the Contents tab is a scroll-spy table of contents
//     of the previewed Markdown document (click-navigable; disabled for non-Markdown previews).
//
// Security: git is invoked via execFileSync with an argument array (no shell), and the tree
// is built with createElement + textContent (no innerHTML).
(function () {
  'use strict';
  if (window.__vmdSidebar) return;                       // idempotent guard
  window.__vmdSidebar = { version: 2 };

  let cp, path, fs;
  try { cp = require('child_process'); path = require('path'); fs = require('fs'); }
  catch (e) { console.warn('[vmd-sidebar] node unavailable; disabled', e); return; }

  // Electron bits for routing / mouse-nav. Failure degrades those features but the tree still
  // renders. (Find no longer needs webContents — it highlights the DOM directly.)
  let remote, ipcRenderer, shell, currentWindow;
  try {
    const electron = require('electron');
    remote = electron.remote; ipcRenderer = electron.ipcRenderer;
    shell = remote && remote.shell;
    currentWindow = remote && remote.getCurrentWindow();
  } catch (e) { /* keep going with tree-only */ }

  // ---- theme ---- inject the selected named theme's CSS variables (default spacemacs-dark). The
  // structural style.css references var(--vv-*); a theme file (themes/<name>.css) defines them.
  // Resolution: VV_THEME env → ~/.config/vinary-viewer/theme → 'spacemacs-dark'. Injected as
  // <style id="vv-theme"> at the top of <head> so the variables are defined before first paint.
  // __dirname is the install dir (sidebar.js is require()d as a module by the vmd.html bootstrap).
  function resolveThemeName() {
    let t = (process.env.VV_THEME || '').trim();
    if (!t) {
      try {
        const cfgHome = process.env.XDG_CONFIG_HOME || path.join(process.env.HOME || '', '.config');
        t = fs.readFileSync(path.join(cfgHome, 'vinary-viewer', 'theme'), 'utf8').trim();
      } catch (e) { /* no config file → fall through to the default */ }
    }
    return (t && /^[\w-]+$/.test(t)) ? t : 'spacemacs-dark';   // sanitize: a theme name is a bare slug
  }
  function injectTheme() {
    if (!document.head) return;
    const read = (n) => fs.readFileSync(path.join(__dirname, 'themes', n + '.css'), 'utf8');
    let name = resolveThemeName(), css;
    try { css = read(name); }
    catch (e) {                                            // unknown theme → fall back to the default
      if (name === 'spacemacs-dark') return;
      try { css = read('spacemacs-dark'); name = 'spacemacs-dark'; } catch (e2) { return; }
    }
    let el = document.getElementById('vv-theme');
    if (!el) {
      el = document.createElement('style');
      el.id = 'vv-theme';
      document.head.insertBefore(el, document.head.firstChild);   // early → vars defined before paint
    }
    el.textContent = css;
    el.setAttribute('data-vv-theme', name);
  }
  injectTheme();
  if (!document.getElementById('vv-theme')) window.addEventListener('DOMContentLoaded', injectTheme);

  const curPath = () => (document.body && document.body.getAttribute('data-filepath')) || null;
  // execFileSync: no shell is spawned; args are passed verbatim to git.
  const git = (args, cwd) => cp.execFileSync('git', args, {
    cwd, encoding: 'utf8', maxBuffer: 64 * 1024 * 1024, stdio: ['ignore', 'pipe', 'ignore'],
  });

  // Canonicalize a (possibly relative or symlinked) path to an absolute real path so it
  // matches the absolute keys stored as the tree's data-abs values.
  function canon(p) {
    if (!p) return null;
    let r;
    try { r = path.resolve(p); } catch (e) { return null; }
    try { r = fs.realpathSync(r); } catch (e) { /* may not exist yet; keep resolved */ }
    return r;
  }

  // ---- file-type routing helpers ----
  const IMG = new Set(['.png', '.jpg', '.jpeg', '.gif', '.svg', '.webp', '.bmp', '.ico', '.apng', '.avif']);
  const extOf = p => (String(p).match(/\.[^./\\]+$/) || [''])[0].toLowerCase();
  // Text vs binary by a NUL-byte sniff of the first 8 KiB (no extension list to maintain).
  function isText(p) {
    try {
      const fd = fs.openSync(p, 'r');
      const b = Buffer.alloc(8192);
      const n = fs.readSync(fd, b, 0, 8192, 0);
      fs.closeSync(fd);
      for (let i = 0; i < n; i++) if (b[i] === 0) return false;
      return true;
    } catch (e) { return true; }                          // unreadable → let vmd try
  }
  function openInViewer(abs) {
    if (currentWindow && window.vmd && window.vmd.setFilePath) window.vmd.setFilePath(currentWindow.id, abs);
  }

  // ---- Feature 4: file-navigation history is vmd's OWN history (renderer/main.js `hist`). The
  // [vmd-hist] patch makes it grow on EVERY navigation (incl. the tree/image/link opens we route
  // via setFilePath), so the native Back/Forward menu (Alt+Left/Right) stays enabled. We just drive
  // that same history from the mouse thumb buttons (see mouseNav → ipcRenderer.emit). ----

  // image/text → open in the viewer; true-binary → external app.
  function routeFile(abs) {
    if (IMG.has(extOf(abs)) || isText(abs)) openInViewer(abs);
    else if (shell && shell.openItem) shell.openItem(abs);
  }
  // Resolve a clicked <a> to an absolute LOCAL FILE path, or null for external/hash/non-file
  // targets (which vmd should handle). Panel links carry data-abs; content links resolve
  // their href against the current document's directory (mirrors vmd's getLinkType).
  function resolveHref(a) {
    if (panel && panel.contains(a)) { return a.getAttribute('data-abs') || null; }
    let href = a.getAttribute('href');
    if (!href) return null;
    if (href.charAt(0) === '#') return null;                       // in-page anchor → vmd scrolls
    if (/^file:\/\//i.test(href)) href = decodeURI(href.slice(7));
    else if (/^[a-z][a-z0-9+.\-]*:/i.test(href)) return null;      // http(s):, mailto:, … → external
    const cur = curPath();
    let abs;
    try { abs = cur ? path.resolve(path.dirname(canon(cur) || cur), href) : path.resolve(href); }
    catch (e) { return null; }
    try { if (fs.statSync(abs).isFile()) return abs; } catch (e) {}
    return null;
  }

  function buildTree(files, gitRoot) {
    const mk = name => ({ name, dirs: new Map(), files: [] });
    const root = mk('');
    for (const rel of files) {
      const parts = rel.split('/');                       // git ls-files always uses '/'
      let node = root;
      for (let i = 0; i < parts.length - 1; i++) {
        if (!node.dirs.has(parts[i])) node.dirs.set(parts[i], mk(parts[i]));
        node = node.dirs.get(parts[i]);
      }
      node.files.push({ name: parts[parts.length - 1], rel, abs: gitRoot + '/' + rel });
    }
    return root;
  }

  // Build detached DOM nodes (textContent only). Native <details> = collapsed-by-default folders.
  function renderDir(node) {
    const ul = document.createElement('ul');
    ul.className = 'vmd-tree-list';
    const dirs = [...node.dirs.keys()].sort((a, b) => a.localeCompare(b));
    for (const d of dirs) {
      const li = document.createElement('li');
      li.className = 'vmd-tree-dir';
      const det = document.createElement('details');
      const sum = document.createElement('summary');
      sum.textContent = d;
      det.appendChild(sum);
      det.appendChild(renderDir(node.dirs.get(d)));
      li.appendChild(det);
      ul.appendChild(li);
    }
    const files = node.files.slice().sort((a, b) => a.name.localeCompare(b.name));
    for (const f of files) {
      const li = document.createElement('li');
      li.className = 'vmd-tree-file';
      const a = document.createElement('a');
      a.setAttribute('href', f.abs);                       // absolute path → routed in-viewer/external
      a.setAttribute('data-abs', f.abs);
      a.title = f.rel;
      a.textContent = f.name;
      li.appendChild(a);
      ul.appendChild(li);
    }
    return ul;
  }

  // ---- file-search filter: hide tree entries whose repo-relative path doesn't contain the query
  // (case-insensitive), keeping — and expanding — the folders on the way to each match. On an empty
  // query the full tree is restored (collapsed) and the current file's path re-revealed. ----
  function applyTreeFilter(ul, q) {                       // returns true if any descendant is shown
    let any = false;
    for (const li of ul.children) {
      if (li.classList.contains('vmd-tree-file')) {
        const a = li.querySelector('a');
        const hay = ((a && (a.title || a.textContent)) || '').toLowerCase();   // a.title = repo-relative path
        const show = !q || hay.indexOf(q) !== -1;
        li.style.display = show ? '' : 'none';
        if (show) any = true;
      } else if (li.classList.contains('vmd-tree-dir')) {
        const det = li.querySelector('details');
        const childUl = det && det.querySelector('ul.vmd-tree-list');
        const childShown = childUl ? applyTreeFilter(childUl, q) : false;
        li.style.display = childShown ? '' : 'none';
        if (det) det.open = q ? childShown : false;       // expand matches while filtering; collapse on clear
        if (childShown) any = true;
      }
    }
    return any;
  }
  function filterTree(treeBody, query) {
    const q = (query || '').trim().toLowerCase();
    const rootUl = treeBody.querySelector('ul.vmd-tree-list');
    if (rootUl) applyTreeFilter(rootUl, q);
    if (!q) highlight();                                  // empty query → reopen the current file's ancestors
  }

  let panel = null, tocPanel = null, lastHi = null;
  let tocHeadings = [], lastTocActive = null;
  function highlight() {
    if (!panel) return;
    if (lastHi) { lastHi.classList.remove('vmd-current'); lastHi = null; }
    const abs = canon(curPath());          // relative/symlinked → canonical absolute, to match data-abs
    if (!abs) return;
    const sel = (window.CSS && CSS.escape) ? CSS.escape(abs) : abs.replace(/(["\\])/g, '\\$1');
    const a = panel.querySelector('a[data-abs="' + sel + '"]');
    if (!a) return;
    a.classList.add('vmd-current'); lastHi = a;
    for (let el = a.parentNode; el && el !== panel; el = el.parentNode)
      if (el.tagName === 'DETAILS') el.open = true;        // reveal ancestors of current file
    a.scrollIntoView({ block: 'nearest' });
  }

  // ---- Contents tab: a table of contents of the previewed Markdown document, with scroll-spy. ----
  const MD_EXT = new Set(['.md', '.markdown', '.mdown', '.mkdn', '.mkd', '.mdwn', '.mdtxt', '.mdtext']);
  const isMarkdownDoc = () => { const p = curPath(); return p ? MD_EXT.has(extOf(p)) : false; };

  function setTab(name) {
    if (!panel) return;
    if (name === 'toc' && panel.classList.contains('vmd-toc-disabled')) return;   // disabled → ignore
    panel.dataset.tab = name;
  }

  // scroll-spy: highlight the TOC entry whose section spans the vertical middle of the window.
  function updateTocActive() {
    if (!tocHeadings.length) return;
    const mid = window.innerHeight / 2;
    let active = tocHeadings[0];
    for (const t of tocHeadings) { if (t.h.getBoundingClientRect().top - 4 <= mid) active = t; else break; }
    if (lastTocActive && lastTocActive !== active.item) lastTocActive.classList.remove('vmd-toc-current');
    active.item.classList.add('vmd-toc-current');
    if (lastTocActive !== active.item) { active.item.scrollIntoView({ block: 'nearest' }); lastTocActive = active.item; }
  }

  function buildToc() {
    if (!tocPanel) return;
    const mb = document.querySelector('.markdown-body');
    const hs = mb ? mb.querySelectorAll('h1,h2,h3,h4,h5,h6') : [];
    tocPanel.textContent = ''; tocHeadings = []; lastTocActive = null;
    const frag = document.createDocumentFragment();
    hs.forEach(h => {
      const item = document.createElement('a');
      item.className = 'vmd-toc-item vmd-toc-h' + h.tagName.slice(1);
      item.textContent = h.textContent;            // textContent only (no innerHTML)
      item.title = h.textContent;
      item._h = h;                                 // direct ref for scroll-spy + click
      frag.appendChild(item);
      tocHeadings.push({ h, item });
    });
    if (!tocHeadings.length) {
      const e = document.createElement('div'); e.className = 'vmd-toc-empty'; e.textContent = 'No sections';
      tocPanel.appendChild(e);
    } else { tocPanel.appendChild(frag); }
    updateTocActive();
  }

  function refreshToc() {                          // gate the Contents tab + (re)build the list
    if (!panel || !tocPanel) return;
    const md = isMarkdownDoc();
    panel.classList.toggle('vmd-toc-disabled', !md);
    if (!md) {
      if (panel.dataset.tab === 'toc') panel.dataset.tab = 'files';
      tocPanel.textContent = ''; tocHeadings = []; lastTocActive = null;
      return;
    }
    buildToc();
  }

  let spyScheduled = false;
  function onScroll() {
    if (spyScheduled) return; spyScheduled = true;
    requestAnimationFrame(() => { spyScheduled = false; updateTocActive(); });
  }

  function build() {
    if (panel) return true;
    const cur = curPath();
    const dir = cur ? path.dirname(canon(cur) || cur) : process.cwd();
    let gitRoot;
    try { gitRoot = git(['rev-parse', '--show-toplevel'], dir).trim() || null; }
    catch (e) { gitRoot = null; }
    if (!gitRoot) return false;                            // hide: no repo → no panel, no shift
    gitRoot = canon(gitRoot) || gitRoot;
    let files = [];
    try { files = git(['ls-files', '-z'], gitRoot).split('\0').filter(Boolean); }
    catch (e) { /* leave empty */ }
    if (!files.length) return false;

    panel = document.createElement('nav');
    panel.id = 'vmd-sidebar';
    panel.dataset.tab = 'files';                           // active tab

    // tab bar: Files (left) | Contents (right)
    const tabs = document.createElement('div');
    tabs.className = 'vmd-tabs';
    const tabFiles = document.createElement('button');
    tabFiles.className = 'vmd-tab'; tabFiles.dataset.tab = 'files'; tabFiles.textContent = 'Files';
    const tabToc = document.createElement('button');
    tabToc.className = 'vmd-tab'; tabToc.dataset.tab = 'toc'; tabToc.textContent = 'Contents';
    tabs.appendChild(tabFiles); tabs.appendChild(tabToc);

    // Files panel: repo header + the tree
    const filesPanel = document.createElement('div');
    filesPanel.className = 'vmd-panel vmd-panel-files';
    const header = document.createElement('div');
    header.className = 'vmd-sidebar-header';
    header.textContent = path.basename(gitRoot) || gitRoot;
    header.title = gitRoot;
    const filter = document.createElement('input');
    filter.className = 'vmd-tree-filter'; filter.type = 'text';
    filter.placeholder = 'Filter files…'; filter.setAttribute('spellcheck', 'false');
    const treeBody = document.createElement('div');
    treeBody.className = 'vmd-sidebar-body';
    treeBody.appendChild(renderDir(buildTree(files, gitRoot)));
    filter.addEventListener('input', function () { filterTree(treeBody, filter.value); });
    filesPanel.appendChild(header); filesPanel.appendChild(filter); filesPanel.appendChild(treeBody);

    // Contents (TOC) panel: filled per document by refreshToc()
    tocPanel = document.createElement('div');
    tocPanel.className = 'vmd-panel vmd-panel-toc';

    panel.appendChild(tabs); panel.appendChild(filesPanel); panel.appendChild(tocPanel);
    document.body.appendChild(panel);                      // sibling of .page-content → survives re-render
    document.body.classList.add('vmd-has-sidebar');        // gates the §12 content shift
    refreshToc();                                          // initial Contents-tab gate + TOC state
    return true;
  }

  // ---- Feature 3: in-page find. A CUSTOM highlighter scoped to .markdown-body, NOT
  // webContents.findInPage — which in Electron 3 also highlights the query inside our find <input>
  // and steals its focus. Matches are wrapped in <mark> (cross-node, via per-text-node Range
  // segments, so hits spanning hljs/inline <span>s are found); the find bar (#vmd-find) lives
  // outside .markdown-body, so it is never searched. Opened by Ctrl/Cmd+F ('find' IPC or keydown). ----
  let findBar = null, findInput = null, findCount = null;
  let findMatches = [], findActiveIdx = -1;          // findMatches[i] = array of <mark>s for match i
  let tocObserver = null, pageContentEl = null;       // set in the bottom observer block

  // Run find DOM mutations with the .page-content observer detached, so our own <mark> edits
  // don't trigger a TOC rebuild / re-find loop.
  function findMutate(fn) {
    if (tocObserver) tocObserver.disconnect();
    try { fn(); }
    finally { if (tocObserver && pageContentEl) requestAnimationFrame(function () { tocObserver.observe(pageContentEl, { childList: true, subtree: true }); }); }
  }

  function clearFindMarks() {
    const marks = document.querySelectorAll('mark.vmd-find-mark');
    const parents = new Set();
    marks.forEach(function (m) { const p = m.parentNode; if (!p) return; while (m.firstChild) p.insertBefore(m.firstChild, m); p.removeChild(m); parents.add(p); });
    parents.forEach(function (p) { p.normalize(); });    // re-merge split text nodes → pristine DOM
    findMatches = []; findActiveIdx = -1;
  }

  function setFindActive(i) {
    if (!findMatches.length) return;
    if (findActiveIdx >= 0 && findMatches[findActiveIdx]) findMatches[findActiveIdx].forEach(function (m) { m.classList.remove('vmd-find-active'); });
    findActiveIdx = ((i % findMatches.length) + findMatches.length) % findMatches.length;
    const marks = findMatches[findActiveIdx];
    marks.forEach(function (m) { m.classList.add('vmd-find-active'); });
    if (marks[0]) marks[0].scrollIntoView({ block: 'center' });
    if (findCount) findCount.textContent = (findActiveIdx + 1) + '/' + findMatches.length;
  }

  function runFind(query) {
    findMutate(function () {
      clearFindMarks();
      if (!query) { if (findCount) findCount.textContent = ''; return; }
      const mb = document.querySelector('.markdown-body');
      if (!mb) { if (findCount) findCount.textContent = ''; return; }
      // 1. flatten text nodes with cumulative offsets
      const nodes = []; let flat = '';
      const walker = document.createTreeWalker(mb, NodeFilter.SHOW_TEXT);
      let tn;
      while ((tn = walker.nextNode())) {
        if (!tn.nodeValue) continue;
        nodes.push({ node: tn, start: flat.length, len: tn.nodeValue.length });
        flat += tn.nodeValue;
      }
      // 2. match ranges (case-insensitive)
      const hay = flat.toLowerCase(), needle = query.toLowerCase();
      const ranges = []; let from = 0, idx;
      while ((idx = hay.indexOf(needle, from)) !== -1) { ranges.push([idx, idx + needle.length]); from = idx + needle.length; }
      if (!ranges.length) { if (findCount) findCount.textContent = 'No matches'; return; }
      // 3. per-text-node segments tagged by match index
      const byNode = new Map();
      ranges.forEach(function (r, mi) {
        const s = r[0], e = r[1];
        for (const ne of nodes) {
          const ns = ne.start, neEnd = ne.start + ne.len;
          if (neEnd <= s || ns >= e) continue;
          if (!byNode.has(ne)) byNode.set(ne, []);
          byNode.get(ne).push({ lstart: Math.max(s, ns) - ns, lend: Math.min(e, neEnd) - ns, mi: mi });
        }
      });
      // 4. wrap each node's segments (descending offset, so earlier splits don't shift later ones)
      const matchMarks = ranges.map(function () { return []; });
      byNode.forEach(function (list, ne) {
        list.sort(function (a, b) { return b.lstart - a.lstart; });
        const head = ne.node;
        list.forEach(function (sg) {
          let target = (sg.lstart > 0) ? head.splitText(sg.lstart) : head;
          if (sg.lend - sg.lstart < target.nodeValue.length) target.splitText(sg.lend - sg.lstart);
          const mark = document.createElement('mark'); mark.className = 'vmd-find-mark';
          target.parentNode.insertBefore(mark, target); mark.appendChild(target);
          matchMarks[sg.mi].push(mark);
        });
      });
      findMatches = matchMarks;
      setFindActive(0);
    });
  }

  function ensureFindBar() {
    if (findBar) return;
    findBar = document.createElement('div'); findBar.id = 'vmd-find';
    findInput = document.createElement('input');
    findInput.type = 'text'; findInput.placeholder = 'Find in page…'; findInput.className = 'vmd-find-input';
    findCount = document.createElement('span'); findCount.className = 'vmd-find-count';
    findBar.appendChild(findInput); findBar.appendChild(findCount);
    document.body.appendChild(findBar);
    findInput.addEventListener('input', function () { runFind(findInput.value); });
    findInput.addEventListener('keydown', function (ev) {
      if (ev.key === 'Escape') { ev.preventDefault(); ev.stopPropagation(); closeFind(); }
      else if (ev.key === 'Enter') {
        ev.preventDefault(); ev.stopPropagation();
        if (findMatches.length) setFindActive(findActiveIdx + (ev.shiftKey ? -1 : 1));   // Enter=next, Shift+Enter=prev
      }
    });
  }
  function openFind() {
    ensureFindBar();
    findBar.classList.add('vmd-find-open');
    findInput.focus(); findInput.select();   // select lets the user overtype a prior query
    if (findInput.value) runFind(findInput.value);
  }
  function closeFind() {
    if (findBar) findBar.classList.remove('vmd-find-open');
    findMutate(clearFindMarks);
  }
  function reFindIfOpen() {                   // re-highlight after the document re-renders (navigation)
    if (findBar && findBar.classList.contains('vmd-find-open') && findInput && findInput.value) runFind(findInput.value);
  }

  // ---- Embedded-figure sizing (§Q1). Render each embedded SVG so its internal text matches the
  // document font: width = docFont × viewBoxWidth / svgFontSize, read straight from the .svg file.
  // This scales a figure DOWN as well as up — a d2/PlantUML SVG often ships with only a viewBox and
  // NO width/height, which leaves the <img> intrinsic-size-less so the browser stretches it to the
  // full column (magnifying its text). If the font-matched width would still exceed the column we
  // fall back to the natural viewBox width (capped) — never wider. Raster images stay natural. §11
  // centers; the dedicated single-image view (.vmd-image-view, §15) is the only full-width case. ----
  const svgMeta = new Map();   // abs .svg path → { v: viewBox width (user units), f: dominant font px }
  function parseSvgMeta(absPath) {
    if (svgMeta.has(absPath)) return svgMeta.get(absPath);
    const meta = { v: 0, f: 0 };
    try {
      const txt = fs.readFileSync(absPath, 'utf8');
      const vb = txt.match(/viewBox\s*=\s*["']\s*[-\d.eE]+\s+[-\d.eE]+\s+([-\d.eE]+)\s+([-\d.eE]+)/);
      if (vb) meta.v = parseFloat(vb[1]);                            // viewBox width = the SVG's user-unit width
      else { const w = txt.match(/<svg[^>]*\swidth\s*=\s*["']([\d.]+)(?:px)?["']/); if (w) meta.v = parseFloat(w[1]); }  // px/unitless only (not %, not stroke-width)
      // Only ABSOLUTE font sizes vote for the dominant font. A relative unit (em/rem/%/ex/ch/vw/vh) scales an
      // INHERITED size, so it is a multiplier, not a size: d2 embeds a `.md` stylesheet for markdown labels
      // whose 1em/1.25em/0.875em/0.85em declarations all round to 1 and outvote the real 16px text labels,
      // driving the font-matched width to docFont × viewBox / 1 — an unbounded blow-up the column cap hides.
      // A bare number is user units as an SVG attribute (font-size="14") but invalid CSS as a declaration
      // (font-size:14), hence the separator capture. Sizes under 2px cannot be real text (and f→0 ⇒ width→∞).
      // null-prototype: an unmatched unit must read as undefined, never as an inherited Object.prototype key
      const ABS_PX = Object.assign(Object.create(null),
        { px: 1, pt: 96 / 72, pc: 16, in: 96, cm: 96 / 2.54, mm: 96 / 25.4, q: 96 / 101.6 });
      const counts = {}; let m; const re = /font-size\s*([:=])\s*["']?\s*([\d.]+)\s*([a-z%]*)/gi;
      while ((m = re.exec(txt))) {
        const unit = m[3].toLowerCase();
        const scale = unit === '' ? (m[1] === '=' ? 1 : undefined) : ABS_PX[unit];
        if (scale === undefined) continue;                           // relative unit → not a size → skip
        const k = Math.round(parseFloat(m[2]) * scale);
        if (k >= 2) counts[k] = (counts[k] || 0) + 1;                // <2px cannot be real text (f→0 ⇒ width→∞)
      }
      let best = 0, bestCount = 0;                                   // dominant (most-frequent) font size
      for (const k in counts) if (counts[k] > bestCount) { bestCount = counts[k]; best = parseFloat(k); }
      meta.f = best;                                                 // ties → smaller size (integer keys iterate ascending)
    } catch (e) { /* unreadable → zeros → fall through to natural sizing */ }
    svgMeta.set(absPath, meta);
    return meta;
  }
  function scaleFigures() {
    const mb = document.querySelector('.markdown-body');
    if (!mb || mb.classList.contains('vmd-image-view')) return;     // dedicated image view → §15 owns sizing
    const cs = window.getComputedStyle(mb);
    const avail = mb.clientWidth - parseFloat(cs.paddingLeft || '0') - parseFloat(cs.paddingRight || '0');
    const docFont = parseFloat(cs.fontSize) || 16;
    mb.querySelectorAll('img').forEach(function (img) {
      if (/^emoji/i.test(img.getAttribute('src') || '')) return;    // inline emoji stay inline
      const url = img.src || '';
      if (!/^file:\/\//i.test(url)) { img.style.width = ''; return; }          // remote/data → leave to CSS
      const abs = decodeURI(url.slice(7)).replace(/[?#].*$/, '');
      if (!/\.svg$/i.test(abs)) { img.style.width = ''; return; }              // raster → natural size (§11 centers)
      const meta = parseSvgMeta(abs);
      if (!meta.v) { img.style.width = ''; return; }                          // no geometry → natural
      let target;
      if (meta.f > 0 && avail > 0) {
        const matched = docFont * meta.v / meta.f;                           // width at which the SVG text == doc font
        target = (matched <= avail) ? matched : Math.min(meta.v, avail);     // font-match if it fits, else natural (capped)
      } else {
        target = (avail > 0) ? Math.min(meta.v, avail) : meta.v;            // no font info → natural viewBox (capped)
      }
      img.style.width = Math.round(target) + 'px';
    });
  }
  let figSched = false;
  function scheduleFigures() {                 // coalesce resize bursts
    if (figSched) return; figSched = true;
    requestAnimationFrame(function () { figSched = false; scaleFigures(); });
  }

  if (ipcRenderer) {
    try { ipcRenderer.on('find', openFind); } catch (e) {}
    // History (Alt+Left/Right menu, app-command) is handled NATIVELY by vmd's own
    // ipcRenderer.on('history-back'/'history-forward', …) → navigateTo(hist.back()/forward()); we no
    // longer hook those channels (that produced a competing second history). The mouse drives the
    // same native handler via ipcRenderer.emit (see mouseNav).
  }

  // ---- unified click router (registered before vmd's main.js, so stopPropagation wins) ----
  window.addEventListener('click', function (ev) {
    const t = ev.target;
    if (!t || !t.closest) return;
    // 0a. tab switch (Files / Contents). Tabs are <button>, not <a>, so they don't hit the
    //     file-link branch; stopImmediatePropagation keeps vmd's same-node handler out.
    const tab = t.closest('.vmd-tab');
    if (tab && panel && panel.contains(tab)) {
      ev.preventDefault(); ev.stopImmediatePropagation(); setTab(tab.dataset.tab); return;
    }
    // 0b. TOC item → scroll its heading into view (TOC items carry no data-abs/file href, so the
    //     file-link branch resolves them to null anyway; handle them explicitly here).
    const tocItem = t.closest('.vmd-toc-item');
    if (tocItem && panel && panel.contains(tocItem)) {
      ev.preventDefault(); ev.stopImmediatePropagation();
      if (tocItem._h) tocItem._h.scrollIntoView({ block: 'start' });   // instant jump (reliable; no animation dependency)
      return;
    }
    // 1. folder summary → toggle the <details> ourselves (vmd preventDefaults the native toggle)
    const summary = t.closest('summary');
    if (summary && panel && panel.contains(summary) &&
        summary.parentNode && summary.parentNode.tagName === 'DETAILS') {
      // stopImmediatePropagation (not stopPropagation): vmd's click handler is on the SAME
      // window node, registered after ours, so only stopImmediate suppresses it.
      ev.preventDefault(); ev.stopImmediatePropagation();
      summary.parentNode.open = !summary.parentNode.open;
      return;
    }
    // 2. any <a> resolving to a local file (panel link, doc link, or a LINKED image) → route
    const a = t.closest('a');
    if (a) {
      const abs = resolveHref(a);
      if (abs) { ev.preventDefault(); ev.stopImmediatePropagation(); routeFile(abs); }  // suppress vmd's handleLink
      return;                                               // external/hash link → let vmd handle
    }
    // 3. a bare embedded image (not wrapped in a link) → open its dedicated full-width view
    if (t.tagName === 'IMG' && t.closest('.markdown-body') && !t.closest('a')) {
      const src = t.src || '';
      if (/^file:\/\//i.test(src)) {
        ev.preventDefault(); ev.stopImmediatePropagation();
        routeFile(decodeURI(src.slice(7)));
      }
    }
  }, false);

  // ---- Feature 4: mouse thumb buttons (3=back, 4=forward) → vmd's native file history.
  // Handle mousedown FIRST: Chromium triggers its native back/forward on mousedown, so preventing
  // the default there (not on mouseup, which is too late) is what lets our handler run. mouseup +
  // auxclick stay as fallbacks; a short debounce makes one physical press navigate exactly once.
  // We emit vmd's own history-back/forward IPC (= ipcRenderer.on listener it registers) so the mouse
  // and the Alt+Left/Right menu traverse the SAME history. ----
  let lastMouseNav = 0;
  function mouseNav(ev) {
    if (ev.button !== 3 && ev.button !== 4) return;     // only the back/forward thumb buttons
    ev.preventDefault();                                 // cancel Chromium's native history navigation
    const now = Date.now();
    if (now - lastMouseNav < 250) return;               // dedupe mousedown + mouseup + auxclick for one press
    lastMouseNav = now;
    if (!ipcRenderer) return;
    try { ipcRenderer.emit(ev.button === 3 ? 'history-back' : 'history-forward'); } catch (e) {}
  }
  window.addEventListener('mousedown', mouseNav, false);
  window.addEventListener('mouseup', mouseNav, false);
  window.addEventListener('auxclick', mouseNav, false);

  // Ctrl/Cmd+F opens the find bar directly too (robust if vmd's menu accelerator / 'find' IPC
  // doesn't reach us). openFind() is idempotent, so double-firing with the menu is harmless.
  // Also: when the find bar is open, Escape closes IT (not the window) — vmd's own keydown
  // (renderer/main.js) closes the window on Escape, so we must intercept before it, regardless
  // of where focus is.
  window.addEventListener('keydown', function (ev) {
    if ((ev.ctrlKey || ev.metaKey) && !ev.altKey && (ev.key === 'f' || ev.key === 'F')) {
      ev.preventDefault(); openFind();
    } else if (ev.key === 'Escape' && findBar && findBar.classList.contains('vmd-find-open')) {
      // stopImmediatePropagation: vmd's keydown (same window node) closes the window on Escape.
      ev.preventDefault(); ev.stopImmediatePropagation(); closeFind();
    }
  }, false);

  // Build at load (fast path within the repo); retry when data-filepath first appears
  // (relative/absolute launch); once built, the observer just re-highlights on navigation.
  function tick() { if (build()) highlight(); refreshToc(); }
  tick();
  new MutationObserver(tick).observe(document.body, { attributes: true, attributeFilter: ['data-filepath'] });

  // Rebuild the TOC when the previewed document's content changes (it renders async, after
  // data-filepath is set). .page-content persists and does NOT contain the sidebar (the panel is
  // a <body> child), so the tree build doesn't trigger this. Scroll-spy updates on scroll/resize.
  let tocSched = false;
  function scheduleToc() {
    if (tocSched) return; tocSched = true;
    requestAnimationFrame(function () { tocSched = false; refreshToc(); reFindIfOpen(); scaleFigures(); });   // re-highlight find + size figures after re-render
  }
  pageContentEl = document.querySelector('.page-content');
  if (pageContentEl) { tocObserver = new MutationObserver(scheduleToc); tocObserver.observe(pageContentEl, { childList: true, subtree: true }); }
  window.addEventListener('scroll', onScroll, true);     // capture: scroll doesn't bubble
  window.addEventListener('resize', function () { onScroll(); scheduleFigures(); });   // scroll-spy + re-fit figures to the new width
  scaleFigures();                                         // size any figures already rendered at load
})();
