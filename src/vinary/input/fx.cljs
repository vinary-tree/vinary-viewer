(ns vinary.input.fx
  "Effects at the edge for keybinding-driven navigation: scrolling the content viewport and moving DOM
   focus between the sidebar filter and the content pane. Pure-ish (touch the DOM only here)."
  (:require [re-frame.core :as rf]
            [re-frame.db :as rfdb]
            [vinary.async.scheduler :as sched]
            [vinary.input.scroll-math :as sm]
            [vinary.input.keymaps-registry :as registry]))

;; install the keymap set with the given id into the live keymap atom. The MODE is now set synchronously in
;; the dispatching event's :db (events.cljs), so this no longer dispatches :input/set-mode — removing the
;; one-tick window where the atom was the new set but the mode was still :insert. @rfdb/app-db is read only
;; for a CUSTOM set's entry (the :sets map), already committed by re-frame's :db-before-:fx ordering.
(rf/reg-fx
 :keymap/install-active
 (fn [id]
   (registry/install-for! @rfdb/app-db id)))

;; persist the keymap registry EDN to disk, debounced (editor edits stream fast; coalesce the writes).
;; Through the shared scheduler — one live timer per key, genuinely cancellable (ADR-0033).
(rf/reg-fx
 :keymap/persist
 (fn [edn]
   (sched/debounce! ::keymap-persist 400
                    (fn [] (when-let [^js v (.-vv js/window)]
                             (when (.-saveKeymap v) (.saveKeymap v edn)))))))

;; sequence timeout (abandon a half-typed chord/leader after timeout-ms)
(rf/reg-fx
 :input/arm-timeout
 (fn [ms]
   (let [id (js/setTimeout #(rf/dispatch [:input/timeout]) ms)]
     (rf/dispatch [:input/set-timeout-id id]))))

(rf/reg-fx :input/cancel-timeout (fn [id] (when id (js/clearTimeout id))))

(defn- content-el [] (.querySelector js/document ".vv-content"))

;; ---- smooth scrolling ----
;; A single requestAnimationFrame loop eases the focused pane toward an ACCUMULATING target. Each scroll
;; command advances the target; the loop chases it at a fixed fraction per frame. So a held arrow (OS key
;; auto-repeat) keeps advancing the target and produces continuous, smooth motion instead of the choppy
;; per-press `behavior:"smooth"` jumps (which interrupt each other on repeat). The target also accumulates
;; across the in-flight animation, so a single tap eases smoothly too.
(defonce ^:private scroll-anim (atom nil))   ; {:el :top :left :raf :frames} | nil

(defn anim-snapshot
  "DEV/test introspection of the easing loop: nil when idle, else the live target and how many frames it
   has been chasing it. A frame count that keeps climbing while the view does not move is the signature of
   a non-terminating chase (see docs/scientific/09-in-page-find-and-scroll-experiments.md)."
  []
  (when-let [{:keys [^js el top left frames]} @scroll-anim]
    #js {:el     (when el (str (.-tagName el) "." (.-className el)))
         :top    top
         :left   left
         :frames (or frames 0)
         :now    (when el (.-scrollTop el))
         :max    (when el (max 0 (- (.-scrollHeight el) (.-clientHeight el))))}))

;; DEV-only per-frame log. The scroll tracer (vinary.renderer.scroll-trace) answers "who wrote?" from the
;; OUTSIDE; this answers "why won't it stop?" from the INSIDE — it is the only place `top` and `dt` exist.
;; Bounded, so a non-terminating loop cannot exhaust the renderer's memory while we watch it.
(def ^:private anim-log-capacity 512)
(defonce ^:private anim-log (atom (array)))

(defn- log-frame! [ct top dt written after max-top reason]
  (when ^boolean js/goog.DEBUG
    (let [^js buf @anim-log]
      (.push buf #js {:t (.now js/performance) :ct ct :top top :dt dt
                      :written written :after after :maxTop max-top :reason (str reason)
                      ;; a requested step that produced no movement — the sub-pixel-quantum signature
                      :stuck (< (js/Math.abs (- after ct)) 0.001)})
      (when (> (.-length buf) anim-log-capacity) (.shift buf)))))

