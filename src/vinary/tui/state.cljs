(ns vinary.tui.state
  "The TUI's pure key→command reducer. `step` maps a key event to a new state across three modes (:normal scroll,
   :find query entry, :toc overlay), threading the viewport/find/toc pure models. No terminal I/O — the driver feeds
   it events and paints `:vp`/`:find`/`:toc`. A bracketed-paste guard drops pasted text that would otherwise fire
   destructive commands in normal mode (a pasted `q` must not quit); Ctrl-C (byte 0x03 → :interrupt) sets :quit? so
   it funnels through the driver's single-shot terminal restore, same as an external SIGINT."
  (:require [vinary.tui.viewport :as vp]
            [vinary.tui.find :as find]
            [vinary.tui.toc :as toc]))

(defn init
  "Initial state over a viewport `v` and the (possibly empty) toc items."
  [v toc-items]
  {:mode :normal :vp v :find nil :toc (toc/state toc-items) :query "" :pasting? false :quit? false})

(defn- jump [st line] (if line (update st :vp vp/to-line line) st))

(defn- find-step [st stepfn]
  (if (and (:find st) (seq (:matches (:find st))))
    (let [f (stepfn (:find st))] (jump (assoc st :find f) (:line (find/current f))))
    st))

(defn- normal [st ev]
  (case (:type ev)
    :down (update st :vp vp/scroll 1)
    :up   (update st :vp vp/scroll -1)
    :pgdn (update st :vp vp/page 1)
    :pgup (update st :vp vp/page -1)
    :home (update st :vp vp/to-top)
    :end  (update st :vp vp/to-bottom)
    :interrupt (assoc st :quit? true)
    :char (case (:ch ev)
            "j" (update st :vp vp/scroll 1)
            "k" (update st :vp vp/scroll -1)
            " " (update st :vp vp/page 1)
            "b" (update st :vp vp/page -1)
            "g" (update st :vp vp/to-top)
            "G" (update st :vp vp/to-bottom)
            "/" (assoc st :mode :find :query "")
            "n" (find-step st find/next-match)
            "N" (find-step st find/prev-match)
            "t" (if (toc/empty? (:toc st)) st (assoc st :mode :toc))
            "q" (assoc st :quit? true)
            st)
    st))

(defn- find-mode [st ev]
  (case (:type ev)
    :char      (update st :query str (:ch ev))          ; typed OR pasted text both build the query here
    :backspace (update st :query #(subs % 0 (max 0 (dec (count %)))))
    :enter     (let [f (find/start (get-in st [:vp :lines]) (:query st))]
                 (jump (assoc st :mode :normal :find f) (:line (find/current f))))
    :escape    (assoc st :mode :normal :query "")
    :interrupt (assoc st :quit? true)
    st))

(defn- toc-mode [st ev]
  (let [h (max 1 (- (get-in st [:vp :h]) 2))]
    (case (:type ev)
      :up   (update st :toc toc/move -1 h)
      :down (update st :toc toc/move 1 h)
      :enter (jump (assoc st :mode :normal) (toc/selected-line (:toc st)))
      :escape (assoc st :mode :normal)
      :interrupt (assoc st :quit? true)
      :char (case (:ch ev)
              "k" (update st :toc toc/move -1 h)
              "j" (update st :toc toc/move 1 h)
              "t" (assoc st :mode :normal)          ; t closes the overlay
              "q" (assoc st :quit? true)
              st)
      st)))

(defn step
  "Advance the state by one key event. Bracketed-paste markers toggle :pasting?; while pasting in :normal mode a
   :char is IGNORED (not run as a command) so a pasted command byte can't fire."
  [st ev]
  (case (:type ev)
    :paste-start (assoc st :pasting? true)
    :paste-end   (assoc st :pasting? false)
    (if (and (:pasting? st) (= :normal (:mode st)) (= :char (:type ev)))
      st
      (case (:mode st)
        :normal (normal st ev)
        :find   (find-mode st ev)
        :toc    (toc-mode st ev)
        st))))

;; ── driver-facing mutators (streaming / resize) ──────────────────────────────
(defn append-lines
  "Append streamed lines to the viewport; a live find is invalidated (its match indices may have shifted)."
  [st new-lines]
  (-> st (update :vp vp/append new-lines) (assoc :find (when (:find st) (find/start (get-in st [:vp :lines]) (:query st))))))

(defn resize
  "Apply a re-rendered line set at a new terminal size, re-running any active find over the new wrapping."
  [st w h lines toc-items]
  (-> st
      (update :vp vp/resize w h lines)
      (assoc :toc (toc/state toc-items))
      (assoc :find (when (:find st) (find/start lines (:query st))))))
