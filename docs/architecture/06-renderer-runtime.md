# 06. Renderer Runtime

This page describes the renderer boot sequence, subscription graph, Markdown body
mounting, scroll/TOC runtime, and dev inspection hooks.

---

## 1. Boot sequence

`vinary.renderer.core/init` runs the renderer:

```clojure
(defn ^:export init []
  (rf/dispatch-sync [:db/init])
  (ds/install-bridge!)
  (install-dev-hooks!)
  (bridge!)
  (keybindings!)
  (mount!))
```

Ordering matters:

| Step | Why |
|------|-----|
| `dispatch-sync [:db/init]` | app-db exists before subscriptions, IPC callbacks, or views run. |
| `ds/install-bridge!` | DataScript transactions can bump `:ds/rev` from the first content event. |
| Dev hooks | Console inspection works before user interaction. |
| `bridge!` | Main-to-renderer IPC is translated into re-frame events. |
| `keybindings!` | Installs the modal/chord resolver exactly once. |
| `mount!` | Creates/reuses the React root and renders `[views/root]`. |

Hot reload calls `mount!` again and reuses app-db, the DataScript connection, and
the React root.

---

## 2. Dev hooks

The renderer installs these on `window` in `vinary.renderer.core/init` for inspection and for the test
harnesses (the Electron smoke, the real-main e2e suites and the benchmarks drive the app through them):

| Hook | Purpose |
|------|---------|
| `window.__vvdb()` | Current app-db as JS data. |
| `window.__vvds()` | Current DataScript cached document rows. |
| `window.__vvdeck(path)` | The `:doc/deck` summary a PDF view reported for `path` — the classification, and the notes model under `notes` (ADR-0048), as JS data (keywords become strings). |
| `window.__vvkeymap(id)` | Select a keymap set. |
| `window.__vvopen(path)` | Open a path in the active tab (`[:doc/open path]`). |
| `window.__vvsource()` | Flip the active tab's facet to its source (`[:tab/toggle-source]`). |
| `window.__vvblame()` | Toggle the blame gutter (`[:blame/toggle]`, ADR-0040). |
| `window.__vvslides(op, arg)` | Drive the slide-deck mode (ADR-0045): `__vvslides('next')`, `__vvslides('goto', 3)` → `[:slides/command …]`; `__vvslides('layout', 'slides' \| 'document' \| null)` → `[:pdf/set-layout …]`. |
| `window.__vvfindtoggle()` | Open or close the find bar (`[:find/toggle]`). |
| `window.__vvfind()` | What find matched: the text, each match's geometry, whether it is on screen, the painted ranges. |
| `window.__vvpending(key)` | Whether a keyed debounce is still armed (`"find/search"`, `"tree/filter"`, …), so a latency test can wait for the app to go quiet. |

