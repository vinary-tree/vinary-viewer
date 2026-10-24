'use strict';

// Passive DOM mirror. The document pipeline still runs once; this sink copies its prepared nodes,
// never its interactions, and never serializes a streamed document into another HTML string.
const scaleFor = (width, previewWidth) => Math.min(1, Math.max(1, previewWidth) / Math.max(1, width));
const centeredTop = (point, viewport, total) => Math.max(0, Math.min(Math.max(0, total - viewport), point - viewport / 2));
const revealTop = (scroll, viewport, top, height) =>
  height >= viewport || top < scroll ? Math.max(0, top)
    : top + height > scroll + viewport ? Math.max(0, top + height - viewport) : scroll;

// Only top-level block boxes are inspected: reading every descendant defeats content-visibility.
// A gap belongs to the following block; a negative fraction preserves its preceding whitespace.
function anchorAt(root, y) {
  const children = root.children;
  if (!children.length) return null;
  let lo = 0, hi = children.length - 1;
  while (lo < hi) {
    const mid = (lo + hi) >>> 1;
    if (children[mid].getBoundingClientRect().bottom < y) lo = mid + 1;
    else hi = mid;
  }
  const node = children[lo], box = node.getBoundingClientRect();
  return { node, fraction: (y - box.top) / Math.max(1, box.height) };
}

function setStyle(node, property, value) {
  if (node.style[property] !== value) node.style[property] = value;
}

