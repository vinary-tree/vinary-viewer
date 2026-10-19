(ns vinary.slides.notes
  "Where a deck's SPEAKER NOTES are (ADR-0048 §1–§5, N1) — pure, DOM-free and pdf.js-free, so the GUI engine, the
   unit tests and `vv --cli --deck-info` share one definition. vinary.slides.evidence reads the document and feeds
   this namespace the page text it asks for; vinary.slides.deck takes the result as a set of hidden pages.

   Vocabulary (docs/GLOSSARY.md):
     split notes   Beamer's `show notes on second screen=<side>`: every page is a slide half plus a notes half
     note page     Beamer's `show notes`: a whole page of notes after the overlay it belongs to, labelled with its own
                   physical page number. Interleaved note pages leave the audience's sequence (deck :hidden)
     text notes    the /Contents of a page's annotations (pdfpc's `\\pdfpcnote`, a sticky note) and the pdfpc sidecar
     run           one pdf.js text item as plain data {:str s :t [a b c d e f]}: its string and its transform (page
                   units, y up), whose (e, f) is the run's origin and whose height is |(c, d)|

   Finding note pages (ADR-0048 §2): the LABEL AUTOMATON (`interleaving`) decides a deck from its page labels alone when
   exactly one reading of them obeys Beamer's rules. Several readings are settled by the PROBE (`probe-next`), which
   reads two pages of a deck without notes. A deck without labels is settled by the SCAN (`scan-step`). A reading from
   a driver whose labels lag a pass (XeLaTeX, dvipdfmx, dvips) is CONFIRMED page by page (`confirm-pairs`). All three
   compare pages with the NOTE-PAGE SIGNATURE (`note-page-signature?`): a note page holds a scaled copy of its slide's
   text. `initial` says which of them a deck needs; vinary.slides.evidence/settle runs them after the first paint."
  (:require [clojure.string :as str]
            [vinary.slides.deck :as deck]))

;; ---- labels -------------------------------------------------------------------------------------------------------

