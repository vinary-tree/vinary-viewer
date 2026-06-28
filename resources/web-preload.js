// vinary-viewer web-view preload — runs (isolated) inside the HTTP page shown by the in-app web view.
// It does NOT expose anything to the remote page; it only reads the page's heading outline for the
// Contents/TOC tab, reports the active heading on scroll (so HTML documents get the same section
// scroll-spy as Markdown), and scrolls to a heading when the app's TOC is clicked. All cross-process
// messages go to the main process, which relays them to the app renderer.
const { ipcRenderer } = require('electron');

function headingEls() {
  return Array.prototype.slice.call(document.querySelectorAll('h1,h2,h3,h4,h5,h6'));
}

let headingCache = [];

function refreshHeadings() {
  headingCache = headingEls().map(function (h, i) {
    if (!h.id) h.id = 'vv-h-' + i;
    return { level: parseInt(h.tagName.slice(1), 10),
             text: (h.textContent || '').trim().slice(0, 200),
             id: h.id,
             offset: window.scrollY + h.getBoundingClientRect().top };
  });
  return headingCache;
}

// Build the outline from the measured heading cache.
function outline() {
  return (headingCache.length ? headingCache : refreshHeadings()).map(function (h) {
    return { level: h.level, text: h.text, id: h.id };
  });
}

function reportToc() { try { refreshHeadings(); ipcRenderer.send('vv:web-toc', outline()); } catch (e) {} }

// The last heading whose top is within 100px of the viewport top (mirrors the Markdown scroll-spy).
function activeHeading() {
  const target = window.scrollY + 100;
  let active = null;
  const hs = headingCache.length ? headingCache : refreshHeadings();
  let lo = 0, hi = hs.length;
  while (lo < hi) {
    const mid = Math.floor((lo + hi) / 2);
    if (hs[mid].offset <= target) {
      active = hs[mid].id;
      lo = mid + 1;
    } else {
      hi = mid;
    }
  }
  return active;
}

let raf = false;
function reportActive() {
  if (raf) return;
  raf = true;
  requestAnimationFrame(function () { raf = false; try { ipcRenderer.send('vv:web-active-heading', activeHeading()); } catch (e) {} });
}

window.addEventListener('DOMContentLoaded', function () { reportToc(); reportActive(); });
window.addEventListener('load', function () { reportToc(); reportActive(); });
window.addEventListener('scroll', reportActive, true);
window.addEventListener('resize', function () { reportToc(); reportActive(); });

let tocTimer = null;
function scheduleTocRefresh() {
  if (tocTimer) clearTimeout(tocTimer);
  tocTimer = setTimeout(function () {
    tocTimer = null;
    reportToc();
    reportActive();
  }, 80);
}
window.addEventListener('DOMContentLoaded', function () {
  if (window.MutationObserver && document.body) {
    new MutationObserver(scheduleTocRefresh).observe(document.body, { childList: true, subtree: true });
  }
});

// app TOC click → scroll the page to that heading id
ipcRenderer.on('vv:web-scroll-to', function (_e, id) {
  const el = id && document.getElementById(id);
  if (el) el.scrollIntoView({ behavior: 'smooth', block: 'start' });
});

