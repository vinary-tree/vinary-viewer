(ns vinary.ir.frontend.pdf
  "PDF front-end (hybrid, lossless by construction): convert pdf.js text-content items into the common IR as
   a reflowable :page > :block > :line > :run tree with a per-node :bbox — the text/structure facet that
   drives find, TOC, copy, and (opt-in) reflow. The pdf.js canvas raster stays the faithful VISUAL facet, so
   no display fidelity is ever discarded (fixed-layout figures remain exact). Grouping runs into lines and
   lines into blocks is genuinely ambiguous; each grouping decision carries a Tropical cost (vertical
   misalignment / gap), so the weighted machinery ranks the segmentation — with a deterministic greedy
   baseline that is correct for the common single-column case. `outline` derives a heading TOC from relative
   font size, for the many PDFs that ship no getOutline. Pure + DOM-free (operates on text-item DATA, not the
   DOM), so it is fully unit-testable with synthetic items."
  (:require [clojure.string :as str]
            [vinary.ir.node :as node]
            [vinary.ir.semiring :as sr]))

;; ---- item normalization ----
(defn normalize-item
  "A pdf.js text item {str, transform:[a b c d e f], width, height, fontName} → a plain map
   {:str :x :y :w :h :font}. e/f are the glyph-run origin; d approximates the glyph height when height is 0."
  [^js it]
  (let [t (.-transform it)]
    {:str  (.-str it)
     :x    (aget t 4)
     :y    (aget t 5)
     :w    (.-width it)
     :h    (let [h (.-height it)] (if (and h (pos? h)) h (js/Math.abs (aget t 3))))
     :font (.-fontName it)}))

;; ---- geometry ----
(defn- bbox-of [page items]
  (let [x1 (apply min (map :x items))
        y1 (apply min (map :y items))
        x2 (apply max (map #(+ (:x %) (:w %)) items))
        y2 (apply max (map #(+ (:y %) (:h %)) items))]
    {:x x1 :y y1 :w (- x2 x1) :h (- y2 y1) :page page}))

(defn- same-line?
  "Two items share a line when their baselines overlap within half the taller glyph height."
  [a b]
  (< (js/Math.abs (- (:y a) (:y b))) (* 0.5 (max (:h a) (:h b)))))

(defn- line-cost
  "Tropical cost of placing item `b` on the same line as `a`: the vertical baseline offset (0 = perfectly
   aligned). Lower = a more confident grouping; the weight lets the forest rank alternative segmentations."
  [a b] (sr/tropical (js/Math.abs (- (:y a) (:y b)))))

;; ---- run → line → block segmentation ----
(defn group-lines
  "Group items into lines in reading order (top→bottom, left→right). Returns a seq of lines; each line is a
   vector of items sorted left→right."
  [items]
  (->> (sort-by (juxt #(- (:y %)) :x) items)          ; y descending (PDF y grows upward), x ascending
       (reduce (fn [lines it]
                 (let [last-line (peek lines)]
                   (if (and last-line (same-line? (peek last-line) it))
                     (conj (pop lines) (conj last-line it))
                     (conj lines [it]))))
               [])
       (mapv #(vec (sort-by :x %)))))

(defn- line-y [line] (:y (first line)))

(defn group-blocks
  "Group consecutive lines into blocks: a vertical gap larger than ~1.6× the median inter-line gap starts a
   new block. Fewer than two lines ⇒ a single block."
  [lines]
  (if (<= (count lines) 1)
    (if (seq lines) [lines] [])
    (let [gaps      (mapv (fn [a b] (js/Math.abs (- (line-y a) (line-y b)))) lines (rest lines))
          median    (nth (sort gaps) (quot (count gaps) 2))
          threshold (* 1.6 (max median 1))]
      (loop [[l & more] lines, cur [], blocks []]
        (cond
          (nil? l)     (if (seq cur) (conj blocks cur) blocks)
          (empty? cur) (recur more [l] blocks)
          (> (js/Math.abs (- (line-y (peek cur)) (line-y l))) threshold) (recur more [l] (conj blocks cur))
          :else        (recur more (conj cur l) blocks))))))

;; ---- IR construction ----
(defn- run->ir  [page it]   (node/leaf :run (:str it) {:bbox {:x (:x it) :y (:y it) :w (:w it) :h (:h it) :page page} :font (:font it)}))
(defn- line->ir [page line]
  (node/node :line (mapv #(run->ir page %) line)
             {:bbox (bbox-of page line)
              :weight (reduce (fn [w [a b]] (sr/times w (line-cost a b)))
                              (sr/tropical 0) (map vector line (rest line)))}))
(defn- block->ir [page lines]
  (node/node :block (mapv #(line->ir page %) lines) {:bbox (bbox-of page (apply concat lines))}))

(defn page->ir
  "One page's normalized items → a :page node (blocks → lines → runs), each carrying its :bbox."
  [page-num items]
  (if (empty? items)
    (node/node :page [] {:page page-num})
    (node/node :page (mapv #(block->ir page-num %) (group-blocks (group-lines items))) {:page page-num})))

(defn doc->ir
  "A seq of [page-num normalized-items] → a :document of :page nodes."
  [pages]
  (node/node :document (mapv (fn [[n items]] (page->ir n items)) pages) {}))

;; ---- capabilities ----
(defn line-text [line-node] (str/join "" (map node/text (node/children line-node))))

(defn page-text
  "The text of a :page node in reading order (runs joined per line, lines by newline) — for in-page find and
   copy over the extracted text facet."
  [page-node]
  (->> (node/preorder page-node)
       (filter #(= :line (node/kind %)))
       (map line-text)
       (str/join "\n")))

(defn- line-height [line-node] (get-in (node/node-meta line-node) [:bbox :h]))

(defn outline
  "A heading TOC from relative font size: lines taller than ~1.3× the median line height are headings, leveled
   by descending size (largest = level 1). id = `p<page>-l<line-index>` for page/line navigation. Intended as
   a fallback for PDFs whose getOutline is empty."
  [ir]
  (let [lines   (filterv #(= :line (node/kind %)) (node/preorder ir))
        heights (sort (remove nil? (map line-height lines)))]
    (if (empty? heights)
      []
      (let [median (nth heights (quot (count heights) 2))
            head?  (fn [l] (> (or (line-height l) 0) (* 1.3 median)))
            heads  (filter head? lines)
            sizes  (->> heads (map line-height) distinct (sort >) vec)
            level  (fn [l] (inc (min 5 (.indexOf sizes (line-height l)))))]
        (into []
              (map-indexed (fn [i l]
                             {:level (level l)
                              :text  (str/trim (line-text l))
                              :id    (str "p" (get-in (node/node-meta l) [:bbox :page]) "-h" i)}))
              heads)))))
