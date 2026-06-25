(ns vinary.renderer.scroll
  "Per-navigation scroll restore for the content pane. A nav event (back/forward/open/activate) stashes the
   target history entry's scrollTop here; the content component applies it once after the next render — so
   navigating back returns a document to where you were. Live-refresh of the same doc leaves it untouched
   (no pending value → no jump), preserving the reader's position.")

(defonce ^:private pending (atom nil))   ; scrollTop to apply on the next content render, or nil

(defn want!
  "Request that the content scroller be set to n after the next render."
  [n]
  (reset! pending n))

(defn- content-el [^js node]
  (or (some-> node (.closest ".vv-content"))
      (.querySelector js/document ".vv-content")))

(defn apply!
  "If a scroll was requested, apply it to the content scroller and clear it. Called from the content
   component's did-mount/did-update; deferred one frame so the new document has fully laid out (otherwise
   scrollTop clamps to a not-yet-complete scrollHeight)."
  [^js node]
  (when-let [n @pending]
    (reset! pending nil)
    (when-let [^js c (content-el node)]
      (let [set-it (fn [] (set! (.-scrollTop c) n))]
        ;; apply next frame, and once more after late layout (figures/fonts) settles, so a tall document
        ;; doesn't clamp the target to a not-yet-complete scrollHeight
        (js/requestAnimationFrame set-it)
        (js/setTimeout set-it 80)))))

(defn current
  "The content scroller's current scrollTop — read at nav time to save the leaving history entry."
  []
  (or (some-> (.querySelector js/document ".vv-content") .-scrollTop) 0))
