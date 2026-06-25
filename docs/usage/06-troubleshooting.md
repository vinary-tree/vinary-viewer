# Troubleshooting

A symptom-first guide. Each entry names what you observe, the most likely causes (in order), and the
exact checks. The fastest single tool is the renderer's **DevTools console** plus the two dev hooks
`__vvdb()` / `__vvds()`; §6 explains the full inspection toolkit.

> **Open DevTools:** Electron's menu is auto-hidden — press `Alt` to reveal it, then *View → Toggle
> Developer Tools* (or the standard shortcut). DevTools shows the renderer's console, where errors and
> the `vv:error` channel surface.

---

## 1. Blank window (nothing renders at all)

The window opens but the content area is empty (not even the watermark), or it is white/garbled.

**Most likely causes, in order:**

1. **The builds were not compiled.** `electron .` runs `dist/main/main.js` and loads
   `resources/public/js/main.js`; if either is missing, nothing works.
   ```bash
   ls dist/main/main.js resources/public/js/main.js   # both must exist
   # If not:
   npm run compile
   ```
2. **The renderer bundle did not load.** `index.html` loads `<script src="js/main.js">`. Open DevTools →
   *Console*; a `Failed to load resource: js/main.js` means the renderer build is missing or the
   `:output-dir`/`:asset-path` is wrong. Re-run `npm run compile` and confirm
   `resources/public/js/main.js` exists.
3. **The preload path is wrong.** The main process computes the preload as
   `<__dirname>/../../resources/preload.js` (`vinary.main.core/preload-path`). If `window.vv` is
   `undefined` in the DevTools console, the preload did not load, so no IPC works.
   ```js
   // DevTools console:
   window.vv          // should be an object with open/close/onContent/onError/onTree
   ```
   If it is `undefined`, verify `resources/preload.js` exists and that you launched from the repo root
   so `__dirname` resolves correctly (the path is relative to `dist/main/`).

**Sanity check the renderer booted:**
```js
__vvdb()    // should return the app-db; if this throws, init() did not run → renderer bundle problem
```

---

## 2. No live-refresh (edits don't update the preview)

You edit and save the open file, but the preview does not change.

**Most likely causes, in order:**

1. **Atomic saves and `awaitWriteFinish`.** Many editors save by writing a temp file and renaming it
   over the original (an *atomic save*); that fires `chokidar`'s `add` rather than `change`. The watcher
   listens for **both** `change` **and** `add` and uses `awaitWriteFinish {stabilityThreshold 80
   pollInterval 20}` to wait for the write to settle. If your editor does something unusual (e.g. writes
   on a network filesystem with odd timestamps), the watcher may miss it — try a plain in-place save to
   confirm refresh works at all:
   ```bash
   printf '\nrefresh test %s\n' "$(date +%s)" >> <the-open-file>
   ```
2. **There is no watcher for that path.** Watchers are **per open path** (`service/open!` starts one
   when a file opens; `service/close!` stops it when the tab closes). If the file is not actually open
   (no tab for it), nothing watches it. Confirm it is open:
   ```js
   __vvds()    // lists open docs; the file's path should be present
   ```
3. **The file moved or was deleted.** A watcher is bound to a specific path; if the file is renamed away
   and a new one takes its place out of band, close and reopen the document to rebind the watcher.

> Detail: [features/01-live-refresh.md](../features/01-live-refresh.md) and
> [design-decisions/0006-multi-watcher-live-refresh.md](../design-decisions/0006-multi-watcher-live-refresh.md).

---

## 3. Markdown not rendering (shows nothing, or "Rendering…", or an error)

The tab opens but the body is empty, stuck on **"Rendering…"**, or shows **"Error: …"**.

**Diagnose by which message you see:**

- **"Error: &lt;message&gt;"** — the document has a `:doc/error`. This comes from either a **read error**
  in the main process (`vv:error`, e.g. the file vanished or is unreadable) or a **render error** in the
  renderer (`:markdown/render` rejected, prefixed `render error:`). The message text tells you which.
  Check the path is readable:
  ```bash
  test -r <the-file> && echo readable || echo "NOT readable"
  ```
- **"Rendering…"** that never resolves — the content arrived but the HTML has not come back. The unified
  pipeline runs in the renderer and returns a `Promise<string>`; if it never resolves you will see a
  rejection logged in the DevTools console (the `.catch` dispatches `[:content/error …]`, which usually
  flips the view to "Error: …"). Open DevTools → *Console* and look for the render-error dispatch.
- **Empty body, no message** — confirm the document actually has content and was classified as
  `markdown`:
  ```js
  __vvds()    // check the doc's :kind is "markdown" (extension .md/.markdown/.mdx)
  ```
  A file with a non-markdown extension is shown as escaped plain text (the **text** kind), not parsed as
  Markdown — that is expected, not a bug. See
  [03-opening-files-and-tabs.md](03-opening-files-and-tabs.md).

