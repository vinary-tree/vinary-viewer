# 04. State Schema Reference

This page describes the current state model: app-db owns UI/navigation, DataScript
owns cached document content, and main owns OS/native resources.

---

## 1. Store split

| Store | Owns | Does not own |
|-------|------|--------------|
| re-frame `app-db` | Tabs, active tab, per-tab histories, saved scroll entries, sidebar state, settings UI, keybinding UI, find state, TOC active heading, command palette state. | Loaded document text/html. |
| DataScript | Cached content entities keyed by `:doc/path`. | Tab identity, active tab, history stacks. |
| Main process atoms/native objects | Watchers, asset watchers, PDF view, web view, config watchers. | Renderer UI state. |

---

## 2. DataScript schema

```clojure
(def schema
  {:doc/path {:db/unique :db.unique/identity}})
```

Only `:doc/path` needs schema because it is the natural key. Other attributes
are scalar cardinality-one fields.

---

## 3. Document attributes

| Attribute | Type | Meaning |
|-----------|------|---------|
| `:doc/path` | string | Absolute local file path; identity attribute. |
| `:doc/kind` | string | `markdown`, `image`, `pdf`, `source`, or `text`. |
| `:doc/text` | string | Raw text for Markdown/source/text. Omitted for binary image/PDF. |
| `:doc/html` | string | Rendered Markdown HTML or escaped text HTML. |
| `:doc/toc` | vector | Render-time Markdown heading metadata. |
| `:doc/assets` | vector | Embedded local asset paths referenced by Markdown. |
| `:doc/error` | string | Read/render error message. |
| `:doc/stamp` | number | Content timestamp used to ignore stale async render results. |

DataScript rejects `nil`; absence is represented by omitting an attribute, and
clearing is represented by retraction.

---

## 4. app-db slices

Current default shape:

```clojure
{:ds/rev 0
 :ui {:theme "spacemacs-dark"
      :active-heading nil
      :sidebar-visible? true
      :sidebar-width 280
      :sidebar-tab :files
      :tree-selected nil
      :tabs []
      :active-tab nil
      :next-tab-id 0
      :projects []
      :menu nil
      :settings {}
      :settings-open? false
      :keymaps {:active "default" :order [] :sets {}}
      :kbedit {:open? false :sel nil :editing nil :capture nil
               :ctx nil :undo [] :redo []}
      :hints {:active? false :targets [] :typed ""}
      :find {:visible? false :query "" :count 0 :idx 0}
      :input {:mode :insert :sequence [] :count nil :in-input? false
              :timeout-id nil}
      :palette {:open? false :source :command :prefix "" :query ""
                :items [] :selected 0}}}
```

Important slices:

| Path | Meaning |
|------|---------|
| `:ds/rev` | DataScript transaction revision signal. |
| `[:ui :tabs]` | Ordered browser-like tab views. |
| `[:ui :active-tab]` | Active tab id. |
| `[:ui :projects]` | Git-rooted file trees. |
| `[:ui :settings]` | Persisted settings loaded from `settings.edn`. |
| `[:ui :keymaps]` | Persisted keymap registry loaded from `keybindings.edn`. |
| `[:ui :input]` | Modal/chord resolver display state. |
| `[:ui :kbedit]` | Keybinding editor state and undo/redo stacks. |
| `[:ui :hints]` | Vimium-style link hint state. |

---

## 5. Tab shape

```clojure
{:id 1
 :uri "/abs/path/current.md"
 :hist {:stack [{:uri "/abs/path/previous.md" :scroll 220}
                {:uri "/abs/path/current.md" :scroll 0}]
        :idx 1}
 :view-source? false}
```

The tab's current `:uri` mirrors the current history entry. Local URI paths are
also the retained paths used for watcher/cache ownership.

---

## 6. DataScript helpers

| Helper | Purpose |
|--------|---------|
| `snapshot` | Returns the current immutable DataScript db. |
| `eid-for-path` | Finds document entity id for a path. |
| `doc-attr` | Reads one document attribute. |
| `active-doc` | Pulls the content entity for the active local path. |
| `doc-paths` | Lists cached document paths. |
| `retract-unretained-tx` | Builds entity retractions for paths no longer retained. |

`open-docs`, `order-for-path`, and `next-order` remain test/back-compat helpers
for content-cache ordering but are no longer the tab model.

---

## 7. Invariants

| Invariant | Reason |
|-----------|--------|
| Tabs are app-db views. | UI navigation should be pure and fast. |
| Cached docs are keyed by `:doc/path`. | Live refresh and multiple histories share one content entity per file. |
| Retained paths are derived from all tab histories. | Main watchers and cached docs are kept for reachable history entries only. |
| Render commits are stamp-checked. | Slow older Markdown renders cannot overwrite newer content. |
| `:ds/rev` is a signal only. | It triggers subscription recomputation without duplicating content into app-db. |

See [../design-decisions/0010-bounded-content-retention-and-render-metadata.md](../design-decisions/0010-bounded-content-retention-and-render-metadata.md).
