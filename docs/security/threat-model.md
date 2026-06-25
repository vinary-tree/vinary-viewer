# Threat model

This document describes vinary-viewer's security posture: the Electron process model it relies on, the
**actual** hardening in place today, the attack surface of the IPC seam, the filesystem-exposure
implications, the markdown-rendering XSS analysis, and the **recommended hardenings** that are not yet
applied. Every claim is grounded in the source; recommended-but-unimplemented items are tagged
**Forthcoming (planned)**.

> **Scope and trust assumptions.** vinary-viewer is a **local, single-user document previewer**: you run
> it and you point it at files you can already read. It is *not* a sandbox for opening untrusted
> documents from the internet, and it does not load remote web content. The analysis below is framed
> against that intended use, and explicitly flags where the current posture would be insufficient for a
> stricter "open untrusted input" use case.

---

## 1. The Electron process model

Electron applications run as two kinds of process, with very different privileges. Defining them
up front:

- **Main process** — a **Node.js** process. It is **trusted**: it has full filesystem, child-process,
  and OS access. In vinary-viewer it is `vinary.main.core` (window + lifecycle) and
  `vinary.main.service` (file reads, `chokidar` watchers, `git`). Output: `dist/main/main.js`.
- **Renderer process** — a **Chromium** (web content) process. It runs the UI as a web page. It should
  be treated as **untrusted relative to the OS**, because it executes the kind of code (HTML/JS, and any
  content rendered from a document) that is most exposed. In vinary-viewer it is
  `vinary.renderer.core` + the reagent/re-frame UI. Output: `resources/public/js/main.js`, loaded by
  `resources/public/index.html`.
- **Preload script** — a small script that runs in the renderer's process **before** the web page, in an
  **isolated** context that still has Node access. It is the **only** bridge between the two worlds. In
  vinary-viewer it is `resources/preload.js`, which exposes `window.vv`.

The deployment diagram makes the boundary explicit:

![Electron process deployment with trust boundary](../diagrams/deploy-electron-processes.svg)

*Figure — source: [`docs/diagrams/deploy-electron-processes.puml`](../diagrams/deploy-electron-processes.puml)*

The **trust boundary** (drawn dashed in the figure) sits around the renderer: everything inside it is
web content with **no direct OS access**; everything crossing it must pass through the amber IPC seam.

---

## 2. Actual posture today

The window is created with these `webPreferences` (`vinary.main.core/create-window!`):

```clojure
(BrowserWindow.
  (clj->js {:width 1280 :height 860
            :backgroundColor "#292b2e"
            :autoHideMenuBar true
            :webPreferences {:contextIsolation true
                             :nodeIntegration false
                             :preload (preload-path)}}))
```

