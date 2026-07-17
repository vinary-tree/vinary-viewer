(ns vinary.main.profile
  "Cold-start profiling marks for the MAIN process, gated on `VV_PROFILE=1` (zero-cost otherwise). Each mark
   prints `[vv-profile] main <name> <ms>` to stdout; `scripts/profile-cold-start.mjs` parses those lines and
   reports the per-phase breakdown. The timestamp is `Date.now()` (wall clock) — the SAME origin the renderer
   uses (`performance.timeOrigin + performance.now()`), so the main and renderer marks sit on one timeline.")

(def on?
  "True iff cold-start profiling is enabled for this process (VV_PROFILE=1)."
  (= "1" (some-> js/process .-env .-VV_PROFILE)))

(defn mark!
  "Emit a profiling mark (a no-op unless VV_PROFILE=1)."
  [name]
  (when on?
    (js/console.log (str "[vv-profile] main " name " " (js/Date.now)))))
