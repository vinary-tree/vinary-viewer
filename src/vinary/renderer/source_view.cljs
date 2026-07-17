(ns vinary.renderer.source-view
  "The @codemirror source-view editor — the RENDERER-ONLY half of the old renderer.syntax, split out so the heavy
   @codemirror packages (~1 MB) live in a lazily-loaded module (this ns is the sole entry of the `:source-view`
   build module and is reached only through the renderer.cm facade), instead of the renderer boot bundle.

   A read-only CodeMirror 6 view of a source file: the doc is set once and the view re-created on live-refresh (no
   incremental editing). Its highlighting SPANS ({:from :to :class}) come from web-tree-sitter via renderer.syntax
   (the @codemirror-free parse core); this ns converts spans → CodeMirror Decorations. Every fn the facade
   (renderer.cm) passes through is re-exported via `exports` (string keys, ^:export) at the end. Adapted from
   LightningBug's lib/editor/syntax.cljs."
  (:require [clojure.string :as str]
            ["@codemirror/state" :refer [EditorState Compartment StateField]]
            ["@codemirror/view" :refer [EditorView Decoration lineNumbers]]
            [vinary.renderer.syntax :as syntax]))

;; the currently-mounted source EditorView, so a source Contents-outline click can scroll it to a line
(defonce ^:private current-view (atom nil))

;; a pending preview→source jump line: set (via renderer.cm/want-source-line!) BEFORE toggling to source remounts
;; the view, consumed when create-source-view mounts (mirrors renderer.scroll want!→apply!). defonce survives reload.
(defonce ^:private pending-source-line (atom nil))

(defn view-from-dom
  "Return the CodeMirror EditorView associated with node, if node is inside one."
  [^js node]
  (when (and node (.-findFromDOM EditorView))
    (.findFromDOM EditorView node)))

