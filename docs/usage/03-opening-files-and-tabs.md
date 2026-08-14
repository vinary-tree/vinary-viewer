# Opening files and tabs

This page documents the current opening paths, file-kind strategy, retained-file
watching model, and per-tab history behavior.

---

## 1. Ways to open a resource

| Entry point | Behavior |
|-------------|----------|
| `vv <path\|URL> …` or `vinary-viewer <path\|URL> …` | Launches Electron, opening **every** non-flag argument in its own tab (in argument order; the **first** is focused). Accepts local file paths and `file://` / `http(s)://` / `ssh://` / `sftp://` / archive URIs; relative paths resolve against the launch directory (remote and URL arguments are kept verbatim). `-t/--type TYPE` (repeatable) names the Nth file's type — see §2a; other flags are ignored. |
| Piped stdin (`git diff \| vv`) | When stdin is not a terminal, its bytes become the **first** document (a lone `-` among the files repositions it), in a tab named `stdin`. Untyped piped text renders literally as plain text; `-t diff` (or any type, §2a) re-interprets it — the primary use case is `git diff \| vv -t diff`, whose side-by-side view enriches from the invoking repo. Stdin is drained to EOF (a piped document is a snapshot); the empty pipe opens nothing. Piped documents never enter Open Recent, and `vv --no-daemon` does not read stdin. |
| `electron . <path\|URL> …` or `npm run start -- <path\|URL> …` | Development equivalent; same multi-argument open. |
| `File > Open` | Native multi-file dialog. One selected file navigates the active tab; multiple selected files open one tab each. |
| Sidebar file tree | Clicking a file dispatches `[:doc/open path]`. |
| Markdown links | Left-click navigates the active tab; `Ctrl+click` / middle-click open a **background** tab; `Ctrl+Shift+click` opens a focused one ([ADR-0044](../design-decisions/0044-browser-link-gesture-family.md)). |
| Directory path | Opening a folder (CLI arg, a folder link, a breadcrumb segment, or `Alt+Up`) lists it **in the pane** — see §6. |
| File ▸ Open Recent | Re-open one of the last 10 opened files (the MRU), or **Clear Recent** — see §6. |
| URI bar | Normalizes typed file paths, `file://` URIs, HTTP/HTTPS URLs, and `ssh://` / `sftp://` remote URIs, then dispatches `[:tab/navigate uri]`. |

The renderer never reads the filesystem directly. Local opens dispatch the
`:vv/open` effect, which calls `window.vv.open(path)`; the Electron main process
reads the file and sends content back over `vv:content`.

---

## 2. File-kind strategy

The main process classifies each local path through `vinary.main.file-kind/kind-of`.

| Kind | Extensions or source | Renderer |
|------|----------------------|----------|
| `markdown` | `.md`, `.markdown`, `.mdx` | unified/remark/rehype render, then Markdown body. |
| `org` | `.org` | uniorg parse through the shared Markdown hast suffix, then Markdown body. |
| `latex` | `.tex`, `.latex`, `.ltx` | unified-latex → HTML string → the shared hast suffix, then Markdown body. `.sty`/`.cls`/`.bib` stay `source`. |
| `image` | `.png`, `.jpg`, `.jpeg`, `.gif`, `.svg`, `.webp`, `.bmp`, `.ico`, `.avif` | Image preview from local file URL. |
| `pdf` | `.pdf` | In-renderer pdf.js: each page draws to a `<canvas>` in the content pane as it scrolls into view (ADR-0013). |
| `mermaid` | `.mmd`, `.mermaid` | Renderer-side Mermaid SVG preview. |
| `diff` | `.diff`, `.patch` | Colored unified diff, with an optional side-by-side split view. |
| `source` | Source files with bundled/user grammars, configured filetype mappings, plus `.d2`, `.puml`, `.dot`, and related non-Mermaid diagram-source extensions | Read-only CodeMirror 6 source view with tree-sitter highlighting when possible. |
| `text` | Fallback | Escaped `<pre class="vv-plain">`. |
| `directory` | any path that is a folder (detected by `vinary.main.service/directory?` *before* `kind-of`) | In-pane directory browser listing the immediate children. |

A remote `ssh://` / `sftp://` URI is classified by the same rules (off its
basename extension), then read over SFTP instead of the local filesystem — so a
remote `.md`, `.tex`, `.pdf`, source file, or directory renders exactly as its
local counterpart. See [08-remote-files-ssh.md](08-remote-files-ssh.md).

