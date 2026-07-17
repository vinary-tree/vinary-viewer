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

// A GitHub-style heading slug: lowercase, drop punctuation, spaces -> hyphens, deduped per document with a
// -N suffix (the same dedup github-slugger/rehype-slug use). This aligns HTML heading ids with the shared
// slug policy every other format uses (readable anchors, not the old vv-h-<i> namespace) so the Contents
// outline and in-page anchors line up. This preload is SANDBOXED (contextIsolation, nodeIntegration:false —
// see main/web.cljs), so it cannot require the ESM-only github-slugger; this self-contained slug matches it
// for the common ASCII case, and web-preload stays self-consistent (outline + scroll-spy + click share it)
// regardless of any exotic-unicode drift. An existing DOM id is always preferred over a generated one.
function slugify(text) {
  return String(text || '').toLowerCase().trim()
    .replace(/[^\wÀ-￿\- ]+/g, '')   // keep word chars, non-ASCII letters, hyphen, space
    .replace(/ +/g, '-');
}

function refreshHeadings() {
  const seen = Object.create(null);
  headingCache = headingEls().map(function (h, i) {
    if (!h.id) {
      let s = slugify(h.textContent) || ('section-' + (i + 1));
      const base = s;
      while (seen[s] !== undefined) { seen[base] = (seen[base] || 0) + 1; s = base + '-' + seen[base]; }
      seen[s] = 0;
      h.id = s;
    }
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

// ---- Native password-manager bridge ---------------------------------------------------------------
// No API is exposed to the remote page. The preload only observes the DOM, reports sanitized form presence
// to main, accepts explicit fill messages from main, and sends save candidates to main memory on submit.
(function () {
  function safeOrigin() {
    try { return location.origin; } catch (e) { return ''; }
  }

  function fieldType(el) {
    return String((el && el.getAttribute('type')) || 'text').toLowerCase();
  }

  function visible(el) {
    if (!el || el.disabled || el.readOnly || fieldType(el) === 'hidden') return false;
    const r = el.getBoundingClientRect();
    const s = getComputedStyle(el);
    return r.width > 0 && r.height > 0 && s.visibility !== 'hidden' && s.display !== 'none';
  }

  function inputs(root) {
    return Array.prototype.slice.call((root || document).querySelectorAll('input'));
  }

  function passwordInputs(root) {
    return inputs(root).filter(function (el) { return fieldType(el) === 'password' && visible(el); });
  }

  function usernameInputs(root) {
    const allowed = { text: true, email: true, tel: true, search: true, url: true, username: true };
    return inputs(root).filter(function (el) {
      const t = fieldType(el);
      return visible(el) && allowed[t] && !/otp|totp|2fa|mfa|code|token/i.test((el.name || '') + ' ' + (el.id || '') + ' ' + (el.autocomplete || ''));
    });
  }

  function rootForPassword(password) {
    return password && (password.form || password.closest('form') || document.body || document.documentElement);
  }

  function loginRoots() {
    const seen = [];
    passwordInputs(document).forEach(function (p) {
      const root = rootForPassword(p);
      if (root && seen.indexOf(root) === -1) seen.push(root);
    });
    return seen;
  }

  function scoreUsername(el, password) {
    const text = ((el.autocomplete || '') + ' ' + (el.name || '') + ' ' + (el.id || '') + ' ' + (el.placeholder || '')).toLowerCase();
    let s = 0;
    if (/username|user|login|email|mail/.test(text)) s += 20;
    if ((el.compareDocumentPosition(password) & Node.DOCUMENT_POSITION_FOLLOWING) !== 0) s += 8;
    if (fieldType(el) === 'email') s += 6;
    return s;
  }

  function usernameFor(root, password) {
    const candidates = usernameInputs(root);
    if (!candidates.length) return null;
    return candidates.slice().sort(function (a, b) { return scoreUsername(b, password) - scoreUsername(a, password); })[0];
  }

  function setNativeValue(el, value) {
    if (!el) return;
    const proto = Object.getPrototypeOf(el);
    const desc = proto && Object.getOwnPropertyDescriptor(proto, 'value');
    if (desc && desc.set) desc.set.call(el, value);
    else el.value = value;
    el.dispatchEvent(new Event('input', { bubbles: true }));
    el.dispatchEvent(new Event('change', { bubbles: true }));
  }

  function collectCredentials(root) {
    const passes = passwordInputs(root);
    if (!passes.length) return null;
    const password = passes.find(function (el) { return el.value; }) || passes[0];
    const username = usernameFor(root, password);
    return {
      url: location.href,
      origin: safeOrigin(),
      username: username ? (username.value || '') : '',
      password: password ? (password.value || '') : ''
    };
  }

  function reportForms() {
    try {
      const roots = loginRoots();
      ipcRenderer.send('vv:password-forms', {
        url: location.href,
        origin: safeOrigin(),
        count: roots.length,
        hasPassword: roots.length > 0
      });
    } catch (e) {}
  }

  let formTimer = null;
  function scheduleFormReport() {
    if (formTimer) clearTimeout(formTimer);
    formTimer = setTimeout(function () {
      formTimer = null;
      reportForms();
    }, 120);
  }

  function sendSaveCandidate(root) {
    try {
      const c = collectCredentials(root);
      if (c && c.password) ipcRenderer.send('vv:password-save-candidate', c);
    } catch (e) {}
  }

  ipcRenderer.on('vv:password-fill', function (_event, payload) {
    try {
      const roots = loginRoots();
      if (!roots.length) return;
      const root = roots[0];
      const password = passwordInputs(root)[0];
      const username = usernameFor(root, password);
      if (username && Object.prototype.hasOwnProperty.call(payload || {}, 'username')) setNativeValue(username, String(payload.username || ''));
      if (password && Object.prototype.hasOwnProperty.call(payload || {}, 'password')) setNativeValue(password, String(payload.password || ''));
      if (password) password.focus();
    } catch (e) {}
  });

  document.addEventListener('submit', function (e) {
    sendSaveCandidate(e.target || document);
  }, true);

  document.addEventListener('click', function (e) {
    const el = e.target && e.target.closest && e.target.closest('button,input[type="submit"],input[type="button"]');
    if (!el) return;
    const root = el.form || el.closest('form') || document.body || document.documentElement;
    setTimeout(function () { sendSaveCandidate(root); }, 0);
  }, true);

  document.addEventListener('keydown', function (e) {
    if (e.key !== 'Enter') return;
    const t = e.target;
    if (!t || t.tagName !== 'INPUT') return;
    const root = rootForPassword(t);
    if (root && passwordInputs(root).length) setTimeout(function () { sendSaveCandidate(root); }, 0);
  }, true);

  window.addEventListener('DOMContentLoaded', scheduleFormReport);
  window.addEventListener('load', scheduleFormReport);
  window.addEventListener('pageshow', scheduleFormReport);
  document.addEventListener('input', scheduleFormReport, true);
  if (window.MutationObserver) {
    window.addEventListener('DOMContentLoaded', function () {
      if (document.body) new MutationObserver(scheduleFormReport).observe(document.body, { childList: true, subtree: true, attributes: true, attributeFilter: ['type', 'autocomplete', 'name', 'id'] });
    });
  }
})();

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
  // app-forwarded page/edge keys (PageDown/PageUp/Home/End) when app chrome holds focus instead of this view
  ipcRenderer.on('vv:web-scroll', function (_e, kind) {
    const vh = window.innerHeight;
    if (kind === 'page-down') sby(vh * 0.9, true);
    else if (kind === 'page-up') sby(-vh * 0.9, true);
    else if (kind === 'home') sto(0);
    else if (kind === 'end') sto(document.documentElement.scrollHeight);
  });
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
