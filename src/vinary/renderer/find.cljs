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
            [vinary.async.scheduler :as sched]
            [vinary.renderer.find-scan :as scan]
            [vinary.renderer.scroll :as scroll]))

;; TWO scheduler keys, and the split is the point.
;;
;; Flattening the document is query-INDEPENDENT, so a new query must not cancel it. Running both phases
;; under one key meant every keystroke threw away a partly-finished walk and started another: on a large
;; document the flatten never completed at all while the user was typing, so every search paid full price
;; and the buffer cache never engaged (measured — docs/scientific/10).
;;
;; Matching IS query-specific, so a new query cancels it immediately.
(def ^:private collect-key ::collect)
(def ^:private match-key   ::match)

(def ^:private empty-state
  {:query   ""      ; the NORMALIZED query the ranges were built for
   :ranges  []      ; live DOM Ranges, in document order
   :idx     0       ; 0-based cursor
   :root    nil     ; the .vv-content element the buffer was built from
   :doc-key nil     ; its data-doc-key at collect time — a different document invalidates outright
   ;; A monotonic mutation COUNTER rather than a dirty flag. Both the flattened buffer and the Ranges
   ;; carry the count they were built at, so each can be judged stale on its own — and, unlike a shared
   ;; boolean, clearing one cannot silently declare the other fresh. It also survives the case a boolean
   ;; could not express: a mutation arriving DURING a build, which must leave the result usable but not
   ;; reusable.
   :mut     0
   :ranges-mut -1   ; the :mut the current Ranges were built at
   :obs     nil     ; the MutationObserver, live only while find is open
   :obs-root nil    ; what it is watching — lets `observe!` be idempotent per root
   ;; cost of the last collect — reported by state-snapshot so a latency figure can be read against the
   ;; size of the work that produced it (docs/scientific/10)
   :ms      0        ; settle latency: start of the pipeline → result, INCLUDING every yield
   :cpu-ms  0        ; main-thread time actually spent inside the slices
   :collect-cpu-ms 0 ; …of which the flatten cost, carried so a cold search can report the whole bill
   :chars   0
   :nodes   0
   :cached? false
   ;; the flattened document — {:text :segs :nodes :root :doc-key :mut} — reused across queries while the
   ;; DOM is unchanged. See `cached-buffer`.
   :buf     nil})

;; in-flight flatten: which root it is walking, and who is waiting for its result
(defonce ^:private collecting (atom {:root nil :waiters []}))

(defonce ^:private state (atom empty-state))

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

;; Work per slice. Both are "enough that the per-slice overhead is negligible, few enough that one slice
;; is far inside a frame"; the scheduler's time budget, not these numbers, is what actually bounds a tick.
(def ^:private walk-batch 512)      ; DOM nodes visited
(def ^:private range-batch 512)     ; matches turned into Ranges

(defn- collect-job
  "Begin a RESUMABLE walk of `root`, feeding a find-scan builder as it goes.

   Resumable because the walk is the expensive half of a search and, on a large document, far longer than
   a frame — 450 ms over 1.1 M characters, measured. A TreeWalker holds its own position, so pausing is
   just returning and resuming is just calling nextNode again; that is the whole reason this is expressible
   without restructuring the traversal.

   The builder is fed inline rather than collecting a token vector first: materializing one map per text
   node would allocate the entire document a second time for no purpose (find-scan/builder)."
  [^js root]
  (let [memo #js {}
        filt #js {:acceptNode
                  (fn [^js n]
                    (cond
                      (= 3 (.-nodeType n))                       js/NodeFilter.FILTER_ACCEPT
                      (reject? memo n)                           js/NodeFilter.FILTER_REJECT
                      (= "BR" (str/upper-case (.-tagName n)))    js/NodeFilter.FILTER_ACCEPT
                      :else                                      js/NodeFilter.FILTER_SKIP))}]
    #js {:walker     (.createTreeWalker js/document root
                                        (bit-or js/NodeFilter.SHOW_ELEMENT js/NodeFilter.SHOW_TEXT)
                                        filt)
         :memo       memo
         :root       root
         :nodes      (array)
         :b          (scan/builder)
         :prevBlock  nil
         :prevParent nil}))

