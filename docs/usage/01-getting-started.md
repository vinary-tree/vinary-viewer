# Getting started

This page takes you from a clean checkout to a live document preview in a few minutes. It is
**examples-first**: every step shows the *exact* command and *what you will see*. By the end you will
have opened a Markdown file, watched it live-refresh as you edit, opened a second file in a tab,
switched themes, used in-page find, navigated history, and used the scroll-spy table of contents.

> **What vinary-viewer is.** A reactive desktop previewer for Markdown (and images and plain text)
> built in ClojureScript with [re-frame](https://day8.github.io/re-frame/) and
> [Electron](https://www.electronjs.org/). You point it at a file; it renders the file and watches it,
> so edits in your editor appear instantly without a manual refresh. It is a new application *inspired
> by* [vmd](https://github.com/yoshuawuyts/vmd) — it shares no runtime code with vmd.

---

## 1. Prerequisites

| Tool      | Why it is needed                                                          | Check                |
|-----------|---------------------------------------------------------------------------|----------------------|
| **Node.js** (with `npm`) | Installs the JavaScript dependencies (unified/remark, chokidar, Electron) and runs the app. | `node --version` |
| **JDK** (Java 11+; Java 26 supported) | `shadow-cljs` is a JVM program; it compiles ClojureScript → JavaScript. | `java -version` |
| **git**   | The sidebar file-tree is built from `git ls-files` of the repo containing the open file. | `git --version` |

> **Why a JDK?** The ClojureScript compiler `shadow-cljs` runs on the JVM. You do **not** write any
> Java; the JDK is purely the compiler's runtime. The project's `shadow-cljs.edn` already passes
> `--sun-misc-unsafe-memory-access=allow` so it builds cleanly on Java 26.

---

## 2. Clone and install

```bash
git clone https://github.com/vinary-tree/vinary-viewer.git
cd vinary-viewer
npm install
```

`npm install` reads [`package.json`](../../package.json) and downloads the dependencies, notably:

- the **unified/remark/rehype** Markdown stack (`unified`, `remark-parse`, `remark-gfm`,
  `remark-rehype`, `rehype-slug`, `rehype-highlight`, `rehype-stringify`),
- **`chokidar`** (the file watcher behind live-refresh),
- **`react`** / **`react-dom`** (reagent renders through React 19),
- and the dev tools **`electron`** and **`shadow-cljs`**.

You will see a `node_modules/` directory appear. No global installs are required.

---

## 3. Run it (development)

```bash
npm run dev
```

This single script (defined in `package.json`) does two things in sequence:

```text
npm run dev  ==  shadow-cljs compile main renderer   &&   electron .
                 └──────────── compile ────────────┘        └─ launch ─┘
```

1. **`shadow-cljs compile main renderer`** compiles the two ClojureScript builds declared in
   [`shadow-cljs.edn`](../../shadow-cljs.edn):
   - **`main`** → `dist/main/main.js` (the Electron *main* process: window, file IO, watchers);
   - **`renderer`** → `resources/public/js/main.js` (the Electron *renderer*: the re-frame UI).
2. **`electron .`** launches Electron, which reads `"main": "dist/main/main.js"` from `package.json`,
   runs the main process, and opens the renderer window from `resources/public/index.html`.

**What you will see:** a 1280×860 window with a dark (`#292b2e`) background and an empty content area
showing the **Vinary Tree watermark** (a faint heraldic-shield emblem) — because no file is open yet.

> First compile is the slow one (the JVM warms up and the renderer bundle is built). Subsequent
> `npm run dev` runs are faster; for an even tighter edit loop use `npm run watch` (see
> [02-installation-and-build.md](02-installation-and-build.md)).

---

## 4. Open a file from the command line

The main process inspects `process.argv` and opens the **first non-flag argument** as a document
(`vinary.main.core/initial-file`). Today you invoke that through Electron:

```bash
electron . README.md
```

**What you will see:** the window opens with `README.md` already rendered. The first heading appears at
the top, a **tab** named `README.md` appears in the tab strip, the git file-tree of the repository
appears on the left, and (if the document has headings) a **Contents** panel appears on the right.

> **Forthcoming (planned): the `vv` launcher.** A thin `vv` / `vinary-viewer` command-line wrapper is
> planned so you can simply type `vv README.md` from anywhere. It is **not built yet**; until it lands,
> use `electron . <file>` from the repo root (or `npm run dev` to open the empty window and then open a
> file from the sidebar). See [02-installation-and-build.md](02-installation-and-build.md).

### What "open" does, end to end

The figure below traces the full round trip from your command to a painted preview. The renderer never
touches the filesystem; it asks the main process over the **`window.vv`** IPC seam, the main process
reads and watches the file, and the rendered HTML flows back through re-frame into the document body.

![Open-a-file sequence](../diagrams/seq-open-file.svg)

*Figure — source: [`docs/diagrams/seq-open-file.puml`](../diagrams/seq-open-file.puml)*

In words (the numbered phases match the diagram):

1. **CLI → main.** `main.core/initial-file` extracts `README.md` from `argv`; after the window's
   `did-finish-load`, `service/open!` runs.
2. **main reads + sends.** `service/send-content!` reads the file (UTF-8) and sends
   `vv:content {:path … :kind "markdown" :text …}` to the renderer over `webContents.send`; it also
   sends `vv:tree {:root … :files …}` from `git ls-files`.
3. **seam → re-frame.** The preload's `window.vv.onContent` callback dispatches `[:content/received …]`.
4. **render.** The `:content/received` event transacts the doc into DataScript and fires the
   `:markdown/render` effect; the unified pipeline returns `Promise<string>` and the HTML comes back on
   `[:content/rendered …]`, which transacts `:doc/html`.
5. **paint.** The `:doc/active` subscription updates; the `markdown-body` view writes the HTML into the
   content node's `innerHTML`. You see the rendered document.
6. **watch.** `service/open!` starts one `chokidar` watcher on the file so future edits re-send.

---

## 5. A guided walkthrough

Do these in order; each step is a feature you will use constantly.

### 5.1 Live-refresh — edit and watch it update

With `README.md` open, edit the file in any editor and save it.

```bash
# In another terminal, append a line (or just edit + save in your editor):
printf '\n## Live edit demo\n\nHello from a live edit.\n' >> README.md
```

**What you will see:** within a moment the preview updates to include the new `## Live edit demo`
heading and paragraph — **no refresh, no reload**. Your scroll position and the active tab are
preserved (content updates flow into DataScript; UI state lives separately, so live-refresh never
disturbs where you are).

> **How it works (one line):** the main process watches the file with `chokidar`
> (`awaitWriteFinish`), and on `change` re-reads it and re-sends `vv:content`; the renderer re-renders.
> Full detail in [features/01-live-refresh.md](../features/01-live-refresh.md).

### 5.2 A second file — tabs

Open another Markdown or text file. From a terminal:

```bash
electron . docs/usage/04-keyboard-shortcuts.md
```

…or, more naturally, **click a file in the left git tree** (every tracked file in the repo is listed).

**What you will see:** a *second tab* appears in the tab strip, and it becomes active. Click a tab to
switch to it; click the **×** on a tab to close it. Closing the active tab re-selects the most recently
ordered remaining tab. Each tab is one open document (keyed by its path); switching tabs is instant
because every open document's rendered HTML already lives in DataScript.

### 5.3 Switch theme

In the toolbar (top-right), use the **theme `<select>`**. Choose **Spacemacs Light**.

**What you will see:** the entire UI — backgrounds, headings, code highlighting, the watermark tint —
recolors instantly to the light palette. Switch back to **Spacemacs Dark** (the default) the same way.

> The switch swaps the `href` of a single `<link id="vv-theme-link">` to the chosen theme's CSS file;
> the structural stylesheet references only `var(--vv-*)` tokens, so changing the palette restyles
> everything at once. See [05-configuration.md](05-configuration.md).

### 5.4 Find on the page — `Ctrl+F`

Press **`Ctrl+F`**. A small find box appears at the top-right of the content.

- Type a word that appears in the document. **What you will see:** every match is highlighted; the
  current match is highlighted in a brighter color and scrolled to the center of the view. The counter
  shows `current/total` (e.g. `1/7`).
- Press **`Enter`** to go to the next match, **`Shift+Enter`** for the previous (both wrap around).
- Press **`Esc`** (or the **×** button) to close find and clear the highlights.

> Matches are painted with the browser's **CSS Custom Highlight API**, which colors text ranges without
> mutating the document DOM — so find composes cleanly with the live-rendered body. See
> [features/05-in-page-find.md](../features/05-in-page-find.md).

### 5.5 Navigate history — `Alt+←` / `Alt+→`

Open a few different files (via the tree or tabs) so you build up a history. Then:

- Press **`Alt+←`** (or click the toolbar **←** button) to go **back** to the previously viewed
  document.
- Press **`Alt+→`** (or the toolbar **→** button) to go **forward** again.

**What you will see:** the active document changes to the previous/next entry in your viewing history.
The buttons are disabled (greyed) when there is nowhere to go. Opening a *new* document after going
back truncates the forward branch — exactly like a web browser's history.

### 5.6 Scroll-spy table of contents

Open a Markdown file with several headings (this very file works). A **Contents** panel appears on the
right.

**What you will see:** as you scroll the document, the heading currently at the top of the viewport is
highlighted in the Contents panel (a scroll-spy). Click any entry to smooth-scroll the document to that
heading. The list is derived from the rendered HTML's heading ids (added by `rehype-slug`).

---

## 6. Where to go next

| If you want to…                                  | Read                                                                 |
|--------------------------------------------------|----------------------------------------------------------------------|
| Understand the build modes (watch, release)      | [02-installation-and-build.md](02-installation-and-build.md)         |
| Learn every way to open files and manage tabs    | [03-opening-files-and-tabs.md](03-opening-files-and-tabs.md)         |
| See all keyboard shortcuts                        | [04-keyboard-shortcuts.md](04-keyboard-shortcuts.md)                 |
| Configure themes (and the planned config dir)     | [05-configuration.md](05-configuration.md)                           |
| Fix a problem                                     | [06-troubleshooting.md](06-troubleshooting.md)                       |
| Understand the architecture                       | [`docs/architecture/01-overview.md`](../architecture/01-overview.md) |

---

### Quick reference — what each step ran

| Goal                | Command / action                                  | Result                                            |
|---------------------|---------------------------------------------------|---------------------------------------------------|
| Install deps        | `npm install`                                     | `node_modules/` populated                         |
| Build + launch      | `npm run dev`                                      | window opens (empty → watermark)                  |
| Open a file         | `electron . README.md`                            | file rendered, tab + tree + TOC appear            |
| Live-refresh        | edit + save the open file                         | preview updates in place                          |
| New tab             | click a file in the tree (or `electron . <file>`) | second tab opens and activates                    |
| Switch theme        | toolbar theme `<select>`                          | UI recolors instantly                             |
| Find                | `Ctrl+F`, type, `Enter` / `Shift+Enter`, `Esc`    | matches highlighted; cycle; close                 |
| History             | `Alt+←` / `Alt+→`                                  | back / forward through viewed documents           |
