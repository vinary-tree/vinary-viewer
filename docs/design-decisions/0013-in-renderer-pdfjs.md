# 0013 — Render PDFs in the renderer with pdf.js, retiring the native PDF view

- **Status:** Accepted
- **Date:** 2026-06-27
- **Deciders:** vinary-viewer maintainers

## Context

PDFs were shown by a **main-owned native `WebContentsView`** (`vinary.main.pdf`) positioned over the
renderer's content area, using Chromium's built-in PDF viewer. That view is **opaque and paints above
the DOM**, which is exactly why a PDF tab behaved unlike every other preview:

- **No app keymap.** Vim/Emacs/standard bindings, smooth bare-arrow scrolling, and `j`/`k` did nothing —
  the focused surface was a separate Chromium context, not `.vv-content`.
- **No themed context menu / Copy.** Right-click went to Chromium's own menu, not the app's
  `:preview-body` menu.
- **No in-page find.** `vinary.renderer.find` walks text nodes under `.vv-content`; the native viewer's
  text is unreachable.
- **No theming.** The viewer's chrome ignored the active `--vv-*` palette.
- **Overlay fighting.** Because it painted on top, menus/dialogs had to *hide* it (the `:ui/overlay-open?`
  rule), and live-refresh needed a bespoke `pdf/reload!` path.

Every other content kind (markdown, source, image, directory) renders **in the renderer DOM** and gets
all of the above for free. PDFs were the outlier.

## Decision

**Render PDFs in the renderer DOM with [pdf.js](https://mozilla.github.io/pdf.js/) (`pdfjs-dist`),
exactly like the Markdown/source previews. Retire the native PDF `WebContentsView` (kept commented for
recoverability).**

Concretely:

1. **Bytes over the existing IPC seam.** `vinary.main.service/send-content!` reads the PDF file and sends
   its bytes on the existing `vv:content` channel (`{:kind "pdf" :bytes <Uint8Array>}`). The renderer
   caches the bytes in a small **byte cache keyed by `:doc/path`** (`vinary.renderer.pdf-cache`), *not*
   in DataScript — large blobs would violate the lean-entity model of ADR-0010. We rejected having the
   sandboxed renderer `fetch` the `file://` path directly: Chromium blocks cross-`file://` `fetch`, and
   it would breach the renderer's no-filesystem boundary (ADR-0009).
2. **`pdf-view` component** (`vinary.ui.views/pdf-view`, a form-3 Reagent component) mounts a
   `.vv-pdf-doc` element *inside* `.vv-content`. Because the pages live in that scroller with a real
   **text layer**, the app's find / selection / smooth-scroll / scroll-restore / context-menu all work
   unchanged — only the copy `selectable-root` and the context-menu root needed widening to
   `.vv-pdf-doc`.
3. **Windowed canvas + full text layers** (`vinary.renderer.pdf`). Page placeholders are **preallocated**
   to exact heights (one metadata pass) so the scrollbar and scroll-restore are correct before any pixel
   paints (no jank — a project best practice). An `IntersectionObserver` rendered to `.vv-content`
   renders each visible page to a `<canvas>` (HiDPI-correct via `devicePixelRatio`) with an overlaid
   transparent **text layer** (selection/find) and a **link layer** (external + intra-doc destinations),
   and *releases* offscreen canvases (keeping the lightweight text layers) to bound memory on large PDFs.
4. **Module + worker loaded at runtime, off the Closure path.** `pdfjs-dist`'s build uses dynamic
   `import()` that Closure `:simple` cannot bundle (`INTERNAL COMPILER ERROR … import() with unsupported
   arguments`). So we do **not** `:require` it. Instead the **legacy** ES5-friendly build is vendored
   (`scripts/sync-pdfjs.mjs` → `resources/public/pdf/`, like the tree-sitter grammars) and the module is
   loaded at runtime via native ESM `import()` behind `new Function("u","return import(u)")` (so Closure
   never parses the `import()`), from a same-origin **blob URL** (a module worker/import built directly
   from a `file://` document can be refused by Chromium). cmaps / standard_fonts / wasm / iccs are
   vendored too, so every PDF feature works offline.
5. **Context-aware zoom + fit/invert.** `:view/zoom` (`Ctrl + +/-/0`) dispatches `:pdf/zoom` when the
   active doc is a PDF, else the Chromium webview zoom. View-menu items add Fit Width/Page/Actual Size
   and a dark-**invert** (canvas-only, so text/selection stay normal); fit-mode + invert persist in
   `settings.edn`.
6. **Outline → Contents.** `doc.getOutline()` is flattened to the app's `:doc/toc` shape with page-anchor
   ids (`vv-pdf-page-N`), so the existing Contents tab + `:toc/scroll` jump to bookmarks with zero new UI.

Pure geometry/zoom/outline helpers live in a DOM-free `vinary.renderer.pdf-layout` so the node `:test`
build covers them without pulling `pdfjs-dist` (which touches `DOMMatrix`/`Worker`).

## Consequences

- PDFs gain **full parity**: app keymap, smooth scroll, in-page find, themed right-click Copy, text
  selection/copy, zoom/fit, dark-invert, and bookmarks — verified by `test/electron-smoke.js`
  (canvas + aligned text layer, Ctrl+C copy, find) and `test/vinary/core_test.cljs` (`pdf-layout-helpers`).
- The native PDF path is **retired but recoverable**: `vinary.main.pdf`, `pdf-host`/`pdf-rect` in
  `views.cljs`, the `pdf/init!` call (`core.cljs`), and the `vv:pdf-*` preload methods are commented with
  a rationale, never deleted (repo rule). `:ui/overlay-open?` now hides only the *web* view.
- New build artifacts: `scripts/sync-pdfjs.mjs` / `check-pdfjs.mjs` + `scripts/pdfjs.lock.json`, and
  `resources/public/pdf/` (gitignored, regenerated by `npm run pdfjs:sync`, prepended to compile/watch/release).
- A ~1 MB worker + cmaps/fonts ship locally — the cost of offline, CSP-friendly rendering.

## Alternatives considered

- **Keep the native viewer.** Rejected: it is the *cause* of the disparity (opaque, separate context).
- **Renderer `fetch("file://…pdf")`.** Rejected: Chromium blocks cross-`file://` `fetch`; breaks the
  no-filesystem boundary.
- **Bundle `pdfjs-dist` through Closure.** Rejected: its dynamic `import()` cannot be processed by
  `:simple`; the runtime-import-of-vendored-module approach sidesteps Closure entirely.
- **Bytes in DataScript.** Rejected: violates ADR-0010's lean content cache; a path-keyed byte cache with
  retention-driven eviction keeps the entity store small.

## Trade-offs

- **Memory vs. correctness on huge PDFs.** Canvases are windowed + released offscreen; text layers stay
  (cheap) so find/selection cover materialized pages, and full-document find materializes all text lazily
  on `Ctrl+F`. The straightforward exact-preallocation metadata pass is O(pages) at open — fully correct,
  acceptable for typical documents; a page-1-estimate fallback for ≳1500-page PDFs is a future optimization.
- **`new Function` for the dynamic import** needs `unsafe-eval`, which is fine here (the renderer has no
  CSP and is sandboxed); it is the price of keeping Closure happy while loading an ESM module by URL.

Cites: ADR-0003 (imperative DOM, no VDOM over the body — the canvas/text engine follows it), ADR-0009
(Mediator IPC seam), ADR-0010 (bounded content retention).
