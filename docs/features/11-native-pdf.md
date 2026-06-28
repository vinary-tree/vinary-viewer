# PDF preview (in-renderer)

**Status: Available now.** *(Supersedes the original native-PDF view — see [ADR-0013](../design-decisions/0013-in-renderer-pdfjs.md).)*

---

## 1 · What it is

PDFs render **in the renderer DOM via [pdf.js](https://mozilla.github.io/pdf.js/)**, exactly like the
Markdown and source previews — not in a separate native viewer. Each page is drawn to a `<canvas>` with a
transparent **text layer** and a **link layer** overlaid, all inside the `.vv-content` scroller. Because
the document lives in the app's own DOM, a PDF behaves like every other document:

- **App keymap + smooth scrolling** — bare arrows, `j`/`k`, Space/PageDown, `g g`/`G`, etc.
- **In-page find** (`Ctrl+F`) across the whole document (text is materialized on demand).
- **Text selection + Copy** — select with the mouse, `Ctrl+C`, or right-click → **Copy** (the themed menu).
- **Zoom / fit / dark-invert** and **bookmarks** in the Contents tab.
- **Themes** — the page letterbox follows the active palette.

## 2 · How you use it

Open a `.pdf` file (CLI `vv report.pdf`, the file tree, or a link). Then:

| Action | Keys / UI |
|---|---|
| Scroll | arrows, `j`/`k`, Space / `Shift`+Space, Page keys, `Home`/`End` |
| Zoom in / out / reset | `Ctrl` `+` / `-` / `0` (context-aware: zooms the PDF) |
| Fit width / page / actual size | **View** menu |
| Invert (dark PDF) | **View ▸ Invert PDF** (canvas only; text/selection stay normal) |
| Find | `Ctrl+F` |
| Copy selection | `Ctrl+C` or right-click ▸ Copy |
| Jump to a bookmark | **Contents** sidebar tab |

Fit-mode and invert persist across sessions (`settings.edn`). Editing a PDF on disk live-refreshes it.

## 3 · Internals

| Piece | Where |
|---|---|
| Bytes → renderer (over `vv:content`, `:kind "pdf" :bytes …`) | `vinary.main.service/send-content!` |
| Byte cache (keyed by `:doc/path`, **not** DataScript) + find hook | `vinary.renderer.pdf-cache` |
| Render engine (worker bootstrap, canvas/text/link layers, virtualization, zoom, outline) | `vinary.renderer.pdf` |
| Pure geometry/zoom/outline helpers (DOM-free, unit-tested) | `vinary.renderer.pdf-layout` |
| `pdf-view` Reagent component (mounts inside `.vv-content`) | `vinary.ui.views/pdf-view` |
| Vendored worker + cmaps/fonts/wasm/iccs | `scripts/sync-pdfjs.mjs` → `resources/public/pdf/` |

The pdf.js module + worker are **vendored** (the legacy ES5 build) and loaded at runtime via a blob-URL
ESM `import()` — Closure `:simple` cannot bundle pdf.js's internal dynamic `import()`. Pages are
**preallocated** to exact heights (no scroll jank), rendered lazily by an `IntersectionObserver`, and
offscreen canvases are released to bound memory. See [ADR-0013](../design-decisions/0013-in-renderer-pdfjs.md)
for the full rationale, and the smoke coverage in `test/electron-smoke.js` (canvas + aligned text layer +
copy + find).
