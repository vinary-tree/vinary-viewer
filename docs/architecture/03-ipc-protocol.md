# 03. IPC Protocol

This page specifies the Electron main/renderer IPC seam exposed as `window.vv`.
The flat channel lookup is in [../reference/ipc-channels.md](../reference/ipc-channels.md).

---

## 1. Mediator rule

All cross-process traffic passes through `resources/preload.js`:

```text
renderer re-frame effect
  -> window.vv.method(...)
  -> ipcRenderer.send("vv:*", payload)
  -> ipcMain handler in main
```

and:

```text
main webContents.send("vv:*", payload)
  -> preload on* wrapper
  -> renderer callback
  -> rf/dispatch [...]
```

The renderer never receives `ipcRenderer` or `fs`.

---

## 2. Renderer to main

| Channel | `window.vv` API | Payload | Main owner | Purpose |
|---------|-----------------|---------|------------|---------|
| `vv:open` | `open(path)` | string path | `vinary.main.service` | Read, classify, send content, send tree, ensure watcher. |
| `vv:close` | `close(path)` | string path | `vinary.main.service` | Close an individual watcher path. Kept for compatibility; retained sync is authoritative. |
| `vv:retained-files` | `syncRetainedFiles(paths)` | path vector | `vinary.main.service` | Reconcile main watchers to the renderer's retained local path set. |
| `vv:watch-assets` | `watchAssets(docPath, paths)` | `{docPath, paths}` | `vinary.main.service` | Watch embedded local assets referenced by a Markdown document. |
| `vv:keymap-request` | `requestKeymap()` | none | `vinary.main.config` | Push current `keybindings.edn`. |
| `vv:keymap-save` | `saveKeymap(edn)` | EDN string | `vinary.main.config` | Persist keybinding registry. |
| `vv:grammars-request` | `requestGrammars()` | none | `vinary.main.grammars` | Push grammar registry. |
| `vv:settings-request` | `requestSettings()` | none | `vinary.main.settings` | Push current `settings.edn`. |
| `vv:settings-save` | `saveSettings(edn)` | EDN string | `vinary.main.settings` | Persist settings. |
| `vv:pdf-show` | `pdfShow(path, bounds)` | `{path, bounds}` | `vinary.main.pdf` | Show native PDF view. |
| `vv:pdf-hide` | `pdfHide()` | none | `vinary.main.pdf` | Hide PDF view. |
| `vv:pdf-bounds` | `pdfBounds(bounds)` | `{bounds}` | `vinary.main.pdf` | Reposition PDF view. |
| `vv:http-show` | `httpShow(url, bounds)` | `{url, bounds}` | `vinary.main.web` | Show HTTP/HTTPS web view. |
| `vv:http-hide` | `httpHide()` | none | `vinary.main.web` | Hide web view. |
| `vv:http-bounds` | `httpBounds(bounds)` | `{bounds}` | `vinary.main.web` | Reposition web view. |
| `vv:http-toc-goto` | `httpTocGoto(id)` | heading id | `vinary.main.web` | Ask web preload to scroll to a heading. |
| `vv:open-dialog` | `openDialog()` | none | main UI/dialog service | Show native open dialog. |
| `vv:clipboard-write` | `copyText(text)` | string | main shell service | Write clipboard text. |
| `vv:open-path` | `openPath(path)` | string | main shell service | Open a local path externally. |
| `vv:open-external` | `openExternal(url)` | string | main shell service | Open external URL in the OS browser. |
| `vv:app-info-request` | `requestAppInfo()` | none | main app info | Push app metadata. |
| `vv:quit` | `quit()` | none | main app lifecycle | Quit application. |
| `vv:devtools` | `toggleDevtools()` | none | main window | Toggle DevTools. |
| `vv:zoom` | `zoom(dir)` | direction | main window | Adjust Electron zoom. |

---

## 3. Main to renderer

Every `on*` API returns an unsubscribe function.

| Channel | `window.vv` API | Payload | Renderer event |
|---------|-----------------|---------|----------------|
| `vv:content` | `onContent(cb)` | `{path, kind, text?, html?, stamp?}` | `[:content/received payload]` |
| `vv:error` | `onError(cb)` | `{path, message, stamp?}` | `[:content/error payload]` |
| `vv:tree` | `onTree(cb)` | `{root, files}` | `[:tree/received payload]` |
| `vv:keymap` | `onKeymap(cb)` | EDN string or parsed payload | `[:keymap/config-received payload]` |
| `vv:grammars` | `onGrammars(cb)` | grammar registry | grammar registry event |
| `vv:http-navigated` | `onHttpNavigated(cb)` | `{url}` | `[:http/navigated payload]` |
| `vv:web-toc` | `onWebToc(cb)` | heading vector | `[:web/toc headings]` |
| `vv:web-active-heading` | `onWebActiveHeading(cb)` | id or nil | `[:web/active-heading id]` |
| `vv:history-nav` | `onHistoryNav(cb)` | direction | history event |
| `vv:open-files` | `onOpenFiles(cb)` | `{paths}` | `[:files/opened payload]` |
| `vv:settings` | `onSettings(cb)` | EDN string | `[:settings/received text]` |
| `vv:app-info` | `onAppInfo(cb)` | app metadata | `[:app-info/received info]` |

---

## 4. Payload discipline

Most payloads are structured-clonable JavaScript values. Settings and keybinding
config intentionally cross as EDN text so Clojure keywords survive the round trip.

Main-to-renderer content payloads:

```clojure
{:path "/abs/path/doc.md"
 :kind "markdown"
 :text "# Title"
 :stamp 1780000000000}
```

Markdown render output is not sent by main; it is produced in the renderer and
committed as:

```clojure
{:html "<h1 id=\"title\">Title</h1>"
 :toc [{:level 1 :text "Title" :id "title"}]
 :assets ["/abs/path/diagram.svg"]}
```

---

## 5. Security posture

| Control | Current setting |
|---------|-----------------|
| `contextIsolation` | enabled |
| `nodeIntegration` | disabled |
| Renderer filesystem access | none; only `window.vv` methods |
| Raw Markdown HTML | not enabled via `rehype-raw` |
| Renderer sandbox | tracked as a hardening item |
| Strict CSP | tracked as a hardening item |

The seam is intentionally broad enough for current app features but narrow
enough to audit. New privileged capabilities should be added in main, exposed as
small `window.vv` methods, and documented here and in the threat model.