(defn- mark-range! [ranges cls s e]
  (when (and cls (< s e))
    (.push ranges (.range (.mark Decoration #js {:class cls}) s e))))

(defn- spans->decorations
  "Convert renderer.syntax highlight spans ({:from :to :class}) into a CodeMirror Decoration range array. Spans are
   produced in capture order (the same order the old in-syntax capture-ranges! pushed its Decoration ranges), so
   the resulting decoration set — built next with sort=true — is unchanged."
  [spans]
  (let [ranges (array)]
    (doseq [span (array-seq spans)]
      (mark-range! ranges (:class span) (:from span) (:to span)))
    ranges))

(defn- decoration-set [ranges]
  (.set Decoration ranges true))   ; sort=true (handles overlapping/nested captures)

(defn- highlight-field [decos]
  (.define StateField
           #js {:create  (fn [_] decos)
                :update  (fn [v _] v)            ; read-only: decorations are computed once
                :provide (fn [f] (.. EditorView -decorations (from f)))}))

(defn selected-text
  "Return the selected text in a CodeMirror view, or nil when all ranges are empty."
  [^js view]
  (when view
    (let [state  (.-state view)
          ranges (.. state -selection -ranges)
          pieces (->> (range (.-length ranges))
                      (keep (fn [i]
                              (let [r (.at ranges i)
                                    from (.-from r)
                                    to   (.-to r)]
                                (when (< from to)
                                  (.sliceDoc state from to))))))]
      (when (seq pieces)
        (str/join "\n" pieces)))))

(defn selection-start
  "Return the first selected position in a CodeMirror view, or nil."
  [^js view]
  (when view
    (let [ranges (.. view -state -selection -ranges)]
      (some (fn [i]
              (let [r (.at ranges i)]
                (when (< (.-from r) (.-to r)) (.-from r))))
            (range (.-length ranges))))))

(defn pos-at-coords [^js view x y]
  (when view
    (.posAtCoords view #js {:x x :y y})))

(defn line-info-at
  "Return {:line :column :text :line-from} for a CodeMirror document position."
  [^js view pos]
  (when (and view (number? pos))
    (let [doc  (.. view -state -doc)
          line (.lineAt doc pos)]
      {:line (.-number line)
       :column (inc (- pos (.-from line)))
       :text (.-text line)
       :line-from (.-from line)})))

(defn want-source-line! [line] (reset! pending-source-line line))

(defn current-source-line
  "The 1-based cursor line of the mounted source view (the anchor for a keyboard/palette 'Go to preview' invoked
   from source with no click target) — the selection start, else the document start. nil when no source view is
   mounted."
  []
  (when-let [^js view @current-view]
    (:line (line-info-at view (or (selection-start view) 0)))))

(defn scroll-source-to-line!
  "Scroll the mounted source view to 1-based `line` (for a source Contents-outline click). No-op if no source
   view is mounted."
  [line]
  (when-let [^js view @current-view]
    (let [doc (.. view -state -doc)
          n   (max 1 (min line (.-lines doc)))
          pos (.-from (.line doc n))]
      (.dispatch view #js {:effects   (.scrollIntoView EditorView pos #js {:y "start"})
                           :selection #js {:anchor pos}}))))

(defn viewport-top-line
  "The 1-based document line at the top of `view`'s viewport — the anchor for the source-view Contents scroll-spy
   (the analog of a pixel scrollTop for the DOM preview spy). `.lineBlockAtHeight` maps the scroller's scrollTop
   (a document-relative height) to the line block currently at the viewport top. nil when `view` is absent."
  [^js view]
  (when view
    (let [block (.lineBlockAtHeight view (.-scrollTop (.-scrollDOM view)))
          doc   (.. view -state -doc)]
      (.-number (.lineAt doc (.-from block))))))

(defn current-viewport-line
  "The 1-based viewport-top line of the MOUNTED source view (nil when none is mounted) — the source coordinate
   saved into history when leaving a :source facet (the :view-pos cofx). A pixel scrollTop is meaningless for a
   source view because CodeMirror scrolls its own `.cm-scroller`, not the `.vv-content` DOM scroller."
  []
  (when-let [^js view @current-view] (viewport-top-line view)))

(defn create-source-view
  "Mount a read-only CodeMirror view of text in parent. If grammar (a {:wasm-url :scm-url}) is given,
   asynchronously load the tree-sitter grammar (via renderer.syntax) and reconfigure with highlighting. Returns
   the view."
  [^js parent text grammar]
  (let [hl   (Compartment.)
        exts #js [(.of (.-readOnly EditorState) true)
                  (.of (.-editable EditorView) false)
                  (lineNumbers)
                  (.-lineWrapping EditorView)        ; a pre-built Extension, not a Facet (no .of)
                  (.of hl #js [])]
        state (.create EditorState #js {:doc text :extensions exts})
        view  (EditorView. #js {:state state :parent parent})]
    (reset! current-view view)          ; register for source-outline line-scroll
    (when-let [l @pending-source-line]  ; consume a pending preview→source jump (deferred across the remount)
      (reset! pending-source-line nil)
      (scroll-source-to-line! l))
    (when grammar
      ;; parse stays in renderer.syntax (@codemirror-free): it resolves highlight SPANS, which we turn into a
      ;; CodeMirror decoration set here — byte-for-byte the same decorations the old in-syntax path produced.
      (-> (syntax/highlight-spans text grammar)
          (.then (fn [spans]
                   (.dispatch view #js {:effects (.reconfigure hl (highlight-field (decoration-set (spans->decorations spans))))})))
          (.catch (fn [e] (js/console.warn "[vv] grammar load failed:" e)))))
    view))

;; The renderer.cm facade loads this module lazily and passes calls through these exported fns (string keys so
;; :simple/:advanced never rename them). Keep in sync with the pass-throughs in renderer.cm.
(def ^:export exports
  #js {"create-source-view"     create-source-view
       "view-from-dom"          view-from-dom
       "scroll-source-to-line!" scroll-source-to-line!
       "want-source-line!"      want-source-line!
       "current-source-line"    current-source-line
       "current-viewport-line"  current-viewport-line
       "viewport-top-line"      viewport-top-line
       "selected-text"          selected-text
       "selection-start"        selection-start
       "pos-at-coords"          pos-at-coords
       "line-info-at"           line-info-at})
