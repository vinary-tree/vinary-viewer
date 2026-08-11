(ns vinary.git.graph-geometry
  "Pure SVG geometry for the Commit Graph document (ADR-0040). Consumes vinary.git.graph's row shape
   ({:hash :lane :edges [{:from :to :kind}] :active}) and produces path data + layout numbers; the
   sidebar mini-rail keeps its own smaller constants, this ns serves the full-pane document. DOM-free
   and unit-tested — every `d` string here is pinned by graph-geometry-test."
  (:require [clojure.string :as str]))

(def row-h 24)
(def lane-w 12)
(def dot-r 3.5)
(def lane-cap 12)
(def palette-size 8)

(defn lane-x
  "The x center of a lane column, clamped to the cap (an over-cap lane draws in the last column)."
  [l]
  (+ (* (min (long l) (dec lane-cap)) lane-w) (/ lane-w 2)))

(defn rail-width
  "The graph column's width for the widest row seen (max-lane is 0-based)."
  [max-lane]
  (* lane-w (min (inc (long max-lane)) lane-cap)))

(defn lane-class
  "The theme-token class for a lane (an 8-hue cycle: --vv-lane-0..7 via .vv-lane-N)."
  [l]
  (str "vv-lane-" (mod (long l) palette-size)))

(defn edge-path
  "SVG `d` for one edge of a row whose dot sits at `lane`. The same self-contained-cell convention
   as the sidebar rail: :pass runs the full row height, :collapse arrives from the row's top edge at
   the dot, and :continue/:branch/:merge leave the dot toward the bottom edge — quadratics with
   mid-height control points keep adjacent (possibly windowed) rows visually continuous."
  [{:keys [from to kind]} lane]
  (let [mid (/ row-h 2)
        xf  (lane-x from)
        xt  (lane-x to)
        xd  (lane-x lane)]
    (case kind
      :pass     (str "M" xf " 0 V " row-h)
      :collapse (str "M" xf " 0 Q " xf " " mid " " xd " " mid)
      :continue (str "M" xd " " mid " V " row-h)
      :branch   (str "M" xd " " mid " Q " xt " " mid " " xt " " row-h)
      :merge    (str "M" xd " " mid " Q " xt " " mid " " xt " " row-h)
      nil)))

(defn row-geometry
  "One row's drawable cell: {:width :dot {:cx :cy :r :class} :paths [{:d :class}] :overflow?}.
   `prev-edges` supplies the top-half ink arriving at the dot (every previous edge targeting the
   dot's lane continues over the border); :overflow? flags lanes clamped at the cap so the view can
   render a » chip."
  [{:keys [lane edges] :as _row} prev-edges max-lane]
  (let [mid   (/ row-h 2)
        paths (-> []
                  (into (keep (fn [{:keys [to]}]
                                (when (= to lane)
                                  {:d (str "M" (lane-x lane) " 0 V " mid)
                                   :class (lane-class lane)})))
                        (or prev-edges []))
                  (into (keep (fn [{:keys [from to kind] :as e}]
                                (when-let [d (edge-path e lane)]
                                  {:d d
                                   :class (lane-class (case kind
                                                        (:pass :collapse) from
                                                        :continue lane
                                                        (:branch :merge) to))})))
                        (or edges [])))]
    {:width     (rail-width max-lane)
     :dot       {:cx (lane-x lane) :cy mid :r dot-r :class (lane-class lane)}
     :paths     paths
     :overflow? (boolean (or (>= (long lane) lane-cap)
                             (some #(or (>= (long (:from %)) lane-cap)
                                        (>= (long (:to %)) lane-cap))
                                   edges)))}))

(defn refs->badges
  "%D decoration strings → [{:name :kind :head?}] with :kind ∈ :local/:remote/:tag. Defensive over
   the raw spellings: \"HEAD -> main\", \"tag: v1.0\", \"origin/main\", bare branch names."
  [refs]
  (into []
        (keep (fn [r]
                (let [r (str/trim (str r))]
                  (cond
                    (str/blank? r) nil
                    (str/starts-with? r "HEAD -> ")
                    {:name (subs r (count "HEAD -> ")) :kind :local :head? true}
                    (= r "HEAD") {:name "HEAD" :kind :local :head? true}
                    (str/starts-with? r "tag: ")
                    {:name (subs r (count "tag: ")) :kind :tag :head? false}
                    (str/includes? r "/")
                    {:name r :kind :remote :head? false}
                    :else {:name r :kind :local :head? false}))))
        refs))

(defn next-cursor
  "Pure keyboard math over the loaded row indices: ↑/↓ ±1, PgUp/PgDn ± visible rows, Home/End,
   clamped to [0, total)."
  [idx total key vis-rows]
  (when (pos? (long total))
    (let [i (long (or idx 0))
          n (long total)
          v (max 1 (long (or vis-rows 1)))]
      (-> (case key
            :up    (dec i)
            :down  (inc i)
            :pgup  (- i v)
            :pgdn  (+ i v)
            :home  0
            :end   (dec n)
            i)
          (max 0)
          (min (dec n))))))

(defn fmt-date
  "\"YYYY-MM-DD HH:mm\" from a strict-ISO date string — locale-free, so tests can pin it. Falls back
   to the raw string when unparsable."
  [iso]
  (let [s (str iso)]
    (if-let [[_ d hh mm] (re-find #"^(\d{4}-\d{2}-\d{2})T(\d{2}):(\d{2})" s)]
      (str d " " hh ":" mm)
      s)))

(defn history-chip-label
  "The dismissible history-mode chip's text (shared by the sidebar panel and the graph toolbar)."
  [{:keys [mode target]}]
  (let [file (some-> (:file target) (str/split #"/") last)]
    (case mode
      :file-history (str "History: " file)
      :line-history (str "History: " file " · L" (:start target) "–" (:end target))
      nil)))