> **Note on raw HTML in Markdown.** vinary-viewer does **not** enable `rehype-raw`, so raw HTML embedded
> in a Markdown source is **not** passed through into the rendered output. If a literal `<div>` you
> wrote in a `.md` file does not appear, that is by design (and a deliberate XSS-hardening choice — see
> [security/threat-model.md](../security/threat-model.md)).

---

## 4. Theme not switching

You pick a theme in the selector but the colors don't change.

**Most likely causes, in order:**

1. **The `#vv-theme-link` element is missing or renamed.** The `:theme/apply` effect finds the palette
   stylesheet by `getElementById "vv-theme-link"` and swaps its `href`. If `index.html` lost the
   `id="vv-theme-link"` on the first `<link>`, the swap silently no-ops. Verify:
   ```js
   document.getElementById("vv-theme-link")   // must be the palette <link>, not null
   ```
2. **The theme file does not exist.** The new `href` becomes `css/themes/<name>.css`. If you added a
   theme to the selector but not the file (or vice versa), the link points at a 404 and the palette
   tokens fall back to whatever was last loaded. Confirm the file exists:
   ```bash
   ls resources/public/css/themes/<name>.css
   ```
3. **You edited the structural sheet instead of a palette.** Colors live only in `themes/*.css`;
   `app.css` references `var(--vv-*)`. Editing `app.css` will not change colors unless you change which
   token a rule uses.

> Detail: [05-configuration.md](05-configuration.md). Ignore the stale `VV_THEME`/relaunch comments in
> the theme-file headers — they are legacy.

---

## 5. Sidebar (git tree) is empty

No file-tree appears on the left.

**Causes:**

- The open file is **not inside a git repository**, or `git` is **not on `PATH`**. The tree is built
  from `git rev-parse --show-toplevel` + `git ls-files` of the file's directory; if either fails, no
  tree is sent (the rest of the app still works). Check:
  ```bash
  git -C "$(dirname <the-file>)" rev-parse --show-toplevel
  ```
- The file is **untracked**. `git ls-files` lists tracked files only; an untracked file's repo tree will
  show, but the file itself won't be in it until you `git add` it.

---

## 6. Inspection toolkit

When a symptom is unclear, these are the tools to reach for, in order of convenience:

### 6.1 The dev hooks

```js
__vvdb()    // current re-frame app-db (UI state: active path, theme, find, history, input)
__vvds()    // open documents from DataScript: [{:path … :order … :kind …} …]
```

`__vvdb()` is the fastest way to see *what the UI thinks is true*: the active path, whether find is
visible, the history stack/index, the current theme.

### 6.2 re-frame-10x and re-frisk

A debug build wires in two panels (see [02-installation-and-build.md](02-installation-and-build.md)):

- **re-frame-10x** — step through every event, subscription recomputation, and `app-db` diff. Use it to
  see, e.g., that `[:content/received]` fired but `[:content/rendered]` did not (a render problem).
- **re-frisk** — a live, always-on view of `app-db`, good for watching state change as you interact.

### 6.3 Read `vv:error`

Read errors and render errors both end up as a document's `:doc/error` and render as the **"Error: …"**
view. The originating channel is `vv:error` (read failures from the main process). To watch it directly
in the console:

```js
window.vv.onError((p) => console.log("vv:error", p));   // logs {:path … :message …}
```

This is the canonical way to catch a file that *opens* but *fails to read* (permissions, a vanished
file, an encoding the UTF-8 read rejects).

### 6.4 Watch content and tree messages

```js
window.vv.onContent((p) => console.log("vv:content", p));   // {:path :kind (:text)}
window.vv.onTree((p)    => console.log("vv:tree", p));      // {:root :files}
```

Useful to confirm the main process is actually sending updates on save (live-refresh) and that the tree
payload is non-empty.

---

## 7. Symptom → first check (cheat sheet)

| Symptom                         | First check                                                            |
|---------------------------------|-----------------------------------------------------------------------|
| Blank window                    | `ls dist/main/main.js resources/public/js/main.js`; `window.vv` defined? |
| No live-refresh                 | `__vvds()` shows the file open? plain in-place save works?             |
| Markdown not rendering          | which message — "Error: …" vs "Rendering…"? `:kind` is `"markdown"`?   |
| Theme not switching             | `document.getElementById("vv-theme-link")` non-null? theme file exists? |
| Empty git tree                  | is the file inside a git repo? is `git` on `PATH`?                     |
| A file opens but errors         | `window.vv.onError(console.log)`; is the path readable?               |

---

*Back to [01-getting-started.md](01-getting-started.md) · See also the
[architecture overview](../architecture/01-overview.md).*
