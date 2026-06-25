# Opening files and tabs

There are three ways to open a document, and a small, predictable multi-tab model on top of them. This
page covers each, plus what file kinds render today and which are planned.

---

## 1. Three ways to open a file

### 1.1 From the command line

The main process reads `process.argv` and opens the **first non-flag argument** as a document
(`vinary.main.core/initial-file`: it drops `argv[0]`=electron and `argv[1]`=app path, removes anything
starting with `-`, and takes the first remaining token).

```bash
electron . README.md
electron . docs/usage/01-getting-started.md
```

**What you see:** the window opens with that file already rendered, a tab for it in the strip, and the
git file-tree of its repository in the left sidebar.

> **Forthcoming (planned): `vv <file>`.** A `vv` launcher that forwards its argument to the app is
> planned, so `vv README.md` will be equivalent to the above. Not built yet — see
> [02-installation-and-build.md](02-installation-and-build.md).

### 1.2 By clicking in the git file-tree

When a file is open, the main process runs `git rev-parse --show-toplevel` and `git ls-files` for the
repository that contains it, and sends the result (`{:root … :files [...]}`) to the renderer over the
`vv:tree` channel. The left sidebar (`vinary.ui.tree`) folds those flat repo-relative paths into a
nested, collapsible folder/file tree using native `<details>`/`<summary>` elements.

- **Click a file** in the tree → it opens in a tab (`[:doc/open <full-path>]`, which fires the
  `:vv/open` effect → `window.vv.open` → main reads + sends it).
- **Filter** the tree with the *"Filter files…"* box at the top of the sidebar. Typing narrows the
  list to files whose path contains your text (case-insensitive); matching folders are force-expanded so
  the hits are visible. Clearing the box restores the full tree.

> The tree is built from `git ls-files`, so **only tracked files appear**. If `git` is unavailable, or
> the open file is not inside a git repository, no tree is shown (the rest of the app works normally).

### 1.3 Programmatically (the effect)

Internally, every "open" path funnels through one re-frame effect:

```clojure
;; vinary.app.fx
(rf/reg-fx :vv/open  (fn [path] (when-let [^js vv (.-vv js/window)] (.open vv path))))
```

So the CLI open (via the main process at startup) and the tree-click open (via `:doc/open`) converge on
the same main-process `service/open!`, which reads the file, sends its content, sends the tree, and
starts a watcher. There is exactly one code path for "show me this file".

---

## 2. The multi-tab model

A **tab is one open document**, keyed by its absolute path. There is no separate tab data structure:
the open documents *are* the tabs. They live in DataScript as `:doc` entities and are listed, ordered by
`:doc/order`, by the `:tabs` subscription that the tab strip (`vinary.ui.tabs`) renders.

### 2.1 Open

Opening a path that is not yet open creates a new `:doc` entity (`:doc/open? true`, the next
`:doc/order`) and a corresponding tab. Opening a path that **is** already open simply re-sends its
content (live-refresh-style) — the per-path keying means you never get duplicate tabs for the same file.

### 2.2 Activate

- **Click a tab** → `[:tab/activate <path>]` sets it as the active document (and records the navigation
  in history, see §3).
- The active tab is visually distinguished (a colored bottom border and brighter text).
- Switching tabs is **instant**: every open document's rendered HTML already lives in DataScript, so
  activating a tab is just a subscription change — no re-read, no re-render.

### 2.3 Close

- **Click the × on a tab** → `[:tab/close <path>]`. This:
  1. retracts the document entity from DataScript (`[:db/retractEntity eid]`), removing the tab;
  2. fires `:vv/close`, which tells the main process to **stop the chokidar watcher** for that path
     (`window.vv.close` → `vv:close` → `service/close!`); and
  3. if you closed the **active** tab, re-selects a remaining tab as active — specifically the
     **last** remaining open document (`(:path (last remaining))`). If you closed a non-active tab, the
     active tab is unchanged. If you closed the last tab, the content area returns to the watermark.

> **Why closing stops the watcher.** Watchers are per-open-path; closing a tab is the precise moment its
> file no longer needs watching, so the watcher is torn down then. This keeps the watcher set exactly
> equal to the set of open files. See
> [design-decisions/0006-multi-watcher-live-refresh.md](../design-decisions/0006-multi-watcher-live-refresh.md).

---

## 3. Opening interacts with history

Activating or opening a document records a navigation entry, so **back/forward** (`Alt+←` / `Alt+→`)
walk the documents you have viewed. Opening a *new* document after going back truncates the forward
branch — exactly like browser history. Full detail in
[04-keyboard-shortcuts.md](04-keyboard-shortcuts.md) and
[features/07-navigation-history.md](../features/07-navigation-history.md).

---

## 4. File kinds

The main process classifies each file by extension (`service/kind-of`) and the renderer shows it with a
**Strategy** based on `:doc/kind` (`vinary.ui.views/content-view`).

### Available now

| Kind         | Extensions                                              | How it is shown                                                                 |
|--------------|--------------------------------------------------------|---------------------------------------------------------------------------------|
| **markdown** | `.md`, `.markdown`, `.mdx`                              | Rendered through the unified/remark/rehype pipeline (GFM, heading slugs, syntax highlighting) into the document body. |
| **image**    | `.png`, `.jpg`/`.jpeg`, `.gif`, `.svg`, `.webp`, `.bmp`, `.ico`, `.avif` | Displayed with an `<img>` loaded by `file://` path. The main process sends **no text** for images (it does not read binary as UTF-8); the renderer loads the file directly. |
| **text**     | anything else                                          | Wrapped verbatim in `<pre class="vv-plain">`, HTML-escaped (`goog.string/htmlEscape`), so arbitrary text files display safely as preformatted text. |

#### Opening an image

```bash
electron . resources/public/assets/shield.svg
```

**What you see:** the image centered in the content area, scaled to fit the width. (SVG is treated as an
image kind; it is rendered by the browser, not parsed as markup by the app.)

### Forthcoming (planned)

These kinds are referenced in the project's keywords and roadmap but are **not built yet**:

| Kind (planned)    | Intent                                                                 |
|-------------------|------------------------------------------------------------------------|
| **pdf**           | Native PDF rendering (e.g. via an Electron `BrowserView`).              |
| **source**        | Syntax-aware source preview backed by tree-sitter.                     |
| **diagram**       | Inline rendering of `d2` / PlantUML / Mermaid sources to SVG.          |

Tag any reliance on these as **Forthcoming (planned)**; today an unrecognized extension falls through to
the **text** kind and is shown as escaped preformatted text.

---

## 5. Summary

| Action            | Trigger                                | Effect                                                    |
|-------------------|----------------------------------------|-----------------------------------------------------------|
| Open from CLI     | `electron . <file>`                    | renders the file; creates its tab + tree                  |
| Open from tree    | click a file in the left sidebar        | `[:doc/open path]` → opens/activates that tab             |
| Filter the tree   | type in *Filter files…*                | narrows visible files; expands matching folders           |
| Activate a tab    | click a tab                            | `[:tab/activate path]` → active doc changes, history records |
| Close a tab       | click the × on a tab                   | retracts the doc, stops its watcher, re-selects active     |
| (no tabs left)    | close the last tab                     | content area shows the watermark                          |

---

*Next: [04-keyboard-shortcuts.md](04-keyboard-shortcuts.md).*