(defn anim-log-entries "DEV/test: the animator's per-frame ring buffer." [] (.slice ^js @anim-log))
(defn anim-log-clear!  "DEV/test: empty the animator frame log." [] (reset! anim-log (array)) true)

(defn- scrollable? [^js el]
  (and el (instance? js/Element el)
       (> (.-scrollHeight el) (+ (.-clientHeight el) 2))
       (let [oy (.-overflowY (.getComputedStyle js/window el))]
         (or (= oy "auto") (= oy "scroll") (= oy "overlay")))))

(defn- focused-scroll-el
  "The scroll container to move: the focused element's nearest scrollable ancestor; else the content pane —
   or, when the content pane itself doesn't scroll (the source view, whose CodeMirror scrolls inside its
   own .cm-scroller and is never focused), that .cm-scroller. This makes every scroll path (page keys,
   arrows, vim C-f/C-b/C-d/C-u/gg/G, emacs C-v/M-v) reach the source view too."
  []
  (or (loop [n (.-activeElement js/document)]
        (cond (nil? n) nil (scrollable? n) n :else (recur (.-parentElement n))))
      (let [^js content (content-el)]
        (if (or (nil? content) (scrollable? content))
          content
          (or (.querySelector content ".cm-scroller") content)))))

(defn cancel-scroll-anim!
  "Abandon any in-flight chase. Cancels the pending frame so no orphan callback can keep stepping, then
   drops the state. Idempotent, and safe to call from a hot input handler."
  []
  (when-let [{:keys [raf]} @scroll-anim]
    (when raf (js/cancelAnimationFrame raf))
    (reset! scroll-anim nil)))

(defn- geom-of [^js el]
  {:top      (.-scrollTop el)
   :left     (.-scrollLeft el)
   :max-top  (max 0 (- (.-scrollHeight el) (.-clientHeight el)))
   :max-left (max 0 (- (.-scrollWidth el) (.-clientWidth el)))})

;; One animation frame. All of the arithmetic — the live re-clamp, the sub-pixel step floor, the stall
;; bail-out and the frame cap — lives in the pure vinary.input.scroll-math/frame, so the termination
;; properties are unit-tested rather than trusted. This function is only the DOM edge: read geometry,
;; write the decided offsets, re-arm or stop.
(defn- anim-step! []
  (let [{:keys [^js el frames prev] :as s} @scroll-anim]
    (if (or (nil? el) (not (.-isConnected el)))
      (cancel-scroll-anim!)
      (let [geom (geom-of el)
            d    (sm/frame {:top (:top s) :left (:left s)} geom prev (or frames 0))]
        (set! (.-scrollTop el) (:top d))
        (set! (.-scrollLeft el) (:left d))
        (log-frame! (:top geom) (:aim-top d) (- (:aim-top d) (:top geom))
                    (:top d) (.-scrollTop el) (:max-top geom) (:reason d))
        (if (:done? d)
          (do (when (and ^boolean js/goog.DEBUG (= :frame-cap (:reason d)))
                (js/console.warn "[scroll] chase hit the frame cap — target was never reached"))
              (reset! scroll-anim nil))          ; the pending frame is THIS one; nothing to cancel
          (reset! scroll-anim
                  (assoc s :top (:aim-top d) :left (:aim-left d)   ; store the RE-CLAMPED target
                           :prev {:top (:top geom) :left (:left geom)}
                           :frames (inc (or frames 0))
                           :raf (js/requestAnimationFrame anim-step!))))))))

(defn- ease-scroll!
  "Set the eased scroll target via `f-top`/`f-left` (each (fn [base-coord ^js el] → new-coord)), clamped
   to the element's scroll range. Reuses the in-flight target as the base so repeats accumulate."
  [^js el f-top f-left]
  (let [s         @scroll-anim
        same?     (and s (identical? (:el s) el))
        max-top   (max 0 (- (.-scrollHeight el) (.-clientHeight el)))
        max-left  (max 0 (- (.-scrollWidth el) (.-clientWidth el)))
        base-top  (if same? (:top s) (.-scrollTop el))
        base-left (if same? (:left s) (.-scrollLeft el))]
    ;; A chase on a DIFFERENT element must not leave its frame scheduled: the orphaned callback would fire,
    ;; read the NEW state, and arm a second concurrent chain — two callbacks per frame, each stepping.
    (when (and s (not same?) (:raf s)) (js/cancelAnimationFrame (:raf s)))
    (reset! scroll-anim {:el     el
                         :top    (-> (f-top base-top el)   (max 0) (min max-top))
                         :left   (-> (f-left base-left el) (max 0) (min max-left))
                         :prev   nil          ; a fresh target: last frame's geometry is no longer a stall
                         :frames 0            ; and the frame budget restarts with it
                         :raf    (when same? (:raf s))})
    (when-not (and same? (:raf s))
      (swap! scroll-anim assoc :raf (js/requestAnimationFrame anim-step!)))))

