# Reference · IPC Channels

> **What this is.** A lookup-form catalog of Electron Inter-Process
> Communication (IPC) channels between the **main** process and the renderer
> process. The architectural rationale is in
> [architecture/03-ipc-protocol.md](../architecture/03-ipc-protocol.md). The
> concrete renderer-facing API is `window.vv`, exposed by
> [`resources/preload.js`](../../resources/preload.js).

All renderer code talks to main through `window.vv`. The renderer never imports
`ipcRenderer`, `fs`, or `child_process` directly.

---

## 1. Renderer → Main

| Channel | Preload method | Payload | Main receiver | Purpose |
|---------|----------------|---------|---------------|---------|
| `vv:open` | `open(path)` | `path` string | `vinary.main.service/open!` | Read a local file, send content, send git tree, and start/reuse its watcher. |
| `vv:close` | `close(path)` | `path` string | `vinary.main.service/close!` | Compatibility close path; retained-file sync is the normal ownership path. |
| `vv:retained-files` | `syncRetainedFiles(paths)` | string array | `vinary.main.service/sync-retained!` | Replace main's retained local-file set and release unretained watchers/assets. |
| `vv:watch-assets` | `watchAssets(docPath, paths)` | `{docPath, paths}` | `vinary.main.service/watch-assets!` | Watch local media assets referenced by a retained Markdown document. |
| `vv:keymap-request` | `requestKeymap()` | none | `vinary.main.config/push!` | Request persisted `keybindings.edn`. |
| `vv:keymap-save` | `saveKeymap(edn)` | EDN string | `vinary.main.config/save!` | Persist keybinding registry EDN. |
| `vv:grammars-request` | `requestGrammars()` | none | `vinary.main.grammars/push!` | Request user grammar registry and filetype mappings. |
| `vv:settings-request` | `requestSettings()` | none | `vinary.main.settings/push!` | Request persisted `settings.edn`. |
| `vv:settings-save` | `saveSettings(edn)` | EDN string | `vinary.main.settings/save!` | Persist settings EDN. |
| `vv:pdf-show` | `pdfShow(path, bounds)` | `{path, bounds}` | `vinary.main.pdf/show!` | Show/load the native PDF `WebContentsView`. |
| `vv:pdf-hide` | `pdfHide()` | none | `vinary.main.pdf/hide!` | Hide the native PDF view. |
| `vv:pdf-bounds` | `pdfBounds(bounds)` | `{bounds}` | `vinary.main.pdf/set-bounds!` | Reposition the native PDF view. |
| `vv:http-show` | `httpShow(url, bounds)` | `{url, bounds}` | `vinary.main.web/show!` | Show/load the in-app HTTP `WebContentsView`. |
| `vv:http-hide` | `httpHide()` | none | `vinary.main.web/hide!` | Hide the in-app HTTP view. |
| `vv:http-bounds` | `httpBounds(bounds)` | `{bounds}` | `vinary.main.web/set-bounds!` | Reposition the in-app HTTP view. |
| `vv:http-toc-goto` | `httpTocGoto(id)` | heading id string | `vinary.main.web` | Scroll the HTTP page to a heading. |
| `vv:open-dialog` | `openDialog()` | none | `vinary.main.shell` | Open the multi-file native dialog. |
| `vv:clipboard-write` | `copyText(text)` | string | `vinary.main.shell` | Copy text to the OS clipboard. |
| `vv:open-path` | `openPath(path)` | string | `vinary.main.shell` | Ask the OS to reveal/open a local path. |
| `vv:open-external` | `openExternal(url)` | string | `vinary.main.shell` | Ask the OS to open an external URL. |
| `vv:app-info-request` | `requestAppInfo()` | none | `vinary.main.shell` | Request app metadata for About UI. |
| `vv:quit` | `quit()` | none | `vinary.main.shell` | Quit the Electron app. |
| `vv:devtools` | `toggleDevtools()` | none | `vinary.main.shell` | Toggle renderer devtools. |
| `vv:zoom` | `zoom(dir)` | direction value | `vinary.main.shell` | Adjust Chromium zoom. |

`bounds` payloads are plain maps with numeric `x`, `y`, `width`, and `height`
fields derived from `getBoundingClientRect()`.

---

## 2. Main → Renderer

