# 05. Data Flows

This page traces the current high-value user flows through main, preload,
re-frame events/effects, DataScript, and views.

---

## 1. Open a local file

1. User launches `vv README.md`, uses `File > Open`, clicks the Files tree, or
   follows a local link.
2. Renderer dispatches `:doc/open`, `:doc/open-new`, `:tab/navigate`, or an open
   dialog event.
3. Navigation code updates app-db tabs/history and computes retained paths.
4. Local file destinations emit `[:vv/open path]`.
5. Preload sends `vv:open`.
6. Main `service/open!` reads/classifies the path, sends `vv:content`, sends
   `vv:tree` when a git root is available, and ensures the file is watched.
7. Renderer receives `vv:content` and dispatches `[:content/received payload]`.
8. `:content/received` caches content in DataScript and starts Markdown render
   when needed.
9. `:content/rendered` stores `:doc/html`, `:doc/toc`, and `:doc/assets`.
10. Subscriptions recompute through `:ds/rev`, and the content view paints.

---

## 2. Live edit refresh

1. User saves a retained local file.
2. Main watcher fires on `change` or `add` after `awaitWriteFinish`.
3. Main re-sends `vv:content` for the same path.
4. Renderer updates the existing content entity keyed by `:doc/path`.
5. Markdown render uses the payload stamp; stale async render results are ignored.
6. `:doc/html`, `:doc/toc`, and `:doc/assets` update.
7. UI state in app-db is untouched except for the `:ds/rev` signal.

Result: the preview repaints without resetting tabs, history, find state, theme,
keybindings, or scroll.

---

## 3. Tab navigation

| Event | Flow |
|-------|------|
| `:tab/navigate` | Save leaving scroll, push active tab history entry, load local file if needed, sync retained paths. |
| `:tab/open` | Save current scroll, create a new active tab, load destination, sync retained paths. |
| `:tab/activate` | Save leaving scroll, activate target tab id, restore target scroll when local, sync retained paths. |
| `:history/back` / `:history/forward` | Step active tab history, load destination if local, restore saved scroll, sync retained paths. |
| `:tab/close` | Remove tab, activate neighbor when needed, sync retained paths and evict unretained cached docs. |

Retained sync is deliberately computed after navigation transforms, so main sees
the complete ownership set derived from all remaining tab histories.

---

## 4. Markdown render and asset watch

1. `:content/received` sees `kind = "markdown"` and emits `:markdown/render`.
2. `vinary.renderer.markdown/render` runs unified/remark/rehype.
3. During HAST processing, render metadata is collected:
   - `:toc` heading entries,
   - `:assets` local embedded asset paths.
4. `:content/rendered` writes `:doc/html`, `:doc/toc`, and `:doc/assets`.
5. `:vv/watch-assets` tells main which embedded local assets belong to the
   Markdown document.
6. When an asset changes, main re-sends the owning Markdown document so cache-bust
   URLs and figure sizing refresh.

---

## 5. PDF and HTTP native views

PDF and HTTP/HTTPS content are main-owned native views layered over a renderer
host element.

| Kind | Renderer role | Main role |
|------|---------------|-----------|
| PDF | Mount `pdf-host`, send show/hide/bounds messages. | Own `WebContentsView`, load `file://...pdf`, reload on retained PDF change. |
| HTTP/HTTPS | Mount web host, send show/hide/bounds/toc-goto messages. | Own web view, report navigation, headings, and active heading. |

The renderer still owns tabs and history. The native views own their own document
scrolling.

---

## 6. Settings and keybindings

Settings:

1. Main watches `~/.config/vinary-viewer/settings.edn`.
2. Renderer requests settings and subscribes to `vv:settings`.
3. `:settings/received` merges EDN into app-db and applies theme/fonts.
4. UI edits dispatch `:settings/set`, apply immediately, and save EDN through
   `vv:settings-save`.

Keybindings:

1. Main watches `~/.config/vinary-viewer/keybindings.edn`.
2. Renderer requests keymap config and subscribes to `vv:keymap`.
3. `:keymap/config-received` normalizes the registry envelope in app-db.
4. `:keymap/install-active` installs the active set into the resolver.
5. Menu/editor changes save through `vv:keymap-save`.

---

## 7. Error flow

| Error source | Flow |
|--------------|------|
| Main read failure | `vv:error` -> `:content/error` -> `:doc/error`. |
| Markdown render failure | render promise catch -> `:content/error` -> `:doc/error`. |
| Next successful content | `:content/received` retracts stale `:doc/error`. |

The content view gives `:doc/error` precedence over stale HTML until a successful
content path clears it.
