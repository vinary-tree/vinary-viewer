(ns vinary.tui.toc
  "The TUI's table-of-contents overlay — PURE. `build` resolves the document's toc entries ({:level :text :id} from
   ir.capability.toc) to rendered LINE indices via the anchor map ir.backend.ansi/render-lines produced (so a jump
   lands on the exact line a heading/record was rendered to — no fragile text matching). The overlay is a selectable
   windowed list; `selected-line` is the jump target the driver feeds to viewport/to-line."
  (:refer-clojure :exclude [empty?])
  (:require [clojure.string :as str]))

(def ^:private ESC (str (char 27)))

(defn build
  "[{:level :text :line} …] for every toc entry whose id resolves to a rendered line (entries with no anchor —
   e.g. a heading inside a not-yet-streamed region — are dropped)."
  [toc anchors]
  (->> toc
       (keep (fn [{:keys [level text id]}]
               (when-let [line (get anchors id)]
                 {:level (or level 1) :text (str/trim (str text)) :line line})))
       vec))

(defn state [items] {:items (vec items) :sel 0 :top 0})

(defn empty? [st] (zero? (count (:items st))))

(defn move
  "Move the selection by `d`, clamped; keep the selection within a `h`-row window (scroll the list `:top`)."
  [st d h]
  (let [n (count (:items st))]
    (if (zero? n)
      st
      (let [sel (-> (+ (:sel st) d) (max 0) (min (dec n)))
            top (:top st)
            top (cond (< sel top) sel
                      (>= sel (+ top h)) (inc (- sel h))
                      :else top)]
        (assoc st :sel sel :top (max 0 top))))))

(defn selected-line [st] (get-in st [:items (:sel st) :line]))

(defn overlay-lines
  "Render the outline to at most `h` rows (windowed around the selection), each `w` wide: `indent · text`, the
   selected row in reverse-video. Returns [str …] for the driver to paint."
  [st w h]
  (let [items (:items st) top (:top st) sel (:sel st)
        win   (subvec items (min top (count items)) (min (+ top h) (count items)))]
    (if (seq win)
      (map-indexed
       (fn [i {:keys [level text]}]
         (let [idx  (+ top i)
               pad  (apply str (repeat (* 2 (dec level)) " "))
               body (let [s (str pad "• " text)] (subs s 0 (min (count s) (max 1 w))))
               body (str body (apply str (repeat (max 0 (- w (count body))) " ")))]
           (if (= idx sel) (str ESC "[7m" body ESC "[27m") body)))
       win)
      ["  (no headings)"])))
