(ns vinary.renderer.find
  "In-page find — the DOM half. Matches are highlighted with the CSS Custom Highlight API (Highlight +
   CSS.highlights + ::highlight()), which paints Ranges without mutating the document DOM, so it composes
   cleanly with the imperative innerHTML content body (ADR-0003). A separate 'current' highlight marks the
   focused match.

   This namespace is the DOM edge only: walk the content pane into a token stream, hand it to the pure
   vinary.renderer.find-scan, turn the matches it reports back into DOM Ranges, paint them, and scroll to
   the focused one. All the string/index arithmetic — and therefore all the behaviour worth asserting —
   lives in find-scan and is unit-tested without a browser.

   Three things here are load-bearing and easy to get wrong (ADR-0032):

   • Ranges may SPAN NODES. That is the whole point of flattening: rendered markup breaks text at every
     inline element, and pdf.js emits one <span> per text run.

   • Some text in the pane must not be searched. The reject list is small and each entry has a reason;
     `mjx-assistive-mml` is the interesting one — MathJax emits a screen-reader MathML duplicate of every
     equation, hidden with `clip`, not `display:none`. It has real layout boxes and `checkVisibility`
     reports it visible, so no generic filter catches it: it has to be named. (renderer.core strips the
     same element from copied selections, for the same reason.)

   • Scrolling is CONFINED to .vv-content via renderer.scroll, never `el.scrollIntoView` — which walks up
     and scrolls every scrollable ancestor, including inner <pre>/table scrollers and #app itself."
  (:require [clojure.string :as str]
            [vinary.renderer.find-scan :as scan]
            [vinary.renderer.scroll :as scroll]))

(defonce ^:private state
  (atom {:query   ""      ; the NORMALIZED query the ranges were built for
         :ranges  []      ; live DOM Ranges, in document order
         :idx     0       ; 0-based cursor
         :root    nil     ; the .vv-content element the buffer was built from
         :doc-key nil     ; its data-doc-key at collect time — a different document invalidates outright
         :dirty?  false   ; set by the MutationObserver: same document, changed nodes
         :obs     nil}))  ; the MutationObserver, live only while find is open

(defn- content-root ^js [] (.querySelector js/document ".vv-content"))
(defn- doc-key-of [^js root] (some-> root .-dataset .-docKey))

;; ---- what counts as searchable text ------------------------------------------------------------------

(def ^:private skip-tags
  #{"SCRIPT" "STYLE" "NOSCRIPT" "TEMPLATE" "SELECT" "OPTION" "TEXTAREA"
    ;; SVG non-rendered content (mermaid, MathJax, svgbob): real text nodes that are never painted
    "TITLE" "DESC" "METADATA" "DEFS"
    ;; MathJax's screen-reader MathML duplicate of every equation. Hidden by a `clip` rule, so it has
    ;; layout boxes and passes every generic visibility test — without this, each equation's text matched
    ;; twice and cycling landed on an invisible copy.
    "MJX-ASSISTIVE-MML"})

;; Tags whose author display is inline but whose COMPUTED display gets blockified by positioning. pdf.js
;; text runs are `position:absolute` <span>s (app.css), so without this escape hatch every run would look
;; like a block and the cross-node matching this whole rewrite exists for would be undone for PDFs.
(def ^:private inline-tags
  #{"SPAN" "A" "EM" "STRONG" "CODE" "B" "I" "U" "S" "SUB" "SUP" "SMALL" "MARK" "ABBR"
    "CITE" "Q" "KBD" "SAMP" "VAR" "TIME" "LABEL" "FONT" "TT" "DFN" "BDI" "BDO" "WBR"})