// ---- Vimium-style link hints (f) for the in-app web page (self-contained; mirrors the renderer's) ----
(function () {
  const ALPHA = 'SADFJKLEWCMPGH';
  let active = false, typed = '', hints = [], overlay = null;

  function inInput() {
    const a = document.activeElement;
    return !!a && (a.tagName === 'INPUT' || a.tagName === 'TEXTAREA' || a.isContentEditable);
  }
  function labels(n) {
    const a = ALPHA.split(''), base = a.length, out = [];
    if (n <= base) { for (let i = 0; i < n; i++) out.push(a[i]); return out; }
    for (let x = 0; x < base && out.length < n; x++)
      for (let y = 0; y < base && out.length < n; y++) out.push(a[x] + a[y]);
    return out;
  }
  function visibleLinks() {
    const vh = innerHeight, vw = innerWidth, res = [];
    const as = document.querySelectorAll('a[href]');
    for (let i = 0; i < as.length; i++) {
      const r = as[i].getBoundingClientRect();
      if (r.width > 0 && r.height > 0 && r.top < vh && r.bottom > 0 && r.left < vw && r.right > 0) res.push(as[i]);
    }
    return res;
  }
  function clear() { active = false; typed = ''; if (overlay) { overlay.remove(); overlay = null; } hints = []; }
  function refresh() {
    hints.forEach(function (h) {
      const match = h.label.indexOf(typed) === 0;
      h.node.style.display = match ? '' : 'none';
      if (!match) return;
      // rebuild via DOM (no innerHTML): dimmed typed prefix + remaining label
      h.node.textContent = '';
      if (typed) {
        const t = document.createElement('span');
        t.style.opacity = '.45';
        t.textContent = typed;
        h.node.appendChild(t);
      }
      h.node.appendChild(document.createTextNode(h.label.slice(typed.length)));
    });
  }
  function start() {
    const links = visibleLinks();
    if (!links.length) return;
    const ls = labels(links.length);
    overlay = document.createElement('div');
    overlay.style.cssText = 'position:fixed;inset:0;z-index:2147483600;pointer-events:none;';
    hints = links.map(function (el, i) {
      const r = el.getBoundingClientRect();
      const d = document.createElement('div');
      d.textContent = ls[i];
      d.style.cssText = 'position:absolute;left:' + Math.round(r.left) + 'px;top:' + Math.round(r.top) + 'px;'
        + 'transform:translate(-3px,-3px);background:#bc6ec5;color:#1b2129;font:700 11px monospace;'
        + 'padding:1px 4px;border-radius:3px;border:1px solid #1b2129;box-shadow:0 1px 5px rgba(0,0,0,.55);';
      overlay.appendChild(d);
      return { el: el, label: ls[i], node: d };
    });
    document.documentElement.appendChild(overlay);
    active = true; typed = '';
  }
  function type(ch) {
    typed += ch.toUpperCase();
    const matches = hints.filter(function (h) { return h.label.indexOf(typed) === 0; });
    if (matches.length === 1) { const el = matches[0].el; clear(); el.click(); }
    else if (matches.length === 0) clear();
    else refresh();
  }
  // vim/standard scroll keys — reading parity with the markdown preview (the web page is a separate
  // scroll context, so these scroll it directly rather than round-tripping to the app).
  let lastG = 0;
  function sby(dy, smooth) { window.scrollBy({ top: dy, behavior: smooth ? 'smooth' : 'auto' }); }
  function sto(y) { window.scrollTo({ top: y, behavior: 'smooth' }); }
  function scrollKey(e) {
    const vh = window.innerHeight, k = e.key;
    if (k === 'j' && !e.ctrlKey) sby(48, false);
    else if (k === 'k' && !e.ctrlKey) sby(-48, false);
    else if (k === 'ArrowDown') sby(48, false);
    else if (k === 'ArrowUp') sby(-48, false);
    else if (k === 'd' && e.ctrlKey) sby(vh / 2, true);
    else if (k === 'u' && e.ctrlKey) sby(-vh / 2, true);
    else if (k === ' ' && !e.shiftKey) sby(vh * 0.9, true);
    else if ((k === ' ' && e.shiftKey) || k === 'PageUp') sby(-vh * 0.9, true);
    else if (k === 'PageDown') sby(vh * 0.9, true);
    else if (k === 'G' || k === 'End') sto(document.documentElement.scrollHeight);
    else if (k === 'Home') sto(0);
    else if (k === 'g' && !e.ctrlKey) { const n = Date.now(); if (n - lastG < 400) { sto(0); lastG = 0; } else lastG = n; }
    else return false;
    return true;
  }
  window.addEventListener('keydown', function (e) {
    if (active) {
      if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); clear(); }
      else if (e.key === 'Backspace') { e.preventDefault(); e.stopPropagation(); typed = typed.slice(0, -1); refresh(); }
      else if (/^[a-zA-Z]$/.test(e.key)) { e.preventDefault(); e.stopPropagation(); type(e.key); }
      return;
    }
    if (inInput() || e.altKey || e.metaKey) return;   // Alt = history (handled in main); inputs type normally
    if (e.key === 'f' && !e.ctrlKey) { e.preventDefault(); e.stopPropagation(); start(); return; }
    if (scrollKey(e)) { e.preventDefault(); e.stopPropagation(); }
  }, true);
})();
