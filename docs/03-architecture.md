# 3 — Architecture

This chapter explains how `vinary-viewer` attaches to `vmd` without forking it: the two Electron
processes it touches, the four integration surfaces, and the runtime "spine" (`body[data-filepath]`)
that drives the dynamic features.

## `vmd` in one paragraph

`vmd` is an [Electron](https://www.electronjs.org/) application. Like every Electron app it runs a
**main process** (Node.js — owns windows, menus, the filesystem, IPC) and one **renderer process** per
window (a Chromium page — owns the DOM). `vmd`'s renderer runs with `nodeIntegration` enabled and the
`remote` module available, so renderer code can `require()` Node modules directly. `vmd` reads the file
you pass, renders it to HTML with a GitHub-flavored-Markdown pipeline, and ships the HTML to the renderer,
which drops it into a `.markdown-body` container inside `.page-content`.

## The four integration surfaces

`vinary-viewer` adds exactly four things, shown in amber/red below.

![architecture](figures/architecture.svg)

| # | Surface | Process | Marker | What it adds |
| --- | --- | --- | --- | --- |
| 1 | `vmd.html` bootstrap | renderer | (the `require` line) | Loads `sidebar.js`. |
| 2 | `create-window.js` | main | `[vmd-img]` `[vmd-nav]` `[vmd-mfb]` | Image view, app-command nav, native addon hook. |
| 3 | `renderer/main.js` | renderer | `[vmd-hist]` | Grow file history on every open. |
| 4 | `style.css` + theme | renderer | — | Layout, sidebar/find/TOC chrome, colors. |

Surfaces 1–3 are applied by `apply.sh` (which runs `patch-create-window.js` and `patch-renderer-main.js`);
surface 4 is loaded by `vmd` via `~/.vmdrc`'s `styles.extra` plus a theme `<style>` injected by
`sidebar.js`. Every patch is **marker-guarded** (it checks for its marker before applying, so re-running
is a no-op) and **backs up** the file it edits to `<file>.vv.bak`.

## Surface 1: the inline-`require` bootstrap

`apply.sh` inserts one line into `vmd`'s `renderer/vmd.html`, just before `<script src="main.js">`:

```html
<script>try{require('/…/vinary-viewer/sidebar.js');}catch(e){console.warn('vinary-viewer',e);}</script>
```

**Why an inline `require()` rather than `<script src="…">`?** `vmd`'s main process installs a `file:`
protocol interceptor (`interceptStringProtocol('file', …)`) so it can serve rendered Markdown to the
renderer. A `<script src="file://…/sidebar.js">` tag is fetched through Chromium's resource loader, which
that interceptor can in principle intercept — a fragile coupling. An **inline `require()` of an absolute
path** goes through **Node's module loader** (available because `nodeIntegration` is on), not Chromium's
`file:` fetch, so it sidesteps the interceptor entirely. The `try/catch` guarantees that if `sidebar.js`
is missing or throws, `vmd` still loads normally.

Because `sidebar.js` is `require()`d as a module, its `__dirname` is the install directory — which is how
it finds the `themes/` folder at runtime without any hard-coded path.

## Surface 2 & 3: the patches

| Marker | File | Change |
| --- | --- | --- |
| `[vmd-img]` | `create-window.js` | When the opened file is an image (`.png/.jpg/.svg/…`), render it as a bare `<div class="vmd-image-view"><img …></div>` instead of decoding it as UTF-8 text. The wrapper is **not** `.markdown-body`, so `vmd`'s Markdown path (which reuses the existing `.markdown-body`) can't inherit the image-view class onto the next document. |
| `[vmd-nav]` | `create-window.js` | `win.on('app-command', …)` forwards OS `browser-backward`/`browser-forward` to the renderer as `history-back`/`history-forward`. |
| `[vmd-mfb]` | `create-window.js` | `require()`s the native addon and `register(cb, win.getNativeWindowHandle())`; the callback sends the same history IPC. Wrapped in `try/catch` so an unbuilt addon is inert. |
| `[vmd-hist]` | `renderer/main.js` | In `onContent`, push to `vmd`'s history on **every** new file path (stock `vmd` only pushes on link clicks). This keeps the Back/Forward menu — and thus `Alt`+`←`/`→` — enabled for tree/image/history opens. |

See [06 — Native addon](06-native-addon.md) for `[vmd-mfb]` and [07 — History navigation](07-history-navigation.md)
for `[vmd-nav]`/`[vmd-hist]`.

## Surface 4: the stylesheet and theme

`install.sh` sets `styles.extra = <VV_HOME>/style.css` in `~/.vmdrc`. At window creation `vmd`'s
`create-window.js` reads that path via `styles.getStylesheet(...)` and injects it after the bundled
`github-markdown-css`, so it wins by source order. `style.css` is **purely structural** — it references
`var(--vv-*)` and never hard-codes a color. The *color* comes from a theme file that `sidebar.js` injects
as `<style id="vv-theme">` (see [05 — Theming](05-theming.md)).

## The runtime spine: `body[data-filepath]`

The dynamic features (tree, table of contents, figure sizing) must react every time a new document is
shown — including in-place re-renders where the URL never changes. The signal `sidebar.js` watches is an
attribute `vmd` already maintains: when content loads, `vmd`'s `onContent` sets
`document.body[data-filepath]` to the current file.

`sidebar.js` attaches a `MutationObserver` to that attribute. Each change runs `tick()`:

```js
function tick() { if (build()) highlight(); refreshToc(); }   // + figure scaling
```

- `build()` (re)builds the file tree if the repository changed, and returns whether a tree is present.
- `highlight()` marks the current file in the tree.
- `refreshToc()` rebuilds the table of contents from the new document's headings and (re)arms scroll-spy.
- figure scaling re-measures embedded SVGs against the document font.

### Why the sidebar survives re-renders

`vmd` re-renders by replacing the contents of `.page-content`. If the sidebar lived inside that
container it would be wiped on every navigation. Instead `sidebar.js` appends `<nav id="vmd-sidebar">`
as a **direct child of `<body>`**, a *sibling* of `.page-content`. The panel therefore persists across
re-renders; only its contents are refreshed by `tick()`. The document is shifted right to clear the
fixed panel only when the body carries the `vmd-has-sidebar` class — which `sidebar.js` adds **only**
inside a git repository — so a non-repo document reserves no space and the layout is identical to stock
`vmd`.

## The load sequence

Putting it together, from `vmd file.md` to a themed, decorated preview:

![load sequence](figures/bootstrap-sequence.svg)

1. The `vmd()` wrapper runs `apply.sh` (re-patching if `vmd` was upgraded), then `command vmd file.md`.
2. The main process creates the window and injects the stylesheets (including `style.css`).
3. The renderer parses `vmd.html`; the inline bootstrap `require()`s `sidebar.js`.
4. `sidebar.js` injects the active theme and starts observing `body[data-filepath]`.
5. The main process sends the rendered Markdown; `onContent` sets `data-filepath`.
6. The observer fires `tick()`: build the tree, refresh the table of contents, scale figures.

From here, every subsequent open — via the tree, a link, the image view, or history — sets
`data-filepath` again and re-runs `tick()`.
