# 03 · IPC Protocol

> **Scope.** The authoritative specification of the **Inter-Process Communication** seam between the
> Electron **main** and **renderer** processes: the channel catalog, the message envelope and its
> rationale, the `on*` → unsubscribe contract, the lifecycle, the error path, the security seam, and
> what is *intentionally not* exposed. The same data is repeated as a flat lookup in
> [reference/ipc-channels.md](../reference/ipc-channels.md).

---

## 1. The seam in one picture

All cross-process traffic flows through **one** object — `window.vv` — exposed by the preload script
(`resources/preload.js`) via Electron's `contextBridge`. The renderer never imports `electron`,
`ipcRenderer`, or `fs`; main never reaches into the DOM. The preload is the **Mediator**: it
collapses what would otherwise be point-to-point IPC sprawl into a single audited surface.

```text
   RENDERER (sandbox-leaning, no Node)            preload (Node priv.)              MAIN (Node)
   ─────────────────────────────────              ──────────────────               ───────────
   window.vv.open(path)        ──send──▶  ipcRenderer.send('vv:open')          ▶  ipcMain.on('vv:open')  → service/open!
   window.vv.close(path)       ──send──▶  ipcRenderer.send('vv:close')         ▶  ipcMain.on('vv:close') → service/close!
   window.vv.requestKeymap()   ──send──▶  ipcRenderer.send('vv:keymap-request')▶  (no main handler yet)

   window.vv.onContent(cb)    ◀─recv───   ipcRenderer.on('vv:content')        ◀  webContents.send('vv:content')
   window.vv.onError(cb)      ◀─recv───   ipcRenderer.on('vv:error')          ◀  webContents.send('vv:error')
   window.vv.onTree(cb)       ◀─recv───   ipcRenderer.on('vv:tree')           ◀  webContents.send('vv:tree')
   window.vv.onKeymap(cb)     ◀─recv───   ipcRenderer.on('vv:keymap')         ◀  (no main sender yet)
```

See also the open-file round trip in [`seq-open-file`](../diagrams/seq-open-file.puml).

---

## 2. Authoritative channel catalog

