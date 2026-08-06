(ns vinary.ir.frontend.diff
  "Diff front-end: parse `.diff`/`.patch` text (vinary.diff) into the common document IR for the UNIFIED
   (single-column, colored) view — lowered identically by the HTML back-end (GUI) and the ANSI back-end
   (terminal `vv-cli`/`vv-tui`). Each file becomes a collapsible `<details class=\"vv-diff-file\" open>`
   wrapper (ADR-0037) whose FIRST child is the file's `<summary>` banner (kind :heading — it also feeds the
   multi-file Contents outline), followed by each hunk's `@@` header line and each body line as a
   class-tagged `<div>` carrying its old/new gutter numbers as `data-*` attributes (drawn by CSS in the
   GUI, invisible to the terminal's text-only ANSI backend). The side-by-side (split) view is GUI-only and
   lives in vinary.diff. Pure — no DOM, no fs.

   Attribute maps use STRING keys (\"className\"/\"id\"/…) — the pure-front-end convention the ANSI backend's
   `attr`/`classes` reader expects — and camelCase `dataOld`/`dataNew` property names, which the HTML back-end's
   hast serializer emits as `data-old`/`data-new`.

   Two-backend invariants of the per-file wrapper (ADR-0037; guarded by diff-test's structure + golden tests):
     - the banner is the wrapper's FIRST child — a :heading child is what keeps the wrapper out of the ANSI
       backend's inline-container merge, and its line index is where the file's TUI anchor lands;
     - the wrapper carries the file id ONLY as IR meta :id (never serialized — the DOM id lives solely on
       the summary, so getElementById stays unambiguous) — ANSI render-lines reads top-level blocks' meta
       :id for the TUI Contents-jump anchors;
     - ANSI output is byte-identical to the pre-wrapper flat form at the diff block separator \"\\n\"
       (cli/render + the TUI both render diffs that way): the wrapper recurses, and within-block joins
       equal the former between-block separators."
  (:require [clojure.string :as str]
            [vinary.diff :as diff]
            [vinary.ir.node :as node]))

(defn- text-leaf [s] (node/leaf :text (or s "")))

;; the line's text lives in an inner <span class="vv-diff-code"> so the two line-number gutters can be CSS
;; pseudo-elements (::before/::after) ordered around it by a grid — the gutters are then drawn in the GUI but,
;; being pseudo-content rather than text nodes, are invisible to the terminal's text-reading ANSI backend.
(def ^:private syntax-class-re #"^cm-[a-z0-9-]+$")

(defn- syntax-nodes
  "Token segments from renderer.syntax → escaped IR text leaves under fixed `cm-*` spans. The renderer's
   style map is already fixed, but validate again at this HTML construction boundary so an arbitrary class can
   never be smuggled through an enriched model."
  [segments]
  (mapv (fn [{:keys [text class]}]
          (if (and (string? class) (re-matches syntax-class-re class))
            (node/node :span [(text-leaf text)] {:tag "span" :attrs {"className" [class]}})
            (text-leaf text)))
        segments))

(defn- code-span
  ([s] (node/node :span [(text-leaf s)] {:tag "span" :attrs {"className" ["vv-diff-code"]}}))
  ([marker text segments]
   (node/node :span
              (if (seq segments)
                (into [(text-leaf marker)] (syntax-nodes segments))
                [(text-leaf (str marker text))])
              {:tag "span" :attrs {"className" ["vv-diff-code"]}})))

(defn- note-line [text]
  (node/node :diff-line [(code-span text)]
             {:tag "div" :attrs {"className" ["vv-diff-line" "vv-diff-note"]}}))

(defn- line-node
  "One unified-diff body line → a class-tagged div carrying the ±/space marker in its (span-wrapped) text — so
   the terminal reads naturally — and the old/new line numbers as data-* gutters (drawn by CSS in the GUI)."
  [{:keys [kind text old-n new-n no-newline?] :as line}]
  (let [marker (case kind :insert "+" :delete "-" " ")
        syntax (case kind :delete (:old-syntax line) :insert (:new-syntax line)
                     (or (:new-syntax line) (:old-syntax line)))
        cls    (cond-> ["vv-diff-line" (str "vv-diff-" (name kind))]
                 no-newline? (conj "vv-diff-no-newline"))]
    (node/node :diff-line [(code-span marker text syntax)]
               {:tag "div"
                :attrs (cond-> {"className" cls}
                         old-n (assoc "dataOld" (str old-n))
                         new-n (assoc "dataNew" (str new-n)))})))

(defn- hunk-node [{:keys [old-start new-start heading]}]
  (node/node :diff-line
             [(code-span (str "@@ -" old-start " +" new-start " @@" (when (seq heading) (str " " heading))))]
             {:tag "div" :attrs {"className" ["vv-diff-line" "vv-diff-hunk"]}}))

(defn- path-anchor [path]
  ;; Deliberately no href: only main can establish that a diff-relative path exists. markdown-body projects the
  ;; resolved target map onto these placeholders after mount; unresolved paths stay inert/plain.
  (node/node :span [(text-leaf path)]
             {:tag "a" :attrs {"className" ["vv-diff-file-path"] "dataVvDiffPath" path}}))

(defn- file-label-nodes [{:keys [old-path new-path rename?]}]
  (cond
    (and rename? old-path new-path (not= old-path new-path))
    [(path-anchor old-path) (text-leaf " → ") (path-anchor new-path)]
    new-path [(path-anchor new-path)]
    old-path [(path-anchor old-path)]
    :else [(text-leaf "/dev/null")]))

(defn- file-heading
  "The file's banner — a `<summary>` (the wrapper's native toggle target; Settings/none of the GUI relies on
   h2 semantics, and the ANSI backend + both Contents outlines dispatch on the :heading KIND, not the tag).
   Carries the canonical DOM id `vv-diff-file-<idx>` + the `.vv-diff-file-head` class the GUI click
   delegation and the link-hints collector key off."
  [file idx]
  (let [status (diff/file-status file)
        label  (diff/file-label file)
        id     (str "vv-diff-file-" idx)]
    (node/node :heading
               [(node/node :span [(text-leaf status)]
                           {:tag "span" :attrs {"className" ["vv-diff-file-status" (str "vv-diff-status-" status)]}})
                (text-leaf " ")
                (node/node :span (file-label-nodes file)
                           {:tag "span" :attrs {"className" ["vv-diff-file-name"]}})]
               {:tag "summary" :level 2 :id id :toc-text label
                :attrs {"id" id "className" ["vv-diff-file-head"] "dataStatus" status}})))

(defn- file-nodes
  "One collapsible `<details class=\"vv-diff-file\" open>` wrapper per file: the banner FIRST (the
   inline-container guard + the ANSI anchor line), then the hunk/body lines. The wrapper's file id is IR
   meta ONLY (`:id` — read by ANSI render-lines for TUI anchors, never serialized); `open` serializes as
   the bare attribute so the default paint matches the all-expanded state (the GUI re-applies the per-tab
   collapsed set right after every innerHTML rebuild)."
  [file idx]
  (let [status (diff/file-status file)]
    [(node/node :details
                (into [(file-heading file idx)]
                      (cond
                        (:binary? file)                              [(note-line "Binary file — no textual diff")]
                        (and (empty? (:hunks file)) (:rename? file)) [(note-line "Renamed with no content change")]
                        (empty? (:hunks file))                       [(note-line "No changes")]
                        :else (mapcat (fn [h] (cons (hunk-node h) (map line-node (:lines h)))) (:hunks file))))
                {:tag "details"
                 :id (str "vv-diff-file-" idx)
                 :attrs {"className" ["vv-diff-file"] "dataStatus" status "open" true}})]))

(defn model->ir
  "Lower an already-parsed (optionally renderer-syntax-enriched) model to unified diff IR. Keeping this entry
   point separate lets the GUI parse/highlight asynchronously while `diff->ir` remains the pure CLI/TUI path."
  [{:keys [preamble files]}]
  (let [pre  (when (seq (str/trim (or preamble "")))
               [(node/node :code-block
                           [(node/node :code [(text-leaf preamble)] {:tag "code"})]
                           {:tag "pre" :attrs {"className" ["vv-diff-preamble"]}})])]
    (node/node :document
               (into (vec pre) (mapcat file-nodes files (range)))
               {})))

(defn diff->ir
  "Parse `text` and lower it to the unified diff IR — a :document of file banners + `@@`/±/context lines,
   preceded by any git-format-patch preamble (commit message + diffstat) as a `<pre>` block."
  [text]
  (model->ir (diff/parse text)))

(defn outline
  "A Contents outline for a diff IR: one entry per file banner (its :toc-text label, anchored by :id).
   Scans PREORDER — the banners live inside the per-file `<details>` wrappers (ADR-0037), no longer as
   direct document children."
  [ir]
  (into []
        (comp (filter #(= :heading (node/kind %)))
              (keep (fn [h] (let [m (node/node-meta h)]
                              (when-let [id (:id m)]
                                {:level 1 :text (or (:toc-text m) "") :id id})))))
        (node/preorder ir)))