(defn- collect-step!
  "Advance the walk by at most `n` nodes. Returns true while there is more to walk.

   Elements are SKIPped (descend without yielding) except <br>, which becomes a soft boundary, and
   rejected subtrees, which are pruned outright."
  [^js job n]
  (let [^js walker (.-walker job)
        ^js nodes  (.-nodes job)
        ^js b      (.-b job)
        memo       (.-memo job)
        ^js root   (.-root job)]
    (loop [i 0]
      (if (>= i n)
        true
        (if-let [^js node (.nextNode walker)]
          (do
            (if (= 3 (.-nodeType node))
              (let [^js parent (.-parentElement node)
                    block (hard-ancestor memo root parent)]
                ;; a new block starts a new line in the buffer; within one block, an inline-block sibling
                ;; only separates words
                (cond
                  (not (identical? block (.-prevBlock job)))
                  (scan/feed! b {:kind :hard})

                  (and (not (identical? parent (.-prevParent job)))
                       (= :soft (boundary-class memo parent)))
                  (scan/feed! b {:kind :soft})

                  :else nil)
                (let [id (.-length nodes)]
                  (.push nodes node)
                  (scan/feed! b {:kind :text :id id :s (or (.-data node) "")}))
                (set! (.-prevBlock job) block)
                (set! (.-prevParent job) parent))
              ;; <br> — a soft boundary that does NOT advance the block/parent cursor
              (scan/feed! b {:kind :soft}))
            (recur (inc i)))
          false)))))

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

;; ---- painting ----------------------------------------------------------------------------------------

(defn- supported? [] (and (exists? js/CSS) (.-highlights js/CSS) (exists? js/Highlight)))