function mount({ pane, host, track, marker, scroller, root }) {
  const document = host.ownerDocument, window = document.defaultView;
  const shadow = host.attachShadow({ mode: 'open' });
  const styleHost = document.createElement('div');
  shadow.appendChild(styleHost);
  const ownStyle = document.createElement('style');
  ownStyle.textContent = ':host { display:block; pointer-events:none; user-select:none; }' +
    '.vv-content { display:block; flex:none; height:auto; overflow:visible; position:relative; }' +
    '.vv-content .markdown-body > :not([data-vv-preview-placeholder]) { content-visibility:visible; }' +
    '* { scrollbar-width:none; } ::-webkit-scrollbar { display:none; }';
  shadow.appendChild(ownStyle);
  let stopped = false, source = null, mirror = null, wrapper = null, scale = 1;
  let frame = 0, copyFrame = 0, mutationTimer = 0, refinement = 0;
  let follow = true, dragging = false, pending = null, stylesDirty = true;
  let correction = null, correctionUntil = 0;
  let copies = new WeakMap(), originals = new WeakMap();
  const inBand = new WeakSet();
  const dirty = new Set();
  const main = scroller();
  if (!main) return () => {};

  function schedule() {
    if (!stopped && !frame) frame = window.requestAnimationFrame(measure);
  }
  function syncStyles() {
    // Application-owned styles only. CSS variables and font preferences inherit from the light DOM;
    // the copied sheets supply selectors inside the shadow tree, including MathJax's late sheet.
    styleHost.replaceChildren();
    for (const node of document.head.querySelectorAll('link[rel="stylesheet"], style')) {
      const copy = node.cloneNode(true);
      if (node.tagName === 'LINK') {
        copy.href = node.href;
        copy.addEventListener('load', schedule, { once: true });
      }
      styleHost.appendChild(copy);
    }
    stylesDirty = false;
  }
  function enqueue(node) {
    if (!node || node === source) return;
    while (node.parentNode && node.parentNode !== source) node = node.parentNode;
    if (node.parentNode === source) dirty.add(node);
  }
  function queueCopy() {
    if (!stopped && dirty.size && !copyFrame) copyFrame = window.requestAnimationFrame(copyBatch);
  }
  function resetRoot(next) {
    band.disconnect();
    if (wrapper) { resize.unobserve(wrapper); wrapper.remove(); }
    if (source) resize.unobserve(source);
    source = next;
    mirror = null;
    wrapper = null;
    dirty.clear();
    copies = new WeakMap();
    originals = new WeakMap();
    if (!source) return;
    // Preserve the main pane's padding, document-kind selectors and any renderer-specific ancestors.
    const ancestors = [];
    for (let n = source; n && n !== main; n = n.parentElement) ancestors.unshift(n);
    wrapper = main.cloneNode(false);
    wrapper.removeAttribute('tabindex');
    wrapper.removeAttribute('id');
    let parent = wrapper;
    for (const node of ancestors) {
      const copy = node.cloneNode(false);
      parent.appendChild(copy);
      parent = copy;
    }
    mirror = parent;
    shadow.appendChild(wrapper);
    resize.observe(source);
    resize.observe(wrapper);
    for (const node of source.childNodes) dirty.add(node);
    queueCopy();
  }
  function placeholder(node, height) {
    const copy = node.cloneNode(false);
    if (node.nodeType === 1) {
      copy.dataset.vvPreviewPlaceholder = '';
      // Keep the block's tag, classes, IDs and margins (including structural CSS selectors), but
      // release its text/SVG/image subtree outside the visible band. Its last measured box remains.
      copy.style.height = `${Math.max(0, height)}px`;
      copy.style.boxSizing = 'border-box';
      copy.style.contentVisibility = 'hidden';
      copy.style.visibility = 'hidden';
    }
    return copy;
  }
  function replaceCopy(node, copy) {
    const old = copies.get(node);
    copies.set(node, copy);
    originals.set(copy, node);
    if (old) { if (old.nodeType === 1) band.unobserve(old); old.replaceWith(copy); }
    else {
      let previous = node.previousSibling;
      while (previous && !copies.has(previous)) previous = previous.previousSibling;
      const before = previous ? copies.get(previous).nextSibling : mirror.firstChild;
      mirror.insertBefore(copy, before);
    }
    if (node.nodeType === 1) band.observe(copy);
  }
  function copyBatch() {
    copyFrame = 0;
    if (stopped || !source) return;
    const deadline = window.performance.now() + 4;
    const batch = [];
    for (const node of dirty) {
      dirty.delete(node);
      if (node.parentNode !== source) continue;
      const old = copies.get(node);
      // Text separators stay verbatim. Materialize only blocks in the strip's viewport + overscan;
      // initial placeholder heights borrow top-level boxes without laying out skipped descendants.
      const copy = node.nodeType !== 1 || inBand.has(node) ? node.cloneNode(true)
        : placeholder(node, old ? old.offsetHeight : node.offsetHeight);
      batch.push([node, copy]);
      if (batch.length >= 512 || window.performance.now() >= deadline) break;
    }
    // Read every box before attaching any copies. Interleaving reads and writes makes Chromium
    // lay out the growing mirror once per block instead of once per batch on a long document.
    for (const [node, copy] of batch) replaceCopy(node, copy);
    queueCopy();
    schedule();
  }
  function mappedPoint(y) {
    const anchor = source && anchorAt(source, y);
    const copy = anchor && copies.get(anchor.node);
    const origin = track.getBoundingClientRect().top;
    if (copy) {
      const box = copy.getBoundingClientRect();
      return box.top - origin + anchor.fraction * box.height;
    }
    const box = main.getBoundingClientRect();
    return (main.scrollTop + y - box.top) * scale;
  }
  function measure() {
    frame = 0;
    if (stopped) return;
    const next = root(main);
    if (next !== source) resetRoot(next);
    if (stylesDirty) syncStyles();
    if (!wrapper) return;
    scale = scaleFor(main.clientWidth, pane.clientWidth);
    setStyle(wrapper, 'width', `${main.clientWidth}px`);
    setStyle(wrapper, 'minHeight', `${main.clientHeight}px`);
    setStyle(host, 'width', `${main.clientWidth}px`);
    setStyle(host, 'transform', `scale(${scale})`);
    // Include the stream's estimated, not-yet-committed tail. The mirror's actual block heights can
    // differ from the main pane's offscreen estimates; anchors, not a whole-document ratio, join them.
    const spacer = main.querySelector('.vv-stream-spacer');
    const tail = spacer ? spacer.offsetHeight : 0;
    const height = Math.max(pane.clientHeight, (wrapper.offsetHeight + tail) * scale);
    // Chromium can realize neighboring content-visibility blocks AFTER the first settled frames.
    // A source ResizeObserver brings us back here; retain the click anchor briefly for that layout.
    if (correction && window.performance.now() < correctionUntil) correction();
    else correction = null;
    const box = main.getBoundingClientRect();
    const top = Math.max(0, Math.min(height, mappedPoint(box.top)));
    const bottom = Math.max(top, Math.min(height, mappedPoint(box.top + main.clientHeight)));
    const scrollTop = follow && !dragging ? revealTop(pane.scrollTop, pane.clientHeight, top, bottom - top) : null;
    setStyle(track, 'height', `${height}px`);
    setStyle(marker, 'top', `${top}px`);
    setStyle(marker, 'height', `${Math.max(2, bottom - top)}px`);
    if (scrollTop !== null && pane.scrollTop !== scrollTop) pane.scrollTop = scrollTop;
    if (pending !== null && !dirty.size) jumpFraction(pending);
  }
  function onMainScroll() { follow = !dragging; schedule(); }
  function cancelPending() {
    pending = null;
    correction = null;
    if (refinement) window.cancelAnimationFrame(refinement);
    refinement = 0;
  }
  function navigate(anchor) {
    const node = originals.get(anchor.node);
    if (!node || !node.isConnected) return;
    pending = null;
    if (refinement) window.cancelAnimationFrame(refinement);
    let frames = 12, stable = 0;
    const place = () => {
      const box = node.getBoundingClientRect();
      const point = main.scrollTop + box.top - main.getBoundingClientRect().top + anchor.fraction * box.height;
      const top = centeredTop(point, main.clientHeight, main.scrollHeight);
      stable = Math.abs(main.scrollTop - top) < 1 ? stable + 1 : 0;
      main.scrollTop = top;
    };
    correction = () => { if (node.isConnected) place(); };
    correctionUntil = window.performance.now() + 500;
    place();
    // The target AND its neighbors can have intrinsic placeholder heights. Keep this one anchor
    // through their first layouts, stopping after two settled frames; user input cancels immediately.
    const settle = () => {
      refinement = 0;
      if (stopped || !node.isConnected) return;
      place();
      if (--frames > 0 && stable < 2) refinement = window.requestAnimationFrame(settle);
    };
    refinement = window.requestAnimationFrame(settle);
  }
  function jumpFraction(fraction) {
    const y = track.getBoundingClientRect().top + fraction * track.offsetHeight;
    if (mirror && y <= mirror.getBoundingClientRect().bottom) {
      const anchor = anchorAt(mirror, y);
      if (anchor) { navigate(anchor); return; }
    }
    // Clicked a region the stream has not committed yet. Keep the target until it arrives, unless
    // another user navigation supersedes it. Never force the stream to materialize just for a preview.
    pending = fraction;
    main.scrollTop = centeredTop(fraction * main.scrollHeight, main.clientHeight, main.scrollHeight);
  }
  function jump(event) {
    const box = track.getBoundingClientRect();
    jumpFraction(Math.max(0, Math.min(1, (event.clientY - box.top) / Math.max(1, box.height))));
  }
  function down(event) {
    if (event.button !== 0) return;
    event.preventDefault();
    dragging = true;
    track.setPointerCapture(event.pointerId);
    jump(event);
  }
  function move(event) { if (dragging) jump(event); }
  function up() { dragging = false; follow = false; }
  function browse() { follow = false; cancelPending(); }
  function onKey(event) {
    // The application's window capture listener owns keyboard scrolling. Observe it on that same
    // node so a handled key still cancels a pending/refining click before its navigation takes over.
    if (pane.contains(event.target)) browse();
    else cancelPending();
  }
  const resize = new window.ResizeObserver(schedule);
  const band = new window.IntersectionObserver(entries => {
    if (stopped) return;
    const evicted = [];
    for (const entry of entries) {
      const copy = entry.target, node = originals.get(copy);
      if (!node || copies.get(node) !== copy) continue;
      if (entry.isIntersecting) {
        inBand.add(node);
        if (copy.hasAttribute('data-vv-preview-placeholder')) dirty.add(node);
      } else {
        inBand.delete(node);
        if (!copy.hasAttribute('data-vv-preview-placeholder')) {
          evicted.push([node, placeholder(node, copy.offsetHeight)]);
        }
      }
    }
    for (const [node, copy] of evicted) replaceCopy(node, copy);
    queueCopy();
    schedule();
  }, { root: pane, rootMargin: '700px 0px' });
  resize.observe(main);
  resize.observe(pane);
  const mutations = new window.MutationObserver(records => {
    if (stopped) return;
    const next = root(main);
    if (next !== source) resetRoot(next);
    for (const record of records) {
      if (record.target === source && record.type === 'childList') {
        for (const node of record.removedNodes) {
          const copy = copies.get(node);
          if (copy) { if (copy.nodeType === 1) band.unobserve(copy); copy.remove(); }
          copies.delete(node);
          dirty.delete(node);
        }
        for (const node of record.addedNodes) enqueue(node);
      } else if (source && source.contains(record.target)) enqueue(record.target);
    }
    // Appends, media resolution and disclosure changes are coalesced; copying is separate from the
    // scroll/marker frame. Find's CSS highlights do not mutate the document and trigger no copying.
    if (!mutationTimer) mutationTimer = window.setTimeout(() => {
      mutationTimer = 0; queueCopy(); schedule();
    }, 100);
  });
  mutations.observe(main, { childList: true, subtree: true, attributes: true, characterData: true });
  const styles = new window.MutationObserver(() => { stylesDirty = true; schedule(); });
  styles.observe(document.head, { childList: true, subtree: true, attributes: true, attributeFilter: ['href'] });
  main.addEventListener('scroll', onMainScroll, { passive: true });
  main.addEventListener('wheel', cancelPending, { passive: true });
  window.addEventListener('pointerdown', cancelPending, true);
  pane.addEventListener('wheel', browse, { passive: true });
  window.addEventListener('keydown', onKey, true);
  track.addEventListener('pointerdown', down);
  track.addEventListener('pointermove', move);
  track.addEventListener('pointerup', up);
  track.addEventListener('pointercancel', up);
  track.addEventListener('lostpointercapture', up);
  document.fonts.addEventListener('loadingdone', schedule);
  schedule();
  return () => {
    stopped = true;
    for (const id of [frame, copyFrame, refinement]) if (id) window.cancelAnimationFrame(id);
    if (mutationTimer) window.clearTimeout(mutationTimer);
    dirty.clear();
    pending = null;
    correction = null;
    resize.disconnect(); band.disconnect(); mutations.disconnect(); styles.disconnect();
    main.removeEventListener('scroll', onMainScroll);
    main.removeEventListener('wheel', cancelPending);
    window.removeEventListener('pointerdown', cancelPending, true);
    pane.removeEventListener('wheel', browse);
    window.removeEventListener('keydown', onKey, true);
    track.removeEventListener('pointerdown', down);
    track.removeEventListener('pointermove', move);
    track.removeEventListener('pointerup', up);
    track.removeEventListener('pointercancel', up);
    track.removeEventListener('lostpointercapture', up);
    document.fonts.removeEventListener('loadingdone', schedule);
    shadow.replaceChildren();
  };
}

module.exports = { scaleFor, centeredTop, revealTop, mount };
