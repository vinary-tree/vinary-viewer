# 4 — Features

Each feature below names the `sidebar.js` routine and/or the `style.css` section (`§N`) that implements
it, so you can read the source alongside. Colors are theme variables (`var(--vv-*)`); see
[05 — Theming](05-theming.md).

## File-tree sidebar (`§12`)

A collapsible tree of the git repository that contains the open document.

- **Source of truth.** The tree is the output of `git ls-files` (run via `execFileSync` with an argument
  array — **no shell**, so paths are never interpreted), grouped into `<details>`/`<summary>` folders and
  `<a>` file links. The DOM is built with `createElement` + `textContent` (never `innerHTML`).
- **Routing.** Clicking a link calls `vmd.setFilePath(...)`, which opens the file *in the viewer*: text
  and HTML render in place, images open in the dedicated image view, and true-binary files open in the
  external application.
- **Current file.** The open document is highlighted (`a.vmd-current`) and re-highlighted by `tick()`
  whenever `data-filepath` changes.
- **Repo-gated.** Outside a git repository the panel is not shown and the body never gets
  `vmd-has-sidebar`, so the layout is identical to stock `vmd`.

## Files / Contents tabs and the table of contents (`§17`)

The sidebar is a flex column: a fixed two-tab bar (**Files** | **Contents**) over the active panel.

- **Contents** is a **scroll-spy table of contents** built from the previewed document's headings
  (`h1`–`h6`), indented by level. Clicking an entry scrolls to that heading
  (`scrollIntoView({block:'start'})`).
- **Scroll-spy.** On scroll, the entry whose heading is nearest the vertical middle of the viewport
  (`window.innerHeight / 2`) is marked current (`a.vmd-toc-current`) and scrolled into view within the
  panel, so the outline follows your reading position.
- **Gated.** For a non-Markdown preview (e.g. an image) the Contents tab is disabled
  (`#vmd-sidebar.vmd-toc-disabled`) and the view falls back to Files.

## File-search filter (`§19`)

A sticky input under the repository header filters the tree to files whose **repo-relative path** matches
your query (`filterTree` → `applyTreeFilter`). Matching is recursive: a folder is shown if any descendant
matches, and matching folders are auto-expanded so results are visible. Clearing the box restores the full
tree.

## In-page find (`§18`, `§16`)

`Ctrl`/`Cmd`+`F` opens `#vmd-find`. Typing highlights every match in the document; `Enter` /
`Shift`+`Enter` cycle the active match; `Esc` closes.

- **Why a custom highlighter.** `vmd` bundles `electron-in-page-search`, a `<webview>` overlay that is
  broken on `vmd`'s Electron. Worse, Electron's native `findInPage` matches text in the *search input
  itself*, so the query you type gets highlighted. `vinary-viewer` hides the bundled search and
  implements its own highlighter **scoped to `.markdown-body`**, so the input is never a match.
- **How it works.** `runFind(query)` flattens the document's text nodes, finds matches (including matches
  that span multiple nodes), and wraps each in `<mark class="vmd-find-mark">`, processing segments in
  descending start order so earlier wraps don't invalidate later offsets. The current match gets
  `.vmd-find-active`. Marks are torn down (`clearFindMarks`) before each re-run, and the `data-filepath`
  observer is detached during mark edits so the highlighter's own DOM mutations don't trigger `tick()`.

## Full-width layout (`§10`)

Stock `vmd` caps `.markdown-body` at `max-width: 888px` and centers it. `§10` sets `max-width: none` and
keeps the standard `padding: 45px`, so content fills the window with the usual gutter. The extra
stylesheet is injected after `vmd`'s default block, so it wins by source order.

## Figure font-matching (`scaleFigures`, `§13`)

The most subtle feature. Documentation SVGs (e.g. from `d2`, PlantUML, Graphviz) frequently declare only
a `viewBox="0 0 W H"` and **no** `width`/`height`. An `<img>` for such an SVG has *no intrinsic size*, so
the browser stretches it to the container width — the full column — which **magnifies the diagram's text**
far past the body font.

`vinary-viewer` instead sizes each embedded SVG so its *internal text equals the document font*. Let:

- `docFont` = the computed font size of `.markdown-body` (CSS px),
- `W` = the SVG's `viewBox` width (user units),
- `F` = the dominant `font-size` authored inside the SVG (user units),
- `D` = the on-screen display width we will set (CSS px).

When the SVG is displayed at width `D`, every user unit is scaled by `D / W`, so its text renders at
`F · D / W` CSS px. Setting that equal to the body font and solving for `D`:

```
docFont = F · (D / W)   ⟹   D = docFont · W / F
```

So `scaleFigures` reads `W` and `F` directly from the `.svg` file (`parseSvgMeta` parses the `viewBox`
width and the modal `font-size`), then sets `img.style.width = round(docFont · W / F)` — which scales a
figure **down** as readily as up. If that width would exceed the available column, it falls back to
`min(W, avail)` so a figure is **never** wider than the column or its own natural size. Files without a
`viewBox`/font (raster images) are left at natural size and merely centered (`§11`). Because this needs
per-file geometry, it lives in JavaScript rather than CSS.

> Example: a `d2` diagram with `viewBox="0 0 1020 …"` whose labels are `font-size:14`, viewed in a 16 px
> document, is set to `16 · 1020 / 14 ≈ 1166 px` — its text now matches the prose, instead of being blown
> up to the full column width.

## Centered tables (`§14`)

`github-markdown-css` makes tables `display:block; width:100%`, so `margin:auto` alone can't center them.
`§14` shrinks the block to its content (`width: fit-content`), centers it with `margin:auto`, and keeps
`overflow:auto` so a very wide table scrolls internally instead of overflowing the page.

## Dedicated image view (`§15`, `[vmd-img]`)

Opening an image file — or clicking a figure embedded in a document — shows just that image at full
width. The main-process `[vmd-img]` patch renders the image as a bare `<div class="vmd-image-view">
<img></div>`; `§15` fills the pane width and keeps the 45 px gutter. The container is deliberately **not**
`.markdown-body`, so the next Markdown document (which reuses the existing `.markdown-body`) can't inherit
the full-width image rule. Figures embedded in a document are given `cursor: pointer` to advertise that
they're clickable.

## History navigation and theming

Two features large enough for their own chapters:

- **History navigation** — `Alt`+`←`/`→`, OS app-command, and the mouse thumb buttons, unified onto
  `vmd`'s native history. See [07 — History navigation](07-history-navigation.md).
- **Named themes** — the `--vv-*` variable palette and how to write/select a theme. See
  [05 — Theming](05-theming.md).
