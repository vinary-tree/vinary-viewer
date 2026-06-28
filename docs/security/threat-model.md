# Threat model

This document describes vinary-viewer's security posture: the Electron process model it relies on, the
**actual** hardening in place today, the attack surface of the IPC seam, the filesystem-exposure
implications, the markdown-rendering XSS analysis, and the **recommended hardenings** that are not yet
applied. Every claim is grounded in the source; recommended-but-unimplemented items are tagged
**Forthcoming (planned)**.

> **Scope and trust assumptions.** vinary-viewer is a **local, single-user document previewer**: you run
> it and you point it at files you can already read. It is *not* a sandbox for opening untrusted
> documents from the internet. It **does** load remote web content — but only in a **separate, isolated
> native web view** (a main-owned `WebContentsView` on the `persist:vinary-web` session, never the
> sandboxed app renderer), which hosts the in-app browser, the optional native ad-blocker (ADR-0014), and
> the optional scoped Chrome-extension runtime (ADR-0015). That distinct trust boundary is analyzed in
> **§6.5**. The analysis below is otherwise framed against the intended use, and explicitly flags where
> the current posture would be insufficient for a stricter "open untrusted input" use case.

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
  open: (path) => ipcRenderer.send('vv:open', path),
  syncRetainedFiles: (paths) => ipcRenderer.send('vv:retained-files', paths),
  watchAssets: (docPath, paths) => ipcRenderer.send('vv:watch-assets', { docPath, paths }),
  copyText: (text) => ipcRenderer.send('vv:clipboard-write', text),
  onContent: (cb) => { … return unsubscribe; },
  onGrammars: (cb) => { … return unsubscribe; },
});
```

### What the seam **does** expose

| Capability class | Direction | Semantics |
|------------------|-----------|-----------|
| File content and watchers | renderer → main → renderer | Read retained local paths, watch retained files and embedded local media assets, and send `vv:content`, `vv:error`, and `vv:tree` payloads back. |
| Configuration | both | Request/persist settings and keybindings; request user grammar and filetype registry data. |
| Native views | renderer → main | Show, hide, and position main-owned PDF and HTTP views; relay HTTP heading metadata back to the renderer. |
| Clipboard and shell helpers | renderer → main | Copy explicit text to the OS clipboard, open local paths through the OS, open external URLs, zoom, devtools, and quit. |
| App metadata and dialogs | both | Open the native file dialog and deliver selected paths; deliver About metadata. |

Each `on*` returns an **unsubscribe** function and passes only the message **payload** to the callback
(`(_e, payload) => cb(payload)`) — the raw Electron `IpcRendererEvent` (which carries `sender`,
`ports`, etc.) is **not** handed to the renderer.

The channel catalog is maintained in
[reference/ipc-channels.md](../reference/ipc-channels.md).

### What the seam does **NOT** expose

This is the important half of the analysis. The renderer **cannot**:

- **touch the filesystem directly** — there is no `readFile`/`writeFile`/`readdir` on `window.vv`; the
  only file capability is "ask main to read a path you name."
- **run a shell or spawn a process** — no `exec`/`spawn` is exposed (the `git` calls live in main and
  are not parameterized by the renderer beyond the open file's directory).
- **reach raw `ipcRenderer`** — `ipcRenderer` is used **inside** the preload but is **not** placed on
  `window`. The renderer cannot `send`/`invoke` arbitrary channels; it is limited to the documented
  `window.vv` methods. (`contextIsolation:true` is what makes this confinement real — without it, the
  page could reach the preload's scope.)
- **access Node globals** — `require`, `process`, `Buffer`, `__dirname` are all absent in the page
  (`nodeIntegration:false`).

**Consequence:** the seam is a **mediator** with a fixed, small vocabulary. An attacker who fully
controls the renderer's JavaScript can, at worst, ask the main process to **read and watch arbitrary
paths the user can read**, write explicit text to the clipboard, control native preview surfaces, and
request the documented shell helpers — they cannot escalate to arbitrary file writes, running commands,
or arbitrary IPC. (Whether "read any path" is itself a concern is the subject of §4.)

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
unified → remark-parse → remark-gfm → remark-math → remark-rehype → rehype-slug
→ rehype-highlight → rehype-stringify → MathJax SVG postprocess → Mermaid SVG postprocess
→ tree-sitter fenced-code postprocess
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
- Math expressions are parsed by `remark-math` and converted to SVG by MathJax. The TeX source is
  local document text; MathJax conversion is renderer-local and does not add privileged IPC.
- Mermaid fenced blocks are converted to SVG by Mermaid's browser renderer with `securityLevel:
  "strict"`. Direct `.mmd` and `.mermaid` files use the same renderer-side Mermaid helper. Mermaid
  output is treated as generated SVG markup and inserted into the preview body.

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
- **Math and Mermaid.** These renderers convert local document text into generated SVG. MathJax and
  Mermaid failures are displayed as escaped inline error blocks. Mermaid rendering uses the library's
  strict security mode and does not add a main-process rendering channel.

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

## 6.5 The web view, ad-blocking, and extensions trust boundary

The in-app web view (`vinary.main.web`), the native ad-blocker (`vinary.main.adblock`, ADR-0014), and the
scoped Chrome-extension runtime (`vinary.main.extensions`, ADR-0015) introduce a **second, distinct trust
boundary** — separate from the sandboxed app renderer analyzed above. This section makes its surface
explicit.

### Isolation

- **A separate session.** The web view, its popup host, the ad-blocker, and any extensions all live on the
  **`persist:vinary-web`** partition — a persistent, dedicated `Session` (cookies/logins for documentation
  sites survive restarts) that is **isolated from the app renderer's session** and from Node. The web view
  and popup views use `contextIsolation: true`, `nodeIntegration: false`. **Extensions cannot reach
  `window.vv`** (the app-renderer seam of ADR-0009 is unchanged and is not exposed to this session) or any
  Node/OS API.
- **Remote content stays in the native view.** Remote pages are *never* loaded into the first-party app
  renderer; the analysis of §1–§5 (which assumes the renderer loads only the local bundle) is unaffected.
- **Local HTML runs as a live page in the web session.** Opening a local `.html`/`.htm`/`.xhtml` file
  renders it in the same isolated web view via its `file://` URL (it **executes scripts**) rather than as
  escaped source. This is the **same isolation/session** as remote pages (`persist:vinary-web`,
  `contextIsolation: true`, `nodeIntegration: false`, no `window.vv`), and the ad-blocker + extensions
  apply. So an untrusted local `.html` carries the trust posture of *remote* content (its scripts run, in
  the sandboxed web view), not that of a trusted first-party document.
