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
            ["@codemirror/state" :refer [EditorState Compartment StateField RangeSetBuilder]]
            ["@codemirror/view" :refer [EditorView Decoration lineNumbers gutter GutterMarker]]
            [vinary.git.blame :as blame]
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

;; ── git blame gutter (ADR-0040): a second gutter behind its own Compartment ─────────────────────
;; The blame Compartment mirrors the grammar-highlight one: the view mounts with it EMPTY, and
;; asynchronously-arriving hunks reconfigure it (set-blame!) without touching any other extension.
;; A remount (live refresh, facet flip) starts empty again — [:blame/ensure] re-applies from its
;; cache, which is also what keeps the gutter honest across document edits (new stamp → re-blame).

(defonce ^:private blame-comp (atom nil))   ; the MOUNTED view's blame Compartment (current-view pattern)

(defn- blame-marker
  "One line's GutterMarker. Prototype-derived rather than `class …extends` — GutterMarker's own
   constructor is empty, so bypassing `new` is safe, and plain interop keeps us clear of
   class-extension machinery under :simple. `eq` compares the info map so CodeMirror can reuse DOM."
  [{:keys [text title cls] :as info}]
  (let [m (js/Object.create (.-prototype GutterMarker))]
    (set! (.-vvInfo m) info)
    (set! (.-eq m) (fn [other] (= info (.-vvInfo ^js other))))
    (set! (.-toDOM m)
          (fn []
            (let [el (js/document.createElement "div")]
              (set! (.-className el) (str "vv-blame-chip" (when cls (str " " cls))))
              (set! (.-textContent el) text)
              (set! (.-title el) title)
              el)))
    m))

(defn- blame-marker-set
  "hunks → a RangeSet of per-line markers. A hunk's FIRST line carries the author · age chip; its
   continuation lines carry an empty marker that keeps the gutter's border column continuous."
  [^js state hunks now]
  (let [b   (RangeSetBuilder.)
        doc (.-doc state)
        n   (.-lines doc)]
    (loop [i 1]
      (when (<= i n)
        (when-let [h (blame/hunk-for-line hunks i)]
          (let [from  (.-from (.line doc i))
                head? (= i (:final-line h))
                info  (if head?
                        {:text  (if (:uncommitted h)
                                  "Uncommitted"
                                  (str (:author-name h) " · " (blame/rel-date (:author-date h) now)))
                         :title (if (:uncommitted h)
                                  "Not yet committed"
                                  (str (subs (:hash h) 0 8) " " (:summary h) "\n"
                                       (:author-name h) " <" (:author-email h) ">"))
                         :cls   (cond (:uncommitted h) "vv-blame-uncommitted"
                                      (:boundary h)    "vv-blame-boundary"
                                      :else            nil)}
                        {:text "" :title "" :cls "vv-blame-cont"})]
            (.add b from from (blame-marker info))))
        (recur (inc i))))
    (.finish b)))

(defn- blame-extension
  "The gutter extension for one hunk set. The doc is immutable (read-only view), so the marker set
   builds once and is reused; a gutter click reports the 1-based line to `on-line-click`."
  [hunks on-line-click]
  (let [markers (atom nil)]
    (gutter #js {:class   "vv-blame-gutter"
                 :markers (fn [^js view]
                            (or @markers
                                (reset! markers (blame-marker-set (.-state view) hunks (js/Date.now)))))
                 :domEventHandlers
                 #js {:mousedown (fn [^js view ^js line _e]
                                   (on-line-click
                                    (.-number (.lineAt (.. view -state -doc) (.-from line))))
                                   true)}})))

(defn set-blame!
  "Show blame hunks in the mounted source view. No-op when none is mounted — or when the registered
   view was destroyed by a facet flip (its DOM is disconnected; current-view keeps the last view by
   design, exactly like scroll-source-to-line!'s contract)."
  [hunks on-line-click]
  (when-let [^js v @current-view]
    (when (.-isConnected (.-dom v))
      (when-let [^js c @blame-comp]
        (.dispatch v #js {:effects (.reconfigure c (blame-extension hunks on-line-click))})))))

(defn clear-blame!
  "Remove the blame gutter from the mounted source view (same destroyed-view guard as set-blame!)."
  []
  (when-let [^js v @current-view]
    (when (.-isConnected (.-dom v))
      (when-let [^js c @blame-comp]
        (.dispatch v #js {:effects (.reconfigure c #js [])})))))

(defn selection-lines
  "1-based [start end] of the mounted source view's primary selection (the cursor line twice when
   the selection is empty), or nil with no mounted view — the line-range-history seam."
  []
  (when-let [^js v @current-view]
    (let [st   (.-state v)
          main (.. st -selection -main)
          doc  (.-doc st)]
      [(.-number (.lineAt doc (.-from main)))
       (.-number (.lineAt doc (.-to main)))])))

(defn create-source-view
  "Mount a read-only CodeMirror view of text in parent. If grammar (a {:wasm-url :scm-url}) is given,
   asynchronously load the tree-sitter grammar (via renderer.syntax) and reconfigure with highlighting. Returns
   the view."
  [^js parent text grammar prepared-spans]
  (let [hl   (Compartment.)
        bl   (Compartment.)
        exts #js [(.of (.-readOnly EditorState) true)
                  (.of (.-editable EditorView) false)
                  (lineNumbers)
                  (.of bl #js [])                    ; blame gutter (ADR-0040), empty until ensured
                  (.-lineWrapping EditorView)        ; a pre-built Extension, not a Facet (no .of)
                  (.of hl #js [])]
        state (.create EditorState #js {:doc text :extensions exts})
        view  (EditorView. #js {:state state :parent parent})]
    (reset! current-view view)          ; register for source-outline line-scroll
    (reset! blame-comp bl)              ; the blame reconfigure target for THIS mount
    (when-let [l @pending-source-line]  ; consume a pending preview→source jump (deferred across the remount)
      (reset! pending-source-line nil)
      (scroll-source-to-line! l))
    (when grammar
      ;; parse stays in renderer.syntax (@codemirror-free): it resolves highlight SPANS, which we turn into a
      ;; CodeMirror decoration set here — byte-for-byte the same decorations the old in-syntax path produced.
      (if (some? prepared-spans)
        (.dispatch view #js {:effects (.reconfigure hl (highlight-field (decoration-set (spans->decorations prepared-spans))))})
        (-> (syntax/highlight-spans text grammar)
            (.then (fn [spans]
                     (.dispatch view #js {:effects (.reconfigure hl (highlight-field (decoration-set (spans->decorations spans))))})))
            (.catch (fn [e] (js/console.warn "[vv] grammar load failed:" e))))))
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
       "line-info-at"           line-info-at
       "set-blame!"             set-blame!
       "clear-blame!"           clear-blame!
       "selection-lines"        selection-lines})
