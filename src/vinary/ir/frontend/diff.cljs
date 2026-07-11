(ns vinary.ir.frontend.diff
  "Diff front-end: parse `.diff`/`.patch` text (vinary.diff) into the common document IR for the UNIFIED
   (single-column, colored) view — lowered identically by the HTML back-end (GUI) and the ANSI back-end
   (terminal `vv-cli`/`vv-tui`). Each file becomes an `<h2>` banner (which also feeds a multi-file Contents
   outline), each hunk a `@@` header line, and each body line a class-tagged `<div>` carrying its old/new
   gutter numbers as `data-*` attributes (drawn by CSS in the GUI, invisible to the terminal's text-only
   ANSI backend). The side-by-side (split) view is GUI-only and lives in vinary.diff. Pure — no DOM, no fs.

   Attribute maps use STRING keys (\"className\"/\"id\"/…) — the pure-front-end convention the ANSI backend's
   `attr`/`classes` reader expects — and camelCase `dataOld`/`dataNew` property names, which the HTML back-end's
   hast serializer emits as `data-old`/`data-new`."
  (:require [clojure.string :as str]
            [vinary.diff :as diff]
            [vinary.ir.node :as node]))

(defn- text-leaf [s] (node/leaf :text (or s "")))

;; the line's text lives in an inner <span class="vv-diff-code"> so the two line-number gutters can be CSS
;; pseudo-elements (::before/::after) ordered around it by a grid — the gutters are then drawn in the GUI but,
;; being pseudo-content rather than text nodes, are invisible to the terminal's text-reading ANSI backend.
(defn- code-span [s] (node/node :span [(text-leaf s)] {:tag "span" :attrs {"className" ["vv-diff-code"]}}))

(defn- note-line [text]
  (node/node :diff-line [(code-span text)]
             {:tag "div" :attrs {"className" ["vv-diff-line" "vv-diff-note"]}}))

(defn- line-node
  "One unified-diff body line → a class-tagged div carrying the ±/space marker in its (span-wrapped) text — so
   the terminal reads naturally — and the old/new line numbers as data-* gutters (drawn by CSS in the GUI)."
  [{:keys [kind text old-n new-n no-newline?]}]
  (let [marker (case kind :insert "+" :delete "-" " ")
        cls    (cond-> ["vv-diff-line" (str "vv-diff-" (name kind))]
                 no-newline? (conj "vv-diff-no-newline"))]
    (node/node :diff-line [(code-span (str marker text))]
               {:tag "div"
                :attrs (cond-> {"className" cls}
                         old-n (assoc "dataOld" (str old-n))
                         new-n (assoc "dataNew" (str new-n)))})))

(defn- hunk-node [{:keys [old-start new-start heading]}]
  (node/node :diff-line
             [(code-span (str "@@ -" old-start " +" new-start " @@" (when (seq heading) (str " " heading))))]
             {:tag "div" :attrs {"className" ["vv-diff-line" "vv-diff-hunk"]}}))

(defn- file-heading [file idx]
  (let [status (diff/file-status file)
        label  (diff/file-label file)
        id     (str "vv-diff-file-" idx)]
    (node/node :heading
               [(node/node :span [(text-leaf status)]
                           {:tag "span" :attrs {"className" ["vv-diff-file-status" (str "vv-diff-status-" status)]}})
                (text-leaf " ")
                (node/node :span [(text-leaf label)]
                           {:tag "span" :attrs {"className" ["vv-diff-file-name"]}})]
               {:tag "h2" :level 2 :id id :toc-text label
                :attrs {"id" id "className" ["vv-diff-file-head"] "dataStatus" status}})))

(defn- file-nodes [file idx]
  (into [(file-heading file idx)]
        (cond
          (:binary? file)                             [(note-line "Binary file — no textual diff")]
          (and (empty? (:hunks file)) (:rename? file)) [(note-line "Renamed with no content change")]
          (empty? (:hunks file))                      [(note-line "No changes")]
          :else (mapcat (fn [h] (cons (hunk-node h) (map line-node (:lines h)))) (:hunks file)))))

(defn diff->ir
  "Parse `text` and lower it to the unified diff IR — a :document of file `<h2>` banners + `@@`/±/context
   lines, preceded by any git-format-patch preamble (commit message + diffstat) as a `<pre>` block."
  [text]
  (let [{:keys [preamble files]} (diff/parse text)
        pre  (when (seq (str/trim (or preamble "")))
               [(node/node :code-block
                           [(node/node :code [(text-leaf preamble)] {:tag "code"})]
                           {:tag "pre" :attrs {"className" ["vv-diff-preamble"]}})])]
    (node/node :document
               (into (vec pre) (mapcat file-nodes files (range)))
               {})))

(defn outline
  "A Contents outline for a diff IR: one entry per file banner (its :toc-text label, anchored by :id)."
  [ir]
  (into []
        (comp (filter #(= :heading (node/kind %)))
              (keep (fn [h] (let [m (node/node-meta h)]
                              (when-let [id (:id m)]
                                {:level 1 :text (or (:toc-text m) "") :id id})))))
        (node/children ir)))