- **Overlay snapshots are inert rasters.** Because the native view always paints *above* the DOM, ANY DOM
  overlay (menu, context menu, or a modal dialog) is shown over the page by displaying a `capturePage`
  **still image** in the first-party renderer while the native view hides, then restoring the live view on
  close. The image is captured proactively (after load + on scroll-settle) and pushed to the renderer
  (`vv:http-snapshot-ready`) so the swap is instant. It is a flat JPEG — **no script, DOM, or
  interactivity** — carrying only pixels the user was already viewing, so it does **not** execute or re-host
  remote content in the renderer and does not extend this trust boundary.

### New attack surface (extensions) — and its mitigations

Extensions, once installed, **execute code within the web view** (content scripts read page content; the
background service worker + popups can make network requests on `persist:vinary-web`). This is a genuine
expansion of attack surface, mitigated by:

- **Web-Store-only provenance.** `installChromeWebStore` is configured with `allowUnpackedExtensions:
  false`; `beforeInstall` **denies** in-page "Add to Chrome", so the *only* install path is the user's
  explicit **Settings ▸ Extensions** action, where the pasted input is parsed to a strict 32-char
  (`[a-p]{32}`) Web Store id (`ext-util/parse-store-id`).
- **Unofficial update endpoint, called out.** Auto-update (`electron-chrome-web-store`, startup + ~5h)
  polls Google's update server — an **unofficial** channel for a non-Chrome client — and verifies CRX3
  signatures. Users who do not want background updates can disable the extension runtime.
- **Popup lock-down.** The popup `WebContentsView` runs with `nodeIntegration: false`; `will-navigate` is
  constrained to the extension's own `chrome-extension://<id>/` origin, and `setWindowOpenHandler` denies
  new windows (external `http(s)` links open in the OS browser).
- **Untrusted manifest data is validated.** A hostile/malformed manifest cannot inject via the toolbar:
  action icons are **magic-byte-sniffed** (PNG/JPEG/WebP) and **size-capped (< 2 MB)** before being
  rendered strictly as `<img src="data:<sniffed-mime>;base64,…">` in the renderer (`extensions/icon-data-url`).

### How the native ad-blocker *reduces* risk

The MPL ad-blocker (ADR-0014) drops malvertising/tracker requests at `webRequest` **before the page sees
them**, shrinking the remote-content attack surface for the web view.

### Documented non-support (honest limits)

Native-messaging password managers (1Password, KeePassXC) are **not** provided by Electron and are out of
scope. For the APIs Electron lacks (`chrome.windows`/`webNavigation`/`cookies`/`notifications`/`contextMenus`/
`privacy`), a **self-contained chrome.\* polyfill preload** (registered for both the frame and service-worker
types) injects **inert, correctly-shaped stubs** into the extension's main world — its pages **and** its
background worker — so an extension that reads them at startup (e.g. LastPass → `chrome.windows.onFocusChanged`)
registers and its popup loads. The stubs **add no capability** (they fail closed: events never fire, getters
resolve empty), so they do **not** expand the extension's reach beyond the network access it already had on
`persist:vinary-web`. `offscreen`/`nativeMessaging`/`sidePanel` remain absent entirely.

---

## 7. Summary of the posture

| Property                                   | Today                                    |
|--------------------------------------------|------------------------------------------|
| Renderer ↔ OS isolation                    | **Strong** — `contextIsolation:true`, `nodeIntegration:false`, Node modules stubbed in the build. |
| Cross-process surface                      | **Mediated** — documented `window.vv` operations; no raw `ipcRenderer`, fs, arbitrary file writes, or shell. |
| Filesystem reads                           | Any path the user can read (CLI-viewer trust); `git` via `execFileSync` (no shell).               |
| Markdown XSS via raw HTML                   | **Not passed through** — `rehype-raw` is not enabled; plain-text/source fallback kinds are HTML-escaped. |
| Renderer sandbox (`sandbox:true`)          | **Off** — Forthcoming (gated on preload migration).                                               |
| CSP                                        | **None yet** — Forthcoming. A future renderer CSP must allow `'unsafe-eval'`: pdf.js JIT-compiles a PDF's PostScript/Type-4 shading functions via `eval` (`isEvalSupported true`), and the pdf.js ESM module loads through `new Function` (ADR-0013). PDFs follow the local-document trust model. |
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
