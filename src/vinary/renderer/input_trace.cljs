(ns vinary.renderer.input-trace
  "DEV-ONLY text-input latency tracer — the instrument for docs/scientific/10.

   It answers two separate questions, because the reported defect ('keys take a few hundred milliseconds
   to appear, and if I type too quickly some keys are missed') is really two defects wearing one coat:

   • WHY IS IT LATE?  A trusted keydown carries `event.timeStamp` on the same clock as
     `performance.now()`, stamped when the browser GENERATED the event — not when it dispatched it. So
     `now - timeStamp` is exactly how long the keystroke sat in the input queue waiting for the main
     thread, which is the user-perceived lag. Nothing else in the app measures it.

   • WHY IS IT LOST?  A native edit never goes through the `value` setter — the browser mutates the
     field's value internally and then fires `input`. Therefore EVERY call to the setter is a
     PROGRAMMATIC write, and a programmatic write that replaces what the user typed with something
     shorter or different is the React controlled-input clobber (RC1). Patching the prototype accessor
     catches it whoever the writer is, which is the same argument vinary.renderer.scroll-trace makes for
     patching scrollTop rather than instrumenting known call sites.

   A third, corroborating signal comes free: `longtask` PerformanceObserver entries (plus a rAF gap
   sampler as a fallback) tell us which long main-thread task the late keystroke was queued behind.

   Widgets mark the end of their own work with `mark!`, so a record can be read end-to-end:
   keydown → input → next paint → work settled.

   Installed only under ^boolean goog.DEBUG, so :release drops both this namespace's body and the call.
   Exposed as window.__vvinputtrace() alongside the other DEV seams in vinary.renderer.core.

   Note on shape: the patched accessor is written as a ZERO-ARG (fn [] …) reading (js-arguments), for the
   reason spelled out in scroll-trace — a ClojureScript variadic would move the body into the
   arity-variadic function, where `this` is no longer the receiver."
  (:require [clojure.string :as str]))

(def ^:private capacity 4096)

(defonce ^:private installed? (atom false))
(defonce ^:private keystrokes (atom {:buf (array) :n 0}))   ; one record per keydown on a watched field
(defonce ^:private writes     (atom {:buf (array) :n 0}))   ; programmatic .value writes
(defonce ^:private tasks      (atom {:buf (array) :n 0}))   ; long main-thread tasks
(defonce ^:private marks      (atom {:buf (array) :n 0}))   ; widget-declared "work settled" points
(defonce ^:private paused?    (atom false))
(defonce ^:private pending    (atom nil))                   ; the keydown awaiting its input event

(defonce ^:private watching
  (atom (str "input:not([type=checkbox]):not([type=radio]):not([type=button])"
             ":not([type=submit]):not([type=range]), textarea")))

(defn- push!
  "Append to a bounded ring, dropping the oldest entry past `capacity`. `:n` counts every entry ever, so a
   summary can tell 'nothing happened' apart from 'the window scrolled past it'."
  [ring entry]
  (let [^js buf (:buf @ring)]
    (.push buf entry)
    (when (> (.-length buf) capacity) (.shift buf))
    (swap! ring update :n inc)))

(defn- describe
  "A short, stable identifier for a field: tag.class#id — enough to tell the find bar from the tree filter
   in a dump without carrying a DOM reference into the record."
  [^js el]
  (if (nil? el)
    "nil"
    (let [tag (str/lower-case (or (.-tagName el) "?"))
          cls (let [c (.-className el)] (if (and (string? c) (not= "" c)) (str "." (str/replace (str/trim c) #"\s+" ".")) ""))
          id  (let [i (.-id el)] (if (and i (not= "" i)) (str "#" i) ""))]
      (str tag cls id))))

(defn- watched? [^js el]
  (boolean (and el (.-matches el)
                (try (.matches el @watching) (catch :default _ false)))))

(defn- printable?
  "Does this key insert exactly one character? `key` is a single code point for printable keys and a word
   ('Shift', 'Backspace', 'ArrowLeft') for everything else — so the length test is the whole check, once
   the modifier chords that never insert are excluded."
  [^js e]
  (let [k (.-key e)]
    (and (string? k)
         (= 1 (count k))
         (not (.-ctrlKey e))
         (not (.-metaKey e))
         (not (.-altKey e)))))

(defn- selection-open?
  "Is a non-empty selection about to be REPLACED by this keystroke? A printable key typed over a selection
   shortens the field, which would otherwise be indistinguishable from a swallowed key — and the URI bar
   select-all-on-focus makes that a routine occurrence, not a corner case. `selectionStart` throws on input
   types that do not support it, so both reads are guarded."
  [^js el]
  (try
    (let [s (.-selectionStart el) e (.-selectionEnd el)]
      (boolean (and (number? s) (number? e) (not= s e))))
    (catch :default _ false)))

;; ---- keystroke path ----------------------------------------------------------------------------------

(defn- on-keydown [^js e]
  (when-not @paused?
    (let [^js el (.-target e)]
      (when (watched? el)
        (let [now (.now js/performance)
              ts  (.-timeStamp e)
              rec #js {:t          now
                       :key        (.-key e)
                       :field      (describe el)
                       ;; how long the keystroke waited for the main thread: the user-perceived lag
                       :queueMs    (if (and (number? ts) (pos? ts) (>= now ts)) (- now ts) js/NaN)
                       :printable  (printable? e)
                       :selection  (selection-open? el)
                       :before     (.-value el)
                       :after      nil
                       :inputMs    js/NaN     ; keydown → input event
                       :paintMs    js/NaN     ; keydown → the frame that painted it
                       :settledMs  js/NaN     ; keydown → the widget declaring its work done
                       :settleLabel nil
                       :inserted   nil        ; what the field actually gained (nil = nothing)
                       :lost       false}     ; a printable key that inserted nothing
              ]
          (reset! pending rec)
          (push! keystrokes rec)
          ;; A printable key that never reaches `input` produced no character at all. Resolve that on the
          ;; next frame rather than never: `pending` is cleared by the input handler when it does arrive.
          (js/requestAnimationFrame
           (fn [_]
             (when (identical? rec @pending)
               (reset! pending nil)
               (set! (.-after rec) (.-value el))
               (set! (.-lost rec) (boolean (.-printable rec)))))))))))

(defn- swallowed?
  "Did this printable keystroke insert nothing? The field failing to grow means either the key was
   swallowed (the defect) or it replaced a selection (not a defect), and `:selection` — captured at
   keydown, before the edit — is what tells them apart."
  [^js rec before after]
  (boolean (and (.-printable rec)
                (not (.-selection rec))
                (<= (count after) (count before)))))

(defn- on-input [^js e]
  (when-not @paused?
    (let [^js el (.-target e)]
      (when (watched? el)
        (let [now (.now js/performance)
              v   (.-value el)
              ^js rec @pending]
          (when rec
            (reset! pending nil)
            (set! (.-inputMs rec) (- now (.-t rec)))
            (set! (.-after rec) v)
            (let [before (.-before rec)]
              (set! (.-inserted rec) (when (> (count v) (count before)) (- (count v) (count before))))
              (set! (.-lost rec) (swallowed? rec before v)))
            (js/requestAnimationFrame
             (fn [_] (set! (.-paintMs rec) (- (.now js/performance) (.-t rec)))))))))))

;; ---- programmatic value writes (the clobber detector) ------------------------------------------------

(defn- patch-value-accessor!
  "Wrap the `value` accessor on `proto` with a pass-through that records writes to watched fields.

   Always delegates, so behaviour is unchanged — the tracer must never perturb what it measures. React's
   input-value tracker installs its OWN descriptor on each node that delegates to this prototype
   descriptor, so React's writes are seen whichever order the two installations happen in."
  [^js proto]
  (when-let [^js desc (js/Object.getOwnPropertyDescriptor proto "value")]
    (let [^js orig-get (.-get desc)
          ^js orig-set (.-set desc)]
      (when (and orig-get orig-set)
        (js/Object.defineProperty
         proto "value"
         #js {:configurable true
              :enumerable   (.-enumerable desc)
              :get          (fn [] (this-as this (.call orig-get this)))
              :set          (fn []
                              (this-as this
                                (let [v     (aget (js-arguments) 0)
                                      watch (and (not @paused?) (watched? this))
                                      from  (when watch (.call orig-get this))]
                                  (.call orig-set this v)
                                  (when (and watch (not= from v))
                                    (push! writes
                                           #js {:t     (.now js/performance)
                                                :field (describe this)
                                                :from  from
                                                :to    v
                                                ;; THE clobber signature: a programmatic write that
                                                ;; discards characters the user had already typed
                                                :clobber (boolean (and (string? from) (string? v)
                                                                       (> (count from) (count v))
                                                                       (str/starts-with? from v)))
                                                :stack (->> (str/split (or (.-stack (js/Error. "iw")) "") #"\n")
                                                            (drop 1)
                                                            (remove #(str/includes? % "input_trace"))
                                                            (take 6)
                                                            (map str/trim)
                                                            (str/join " | "))})))))})))))

;; ---- long main-thread tasks --------------------------------------------------------------------------

(defn- observe-long-tasks!
  "Record `longtask` entries — the task a late keystroke was queued behind. Not universally available, so
   a rAF gap sampler runs alongside it: any frame interval materially longer than a 60 Hz frame is a block
   whether or not the entry type exists."
  []
  (when (exists? js/PerformanceObserver)
    (try
      (let [obs (js/PerformanceObserver.
                 (fn [^js list _]
                   (when-not @paused?
                     (doseq [^js e (array-seq (.getEntries list))]
                       (push! tasks #js {:t (.-startTime e) :ms (.-duration e) :src "longtask"})))))]
        (.observe obs #js {:entryTypes #js ["longtask"]}))
      (catch :default _ nil)))
  (let [last (atom (.now js/performance))]
    (letfn [(frame [_]
              (let [now (.now js/performance)
                    dt  (- now @last)]
                (reset! last now)
                ;; 32 ms = two 60 Hz frames; below that a gap is ordinary scheduling jitter
                (when (and (not @paused?) (> dt 32))
                  (push! tasks #js {:t (- now dt) :ms dt :src "frame-gap"}))
                (js/requestAnimationFrame frame)))]
      (js/requestAnimationFrame frame))))

;; ---- widget-declared settle points -------------------------------------------------------------------

(defn mark!
  "Record that `label`'s work for the current keystroke has settled (find published its count, the tree
   filter committed, …). Safe to call from release builds: it is a no-op until `expose!` has run."
  [label]
  (when (and @installed? (not @paused?))
    (let [now (.now js/performance)
          ^js buf (:buf @keystrokes)
          ^js rec (when (pos? (.-length buf)) (aget buf (dec (.-length buf))))]
      (when (and rec (js/isNaN (.-settledMs rec)))
        (set! (.-settledMs rec) (- now (.-t rec)))
        (set! (.-settleLabel rec) (str label)))
      (push! marks #js {:t now :label (str label)}))))

;; ---- summary -----------------------------------------------------------------------------------------

(defn- percentile [^js xs p]
  (if (zero? (.-length xs))
    js/NaN
    (let [sorted (.sort (.slice xs) (fn [a b] (- a b)))
          i (js/Math.min (dec (.-length sorted))
                         (js/Math.floor (* p (.-length sorted))))]
      (aget sorted i))))

(defn- finite-values [^js buf prop]
  (let [out (array)]
    (.forEach buf (fn [^js e] (let [v (aget e prop)] (when (and (number? v) (js/isFinite v)) (.push out v)))))
    out))

(defn- summary
  "The measurement, reduced to comparable numbers.

   `blockMs` and `frameGapMs` are reported SEPARATELY on purpose. A `longtask` entry is a real
   main-thread occupancy, whoever caused it. A rAF frame gap is only evidence of one when frames are
   actually being served: Chromium throttles requestAnimationFrame for an occluded window — which is
   every window under xvfb — so under a headless harness the gap sampler reports a steady ~1 s cadence
   that has nothing to do with the app. Merging the two would have made every scenario look blocked."
  []
  (let [^js ks (:buf @keystrokes)
        ^js ws (:buf @writes)
        ^js ts (:buf @tasks)
        q  (finite-values ks "queueMs")
        s  (finite-values ks "settledMs")
        lost (.filter ks (fn [^js e] (.-lost e)))
        clob (.filter ws (fn [^js e] (.-clobber e)))
        long-tasks (.filter ts (fn [^js e] (= "longtask" (.-src e))))
        gaps       (.filter ts (fn [^js e] (= "frame-gap" (.-src e))))
        blk  (finite-values long-tasks "ms")
        gap  (finite-values gaps "ms")]
    #js {:keystrokes  (:n @keystrokes)
         :lostKeys    (.-length lost)
         :clobbers    (.-length clob)
         ;; the user-perceived lag, in one number
         :queueMs     #js {:p50 (percentile q 0.50) :p95 (percentile q 0.95) :max (percentile q 1.0)}
         :settledMs   #js {:p50 (percentile s 0.50) :p95 (percentile s 0.95) :max (percentile s 1.0)}
         :blockMs     #js {:p50 (percentile blk 0.50) :p95 (percentile blk 0.95) :max (percentile blk 1.0)}
         :frameGapMs  #js {:p50 (percentile gap 0.50) :p95 (percentile gap 0.95) :max (percentile gap 1.0)}
         :blocks      (.-length long-tasks)
         ;; total main-thread occupancy across the window. Divided by the window's wall time this gives a
         ;; saturation figure, which is the honest way to say "the renderer had no time to service input":
         ;; four 450 ms searches inside 1.65 s of typing is 109% — the queue can only grow.
         :blockTotalMs (.reduce blk (fn [a b] (+ a b)) 0)}))

;; ---- install / expose --------------------------------------------------------------------------------

(defn install!
  "Patch the value accessors, attach the key listeners, and start the task observer. Idempotent."
  []
  (when (and (exists? js/document) (not @installed?))
    (reset! installed? true)
    (when (exists? js/HTMLInputElement)    (patch-value-accessor! (.-prototype js/HTMLInputElement)))
    (when (exists? js/HTMLTextAreaElement) (patch-value-accessor! (.-prototype js/HTMLTextAreaElement)))
    ;; capture phase for keydown so the record exists before any app handler can preventDefault or
    ;; re-dispatch; bubble phase for input so the field's value is final when we read it
    (.addEventListener js/document "keydown" on-keydown true)
    (.addEventListener js/document "input" on-input false)
    (observe-long-tasks!)))

(defn- api []
  #js {:entries  (fn [] (.slice ^js (:buf @keystrokes)))
       :writes   (fn [] (.slice ^js (:buf @writes)))
       :blocks   (fn [] (.slice ^js (:buf @tasks)))
       :marks    (fn [] (.slice ^js (:buf @marks)))
       ;; the two defect signatures, pre-filtered
       :lost     (fn [] (.filter ^js (:buf @keystrokes) (fn [^js e] (.-lost e))))
       :clobbers (fn [] (.filter ^js (:buf @writes) (fn [^js e] (.-clobber e))))
       :summary  (fn [] (summary))
       :watch    (fn [] (let [sel (aget (js-arguments) 0)] (reset! watching sel) sel))
       :pause    (fn [] (reset! paused? true) true)
       :resume   (fn [] (reset! paused? false) true)
       :clear    (fn []
                   (reset! keystrokes {:buf (array) :n 0})
                   (reset! writes {:buf (array) :n 0})
                   (reset! tasks {:buf (array) :n 0})
                   (reset! marks {:buf (array) :n 0})
                   (reset! pending nil)
                   true)})

(defn expose!
  "Install the tracer and publish window.__vvinputtrace. DEV builds only — callers must gate on
   ^boolean goog.DEBUG so :release drops both this ns's body and the call."
  []
  (install!)
  (set! (.-__vvinputtrace js/window) (fn [] (api))))
