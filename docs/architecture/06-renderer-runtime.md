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

| Hook | Purpose |
|------|---------|
| `window.__vvdb()` | Current app-db as JS data. |
| `window.__vvds()` | Current DataScript cached document rows. |
| `window.__vvkeymap(id)` | Development helper to select a keymap set. |

Debug builds also preload re-frame-10x, re-frisk, and DevTools formatters.

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

---

## 7. See also

- [04-state-schema-reference.md](04-state-schema-reference.md)
- [05-data-flows.md](05-data-flows.md)
- [../features/10-scroll-spy-toc.md](../features/10-scroll-spy-toc.md)
- [../diagrams/component-renderer.puml](../diagrams/component-renderer.puml)
