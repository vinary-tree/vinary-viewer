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
   are what the tools emit for every slide."
  {:powerpoint-169 [960 540]
   :powerpoint-43  [720 540]
   :google-169     [720 405]})

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
   Otherwise every page is its own frame (a deck built without page labels, e.g. by xdvipdfmx, or any non-Beamer
   PDF)."
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

(defn deck
  "Assemble the deck model from evidence `{:creator :producer :creator-tool :page-mode :labels :sizes}`. Frames
   are grouped by page labels only for Beamer sources (:beamer, :beamer-geometry) — other producers' labels are
   ordinary page numbers. Works for ANY PDF: a non-deck gets {:deck? false} and one page per frame, which is what
   \"View as Slide Show\" on an arbitrary PDF navigates.

   :second-screen is the axis (second-screen-axis) when EVERY page doubles one Beamer paper, else nil. Such a deck
   gets one frame per page and NO labels: pgfpages' two-screen shipout corrupts Beamer's page labels, and the
   corruption is not detectable — measured on LuaLaTeX output, five single-overlay frames with notes are labelled
   1 1 2 2 3 3 (not 1 2 3 4 5 5), a well-formed sequence that would merge frames 1+2 and 3+4. (The `previous slide`
   variant keeps correct labels, but its pages are indistinguishable by geometry, so it is treated alike.) The page
   number is always right; a corrupt label would make the counter, typed-number goto and frame steps lie."
  [evidence]
  (let [cls     (classify evidence)
        n       (count (:sizes evidence))
        uniform (uniform-size (:sizes evidence))
        axis    (when uniform (second-screen-axis uniform))
        labels  (when-not axis (some-> (:labels evidence) vec))
        group?  (and (not axis) (contains? #{:beamer :beamer-geometry} (:source cls)))
        frames  (frames-from-labels n labels group?)]
    (assoc cls
           :pages         n
           :frames        frames
           :frame-of      (frame-index frames)
           :labels        (when (= n (count labels)) labels)
           :second-screen axis)))

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

(defn position
  "Everything a slide counter shows for page p: {:page :pages :frame :frames :overlay :overlays :label}, with
   :overlay/:overlays 0-based/count within the frame (a one-page frame is overlay 0 of 1)."
  [d p]
  (let [p      (clamp-page d p)
        f      (frame-of d p)
        [s e]  (nth (:frames d) f [p p])]
    {:page p :pages (:pages d 0) :frame f :frames (count (:frames d))
     :overlay (- p s) :overlays (inc (- e s)) :label (label-of d p)}))

;; ---- navigation -----------------------------------------------------------------------------------------

(def history-limit
  "The jump history (GoBack/GoForward) keeps at most this many entries per direction."
  64)

(defn- parse-pos-int [s]
  (when (re-matches #"\d{1,7}" s) (js/parseInt s 10)))

(defn resolve-goto
  "The page a typed slide reference `s` names, or nil: first the page whose LABEL is `s` (in a Beamer deck the
   label is the frame number, so \"7\" opens frame 7's first overlay — the number on the slide), else `s` as a
   1-based physical page number."
  [d s]
  (let [s (str/trim (str s))]
    (when (seq s)
      (or (when-let [labels (:labels d)]
            (first (keep-indexed (fn [i l] (when (= l s) i)) labels)))
          (when-let [k (parse-pos-int s)]
            (when (<= 1 k (:pages d 0)) (dec k)))))))

(defn- push-bounded [v x]
  (let [v (conj (or v []) x)]
    (if (> (count v) history-limit) (subvec v (- (count v) history-limit)) v)))

(defn- jump [nav to]
  (if (= to (:page nav))
    nav
    (assoc nav :page to :back (push-bounded (:back nav) (:page nav)) :fwd [])))

(defn step
  "Apply navigation `op` to `nav` within deck `d`; returns the new nav. Ops:
     :next / :prev               one page (one overlay) forward / back, clamped at the ends
     :next-frame                 the FIRST page of the next frame (skips the rest of this frame's overlays);
                                 on the last frame, its last page
     :prev-frame                 the LAST page of the previous frame — the slide fully built, as presenters
                                 expect when going back; on the first frame, page 0
     :first / :last              page 0 / the last page                       (jumps)
     [:goto p]                   physical page p, clamped                       (jump)
     [:goto-label s]             resolve-goto; no-op when s names nothing       (jump)
     [:jump p]                   a link/outline/overview target                 (jump)
     :back / :forward            walk the jump history (PDF GoBack/GoForward)
   Only JUMPS record history; sequential steps never do, so Back returns to where a link was followed from."
  [d nav op]
  (let [n (:pages d 0)]
    (if (zero? n)
      nav
      (let [p       (clamp-page d (:page nav 0))
            nav     (assoc nav :page p)
            frames  (:frames d)
            f       (frame-of d p)
            last-f  (dec (count frames))
            kind    (if (vector? op) (first op) op)]
        (case kind
          :next       (assoc nav :page (min (inc p) (dec n)))
          :prev       (assoc nav :page (max (dec p) 0))
          :next-frame (assoc nav :page (if (< f last-f) (first (nth frames (inc f))) (second (nth frames last-f))))
          :prev-frame (assoc nav :page (if (pos? f) (second (nth frames (dec f))) 0))
          :first      (jump nav 0)
          :last       (jump nav (dec n))
          :goto       (jump nav (clamp-page d (second op)))
          :jump       (jump nav (clamp-page d (second op)))
          :goto-label (if-let [t (resolve-goto d (second op))] (jump nav t) nav)
          :back       (if-let [t (peek (:back nav))]
                        (assoc nav :page (clamp-page d t) :back (pop (:back nav)) :fwd (push-bounded (:fwd nav) p))
                        nav)
          :forward    (if-let [t (peek (:fwd nav))]
                        (assoc nav :page (clamp-page d t) :fwd (pop (:fwd nav)) :back (push-bounded (:back nav) p))
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

(def wheel-cooldown-ms
  "After a wheel flip, ignore wheel input for this long (pdf.js MOUSE_SCROLL_COOLDOWN_TIME) so one trackpad
   fling cannot skip several slides."
  50)

(defn wheel-step
  "Accumulate one normalized wheel `delta` (positive = towards the next slide; pixel deltas ÷ 1200, line deltas
   ÷ 30 — pdf.js normalizeWheelEventDelta) at time `now` (ms). State {:acc :last} where :last is the time of the
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