| Channel | Preload subscription | Payload | Renderer event/effect | Purpose |
|---------|----------------------|---------|-----------------------|---------|
| `vv:content` | `onContent(cb)` | `{path, kind, stamp, text?}` | `[:content/received ...]` | Deliver initial and live-refreshed document content. |
| `vv:error` | `onError(cb)` | `{path?, message, stamp?}` | `[:content/error ...]` | Deliver read/render errors. |
| `vv:tree` | `onTree(cb)` | `{root, files}` | `[:tree/received ...]` | Deliver git file-tree data. |
| `vv:keymap` | `onKeymap(cb)` | keymap registry map/string | `[:keymap/config-received ...]` | Deliver persisted keybinding config. |
| `vv:grammars` | `onGrammars(cb)` | EDN string map `{:grammars [...] :filetypes {...}}` | `syntax/register-user!` | Deliver user tree-sitter grammar entries and filetype mappings. |
| `vv:http-navigated` | `onHttpNavigated(cb)` | `{url}` | `[:http/navigated ...]` | Sync in-app HTTP navigation into active tab history. |
| `vv:web-toc` | `onWebToc(cb)` | heading vector | `[:web/toc ...]` | Deliver heading outline from the HTTP web view. |
| `vv:web-active-heading` | `onWebActiveHeading(cb)` | heading id or nil | `[:web/active-heading ...]` | Deliver active HTTP heading. |
| `vv:history-nav` | `onHistoryNav(cb)` | `"back"` or `"forward"` | history dispatch | Forward browser-like navigation from native/web surfaces. |
| `vv:open-files` | `onOpenFiles(cb)` | `{paths}` | `[:files/opened ...]` | Deliver file selections from the native Open dialog. |
| `vv:settings` | `onSettings(cb)` | EDN string | `[:settings/received ...]` | Deliver persisted settings. |
| `vv:app-info` | `onAppInfo(cb)` | app metadata map | `[:app-info/received ...]` | Deliver app metadata. |

---

## 3. Content Kinds

`vv:content` uses the file classifier in `vinary.main.file-kind/kind-of`:

| Extension(s) | `kind` | Payload detail |
|---------------|--------|----------------|
| `.md`, `.markdown`, `.mdx` | `"markdown"` | text is read and rendered in the renderer. |
| image extensions such as `.png`, `.jpg`, `.svg`, `.webp`, `.avif` | `"image"` | binary is not read as text; renderer uses `file://` URL. |
| `.pdf` | `"pdf"` | binary is not read as text; main-owned PDF view loads `file://` URL. |
| `.mmd`, `.mermaid` | `"mermaid"` | text is read and rendered to SVG by the renderer-side Mermaid strategy. |
| bundled/user source extensions, configured filetype mappings, and non-Mermaid diagram-source extensions | `"source"` | text is read into the read-only source view. |
| everything else | `"text"` | text is escaped into plain HTML. |

---

## 4. The `on*` → Unsubscribe Contract

Every `window.vv.on*(cb)` subscription returns an unsubscribe function that calls
`ipcRenderer.removeListener`. The Electron event object is dropped; only the
payload reaches renderer code.

```js
onTree: (cb) => {
  const h = (_e, payload) => cb(payload);
  ipcRenderer.on('vv:tree', h);
  return () => ipcRenderer.removeListener('vv:tree', h);
},
```

---

## 5. Envelope And Conversion

| Hop | Conversion |
|-----|------------|
| main → renderer | ClojureScript data is converted with `clj->js` or serialized as EDN text before `webContents.send`. |
| renderer receive | Renderer converts JavaScript payloads with `js->clj :keywordize-keys true`, or parses EDN text where keyword fidelity matters. |
| renderer → main | Preload sends plain strings, arrays, objects, numbers, booleans, or EDN strings. |

Plain structured data is mandatory because Electron IPC and `contextBridge`
cross V8 context boundaries; ClojureScript persistent structures do not cross
that boundary directly.

---

## 6. Not Exposed

The renderer does not receive direct access to `fs`, arbitrary file reads,
`ipcRenderer`, `child_process`, or Node `require`. See
[architecture/03-ipc-protocol.md §8](../architecture/03-ipc-protocol.md#8-what-is-intentionally-not-exposed)
and [security/threat-model.md](../security/threat-model.md).
