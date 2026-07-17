(ns vinary.renderer.profile
  "Cold-start profiling marks for the RENDERER, gated on the `?profile=1` search param that the main process
   puts on the index.html URL when `VV_PROFILE=1` (the renderer has no `process.env` under contextIsolation).
   Each mark prints `[vv-profile] renderer <name> <ms>`, where the timestamp is `performance.timeOrigin +
   performance.now()` — absolute wall-clock, the SAME origin as the main process's `Date.now()` marks, so both
   sit on one timeline for `scripts/profile-cold-start.mjs`. `mark-first-content!` installs a one-shot observer
   that fires `rendered` the first time any content child appears in the pane."
  (:require [clojure.string :as str]))

(def on?
  "True iff the renderer was launched with ?profile=1 (main sets it when VV_PROFILE=1)."
  (boolean (some-> js/window .-location .-search (str/includes? "profile=1"))))

(defn now-ms [] (+ (.-timeOrigin js/performance) (.now js/performance)))

(defn mark!
  "Emit a profiling mark (a no-op unless profiling is on)."
  [name]
  (when on?
    (js/console.log (str "[vv-profile] renderer " name " " (now-ms)))))

(defn mark-first-content!
  "Fire the `rendered` mark once, the first time a content node (markdown body / pdf canvas / source editor /
   image / table / log / diagram) is painted into the pane. A `setInterval` poll (NOT requestAnimationFrame,
   which stalls in a headless/occluded window — only the first frame fires), bounded, stopping the instant
   content appears; on the deadline it reports the pane's fill so a miss is diagnosable."
  []
  (when on?
    (let [sel ".markdown-body, .vv-pdf-doc, .cm-editor, .vv-image-view, .vv-table, .vv-log, .vv-mermaid, .vv-diagram"
          deadline (+ (.now js/performance) 15000)
          id (atom nil)]
      (reset! id
        (js/setInterval
          (fn []
            (cond
              (.querySelector js/document sel)   (do (mark! "rendered") (js/clearInterval @id))
              (>= (.now js/performance) deadline) (js/clearInterval @id)
              :else nil))
          40)))))