(defn- label-int
  "A page label that is a canonical non-negative decimal integer (as Beamer writes frame numbers and hyperref writes
   page numbers), as a number; nil otherwise."
  [s]
  (when (and (string? s) (re-matches #"0|[1-9]\d{0,8}" s)) (js/parseInt s 10)))

(defn integer-labels
  "The page labels as integers when EVERY label is a canonical non-negative integer, else nil (ADR-0048 §2.1: the
   automaton runs only then)."
  [labels]
  (when (seq labels)
    (let [v (mapv label-int labels)]
      (when (every? some? v) v))))

;; ---- 2.1 the label automaton --------------------------------------------------------------------------------------
;; A PARSE gives each page a role, slide or note, and is valid when it obeys Beamer's rules: page 0 is a slide and the
;; frame number starts at f := L0; a slide carries f or f + 1 (an overlay, or a new frame) and sets f to it; a note
;; page is a page after page 0 that carries its own physical page number, Li = i + 1, and leaves f alone.
;;
;; Call page j ELIGIBLE when j ≥ 1 and Lj = j + 1: only those can be note pages. Every other page, and page 0, is a
;; REQUIRED slide, and sets f to its own label whatever came before it. Between two consecutive required slides r < r'
;; lies a GAP of eligible pages r+1 … r'-1, whose labels are consecutive (Lj = j + 1). Two slides chosen in a gap with
;; no slide between them are adjacent pages, because a slide's label is the previous slide's or one more and two
;; eligible labels differ by their distance. So the slides a parse chooses in a gap form one BLOCK [x y], or none. A
;; parse is one choice per gap, the gaps are independent, and each gap's choices are few:
;;   no block (the whole gap is notes)  valid when L(r') ∈ {L(r), L(r) + 1}              (r' = nil, the tail: always)
;;   a block [x y]                       x ∈ {L(r) − 1, L(r)} (x's label x + 1 follows L(r)), and
;;                                       y ∈ {L(r') − 2, L(r') − 1} (L(r') follows y's label y + 1); at the tail, any y ≥ x
;; The valid parses are the product of the gaps' choices (docs/theory/12 §2 proves it), found in one pass. When L0 ≤ 1,
;; only the FIRST gap can have several choices: along any valid parse a slide's label is at most its page index + 1, so
;; a later required slide r has L(r) ≤ r, and x ∈ {L(r) − 1, L(r)} leaves no x ≥ r + 1.

(defn- gap-choices
  "The valid choices of the gap after required slide `r` (up to required slide `r'`, nil at the tail; its pages are
   r+1 … hi): nil for no block, [x y] for a block of slides."
  [L n r r']
  (let [lr     (nth L r)
        hi     (if r' (dec r') (dec n))
        ok?    (fn [f] (or (nil? r') (let [l (nth L r')] (or (= l f) (= l (inc f))))))
        xs     (filter #(<= (inc r) % hi) [(dec lr) lr])
        ys     (fn [x] (if r'
                         (filter #(<= x % hi) [(- (nth L r') 2) (dec (nth L r'))])
                         (range x (inc hi))))
        blocks (for [x xs, y (ys x) :when (ok? (inc y))] [x y])]
    (cond-> (vec blocks) (ok? lr) (conj nil))))

(defn- gap-notes
  "The note pages a choice leaves in the gap r+1 … hi: every page outside its block."
  [r hi choice]
  (if-let [[x y] choice]
    (into #{} (remove #(<= x % y)) (range (inc r) (inc hi)))
    (set (range (inc r) (inc hi)))))

(defn interleaving
  "Decide a deck's note pages from its integer page labels `L` (integer-labels) by the automaton of ADR-0048 §2.1.
   Returns, by :status:
     :none        the only valid parse has no note page (not interleaved)
     :invalid     no parse is valid: shown as it is. A label that drops, as a restarted frame counter makes it, usually
                  leaves none, but not always: 1 2 3 4 5 1 2 is :proven (docs/theory/12 §2.4.1, restartable?)
     :proven      exactly one parse is valid and it has note pages: {:note-pages #{…}}
     :ambiguous   L0 ≤ 1 and several parses are valid. They differ only in the deck's PLAIN RUN, pages 1 … :run-end,
                  where candidate a reads pages a … :run-end as note pages: {:candidates [a…] ascending, :run-end,
                  :base #{…} (the note pages every parse shares, after the run), :fallback? (whether the parse with no
                  note page in the run is valid: its note pages are :base)}. The probe chooses.
     :undecidable several parses are valid and L0 ≥ 2 (a frame counter set by hand before the first frame): shown as
                  it is, even when they differ only by distinct first note pages in the first gap, the case the probe
                  decides. With L0 ≤ 1 several valid parses are always :ambiguous (docs/theory/12 §2.3, Theorem 12),
                  and the first test for :undecidable, two gaps with a choice, never holds (§2.2, Proposition 8)."
  [L]
  (let [L (vec L)
        n (count L)]
    (if (< n 2)
      {:status :none}
      (let [eligible? (fn [j] (= (nth L j) (inc j)))
            required  (into [0] (remove eligible?) (range 1 n))
            gaps      (conj (mapv vector required (rest required)) [(peek required) nil])
            choices   (mapv (fn [[r r']] (gap-choices L n r r')) gaps)
            hi-of     (fn [[_ r']] (if r' (dec r') (dec n)))
            notes-of  (fn [k choice] (gap-notes (first (nth gaps k)) (hi-of (nth gaps k)) choice))]
        (cond
          (some empty? choices)
          {:status :invalid}

          (every? #(= 1 (count %)) choices)
          (let [notes (into #{} (mapcat (fn [k] (notes-of k (first (nth choices k))))) (range (count gaps)))]
            (if (empty? notes) {:status :none} {:status :proven :note-pages notes}))

          :else
          (let [multi  (filterv #(> (count (nth choices %)) 1) (range (count gaps)))
                g      (first multi)
                r      (first (nth gaps g))
                hi     (hi-of (nth gaps g))
                base   (into #{} (mapcat (fn [k] (when (not= k g) (notes-of k (first (nth choices k))))))
                             (range (count gaps)))
                ;; in the plain run every choice reads "slides 1 … y, notes y+1 … hi", or all notes: candidate y + 1
                firsts (map (fn [c]
                              (if-let [[x y] c]
                                (when (= x (inc r)) (if (< y hi) (inc y) ::no-note))
                                (inc r)))
                            (nth choices g))]
            (if (or (> (count multi) 1) (not= 0 r) (>= (nth L 0) 2) (some nil? firsts)
                    (not (apply distinct? firsts)))
              {:status :undecidable}
              {:status     :ambiguous
               :candidates (vec (sort (remove #{::no-note} firsts)))
               :run-end    hi
               :base       base
               :fallback?  (boolean (some #{::no-note} firsts))})))))))

(defn restartable?
  "Do the labels `L` also read as ALL SLIDES with a frame counter restarted wherever a label drops, i.e. is every
   label at most one more than the one before it? Then a parse with note pages is not proven by the labels alone: the
   plain run 1 2 … m of a deck without notes, followed by an appendix that restarts at 1 (appendixnumberbeamer), reads
   as one slide and m − 1 note pages, a parse the rules admit and nothing in the labels refutes (docs/theory/12 §2.4).
   Any note page after a slide outside the plain run makes its label jump by two or more, so with L0 ≤ 1 a proven parse
   reads so exactly when its note pages are one run directly after the plain run (docs/theory/12 §2.4.5,
   Proposition 16). Of the n-page decks whose counter never restarts, the 2^(n−2) − 1 forms with L0 = 1, note pages
   1 … m, page m + 1 an overlay of frame 1 and only slides after it are proven so (Corollary 17); their note pages are
   confirmed by their content (ADR-0048 §2.5)."
  [L]
  (every? (fn [i] (<= (nth L i) (inc (nth L (dec i))))) (range 1 (count L))))

(defn candidate-notes
  "The note pages of an :ambiguous deck's candidate `a`: pages a … :run-end, plus :base."
  [il a]
  (into (:base il) (range a (inc (:run-end il)))))

(defn fallback-notes
  "The note pages of an :ambiguous deck's parse with no note page in its plain run (:base, #{} when every page is a
   slide), or nil when that parse is not valid."
  [il]
  (when (:fallback? il) (:base il)))

;; ---- 2.2 the note-page signature ----------------------------------------------------------------------------------

(def max-thumbnail-scale
  "A thumbnail is the slide drawn at σ ≤ this. Beamer's note templates use 0.25 and 0.125; a template's own frame title
   repeats the slide's title at 0.69, and the bound excludes it."
  0.4)

(def scale-tolerance
  "The matches of one copy have scales that agree within this fraction."
  0.02)

(def offset-tolerance
  "…and offsets that agree within this fraction of the note page's diagonal."
  0.005)

(def max-signature-runs
  "A page with more text runs than this is not compared (the signature is nil, no evidence). No slide comes near it:
   the most on a measured real page is 443. With max-signature-chars and max-signature-matches it bounds what one
   comparison costs the thread that calls it, the renderer's in the GUI: the slowest pages measured, which load all
   three caps at once, take about 16 ms (docs/scientific/14)."
  4096)

(def max-signature-chars
  "…nor is a page whose runs' strings hold more UTF-16 code units than this: the comparison sorts the note page's
   strings and looks the slide's up among them, work that grows with the text (docs/scientific/14). The most on a
   measured real page is 1 569."
  65536)

(def max-signature-matches
  "The comparison gives up (nil, no evidence) when more pairs of runs with one string than this have a scale a
   thumbnail could have (σ ≤ max-thumbnail-scale): a page of one string repeated thousands of times. A slide and its
   thumbnail pair up about once per run; the most on a measured real pair is 372."
  16384)

(defn- run-height [{[_ _ c d] :t}] (if (and (number? c) (number? d)) (js/Math.hypot c d) 0))

(defn- upright?
  "A run set without rotation or mirroring (its transform has no turn or shear beyond rounding)."
  [{[a b c d] :t :as r}]
  (let [h (run-height r)]
    (and (pos? h) (pos? a) (pos? d) (<= (js/Math.abs b) (* 1e-3 h)) (<= (js/Math.abs c) (* 1e-3 h)))))

(defn- quarter-turn?
  "A run rotated by ±90° (Beamer's note date is drawn with `\\pgftransformrotate{90}`)."
  [{[a b _ d] :t :as r}]
  (let [h (run-height r)]
    (and (pos? h) (<= (js/Math.abs a) (* 1e-3 h)) (<= (js/Math.abs d) (* 1e-3 h))
         (>= (js/Math.abs b) (* 0.999 h)))))

(defn- glyphs
  "The characters of a run that are not white space."
  [{s :str}]
  (if (string? s) (count (str/replace s #"\s" "")) 0))

(def ^:private date-re #"\d{4}-\d{2}-\d{2}")

(defn rotated-date?
  "ROTATED-DATE (ADR-0048 §2.2): does a page (its runs) carry a run rotated by ±90° that reads as a date YYYY-MM-DD,
   the default note template's date? pdf.js may split the date into several runs, so the concatenation of the page's
   rotated runs counts too."
  [runs]
  (let [turned (filter quarter-turn? runs)]
    (boolean (or (some #(re-matches date-re (str/trim (str (:str %)))) turned)
                 (re-matches date-re (str/replace (apply str (map :str turned)) #"\s" ""))))))

(def ^:private log-scale-cell
  ;; matches in one cell have scale ratios below 1 + scale-tolerance
  (js/Math.log (+ 1 scale-tolerance)))

(def ^:private half-cell
  ;; an axis's two shifts: none, and half a cell
  [0 0.5])

(def ^:private grid-shifts
  ;; the eight grids, shifted by half a cell along any of the three axes
  (vec (for [a half-cell b half-cell c half-cell] [a b c])))

(defn hash-tables
  "The tables of the cells' hash (copy-covers?): for each of a cell's three axes and each of the 8 bytes of an index,
   256 random 32-bit words — 6 144 in all, the table of (axis x, byte j) at offset (8x + j)·256 — drawn from the
   platform's cryptographic generator on every call, so that no PDF can be made whose cells collide: the cells are
   fixed before the tables are drawn."
  []
  (let [tables (js/Int32Array. 6144)]
    (.getRandomValues (.-crypto js/globalThis) tables)
    tables))

(defn- index-hash
  "The hash of one cell index `v` on axis `x` (0, 1 or 2) under `tables`: the XOR of 8 entries, one per byte of v's
   CANONICAL bits — −0 as +0, and every NaN as 0x7FF8000000000000 — so that the indices that `same-index?` equates
   hash alike. `bits` and `words` are a Float64Array of one element and a Uint32Array over its buffer."
  [^js tables ^js bits ^js words x v]
  (let [nan? (not (== v v))
        _    (when-not nan? (aset bits 0 (if (== v 0) 0 v)))
        lo   (if nan? 0 (aget words 0))
        hi   (if nan? 0x7ff80000 (aget words 1))
        o    (* x 2048)]
    (bit-xor (aget tables (+ o (bit-and lo 255)))
             (aget tables (+ o 256 (bit-and (unsigned-bit-shift-right lo 8) 255)))
             (aget tables (+ o 512 (bit-and (unsigned-bit-shift-right lo 16) 255)))
             (aget tables (+ o 768 (unsigned-bit-shift-right lo 24)))
             (aget tables (+ o 1024 (bit-and hi 255)))
             (aget tables (+ o 1280 (bit-and (unsigned-bit-shift-right hi 8) 255)))
             (aget tables (+ o 1536 (bit-and (unsigned-bit-shift-right hi 16) 255)))
             (aget tables (+ o 1792 (unsigned-bit-shift-right hi 24))))))

(defn- same-index?
  "Equal as cell indices: SameValueZero, so −0 equals +0 and NaN equals NaN — exactly when `str` prints two indices
   alike, as the cell keys of the comparison before docs/scientific/14 did."
  [x y]
  (or (== x y) (and (not (== x x)) (not (== y y)))))

(defn- copy-covers?
  "Does one COPY among the matches cover at least `need` characters of the slide (`glyph-of`, an Int32Array: the
   characters of slide run i)? Match k is slide run (aget mi k), with the cell quotients
   (aget qls k) = ln σ / ln(1 + scale-tolerance), (aget qtx k) = tx / tol and (aget qty k) = ty / tol. A copy is the
   matches in one cell of a grid over (ln σ, tx, ty) whose cells are ln(1 + scale-tolerance) wide in ln σ and `tol`
   wide in each offset, or of one of the seven grids shifted from it by half a cell: every copy found agrees within
   the tolerances, and every set of matches that agrees within half of them falls into one cell of some grid
   (docs/theory/12 §3). A slide run counts once per copy, however many runs of the note page repeat its string: the
   matches come grouped by slide run, in order, so a cell has counted run i already exactly when the last run it
   counted is i. The verdict does not depend on the matches' order, since a cell's sum only grows.
   The cells of a grid live in a hash table of this function's own, on typed arrays: B buckets (the least power of two
   ≥ 2m, at least 16) chained through `nexts`, and per cell its three indices, the last slide run counted and the sum.
   A bucket's `stamps` entry names the grid (1–8) that last wrote its head, so each grid starts with an empty table at
   no cost. A cell's hash is SIMPLE TABULATION over its indices' canonical bytes, with tables drawn at random on every
   call (hash-tables): two distinct cells share a bucket with probability exactly 1/B whatever the PDF, so a lookup
   walks under 1.5 cells on average (docs/scientific/14 §5.3). The hash only decides where a cell is kept; `same-index?`
   decides which matches share a cell, so the verdict does not depend on the tables drawn. Each match's six per-axis
   hashes (two shifts × three axes) are computed once; a pass XORs three of them. 8 passes, each O(m) expected,
   stopping at the first copy that covers enough."
  [^js glyph-of ^js mi ^js qls ^js qtx ^js qty need]
  (let [m (.-length mi)]
    (if (zero? m)
      false
      (let [[h0 h1] half-cell
            tables  (hash-tables)
            bits    (js/Float64Array. 1)
            words   (js/Uint32Array. (.-buffer bits))
            ;; match k's index hashes, each computed as a pass computes its index: ℓ under h0 and h1, x …, y …
            axes    (js/Int32Array. (* 6 m))
            _       (dotimes [k m]
                      (let [o (* 6 k)]
                        (aset axes o (index-hash tables bits words 0 (js/Math.floor (- (aget qls k) h0))))
                        (aset axes (+ o 1) (index-hash tables bits words 0 (js/Math.floor (- (aget qls k) h1))))
                        (aset axes (+ o 2) (index-hash tables bits words 1 (js/Math.floor (- (aget qtx k) h0))))
                        (aset axes (+ o 3) (index-hash tables bits words 1 (js/Math.floor (- (aget qtx k) h1))))
                        (aset axes (+ o 4) (index-hash tables bits words 2 (js/Math.floor (- (aget qty k) h0))))
                        (aset axes (+ o 5) (index-hash tables bits words 2 (js/Math.floor (- (aget qty k) h1))))))
            nb     (loop [b 16] (if (< b (* 2 m)) (recur (* 2 b)) b))
            mask   (dec nb)
            heads  (js/Int32Array. nb)        ; per bucket: its newest cell, when its stamp is this grid's
            stamps (js/Int32Array. nb)        ; per bucket: the grid (1–8) that last wrote its head; 0 = none
            nexts  (js/Int32Array. m)         ; per cell, numbered in the order first seen: the next cell of its
                                              ; bucket, −1 at the end
            ca     (js/Float64Array. m)       ; …its three indices
            cb     (js/Float64Array. m)
            cc     (js/Float64Array. m)
            last   (js/Int32Array. m)         ; …the last slide run counted
            sums   (js/Float64Array. m)]      ; …and the characters of its distinct slide runs
        (loop [g 0]
          (if (= g (count grid-shifts))
            false
            (let [[s0 s1 s2] (nth grid-shifts g)
                  tag  (inc g)
                  hl   (if (== s0 h0) 0 1)
                  hx   (if (== s1 h0) 2 3)
                  hy   (if (== s2 h0) 4 5)
                  hit? (loop [k 0, n 0]
                         (if (== k m)
                           false
                           (let [a      (js/Math.floor (- (aget qls k) s0))
                                 b      (js/Math.floor (- (aget qtx k) s1))
                                 c      (js/Math.floor (- (aget qty k) s2))
                                 o      (* 6 k)
                                 bucket (bit-and (bit-xor (aget axes (+ o hl)) (aget axes (+ o hx)) (aget axes (+ o hy)))
                                                 mask)
                                 chain  (if (== (aget stamps bucket) tag) (aget heads bucket) -1)
                                 cell   (loop [x chain]
                                          (if (or (neg? x)
                                                  (and (same-index? (aget ca x) a) (same-index? (aget cb x) b)
                                                       (same-index? (aget cc x) c)))
                                            x
                                            (recur (aget nexts x))))
                                 i      (aget mi k)]
                             (cond
                               (neg? cell)
                               (let [sum (aget glyph-of i)]
                                 (aset ca n a)
                                 (aset cb n b)
                                 (aset cc n c)
                                 (aset nexts n chain)
                                 (aset heads bucket n)
                                 (aset stamps bucket tag)
                                 (aset last n i)
                                 (aset sums n sum)
                                 (if (>= sum need) true (recur (inc k) (inc n))))

                               (== i (aget last cell))
                               (recur (inc k) n)

                               :else
                               (let [sum (+ (aget sums cell) (aget glyph-of i))]
                                 (aset last cell i)
                                 (aset sums cell sum)
                                 (if (>= sum need) true (recur (inc k) n)))))))]
              (if hit? true (recur (inc g))))))))))

(defn- page-chars
  "The UTF-16 code units of a page's runs' strings; a run whose :str is not a string counts 0."
  [runs]
  (reduce (fn [n {s :str}] (if (string? s) (+ n (.-length s)) n)) 0 runs))

(defn- sorted-runs
  "The indices 0…n−1 in ascending order of (strs[k], hs[k]) — the string in UTF-16 code-unit order (compare), then the
   height — and, among equal keys, of k: a bottom-up merge sort, stable, of at most n⌈log₂ n⌉ comparisons, each of
   which reads at most three times the shorter string. It sorts by comparing, never by hashing, so no choice of the
   strings slows it (docs/scientific/14). The result is one of its two Int32Array buffers."
  [^js strs ^js hs n]
  (let [ahead? (fn [a b]
                 (let [c (compare (aget strs a) (aget strs b))]
                   (or (neg? c) (and (zero? c) (<= (aget hs a) (aget hs b))))))]
    (loop [src   (let [o (js/Int32Array. n)] (dotimes [k n] (aset o k k)) o)
           dst   (js/Int32Array. n)
           width 1]
      (if (>= width n)
        src
        (do (loop [lo 0]
              (when (< lo n)
                (let [mid (min n (+ lo width))
                      hi  (min n (+ mid width))]
                  ;; merge [lo, mid) and [mid, hi): the left run's element first on a tie, so the sort is stable
                  (loop [a lo, b mid, k lo]
                    (when (< k hi)
                      (if (and (< a mid) (or (== b hi) (ahead? (aget src a) (aget src b))))
                        (do (aset dst k (aget src a)) (recur (inc a) b (inc k)))
                        (do (aset dst k (aget src b)) (recur a (inc b) (inc k))))))
                  (recur hi))))
            (recur dst src (* 2 width)))))))

(defn- by-height
  "The note page's upright runs whose :str is a string, sorted by string (UTF-16 code units) and then by height, as
   #js [strings starts hs xs ys]: the distinct strings in that order; where each one's runs begin, an Int32Array with
   one more entry, the end — string g's runs are [starts[g], starts[g+1]), by ascending height, ties in page order;
   and every run's height and origin, Float64Arrays in the sorted order. No hash: see sorted-runs."
  [note]
  (let [runs    (into [] (filter #(and (upright? %) (string? (:str %)))) note)
        n       (count runs)
        strs    (js/Array. n)
        h       (js/Float64Array. n)
        _       (dotimes [k n] (let [r (nth runs k)] (aset strs k (:str r)) (aset h k (run-height r))))
        order   (sorted-runs strs h n)
        strings (js/Array. n)
        starts  (js/Int32Array. (inc n))
        hs      (js/Float64Array. n)
        xs      (js/Float64Array. n)
        ys      (js/Float64Array. n)
        g       (loop [k 0, g 0]
                  (if (== k n)
                    g
                    (let [j (aget order k)
                          s (aget strs j)
                          [_ _ _ _ x y] (:t (nth runs j))
                          new? (or (zero? k) (not= s (aget strs (aget order (dec k)))))]
                      (aset hs k (aget h j))
                      (aset xs k x)
                      (aset ys k y)
                      (when new?
                        (aset strings g s)
                        (aset starts g k))
                      (recur (inc k) (if new? (inc g) g)))))]
    (aset starts g n)
    #js [strings (.subarray starts 0 (inc g)) hs xs ys]))

(defn- group-of
  "Which of the ascending distinct `strings` (the first g of them) is s — its index — or -1: a binary search."
  [^js strings g s]
  (loop [lo 0, hi g]
    (if (< lo hi)
      (let [mid (unsigned-bit-shift-right (+ lo hi) 1)
            c   (compare s (aget strings mid))]
        (cond (neg? c) (recur lo mid)
              (pos? c) (recur (inc mid) hi)
              :else    mid))
      -1)))

(defn- prefix-count
  "The first index in [from, to) of the ascending `heights` whose h / hs is above bound, or `to`: the heights with
   h / hs ≤ bound are a prefix of the range, since h ↦ h / hs never decreases (IEEE division is monotone; NaN, from
   ∞/∞, comes only last and is ≤ nothing)."
  [^js heights from to hs bound]
  (loop [lo from, hi to]
    (if (< lo hi)
      (let [mid (unsigned-bit-shift-right (+ lo hi) 1)]
        (if (<= (/ (aget heights mid) hs) bound) (recur (inc mid) hi) (recur lo mid)))
      lo)))

(defn note-page-signature?
  "SIG (ADR-0048 §2.2): does page `note` (its runs) annotate slide page `slide` (its runs)? `diag` is the note page's
   diagonal in page units. true when the note page holds a SCALED COPY of the slide: upright runs that repeat the
   slide's upright strings at one scale σ ≤ max-thumbnail-scale and one offset (tx, ty) = note origin − σ · slide
   origin, covering at least half of the slide's characters; false otherwise. When the slide has no upright text, the
   default template's date decides: true when the note page has a rotated date, false when it has none, and nil (no
   evidence) when it has no text at all. nil also when a page exceeds max-signature-runs or max-signature-chars, or
   the pairs exceed max-signature-matches.
   The MATCHES of a slide run are the note page's upright runs of its string with σ = h / hs in
   (0, max-thumbnail-scale]. With the note page's runs sorted by string and height (by-height), they are one index
   range (a binary search for the string, two for the heights: group-of, prefix-count), so the pairs that cannot match
   are never visited; they are counted before one is built, and built into arrays of exactly that size
   (docs/theory/12 §3.7, docs/scientific/14)."
  [slide note diag]
  (when (and (<= (count slide) max-signature-runs) (<= (count note) max-signature-runs)
             (<= (page-chars slide) max-signature-chars) (<= (page-chars note) max-signature-chars))
    (let [s-runs   (into [] (filter #(and (upright? %) (pos? (glyphs %)))) slide)
          glyph-of (mapv glyphs s-runs)
          total    (reduce + 0 glyph-of)]
      (if (zero? total)
        (when (some #(pos? (glyphs %)) note) (rotated-date? note))
        (let [^js sorted (by-height note)
              strings (aget sorted 0)
              ^js starts (aget sorted 1)
              gh      (aget sorted 2)
              gx      (aget sorted 3)
              gy      (aget sorted 4)
              groups  (dec (.-length starts))
              tol     (max 1e-9 (* offset-tolerance diag))
              n       (count s-runs)
              kept    (js/Int32Array. (* 2 n))   ; slide run i keeps the sorted runs [kept[2i], kept[2i+1])
              m       (loop [i 0, m 0]
                        (cond
                          (> m max-signature-matches) nil
                          (= i n)                     m
                          :else
                          (let [r (nth s-runs i)
                                g (group-of strings groups (:str r))]
                            (if (neg? g)
                              (recur (inc i) m)
                              (let [hs   (run-height r)
                                    from (aget starts g)
                                    to   (aget starts (inc g))
                                    lo   (prefix-count gh from to hs 0)
                                    hi   (prefix-count gh from to hs max-thumbnail-scale)]
                                (aset kept (* 2 i) lo)
                                (aset kept (inc (* 2 i)) hi)
                                (recur (inc i) (+ m (- hi lo))))))))]
          (when m
            (let [mi  (js/Int32Array. m)
                  qls (js/Float64Array. m)
                  qtx (js/Float64Array. m)
                  qty (js/Float64Array. m)]
              (loop [i 0, at 0]
                (when (< i n)
                  (let [lo (aget kept (* 2 i))
                        hi (aget kept (inc (* 2 i)))]
                    (if (< lo hi)
                      (let [r  (nth s-runs i)
                            hs (run-height r)
                            [_ _ _ _ sx sy] (:t r)]
                        (dotimes [d (- hi lo)]
                          (let [k     (+ lo d)
                                sigma (/ (aget gh k) hs)
                                j     (+ at d)]
                            (aset mi j i)
                            (aset qls j (/ (js/Math.log sigma) log-scale-cell))
                            (aset qtx j (/ (- (aget gx k) (* sigma sx)) tol))
                            (aset qty j (/ (- (aget gy k) (* sigma sy)) tol))))
                        (recur (inc i) (+ at (- hi lo))))
                      (recur (inc i) at)))))
              (copy-covers? (js/Int32Array.from (into-array glyph-of)) mi qls qtx qty (/ total 2)))))))))

;; ---- 2.3 the probe, 2.4 the scan, 2.5 the confirmation --------------------------------------------------------------

(defn probe-next
  "The probe's next move over an :ambiguous interleaving `il` (ADR-0048 §2.3), given the pairs read so far `seen`
   [{:sig s :dated? d}…]: one per candidate, walked from the LAST candidate a to the first, each the signature of the
   pair (a − 1, a) and whether page a carries the rotated date. Returns {:read a} (read that pair next), or
   {:note-pages ps :via a|:fallback} once decided, ps nil when the deck is shown as it is.
     the pair passes                 → candidate a is the parse
     it fails, but page a is dated   → page a is a note page of an earlier slide: try the previous candidate
     otherwise                       → page a is a slide, which no earlier candidate allows: the parse with no note
                                       page in the plain run (fallback-notes), when it is valid"
  [il seen]
  (let [desc (vec (rseq (:candidates il)))]
    (loop [i 0]
      (if (< i (count seen))
        (let [{:keys [sig dated?]} (nth seen i)]
          (cond
            (true? sig) {:note-pages (candidate-notes il (nth desc i)) :via (nth desc i)}
            dated?      (recur (inc i))
            :else       {:note-pages (fallback-notes il) :via :fallback}))
        (if (< i (count desc))
          {:read (nth desc i)}
          {:note-pages (fallback-notes il) :via :fallback})))))

(defn scan-step
  "One page of the scan of a deck without labels (ADR-0048 §2.4). `state` is {:slide s :notes #{…}}, initially
   {:slide 0 :notes #{}}; page `j`, whose signature against slide page s is `sig`, is a note page when the signature
   holds, and otherwise a slide that later pages are compared with."
  [state j sig]
  (if (true? sig)
    (update state :notes conj j)
    (assoc state :slide j)))

(defn confirm-pairs
  "The pairs CONFIRM checks for note pages `note-pages` of an n-page deck (ADR-0048 §2.5): [s j] for each note page j
   in order, s being the last slide page before it, the slide j annotates."
  [n note-pages]
  (loop [j 1, slide 0, acc (transient [])]
    (if (< j n)
      (if (contains? note-pages j)
        (recur (inc j) slide (conj! acc [slide j]))
        (recur (inc j) j acc))
      (persistent! acc))))

(defn confirmed?
  "CONFIRM's verdict on one pair: the note page's signature holds, or the page carries the rotated date."
  [sig dated?]
  (or (true? sig) (boolean dated?)))

(def max-content-pages
  "The background makes at most this many page-text reads for one deck (ADR-0048 §8.9). A deck whose decision needs
   more is shown as it is, :reason :too-many-pages: the scan of a longer deck, and the confirmation of a proven parse
   with more distinct pages (confirm-page-count), are refused before any page is read; any other work stops at the
   read that would exceed it, a confirmation that reads a slide's text again included (docs/theory/12 §4.5). Real
   decks are well below it. The bound keeps a hostile PDF from occupying pdf.js's worker, which extracts each page's
   text, for minutes; the comparisons of those pages run on the thread that calls them, each bounded by
   max-signature-runs, max-signature-chars and max-signature-matches."
  2000)

(defn confirm-page-count
  "How many distinct pages CONFIRM reads for note pages `note-pages` of an n-page deck: each note page and each slide
   that has notes. Its reads can number more: the reader keeps the last four pages, so a slide with five or more note
   pages can be read again (docs/theory/12 §5)."
  [n note-pages]
  (count (into #{} cat (confirm-pairs n note-pages))))

(def ^:private trusted-producer-re
  ;; hyperref writes the page labels in the run itself only for pdfTeX and LuaTeX (ADR-0048 §2.5)
  #"(?i)\b(pdftex|luatex|luahbtex)\b")

(defn trusts-labels?
  "Are a deck's labels from the run that wrote it, i.e. does its Producer name pdfTeX, LuaTeX or LuaHBTeX? Other
   drivers (XeLaTeX's xdvipdfmx, dvipdfmx, dvips) write the labels the previous run left, so a parse from them is
   confirmed first."
  [producer]
  (boolean (and (string? producer) (re-find trusted-producer-re producer))))

;; ---- 5. pdfpc's settings ------------------------------------------------------------------------------------------
;; pdfpc reads its XMP keys with GLib's GMarkup, which is not namespace-aware (pdfpc xml.vala): every element whose
;; qualified name starts with `pdfpc:` is captured under its local name, with the text since the last start tag; the
;; last one wins; attribute-form properties are ignored; a markup error applies no key at all.

(def ^:private xml-entities {"lt" "<" "gt" ">" "amp" "&" "quot" "\"" "apos" "'"})

(defn- xml-char
  "The character of a numeric reference's code point, when XML permits it; nil otherwise (GMarkup's error)."
  [cp]
  (when (or (<= 0x1 cp 0xd7ff) (<= 0xe000 cp 0xfffd) (<= 0x10000 cp 0x10ffff))
    (js/String.fromCodePoint cp)))

(defn- xml-text
  "XML character data with its references decoded, as GMarkup decodes them: the five predefined entities and decimal
   or hexadecimal character references. nil when a reference is unknown, unterminated or not a permitted character."
  [s]
  (let [out #js []]
    (loop [i 0]
      (let [amp (.indexOf s "&" i)]
        (if (neg? amp)
          (do (.push out (subs s i)) (.join out ""))
          (let [semi (.indexOf s ";" amp)]
            (when (pos? semi)
              (let [nm (subs s (inc amp) semi)
                    ch (cond
                         (contains? xml-entities nm)            (get xml-entities nm)
                         (re-matches #"#[0-9]{1,7}" nm)          (xml-char (js/parseInt (subs nm 1) 10))
                         (re-matches #"#x[0-9A-Fa-f]{1,6}" nm)   (xml-char (js/parseInt (subs nm 2) 16)))]
                (when ch
                  (.push out (subs s i amp) ch)
                  (recur (inc semi)))))))))))

(defn- tag-end
  "The index of the `>` that closes the tag whose body starts at `from`, skipping quoted attribute values; -1 when
   none does."
  [s from]
  (let [n (count s)]
    (loop [i from, quote nil]
      (if (>= i n)
        -1
        (let [c (.charAt s i)]
          (cond
            quote                     (recur (inc i) (when-not (= c quote) quote))
            (or (= c "\"") (= c "'")) (recur (inc i) c)
            (= c ">")                 i
            :else                     (recur (inc i) nil)))))))

(defn- opens? [s at prefix] (= prefix (subs s at (+ at (count prefix)))))

(defn pdfpc-xmp
  "pdfpc's keys in a raw XMP packet `raw` (pdf.js `metadata.getRaw()`), as pdfpc reads them (ADR-0048 §5): {local-name
   text} for every element named `pdfpc:<local-name>`, the last one winning, its text being the character data since
   the last start tag with references decoded. No namespace processing: pdfpc.sty nests its block as a second
   rdf:Description, which an RDF-aware parser may drop. {} when there are no keys, and when the packet has a markup
   error (an unbalanced or unterminated tag, comment, CDATA section or processing instruction, or a bad reference),
   because GMarkup then applies none. nil without a packet."
  [raw]
  (when (string? raw)
    (let [n (count raw)]
      (loop [i 0, stack (), text "", acc {}]
        (let [lt    (.indexOf raw "<" i)
              chunk (xml-text (subs raw i (if (neg? lt) n lt)))
              text  (when chunk (if (seq stack) (str text chunk) text))]
          (cond
            (nil? text) {}
            (neg? lt)   (if (empty? stack) acc {})

            (opens? raw lt "<!--")
            (let [e (.indexOf raw "-->" (+ lt 4))]
              (if (neg? e) {} (recur (+ e 3) stack text acc)))

            (opens? raw lt "<![CDATA[")
            (let [e (.indexOf raw "]]>" (+ lt 9))]
              (if (neg? e) {} (recur (+ e 3) stack (if (seq stack) (str text (subs raw (+ lt 9) e)) text) acc)))

            (opens? raw lt "<?")
            (let [e (.indexOf raw "?>" (+ lt 2))]
              (if (neg? e) {} (recur (+ e 2) stack text acc)))

            (opens? raw lt "<!")
            (let [e (tag-end raw (+ lt 2))]
              (if (neg? e) {} (recur (inc e) stack text acc)))

            (opens? raw lt "</")
            (let [e  (.indexOf raw ">" (+ lt 2))
                  nm (when-not (neg? e) (str/trim (subs raw (+ lt 2) e)))]
              (if (or (nil? nm) (not= nm (first stack)))
                {}
                (recur (inc e) (rest stack) text
                       (if (str/starts-with? nm "pdfpc:") (assoc acc (subs nm 6) text) acc))))

            :else
            (let [e (tag-end raw (inc lt))]
              (if (neg? e)
                {}
                (let [body   (subs raw (inc lt) e)
                      empty? (str/ends-with? body "/")
                      nm     (first (str/split (str/trim (if empty? (subs body 0 (dec (count body))) body)) #"\s+"))]
                  (cond
                    (str/blank? nm) {}
                    empty?          (recur (inc e) stack ""
                                           (if (str/starts-with? nm "pdfpc:") (assoc acc (subs nm 6) "") acc))
                    :else           (recur (inc e) (cons nm stack) "" acc)))))))))))

(defn- atoi
  "C's atoi, as pdfpc parses numbers: leading white space and a sign, then digits; trailing junk ignored; 0 when there
   are no digits."
  [s]
  (if-let [[_ d] (re-find #"^\s*([+-]?\d+)" (str s))] (js/parseInt d 10) 0))

(defn notes-position-of
  "A notes position as pdfpc reads it: left, right, top or bottom in any case; anything else (none) → nil."
  [s]
  (when (string? s) (#{:left :right :top :bottom} (keyword (str/lower-case s)))))

(defn- non-blank [s] (when (and (string? s) (not (str/blank? s))) s))

(defn pdfpc-settings
  "pdfpc's settings (ADR-0048 §5) from its XMP keys `xmp` (pdfpc-xmp) and a parsed sidecar `sc` (parse-sidecar): XMP
   first, then the sidecar, then pdfpc's defaults.
     {:notes-position :left|:right|:top|:bottom|nil  :duration-min n|nil  :start-time \"HH:MM\"|nil
      :end-time \"HH:MM\"|nil  :last-minutes n  :end-frame n|nil (0-based)  :markdown? bool}
   A legacy sidecar's [notes] section forces plain text, as pdfpc applies it after everything else."
  [xmp sc]
  (let [x     (or xmp {})
        s     (when (= :read (:status sc)) (:settings sc))
        pick  (fn [xk parse sk] (if (contains? x xk) (parse (get x xk)) (get s sk)))
        dur   (pick "Duration" atoi :duration)
        end   (if (contains? x "EndUserSlide") (dec (atoi (get x "EndUserSlide"))) (get s :end-slide))
        lastm (pick "LastMinutes" atoi :last-minutes)
        off?  (if (contains? x "DisableMarkdown")
                (= "true" (get x "DisableMarkdown"))
                (true? (get s :disable-markdown)))]
    {:notes-position (pick "NotesPosition" notes-position-of :notes-position)
     :duration-min   (when (and (integer? dur) (pos? dur)) dur)
     :start-time     (non-blank (pick "StartTime" identity :start-time))
     :end-time       (non-blank (pick "EndTime" identity :end-time))
     :last-minutes   (if (integer? lastm) lastm 5)
     :end-frame      (when (and (integer? end) (>= end 0)) end)
     :markdown?      (not (or off? (true? (get s :legacy-notes?))))}))

(defn split-axis
  "The axis pdfpc's notes position splits a page along (ADR-0048 §5): left/right → :horizontal, top/bottom →
   :vertical, none → nil."
  [position]
  (case position (:left :right) :horizontal (:top :bottom) :vertical nil))

(defn notes-position
  "Where the notes sit on a split deck: an explicit :slides-notes preference first (`pref`, one of
   vinary.slides.deck/notes-positions, :off included), then pdfpc's notes position, then :auto (the geometric default
   of vinary.slides.deck/slide-side)."
  [pref pdfpc-position]
  (if (and (contains? deck/notes-positions pref) (not= :auto pref))
    pref
    (or pdfpc-position :auto)))

;; ---- the sidecar (ADR-0048 §4, §5; pdfpc pdf.vala) -----------------------------------------------------------------

;; Disabled: the cap is enforced where the file is read, before a byte of it is — main's
;; content_service.js PDFPC_SIDECAR_MAX_BYTES (4 MiB, ADR-0048 §8.9) — and a sidecar past it reaches this namespace
;; only as {:tooLarge true} (sidecar-of). A second copy here would be a constant nothing reads, free to drift.
;; (def max-sidecar-bytes
;;   "Main reads a sidecar only up to this size (ADR-0048 §8.9); a larger one is not read."
;;   (* 4 1024 1024))

(defn- json-object? [x] (and (some? x) (= "object" (goog/typeOf x))))
(defn- json-int [v] (when (and (number? v) (js/Number.isInteger v)) v))
(defn- json-str [v] (when (string? v) v))
(defn- member [o k] (when (.call (.-hasOwnProperty (.-prototype js/Object)) o k) (unchecked-get o k)))

(defn- parse-json-sidecar [o]
  (let [fmt   (member o "pdfpcFormat")
        pages (member o "pages")]
    (if (and (number? fmt) (> fmt 2))
      {:status :ignored :why :format :format fmt}
      {:status   :read
       :format   (or (json-int fmt) 0)
       :settings {:duration         (json-int (member o "duration"))
                  :end-time         (json-str (member o "endTime"))
                  :start-time       (json-str (member o "startTime"))
                  :last-minutes     (json-int (member o "lastMinutes"))
                  :end-slide        (json-int (member o "endSlide"))
                  :notes-position   (notes-position-of (member o "beamerNotePosition"))
                  :disable-markdown (true? (member o "disableMarkdown"))}
       :entries  (if (array? pages)
                   (into []
                         (keep (fn [e]
                                 (when (json-object? e)
                                   (when-let [idx (json-int (member e "idx"))]
                                     {:idx     idx
                                      :label   (or (json-str (member e "label")) "")
                                      :overlay (or (json-int (member e "overlay")) 0)
                                      :forced? (true? (member e "forcedOverlay"))
                                      :hidden? (true? (member e "hidden"))
                                      :note    (json-str (member e "note"))}))))
                         (array-seq pages))
                   [])})))

(defn- legacy-sections
  "pdfpc's legacy format: a header `[name]` ANYWHERE in the text (regex \\[[A-Za-z_]+\\]) starts a section, whose body
   runs to the next header, white space stripped. {name body}, the last of a repeated name winning."
  [text]
  (let [re   (js/RegExp. "\\[[A-Za-z_]+\\]" "g")
        hits (loop [acc (transient [])]
               (if-let [m (.exec re text)]
                 (recur (conj! acc [(.-index m) (aget m 0)]))
                 (persistent! acc)))]
    (into {}
          (map-indexed (fn [k [at h]]
                         (let [from (+ at (count h))
                               to   (if (< (inc k) (count hits)) (first (nth hits (inc k))) (count text))]
                           [(subs h 1 (dec (count h))) (str/trim (subs text from to))])))
          hits)))

(defn- legacy-notes
  "The [notes] body of a legacy sidecar, as pdfpc reads it: pieces split on `### ` wherever it occurs, each headed by a
   1-based user-slide number (atoi of the text up to the piece's first newline), its text after that newline with
   `\\[`, `\\]` and `\\#` unescaped. A piece without a newline carries no text. [[user-slide text]…]"
  [body]
  (into []
        (keep (fn [piece]
                (when-let [nl (str/index-of piece "\n")]
                  (let [text (str/trimr (str/replace (subs piece (inc nl)) #"\\([\[\]#])" "$1"))]
                    (when (seq text) [(atoi (subs piece 0 nl)) text])))))
        (str/split body #"### " -1)))

(defn- parse-legacy-sidecar [text]
  (let [s (legacy-sections text)]
    (if (empty? s)
      {:status :ignored :why :unrecognized}
      {:status   :read
       :format   :legacy
       :settings {:duration       (some-> (get s "duration") atoi)
                  :start-time     (non-blank (get s "start_time"))
                  :end-time       (non-blank (get s "end_time"))
                  :last-minutes   (some-> (get s "last_minutes") atoi)
                  ;; pdfpc ≤ 4.4.1 wrote it 1-based and current pdfpc reads it as 0-based: kept as pdfpc reads it
                  :end-slide      (some-> (get s "end_user_slide") atoi)
                  :notes-position (notes-position-of (get s "notes_position"))
                  :legacy-notes?  (contains? s "notes")}
       ;; [skip]: comma-terminated 1-based page numbers; the field after the last comma is ignored (pdfpc's reader)
       :skip     (when-let [b (get s "skip")]
                   (into #{} (comp (map atoi) (map dec) (filter pos?)) (butlast (str/split b #"," -1))))
       :notes    (some-> (get s "notes") legacy-notes)})))

(defn parse-sidecar
  "A pdfpc sidecar's text, decided as pdfpc decides it (ADR-0048 §5): JSON first. A root that is not an object, or a
   format above 2, is not a sidecar this version understands ({:status :ignored :why …}). Anything that is not JSON is
   pdfpc's legacy format. nil text → nil (no sidecar)."
  [text]
  (when (string? text)
    (let [parsed (try (js/JSON.parse text) (catch :default _ ::not-json))]
      (cond
        (= parsed ::not-json)                            (parse-legacy-sidecar text)
        (or (not (json-object? parsed)) (array? parsed)) {:status :ignored :why :not-an-object}
        :else                                            (parse-json-sidecar parsed)))))

(defn sidecar-of
  "The parsed sidecar of what main read beside a PDF (ADR-0048 N7: content_service readPdfpcSidecarLocal / Remote, as
   the payload's :sidecar): parse-sidecar of its :text; a sidecar too large to read, or one that could not be read,
   is {:status :ignored :why :too-large|:unreadable}, so `--deck-info` names it; nil when there is none."
  [{:keys [text tooLarge error] :as read}]
  (cond
    (string? text) (parse-sidecar text)
    tooLarge       {:status :ignored :why :too-large}
    (some? error)  {:status :ignored :why :unreadable}
    (some? read)   {:status :ignored :why :unreadable}
    :else          nil))

(defn- poppler-labels
  "Page labels as Poppler gives them to pdfpc: the PDF's labels, or 1-based page numbers when it has none."
  [labels n]
  (if (= n (count labels)) (mapv str labels) (mapv #(str (inc %)) (range n))))

(defn- user-slides
  "pdfpc's user slide of every page when its legacy notes are placed: runs of equal labels when auto-grouping is on,
   one per page otherwise (a legacy [skip] or any notes position switches it off; [skip] applies after the notes)."
  [labels grouping?]
  (if-not grouping?
    (vec (range (count labels)))
    (persistent!
     (reduce (fn [acc i]
               (conj! acc (cond
                            (zero? i)                                 0
                            (= (nth labels i) (nth labels (dec i)))   (nth acc (dec i))
                            :else                                     (inc (nth acc (dec i))))))
             (transient [])
             (range (count labels))))))

(defn sidecar-pages
  "What a parsed sidecar `sc` does to an n-page deck labelled `labels` (pdf.js's labels, or nil), page by page, as
   pdfpc places it: {:notes {page text} :hidden #{…} :forced #{…}}. `position` is the effective notes position
   (pdfpc-settings), which turns pdfpc's auto-grouping off.
     JSON entries apply in order. An entry's page is its `idx`, unless the deck changed under it: then its `label` and
     `overlay` relocate it (pdfpc's resolve: the overlay-th page of the first run labelled so). A later entry for the
     same page decides its hidden flag; a forced overlay never applies to page 0; out-of-range pages are ignored.
     A legacy file's notes go to every page of their 1-based user slide; its [skip] pages are forced overlays."
  [sc labels n position]
  (let [labels (poppler-labels labels n)
        none   {:notes {} :hidden #{} :forced #{}}]
    (cond
      (not= :read (:status sc))
      none

      (= :legacy (:format sc))
      (let [us    (user-slides labels (and (nil? (:skip sc)) (nil? position)))
            pages (group-by #(nth us %) (range n))]
        (assoc none
               :notes  (reduce (fn [m [u text]] (reduce #(assoc %1 %2 text) m (get pages (dec u)))) {} (:notes sc))
               :forced (into #{} (filter #(< % n)) (:skip sc))))

      :else
      (let [first-of (reduce (fn [m i] (let [l (nth labels i)] (if (contains? m l) m (assoc m l i)))) {} (range n))]
        (reduce (fn [{:keys [forced] :as acc} {:keys [idx label overlay forced? hidden? note]}]
                  (let [overlay-now (fn [p] (loop [k 0, q p]
                                              (if (and (pos? q) (contains? forced q)) (recur (inc k) (dec q)) k)))
                        s (cond
                            (= "" label) idx
                            (and (< -1 idx n) (= label (nth labels idx)) (= overlay (overlay-now idx))) idx
                            :else (if-let [i0 (get first-of label)]
                                    (let [s (+ i0 overlay)] (if (and (< s n) (= label (nth labels s))) s idx))
                                    idx))]
                    (if-not (< -1 s n)
                      acc
                      (cond-> (update acc :hidden (if hidden? #(conj % s) #(disj % s)))
                        (and forced? (pos? s)) (update :forced conj s)
                        (seq note)             (update :notes assoc s note)))))
                none
                (:entries sc))))))

;; ---- 4. text notes ------------------------------------------------------------------------------------------------

(def text-note-subtypes
  "The annotation types whose /Contents pdfpc reads as notes (ADR-0048 §4)."
  #{"Text" "FreeText" "Highlight" "Underline" "Squiggly"})

(def ^:private pdfdoc-low
  ;; pdf.js 5.4.149 PDFStringTranslateTable, bytes 0x18–0x1F (0x1B, the language escape, is handled apart)
  [0x2d8 0x2c7 0x2c6 0x2d9 0x2dd 0x2db 0x2da 0x2dc])

(def ^:private pdfdoc-high
  ;; …and bytes 0x80–0xA0; 0 means the byte decodes to the Latin-1 character of its own number (0x9F)
  [0x2022 0x2020 0x2021 0x2026 0x2014 0x2013 0x192 0x2044 0x2039 0x203a 0x2212 0x2030 0x201e 0x201c 0x201d 0x2018
   0x2019 0x201a 0x2122 0xfb01 0xfb02 0x141 0x152 0x160 0x178 0x17d 0x131 0x142 0x153 0x161 0x17e 0 0x20ac])

(def ^:private pdfdoc-inverse
  "Character code → the byte pdf.js decodes to it, for the bytes that decode to another character."
  (into {} (concat (keep-indexed (fn [k c] (when (and (pos? c) (not= k 3)) [c (+ 0x18 k)])) pdfdoc-low)
                   (keep-indexed (fn [k c] (when (pos? c) [c (+ 0x80 k)])) pdfdoc-high))))

(def ^:private never-decoded
  "Character codes below 0x100 that no byte decodes to: those of the remapped bytes, and 0x1B, which starts a language
   escape the decoder removes."
  (into #{0x1b} (vals pdfdoc-inverse)))

(defn pdfdoc-bytes
  "PDFDOC⁻¹ (ADR-0048 §4): the bytes other than 0x1B that pdf.js's PDFDocEncoding table (stringToPDFString) decodes
   to `s`, as a Uint8Array; nil when a character of `s` is one that no byte decodes to. No two such bytes decode to
   the same character, so the bytes are unique when they exist. They are the bytes pdf.js read only when it decoded a
   literal string through that table whole and with no escape: no byte 0x1B, and no byte-order mark, or one its
   TextDecoder rejected on a string that is not UTF-16 of odd length (docs/theory/12 §6.1–§6.3)."
  [s]
  (let [n   (count s)
        out (js/Uint8Array. n)]
    (loop [i 0]
      (if (< i n)
        (let [c (.charCodeAt s i)
              b (or (get pdfdoc-inverse c)
                    (when (and (<= c 0xff) (not (contains? never-decoded c))) c))]
          (when b
            (aset out i b)
            (recur (inc i))))
        out))))

(def ^:private pdftex-re #"(?i)\bpdftex\b")

(defn repair
  "REPAIR (ADR-0048 §4). pdfTeX writes a `\\pdfpcnote`'s UTF-8 bytes into a literal string, which every reader decodes
   a byte at a time as PDFDocEncoding (`café` reads `cafÃ©`). When the Producer names pdfTeX and the string's bytes
   (pdfdoc-bytes) hold a byte ≥ 0x80 and are valid UTF-8, their UTF-8 decoding is the note; otherwise `s` as it is."
  [s producer]
  (if-not (and (string? s) (string? producer) (re-find pdftex-re producer))
    s
    (if-let [b (pdfdoc-bytes s)]
      (if (some #(>= % 0x80) (array-seq b))
        (try (.decode (js/TextDecoder. "utf-8" #js {:fatal true}) b)
             (catch :default _ s))
        s)
      s)))

(defn annotation-text
  "A page's annotation note from its annotations `annots` [{:subtype :contents :dir}…] in /Annots order (ADR-0048
   §4): the /Contents of the types pdfpc reads, each REPAIRed for `producer`, the non-blank ones joined with a newline
   (pdfpc's rule). {:text :dir}, :dir being the annotations' common direction (pdf.js's \"ltr\"/\"rtl\") or \"auto\"
   when they differ; nil when there is no such note."
  [annots producer]
  (let [notes (into [] (comp (filter #(contains? text-note-subtypes (:subtype %)))
                             (map #(assoc % :contents (repair (:contents %) producer)))
                             (filter #(non-blank (:contents %))))
                    annots)]
    (when (seq notes)
      {:text (str/join "\n" (map :contents notes))
       :dir  (let [dirs (into #{} (map #(or (:dir %) "auto")) notes)] (if (= 1 (count dirs)) (first dirs) "auto"))})))

(defn page-notes
  "The text notes the presenter sees for a page (ADR-0048 §4): the annotation note (annotation-text), then the sidecar
   note when it differs, because a viewer should not drop a note its author wrote (pdfpc shows only one).
   [{:text :dir :from :annotation|:sidecar}…]"
  [annotation sidecar]
  (let [a (some-> annotation :text non-blank)
        s (non-blank sidecar)]
    (cond-> []
      a                                               (conj {:text a :dir (:dir annotation "auto") :from :annotation})
      (and s (not= (some-> a str/trim) (str/trim s))) (conj {:text s :dir "auto" :from :sidecar}))))

;; ---- the model --------------------------------------------------------------------------------------------------

(defn- plan
  "What a deck's first paint already knows about its note pages, and what the background must decide: only a proven
   parse from a driver that writes its labels in the same run, and that no restarted frame counter explains, hides
   pages at once. Work that would read more than max-content-pages pages is refused here, before it starts."
  [{:keys [labels producer sizes] :as evidence} axis]
  (let [source (:source (deck/classify evidence))
        n      (count sizes)
        none   (fn [m] (merge {:style :none :note-pages #{}} m))]
    (cond
      axis                                       {:style :split :note-pages #{}}
      (not (#{:beamer :beamer-geometry} source)) (none {:reason :not-beamer})
      (< n 2)                                    (none {:reason :one-page})
      (nil? labels)                              (none (if (> n max-content-pages) {:reason :too-many-pages} {:pending :scan}))
      :else
      (if-let [L (integer-labels labels)]
        (let [il       (interleaving L)
              trusted? (trusts-labels? producer)]
          (case (:status il)
            :proven    (cond
                         (and trusted? (not (restartable? L)))
                         {:style :interleaved :note-pages (:note-pages il) :proof [:labels] :interleaving il}

                         (> (confirm-page-count n (:note-pages il)) max-content-pages)
                         (none {:reason :too-many-pages :interleaving il})

                         :else
                         (none {:pending :confirm :interleaving il}))
            :ambiguous (none {:pending :probe :interleaving il :confirm? (not trusted?)})
            ;; :none reads as :no-note-pages, beside :style :none; :invalid and :undecidable are their own reasons
            (none {:reason (if (= :none (:status il)) :no-note-pages (:status il)) :interleaving il})))
        (none {:reason :labels-not-integers})))))

(defn initial
  "The notes model of a deck at its first paint (ADR-0048 N1), from its `evidence` (vinary.slides.evidence/gather,
   whose :pdfpc is pdfpc-xmp's keys, plus :sizes) and its parsed sidecar `sc` (parse-sidecar, or nil):
     :style        :split | :interleaved | :none
     :note-pages   the interleaved note pages (#{} otherwise)
     :proof        how they were found: a vector of :labels :probe :confirm :scan
     :pending      the background work that decides the rest (vinary.slides.evidence/settle): :probe :confirm :scan
     :reason       why no page is hidden, when there is one to give
     :interleaving the label automaton's result, when it ran
     :pdfpc        pdfpc-settings;  :pdfpc? whether the deck carries pdfpc metadata (XMP keys, or a sidecar read)
     :markdown?    whether text notes render as Markdown: pdfpc metadata present and Markdown not disabled
     :sidecar      {:status :format :why} plus sidecar-pages, or nil without a sidecar"
  [evidence sc]
  (let [n        (count (:sizes evidence))
        xmp      (:pdfpc evidence)
        settings (pdfpc-settings xmp sc)
        uniform  (deck/uniform-size (:sizes evidence))
        axis     (or (when uniform (deck/second-screen-axis uniform)) (split-axis (:notes-position settings)))
        pdfpc?   (boolean (or (seq xmp) (= :read (:status sc))))]
    (assoc (plan evidence axis)
           :pdfpc     settings
           :pdfpc?    pdfpc?
           :markdown? (and pdfpc? (:markdown? settings))
           :sidecar   (when sc
                        (merge (select-keys sc [:status :format :why])
                               (when (= :read (:status sc))
                                 (sidecar-pages sc (:labels evidence) n (:notes-position settings))))))))

(defn decided
  "A model whose pending work has finished: note pages `note-pages` (nil or empty: shown as it is) found by the steps
   `proof`, or `reason` why none are hidden."
  [model note-pages proof reason]
  (if (seq note-pages)
    (assoc model :style :interleaved :note-pages (set note-pages) :proof proof :pending nil :reason nil)
    (assoc model :style :none :note-pages #{} :proof nil :pending nil :reason reason)))

(defn deck-evidence
  "The keys a notes `model` adds to the deck's evidence (vinary.slides.deck/deck) under the :slides-notes preference
   `pref`: the note pages as :hidden, pdfpc's hidden pages as :skipped, its forced overlays, its end slide, and the
   axis its notes position splits pages along. `pref` :off is the escape hatch that shows every page as it is: no
   page is hidden and no page is split by pdfpc's position (a geometric second screen stays one, ADR-0045 §9)."
  [model pref]
  (let [off? (= :off pref)]
    {:hidden     (when (and (= :interleaved (:style model)) (not off?)) (:note-pages model))
     :skipped    (get-in model [:sidecar :hidden])
     :forced     (get-in model [:sidecar :forced])
     :end-frame  (get-in model [:pdfpc :end-frame])
     :split-axis (when-not off? (split-axis (get-in model [:pdfpc :notes-position])))}))

(def ^:private opposite {:left :right :right :left :top :bottom :bottom :top})

(defn sources-for
  "Where the presenter view finds page `page`'s notes (ADR-0048 §6.1), in order: the notes REGIONS (on a split deck
   the notes half of the page, on an interleaved deck each of its note pages, whole: {:kind :region :page p :rect
   {:x :y :w :h}}, page units, y down), then {:kind :text :page p}, its text notes, which the pane reads on demand.
   `d` is the deck model, `sizes` the page sizes, `pref` the :slides-notes preference."
  [model d sizes page pref]
  (let [p       (deck/snap d page)
        size    (nth sizes p nil)
        side    (deck/slide-side (:second-screen d) (notes-position pref (get-in model [:pdfpc :notes-position])))
        regions (cond
                  (and side size)
                  [{:kind :region :page p :rect (deck/half-rect size (opposite side))}]

                  (seq (:hidden d))
                  (into [] (comp (take-while #(contains? (:hidden d) %))
                                 (map (fn [j] {:kind :region :page j :rect (deck/half-rect (nth sizes j [0 0]) nil)})))
                        (range (inc p) (:pages d 0)))

                  :else [])]
    (conj regions {:kind :text :page p})))

(defn has-notes?
  "Does the model know of notes for some page: split halves, note pages, sidecar notes, or pdfpc metadata? (The hint's
   test, ADR-0048 §6.1, less the text notes of rendered pages, which the engine adds as it reads them.)"
  [model]
  (boolean (or (#{:split :interleaved} (:style model))
               (seq (get-in model [:sidecar :notes]))
               (:pdfpc? model))))

(defn summary
  "The small, serializable part of a notes model for app state and `--deck-info`."
  [model]
  {:style      (:style model :none)
   :note-pages (count (:note-pages model))
   :proof      (:proof model)
   :pending    (:pending model)
   :reason     (:reason model)
   :pdfpc?     (boolean (:pdfpc? model))
   :markdown?  (boolean (:markdown? model))
   :sidecar    (get-in model [:sidecar :status])
   :has-notes? (has-notes? model)})
