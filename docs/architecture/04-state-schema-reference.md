# 04. State Schema Reference

This page describes the current state model: app-db owns UI/navigation, DataScript
owns cached document content, and main owns OS/native resources.

---

## 1. Store split

| Store | Owns | Does not own |
|-------|------|--------------|
| re-frame `app-db` | Tabs, active tab, per-tab histories, saved scroll entries, sidebar state, settings UI, keybinding UI, find state, TOC active heading, command palette state. | Loaded document text/html. |
| DataScript | Cached content entities keyed by `:doc/path`. | Tab identity, active tab, history stacks. |
| Main process atoms/native objects | File/asset watchers, the native web view, SSH sessions, stream sessions, config watchers. | Renderer UI state. (The native PDF view was retired in [ADR-0013](../design-decisions/0013-in-renderer-pdfjs.md); PDFs now render in the renderer with pdf.js.) |

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
| `:doc/path` | string | Identity attribute. An absolute **local** path, or a **virtual URI** — `ssh://` / `sftp://` (remote, [ADR-0027](../design-decisions/0027-remote-files-over-ssh.md)) or `vv-archive://` (inside an archive). Retained-path ownership keys off this value. |
| `:doc/kind` | string | The classified content kind, selecting the renderer Strategy: `markdown`, `org`, `latex`, `source`, `text`, `image`, `pdf`, `directory`, `office`, `table`, `log`, `archive`, `html`, or `diff`. |
| `:doc/text` | string | Raw text for text-bearing kinds (Markdown/Org/LaTeX/source/text/diff). Omitted for binary image/PDF and directories. |
| `:doc/html` | string | Rendered HTML (Markdown/Org/LaTeX/office/diff) or escaped text HTML. |
| `:doc/toc` | vector | Render-time heading/outline metadata (Markdown/Org/office headings, a PDF font-size outline, or a source-code outline). |
| `:doc/assets` | vector | Embedded local asset paths referenced by the document. |
| `:doc/meta` | map | Load metadata; notably `{:size n}`, which gates whether the doc streams. |
| `:doc/entries` | vector | Immediate children of a directory document; each is `{:name :path :dir? :size :mtime :symlink}`. Present only when `:doc/kind = "directory"`. |
| `:doc/sheets` | vector | Worksheet/table pages of a workbook or delimited table (paged view). |
| `:doc/page` / `:doc/paged?` | number / boolean | Current page, and whether a large log/table is served in pages. |
| `:doc/data-url` | string | A `data:` URL for a raster/remote image rendered inline. |
| `:doc/reflow-html` | string | Reflowed-prose HTML of a PDF's extracted text (View ▸ Reflow Text). |
| `:doc/sourceable?` / `:doc/source-sibling` | boolean / string | Whether a Preview↔Source toggle exists, and the collocated source path a PDF can switch to. |
| `:doc/pdf-sibling` | string | Path of a collocated same-stem `.pdf` a document can switch to (Document↔PDF switch, [ADR-0025](../design-decisions/0025-latex-rendering-via-unified-latex.md)/[0026](../design-decisions/0026-diff-rendering-side-by-side-and-repo-filetypes.md)). |
| `:doc/diff-split-html` | string | The GUI-only, on-disk-enriched side-by-side (split) rendering of a diff. |
| `:doc/streaming?` / `:doc/stream-progress` / `:doc/stream-note` | boolean / number / string | Whether the doc renders incrementally, its progress in `[0,1]`, and a user-facing status note. |
| `:doc/error` | string | Read/render error message. |
| `:doc/stamp` | number | Content timestamp used to ignore stale async render results. |

