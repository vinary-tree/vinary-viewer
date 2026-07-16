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
            [vinary.ir.semiring :as sr]
            [vinary.ir.lattice :as lattice]
            [vinary.ir.earley :as earley]
            [vinary.ir.forest :as forest]))

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

;; ---- weighted-optimal block segmentation (lattice ∩ CFG → Viterbi forest) ----
;; The greedy `group-blocks` splits at a single global vertical-gap threshold (1.6×median), which mis-segments
;; multi-column pages, tables, and figure captions. `group-blocks-weighted` instead builds a LATTICE of
;; candidate blocks — one Tropical-costed edge per span [i,j) — parses the trivial `Blocks → Block+` grammar
;; against it, and takes the Viterbi-best partition (ir.forest/viterbi-parse). The block cost balances abnormal
;; vertical gaps AND horizontal (column) spread against a per-block penalty λ, so the GLOBAL optimum separates
;; paragraphs and columns a local threshold cannot. This activates the weighted substrate (ir.{lattice,earley,
;; forest}) for its intended purpose: genuinely ambiguous segmentation (plan G1). Bounded (candidate span ≤
;; `max-block-span`, page ≤ `max-weighted-lines`); above the bound, or for ≤2 lines, it falls back to greedy.
(def ^:private max-block-span    30)   ; longest candidate block (a paragraph rarely exceeds this) — bounds edges
(def ^:private max-weighted-lines 120) ; above this a page is dense enough that the O(n·span) parse isn't worth it

(defn- line-x [line] (apply min (map :x line)))

(defn- block-cost
  "Tropical cost of grouping lines [i,j) into ONE block: excess vertical gap above the typical line spacing
   `typ` (a paragraph break inside a block is penalized) + β·horizontal spread (mixing columns is penalized) +
   a per-block penalty λ (so the optimizer neither shatters into singletons nor fuses everything)."
  [linev i j typ lambda beta]
  (let [sub (subvec linev i j)
        ys  (mapv line-y sub)
        xs  (mapv line-x sub)
        excess (reduce + 0.0 (map (fn [a b] (max 0.0 (- (js/Math.abs (- a b)) typ))) ys (rest ys)))
        spread (if (> (count xs) 1) (- (apply max xs) (apply min xs)) 0.0)]
    (+ excess (* beta spread) lambda)))

(defn group-blocks-weighted
  "Weighted-optimal line→block segmentation via the lattice/Earley/forest substrate (see the section comment).
   Returns the same [[line…]…] shape as `group-blocks`. `opts` may override :block-penalty (λ) and
   :column-weight (β). Falls back to the greedy `group-blocks` for ≤2 lines or a page over max-weighted-lines."
  ([lines] (group-blocks-weighted lines {}))
  ([lines opts]
   (let [linev (vec lines)
         n     (count linev)]
     (if (or (<= n 2) (> n max-weighted-lines))
       (group-blocks lines)
       (let [ys     (mapv line-y linev)
             gaps   (vec (sort (map (fn [a b] (js/Math.abs (- a b))) ys (rest ys))))
             typ    (if (seq gaps) (nth gaps (quot (count gaps) 2)) 0)
             ;; λ = 0.6·typ makes the additive vertical term split at gap > 1.6·typ — identical to the greedy
             ;; baseline for single-column (which it thus subsumes); the non-additive β·column-spread term is
             ;; what lets the GLOBAL optimum separate columns a per-gap threshold cannot.
             lambda (get opts :block-penalty (* 0.6 (max typ 1)))
             beta   (get opts :column-weight 0.5)
             one    (sr/tropical 0)
             edges  (for [i (range n), j (range (inc i) (inc (min n (+ i max-block-span))))]
                      {:from i :to j :label [i j] :weight (sr/tropical (block-cost linev i j typ lambda beta))})
             lat    (lattice/from-edges (inc n) edges)
             prods  (into [{:lhs :S :rhs [:Bs] :weight one}
                           {:lhs :Bs :rhs [:B :Bs] :weight one}
                           {:lhs :Bs :rhs [:B] :weight one}]
                          (map (fn [{:keys [label]}] {:lhs :B :rhs [label] :weight one})) edges)
             chart  (earley/parse (earley/grammar :S prods one) lat)]
         (if-let [[tree _] (forest/viterbi-parse chart)]
           (->> (tree-seq :children :children tree) (keep :terminal) (mapv (fn [[i j]] (subvec linev i j))))
           (group-blocks lines)))))))   ; not recognized (shouldn't happen) → greedy fallback

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
    (node/node :page (mapv #(block->ir page-num %) (group-blocks-weighted (group-lines items))) {:page page-num})))

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

(defn reflow-ir
  "Transform the fixed-layout :page/:block/:line/:run PDF IR into a REFLOWABLE prose :document: each block
   becomes a :paragraph of its joined line text; a lone heading-sized line becomes a :heading. Lowered through
   ir.backend.html this yields extracted text that reflows to the viewport width — the opt-in reflow view.
   The faithful canvas render is unaffected (this is a separate, additive facet)."
  [pdf-ir]
  (let [lines   (filterv #(= :line (node/kind %)) (node/preorder pdf-ir))
        heights (sort (remove nil? (map line-height lines)))
        median  (if (seq heights) (nth heights (quot (count heights) 2)) 0)
        block->node
        (fn [b]
          (let [blines (filterv #(= :line (node/kind %)) (node/children b))
                txt    (str/join " " (remove str/blank? (map line-text blines)))]
            (when (seq txt)
              (if (and (= 1 (count blines)) (> (or (line-height (first blines)) 0) (* 1.3 median)))
                (node/node :heading   [(node/leaf :text txt)] {:level 2})
                (node/node :paragraph [(node/leaf :text txt)] {})))))]
    (node/node :document
               (into [] (comp (filter #(= :block (node/kind %))) (keep block->node)) (node/preorder pdf-ir))
               {})))

(defn outline
  "A heading TOC from relative font size: lines taller than ~1.3× the median line height are headings, leveled
   by descending size (largest = level 1). The id is the page anchor `vv-pdf-page-<page>` so a Contents click
   scrolls to the heading's page via the existing PDF page navigation. Intended as a fallback for PDFs whose
   getOutline is empty."
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
              (map (fn [l]
                     {:level (level l)
                      :text  (str/trim (line-text l))
                      :id    (str "vv-pdf-page-" (get-in (node/node-meta l) [:bbox :page]))}))
              heads)))))