(defn- style-info*
  "ONE computed-style resolution per element, answering both questions the walk has: is this subtree
   invisible, and does it start a new block?

   Deliberately NOT Element.checkVisibility: that can force layout, and calling it per element made a
   find over a 7 000-record streamed log take longer than the whole smoke suite's budget. Reading
   `display` / `visibility` off the computed style answers the same question for the cases that occur
   here, without layout — and `content-visibility:auto` blocks stay searchable, which is what we want
   (being off-screen is a rendering optimisation, not a statement about the document)."
  [^js el]
  (let [cs  (.getComputedStyle js/window el)
        d   (.-display cs)
        p   (.-position cs)
        tag (some-> (.-tagName el) str/upper-case)]
    {:hidden? (or (= d "none") (= (.-visibility cs) "hidden"))
     :class   (cond
                ;; CSS blockification: an absolutely/fixed-positioned element computes to `display:block`
                ;; whatever the author wrote. Trust the tag instead — a positioned <span> is a text run.
                (and (contains? #{"absolute" "fixed"} p) (contains? inline-tags tag)) :inline
                (or (= d "inline") (= d "contents") (str/starts-with? d "ruby"))      :inline
                (str/starts-with? d "inline-")                                       :soft
                :else                                                                :hard)}))

(defn- style-info
  "`style-info*`, memoized per collect pass.

   Keyed by the element's SHAPE rather than its identity, because getComputedStyle is the only expensive
   call in the walk: a 7 000-record log or a 10 000-row split diff has a handful of distinct shapes, so
   the number of style resolutions is O(#distinct shapes), not O(#elements) — which is what keeps the walk
   linear in the document's text. An element carrying an inline `style` attribute bypasses the cache,
   since its shape is not captured by the key. Ancestors that are themselves hidden are pruned by the
   walker before their descendants are ever classified, so per-element differences within one shape do not
   arise in practice; a miss could only cost a missing or extra boundary, never an error."
  [memo ^js el]
  (if (.hasAttribute el "style")
    (style-info* el)
    (let [^js p (.-parentElement el)
          k (str (some-> p .-tagName) "|" (some-> p .-className) "|"
                 (.-tagName el) "|" (.-className el))]
      (or (aget memo k)
          (let [v (style-info* el)]
            (aset memo k v)
            v)))))

(defn- reject?
  "Should this element's whole subtree be skipped? Attribute and tag checks first — they are free — so the
   style resolution is only reached for elements that survive them."
  [memo ^js el]
  (let [tag (some-> (.-tagName el) str/upper-case)]
    (or (contains? skip-tags tag)
        (.hasAttribute el "hidden")
        (= "true" (.getAttribute el "aria-hidden"))
        (:hidden? (style-info memo el)))))

(defn- boundary-class [memo ^js el] (:class (style-info memo el)))

(defn- hard-ancestor
  "The nearest ancestor of `el` (inclusive) that starts a new block, or `root`."
  [memo ^js root ^js el]
  (loop [^js n el]
    (cond
      (or (nil? n) (identical? n root)) root
      (= :hard (boundary-class memo n)) n
      :else (recur (.-parentElement n)))))

(defn- collect-tokens
  "Walk `root` into [tokens node-table]. Elements are SKIPped (descend without yielding) except <br>,
   which becomes a soft boundary, and rejected subtrees, which are pruned outright."
  [^js root]
  (let [tokens (transient [])
        nodes  (array)
        memo   #js {}
        filt   #js {:acceptNode
                    (fn [^js n]
                      (cond
                        (= 3 (.-nodeType n))                       js/NodeFilter.FILTER_ACCEPT
                        (reject? memo n)                           js/NodeFilter.FILTER_REJECT
                        (= "BR" (str/upper-case (.-tagName n)))    js/NodeFilter.FILTER_ACCEPT
                        :else                                      js/NodeFilter.FILTER_SKIP))}
        walker (.createTreeWalker js/document root
                                  (bit-or js/NodeFilter.SHOW_ELEMENT js/NodeFilter.SHOW_TEXT)
                                  filt)]
    (loop [prev-block nil, prev-parent nil]
      (if-let [^js n (.nextNode walker)]
        (if (= 3 (.-nodeType n))
          (let [^js parent (.-parentElement n)
                block (hard-ancestor memo root parent)]
            ;; a new block starts a new line in the buffer; within one block, an inline-block sibling only
            ;; separates words
            (cond
              (not (identical? block prev-block)) (conj! tokens {:kind :hard})
              (and (not (identical? parent prev-parent))
                   (= :soft (boundary-class memo parent))) (conj! tokens {:kind :soft})
              :else nil)
            (let [id (.-length nodes)]
              (.push nodes n)
              (conj! tokens {:kind :text :id id :s (or (.-data n) "")}))
            (recur block parent))
          (do (conj! tokens {:kind :soft})            ; <br>
              (recur prev-block prev-parent)))
        nil))
    [(persistent! tokens) nodes]))

;; ---- ranges ------------------------------------------------------------------------------------------

(defn- ->range
  "A DOM Range for one match, possibly spanning nodes. nil when either endpoint fails to resolve."
  [^js nodes segs m]
  (when-let [{:keys [start end]} (scan/match-endpoints segs m)]
    (let [^js sn (aget nodes (:id start))
          ^js en (aget nodes (:id end))]
      (when (and sn en)
        ;; Offsets are clamped defensively: the node table is a snapshot, and a characterData mutation
        ;; racing the walk would otherwise throw IndexSizeError and kill the whole search.
        (let [so (min (:off start) (.-length sn))
              eo (min (:off end) (.-length en))
              r  (.createRange js/document)]
          (.setStart r sn so)
          (.setEnd r en eo)
          r)))))

(defn- collect-ranges
  "All matches of `query` under `root`, as DOM Ranges in document order."
  [^js root query]
  (let [q (scan/normalize-query query)]
    (if (or (nil? root) (= "" q))
      []
      (let [[tokens nodes] (collect-tokens root)
            {:keys [text segs]} (scan/build tokens)]
        (into [] (keep #(->range nodes segs %)) (scan/scan text q))))))

;; ---- painting ----------------------------------------------------------------------------------------

(defn- supported? [] (and (exists? js/CSS) (.-highlights js/CSS) (exists? js/Highlight)))

(defn- paint! [ranges idx]
  (when (supported?)
    (let [all (js/Highlight.)
          cur (js/Highlight.)]
      (doseq [r ranges] (.add all r))
      (when (and (seq ranges) (< idx (count ranges))) (.add cur (nth ranges idx)))
      (.set (.-highlights js/CSS) "vv-find" all)
      (.set (.-highlights js/CSS) "vv-find-current" cur))))

;; ---- scrolling ---------------------------------------------------------------------------------------

(defn- target-rect
  "The rect to scroll to for `r`.

   A Range inside a `content-visibility:auto` block that the browser has SKIPPED has no client rects of
   its own — its descendants were never laid out — but the containing block does have one, sized by
   contain-intrinsic-size. Fall back to it so a match in a not-yet-rendered streamed block still scrolls
   approximately into view; `correct!` then refines once the block has really been laid out."
  [^js r]
  (let [rect (.getBoundingClientRect r)]
    (if (and (pos? (.-length (.getClientRects r)))
             (or (pos? (.-width rect)) (pos? (.-height rect))))
      rect
      (loop [^js el (some-> (.-startContainer r) .-parentElement)]
        (cond
          (nil? el) nil
          (pos? (.-length (.getClientRects el))) (.getBoundingClientRect el)
          :else (recur (.-parentElement el)))))))

(defn- scroll-to!
  "Bring the focused match to the middle of the content pane, scrolling ONLY that pane.

   Two-phase: scroll instantly, then re-measure on the next frame and correct if the target moved. It can
   move because scrolling a `content-visibility:auto` block into view is what causes it to be laid out for
   the first time, replacing its contain-intrinsic-size estimate with a real height. The correction is
   skipped if the user scrolled in between — never fight them."
  [ranges idx]
  (when-let [^js r (nth ranges idx nil)]
    (when-let [^js scroller (scroll/scroller-of (some-> (.-startContainer r) .-parentElement))]
      (when-let [rect (target-rect r)]
        (let [asked (scroll/scroll-rect-to! scroller rect {:block :center :behavior "auto"})]
          (js/requestAnimationFrame
           (fn [_]
             (when (and asked (< (js/Math.abs (- (.-scrollTop scroller) asked)) 1.5))
               (when-let [rect' (target-rect r)]
                 (let [again (scroll/confined-top
                              (.-scrollTop scroller)
                              (.-top (.getBoundingClientRect scroller))
                              (.-clientHeight scroller)
                              (max 0 (- (.-scrollHeight scroller) (.-clientHeight scroller)))
                              (.-top rect') (.-height rect') :center 0)]
                   (when (> (js/Math.abs (- again (.-scrollTop scroller))) 4)
                     (.scrollTo scroller #js {:top again :behavior "auto"}))))))))))))

;; ---- invalidation ------------------------------------------------------------------------------------

(defn- disconnect-observer! []
  (when-let [^js o (:obs @state)] (.disconnect o))
  (swap! state assoc :obs nil))

(defn- observe! [^js root]
  (disconnect-observer!)
  (when (and root (exists? js/MutationObserver))
    (let [o (js/MutationObserver. (fn [_ _] (swap! state assoc :dirty? true)))]
      (.observe o root #js {:subtree true :childList true :characterData true})
      (swap! state assoc :obs o))))

(defn clear! []
  (when (supported?)
    (.delete (.-highlights js/CSS) "vv-find")
    (.delete (.-highlights js/CSS) "vv-find-current"))
  (disconnect-observer!)
  (reset! state {:query "" :ranges [] :idx 0 :root nil :doc-key nil :dirty? false :obs nil}))

(defn- result [] (let [{:keys [ranges idx]} @state
                       n (count ranges)]
                   {:count n :idx (if (pos? n) (inc idx) 0)}))

(defn- recollect!
  "Rebuild the ranges for the stored query against the live DOM, keeping the cursor as close as possible."
  [^js root]
  (let [{:keys [query idx]} @state
        ranges (collect-ranges root query)
        idx'   (or (scan/clamp-idx (count ranges) idx) 0)]
    (swap! state assoc :ranges ranges :idx idx' :root root :doc-key (doc-key-of root) :dirty? false)
    (paint! ranges idx')
    (result)))

(defn- ensure-fresh!
  "Reconcile the stored ranges with the live DOM before using them.

   Two situations, opposite responses. A DIFFERENT document (the doc-key changed) makes the ranges
   meaningless — drop everything. The SAME document with changed nodes (a live refresh, a streamed append,
   a MathJax or mermaid post-pass, a PDF text layer arriving) keeps the query meaningful — re-collect and
   keep the cursor. Without this, `cycle!` walked detached nodes: the counter advanced while the view sat
   still, which is exactly what 'navigating among matches does not work right' looked like."
  []
  (let [^js root (content-root)
        {:keys [query doc-key dirty?]} @state]
    (cond
      (or (nil? root) (= "" query)) (result)
      (not= doc-key (doc-key-of root)) (do (clear!) (result))
      dirty? (recollect! root)
      :else (result))))

;; ---- public API --------------------------------------------------------------------------------------

(defn search!
  "Recompute matches for `query`, paint them, and scroll the first into view.
   Returns {:count n :idx i} with a 1-based idx (0 when there are no matches)."
  [query]
  (let [q (scan/normalize-query query)]
    (if (= "" q)
      (do (clear!) {:count 0 :idx 0})
      (let [^js root (content-root)
            ranges (collect-ranges root q)]
        (swap! state assoc :query q :ranges ranges :idx 0
               :root root :doc-key (doc-key-of root) :dirty? false)
        (observe! root)
        (paint! ranges 0)
        (scroll-to! ranges 0)
        (result)))))

(defn cycle!
  "Move the focused match by `dir` (+1 next, -1 previous), wrapping at both ends.
   Returns {:count n :idx i} — the count too, because reconciling with the live DOM can change it."
  [dir]
  (ensure-fresh!)
  (let [{:keys [ranges idx]} @state
        n (count ranges)]
    (if-let [idx' (scan/step-idx n idx dir)]
      (do (swap! state assoc :idx idx')
          (paint! ranges idx')
          (scroll-to! ranges idx')
          (result))
      {:count 0 :idx 0})))

(defn state-snapshot
  "DEV/test observability: what is actually matched, where it is painted, and whether it is on screen.

   The counter alone could never distinguish 'found 7 matches' from 'found 7 matches and highlighted the
   wrong text', which is why the original defects survived the smoke suite."
  []
  (let [{:keys [query ranges idx]} @state
        ^js scroller (content-root)
        c-rect (some-> scroller .getBoundingClientRect)]
    {:query   query
     :count   (count ranges)
     :idx     (if (seq ranges) (inc idx) 0)
     :painted (when (supported?)
                (some-> (.get (.-highlights js/CSS) "vv-find") .-size))
     ;; keys are deliberately question-mark-free: this map crosses into JS through clj->js, which would
     ;; otherwise produce a property literally named "visible?" — awkward to read from a test
     :matches (mapv (fn [^js r]
                      (let [rect (target-rect r)]
                        {:text   (.toString r)
                         :top    (some-> rect .-top)
                         :height (some-> rect .-height)
                         :visible (boolean (and rect c-rect
                                                (>= (.-bottom rect) (.-top c-rect))
                                                (<= (.-top rect) (.-bottom c-rect))))}))
                    ranges)}))
