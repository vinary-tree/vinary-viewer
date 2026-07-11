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

The sequence below shows the bounded-preview protocol for large logs, delimited files,
and archive members. A *page* is a finite row or line window; a *virtual archive URI*
is an address for an archive member that has not been extracted to disk.

![Bounded preview streaming](../diagrams/seq-content-page-streaming.svg)

*Figure - source: [`docs/diagrams/seq-content-page-streaming.puml`](../diagrams/seq-content-page-streaming.puml).*

---

## 2. Renderer to main

| Channel | `window.vv` API | Payload | Main owner | Purpose |
|---------|-----------------|---------|------------|---------|
| `vv:open` | `open(path)` | string path | `vinary.main.service` | Read, classify, send content, send tree, ensure watcher. |
| `vv:close` | `close(path)` | string path | `vinary.main.service` | Close an individual watcher path. Kept for compatibility; retained sync is authoritative. |
| `vv:retained-files` | `syncRetainedFiles(paths)` | path vector | `vinary.main.service` | Reconcile main watchers to the renderer's retained local path set. |
| `vv:watch-assets` | `watchAssets(docPath, paths)` | `{docPath, paths}` | `vinary.main.service` | Watch embedded local assets referenced by a Markdown document. |
| `vv:content-page` | `contentPage(request)` | page request map | `vinary.main.service` | Fetch one bounded page of a large log or delimited-table preview. (Accepts `ssh://` paths.) |
| `vv:load-remote-asset` | `loadRemoteAsset(req)` | `{uri, relativeTo}` | `vinary.main.service` | Fetch a remote asset's bytes over SFTP → a `data:` URL (remote Markdown/Office relative images). |
| `vv:ssh-prompt-reply` | `sshPromptReply(promptId, secret)` | `{promptId, secret}` | `vinary.main.ssh` | The typed SSH secret — the **only** secret-bearing channel; one-shot, never persisted. |
| `vv:ssh-close-connection` | `sshCloseConnection(connKey)` | connKey string | `vinary.main.ssh` | Close a pooled SSH connection. |
| `vv:connections-request` | `requestConnections()` | none | `vinary.main.connections` | Push current `connections.edn`. |
| `vv:connections-save` | `saveConnections(edn)` | EDN string | `vinary.main.connections` | Persist non-secret SSH connection metadata. |
| `vv:keymap-request` | `requestKeymap()` | none | `vinary.main.config` | Push current `keybindings.edn`. |
| `vv:keymap-save` | `saveKeymap(edn)` | EDN string | `vinary.main.config` | Persist keybinding registry. |
| `vv:grammars-request` | `requestGrammars()` | none | `vinary.main.grammars` | Push grammar registry. |
| `vv:settings-request` | `requestSettings()` | none | `vinary.main.settings` | Push current `settings.edn`. |
| `vv:settings-save` | `saveSettings(edn)` | EDN string | `vinary.main.settings` | Persist settings. |
| `vv:recent-request` | `requestRecent()` | none | `vinary.main.recent` | Push current `recent.edn`. |
| `vv:recent-save` | `saveRecent(edn)` | EDN string | `vinary.main.recent` | Persist recent-navigation state (trail + MRU). |
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
| `vv:content` | `onContent(cb)` | `{path, kind, text?, html?, entries?, sheets?, page?, meta?, stamp?}` | `[:content/received payload]` |
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
| `vv:ssh-prompt` | `onSshPrompt(cb)` | `{promptId, kind, host, user, attempt, keyPath?, prompt?}` (non-secret request) | `[:ssh/prompt req]` |
| `vv:ssh-error` | `onSshError(cb)` | `{connKey, host, kind, message}` | `[:ssh/error info]` |
| `vv:ssh-status` | `onSshStatus(cb)` | `{connKey, host, state}` | `[:ssh/status info]` |
| `vv:connections` | `onConnections(cb)` | EDN string | `[:connections/received text]` |
| `vv:recent` | `onRecent(cb)` | EDN string | `[:recent/received text]` |
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

Large logs and large delimited files use a two-step protocol. The initial
`vv:content` message is a manifest plus the first page. The main process reads
the source through a stream and stops after the requested page plus one lookahead
line or row, so the renderer never receives an unbounded text blob for these
formats:

```clojure
{:path "/abs/path/app.log"
 :kind "log"
 :paged true
 :page {:index 0 :lines ["..."] :hasPrev false :hasNext true}
 :meta {:size 81234567 :pageSize 2000}
 :stamp 1780000000000}
```

The renderer asks for adjacent pages through `vv:content-page`:

```clojure
{:path "/abs/path/app.log" :kind "log" :stamp 1780000000000 :page 1}
```

and receives only that bounded page. Delimited files use the same shape with
`:kind "table"` and `:rows` instead of `:lines`. The renderer keeps a finite
nearby-page cache and prefetches the previous and next page when possible; main
keeps a bounded request cache keyed by `path`, `stamp`, `kind`, `page`, and
`sheet`.

Supported tabular preview formats are `.csv`, `.tsv`, `.tab`, `.psv`, `.dsv`,
`.xlsx`, `.xlsm`, `.ods`, and `.fods`. Delimited files are stream-paged when
large; workbook formats are parsed from capped preview bytes because their ZIP/XML
metadata must be read together. Supported office-style document previews are
`.docx`, `.odt`, `.odp`, and `.odf`; they produce sanitized HTML plus extracted
text where available.

**Directories reuse `vv:content`** — no dedicated channel. When the opened path is a
directory, `vinary.main.service/send-content!` sends a listing instead of file text:

```clojure
{:path "/abs/path/dir"
 :kind "directory"
 :entries [{:name "report.md" :path "/abs/path/dir/report.md"
            :dir? false :size 8421 :mtime 1780000000000 :symlink false}
           …]
 :stamp 1780000000000}
```

The renderer stores `:entries` on the document entity as `:doc/entries` and renders
the in-pane directory browser. A depth-0 watcher re-sends the listing as children
change, exactly like a file's live refresh.

Archives also reuse `vv:content`: opening an archive returns `:kind "archive"` with
directory-browser-compatible `:entries`. Entry paths are virtual archive URIs rather
than extracted temporary files. Archive directories are represented as URI-chain
entries ending in `/`; archive members retain their full path within the current
archive layer:

```text
vv-archive://open?chain=%5B%22%2Fabs%2Fbundle.zip%22%2C%22logs%2Fapp.log%22%5D
```

The URI-bar display form is `file:///abs/bundle.zip!/logs/app.log`. Nested archives
append archive entry names to the encoded chain, for example:

```text
vv-archive://open?chain=%5B%22%2Fabs%2Fouter.zip%22%2C%22inner.tar.gz%22%2C%22logs%2Fapp.log%22%5D
```

The main process resolves the chain one archive layer at a time, never writes an
extracted entry to disk, and enforces bounded nested-archive depth and entry-size
limits before streaming the final entry to its preview.

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
| Raw Markdown HTML | sanitized — `rehype-raw` + `rehype-sanitize` (GitHub allowlist) |
| Renderer sandbox | tracked as a hardening item |
| Strict CSP | applied — `<meta>` in `index.html` |

The seam is intentionally broad enough for current app features but narrow
enough to audit. New privileged capabilities should be added in main, exposed as
small `window.vv` methods, and documented here and in the threat model.
