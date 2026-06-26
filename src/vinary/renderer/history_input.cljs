(ns vinary.renderer.history-input
  "Coalescing for browser-style history commands that can arrive through multiple native input channels.")

(def duplicate-window-ms 180)

(defn accept
  "Return [state' accepted?] for a history input `dir` at millisecond time `now`.
   Repeated same-direction inputs inside duplicate-window-ms are treated as the same physical gesture."
  [state dir now]
  (let [{last-dir :dir last-time :time} state
        last-time (or last-time 0)]
    (if (and (= dir last-dir) (<= (- now last-time) duplicate-window-ms))
      [state false]
      [{:dir dir :time now} true])))