Mermaid source files render as diagrams. Other diagram source files open as
source text unless you embed generated SVG output in Markdown. Directories are
detected before the extension classifier and list their children rather than being
read as text.

Plain text renders **literally**: the delimiter sniff that used to flip prose with a stable
comma/tab/pipe count into a column-aligned table is disabled (ADR-0036). A real delimited file is
either *named* `.csv`/`.tsv`/… or *declared* — `-t csv`, or Settings ▸ File Type ▸ Delimited Table.
(The log sniff is kept: an extensionless syslog still opens as a pageable log.)

---

## 2a. Explicit file types — `-t/--type` and Settings ▸ File Type (ADR-0036)

The classifier above is only the *default*. An explicit type overrides it, per file:

- **At the CLI** — repeatable `-t/--type TYPE` (alias `--file-type`), pairing positionally: the Nth
  type applies to the Nth file, and a piped-stdin document is file 0 (or wherever `-` placed it).
  Fewer types than files is fine — the rest deduce by extension, else plain text. `TYPE` is a
  standard MIME type (`text/x-diff`, `application/pdf`, `text/csv;charset=utf-8`), a short alias
  (`diff`, `patch`, `md`, `org`, `tex`, `log`, `csv`, `tsv`, `psv`, `table`, `mermaid`, `html`,
  `source`, `pdf`, `image`, `text`), or a **grammar language** (`python`, `rs`, `clojure` — rendered
  as highlighted source with that grammar). Kind tokens win over grammar names: `-t markdown` means
  the *rendered* document, exactly as the `.md` extension does. An unknown token is a usage error
  (with the valid-token list), printed on the invoking terminal even for the GUI (`vv` reads the
  daemon's rejection reply).

  ```bash
  git diff | vv -t diff              # THE use case: a piped diff, unified/side-by-side
  vv -t diff sftp://arch-ws/x.log    # types work on remote URIs too
  vv -t md notes.txt -t py tool      # notes.txt as markdown, `tool` as python source
  ```

- **In the app** — **Settings ▸ File Type** re-interprets the *shown* document (the active facet's
  file): the text-representable kinds first (Plain Text, Markdown, Org, LaTeX, Diff / Patch, Log,
  Delimited Table, Mermaid, HTML, Source (auto)), then every bundled grammar language. The pick
  re-reads and re-renders in place, and it **sticks**: the override lives in the main process keyed
  by the path, so Reload, facet switches, history navigation, and file-save live-refreshes all keep
  it. Closing the tab forgets it (reopening returns to extension deduction).

Types on a directory or an `http(s)://` page are ignored (directories always list; web pages render
in the web view). `office`/`archive` types are refused for piped input (their parsers need a named
on-disk file).

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
| File watchers, SSH/stream sessions, and the native web view | Electron main process | Privileged OS and Electron APIs stay outside the renderer. (PDFs render **in-renderer** via pdf.js — [ADR-0013](../design-decisions/0013-in-renderer-pdfjs.md) — so they are no longer a main-owned native view.) |

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
entry. After tab/history changes, each renderer window sends its own retained set
to the main process with `window.vv.syncRetainedFiles(paths)`. Main watches the
union across live windows and fans a change out to every window retaining that path.

The retained set controls:

1. Which local files remain watched by `chokidar`.
2. Which Markdown asset watchers remain active.
3. Which DataScript content entities remain cached.

Closing a tab does not necessarily close its current file if that file still
appears in another tab's history—or in another app window. Once a file is
unreachable from every history in every live window, its watcher is closed.
Renderer content is evicted according to that window's own retained set.

Only the active tab owns a rendered document DOM. To keep tab activation fast without
retaining hidden documents, the renderer may keep DOM-free prepared artifacts for the
two most-recent inactive documents, provided each is at most 32 MiB. Content stamps
invalidate these artifacts on live refresh.

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
| `Ctrl+click` / middle-click | Open the entry in a background tab (always a new one). |
| `Ctrl+Shift+click` | Open the entry focused, reusing an existing tab for it. |
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
| Open one or many from shell | `vv <path\|URL> …` (each in its own tab; first focused) |
| Open in development | `npm run start -- <path\|URL> …` |
| Open one or many files in the app | `File > Open` |
| Open local link in active tab | left-click |
| Open local link in a background tab | `Ctrl+click` or middle-click |
| Open local link in a focused tab | `Ctrl+Shift+click` |
| Read diagram source | Open it as a source file |
| Render generated diagrams in Markdown | Embed the generated `.svg` or image file |

---

*Next: [04-keyboard-shortcuts.md](04-keyboard-shortcuts.md).*