(defn- paint-current!
  "Set the single-Range 'focused match' highlight. Split from the all-matches paint because cycling
   changes only this one, and rebuilding a Highlight over every match to move a cursor by one was a cost
   proportional to the match count on a keystroke that changes nothing else."
  [ranges idx]
  (when (supported?)
    (let [cur (js/Highlight.)]
      (when (and (seq ranges) (< idx (count ranges))) (.add cur (nth ranges idx)))
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
  (swap! state assoc :obs nil :obs-root nil))

(defn- observe!
  "Watch `root` for the mutations that invalidate the flattened buffer. IDEMPOTENT for a root already
   being watched: re-arming would disconnect first, and disconnecting DISCARDS records the observer has
   queued but not yet delivered — which is precisely the dirty flag the buffer cache depends on. Since a
   search now runs on every keystroke, re-arming per search would give a mutation a fresh chance to be
   dropped on each one."
  [^js root]
  (when-not (and (:obs @state) (identical? root (:obs-root @state)))
    (disconnect-observer!)
    (when (and root (exists? js/MutationObserver))
      (let [o (js/MutationObserver. (fn [_ _] (swap! state update :mut inc)))]
        (.observe o root #js {:subtree true :childList true :characterData true})
        (swap! state assoc :obs o :obs-root root)))))

(defn clear! []
  (sched/cancel! collect-key)
  (sched/cancel! match-key)
  (reset! collecting {:root nil :waiters []})
  (when (supported?)
    (.delete (.-highlights js/CSS) "vv-find")
    (.delete (.-highlights js/CSS) "vv-find-current"))
  (disconnect-observer!)
  (reset! state empty-state))

(defn- result [] (let [{:keys [ranges idx]} @state
                       n (count ranges)]
                   {:count n :idx (if (pos? n) (inc idx) 0)}))

;; ---- the sliced pipeline -----------------------------------------------------------------------------

(defn- cached-buffer
  "The stored flattened buffer when it is still valid for `root`, else nil.

   `docs/theory/06` already stated the property this exploits — *the buffer depends only on the DOM, not
   on the query* — but nothing acted on it: every keystroke rebuilt the whole thing. The precondition for
   reuse was already being computed, too, by the old `ensure-fresh!` for the benefit of `cycle!`: the same
   document, the same content element, and no mutation since. Reusing it makes a keystroke on an unchanged
   document cost a native `indexOf` plus the Ranges, instead of a full DOM walk — measured at 9 ms against
   304 ms on an 808 kB document."
  [^js root]
  (let [{:keys [buf mut]} @state]
    (when (and buf
               (= (:mut buf) mut)
               (identical? (:root buf) root)
               (= (:doc-key buf) (doc-key-of root)))
      buf)))

(defn- stale-ranges?
  "Were the current Ranges built before a mutation? Then they may point at detached nodes, and cycling
   through them would advance the counter while the view sat still."
  []
  (not= (:ranges-mut @state) (:mut @state)))

;; CPU time and settle latency are different quantities once work is sliced, and conflating them would
;; make the ledger unreadable: `:ms` is how long the user waited for a result, `:cpu-ms` is how much main
;; thread that cost. The first includes every yield; the second is what must stay small for typing to feel
;; immediate.
(defn- timed-step
  "Wrap `work` so the main-thread time it consumes accumulates into `st`'s :cpu field."
  [^js st work]
  (fn []
    (let [t (.now js/performance)
          more? (work)]
      (set! (.-cpu st) (+ (.-cpu st) (- (.now js/performance) t)))
      more?)))

(defn- with-buffer!
  "Call `k` with a flattened buffer for `root`, building one if there is no valid cached one.

   The build runs under its OWN scheduler key and is deliberately not cancelled when the query changes,
   because the buffer does not depend on the query. Callers that arrive while a build is in flight simply
   wait for it — only the newest matters, since an older one's result is discarded by the caller's
   generation check anyway, but they are all resumed so no caller is left without a reply.

   A mutation arriving mid-build leaves the buffer USABLE but not REUSABLE: `k` still receives it (the
   walk saw a real document, and this is exactly what the pre-chunked code did), while the mutation
   counter it carries no longer matches, so the next search rebuilds. That is what keeps a continuously
   mutating document from looping forever on rebuild-and-discard."
  [^js root k]
  (if-let [buf (cached-buffer root)]
    (k buf)
    (do
      (swap! collecting update :waiters conj k)
      (when-not (identical? root (:root @collecting))
        (swap! collecting assoc :root root)
        (let [mut0 (:mut @state)
              job  (collect-job root)
              st   #js {:cpu 0}
              step (timed-step st #(collect-step! job walk-batch))
              done (fn []
                     (let [{:keys [text segs]} (scan/finish (.-b job))
                           buf {:text text :segs segs :nodes (.-nodes job)
                                :root root :doc-key (doc-key-of root) :mut mut0}
                           ws  (:waiters @collecting)]
                       (swap! state assoc :buf buf
                              :chars (count text) :nodes (.-length ^js (.-nodes job))
                              :collect-cpu-ms (.-cpu st))
                       (reset! collecting {:root nil :waiters []})
                       (doseq [w ws] (w buf))))]
          (sched/slice! collect-key {:step step :done done}))))))

(defn- run-match!
  "Scan `buf` for `q` and materialize the Ranges, sliced. Cancelled outright by the next keystroke.

   `keep-idx` nil means a fresh search: land on the first match and scroll to it. Non-nil means a
   re-collect behind `cycle!`: clamp the existing cursor and do NOT scroll, because the caller is about to
   move the cursor itself and two scrolls would read as a jump."
  [^js root buf q keep-idx t0 cached? on-done]
  (let [{:keys [text segs nodes]} buf
        st   #js {:matches nil :mi 0 :ranges (array) :hl nil :cpu 0}
        work (fn []
               (if (nil? (.-matches st))
                 ;; scanning is one native indexOf loop over the whole buffer — cheap enough to do whole,
                 ;; but it gets its own slice so the budget is checked before Ranges start allocating
                 (do (set! (.-matches st) (scan/scan text q))
                     (set! (.-hl st) (when (supported?) (js/Highlight.)))
                     true)
                 (let [ms  (.-matches st)
                       n   (count ms)
                       end (min n (+ (.-mi st) range-batch))]
                   (loop [i (.-mi st)]
                     (when (< i end)
                       (when-let [^js r (->range nodes segs (nth ms i))]
                         (.push ^js (.-ranges st) r)
                         (when-let [^js hl (.-hl st)] (.add hl r)))
                       (recur (inc i))))
                   (set! (.-mi st) end)
                   (< end n))))
        done (fn []
               (let [ranges (vec (.-ranges st))
                     idx    (or (when keep-idx (scan/clamp-idx (count ranges) keep-idx)) 0)]
                 (swap! state assoc
                        :query q :ranges ranges :idx idx
                        :root root :doc-key (doc-key-of root)
                        :ranges-mut (:mut buf)
                        :cached? cached?
                        :ms (- (.now js/performance) t0)
                        :cpu-ms (+ (.-cpu st) (if cached? 0 (or (:collect-cpu-ms @state) 0))))
                 (when (supported?)
                   (.set (.-highlights js/CSS) "vv-find" (or (.-hl st) (js/Highlight.))))
                 (paint-current! ranges idx)
                 (when (nil? keep-idx) (scroll-to! ranges idx))
                 (on-done (result))))]
    (sched/slice! match-key {:step (timed-step st work) :done done})))

(defn- start-search!
  "Flatten if necessary, then match. See `with-buffer!` for why those are two jobs and not one."
  [^js root q keep-idx on-done]
  (let [t0      (.now js/performance)
        cached? (some? (cached-buffer root))]
    (with-buffer! root (fn [buf] (run-match! root buf q keep-idx t0 cached? on-done)))))

;; ---- public API --------------------------------------------------------------------------------------

(defn search!
  "Recompute matches for `query`, paint them, and scroll the first into view.

   ASYNCHRONOUS: calls `on-result` with {:count n :idx i} (1-based idx, 0 when nothing matches) once the
   sliced pipeline settles. A newer `search!` cancels an older one, whose `on-result` then never fires —
   so a caller must not treat a missing callback as an error. The generation stamped by the caller remains
   the guard for the other race, an in-flight run whose reply lands after a newer one's."
  [query on-result]
  (let [q (scan/normalize-query query)]
    (if (= "" q)
      (do (clear!) (on-result {:count 0 :idx 0}))
      (if-let [^js root (content-root)]
        (do (observe! root)
            (start-search! root q nil on-result))
        (on-result {:count 0 :idx 0})))))

(defn- step-cursor!
  "Move the cursor by `dir` over the ranges already held, repaint the focused match, and scroll to it."
  [dir]
  (let [{:keys [ranges idx]} @state
        n (count ranges)]
    (if-let [idx' (scan/step-idx n idx dir)]
      (do (swap! state assoc :idx idx')
          (paint-current! ranges idx')
          (scroll-to! ranges idx')
          (result))
      {:count 0 :idx 0})))

(defn cycle!
  "Move the focused match by `dir` (+1 next, -1 previous), wrapping at both ends. Calls `on-result` with
   {:count n :idx i} — the count too, because reconciling with the live DOM can change it.

   Reconciles first, and the two situations get opposite responses. A DIFFERENT document (the doc-key
   changed) makes the ranges meaningless — drop everything. The SAME document with changed nodes (a live
   refresh, a streamed append, a MathJax or mermaid post-pass, a PDF text layer arriving) keeps the query
   meaningful — re-collect and keep the cursor. Without this, `cycle!` walked detached nodes: the counter
   advanced while the view sat still, which is exactly what 'navigating among matches does not work right'
   looked like.

   The re-collect goes through the same sliced pipeline, so a cycle over a document that changed under it
   does not block either."
  [dir on-result]
  (let [^js root (content-root)
        {:keys [query doc-key idx]} @state]
    (cond
      (or (nil? root) (= "" query))    (on-result (result))
      (not= doc-key (doc-key-of root)) (do (clear!) (on-result (result)))
      (stale-ranges?)                  (start-search! root query idx
                                                      (fn [_] (on-result (step-cursor! dir))))
      :else                            (on-result (step-cursor! dir)))))

(defn state-snapshot
  "DEV/test observability: what is actually matched, where it is painted, and whether it is on screen.

   The counter alone could never distinguish 'found 7 matches' from 'found 7 matches and highlighted the
   wrong text', which is why the original defects survived the smoke suite."
  []
  (let [{:keys [query ranges idx ms cpu-ms chars nodes cached?]} @state
        dirty? (stale-ranges?)
        ^js scroller (content-root)
        c-rect (some-> scroller .getBoundingClientRect)]
    {:query   query
     :count   (count ranges)
     :idx     (if (seq ranges) (inc idx) 0)
     :painted (when (supported?)
                (some-> (.get (.-highlights js/CSS) "vv-find") .-size))
     ;; the cost of the last collect, and the size of the document it ran over. Deliberately NOT
     ;; goog.DEBUG-gated: a release build is the one whose latency we actually care about, and these are
     ;; three numbers already sitting in the state atom (docs/scientific/10).
     :ms      ms
     :cpuMs   cpu-ms
     :chars   chars
     :nodes   nodes
     ;; did this search reuse the flattened buffer instead of re-walking the DOM? The single number that
     ;; says whether the incremental path is actually engaging (docs/scientific/10). Question-mark-free
     ;; for the same clj->js reason as :visible below.
     :cached  (boolean cached?)
     ;; has the DOM changed since the buffer was built? `cached` false with `dirty` true means something
     ;; is mutating the content pane between keystrokes and defeating the reuse — a different problem from
     ;; the buffer simply not existing yet, and one only this flag can tell apart.
     :dirty   (boolean dirty?)
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