Seven channels, two directions. Channel names are literal strings (the `vv:` prefix namespaces them).
The two **keymap** channels are *defined-but-dormant* — the renderer is wired but main has no handler
yet (see [§2.2](#22-the-dormant-keymap-channels)).

| Channel | Direction | Payload (JSON) | Sender API | Receiver API | Trigger | Status |
| --- | --- | --- | --- | --- | --- | --- |
| `vv:open` | renderer → main | `path` *(string)* | `window.vv.open(path)` → `ipcRenderer.send` | `ipcMain.on("vv:open", …)` → `service/open!` | `:vv/open` fx (from `:doc/open`, i.e. clicking a file in the tree) | live |
| `vv:close` | renderer → main | `path` *(string)* | `window.vv.close(path)` → `ipcRenderer.send` | `ipcMain.on("vv:close", …)` → `service/close!` | `:vv/close` fx (from `:tab/close`) | live |
| `vv:keymap-request` | renderer → main | *(none)* | `window.vv.requestKeymap()` → `ipcRenderer.send` | *(no main handler yet)* | `bridge!` after subscribing `onKeymap` | **pending** |
| `vv:content` | main → renderer | `{:path :kind (:text)}` | `webContents.send("vv:content", …)` | `window.vv.onContent(cb)` → `[:content/received]` | initial `open!`; every chokidar `change`/`add` | live |
| `vv:error` | main → renderer | `{:path :message}` | `webContents.send("vv:error", …)` | `window.vv.onError(cb)` → `[:content/error]` | `readFileSync` throws in `send-content!` | live |
| `vv:tree` | main → renderer | `{:root :files}` | `webContents.send("vv:tree", …)` | `window.vv.onTree(cb)` → `[:tree/received]` | `open!` when the file is inside a git repo | live |
| `vv:keymap` | main → renderer | user keymap config *(map)* | `webContents.send("vv:keymap", …)` | `window.vv.onKeymap(cb)` → `[:keymap/config-received]` | (main pushes `keybindings.edn`) | **pending** |

### 2.2 The dormant keymap channels

`vv:keymap-request` and `vv:keymap` carry the user's custom-keybinding configuration
(`~/.config/vinary-viewer/keybindings.edn`) from main to the renderer's keymap layer. The **renderer
side is wired** — `renderer.core/bridge!` subscribes via `window.vv.onKeymap` (guarded on its
existence) and `requestKeymap`s to pull any config main may have already loaded — but **main does not
yet implement the request handler or send the config**. Until it does, the renderer resolves keys
against the **bundled** `:default` preset (or a preset selected at runtime via
`window.__vvkeymap("vim")`). This is documented as pending so the channels are not mistaken for live
behaviour; see [reference/events-effects-subs.md §4](../reference/events-effects-subs.md#4-the-input--command-layer-available--in-progress).

### 2.1 Payload shapes (exact)

```clojure
;; vv:content  — kind ∈ {"markdown" "image" "text"} from service/kind-of
{:path "/abs/path/README.md"
 :kind "markdown"
 :text "# Hello\n…"}        ; PRESENT for "markdown"/"text"; ABSENT for "image"

;; images carry NO :text — the renderer displays them by file:// path:
{:path "/abs/path/logo.png" :kind "image"}

;; vv:error
{:path "/abs/path/missing.md" :message "ENOENT: no such file or directory, open '…'"}

;; vv:tree  — root is the repo top-level; files are repo-relative paths from `git ls-files`
{:root  "/abs/repo"
 :files ["README.md" "docs/intro.md" "src/core.cljs" …]}
```

> **`kind` classification** (`service/kind-of`, case-insensitive on the extension):
> `*.md|*.markdown|*.mdx` → `"markdown"`; `*.png|*.jpe?g|*.gif|*.svg|*.webp|*.bmp|*.ico|*.avif` →
> `"image"`; everything else → `"text"`.

---

## 3. The envelope: why plain JSON

Messages are **plain JSON objects**, not Clojure data, because `contextBridge` and Electron IPC can
only transfer **structured-clonable** values across the V8 context boundary — ClojureScript
persistent data structures do not survive that hop. The conversion is explicit and symmetric:

| Direction | Conversion site | Call |
| --- | --- | --- |
| main → renderer (send) | `vinary.main.service` | `(clj->js {…})` before `webContents.send` |
| main → renderer (receive) | `vinary.renderer.core/bridge!` | `(js->clj payload :keywordize-keys true)` before dispatch |
| renderer → main (send) | `vinary.app.fx` (`:vv/open`/`:vv/close`) | a bare string `path` (no map) |
| renderer → main (receive) | `vinary.main.service/init!` | the handler reads `path` directly |

Keywordizing on receipt (`:keywordize-keys true`) is what turns `{"path": …, "kind": …}` into
`{:path … :kind …}` so the rest of the renderer can pattern-match on Clojure keywords.

**Rationale for plain JSON + a tiny vocabulary:** keeping the wire format to flat,
already-clonable maps means (a) no serialization library is needed, (b) the seam is trivially
auditable (a handful of channels, a few flat payload shapes), and (c) the renderer can stay a
sandbox-leaning page with no Node deserialization code.

---

## 4. The `on*` → unsubscribe contract

Each `window.vv.on*(cb)` **returns an unsubscribe function**. This is the discipline that prevents
listener leaks if the bridge is ever re-wired (e.g. a future multi-window setup or a hot reload that
re-runs `bridge!`).

```js
// resources/preload.js
onContent: (cb) => {
  const h = (_e, payload) => cb(payload);
  ipcRenderer.on('vv:content', h);
  return () => ipcRenderer.removeListener('vv:content', h);   // ◀ unsubscribe
},
// onError, onTree follow the identical pattern.
```

The first IPC argument (the Electron `event`) is **dropped** (`_e`); only the `payload` reaches the
renderer callback, so the renderer never sees Electron internals.

> **Current usage.** `vinary.renderer.core/bridge!` calls `onContent`/`onError`/`onTree` once at
> startup and does not currently retain the returned unsubscribers (the renderer lives for the
> window's lifetime). The contract exists so that retaining + calling them is a one-line change when
> a lifecycle that needs teardown is introduced.

---

## 5. Lifecycle

```text
== boot ==
main.core/main → service/init!   registers ipcMain handlers for vv:open / vv:close
main.core/create-window!         BrowserWindow(preload) → loadFile(index.html)
renderer init                    bridge! wires window.vv.on* → re-frame dispatch
did-finish-load (main)           service/open! wc initial-file   (if any file on argv)

== steady state ==
renderer  ──vv:open / vv:close──▶  main   (open a tree file / close a tab)
main      ──vv:content / vv:error / vv:tree──▶  renderer   (initial + every file change)

== teardown ==
window 'closed' → main resets main-window atom
app 'window-all-closed' → quit (except macOS)
:tab/close → :vv/close → service/close! stops that path's watcher
```

The **`did-finish-load`** timing matters: main waits until the renderer page has finished loading
(so `window.vv.onContent` is wired) before pushing the initial file's content. Otherwise the first
`vv:content` could arrive before any listener exists.

---

## 6. The error path

`send-content!` reads the file inside a `try`/`catch`. On failure it emits **`vv:error`** instead of
`vv:content`:

```clojure
;; vinary.main.service/send-content!  (text/markdown branch)
(try
  (let [text (.readFileSync fs path "utf8")]
    (.send wc "vv:content" (clj->js {:path path :kind kind :text text})))
  (catch :default e
    (.send wc "vv:error" (clj->js {:path path :message (.-message e)}))))
```

On the renderer side, `vv:error` → `[:content/error {:path :message}]` → transact `:doc/error` into
that doc's DataScript entity → `content-view` renders the `.vv-error` div. The **render** side has a
symmetric error: if the unified pipeline rejects, the `:markdown/render` fx's `.catch` dispatches
`[:content/error {… :message "render error: …"}]`. Both kinds of error converge on the same
`:doc/error` attribute and the same red error view.

**Error retraction.** Because a later successful `vv:content` for the same path **omits** `:doc/error`
and explicitly `[:db/retract …]`s any stale error (DataScript rejects `nil` values), fixing the file
and saving it clears the error automatically. See
[05 · Data Flows §9](./05-data-flows.md#9-error-arrival--retraction).

---

## 7. Security seam

The seam is the app's primary trust boundary. The actual posture today:

| Control | Setting | Where |
| --- | --- | --- |
| Context isolation | `contextIsolation: true` | `create-window!` `webPreferences` |
| Node integration | `nodeIntegration: false` | `create-window!` `webPreferences` |
| Preload | `resources/preload.js` | `create-window!` `webPreferences` |
| Exposed API | minimal JSON-only `window.vv` (`open`, `close`, `requestKeymap`, `onContent`, `onError`, `onTree`, `onKeymap`) | `contextBridge.exposeInMainWorld` |
| Menu | `autoHideMenuBar: true` | `create-window!` |
| Raw HTML in Markdown | **not passed through** (no `rehype-raw`) | `vinary.renderer.markdown` |

The preload runs in an **isolated world** with Node access but exposes **only** `open`, `close`,
`requestKeymap`, `onContent`, `onError`, `onTree`, `onKeymap`. The renderer therefore cannot read
arbitrary files, spawn processes, or touch `ipcRenderer` directly — it can only ask main to
open/close a path (or request its keymap config) and listen for pushed content.

> **Honest gaps (recommended hardenings, not yet applied).** The renderer is **not** `sandbox: true`
> today, and the preload uses CommonJS `require('electron')` (which a full `sandbox: true` would
> restrict to a preload-safe subset). There is **no Content-Security-Policy** meta/header yet. These
> are tracked as Forthcoming hardenings — adding `sandbox: true` and a strict CSP would tighten the
> boundary further. See [security/threat-model.md](../security/threat-model.md).

---

## 8. What is intentionally NOT exposed

| Not exposed | Why |
| --- | --- |
| `fs` / arbitrary file reads | The renderer asks main to open a *specific* path; it cannot enumerate or read the disk. (`fs`/`path`/`url` are even build-stubbed to `false`.) |
| `ipcRenderer` directly | Would let any renderer code send arbitrary channels; the preload exposes only the seven vetted ones. |
| `child_process` / shell | git is invoked **only** in main (`repo-tree`), never reachable from the renderer. |
| Writing files | vinary-viewer is read-only with respect to user content (see [01 §2](./01-overview.md#2-system-context)). |
| Node `require` in the page | `nodeIntegration: false` + isolated context. |

---

## 9. See also

- [reference/ipc-channels.md](../reference/ipc-channels.md) — the same catalog as a flat lookup.
- [05 · Data Flows](./05-data-flows.md) — each channel in the context of a full user action.
- [reference/namespaces.md](../reference/namespaces.md) — `main.service`, `renderer.core`, `app.fx`.
- [security/threat-model.md](../security/threat-model.md) — the threat analysis behind the seam.
