(ns vinary.renderer.scroll-trace
  "DEV-ONLY scroll-write tracer. Installs a pass-through patch over `Element.prototype`'s scrollTop /
   scrollLeft setters and its scrollTo / scrollBy / scrollIntoView methods, recording every write to a
   WATCHED scroller (default: .vv-content, #app, body, html) into a bounded ring buffer together with the
   writer's stack, the requested value, and the value that ACTUALLY stuck after the write.

   Why a prototype patch and not per-call-site instrumentation: the question this tracer exists to answer is
   'which writer reverts the user's scroll?' — and the interesting answer may be a writer we have not
   inventoried (a library, or a call site added later). Instrumenting only the ~10 known sites would beg
   the question.

   Recording `after` (the post-write scrollTop) is the point of the whole thing for the runaway-animator
   hypothesis: a write whose `requested` never equals its `after` is a CLAMPED write, which is exactly the
   condition under which vinary.input.fx/anim-step! can never reach its target and re-arms forever.

   Installed only under ^boolean goog.DEBUG, so it is dead-code-eliminated from :release. Exposed as
   window.__vvscrolltrace() alongside the other DEV seams in vinary.renderer.core.

   Note on shape: every patched function is written as a ZERO-ARG (fn [] …) reading (js-arguments). A
   ClojureScript variadic (fn [& args] …) would move the body into the arity-variadic function, where
   `this` is no longer the receiver — so the patch would silently lose the element it is tracing."
  (:require [clojure.string :as str]))

(def ^:private capacity 2048)

(defonce ^:private installed? (atom false))
(defonce ^:private ring (atom {:buf (array) :n 0}))     ; :n = total writes ever (buf is bounded)
(defonce ^:private watching (atom ".vv-content, #app, body, html"))
(defonce ^:private paused? (atom false))

(defn- class-list
  "Up to three class names, read off classList so an SVG element's SVGAnimatedString className can't
   stringify to \"[object SVGAnimatedString]\"."
  [^js el]
  (let [cl (.-classList el)
        n  (min 3 (or (some-> cl .-length) 0))]
    (str/join (map (fn [i] (str "." (.item cl i))) (range n)))))

(defn- describe
  "A short, stable identifier for a scroller: tag#id.class1.class2 (classes capped, so a streamed body's
   long class list can't drown the record)."
  [^js el]
  (if (nil? el)
    "nil"
    (let [tag (str/lower-case (or (.-tagName el) "?"))
          id  (let [i (.-id el)] (if (and i (not= "" i)) (str "#" i) ""))]
      (str tag id (class-list el)))))

(defn- watched?
  "Does this write target one of the scrollers under investigation? `matches` is absent on non-elements
   and throws on a bad selector, so guard both."
  [^js el]
  (boolean (and el (.-matches el)
                (try (.matches el @watching) (catch :default _ false)))))

(defn- caller-stack
  "The JS stack with this namespace's own frames trimmed, so the first line is the actual writer."
  []
  (let [raw (or (.-stack (js/Error. "scroll-trace")) "")]
    (->> (str/split raw #"\n")
         (drop 1)                                        ; the Error line itself
         (remove #(str/includes? % "scroll_trace"))
         (take 8)
         (map str/trim)
         (str/join " | "))))

(defn- record! [^js el op from requested after]
  (when-not @paused?
    (let [entry #js {:t         (.now js/performance)
                     :el        (describe el)
                     :op        (str op)
                     :from      from
                     :requested requested
                     :after     after
                     ;; did the write move the scroller AT ALL? A write that requests a change and produces
                     ;; none is the sub-quantum step of hypothesis H1-B — the animator's `dt` never shrinks,
                     ;; so it re-arms forever. This flag is the primary discriminator.
                     :moved     (and (number? from) (number? after)
                                     (> (js/Math.abs (- after from)) 0.001))
                     ;; a clamped write is the signature of an unreachable scroll target (H1-A)
                     :clamped   (and (number? requested) (number? after)
                                     (> (js/Math.abs (- requested after)) 0.5))
                     :stack     (caller-stack)}
          ^js buf (:buf @ring)]
      (.push buf entry)
      (when (> (.-length buf) capacity) (.shift buf))
      (swap! ring update :n inc))))

;; ---- the patches ------------------------------------------------------------------------------------
;; Each wraps the ORIGINAL accessor/method and always delegates to it, so behaviour is unchanged: the
;; tracer must never itself perturb what it measures.

(defn- patch-accessor! [^js proto prop]
  (when-let [^js desc (js/Object.getOwnPropertyDescriptor proto prop)]
    (let [^js orig-get (.-get desc)
          ^js orig-set (.-set desc)]
      (when (and orig-get orig-set)
        (js/Object.defineProperty
         proto prop
         #js {:configurable true
              :enumerable   (.-enumerable desc)
              :get          (fn [] (this-as this (.call orig-get this)))
              :set          (fn []
                              (this-as this
                                (let [v     (aget (js-arguments) 0)
                                      watch (watched? this)
                                      ;; read BEFORE the write so `moved` is measurable
                                      from  (when watch (.call orig-get this))]
                                  (.call orig-set this v)
                                  (when watch
                                    (record! this prop from v (.call orig-get this))))))})))))

(defn- patch-method! [^js proto prop]
  (when-let [^js orig (aget proto prop)]
    (aset proto prop
          (fn []
            (this-as this
              (let [args  (js-arguments)
                    watch (watched? this)
                    from  (when watch (.-scrollTop ^js this))
                    ret   (.apply orig this args)]
                (when watch
                  ;; scrollTo/scrollBy/scrollIntoView are asynchronous under behavior:"smooth", so the
                  ;; read-back is the value at CALL time; the ensuing animation shows up as browser frames
                  ;; with no JS write, which is exactly what we want to tell apart from a JS write.
                  (record! this prop from (aget args 0) (.-scrollTop ^js this)))
                ret))))))

(defn install!
  "Patch the scroll accessors/methods. Idempotent; a no-op outside a browser."
  []
  (when (and (exists? js/Element) (not @installed?))
    (reset! installed? true)
    (let [proto (.-prototype js/Element)]
      (patch-accessor! proto "scrollTop")
      (patch-accessor! proto "scrollLeft")
      (patch-method!   proto "scrollTo")
      (patch-method!   proto "scrollBy")
      (patch-method!   proto "scrollIntoView"))))

;; ---- the DEV hook -----------------------------------------------------------------------------------

(defn- api []
  #js {:entries (fn [] (.slice ^js (:buf @ring)))
       :count   (fn [] (:n @ring))
       :clear   (fn [] (reset! ring {:buf (array) :n 0}) true)
       :pause   (fn [] (reset! paused? true) true)
       :resume  (fn [] (reset! paused? false) true)
       ;; narrow or widen what is recorded, e.g. __vvscrolltrace().watch(".vv-content")
       :watch   (fn [] (let [sel (aget (js-arguments) 0)] (reset! watching sel) sel))
       ;; only the writes the browser could not satisfy — the unreachable-target signature (H1-A)
       :clamped (fn [] (.filter ^js (:buf @ring) (fn [e] (.-clamped ^js e))))
       ;; writes that asked for a change and produced NO movement — the sub-quantum signature (H1-B)
       :unmoved (fn [] (.filter ^js (:buf @ring)
                                (fn [^js e] (and (not (.-moved e))
                                                 (number? (.-from e)) (number? (.-requested e))
                                                 (> (js/Math.abs (- (.-requested e) (.-from e))) 0.001)))))
       ;; "who wrote, how often" — the first line of each entry's stack, tallied
       :writers (fn []
                  (let [tally #js {}]
                    (.forEach ^js (:buf @ring)
                              (fn [^js e]
                                (let [k (first (str/split (or (.-stack e) "?") #" \| "))]
                                  (aset tally k (inc (or (aget tally k) 0))))))
                    tally))})

(defn expose!
  "Install the tracer and publish window.__vvscrolltrace. DEV builds only — callers must gate on
   ^boolean goog.DEBUG so :release drops both this ns's body and the call."
  []
  (install!)
  (set! (.-__vvscrolltrace js/window) (fn [] (api))))
