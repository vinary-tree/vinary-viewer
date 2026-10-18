(ns vinary.slides.deck
  "The pure, DOM-free model of a slide deck (ADR-0045): is this PDF a deck, how do its pages group into frames,
   and where does each navigation command go. No pdf.js, no DOM, no re-frame — so the GUI engine, the unit tests,
   `vv --cli --deck-info`, and a future terminal slide mode all share one definition.

   Vocabulary (docs/GLOSSARY.md):
     page     one PDF page, 0-based index p ∈ [0, n)
     frame    a Beamer frame: a maximal run of consecutive pages that are overlays of one slide (\\pause, <2->).
              A deck without overlay information has exactly one page per frame.
     deck     {:deck? :tier :source :pages :frames :frame-of :labels} — the classification plus the frame partition.
     nav      {:page p :back [p…] :fwd [p…]} — the current page plus a bounded jump history (PDF GoBack/GoForward).

   Detection (`classify`) reads only EVIDENCE gathered from the document (vinary.slides.evidence): the /Info
   Creator and Producer, the XMP CreatorTool, the catalog /PageMode, the page labels, and every page's size.
   Its rules are ordered data tables, first match wins; the reasoning behind each is in ADR-0045 §Detection and
   the corpus evaluation in docs/scientific/12."
  (:require [clojure.string :as str]))

;; ---- reference tables ---------------------------------------------------------------------------------

(def beamer-papers
  "Beamer's paper sizes in PDF points (bp): the `aspectratio` class option → \\paperwidth × \\paperheight
   (beamer.cls:312-345; cm × 72/2.54). A custom ratio keeps the 9.6 cm height."
  {43   [362.835 272.126]    ; 12.80 × 9.60 cm (the default)
   169  [453.543 255.118]    ; 16.00 × 9.00
   1610 [453.543 283.465]    ; 16.00 × 10.00
   149  [396.850 255.118]    ; 14.00 × 9.00
   141  [420.945 297.638]    ; 14.85 × 10.50
   54   [354.331 283.465]    ; 12.50 × 10.00
   32   [382.677 255.118]    ; 13.50 × 9.00
   2013 [396.850 257.953]})  ; 14.00 × 9.10

(def export-canvases
  "Exact page canvases of slide-tool PDF exports (bp). Landscape alone is NOT evidence — a local corpus survey
   (docs/scientific/12) found hundreds of landscape figures, web print-outs and forms — but these exact sizes
   are what the tools emit for every slide. Each entry says where it comes from: MEASURED (exported here by
   scripts/exp-deck-export-corpus.cjs, LibreOffice Impress 26.8 at each slide-size preset), OBSERVED (an export
   in the natural corpus), DOCUMENTED (the vendor's own published preset), or REPORTED (a third-party source only;
   the vendor's documentation names the preset without its size, and no sample could be produced here) —
   docs/scientific/12 §7 cites each."
  {:powerpoint-169 [960 540]        ; PowerPoint Widescreen, 13.333 × 7.5 in; Impress Widescreen (measured)
   :powerpoint-43  [720 540]        ; PowerPoint / Impress On-screen Show (4:3), 10 × 7.5 in (Impress measured)
   :google-169     [720 405]        ; Google Slides 16:9; Impress On-screen Show (16:9) (measured)
   :on-screen-1610 [720 450]        ; Impress On-screen Show (16:10) (measured); PowerPoint's, 10 × 6.25 in (documented)
   :impress-43     [793.7 595.3]    ; Impress Screen 4:3, 28 × 21 cm (measured 793.7 × 595.28; observed 794 × 595 from
                                    ; an older Impress that rounds to whole points — both within the tolerance)
   :impress-169    [793.7 446.5]    ; Impress Screen 16:9, 28 × 15.75 cm — Impress's default (measured)
   :impress-1610   [793.7 496.1]    ; Impress Screen 16:10, 28 × 17.5 cm (measured)
   :keynote-43     [1024 768]       ; Keynote Standard (observed)
   :keynote-169    [1920 1080]})    ; Keynote Wide (reported — see the docstring)

(def size-tolerance
  "|Δ| in bp under which two page dimensions count as equal (TeX rounds \\paperwidth to the scaled point;
   exporters round to whole points)."
  0.5)