**Debug builds only** (gated on `goog.DEBUG`; the release smoke asserts they are absent): `window.__vvscrolltrace()`
and `window.__vvinputtrace()` (who wrote `scrollTop`, and the typist's input trace — docs/scientific/09, 10),
`window.__vvwarmcache()` (warm-cache statistics), `window.__vvscrollanim()` and `window.__vvscrollanimlog()` (the
smooth-scroll animator's state and log).

Debug builds also preload re-frame-10x and DevTools formatters (the renderer
`:preloads` are `[devtools.preload day8.re-frame-10x.preload.react-18]`).

---

## 3. React/Reagent root

`mount!` creates a React root once:

```clojure
(defonce root (atom nil))

(defn mount! []
  (when (nil? @root)
    (reset! root (rdomc/create-root (.getElementById js/document "app"))))
  (rdomc/render @root [views/root]))
```

The renderer uses React 19 through Reagent.

---

## 4. Markdown body lifecycle

The app shell is declarative Reagent. Rendered Markdown content is intentionally
inserted as HTML into a host node:

```clojure
(defn markdown-body [_html _source _path]
  (r/create-class
   {:component-did-mount  ...
    :component-did-update ...
    :reagent-render
    (fn [_html _source _path]
      [:div.markdown-body {:ref ...}])}))
```

On mount/update, the component writes `innerHTML`, then runs post-layout work:

1. Apply rendered HTML.
2. Scale embedded local SVG figures if measurements changed.
3. Refresh cached heading offsets.
4. Apply pending scroll restore.

The renderer guards async post-render work with a render token so stale work from
an older document/render cannot apply to the current view.

`markdown-body` is one branch of the `vinary.ui.views/content-view` **Strategy**
selector, which picks the renderer for the active tab by URI scheme and `:doc/kind`
(empty → watermark, error, web view, in-renderer PDF, image, mermaid, source, View-Source,
a **streamed body** (`ir-stream-body`) for large documents, a **diff** view, and so on —
see [theory/05-strategy-renderer-registry.md](../theory/05-strategy-renderer-registry.md)).
A `:doc/kind = "directory"` document selects `vinary.ui.views/dir-view`, the in-pane
**directory browser**: a pure-Reagent detailed list (name · size · modified) over the
document's `:doc/entries` that, like the image view, restores per-history scroll on
mount/update. `Enter` opens the highlighted entry (as do `Alt+Down` / Vim `J`), while
bare arrow keys fall through to the global smooth pane-scroll.

---

## 5. Subscription graph

| Subscription family | Source | Examples |
|---------------------|--------|----------|
| UI subscriptions | app-db | `:ui/theme`, `:ui/settings`, `:ui/find`, `:keymaps/*`, `:history/*`. |
| Navigation subscriptions | app-db via `vinary.app.nav` | `:tabs`, `:ui/active-path`, `:ui/active-view-source?`. |
| Content subscriptions | DataScript gated by `:ds/rev` | `:doc/active`, `:doc/toc`. |
| Web subscriptions | app-db | `:web/toc`, `:web/active-heading`. |

DataScript-reading subscriptions do not store duplicated content in app-db. They
use `:ds/rev` as an invalidation signal and read the current snapshot.

---

## 6. TOC and scroll runtime

Markdown TOC data is stored as `:doc/toc` during render. Scroll-spy uses live DOM
measurements:

1. Heading offsets are refreshed after Markdown HTML and figure sizing settle.
2. Scroll events are coalesced with `requestAnimationFrame`.
3. The active heading is found with binary search over cached offsets.
4. `[:toc/active-heading id]` updates `[:ui :active-heading]`.

HTTP/HTTPS web content uses `resources/web-preload.js` to run the same idea
inside the web view and report headings/active heading to the renderer.

**One scroll owner, and a delegate.** Every scroll command — the bare arrows and page keys of `key-scroll!`, Vim's
and Emacs's motions, the palette's — reaches the `:dom/scroll` effect as one options map and moves `.vv-content`,
the single confined scroller (ADR-0032). `vinary.renderer.scroll` lets one view claim those commands instead: a PDF
in the **Slides layout** registers a delegate that turns each into a slide step (`deck/scroll->op`) and runs it on
the engine (ADR-0045 §4); unmounting clears it, identity-checked. Keys that only move the view run inside their key
handler (`commands/dispatch-now!` → `rf/dispatch-sync`), so the move shows in the very next frame
(docs/scientific/12 §8).

**The slides runtime** lives in the pdf.js engine (`vinary.renderer.pdf`): the layout, the slots, the current slide
and the jump history are engine state, mirrored into `[:ui :slides]` at most once per frame (`:slides/changed`) for
the menus, the slide bar and the tests; `vinary.renderer.pdf-cache` holds the hooks other surfaces use (the slides
snapshot the overview borrows, the slide to restore after Back/Forward) and the PDF byte cache's good and candidate
versions. See [feature 35](../features/35-slide-shows.md).

---

## 7. See also

- [04-state-schema-reference.md](04-state-schema-reference.md)
- [05-data-flows.md](05-data-flows.md)
- [../features/10-scroll-spy-toc.md](../features/10-scroll-spy-toc.md)
- [../diagrams/component-renderer.puml](../diagrams/component-renderer.puml)
