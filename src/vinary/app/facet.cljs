(ns vinary.app.facet
  "The document-FACET model. A document may have several collocated representations — an authored source
   (markdown/org/latex/mermaid/diff) plus its compiled PDF — discovered main-side as the `:doc/siblings` group. A
   facet = `{:path :type}` where `:type` is `:preview` (rendered) or `:source` (highlighted text). This namespace
   is the single source of truth for the toolbar's `[Preview ▾ | Source ▾]` combo: which files each button offers,
   in what order, which is active, and what a click activates. The pure core takes explicit args (node-testable,
   no DataScript); the thin glue at the bottom reads DataScript + app-db. Replaces the old binary
   `:representation`/`:view-source?` axes with one uniform facet."
  (:require [vinary.app.nav :as nav]
            [vinary.app.ds :as ds]
            [vinary.main.file-kind :as file-kind]))

;; ── kind metadata ──────────────────────────────────────────────────────────────────────────────────────────
(def kind-priority
  "Tie-break priority among the non-opened group members (lower = earlier). The opened file always sorts first
   regardless of kind; this orders the rest."
  {"pdf" 0 "markdown" 1 "org" 2 "latex" 3 "mermaid" 4 "diff" 5})

(def kind-label
  "The display label for a representation in the combo menus. All group members share a basename, so we name each
   option by its kind (PDF / Org / LaTeX / …) rather than the redundant filename."
  {"pdf" "PDF" "markdown" "Markdown" "org" "Org" "latex" "LaTeX" "mermaid" "Mermaid" "diff" "Diff"})

(defn- pdf? [m] (= "pdf" (:kind m)))

;; ── pure core (explicit args; unit-tested in facet_test) ─────────────────────────────────────────────────────
(defn ordered-members
  "Group members ordered opened-file-first, then by `kind-priority`, then path."
  [group primary]
  (vec (sort-by (fn [{:keys [path kind]}] [(if (= path primary) 0 1) (kind-priority kind 99) (str path)])
                group)))

(defn preview-options
  "Files offered under Preview: every group member renders, with the compiled PDF hoisted to the head, then the
   ordered remainder (opened-file-first)."
  [group primary]
  (let [om (ordered-members group primary)]
    (into (vec (filter pdf? om)) (remove pdf? om))))

(defn source-options
  "Files offered under Source: the group's text sources (every member except the PDF), opened-file-first."
  [group primary]
  (vec (remove pdf? (ordered-members group primary))))

(defn show-view-switch?
  "Whether to show the Preview/Source control at all: true iff there is a source to view OR ≥2 previews to choose
   between. A lone PDF (preview-only, no source) → false (nothing meaningful to toggle)."
  [group primary]
  (boolean (or (seq (source-options group primary))
               (>= (count (preview-options group primary)) 2))))

(defn default-facet
  "The facet shown when a tab has made no explicit choice: the compiled PDF's preview when a PDF exists, the
   opened file isn't itself that PDF, and the `collocated-default` pref isn't `:document`; else the opened file's
   own preview."
  [group primary pref]
  (let [pdf (some #(when (pdf? %) (:path %)) group)]
    (if (and pdf (not= primary pdf) (not= pref :document))
      {:path pdf :type :preview}
      {:path primary :type :preview})))

(defn valid-facet?
  "Whether a stored facet still names a real option (its path is in the group, and — for `:source` — is a source
   option, never the PDF). Guards a stale facet after a file was deleted/replaced."
  [group primary facet]
  (boolean (when facet
             (case (:type facet)
               :preview (some #(= (:path facet) (:path %)) (preview-options group primary))
               :source  (some #(= (:path facet) (:path %)) (source-options group primary))
               nil))))

(defn active-facet
  "The effective facet: the tab's stored facet when still valid, else the default."
  [tab-facet group primary pref]
  (if (valid-facet? group primary tab-facet)
    tab-facet
    (default-facet group primary pref)))

(defn main-target
  "The path a combo button's MAIN region activates: the most-recently-used file for that type when it is still an
   option, else the first (highest-priority) option; nil when the type has no options."
  [options mru-path]
  (let [paths (map :path options)]
    (or (some #{mru-path} paths) (first paths))))

(defn toggle-target
  "Flipping the view type (the `C-S-s` / 'View Source' action) from `cur-type`: the other type's main target, or
   nil when the other type has no options (e.g. a PDF-only doc cannot switch to Source)."
  [cur-type group primary mru]
  (let [other (if (= cur-type :source) :preview :source)
        opts  (if (= other :preview) (preview-options group primary) (source-options group primary))]
    (when-let [tgt (main-target opts (get mru other))]
      {:path tgt :type other})))

(defn- button-mode [options]
  (case (count options) 0 :hidden 1 :plain :combo))

(defn- opt-entries [options button-type active-type active-path]
  (mapv (fn [{:keys [path kind]}]
          {:path path :kind kind :label (kind-label kind kind)
           :active? (and (= button-type active-type) (= path active-path))})
        options))

(defn view-model
  "Everything the toolbar renders, from the tab's facet/MRU + the group + the pref. `:show?` gates the whole
   control; each of `:preview`/`:source` carries its button `:mode` (`:hidden`/`:plain`/`:combo`), `:main-path`
   (the main-region target), and `:options` (each `{:path :kind :label :active?}`)."
  [tab-facet group primary pref mru]
  (let [af       (active-facet tab-facet group primary pref)
        previews (preview-options group primary)
        sources  (source-options group primary)]
    {:show?       (show-view-switch? group primary)
     :active-type (:type af)
     :active-path (:path af)
     :preview {:mode (button-mode previews)
               :main-path (main-target previews (get mru :preview))
               :options (opt-entries previews :preview (:type af) (:path af))}
     :source  {:mode (button-mode sources)
               :main-path (main-target sources (get mru :source))
               :options (opt-entries sources :source (:type af) (:path af))}}))

;; ── DataScript + app-db glue (integration; reads state, delegates to the pure core above) ────────────────────
(defn group-of
  "The active document group for `primary` — its `:doc/siblings` (which already includes `primary` itself); else a
   synthesized SINGLE-member group when the doc is a group-kind that carries no siblings (so a lone markdown/org/
   latex/mermaid/diff still offers its Preview + Source); else nil for a non-group kind (no view switch). The
   synthesized fallback also matches the real payload (main always attaches :siblings for a group kind)."
  [snap primary]
  (let [doc  (ds/active-doc snap primary)
        sibs (:doc/siblings doc)
        kind (:doc/kind doc)]
    (cond
      (seq sibs)                              (vec sibs)
      (contains? file-kind/group-kinds kind)  [{:path primary :kind kind}]
      :else                                   nil)))

(defn- pref-of [db] (get-in db [:ui :settings :collocated-default] :pdf))

(defn resolve-facet
  "The effective facet of the active tab (glue over `active-facet`)."
  [db]
  (let [snap    (ds/snapshot)
        primary (nav/active-path db)]
    (active-facet (nav/facet db) (group-of snap primary) primary (pref-of db))))

(defn active-content-path
  "The path of the file the active tab is currently SHOWING (its active facet) — the content the pane, Contents
   outline, and find operate on. Falls back to the tab's primary path."
  [db]
  (or (:path (resolve-facet db)) (nav/active-path db)))

(defn active-type
  "The active tab's current view type — `:preview` or `:source`."
  [db]
  (:type (resolve-facet db)))