What this gives us, mapped to the Electron security checklist
([Electron — *Security*](https://www.electronjs.org/docs/latest/tutorial/security)):

| Setting                          | Value today    | Checklist item                                            | Effect                                                                                       |
|----------------------------------|----------------|-----------------------------------------------------------|----------------------------------------------------------------------------------------------|
| `contextIsolation`               | **`true`**     | "3. Enable Context Isolation"                             | The page's JavaScript runs in a **separate context** from the preload; the page cannot reach into the preload's Node-capable scope except through the explicitly exposed `window.vv`. |
| `nodeIntegration`                | **`false`**    | "2. Do not enable Node.js integration for remote content" | The page has **no** `require`, `process`, `Buffer`, etc. Renderer code cannot touch Node/OS APIs directly. |
| `preload`                        | a minimal seam | (enables item 3's safe-exposure pattern)                  | All cross-process access is funneled through one auditable script.                            |
| `autoHideMenuBar`                | `true`         | —                                                         | Cosmetic; the menu is hidden (press `Alt` to reveal). Not a security control.                 |

In addition, the **renderer build stubs Node modules** (`shadow-cljs.edn`:
`:js-options {:resolve {"fs" false "fs/promises" false "path" false "url" false}}`), so even the
*bundler* refuses to give renderer code a filesystem module. This is defense in depth alongside
`nodeIntegration:false`.

**Net posture:** the renderer is web content with no direct OS access; its *only* capability is the
narrow JSON message API described next.

---

## 3. The IPC seam — exposed attack surface

`resources/preload.js` uses `contextBridge.exposeInMainWorld('vv', …)` to expose a **minimal, JSON-only**
API. This is the entire surface the renderer can use to affect the outside world:

```js
contextBridge.exposeInMainWorld('vv', {
  open:  (path) => ipcRenderer.send('vv:open', path),    // renderer → main
  close: (path) => ipcRenderer.send('vv:close', path),   // renderer → main
  onContent: (cb) => { … return unsubscribe; },          // main → renderer
  onError:   (cb) => { … return unsubscribe; },          // main → renderer
  onTree:    (cb) => { … return unsubscribe; },           // main → renderer
});
```

### What the seam **does** expose

| Capability        | Direction        | Semantics                                                                 |
|-------------------|------------------|---------------------------------------------------------------------------|
| `vv.open(path)`   | renderer → main  | "read and watch this path"; main responds with `vv:content` (or `vv:error`) and `vv:tree`. |
| `vv.close(path)`  | renderer → main  | "stop watching this path."                                                |
| `vv.onContent`    | main → renderer  | receive `{:path :kind (:text)}` whenever a file's content is (re-)sent.    |
| `vv.onError`      | main → renderer  | receive `{:path :message}` on a read failure.                             |
| `vv.onTree`       | main → renderer  | receive `{:root :files}` (the git file list).                             |

Each `on*` returns an **unsubscribe** function and passes only the message **payload** to the callback
(`(_e, payload) => cb(payload)`) — the raw Electron `IpcRendererEvent` (which carries `sender`,
`ports`, etc.) is **not** handed to the renderer.

### What the seam does **NOT** expose

This is the important half of the analysis. The renderer **cannot**:

- **touch the filesystem directly** — there is no `readFile`/`writeFile`/`readdir` on `window.vv`; the
  only file capability is "ask main to read a path you name."
- **run a shell or spawn a process** — no `exec`/`spawn` is exposed (the `git` calls live in main and
  are not parameterized by the renderer beyond the open file's directory).
- **reach raw `ipcRenderer`** — `ipcRenderer` is used **inside** the preload but is **not** placed on
  `window`. The renderer cannot `send`/`invoke` arbitrary channels; it is limited to the five named
  operations above. (`contextIsolation:true` is what makes this confinement real — without it, the page
  could reach the preload's scope.)
- **access Node globals** — `require`, `process`, `Buffer`, `__dirname` are all absent in the page
  (`nodeIntegration:false`).

**Consequence:** the seam is a **mediator** with a fixed, small vocabulary. An attacker who fully
controls the renderer's JavaScript can, at worst, ask the main process to **read and watch arbitrary
paths the user can read** and receive their contents back — they cannot escalate to writing files,
running commands, or arbitrary IPC. (Whether "read any path" is itself a concern is the subject of §4.)

> **Design note.** This single-seam shape is a deliberate decision: see
> [design-decisions/0009-mediator-ipc-over-point-to-point.md](../design-decisions/0009-mediator-ipc-over-point-to-point.md).
> One auditable, JSON-only boundary is far easier to reason about than ad-hoc `ipcRenderer` calls
> scattered through the UI.

---

## 4. Filesystem exposure

The main process reads **any path it is given** (`service/send-content!` →
`fs.readFileSync(path, "utf8")`), and runs `git` in the **directory of the open file**
(`service/repo-tree`). It performs no allow-listing or path confinement.

**Why this is acceptable in the intended model:** vinary-viewer is a **CLI-invoked, single-user
viewer**. The paths it reads originate from (a) the command line you typed and (b) files you clicked in
the git tree of your own repository. The process runs with **your** privileges and reads files **you can
already read**; it never elevates. There is no remote input source that could name a path.

**Where this would be a problem:** if the renderer were ever to load **remote or untrusted web content**
that could script `window.vv.open("/etc/passwd")` (or any readable path) and exfiltrate the returned
`vv:content` text. Today the renderer loads only the local, first-party bundle from `index.html`, so
there is no such script. The mitigations in §6 (CSP, disabling navigation, sandbox) exist precisely to
preserve this property if the app grows features that touch the network.

**`git` invocation safety:** the `git` arguments are fixed verbs (`rev-parse --show-toplevel`,
`ls-files`) executed via `child_process.execFileSync` (which does **not** spawn a shell, so there is no
shell-injection vector), with `cwd` set to the open file's directory. The renderer cannot inject
additional `git` arguments; it only influences *which directory* `git` runs in by choosing which file to
open.

---

## 5. Rendered-markdown XSS

Markdown is rendered in the **renderer** by the unified pipeline (`vinary.renderer.markdown/render`) and
written into the document body via `innerHTML` (`vinary.ui.views/set-inner!`). Setting `innerHTML` is the
classic XSS sink, so the question is: **can a malicious Markdown document inject executing script?**

### The pipeline (exact)

```text
unified → remark-parse → remark-gfm → remark-rehype → rehype-slug → rehype-highlight → rehype-stringify
```

The decisive fact: **`rehype-raw` is NOT in the pipeline** (and is not even a dependency in
`package.json`). `remark-rehype` runs with its default behavior, which **discards raw HTML embedded in
the Markdown source** — without `rehype-raw` (or `allowDangerousHtml`), inline/literal HTML nodes are
**not** passed through into the output tree. Concretely:

- A Markdown source containing `<img src=x onerror=alert(1)>` or `<script>…</script>` produces **no**
  corresponding element in the rendered HTML — the raw HTML is dropped, not executed.
- What *is* produced is the structured HTML that remark/rehype generate from Markdown constructs
  (headings, lists, links, code blocks, GFM tables, etc.), plus `rehype-slug` ids and
  `rehype-highlight` `<span class="hljs-…">` wrappers.

So the `innerHTML` write receives HTML derived from a pipeline that **does not forward attacker-authored
raw HTML**. This is the primary reason the `innerHTML` sink is acceptable here.

### Residual considerations (and why they are low-risk today)

- **Links.** Rendered links keep their `href`. A document could contain a `javascript:` link; clicking
  it could execute script in the page. This is a known Markdown-renderer consideration. Today the app
  does not intercept link clicks; users open **local, trusted** documents, so the exposure is the same
  as opening those documents in any Markdown viewer. A future hardening could sanitize/agnostic-ize link
  schemes (see §6).
- **Images.** `![alt](url)` produces `<img src=url>`; with `nodeIntegration:false` and no remote-content
  loading policy yet, an image `src` could trigger a network fetch. Under the strict CSP recommended in
  §6, such fetches would be constrained.
- **Plain-text kind.** Non-markdown files are wrapped as `<pre class="vv-plain">` with the text
  **HTML-escaped** via `goog.string/htmlEscape` (`vinary.app.events/plain-html`), so a `.txt`/source
  file containing `<script>` is shown as inert, escaped text — never parsed as HTML.

> **Precise statement.** *Raw HTML embedded in a Markdown document is not passed through to the rendered
> output, because the pipeline does not enable `rehype-raw`.* That, plus escaping the plain-text kind, is
> what makes the `innerHTML` body safe for the intended local-document use. The residual link/image
> considerations are exactly what the CSP and navigation hardenings in §6 are meant to close before the
> app handles untrusted input.

---

## 6. Recommended hardenings — Forthcoming (planned)

These are **not yet applied**. They are listed against the Electron security checklist so the path to a
stricter posture (e.g. for opening untrusted documents) is explicit.

| Hardening                                              | Checklist item                          | Why / what it buys                                                                                  | Status                |
|--------------------------------------------------------|-----------------------------------------|----------------------------------------------------------------------------------------------------|-----------------------|
| **Enable `sandbox: true`** on the renderer             | "4. Enable process sandboxing"          | Runs the renderer in an OS-level sandbox, reducing the blast radius of a renderer compromise. **Requires migrating the preload off CommonJS `require`** (a sandboxed preload cannot use Node `require`; today `preload.js` does `require('electron')`). | Forthcoming (planned) |
| **Strict `Content-Security-Policy`** (`<meta>` in `index.html`) | "7. Define a Content-Security-Policy" | Constrain script/style/img/connect sources; e.g. forbid inline script, remote connects. Closes the image-fetch and `javascript:`-link residuals from §5. | Forthcoming (planned) |
| **Disable / limit navigation**                          | "13. Disable or limit navigation"       | Prevent the renderer from navigating away from the first-party bundle (e.g. handle `will-navigate`/`window.open`), so a crafted link cannot turn the window into a browser for remote content. | Forthcoming (planned) |

Additional, lower-priority items consistent with the checklist (also Forthcoming):

- **Link-scheme sanitization** for rendered Markdown (drop/neutralize `javascript:` hrefs).
- If untrusted-document support is ever added, consider an **HTML sanitizer** stage (e.g.
  `rehype-sanitize`) — though note that *not* enabling `rehype-raw` already removes the main raw-HTML
  vector for Markdown input.

> **Preload migration caveat.** Turning on `sandbox:true` is the single highest-value hardening, but it
> is gated on rewriting `resources/preload.js` so it does not call CommonJS `require('electron')` at
> module top level in a way the sandbox forbids. This is why it is sequenced as a deliberate, planned
> change rather than a one-line flag flip.

---

## 7. Summary of the posture

| Property                                   | Today                                    |
|--------------------------------------------|------------------------------------------|
| Renderer ↔ OS isolation                    | **Strong** — `contextIsolation:true`, `nodeIntegration:false`, Node modules stubbed in the build. |
| Cross-process surface                      | **Minimal** — five named, JSON-only `window.vv` operations; no raw `ipcRenderer`, fs, or shell.   |
| Filesystem reads                           | Any path the user can read (CLI-viewer trust); `git` via `execFileSync` (no shell).               |
| Markdown XSS via raw HTML                   | **Not passed through** — `rehype-raw` is not enabled; plain-text kind is HTML-escaped.            |
| Renderer sandbox (`sandbox:true`)          | **Off** — Forthcoming (gated on preload migration).                                               |
| CSP                                        | **None yet** — Forthcoming.                                                                       |
| Navigation lock-down                       | **None yet** — Forthcoming.                                                                       |

The current design is appropriate for its intended **local, single-user, trusted-document** use, and it
already follows the most important Electron isolation recommendations. The Forthcoming items in §6 are
the roadmap to a posture that would also be safe for **untrusted** input.

---

*See also: [design-decisions/0002-render-markdown-in-renderer.md](../design-decisions/0002-render-markdown-in-renderer.md),
[design-decisions/0003-ref-innerHTML-no-vdom-body.md](../design-decisions/0003-ref-innerHTML-no-vdom-body.md), and
[design-decisions/0009-mediator-ipc-over-point-to-point.md](../design-decisions/0009-mediator-ipc-over-point-to-point.md).*

---

### References

- Electron, *Security* (security checklist & recommendations) —
  <https://www.electronjs.org/docs/latest/tutorial/security> (no DOI).
- W3C, *CSS Custom Highlight API Module Level 1* (relevant to the find feature's no-DOM-mutation
  highlighting) — <https://www.w3.org/TR/css-highlight-api-1/> (no DOI).
