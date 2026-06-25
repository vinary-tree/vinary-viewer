# Reference · IPC Channels

> **What this is.** A flat, lookup-form catalog of every Electron IPC channel between the **main** and
> **renderer** processes. The architectural narrative (envelope rationale, the `on*` contract,
> security seam) is in [architecture/03-ipc-protocol.md](../architecture/03-ipc-protocol.md); this page
> is the same data optimised for "what is channel X?". All traffic crosses the preload `contextBridge`
> object `window.vv` (`resources/preload.js`).

---

## 1. At a glance

```text
   renderer → main                         main → renderer
   ───────────────                         ───────────────
   vv:open            (open / re-open)      vv:content   (content, live)
   vv:close           (stop watching)       vv:error     (read error)
   vv:keymap-request  (pull user keymap)    vv:tree      (git file-tree)
                                            vv:keymap    (user keymap config)
```

Three renderer→main, four main→renderer; **seven** channels total.

---

## 2. Channel catalog

| Channel | Direction | Payload (JSON) | Sender (renderer side / main side) | Receiver | Trigger | Status |
| --- | --- | --- | --- | --- | --- | --- |
| `vv:open` | renderer → main | `path` *(string)* | `window.vv.open(path)` → `ipcRenderer.send` | `ipcMain.on("vv:open")` → `service/open!` | `:vv/open` fx (open a tree file / re-open) | live |
| `vv:close` | renderer → main | `path` *(string)* | `window.vv.close(path)` → `ipcRenderer.send` | `ipcMain.on("vv:close")` → `service/close!` | `:vv/close` fx (from `:tab/close`) | live |
| `vv:keymap-request` | renderer → main | *(none)* | `window.vv.requestKeymap()` → `ipcRenderer.send` | *(no main handler yet)* | `renderer.core/bridge!` after subscribing to `onKeymap` | **renderer-wired; main handler pending** |
| `vv:content` | main → renderer | `{:path :kind (:text)}` | `webContents.send("vv:content")` | `window.vv.onContent(cb)` → `[:content/received]` | initial `open!`; every chokidar `change`/`add` | live |
| `vv:error` | main → renderer | `{:path :message}` | `webContents.send("vv:error")` | `window.vv.onError(cb)` → `[:content/error]` | `readFileSync` throws in `send-content!` | live |
| `vv:tree` | main → renderer | `{:root :files}` | `webContents.send("vv:tree")` | `window.vv.onTree(cb)` → `[:tree/received]` | `open!` when the file is in a git repo | live |
| `vv:keymap` | main → renderer | user keymap config *(map)* | `webContents.send("vv:keymap")` | `window.vv.onKeymap(cb)` → `[:keymap/config-received]` | (main pushes the user's `keybindings.edn`) | **renderer-wired; main sender pending** |

> **`vv:keymap` / `vv:keymap-request` are defined-but-dormant.** The renderer subscribes
> (`onKeymap`) and pulls (`requestKeymap`), guarded on `window.vv.onKeymap` existing, but **main does
> not yet handle the request nor send the config**. Until it does, the renderer uses the bundled
> `:default` preset (or a preset chosen at runtime via `window.__vvkeymap("vim")`). See
> [events-effects-subs.md §4.4](./events-effects-subs.md#44-what-is-wired-vs-pending).

---

## 3. Payload shapes (verbatim)

```clojure
;; vv:content  — kind ∈ {"markdown" "image" "text"}  (service/kind-of)
{:path "/abs/README.md" :kind "markdown" :text "# Hello\n…"}   ; :text for markdown/text
{:path "/abs/logo.png"  :kind "image"}                          ; NO :text for images (file:// load)

;; vv:error
{:path "/abs/missing.md" :message "ENOENT: …"}

;; vv:tree  — root = git top-level; files = repo-relative (git ls-files)
{:root "/abs/repo" :files ["README.md" "docs/intro.md" …]}

;; vv:keymap  — the user's ~/.config/vinary-viewer/keybindings.edn, e.g.
{:extends :vim :initial-mode :normal :timeout-ms 1000
 :keymaps {:normal {"za" :sidebar/toggle "zd" :unbind}}}
```

`vv:open`, `vv:close` send a **bare string** `path`; `vv:keymap-request` sends **nothing**.

---

## 4. The `kind` classifier (`service/kind-of`)

Case-insensitive on the file extension:

| Extension(s) | `kind` |
| --- | --- |
| `.md`, `.markdown`, `.mdx` | `"markdown"` |
| `.png`, `.jpg`/`.jpeg`, `.gif`, `.svg`, `.webp`, `.bmp`, `.ico`, `.avif` | `"image"` |
| everything else | `"text"` |

---

## 5. The `on*` → unsubscribe contract

Every `window.vv.on*(cb)` returns an **unsubscribe function** that calls
`ipcRenderer.removeListener`. The Electron `event` object is dropped — only the `payload` reaches the
renderer callback. (`onContent`, `onError`, `onTree`, `onKeymap` all follow this identical shape.)

```js
onTree: (cb) => {
  const h = (_e, payload) => cb(payload);
  ipcRenderer.on('vv:tree', h);
  return () => ipcRenderer.removeListener('vv:tree', h);   // ◀ unsubscribe
},
```

---

## 6. Envelope & conversion

| Hop | Conversion |
| --- | --- |
| main → renderer (send) | `(clj->js {…})` before `webContents.send` |
| main → renderer (receive) | `(js->clj payload :keywordize-keys true)` before `rf/dispatch` |
| renderer → main (send) | bare string (`vv:open`/`vv:close`) or nothing (`vv:keymap-request`) |

Plain JSON is mandatory because `contextBridge`/Electron IPC transfers only structured-clonable
values across the V8 context boundary (ClojureScript persistent structures do not survive it).

---

## 7. Not exposed (intentionally)

`fs` / arbitrary reads · `ipcRenderer` directly · `child_process` / git (main-only) · writing files ·
Node `require` in the page. See
[architecture/03-ipc-protocol.md §8](../architecture/03-ipc-protocol.md#8-what-is-intentionally-not-exposed)
and [security/threat-model.md](../security/threat-model.md).
