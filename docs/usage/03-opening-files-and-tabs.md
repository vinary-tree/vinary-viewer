# Opening files and tabs

This page documents the current opening paths, file-kind strategy, retained-file
watching model, and per-tab history behavior.

---

## 1. Ways to open a resource

| Entry point | Behavior |
|-------------|----------|
| `vv <path>` or `vinary-viewer <path>` | Launches Electron with the first non-flag argument as the initial document. |
| `electron . <path>` or `npm run start -- <path>` | Development equivalent of launcher-based open. |
| `File > Open` | Native multi-file dialog. One selected file navigates the active tab; multiple selected files open one tab each. |
| Sidebar file tree | Clicking a file dispatches `[:doc/open path]`. |
| Markdown links | Left-click navigates the active tab; `Ctrl+click` opens a new tab. |
| Directory path | Opening a folder (CLI arg, a folder link, a breadcrumb segment, or `Alt+Up`) lists it **in the pane** — see §6. |
| File ▸ Open Recent | Re-open one of the last 10 opened files (the MRU), or **Clear Recent** — see §6. |
| URI bar | Normalizes typed file paths, `file://` URIs, and HTTP/HTTPS URLs, then dispatches `[:tab/navigate uri]`. |

The renderer never reads the filesystem directly. Local opens dispatch the
`:vv/open` effect, which calls `window.vv.open(path)`; the Electron main process
reads the file and sends content back over `vv:content`.

---

## 2. File-kind strategy

The main process classifies each local path through `vinary.main.file-kind/kind-of`.

| Kind | Extensions or source | Renderer |
|------|----------------------|----------|
| `markdown` | `.md`, `.markdown`, `.mdx` | unified/remark/rehype render, then Markdown body. |
| `image` | `.png`, `.jpg`, `.jpeg`, `.gif`, `.svg`, `.webp`, `.bmp`, `.ico`, `.avif` | Image preview from local file URL. |
| `pdf` | `.pdf` | Main-owned `WebContentsView` showing Chromium's PDF viewer. |
| `mermaid` | `.mmd`, `.mermaid` | Renderer-side Mermaid SVG preview. |
| `source` | Source files with bundled/user grammars, configured filetype mappings, plus `.d2`, `.puml`, `.dot`, and related non-Mermaid diagram-source extensions | Read-only CodeMirror 6 source view with tree-sitter highlighting when possible. |
| `text` | Fallback | Escaped `<pre class="vv-plain">`. |
| `directory` | any path that is a folder (detected by `vinary.main.service/directory?` *before* `kind-of`) | In-pane directory browser listing the immediate children. |

Mermaid source files render as diagrams. Other diagram source files open as
source text unless you embed generated SVG output in Markdown. Directories are
detected before the extension classifier and list their children rather than being
read as text.

---

## 3. Tab model

Tabs are browser-like views in re-frame `app-db`:

```clojure
{:id 3
 :uri "/abs/path/to/doc.md"
 :hist {:stack [{:uri "/abs/path/to/doc.md" :scroll 0}]
        :idx 0}}
```

DataScript is not the tab owner. DataScript is the bounded content cache keyed
by `:doc/path`; it stores loaded content such as `:doc/html`, `:doc/text`,
`:doc/toc`, `:doc/assets`, `:doc/kind`, and `:doc/error`.

This split matters:

| State | Owner | Reason |
|-------|-------|--------|
| Tab order, active tab, per-tab history, scroll entries | re-frame `app-db` | Fast UI transitions and browser-like history. |
| Loaded document content and render metadata | DataScript | Queryable content cache with bounded eviction. |
| File watchers and native PDF/web views | Electron main process | Privileged OS and Electron APIs stay outside the renderer. |

---

## 4. Navigation behavior

| Action | Event | Result |
|--------|-------|--------|
| Open existing tab's URI | `[:doc/open uri]` | Focuses the existing tab and restores its saved scroll. |
| Open a new URI in active tab | `[:tab/navigate uri]` | Saves the leaving scroll and pushes a new history entry. |
| Open URI in a new tab | `[:tab/open uri]` or `[:doc/open-new uri]` | Saves the current tab scroll, creates a new active tab, and loads the URI. |
| Back/Forward | `[:history/back]` / `[:history/forward]` | Moves inside the active tab's history stack and restores that entry's scroll. |
| Close tab | `[:tab/close id]` | Removes the view and activates a neighbor when needed. |

Opening a new URI after going back truncates the forward branch, matching browser
history semantics.

---

## 5. Retained files and watchers

A **retained file** is any local file still reachable from any open tab history
entry. After tab/history changes, the renderer sends that retained set to the
main process with `window.vv.syncRetainedFiles(paths)`.

The retained set controls:

1. Which local files remain watched by `chokidar`.
2. Which Markdown asset watchers remain active.
3. Which DataScript content entities remain cached.

Closing a tab does not necessarily close its current file if that file still
appears in another tab's history. Once a file is unreachable from every tab
history, its watcher is closed and its cached content is evicted.

See [../design-decisions/0010-bounded-content-retention-and-render-metadata.md](../design-decisions/0010-bounded-content-retention-and-render-metadata.md)
for the design rationale.

---

## 6. Browsing directories and Open Recent

**Directories open in the pane.** Opening a folder lists its immediate children
right in the preview area, instead of popping the OS file manager. A folder is a
normal navigation target: it pushes a history entry, appears in the breadcrumb, and
participates in `Alt+Up` / `Alt+Down`.

The listing is a detailed list (name · size · modified).

| Gesture | Result |
|---------|--------|
| Open an entry | Single-click on Linux, double-click on Windows/macOS, or highlight + `Enter` / `Alt+Down` (a folder descends; a file shows its preview). |
| `Ctrl+click` | Open the entry in a new tab. |
| `↑` `↓` `←` `→` | Smoothly scroll the listing (they do not move the highlight). |
| Right-click a folder ▸ Open in file manager | The one explicit way to hand a folder to the OS. |

See [../features/16-directory-browser.md](../features/16-directory-browser.md) and
[../features/17-breadcrumb-and-up-down-navigation.md](../features/17-breadcrumb-and-up-down-navigation.md).

**File ▸ Open Recent.** Every opened **file** (not a directory or a URL) is added to
a most-recently-used list, capped at 10, surfaced under **File ▸ Open Recent**.
Pick an entry to re-open it, or choose **Clear Recent** to empty the list. The MRU
persists to `recent.edn` (see [05-configuration.md](05-configuration.md)).

---

## 7. Summary

| Goal | Use |
|------|-----|
| Open from shell | `vv <path>` |
| Open in development | `npm run start -- <path>` |
| Open one or many files in the app | `File > Open` |
| Open local link in active tab | left-click |
| Open local link in new tab | `Ctrl+click` |
| Read diagram source | Open it as a source file |
| Render generated diagrams in Markdown | Embed the generated `.svg` or image file |

---

*Next: [04-keyboard-shortcuts.md](04-keyboard-shortcuts.md).*
