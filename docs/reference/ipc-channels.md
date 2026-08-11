# Reference · IPC Channels

> **What this is.** A lookup-form catalog of Electron Inter-Process
> Communication (IPC) channels between the **main** process and the renderer
> process. The architectural rationale is in
> [architecture/03-ipc-protocol.md](../architecture/03-ipc-protocol.md). The
> concrete renderer-facing API is `window.vv`, exposed by
> [`resources/preload.js`](../../resources/preload.js), which is the
> **authoritative** source for this catalog (95 distinct `vv:*` channels).

All renderer code talks to main through `window.vv`. The renderer never imports
`ipcRenderer`, `fs`, or `child_process` directly. A separate isolated preload,
[`resources/web-preload.js`](../../resources/web-preload.js), runs inside the
in-app HTTP page (see [§2.1](#21-web-view-preload-channels)).

Legend: **⮐** marks an `ipcRenderer.invoke` request/response channel (all others
are fire-and-forget `send`/`on`). Payloads are plain JSON-shaped data or EDN
**text** (see [§5](#5-envelope-and-conversion)).

---

## 1. Renderer → Main

### 1.1 Files, content, paging & streaming

| Channel | Preload method | Payload | Main receiver | Purpose |
|---------|----------------|---------|---------------|---------|
| `vv:open` | `open(path)` | `path` string | `service/open!` | Read a local or `ssh://`/`sftp://` file, send content and an available local/target tree, and start/reuse its watch ownership. The path alone is sent — an explicit type (ADR-0036) is looked up in main's doc-overrides registry, so Reload/facet/history re-opens keep it. A piped-stdin document (registry `:stdin?`) skips the tree and the watcher. |
| `vv:set-file-type` | `setFileType(req)` | `{path, kind, language?}` | `service` (registers via `doc-overrides/set-type!`) | Settings ▸ File Type (ADR-0036): register an explicit type for `path` (kind/language/delimiter replace as a unit; a piped document's stdin facts survive) and re-send it through the full open pipeline to every retaining window. |
| `vv:close` | `close(path)` | `path` string | `service/close!` | Compatibility close path; retained-file sync is the normal ownership path. |
| `vv:retained-files` | `syncRetainedFiles(paths)` | string array | `service/sync-retained!` | Replace the sending window's retained document-identity set (local paths and remote URIs). Watchers, authenticated remote subscriptions, and assets are released only after no live window retains the identity. |
| `vv:watch-assets` | `watchAssets(docPath, paths)` | `{docPath, paths}` | `service/watch-assets!` | Watch local media assets referenced by a retained Markdown/Org/LaTeX document. |
| `vv:tree-roots` | `syncTreeRoots(roots)` | root string array | `service/sync-tree-roots!` | Reconcile the sending window's Files projects; unoffered roots are rejected. Authenticated remote roots are forwarded to their target daemon owner. |
| `vv:tree-expanded` | `syncTreeExpanded(scopes)` | `[{root, path}]` | `service/sync-tree-expanded!` | Reconcile shared depth-0 watchers to effective expanded directories, locally or on an authenticated target daemon; collapse/unmount releases ownership. |
| `vv:tree-refresh` ⮐ | `refreshTree(request)` | `{root, path}` → `{root, files, synthetic?, scope?}` | `service/refresh-tree-request` | Re-list one directory beneath a visible, previously offered local or remote project root. |
| `vv:tree-refresh-all` ⮐ | `refreshAllTrees()` | none → tree payload array | `service/refresh-all-tree-requests` | Re-list every project root visible in the sending window. |
| `vv:content-page` ⮐ | `contentPage(request)` | `{path, kind, stamp, page, meta?}` → page payload with `hasPrev`/`hasNext` | `content_service.contentPage` | Fetch one bounded page for a large log or delimited-table preview. |
| `vv:stream-open` ⮐ | `streamOpen(req)` | `{path, mode:"lines"\|"bytes"}` → `{sessionId, size, mode}` | `content_service.streamOpen` | Open a bounded-memory pull-cursor stream session (local `fs` or SFTP read-stream). |
| `vv:stream-pull` ⮐ | `streamPull(req)` | `{sessionId}` → `{seq, done, progress, lines?\|text?, error?, partial?}` | `content_service.streamPull` | Pull the next batch (credit-1). `partial` flags a mid-stream drop (e.g. dropped SSH). |
| `vv:stream-close` ⮐ | `streamClose(req)` | `{sessionId}` → `{ok}` | `content_service.streamClose` | Close/destroy a stream session (also reaped on idle). |
| `vv:complete-path` ⮐ | `completePath(input)` | string → completion data | `service` (`complete` / `complete-remote`) | Path-completion for the URI bar: directory children + exact-match info (SFTP-aware). |
| `vv:load-pdf-bytes` ⮐ | `loadPdfBytes(path)` | `path` string → Buffer \| nil | `service` | Read a collocated sibling PDF's bytes into the renderer's pdf-cache (Document↔PDF switch) — **no** new tab. A remote sibling reads over SFTP. |
| `vv:load-diff-sources` ⮐ | `loadDiffSources(req)` | `{diffPath, files, includePaths?, includeContent?}` → legacy `{rel → content}` or structured `{rel → {path, content?}}` | `service` (`diff-source/load-local` / `loadRemoteDiffSources`) | Resolve a diff's referenced files (absolute paths directly; relative paths by walking ancestors). The opt-in structured response supports an eager path-only existence lookup for navigable headers and a lazy full-content lookup for Split enrichment; omitted options preserve the legacy content map. Remote diffs resolve over SFTP with canonical percent-encoded URIs. A **local structured request additionally triggers the ADR-0038 adoption push** — a second `vv:tree` send with the diff attached via `extras` — when the resolved targets prove a repository (`adopt-diff-project!`; guarded per window/diff, skipped for commit diffs carrying `:git`). |
| `vv:load-remote-asset` ⮐ | `loadRemoteAsset(req)` | `{uri, relativeTo}` → `data:` URL | `content_service.loadRemoteAsset` | Fetch a remote asset's bytes → a `data:` URL so a remote Markdown/Office doc's relative images render (the renderer cannot reach the host). |
| `vv:git-log` ⮐ | `gitLog(req)` | `{root, ref?, skip, limit, file?, follow?, lineRange?}` → `{commits: [{hash, parents, author-name, author-email, author-date, committer-date, refs, subject, body}], exhausted, empty?}` \| `{error}` | `git/handle-log` | One page of history (250/page; `--date-order` so children precede parents — the lane invariant). A given ref is rev-parse-verified before any log argv runs; an empty repository answers `empty: true`; `lineRange` is reserved for line-range history's own `-L` record discipline (an explicit error until that lands). ADR-0039. |
| `vv:git-branches` ⮐ | `gitBranches(req)` | `{root}` → `{head, detached?, branches: [{name, kind, current?}]}` \| `{error}` | `git/handle-branches` | Refs for the branch combo: local/remote/tag from `for-each-ref`, the current branch starred, a detached HEAD labeled by its short hash. |
| `vv:git-open-diff` ⮐ | `gitOpenDiff(req)` | `{root, from?, to, parent?, dots?, paths?}` → `{path, title}` \| `{error}` | `git/handle-open-diff` | Produce a commit diff as a spilled `.diff` document: both sides rev-parse-verified (a nil/`parent?` `from` resolves `to^`, root commits fall back to the empty tree — one plain `git diff` path), the text spilled to a private `$XDG_RUNTIME_DIR/vinary-viewer/git-diff/…/<shortA..shortB>.diff` registered in doc-overrides as `{:kind "diff" :stdin? true :cwd root :git {…}}` (MRU-excluded, unlinked with retention, tree-adoption opt-out, rev-aware enrichment). The **renderer** opens the returned path (`[:tab/navigate]` — main never creates tabs); re-activating the same range returns the existing spill. |
| `vv:git-watch` | `gitWatch(roots)` | root string array | `git/sync-owner-roots!` | Replace the sending window's watched-repo set (the Commits panel syncs on mount/root-change/unmount; `[]` releases). Last owner out closes that repo's `.git` watcher. |
| `vv:git-blame` ⮐ | `gitBlame(req)` | `{file, root?}` → `{root, hunks: [{hash, orig-line, final-line, count, author-name, author-email, author-date, summary, boundary?, uncommitted}]}` \| `{error}` | `git/handle-blame` | Blame the WORKING TREE of one local file (`blame --line-porcelain`, parsed and coalesced main-side — hunks are ~100× smaller than porcelain). The repo is re-derived from the file's own directory; uncommitted lines arrive as the zero hash; a non-repo file answers with an honest error. ADR-0040. |

### 1.2 Config & persistence

| Channel | Preload method | Payload | Main receiver | Purpose |
|---------|----------------|---------|---------------|---------|
| `vv:keymap-request` | `requestKeymap()` | none | `config/push!` | Request persisted `keybindings.edn`. |
| `vv:keymap-save` | `saveKeymap(edn)` | EDN string | `config/save!` | Persist keybinding registry EDN. |
| `vv:grammars-request` | `requestGrammars()` | none | `grammars/push!` | Request user grammar registry and filetype mappings. |
| `vv:settings-request` | `requestSettings()` | none | `settings/push!` | Request persisted `settings.edn`. |
| `vv:settings-save` | `saveSettings(edn)` | EDN string | `settings/save!` | Persist settings EDN. |
| `vv:recent-request` | `requestRecent()` | none | `recent/push!` | Request persisted `recent.edn` (trail + recent-files + web-history). |
| `vv:recent-save` | `saveRecent(edn)` | EDN string | `recent/save!` | Persist recent-navigation EDN (debounced 300 ms in the renderer). |
| `vv:ext-config-request` / `vv:ext-config-save` | `requestExtConfig()` / `saveExtConfig(edn)` | none / EDN | `ext-config` | Request / persist `extensions.edn` (ad-block + extension prefs). |
| `vv:connections-request` | `requestConnections()` | none | `connections/push!` | Request persisted `connections.edn` (non-secret SSH host entries). |
| `vv:connections-save` | `saveConnections(edn)` | EDN string | `connections/save!` | Persist SSH connection metadata EDN. |

### 1.3 In-app HTTP web view

| Channel | Preload method | Payload | Main receiver | Purpose |
|---------|----------------|---------|---------------|---------|
| `vv:http-show` | `httpShow(url, bounds, tabId)` | `{url, bounds, tabId}` | `web/show!` | Show/load the in-app HTTP `WebContentsView`. `tabId` is the **owning** tab, so the navigation relay updates that tab even after the user switches away mid-load. |
| `vv:http-hide` | `httpHide()` | none | `web/hide!` | Hide the in-app HTTP view. |
| `vv:http-bounds` | `httpBounds(bounds)` | `{bounds}` | `web/set-bounds!` | Reposition the in-app HTTP view. |
| `vv:http-snapshot` ⮐ | `httpSnapshot()` | none → JPEG data-URL | `web` (cached `capturePage`→`toJPEG`) | Cold-cache fallback raster: freeze the page under an overlay while the always-on-top native view hides. |
| `vv:http-toc-goto` | `httpTocGoto(id)` | heading id string | `web` → web preload `vv:web-scroll-to` | Scroll the HTTP page to a heading. |
| `vv:http-scroll` | `httpScroll(kind)` | kind string (`page-down`/`page-up`/`home`/`end`) | `web` → web preload `vv:web-scroll` | Forward page/edge keys to the web page when app chrome holds focus (native view visible). |
| `vv:http-zoom` / `vv:http-zoom-set` | `httpZoom(dir)` / `httpZoomSet(f)` | direction / factor | `web` (`setZoomFactor`) | Zoom the web PAGE (the native view's own webContents), not the app chrome; reports on `vv:zoom-changed`. |

### 1.4 SSH / SFTP & connections

| Channel | Preload method | Payload | Main receiver | Purpose |
|---------|----------------|---------|---------------|---------|
| `vv:ssh-prompt-reply` | `sshPromptReply(promptId, secret)` | `{promptId, secret}` | `ssh` (`reply!`) | The **only** secret-bearing renderer→main channel: return the user-typed password / key passphrase / MFA answer for a pending prompt. One-shot, resolved into a main-memory promise, **never persisted or placed in app-db**. |
| `vv:ssh-close-connection` | `sshCloseConnection(connKey)` | `connKey` string | `ssh` → `transport.closeConnection` | Close a pooled SSH connection by its `user@host:port` key. |

> Persisted (non-secret) connection metadata rides `vv:connections-request` /
> `vv:connections-save` in [§1.2](#12-config--persistence).

### 1.5 Native password-manager bridge

| Channel | Preload method | Payload | Main receiver | Purpose |
|---------|----------------|---------|---------------|---------|
| `vv:password-state-request` | `passwordState()` | none | `passwords` | Refresh provider status for the native password-manager bridge. |
| `vv:password-search` | `passwordSearch(url)` | URL string | `passwords` | Search ready providers for logins matching the current web origin. Results are sanitized metadata only. |
| `vv:password-fill` | `passwordFill(item)` | `{provider, id, vault-id?, …}` metadata | `passwords` | Reveal the selected item in main, then send credentials directly to the web-view preload. Passwords do not cross into renderer app-db. |
| `vv:password-save` | `passwordSave(payload)` | `{token, provider}` | `passwords` | Save a short-lived main-memory login candidate into the chosen provider (the token references the secret; the renderer never carries the password). |
| `vv:password-dismiss-save` | `passwordDismissSave(token)` | token string | `passwords` | Drop an unsaved login candidate from main memory. |

### 1.6 Extensions & ad-blocking

| Channel | Preload method | Payload | Main receiver | Purpose |
|---------|----------------|---------|---------------|---------|
| `vv:ext-state-request` | `extState()` | none | `extensions` | Request the installed-extensions state push. |
| `vv:ext-install` / `vv:ext-remove` | `extInstall(idOrUrl)` / `extRemove(id)` | string | `extensions` | Install (Web-Store id/URL) / uninstall an extension. |
| `vv:ext-set-enabled` | `extSetEnabled(id, on)` | `{id, on}` | `extensions` | Enable/disable a loaded extension. |
| `vv:ext-check-updates` | `extCheckUpdates()` | none | `extensions` | Trigger a Web-Store update check. |
| `vv:ext-action-clicked` / `vv:ext-popup-close` | `extActionClicked(id, popup, bounds)` / `extPopupClose()` | `{id, popup, bounds}` / none | `ext-popup` | Open / close a browser-action popup. |
| `vv:adblock-set-enabled` / `vv:adblock-set-lists` / `vv:adblock-refresh` | `adblockSetEnabled(on)` / `adblockSetLists(kw)` / `adblockRefresh()` | bool / string / none | `adblock` | Toggle / set list-set / refresh the ad-blocker. |

### 1.7 Shell, window & zoom

| Channel | Preload method | Payload | Main receiver | Purpose |
|---------|----------------|---------|---------------|---------|
| `vv:open-dialog` | `openDialog(defaultPaths)` | candidate paths (`string[]`) | `shell` | Open the multi-file native dialog, seeded to the active/most-recent folder — main's `seeds->dir` opens in the first candidate that resolves (`seed-dir`: file → its parent, dir → itself; else the OS home dir). |
| `vv:clipboard-write` | `copyText(text)` | string | `shell` | Copy text to the OS clipboard. |
| `vv:open-path` | `openPath(path)` | string | `shell` | Ask the OS to reveal/open a local path. |
| `vv:open-external` | `openExternal(url)` | string | `shell` | Ask the OS to open an external URL. |
| `vv:app-info-request` | `requestAppInfo()` | none | `shell` | Request app metadata for the About UI. |
| `vv:quit` | `quit()` | none | `shell` | Quit the Electron app. |
| `vv:devtools` | `toggleDevtools()` | none | `shell` | Toggle renderer devtools. |
| `vv:zoom` / `vv:zoom-set` | `zoom(dir)` / `zoomSet(f)` | direction / factor | `shell` | Adjust the app-window zoom (DOM views); reports the new factor on `vv:zoom-changed`. |

### 1.8 Retired native-PDF seam

| Channel | Preload method | Payload | Main receiver | Purpose |
|---------|----------------|---------|---------------|---------|
| `vv:pdf-show` / `vv:pdf-hide` / `vv:pdf-bounds` | `pdfShow` / `pdfHide` / `pdfBounds` | `{path, bounds}` / — / `{bounds}` | *RETIRED* | Native PDF view seam — retired for in-renderer pdf.js (ADR-0013). The preload methods still exist as **harmless no-ops**; `main.pdf/init!` is commented out in `core.cljs`, so these channels have **no live listener**. |

`bounds` payloads are plain maps with numeric `x`, `y`, `width`, and `height`
fields derived from `getBoundingClientRect()`.

---

## 2. Main → Renderer

Each `window.vv.on*(cb)` subscription returns an unsubscribe function (see
[§4](#4-the-on--unsubscribe-contract)).

### 2.1 Content, tree & config

| Channel | Preload subscription | Payload | Renderer event/effect | Purpose |
|---------|----------------------|---------|-----------------------|---------|
| `vv:content` | `onContent(cb)` | `{path, kind, stamp, text?, html?, bytes?, entries?, sheets?, page?, meta?, dataUrl?, sourceable?, paged?, pdfSibling?, sourceSibling?, language?, stdin?, baseDir?}` | `[:content/received …]` | Deliver initial and live-refreshed document content. PDFs carry `:bytes`; directories/archives carry `:entries`; large logs/tables carry the first `:page`; a doc collocated with an exported PDF carries `:pdfSibling` (and a PDF its `:sourceSibling`). ADR-0036 adds three optional keys: `language` (an explicit source grammar → `:doc/language`, the renderer's grammar pick), `stdin` (a piped document — excluded from the Recent MRU), and `baseDir` (the invoking cwd a piped document's relative assets and diff sources resolve against → `:doc/base-dir`). |
| `vv:error` | `onError(cb)` | `{path?, message, stamp?}` | `[:content/error …]` | Deliver read/render errors. |
| `vv:tree` | `onTree(cb)` | `{root, files, synthetic?, scope?, extras?}` | `[:tree/received …]` | Deliver a full project listing or a scoped automatic update. `root` may be local or `ssh://`/`sftp://`; `scope` and files remain root-relative. `synthetic? true` marks an inferred non-git root. `extras` = `[{path, name, kind, transient?}]`, attached documents ADR-0038 adopts into a project (a diff listed inside the repository it describes; absolute `path`, pinned rendering, `transient?` extras pruned with document retention) — full entries only, never on scoped payloads. |
| `vv:git-changed` | `onGitChanged(cb)` | `{root}` | Commits surfaces (`[:commits/git-changed …]`) | Debounced (300 ms) notice that a repository's `HEAD`/`refs`/`packed-refs` changed — a commit, fetch, tag, or branch switch. Watch targets resolve via `rev-parse --absolute-git-dir`/`--git-common-dir`, so worktrees notify correctly; ownership arrives over `vv:git-watch`. ADR-0039. |
| `vv:keymap` | `onKeymap(cb)` | EDN **text** (registry) | `[:keymap/config-received …]` | Deliver persisted keybinding config. |
| `vv:grammars` | `onGrammars(cb)` | EDN **text** `{:grammars [...] :filetypes {...}}` | `syntax/register-user!` | Deliver user tree-sitter grammar entries and filetype mappings. |
| `vv:settings` | `onSettings(cb)` | EDN **text** | `[:settings/received …]` | Deliver persisted settings. |
| `vv:recent` | `onRecent(cb)` | EDN **text** | `[:recent/received …]` | Deliver persisted recent-navigation state (`{:trail … :recent-files [...] :web-history [...]}`). |
| `vv:ext-config` | `onExtConfig(cb)` | EDN **text** | `[:ext-config/received …]` | Deliver persisted ad-block + extension prefs. |
| `vv:connections` | `onConnections(cb)` | EDN **text** | `[:connections/received …]` | Deliver persisted (non-secret) SSH connection metadata. |

### 2.2 In-app web view

| Channel | Preload subscription | Payload | Renderer event/effect | Purpose |
|---------|----------------------|---------|-----------------------|---------|
| `vv:http-navigated` | `onHttpNavigated(cb)` | `{url, tab}` | `[:http/navigated …]` | Record in-app HTTP navigation onto the **owner** tab's history (`tab` may not be the active tab — a slow page can finish after the user switches away). |
| `vv:http-snapshot-ready` | `onHttpSnapshotReady(cb)` | JPEG data-URL | `web-host` (`pre-snap`) | Push a fresh page raster (captured after load + on scroll-settle) so opening any overlay freezes the page instantly. |
| `vv:web-toc` | `onWebToc(cb)` | heading vector | `[:web/toc …]` | Deliver the heading outline from the HTTP web view (relayed from its preload). |
| `vv:web-active-heading` | `onWebActiveHeading(cb)` | heading id or nil | `[:web/active-heading …]` | Deliver the active HTTP heading (scroll-spy). |
| `vv:web-key` | `onWebKey(cb)` | `{key, ctrl, shift, alt, meta}` | replayed as a synthetic `window` keydown → keymap resolver | Forward an app-global Ctrl/Cmd chord from the (separate-context) web view so the keymap runs the same command. Page editing/clipboard chords stay with the page. |
| `vv:history-nav` | `onHistoryNav(cb)` | `"back"` or `"forward"` | history dispatch | Forward browser-like navigation from native/web surfaces. |

### 2.3 Open, app-info & zoom

| Channel | Preload subscription | Payload | Renderer event/effect | Purpose |
|---------|----------------------|---------|-----------------------|---------|
| `vv:open-files` | `onOpenFiles(cb)` | `{paths, focus-first?}` | `[:files/opened …]` | Deliver file/URI selections from the native Open dialog **or** the command-line arguments at launch (each opened in its own tab). `focus-first` (command-line launch) re-focuses the first path's tab. |
| `vv:app-info` | `onAppInfo(cb)` | app metadata map | `[:app-info/received …]` | Deliver app metadata for the About dialog. |
| `vv:zoom-changed` | `onZoomChanged(cb)` | `{:context "window"\|"web" :factor n}` | `[:view/zoom-changed …]` | Report the resolved app-window / web-view zoom factor so the zoom bar shows the live %. |

### 2.4 Extensions & ad-blocking

| Channel | Preload subscription | Payload | Renderer event/effect | Purpose |
|---------|----------------------|---------|-----------------------|---------|
| `vv:ext-state` | `onExtState(cb)` | EDN **text** | `[:ext/state-received …]` | Deliver the installed-extensions list + master toggle. |
| `vv:ext-install-result` / `vv:ext-update-result` | `onExtInstallResult` / `onExtUpdateResult` | object | `[:ext/install-result …]` / `[:ext/update-result …]` | Install / update outcome. |
| `vv:adblock-status` | `onAdblockStatus(cb)` | `{:status :updating\|:ok\|:offline\|:error :last-updated? :error?}` | `[:adblock/status-received …]` | Ad-block refresh feedback for the Extensions dialog. |

### 2.5 Native password bridge

| Channel | Preload subscription | Payload | Renderer event/effect | Purpose |
|---------|----------------------|---------|-----------------------|---------|
| `vv:password-state` | `onPasswordState(cb)` | `{providers, forms, busy?, error?}` | `[:passwords/state-received …]` | Provider status and web-form presence. |
| `vv:password-items` | `onPasswordItems(cb)` | `{url, origin, items}` | `[:passwords/items-received …]` | Sanitized login matches (provider/id/title/username/url metadata, never password fields). |
| `vv:password-save-prompt` | `onPasswordSavePrompt(cb)` | `{token, url, origin, username, providers}` or null | `[:passwords/save-prompt …]` | Prompt the renderer to save a login candidate held only in main memory. Null closes the prompt. |
| `vv:password-result` | `onPasswordResult(cb)` | `{ok, action, message}` | `[:passwords/result-received …]` | Fill/save result message. |

### 2.6 SSH / SFTP

| Channel | Preload subscription | Payload | Renderer event/effect | Purpose |
|---------|----------------------|---------|-----------------------|---------|
| `vv:ssh-prompt` | `onSshPrompt(cb)` | `{promptId, kind, host, user, connKey, keyPath?, prompt?, echo?, attempt}` | `[:ssh/prompt …]` | A **non-secret** auth-prompt request (password / passphrase / keyboard-interactive). The reply's secret returns on `vv:ssh-prompt-reply`; the prompt modal holds the secret locally. |
| `vv:ssh-status` | `onSshStatus(cb)` | `{connKey, host, state}` | `[:ssh/status …]` | Connection status (connecting/ready/closed). |
| `vv:ssh-error` | `onSshError(cb)` | `{connKey, host, port, kind, message}` | `[:ssh/error …]` | A connection/transport error (host-key rejected, SFTP error, …). |

---

## 2.1 Web-View Preload Channels

These channels involve `resources/web-preload.js`, which runs in the isolated
in-app HTTP page. It exposes **no** API to the remote page — it only observes the
DOM and relays to main, which forwards to the app renderer where relevant.

| Channel | Direction | Payload | Peer | Purpose |
|---------|-----------|---------|------|---------|
| `vv:web-toc` | web preload → main → renderer | heading vector | `web.cljs` relay → `[:web/toc …]` | Report the page heading outline (re-sent to the app renderer on the same channel name). |
| `vv:web-active-heading` | web preload → main → renderer | heading id or nil | `web.cljs` relay → `[:web/active-heading …]` | Report the scroll-spy active heading. |
| `vv:web-scroll-to` | main → web preload | heading id string | `web-preload.js` | App TOC click → scroll the page to a heading (sent by `web.cljs` on `vv:http-toc-goto`). |
| `vv:web-scroll` | main → web preload | kind (`page-down`/`page-up`/`home`/`end`) | `web-preload.js` | App-forwarded page/edge keys when app chrome holds focus (sent by `web.cljs` on `vv:http-scroll`). |
| `vv:password-forms` | web preload → main | `{url, origin, count, hasPassword}` | `passwords` | Report login-form presence so the app can show the key-icon badge. |
| `vv:password-save-candidate` | web preload → main | `{url, origin, username, password}` | `passwords` | Store a submitted login candidate in main memory behind a short-lived token. |
| `vv:password-fill` | main → web preload | `{username, password, url?}` | `web-preload.js` | Fill the selected login directly into DOM fields and fire `input`/`change`. *(Same channel name as the renderer→main `vv:password-fill` in [§1.5](#15-native-password-manager-bridge), but a distinct sender/receiver context.)* |

---

## 3. Content Kinds

`vv:content` uses the file classifier in `vinary.main.file-kind/kind-of`, except
for directories, which `vinary.main.service/send-content!` detects first (via
`directory?`) and lists rather than reading as text:

| Extension(s) | `kind` | Payload detail |
|---------------|--------|----------------|
| any path that is a directory | `"directory"` | not read as text; payload carries `:entries [...]`, each `{:name :path :dir? :size :mtime :symlink}`. Stored as `:doc/entries`, rendered in-pane by the directory browser. |
| `.md`, `.markdown`, `.mdx` | `"markdown"` | text is read and rendered through the common IR in the renderer. |
| `.org` | `"org"` | text is read and rendered through the common IR via uniorg (HTML + heading TOC + assets), like Markdown. |
| `.tex`, `.latex`, `.ltx` | `"latex"` | text is read and rendered through the common IR via unified-latex (batch render). `.sty`/`.cls`/`.bib` are **not** here — they render as highlighted source. |
| `.diff`, `.patch` | `"diff"` | text is read and rendered by the diff IR front-end: a colored unified view plus an on-demand side-by-side (split) view enriched from on-disk sources. |
| image extensions such as `.png`, `.jpg`, `.svg`, `.webp`, `.avif` | `"image"` | binary is not read as text; renderer uses a `file://` URL (a remote image arrives as `:dataUrl`). |
| `.pdf` | `"pdf"` | bytes streamed to the renderer (`:bytes`) and rendered in-DOM by pdf.js (ADR-0013); never read as text. |
| `.html`, `.htm`, `.xhtml` | `"html"` | not read as text; rendered as a live page in the in-app web view via its `file://` URL, with the ad-blocker + extensions applied. |
| `.docx`, `.odt`, `.odp`, `.odf` | `"office"` | parsed in main into sanitized HTML/text preview payloads, then rendered through the common IR. |
| `.csv`, `.tsv`, `.tab`, `.psv`, `.dsv`, `.xlsx`, `.xlsm`, `.ods`, `.fods` | `"table"` | delimited files render as bounded rows (large files page through `vv:content-page`); workbooks render capped sheet matrices. |
| `.log`, `.out`, `.err`, `.trace`, compressed `.log.gz` / rotated `.log.N.gz`, standard log basenames, and sniffed log-like text | `"log"` | rendered as lines with tolerant severity highlighting; large logs page through `vv:content-page` or stream through `vv:stream-*`. |
| `.zip`, `.jar`, `.war`, `.ear`, `.epub`, `.tar`, `.tgz`, `.tar.gz` and `vv-archive://…` | `"archive"` | listed in-pane as directory-browser-compatible entries; nested archive entries are addressed by virtual archive URI chains and are not extracted to disk. |
| `.mmd`, `.mermaid` | `"mermaid"` | text is read and rendered to SVG by the renderer-side Mermaid strategy. |
| bundled/user source extensions, configured filetype mappings, well-known repo files, and non-Mermaid diagram-source extensions | `"source"` | text is read into the read-only CodeMirror source view. |
| everything else | `"text"` | plain text routes through the content service first so extensionless syslogs can be LOG-sniffed; otherwise text is escaped into plain HTML **literally** — the delimiter sniff that used to flip prose into a column-aligned table is disabled (ADR-0036; an explicit `-t csv` / Settings ▸ File Type ▸ Delimited Table is the sanctioned path). |

`ssh://` and `sftp://` URIs classify off the same basename extension (served by
the async SFTP reader instead of the local filesystem).

An **explicit type** (ADR-0036 — CLI `-t/--type`, Settings ▸ File Type) overrides this classifier
entirely: main's doc-overrides registry supplies the kind on every send (so watcher live-refreshes
cannot revert it), and the content service treats it as authoritative — no `classifyName`, no content
sniffing, delimited-by-declaration for an explicit `table` (`meta.delimiter` carries `-t tsv`'s tab).

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
| main → renderer | ClojureScript data is converted with `clj->js`, or serialized as EDN **text** where keyword fidelity matters (`vv:keymap`, `vv:grammars`, `vv:settings`, `vv:recent`, `vv:ext-config`, `vv:connections`). |
| renderer receive | Renderer converts JavaScript payloads with `js->clj :keywordize-keys true`, or parses EDN text with `cljs.reader`. |
| renderer → main | Preload sends plain strings, arrays, objects, numbers, booleans, or EDN strings. |

Plain structured data is mandatory because Electron IPC and `contextBridge`
cross V8 context boundaries; ClojureScript persistent structures do not cross
that boundary directly.

---

## 6. Not Exposed

The renderer does not receive direct access to `fs`, arbitrary file reads,
`ipcRenderer`, `child_process`, or Node `require`. SSH/password secrets stay
main-side by construction (`vv:ssh-prompt-reply` is the sole secret-bearing
renderer→main channel, and it is one-shot and never persisted). See
[architecture/03-ipc-protocol.md §8](../architecture/03-ipc-protocol.md#8-what-is-intentionally-not-exposed)
and [security/threat-model.md](../security/threat-model.md).
