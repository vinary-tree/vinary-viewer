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
| `vv:content-page` | `contentPage(request)` ⮐ | `{path, kind, stamp, page, meta?}` | `vinary.main.service` → `content_service.contentPage` | *(invoke)* Fetch one bounded page for a large log or delimited-table preview, including `hasPrev`/`hasNext` flags. |
| `vv:keymap-request` | `requestKeymap()` | none | `vinary.main.config/push!` | Request persisted `keybindings.edn`. |
| `vv:keymap-save` | `saveKeymap(edn)` | EDN string | `vinary.main.config/save!` | Persist keybinding registry EDN. |
| `vv:grammars-request` | `requestGrammars()` | none | `vinary.main.grammars/push!` | Request user grammar registry and filetype mappings. |
| `vv:settings-request` | `requestSettings()` | none | `vinary.main.settings/push!` | Request persisted `settings.edn`. |
| `vv:settings-save` | `saveSettings(edn)` | EDN string | `vinary.main.settings/save!` | Persist settings EDN. |
| `vv:recent-request` | `requestRecent()` | none | `vinary.main.recent/push!` | Request persisted `recent.edn` (dir→child trail + recent-files MRU). |
| `vv:recent-save` | `saveRecent(edn)` | EDN string | `vinary.main.recent/save!` | Persist recent-navigation EDN (debounced 300 ms in the renderer). |
| `vv:complete-path` | `completePath(input)` ⮐ | string | `vinary.main.service` | *(invoke)* Path-completion data for the URI bar: directory children + exact-match info. |
| `vv:ext-config-request` / `vv:ext-config-save` | `requestExtConfig()` / `saveExtConfig(edn)` | none / EDN | `vinary.main.ext-config` | Request / persist `extensions.edn` (ad-block + extension prefs). |
| `vv:ext-state-request` | `extState()` | none | `vinary.main.extensions` | Request the installed-extensions state push. |
| `vv:ext-install` / `vv:ext-remove` | `extInstall(idOrUrl)` / `extRemove(id)` | string | `vinary.main.extensions` | Install (Web-Store id/URL) / uninstall an extension. |
| `vv:ext-set-enabled` | `extSetEnabled(id, on)` | `{id, on}` | `vinary.main.extensions` | Enable/disable a loaded extension. |
| `vv:ext-check-updates` | `extCheckUpdates()` | none | `vinary.main.extensions` | Trigger a Web-Store update check. |
| `vv:ext-action-clicked` / `vv:ext-popup-close` | `extActionClicked(id, popup, bounds)` / `extPopupClose()` | `{id, popup, bounds}` / none | `vinary.main.ext-popup` | Open / close a browser-action popup. |
| `vv:adblock-set-enabled` / `vv:adblock-set-lists` / `vv:adblock-refresh` | `adblockSetEnabled(on)` / `adblockSetLists(kw)` / `adblockRefresh()` | bool / string / none | `vinary.main.adblock` | Toggle / set list-set / refresh the ad-blocker. |
| `vv:password-state-request` | `passwordState()` | none | `vinary.main.passwords` | Refresh provider status for the native password-manager bridge. |
| `vv:password-search` | `passwordSearch(url)` | URL string | `vinary.main.passwords` | Search ready providers for logins matching the current web origin. Results are sanitized metadata only. |
| `vv:password-fill` | `passwordFill(item)` | `{provider, id, vault-id?, ...}` metadata | `vinary.main.passwords` | Reveal the selected item in main, then send credentials directly to the web-view preload. Passwords do not cross into renderer app-db. |
| `vv:password-save` | `passwordSave(payload)` | `{token, provider}` | `vinary.main.passwords` | Save a short-lived main-memory login candidate into the chosen provider. The token references the secret; the renderer does not carry the password. |
| `vv:password-dismiss-save` | `passwordDismissSave(token)` | token string | `vinary.main.passwords` | Drop an unsaved login candidate from main memory. |
| `vv:pdf-show` / `vv:pdf-hide` / `vv:pdf-bounds` | `pdfShow` / `pdfHide` / `pdfBounds` | — | *RETIRED* | Native PDF view seam — retired for in-renderer pdf.js (ADR-0013); no main listener (harmless no-ops). |
| `vv:http-show` | `httpShow(url, bounds, tabId)` | `{url, bounds, tabId}` | `vinary.main.web/show!` | Show/load the in-app HTTP `WebContentsView`. `tabId` is the **owning** tab (the active http tab at show time); main stores it so the navigation relay updates that tab even after the user switches away mid-load. |
| `vv:http-hide` | `httpHide()` | none | `vinary.main.web/hide!` | Hide the in-app HTTP view. |
| `vv:http-bounds` | `httpBounds(bounds)` | `{bounds}` | `vinary.main.web/set-bounds!` | Reposition the in-app HTTP view. |
| `vv:http-zoom` / `vv:http-zoom-set` | `httpZoom(dir)` / `httpZoomSet(f)` | direction / factor | `vinary.main.web` (`setZoomFactor`) | Zoom the web PAGE (the native view's own webContents), not the app chrome; reports the new factor on `vv:zoom-changed`. |
| `vv:http-snapshot` *(invoke)* | `httpSnapshot()` | none → JPEG data-URL | `vinary.main.web` (cached `capturePage`→`toJPEG`) | Cold-cache fallback: return the latest cached page raster (a proactive capture+push keeps it fresh) so the renderer can freeze the page under any overlay while the always-on-top native view hides. |
| `vv:http-toc-goto` | `httpTocGoto(id)` | heading id string | `vinary.main.web` | Scroll the HTTP page to a heading. |
| `vv:open-dialog` | `openDialog()` | none | `vinary.main.shell` | Open the multi-file native dialog. |
| `vv:clipboard-write` | `copyText(text)` | string | `vinary.main.shell` | Copy text to the OS clipboard. |
| `vv:open-path` | `openPath(path)` | string | `vinary.main.shell` | Ask the OS to reveal/open a local path. |
| `vv:open-external` | `openExternal(url)` | string | `vinary.main.shell` | Ask the OS to open an external URL. |
| `vv:app-info-request` | `requestAppInfo()` | none | `vinary.main.shell` | Request app metadata for About UI. |
| `vv:quit` | `quit()` | none | `vinary.main.shell` | Quit the Electron app. |
| `vv:devtools` | `toggleDevtools()` | none | `vinary.main.shell` | Toggle renderer devtools. |
| `vv:zoom` / `vv:zoom-set` | `zoom(dir)` / `zoomSet(f)` | direction / factor | `vinary.main.shell` | Adjust the app-window zoom (DOM views); reports the new factor on `vv:zoom-changed`. |

`bounds` payloads are plain maps with numeric `x`, `y`, `width`, and `height`
fields derived from `getBoundingClientRect()`.

---

## 2. Main → Renderer

| Channel | Preload subscription | Payload | Renderer event/effect | Purpose |
|---------|----------------------|---------|-----------------------|---------|
| `vv:content` | `onContent(cb)` | `{path, kind, stamp, text?, html?, bytes?, entries?, sheets?, page?, meta?, paged?}` | `[:content/received ...]` | Deliver initial and live-refreshed document content. PDFs carry `:bytes`; directories/archives carry `:entries`; large logs/tables carry the first page. |
| `vv:error` | `onError(cb)` | `{path?, message, stamp?}` | `[:content/error ...]` | Deliver read/render errors. |
| `vv:tree` | `onTree(cb)` | `{root, files}` | `[:tree/received ...]` | Deliver git file-tree data. |
| `vv:keymap` | `onKeymap(cb)` | keymap registry map/string | `[:keymap/config-received ...]` | Deliver persisted keybinding config. |
| `vv:grammars` | `onGrammars(cb)` | EDN string map `{:grammars [...] :filetypes {...}}` | `syntax/register-user!` | Deliver user tree-sitter grammar entries and filetype mappings. |
| `vv:http-navigated` | `onHttpNavigated(cb)` | `{url, tab}` | `[:http/navigated ...]` | Record in-app HTTP navigation onto the **owner** tab's history (`tab` = the tab that owns the web-view load, NOT necessarily the active tab — a slow page may finish loading after the user switched to another tab). |
| `vv:http-snapshot-ready` | `onHttpSnapshotReady(cb)` | JPEG data-URL | `web-host` (`pre-snap`) | Push a fresh page raster (captured after load + on scroll-settle) so opening any overlay freezes the page **instantly** — a synchronous DOM swap, no capture on the open path. |
| `vv:web-toc` | `onWebToc(cb)` | heading vector | `[:web/toc ...]` | Deliver heading outline from the HTTP web view. |
| `vv:web-active-heading` | `onWebActiveHeading(cb)` | heading id or nil | `[:web/active-heading ...]` | Deliver active HTTP heading. |
| `vv:history-nav` | `onHistoryNav(cb)` | `"back"` or `"forward"` | history dispatch | Forward browser-like navigation from native/web surfaces. |
| `vv:web-key` | `onWebKey(cb)` | `{key, ctrl, shift, alt, meta}` | replayed as a synthetic `window` keydown → keymap resolver | Forward an app-global Ctrl/Cmd chord from the (separate-context) web view so the keymap runs the same command (Ctrl+O, Ctrl+Shift+O, Ctrl+L, Ctrl+F, zoom …). Page editing/clipboard chords (Ctrl+C/V/X/A/Z) stay with the page. |
| `vv:open-files` | `onOpenFiles(cb)` | `{paths, focus-first?}` | `[:files/opened ...]` | Deliver file/URI selections from the native Open dialog **or** the arguments named on the command line at launch (each opened in its own tab). Optional `focus-first` (set by the command-line launch) re-focuses the first path's tab after all open; the dialog omits it and leaves the last-opened tab active. |
| `vv:settings` | `onSettings(cb)` | EDN string | `[:settings/received ...]` | Deliver persisted settings. |
| `vv:recent` | `onRecent(cb)` | EDN string | `[:recent/received ...]` | Deliver persisted recent-navigation state (`{:trail {…} :recent-files [...] :web-history [...]}`); initial push plus on external edit. |
| `vv:ext-config` | `onExtConfig(cb)` | EDN string | `[:ext-config/received ...]` | Deliver persisted ad-block + extension prefs (`extensions.edn`). |
| `vv:ext-state` | `onExtState(cb)` | EDN string | `[:ext/state-received ...]` | Deliver the installed-extensions list + master toggle. |
| `vv:ext-install-result` / `vv:ext-update-result` | `onExtInstallResult` / `onExtUpdateResult` | object | `[:ext/install-result ...]` / `[:ext/update-result ...]` | Install / update outcome. |
| `vv:adblock-status` | `onAdblockStatus(cb)` | `{:status :updating\|:ok\|:offline\|:error :last-updated? :error?}` | `[:adblock/status-received ...]` | Ad-block refresh feedback for the Extensions dialog ("Updating…" → "✓ Updated" / "⚠ Offline" / "✗ …"). |
| `vv:password-state` | `onPasswordState(cb)` | `{providers, forms, busy?, error?}` | `[:passwords/state-received ...]` | Provider status and web-form presence for the native password-manager UI. |
| `vv:password-items` | `onPasswordItems(cb)` | `{url, origin, items}` | `[:passwords/items-received ...]` | Sanitized login matches. Items contain provider/id/title/username/url metadata, never password fields. |
| `vv:password-save-prompt` | `onPasswordSavePrompt(cb)` | `{token, url, origin, username, providers}` or null | `[:passwords/save-prompt ...]` | Prompt the renderer to save a login candidate held only in main memory. Null closes the prompt. |
| `vv:password-result` | `onPasswordResult(cb)` | `{ok, action, message}` | `[:passwords/result-received ...]` | Fill/save result message. |
| `vv:zoom-changed` | `onZoomChanged(cb)` | `{:context "window"\|"web" :factor n}` | `[:view/zoom-changed ...]` | Report the resolved app-window / web-view zoom factor so the zoom bar shows the live %. |
| `vv:app-info` | `onAppInfo(cb)` | app metadata map | `[:app-info/received ...]` | Deliver app metadata. |

---

## 2.1 Web-View Preload Channels

These channels involve `resources/web-preload.js`, which runs in the isolated in-app web page. They do
not expose an API to the remote page.

| Channel | Direction | Payload | Receiver | Purpose |
|---------|-----------|---------|----------|---------|
| `vv:password-forms` | web preload -> main | `{url, origin, count, hasPassword}` | `vinary.main.passwords` | Report login-form presence so the app can show the key-icon badge. |
| `vv:password-save-candidate` | web preload -> main | `{url, origin, username, password}` | `vinary.main.passwords` | Store a submitted login candidate in main memory behind a short-lived token. |
| `vv:password-fill` | main -> web preload | `{username, password, url?}` | `resources/web-preload.js` | Fill the selected login directly into DOM fields and fire `input`/`change` events. |

---

## 3. Content Kinds

`vv:content` uses the file classifier in `vinary.main.file-kind/kind-of`, except
for directories, which `vinary.main.service/send-content!` detects first (via
`directory?`) and lists rather than reading as text:

| Extension(s) | `kind` | Payload detail |
|---------------|--------|----------------|
| any path that is a directory | `"directory"` | not read as text; payload is `{:path :kind "directory" :entries [...] :stamp}`, where each entry is `{:name :path :dir? :size :mtime :symlink}`. Stored on the document entity as `:doc/entries` and rendered in-pane by the directory browser. |
| `.md`, `.markdown`, `.mdx` | `"markdown"` | text is read and rendered in the renderer. |
| image extensions such as `.png`, `.jpg`, `.svg`, `.webp`, `.avif` | `"image"` | binary is not read as text; renderer uses `file://` URL. |
| `.pdf` | `"pdf"` | bytes streamed to the renderer (`:bytes`) and rendered in-DOM by pdf.js (ADR-0013); never read as text. |
| `.html`, `.htm`, `.xhtml` | `"html"` | not read as text; rendered as a live page in the in-app web view via its `file://` URL (edge-to-edge), with the ad-blocker + extensions applied. |
| `.docx`, `.odt`, `.odp`, `.odf` | `"office"` | parsed in main into sanitized HTML/text preview payloads. |
| `.csv`, `.tsv`, `.tab`, `.psv`, `.dsv`, `.xlsx`, `.xlsm`, `.ods`, `.fods` | `"table"` | delimited files render as bounded rows, large delimited files page through `vv:content-page`, and workbooks render capped sheet matrices. |
| `.log`, `.out`, `.err`, `.trace`, compressed `.log.gz` / rotated `.log.N.gz`, standard log basenames, and sniffed log-like text | `"log"` | rendered as lines with tolerant severity highlighting; large logs page through `vv:content-page`. |
| `.zip`, `.jar`, `.war`, `.ear`, `.epub`, `.tar`, `.tgz`, `.tar.gz` and `vv-archive://...` | `"archive"` | listed in-pane as directory-browser-compatible entries; nested archive entries are addressed by virtual archive URI chains and are not extracted to disk. |
| `.mmd`, `.mermaid` | `"mermaid"` | text is read and rendered to SVG by the renderer-side Mermaid strategy. |
| bundled/user source extensions, configured filetype mappings, and non-Mermaid diagram-source extensions | `"source"` | text is read into the read-only source view. |
| everything else | `"text"` | plain text routes through the content service first so extensionless logs/delimited files can be sniffed; otherwise text is escaped into plain HTML. |

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