The authoritative attribute list is the `d/pull` vector in `active-doc`
(`vinary.app.ds`) — a new `:doc/*` attribute is invisible to views until added there.
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
      :dir-selected nil             ; highlighted directory-entry path (Enter / Alt+Down opens it)
      :tabs []
      :active-tab nil
      :next-tab-id 0
      :tab-drop nil                 ; {:over <tab-id> :after? bool} tab-drag drop indicator, or nil
      :projects []
      :menu nil
      :settings {}
      :recent {:trail {} :recent-files []}  ; persisted (recent.edn): dir→last-child trail + MRU
      :ctrl-held? false             ; Control currently held (drives the breadcrumb URI bar)
      :settings-open? false
      :keymaps {:active "default" :order [] :sets {}}
      :kbedit {:open? false :sel nil :editing nil :capture nil
               :ctx nil :undo [] :redo []}
      :hints {:active? false :targets [] :typed ""}
      :find {:visible? false :query "" :count 0 :idx 0}
      :input {:mode :insert :sequence [] :count nil :in-input? false
              :timeout-id nil}
      :passwords {:open? false :providers [] :forms {:count 0}
                  :items [] :busy? false :error nil
                  :result nil :save-prompt nil}
      :palette {:open? false :source :command :prefix "" :query ""
                :items [] :selected 0}}}
```

Important slices:

| Path | Meaning |
|------|---------|
| `:ds/rev` | DataScript transaction revision signal. |
| `[:ui :tabs]` | Ordered browser-like tab views. |
| `[:ui :active-tab]` | Active tab id. |
| `[:ui :tab-drop]` | Tab-drag drop-line indicator `{:over <tab-id> :after? bool}` (or nil). |
| `[:ui :dir-selected]` | Explicitly highlighted directory-entry path (the rendered highlight also consults the trail). |
| `[:ui :ctrl-held?]` | Whether Control is held; gates the Ctrl-hover breadcrumb URI bar. |
| `[:ui :recent]` | Persisted recent-navigation state `{:trail {dir→child} :recent-files [...] :web-history [...]}` from `recent.edn`. |
| `[:ui :uri-complete]` | Address-bar completion state (`{:input :entries :dismissed? :selected :error? …}`). |
| `[:ui :pdf]` | In-renderer PDF view-state `{:scale :fit :invert?}` (fit + invert persisted in `settings.edn`). |
| `[:ui :extensions]` | Extension runtime state pushed from main: `{:enabled? :installed [...] :install-status :update-status}`. |
| `[:ui :adblock]` | Ad-block prefs `{:enabled? :lists :last-updated}` (persisted in `extensions.edn`). |
| `[:ui :passwords]` | Native password-manager bridge UI state. It stores provider status, form presence, sanitized item metadata, result messages, and save tokens; it never stores revealed passwords. |
| `[:ui :extensions-open?]` | Whether the Settings ▸ Extensions dialog is open (an overlay for `:ui/overlay-open?`). |
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
 :view-source? false        ; Preview (false) vs Source (true) — the Ctrl+Shift+S / Ctrl+Shift+D toggle
 :representation :document  ; :document vs :pdf — the Document↔PDF switch (initial value per the
                            ;   :collocated-default preference when a same-stem sibling .pdf exists)
 :diff-view :unified}       ; :unified vs :split — the [Unified | Split] toggle (diff documents only)
```

The tab's current `:uri` mirrors the current history entry, and may be a **virtual
URI** (`ssh://`, `sftp://`, `vv-archive://`) as well as a local path; local URI
paths are also the retained paths used for watcher/cache ownership. The per-tab
`:view-source?`, `:representation`, and `:diff-view` keys select *which face* of the
same document is shown — preview vs. source, rendered document vs. exported PDF, and
unified vs. side-by-side diff, respectively.

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

## 8. The two stores at a glance

The DataScript `:doc` entity (the single source of truth) and the ephemeral `app-db` map, joined by the amber `:ds/rev` bridge field.

![State model — the DataScript :doc entity and app-db, linked by the :ds/rev bridge](../diagrams/class-state-model.svg)

*Diagram source: [`../diagrams/class-state-model.puml`](../diagrams/class-state-model.puml).*
