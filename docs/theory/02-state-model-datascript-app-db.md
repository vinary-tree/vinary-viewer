# Theory 02. State Model: DataScript + app-db

This page explains why vinary-viewer uses app-db for UI/navigation and DataScript
for cached document content.

---

## 1. One fact, one home

| Fact | Home |
|------|------|
| Ordered tabs, active tab, per-tab histories, scroll entries | app-db |
| Loaded document text, rendered HTML, TOC metadata, embedded asset list, render errors | DataScript |
| File watchers, asset watchers, PDF/web native views | Main process |

The split prevents live-refresh content updates from overwriting UI state.

---

## 2. DataScript role

DataScript is an in-memory immutable database. vinary-viewer uses one identity
attribute:

```clojure
{:doc/path {:db/unique :db.unique/identity}}
```

This makes local file path the natural key. Re-reading a file upserts the same
document entity rather than creating duplicates.

Document entities may contain:

```clojure
{:doc/path "/abs/path/doc.md"
 :doc/kind "markdown"
 :doc/text "# Title"
 :doc/html "<h1 id=\"title\">Title</h1>"
 :doc/toc [{:level 1 :text "Title" :id "title"}]
 :doc/assets ["/abs/path/diagram.svg"]
 :doc/stamp 1780000000000}
```

DataScript rejects `nil`; vinary-viewer models "no value" as an absent attribute
and clears stale values with retractions.

---

## 3. app-db role

app-db is a map for session UI state:

```clojure
{:ds/rev 0
 :ui {:tabs []
      :active-tab nil
      :theme "spacemacs-dark"
      :settings {}
      :keymaps {:active "default" :order [] :sets {}}
      :find {:visible? false :query "" :count 0 :idx 0}
      :input {:mode :insert :sequence [] :count nil :in-input? false}
      :active-heading nil}}
```

Tabs are views, not content entities. A tab points at a URI and owns a history
stack of `{uri, scroll}` entries. Content for local file URIs is looked up in
DataScript by path.

---

## 4. Reactivity bridge

DataScript is not app-db, so re-frame will not see DataScript changes unless they
produce an app-db signal. The bridge is:

```clojure
(d/listen! conn ::reframe
  (fn [_] (rf/dispatch [:ds/changed])))

(rf/reg-event-db :ds/changed
  (fn [db _] (update db :ds/rev inc)))
```

DataScript-reading subscriptions declare `:<- [:ds/rev]`, then read a snapshot.
The counter is not content; it is only an invalidation signal.

---

## 5. Retention

The cache is bounded by navigation reachability. The retained path set is derived
from every local URI in every open tab history. When a path is no longer retained:

1. Main closes its watcher.
2. Main releases owned asset watchers.
3. Renderer retracts its cached document entity from DataScript.

This keeps Back/Forward reliable without keeping abandoned content forever.

---

## References

- DataScript: <https://github.com/tonsky/datascript>
- re-frame documentation: <https://day8.github.io/re-frame/>
- ADR-0010: [../design-decisions/0010-bounded-content-retention-and-render-metadata.md](../design-decisions/0010-bounded-content-retention-and-render-metadata.md)

## 6. How one transaction reaches the right subscriptions

A single DataScript transaction bumps `:ds/rev` in `app-db`, and only the subscriptions that read it recompute.

![The :ds/rev bridge — one DataScript transaction makes the right subscriptions recompute](../diagrams/object-ds-rev-bridge.svg)

*Diagram source: [`../diagrams/object-ds-rev-bridge.puml`](../diagrams/object-ds-rev-bridge.puml).*
