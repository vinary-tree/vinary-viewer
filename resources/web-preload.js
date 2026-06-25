// vinary-viewer web-view preload — runs (isolated) inside the HTTP page shown by the in-app web view.
// It does NOT expose anything to the remote page; it only reads the page's heading outline for the
// Contents/TOC tab, reports the active heading on scroll (so HTML documents get the same section
// scroll-spy as Markdown), and scrolls to a heading when the app's TOC is clicked. All cross-process
// messages go to the main process, which relays them to the app renderer.
const { ipcRenderer } = require('electron');

function headingEls() {
  return Array.prototype.slice.call(document.querySelectorAll('h1,h2,h3,h4,h5,h6'));
}

// Build the outline (assigning ids to anchorless headings so the app can scroll to them).
function outline() {
  return headingEls().map(function (h, i) {
    if (!h.id) h.id = 'vv-h-' + i;
    return { level: parseInt(h.tagName.slice(1), 10),
             text: (h.textContent || '').trim().slice(0, 200),
             id: h.id };
  });
}

function reportToc() { try { ipcRenderer.send('vv:web-toc', outline()); } catch (e) {} }

// The last heading whose top is within 100px of the viewport top (mirrors the Markdown scroll-spy).
function activeHeading() {
  const ctop = 100;
  let active = null;
  const hs = headingEls();
  for (let i = 0; i < hs.length; i++) {
    if (hs[i].getBoundingClientRect().top <= ctop) active = hs[i].id; else break;
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
  window.addEventListener('keydown', function (e) {
    if (active) {
      if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); clear(); }
      else if (e.key === 'Backspace') { e.preventDefault(); e.stopPropagation(); typed = typed.slice(0, -1); refresh(); }
      else if (/^[a-zA-Z]$/.test(e.key)) { e.preventDefault(); e.stopPropagation(); type(e.key); }
      return;
    }
    if (e.key === 'f' && !e.ctrlKey && !e.altKey && !e.metaKey && !inInput()) {
      e.preventDefault(); e.stopPropagation(); start();
    }
  }, true);
})();