(def ^:private beamer-re #"(?i)\bbeamer\b")
(def ^:private tex-producer-re
  ;; the PDF producers of the TeX engines: pdfTeX, LuaTeX/LuaHBTeX, XeTeX (via xdvipdfmx), dvipdfm(x), Tectonic
  #"(?i)\b(pdftex|luatex|luahbtex|xetex|xdvipdfmx|dvipdfmx?|tectonic)\b")

;; ---- geometry predicates --------------------------------------------------------------------------------

(defn- approx= [a b] (<= (js/Math.abs (- a b)) size-tolerance))

(defn same-size?
  "Do page sizes [w1 h1] and [w2 h2] agree within `size-tolerance`?"
  [[w1 h1] [w2 h2]]
  (boolean (and w1 h1 w2 h2 (approx= w1 w2) (approx= h1 h2))))

(defn uniform-size
  "The common size of `sizes` when every page has (approximately) the same size, else nil."
  [sizes]
  (when-let [s0 (first sizes)]
    (when (every? #(same-size? s0 %) sizes) s0)))

(defn beamer-paper?
  "Is `size` one of Beamer's paper sizes?"
  [size]
  (boolean (some #(same-size? size %) (vals beamer-papers))))

(defn export-canvas
  "The slide-tool key whose export canvas `size` matches, or nil."
  [size]
  (some (fn [[k v]] (when (same-size? size v) k)) export-canvases))

;; ---- second-screen pages ---------------------------------------------------------------------------------
;; Beamer's two-screen options — `show notes on second screen=<side>` (and the rarer `previous slide on second
;; screen` / `second mode text on second screen`, beamerbasetwoscreens.sty) — compose every physical page from TWO
;; paper-sized halves with pgfpages' `two screens with optional second` layout (pgfpages.sty:132-162): the slide is
;; logical page 1, and <side> names where the SECOND screen (the notes) goes. So the page is twice the paper width
;; (right / left) or height (top / bottom); `right` is Beamer's default (beamerbasenotes.sty:33). Measured on
;; LuaLaTeX output (docs/scientific/data/12/second-screen-2026-10-16.txt): a 4:3 deck's 362.835 × 272.126 bp paper
;; becomes 725.669 × 272.126 (right / left) and 362.835 × 544.252 (top / bottom).

(defn second-screen-axis
  "The axis along which page `size` doubles one of Beamer's papers — :horizontal (2w × h: the second screen is
   right or left of the slide), :vertical (w × 2h: above or below it) — or nil."
  [size]
  (some (fn [[pw ph]]
          (cond (same-size? size [(* 2 pw) ph]) :horizontal
                (same-size? size [pw (* 2 ph)]) :vertical))
        (vals beamer-papers)))

(def notes-positions
  "The :slides-notes preference — where the SECOND screen (the notes) sits on a second-screen deck's pages, in the
   words of Beamer's `show notes on second screen=<value>`: :auto (Beamer's own default, right — and bottom for a
   page doubled vertically, where Beamer has no default), :right, :left, :bottom, :top, or :off (never split: show
   whole pages, notes included)."
  #{:auto :right :left :bottom :top :off})

(defn slide-side
  "Which half of a second-screen page holds the SLIDE, for split `axis` (:horizontal, :vertical or nil) under the
   :slides-notes preference `notes`: :left :right :top :bottom, or nil for the whole page (no second screen, or the
   preference is :off). The slide sits opposite the notes — notes right → slide left. A preference naming the
   other axis's side (:left on a vertically doubled page) means that axis's default; an unknown value means :auto."
  [axis notes]
  (let [notes (if (contains? notes-positions notes) notes :auto)]
    (when-not (= notes :off)
      (case axis
        :horizontal (if (= notes :left) :right :left)
        :vertical   (if (= notes :top) :bottom :top)
        nil))))

(defn half-rect
  "The part of a page of size [w h] that `side` shows, in page units with y DOWN from the top edge (pdf.js viewport
   space): {:x :y :w :h} — the slide half for :left :right :top :bottom, the whole page for nil."
  [[w h] side]
  (case side
    :left   {:x 0       :y 0       :w (/ w 2) :h h}
    :right  {:x (/ w 2) :y 0       :w (/ w 2) :h h}
    :top    {:x 0       :y 0       :w w       :h (/ h 2)}
    :bottom {:x 0       :y (/ h 2) :w w       :h (/ h 2)}
    {:x 0 :y 0 :w w :h h}))

(defn display-size
  "The size [w h] at which a page of size `size` is shown when `side` is its slide half (nil: the whole page)."
  [size side]
  (let [{:keys [w h]} (half-rect size side)] [w h]))

(defn on-slide-half?
  "Does a text run whose top-left corner sits at (`left-pct`, `top-pct`) — percent of the FULL page, y down — lie on
   slide half `side`? A run starting exactly on the midline belongs to the second half; nil side → always."
  [side left-pct top-pct]
  (case side
    :left   (< left-pct 50)
    :right  (>= left-pct 50)
    :top    (< top-pct 50)
    :bottom (>= top-pct 50)
    true))

;; ---- classification -------------------------------------------------------------------------------------

(defn classify
  "Classify document evidence `{:creator :producer :creator-tool :page-mode :sizes}` as a slide deck.
   Returns {:deck? bool :tier :definite|:probable|nil :source kw|nil}. Rules, first match wins:

     definite  :beamer           Creator or XMP CreatorTool names Beamer (beamer.cls sets pdfcreator=\"LaTeX with
                                 Beamer class\"), and the document has ≥ 2 pages or a Beamer paper size (or one
                                 doubled for a second screen) — a one-page beamerposter carries the same Creator but
                                 is not a deck
     definite  :fullscreen-mode  the catalog asks to open in full-screen mode (/PageMode /FullScreen, PDF 32000-1
                                 §7.7.2) — authored for presenting
     probable  :slide-export     ≥ 2 pages, all one size, and that size is a slide tool's exact export canvas
     probable  :beamer-geometry  ≥ 2 pages, all one Beamer paper size — or one doubled for a second screen
                                 (second-screen-axis) — produced by a TeX engine (a Beamer deck whose
                                 \\hypersetup{pdfcreator=…} was overridden)
     —         not a deck        everything else; in particular landscape pages alone never qualify"
  [{:keys [creator producer creator-tool page-mode sizes]}]
  (let [n           (count sizes)
        uniform     (uniform-size sizes)
        beamer-tag? (boolean (or (and (string? creator) (re-find beamer-re creator))
                                 (and (string? creator-tool) (re-find beamer-re creator-tool))))]
    (cond
      (and beamer-tag? (or (>= n 2) (and (= n 1) (or (beamer-paper? (first sizes))
                                                    (second-screen-axis (first sizes))))))
      {:deck? true :tier :definite :source :beamer}

      (= "FullScreen" page-mode)
      {:deck? true :tier :definite :source :fullscreen-mode}

      (and (>= n 2) uniform (export-canvas uniform))
      {:deck? true :tier :probable :source :slide-export}

      (and (>= n 2) uniform (or (beamer-paper? uniform) (second-screen-axis uniform))
           (string? producer) (re-find tex-producer-re producer))
      {:deck? true :tier :probable :source :beamer-geometry}

      :else
      {:deck? false :tier nil :source nil})))

;; ---- frames ---------------------------------------------------------------------------------------------

(defn frames-from-labels
  "Partition pages [0, n) into frames [[start end]…] (inclusive). When `group?` and `labels` has one entry per
   page, a frame is a maximal run of ADJACENT pages with the same non-blank label — Beamer labels every overlay
   page with its frame number (\\thispdfpagelabel{\\insertframenumber}, beamerbaseframe.sty:72), so the overlays of
   a frame share a label. Only adjacency counts: \\againframe repeats a label later and must start a new frame.
   Otherwise every page is its own frame (a deck built without page labels, e.g. by a single XeLaTeX pass, whose
   driver writes the labels the previous run left in the .aux — docs/scientific/12 §5.1 — or any non-Beamer PDF)."
  [n labels group?]
  (let [labels (when (and group? labels (= n (count labels))) labels)
        joins? (fn [i] (and labels
                            (let [a (nth labels (dec i)) b (nth labels i)]
                              (and (string? a) (not (str/blank? a)) (= a b)))))]
    (if (<= n 0)
      []
      (loop [i 1, start 0, acc (transient [])]
        (cond
          (>= i n)   (persistent! (conj! acc [start (dec n)]))
          (joins? i) (recur (inc i) start acc)
          :else      (recur (inc i) i (conj! acc [start (dec i)])))))))

(defn frame-index
  "The page → frame lookup vector for a frame partition: (nth result p) = the frame containing page p. Built
   with a transient — one conj! per page, so a partition of n pages yields exactly n entries."
  [frames]
  (persistent!
   (reduce (fn [acc [f [s e]]]
             (loop [p s, a acc] (if (> p e) a (recur (inc p) (conj! a f)))))
           (transient [])
           (map-indexed vector frames))))

;; ---- the audience's pages (ADR-0048 §3) ------------------------------------------------------------------
;; A deck may carry pages the audience never sees: the note pages of Beamer's `show notes` (vinary.slides.notes finds
;; them). They stay in the document — the engine keeps a slot per page, and the Document layout shows every page — but
;; navigation runs over the SHOWN pages, in their order (the ORDINAL), and a hidden page SNAPS to its slide: the nearest
;; shown page below it. Page 0 is never hidden (a note page follows the slide it annotates), so every page has one.

(defn- page-set
  "The integers of `ps` that lie in [lo, n), as a set; anything else is dropped."
  [ps lo n]
  (into #{} (filter #(and (integer? %) (<= lo %) (< % n))) ps))

(defn- audience-pages
  "One pass over the pages [0, n) given the `hidden` set: {:shown [p…] :ordinal [o|nil …] :snap [p' …]} — the shown
   pages in order, each page's position among them (nil when hidden), and the page each one snaps to."
  [n hidden]
  (loop [p 0, last-shown 0, shown (transient []), ordinal (transient []), snap (transient [])]
    (if (< p n)
      (if (contains? hidden p)
        (recur (inc p) last-shown shown (conj! ordinal nil) (conj! snap last-shown))
        ;; the ordinal is read BEFORE the conj!: a transient grows in place, so a count taken after it is one too high
        (let [o (count shown)]
          (recur (inc p) p (conj! shown p) (conj! ordinal o) (conj! snap p))))
      {:shown (persistent! shown) :ordinal (persistent! ordinal) :snap (persistent! snap)})))

(defn- frames-over
  "Frames over the SHOWN pages (physical indices `shown`): a maximal run of shown pages, adjacent in the audience's
   order, whose labels are equal (frames-from-labels' rule, applied to the shown pages' labels) or whose later page
   is a FORCED overlay (pdfpc's `forcedOverlay` / legacy `[skip]`: the page joins the previous frame). Each frame is
   [first last] in physical indices — a frame may enclose hidden pages, the note pages written after its overlays."
  [shown labels group? forced]
  (let [m      (count shown)
        lab    (when (and group? labels) (fn [o] (nth labels (nth shown o))))
        joins? (fn [o] (or (contains? forced (nth shown o))
                           (and lab (let [a (lab (dec o)) b (lab o)]
                                      (and (string? a) (not (str/blank? a)) (= a b))))))]
    (if (<= m 0)
      []
      (loop [o 1, start 0, acc (transient [])]
        (cond
          (>= o m)   (persistent! (conj! acc [(nth shown start) (nth shown (dec m))]))
          (joins? o) (recur (inc o) start acc)
          :else      (recur (inc o) o (conj! acc [(nth shown start) (nth shown (dec o))])))))))

(defn- frame-lookup
  "The page → frame vector when some pages are hidden: a page belongs to the frame of the shown page it snaps to, so a
   note page written after a frame's last overlay — outside that frame's [first last] — belongs to its slide's frame.
   `snap` never decreases along the pages and the frames ascend, so one pointer walks the frames once."
  [n frames snap]
  (let [nf (count frames)]
    (loop [p 0, k 0, acc (transient [])]
      (if (< p n)
        (let [s (nth snap p)
              k (loop [k k]
                  (if (and (< (inc k) nf) (>= s (first (nth frames (inc k))))) (recur (inc k)) k))]
          (recur (inc p) k (conj! acc k)))
        (persistent! acc)))))

(defn deck
  "Assemble the deck model from evidence `{:creator :producer :creator-tool :page-mode :labels :sizes}`. Frames
   are grouped by page labels only for Beamer sources (:beamer, :beamer-geometry) — other producers' labels are
   ordinary page numbers. Works for ANY PDF: a non-deck gets {:deck? false} and one page per frame, which is what
   \"View as Slide Show\" on an arbitrary PDF navigates.

   :second-screen is the axis (second-screen-axis) when EVERY page doubles one Beamer paper, else nil. Such a deck
   gets one frame per page and NO labels: pgfpages' two-screen shipout corrupts Beamer's page labels, and the
   corruption is not detectable — measured on LuaLaTeX output, five frames with notes (the last with two overlays)
   are labelled 1 1 2 2 3 3 (not 1 2 3 4 5 5), a well-formed sequence that would merge frames 1+2 and 3+4. (The
   `previous slide` variant keeps correct labels, but its pages are indistinguishable by geometry, so it is treated
   alike.) The page number is always right; a corrupt label would make the counter, typed-number goto and frame
   steps lie. `:split-axis` names an axis to split pages along when the geometry gives none — pdfpc's notes
   position on a deck whose pages are not a doubled Beamer paper (ADR-0048 §5); its labels are dropped alike.

   The audience's pages (ADR-0048 §3, §5), all optional — without them the model is exactly ADR-0045's:
     :hidden     pages the audience never sees (note pages); page 0 and out-of-range pages are ignored
     :skipped    pdfpc's hidden pages: :next/:prev and the frame steps pass over them; Home, End, a typed number
                 and the overview still reach them
     :forced     pdfpc's forced overlays: the page joins the previous frame
     :end-frame  pdfpc's end slide (0-based frame): End goes first to its last page
   The model then also carries :shown (the shown pages, ascending), :ordinal (each page's position among them, nil
   when hidden) and :snap (the page each page shows as)."
  [evidence]
  (let [cls     (classify evidence)
        n       (count (:sizes evidence))
        uniform (uniform-size (:sizes evidence))
        axis    (or (when uniform (second-screen-axis uniform))
                    (#{:horizontal :vertical} (:split-axis evidence)))
        labels  (when-not axis (some-> (:labels evidence) vec))
        labels  (when (= n (count labels)) labels)
        group?  (and (not axis) (contains? #{:beamer :beamer-geometry} (:source cls)))
        hidden  (page-set (:hidden evidence) 1 n)
        forced  (page-set (:forced evidence) 1 n)
        {:keys [shown ordinal snap]} (audience-pages n hidden)
        frames  (if (and (empty? hidden) (empty? forced))
                  (frames-from-labels n labels group?)
                  (frames-over shown labels group? forced))
        end     (:end-frame evidence)]
    (assoc cls
           :pages         n
           :frames        frames
           :frame-of      (if (empty? hidden) (frame-index frames) (frame-lookup n frames snap))
           :labels        labels
           :second-screen axis
           :hidden        hidden
           :skipped       (page-set (:skipped evidence) 0 n)
           :forced        forced
           :end-frame     (when (and (integer? end) (<= 0 end) (< end (count frames))) end)
           :shown         shown
           :ordinal       ordinal
           :snap          snap)))

(defn summary
  "The small, serializable part of a deck for app state (:doc/deck): no per-page vectors."
  [d]
  {:deck?         (boolean (:deck? d))
   :tier          (:tier d)
   :source        (:source d)
   :pages         (:pages d 0)
   :frame-count   (count (:frames d))
   :second-screen (:second-screen d)})

;; ---- positions ------------------------------------------------------------------------------------------

(defn frame-of
  "The frame index containing page p (0 for an empty deck)."
  [d p]
  (nth (:frame-of d) p 0))

(defn label-of
  "The display label of page p: its PDF page label when the deck has one, else its 1-based number."
  [d p]
  (or (nth (:labels d) p nil) (str (inc p))))

(defn clamp-page
  "Clamp p into [0, pages-1] (0 for an empty deck)."
  [d p]
  (-> p (max 0) (min (max 0 (dec (:pages d 0))))))

(defn snap
  "The page the audience sees for page p (clamped): p itself when it is shown, else its slide — the nearest shown
   page BELOW it (ADR-0048 §3). A snap never moves up, so two windows that disagree for a while about which pages are
   hidden cannot snap each other back and forth (docs/theory/11 §5.6)."
  [d p]
  (let [p (clamp-page d p)]
    (if-let [s (:snap d)] (nth s p p) p)))

(defn- shown-pages
  "The shown pages of `d` — every page for a model built without :hidden (or by hand)."
  [d]
  (or (:shown d) (vec (range (:pages d 0)))))

(defn- ordinal-of
  "Page p's position among the shown pages (p itself for a model without the vector); nil when hidden."
  [d p]
  (if-let [o (:ordinal d)] (nth o p nil) p))

(defn position
  "Everything a slide counter shows for page p: {:page :pages :frame :frames :overlay :overlays :label}, with
   :overlay/:overlays 0-based/count within the frame (a one-page frame is overlay 0 of 1), counted over the frame's
   SHOWN pages — a hidden page reports its slide's overlay. :page and :pages stay physical (the engine's slots are)."
  [d p]
  (let [p      (clamp-page d p)
        f      (frame-of d p)
        [s e]  (nth (:frames d) f [p p])
        os     (ordinal-of d s)
        o      (ordinal-of d (snap d p))
        oe     (ordinal-of d e)]
    {:page p :pages (:pages d 0) :frame f :frames (count (:frames d))
     :overlay (- o os) :overlays (inc (- oe os)) :label (label-of d p)}))

;; ---- navigation -----------------------------------------------------------------------------------------

(def history-limit
  "The jump history (GoBack/GoForward) keeps at most this many entries per direction."
  64)

(defn- parse-pos-int [s]
  (when (re-matches #"\d{1,7}" s) (js/parseInt s 10)))

(defn resolve-goto
  "The page a typed slide reference `s` names, or nil: first the shown page whose LABEL is `s` (in a Beamer deck the
   label is the frame number, so \"7\" opens frame 7's first overlay — the number on the slide), else `s` as a
   1-based position among the shown pages (the physical page number when nothing is hidden). A hidden page's label —
   a note page's own page number — is never matched (ADR-0048 §3)."
  [d s]
  (let [s     (str/trim (str s))
        shown (shown-pages d)]
    (when (seq s)
      (or (when-let [labels (:labels d)]
            (some (fn [p] (when (= (nth labels p) s) p)) shown))
          (when-let [k (parse-pos-int s)]
            (when (<= 1 k (count shown)) (nth shown (dec k))))))))

(defn- push-bounded [v x]
  (let [v (conj (or v []) x)]
    (if (> (count v) history-limit) (subvec v (- (count v) history-limit)) v)))

(defn- jump [nav to]
  (if (= to (:page nav))
    nav
    (assoc nav :page to :back (push-bounded (:back nav) (:page nav)) :fwd [])))

(defn- unskipped
  "The first shown page from position `o` on, stepping by `delta` (+1 forward, -1 back), that pdfpc does not skip
   (`:skipped`); nil when there is none."
  [d shown o delta]
  (let [skipped (:skipped d)
        m       (count shown)]
    (loop [o o]
      (when (and (<= 0 o) (< o m))
        (let [p (nth shown o)]
          (if (contains? skipped p) (recur (+ o delta)) p))))))

(defn- unskipped-at
  "Page t, or — when pdfpc skips it — the nearest page that is not skipped in direction `delta`; nil when none is."
  [d shown t delta]
  (if (contains? (:skipped d) t) (unskipped d shown (ordinal-of d t) delta) t))

(defn step
  "Apply navigation `op` to `nav` within deck `d`; returns the new nav. Every op runs over the SHOWN pages, from the
   current page's snap (ADR-0048 §3):
     :next / :prev               one shown page (one overlay) forward / back, clamped at the ends
     :next-frame                 the FIRST page of the next frame (skips the rest of this frame's overlays);
                                 on the last frame, its last page
     :prev-frame                 the LAST page of the previous frame — the slide fully built, as presenters
                                 expect when going back; on the first frame, page 0
     :first / :last              page 0 / the last shown page — or first the end slide's last page when the deck
                                 names one (pdfpc's end slide, ADR-0048 §6.5)        (jumps)
     [:goto p]                   physical page p, clamped and snapped           (jump)
     [:goto-label s]             resolve-goto; no-op when s names nothing       (jump)
     [:jump p]                   a link/outline/overview target                 (jump)
     [:show p]                   page p, clamped and snapped — NO history: how a window follows another (ADR-0048 §8)
     :back / :forward            walk the jump history (PDF GoBack/GoForward)
   Only JUMPS record history; sequential steps never do, so Back returns to where a link was followed from. The
   sequential steps pass over pdfpc's skipped pages (:skipped), staying put when only skipped pages lie ahead."
  [d nav op]
  (let [n (:pages d 0)]
    (if (zero? n)
      nav
      (let [p       (snap d (:page nav 0))
            nav     (assoc nav :page p)
            shown   (shown-pages d)
            o       (ordinal-of d p)
            frames  (:frames d)
            f       (frame-of d p)
            last-f  (dec (count frames))
            kind    (if (vector? op) (first op) op)]
        (case kind
          :next       (assoc nav :page (or (unskipped d shown (inc o) 1) p))
          :prev       (assoc nav :page (or (unskipped d shown (dec o) -1) p))
          :next-frame (assoc nav :page (or (if (< f last-f)
                                             (unskipped-at d shown (first (nth frames (inc f))) 1)
                                             (unskipped-at d shown (second (nth frames last-f)) 1))
                                           p))
          :prev-frame (assoc nav :page (or (unskipped-at d shown (if (pos? f) (second (nth frames (dec f))) 0) -1) p))
          :first      (jump nav (first shown))
          :last       (let [end (some->> (:end-frame d) (nth frames) second)]
                        (jump nav (if (and end (< p end)) end (peek shown))))
          :goto       (jump nav (snap d (second op)))
          :jump       (jump nav (snap d (second op)))
          :show       (assoc nav :page (snap d (second op)))
          :goto-label (if-let [t (resolve-goto d (second op))] (jump nav t) nav)
          :back       (if-let [t (peek (:back nav))]
                        (assoc nav :page (snap d t) :back (pop (:back nav)) :fwd (push-bounded (:fwd nav) p))
                        nav)
          :forward    (if-let [t (peek (:fwd nav))]
                        (assoc nav :page (snap d t) :fwd (pop (:fwd nav)) :back (push-bounded (:back nav) p))
                        nav)
          nav)))))

;; ---- input mapping (pure) -------------------------------------------------------------------------------

(def named-actions
  "The PDF named actions (PDF 32000-1:2008 §12.6.4.11, `/S /Named`) that a LINK in a document may trigger, mapped
   to what the viewer does: the paging ops of `step`, and :find (open the find bar). An ALLOW-LIST — every other
   named action (Print, SaveAs, Quit, FullScreen, Open, …) is ignored, so a document can page itself and open find
   but never print, save, quit or make the window fullscreen (ADR-0045 D13). Beamer emits GoBack / Find /
   GoForward for its back-find-forward navigation symbol; hyperref's \\Acrobatmenu can emit any of them."
  {"NextPage" :next "PrevPage" :prev "FirstPage" :first "LastPage" :last
   "GoBack"   :back "GoForward" :forward "Find" :find})

(defn named-action-op
  "The op for PDF named action `action` (a string), or nil when it is not allowed."
  [action]
  (get named-actions action))

(def max-digits
  "A typed slide number keeps at most this many digits."
  6)

(defn digits-step
  "Append digit `d` (0–9) to the typed-number buffer `s`, keeping at most `max-digits` digits."
  [s d]
  (let [s (str s d)]
    (if (> (count s) max-digits) (subs s (- (count s) max-digits)) s)))

(defn scroll->op
  "Map a content-scroll command (the `:nav/scroll` opts every scroll key produces — arrows, PgUp/PgDn, Home/End,
   Vim j/k/C-d/C-f…, Emacs C-v/M-v…) to a slide op, so ONE scroll delegate turns every keymap's scrolling into
   slide navigation. `:shift?` (Shift+PgDn etc.) promotes a page step to a frame step. nil = not a navigation."
  [{:keys [dy dx to shift?]}]
  (let [base (cond
               (= to :top)                           :first
               (= to :bottom)                        :last
               (= dx :right)                         :next
               (= dx :left)                          :prev
               (#{:page :half} dy)                   :next
               (#{:-page :-half} dy)                 :prev
               (and (number? dy) (pos? dy))          :next
               (and (number? dy) (neg? dy))          :prev
               :else                                 nil)]
    (if shift? (get {:next :next-frame :prev :prev-frame} base base) base)))

(def wheel-threshold
  "Accumulated normalized wheel delta that flips one slide (pdf.js presentation mode's PAGE_SWITCH_THRESHOLD)."
  0.1)

(def wheel-pixels-per-unit
  "Wheel pixels per normalized unit: pdf.js normalizeWheelEventDelta's MOUSE_PIXELS_PER_LINE (30) × its
   MOUSE_LINES_PER_PAGE (30). A mouse-wheel notch is 120 device-independent pixels in Chromium on Linux/X11
   (measured, docs/scientific/12 §8.7), so it is one flip — 120/900 ≈ 0.133, clear of the threshold, where the old
   ÷ 1200 sat exactly ON it — and so is any notch of at least 90 pixels."
  900)

(def wheel-lines-per-unit
  "Wheel lines per normalized unit (pdf.js's MOUSE_LINES_PER_PAGE): a browser that scrolls a notch as 3 lines
   reaches the threshold in one notch."
  30)

(defn wheel-notches
  "One wheel event's delta {:dx :dy :mode :zoom} in pdf.js's normalized units (normalizeWheelEventDelta), signed
   TOWARDS THE NEXT SLIDE: the length of the (dx, dy) vector, positive when scrolling down or right. A pixel delta
   (mode 0) is first scaled back to device-independent pixels by the window `zoom`: Chromium reports it in CSS
   pixels, a notch's 120 divided by the zoom (measured: 96 at 125 %, 80 at 150 %), so without this a zoomed-in
   window would need two notches per slide. Then pixels ÷ wheel-pixels-per-unit, lines (mode 1) ÷
   wheel-lines-per-unit, and pages (mode 2) as they are."
  [{:keys [dx dy mode zoom]}]
  (let [dx    (or dx 0)
        dy    (or dy 0)
        len   (js/Math.hypot dx dy)
        angle (js/Math.atan2 dy dx)
        d     (if (and (< (* -0.25 js/Math.PI) angle) (< angle (* 0.75 js/Math.PI))) len (- len))]
    (case mode
      0 (/ (* d (or zoom 1)) wheel-pixels-per-unit)
      1 (/ d wheel-lines-per-unit)
      d)))

(def wheel-cooldown-ms
  "After a wheel flip, ignore wheel input for this long (pdf.js MOUSE_SCROLL_COOLDOWN_TIME) so one trackpad
   fling cannot skip several slides."
  50)

(defn wheel-step
  "Accumulate one normalized wheel `delta` (wheel-notches: positive = towards the next slide) at time `now` (ms). State {:acc :last} where :last is the time of the
   last flip. Returns {:state state' :op :next|:prev|nil}. The accumulator resets when the direction changes."
  [{:keys [acc last] :or {acc 0 last -1e15}} delta now]
  (cond
    (< (- now last) wheel-cooldown-ms)
    {:state {:acc 0 :last last} :op nil}

    :else
    (let [acc (if (or (and (pos? acc) (neg? delta)) (and (neg? acc) (pos? delta))) 0 acc)
          acc (+ acc delta)]
      (if (>= (js/Math.abs acc) wheel-threshold)
        {:state {:acc 0 :last now} :op (if (pos? acc) :next :prev)}
        {:state {:acc acc :last last} :op nil}))))

(def swipe-min-px
  "A swipe must travel at least this far horizontally (CSS px) to flip a slide."
  48)

(def swipe-max-ms
  "…within this long (a slow drag is not a swipe)."
  700)

(def swipe-ratio
  "…and at least this many times as far horizontally as vertically (a vertical drag is not a page turn)."
  1.5)

(defn swipe-op
  "The slide op of one touch / pen gesture from press to release: `dx` `dy` (CSS px, release minus press) over `dt`
   (ms). A quick, mostly horizontal swipe flips one slide — leftward (dx < 0) to the next, rightward to the previous,
   like turning a page; anything else is nil."
  [{:keys [dx dy dt]}]
  (when (and (number? dx) (number? dy) (number? dt)
             (<= dt swipe-max-ms)
             (>= (js/Math.abs dx) swipe-min-px)
             (>= (js/Math.abs dx) (* swipe-ratio (js/Math.abs dy))))
    (if (neg? dx) :next :prev)))

;; ---- slide transitions ---------------------------------------------------------------------------------

(def transition-ms
  "How long each transition kind takes (ms) — short: a presenter's flip must never feel sluggish."
  {:fade 220 :push 260})

(defn transition-plan
  "PURE. How a slide flip from page `from` to page `to` is animated under preference `transition`
   (:none — the default — :fade or :push): {:kind :fade|:push :direction :forward|:backward :ms n}, or nil for an
   instant flip — no transition chosen, no actual page change, or the viewer asked the OS for reduced motion
   (`reduced-motion?`, prefers-reduced-motion: reduce). The direction follows the page order, so Back after a jump
   pushes the old slide the other way."
  [{:keys [transition from to reduced-motion?]}]
  (let [kind (#{:fade :push} transition)]
    (when (and kind (not reduced-motion?) (number? from) (number? to) (not= from to))
      {:kind kind :direction (if (> to from) :forward :backward) :ms (transition-ms kind)})))

;; ---- overview grid --------------------------------------------------------------------------------------

(defn overview-tiles
  "One tile per frame: {:frame f :page representative :alt other-end :label}. `rep` :last (default — the frame
   fully built, as pdfpc's overview opens it) or :first; Shift+Enter opens :alt."
  [d rep]
  (vec (map-indexed (fn [f [s e]]
                      (let [[page alt] (if (= rep :first) [s e] [e s])]
                        {:frame f :page page :alt alt :label (label-of d s)}))
                    (:frames d))))

(defn grid-move
  "Move the overview selection `sel` (0-based tile index) by `op` in a grid of `n` tiles laid out `cols` per row;
   `page-rows` rows per PgUp/PgDn. Left/right walk the reading order (wrapping rows); up/down keep the column and
   stay put at the top/bottom edge; everything clamps into [0, n-1]."
  [sel op {:keys [n cols page-rows]}]
  (if (<= (or n 0) 0)
    0
    (let [cols  (max 1 (or cols 1))
          rows  (max 1 (or page-rows 1))
          clamp #(-> % (max 0) (min (dec n)))
          vert  (fn [t] (if (and (>= t 0) (< t n)) t sel))]
      (clamp (case op
               :left      (dec sel)
               :right     (inc sel)
               :up        (vert (- sel cols))
               :down      (vert (+ sel cols))
               :page-up   (- sel (* cols rows))
               :page-down (+ sel (* cols rows))
               :home      0
               :end       (dec n)
               sel)))))

;; ---- layout choice --------------------------------------------------------------------------------------

(defn effective-layout
  "Which layout a PDF view shows: presenting → :slides; else the document view's explicit choice
   (:slides/:document); else a detected deck opens as :slides unless the preference `open-as` is :document;
   everything else is the continuous :document layout."
  [{:keys [presenting? explicit deck? open-as]}]
  (cond
    presenting?                          :slides
    (#{:slides :document} explicit)      explicit
    (and deck? (not= open-as :document)) :slides
    :else                                :document))
