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