;; ---- user input abandons a programmatic scroll -------------------------------------------------------
;; Chromium cancels its own `behavior:"smooth"` scrolls the moment the user scrolls; a hand-rolled chase
;; gets no such courtesy, so it must be wired explicitly. Without this, a chase that is merely SLOW (not
;; broken) still fights the wheel for its duration.
(defonce ^:private cancel-installed? (atom false))

(defn install-scroll-cancel!
  "Abandon an in-flight eased scroll as soon as the user scrolls or points at the document. Capture-phase
   and passive: this never blocks or delays the input it observes."
  []
  (when-not @cancel-installed?
    (reset! cancel-installed? true)
    (let [opts #js {:capture true :passive true}
          bail (fn [_] (cancel-scroll-anim!))]
      (.addEventListener js/window "wheel" bail opts)
      (.addEventListener js/window "touchstart" bail opts)
      (.addEventListener js/window "pointerdown" bail opts))))

(rf/reg-fx
 :dom/scroll
 (fn [{:keys [dy dx to]}]
   (when-let [^js el (focused-scroll-el)]
     (let [keep-top  (fn [t _] t)
           keep-left (fn [l _] l)]
       (cond
         (= to :top)    (ease-scroll! el (fn [_ _]     0)                                 keep-left)
         (= to :bottom) (ease-scroll! el (fn [_ ^js e] (.-scrollHeight e))                keep-left)
         (= dy :page)   (ease-scroll! el (fn [t ^js e] (+ t (* 0.9 (.-clientHeight e))))  keep-left)
         (= dy :-page)  (ease-scroll! el (fn [t ^js e] (- t (* 0.9 (.-clientHeight e))))  keep-left)
         (= dy :half)   (ease-scroll! el (fn [t ^js e] (+ t (* 0.5 (.-clientHeight e))))  keep-left)
         (= dy :-half)  (ease-scroll! el (fn [t ^js e] (- t (* 0.5 (.-clientHeight e))))  keep-left)
         (= dx :right)  (ease-scroll! el keep-top (fn [l ^js e] (+ l (* 0.18 (.-clientWidth e)))))
         (= dx :left)   (ease-scroll! el keep-top (fn [l ^js e] (- l (* 0.18 (.-clientWidth e)))))
         (number? dy)   (ease-scroll! el (fn [t _] (+ t dy)) keep-left)
         (number? dx)   (ease-scroll! el keep-top (fn [l _] (+ l dx)))
         :else          nil)))))

(rf/reg-fx
 :dom/focus
 (fn [target]
   (case target
     :uri     (when-let [^js el (.querySelector js/document ".vv-uri-input")]
                (.focus el)
                (.select el))
     :tree    (some-> ^js (.querySelector js/document ".vv-tree-filter") .focus)
     ;; preventScroll: focusing a scroller normally scrolls it to reveal the focused thing, which would
     ;; make "focus the content pane" silently move the reader's position (and would appear in the scroll
     ;; tracer as an unattributed writer). Works now that .vv-content carries tabindex=-1 — before that
     ;; this call was a silent no-op, since a plain <div> cannot take focus. ADR-0032.
     :content (some-> ^js (content-el) (.focus #js {:preventScroll true}))
     :toggle  (let [active (.-activeElement js/document)
                    tree   (.querySelector js/document ".vv-tree-filter")]
                (if (and tree (= active tree))
                  (some-> ^js (content-el) (.focus #js {:preventScroll true}))
                  (some-> ^js tree .focus)))
     nil)))
