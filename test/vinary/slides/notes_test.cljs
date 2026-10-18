(ns vinary.slides.notes-test
  "The speaker-notes model (ADR-0048 §1–§5): the label automaton, the note-page signature, the probe, scan and
   confirmation steps, pdfpc's XMP keys and sidecar, the repair of pdfTeX's notes, and the model the engine and
   `vv --cli --deck-info` share. DOM-free and pdf.js-free: text runs are plain data, as vinary.slides.evidence makes
   them. The real decks in test/fixtures are checked end to end by test/cli-smoke.js."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [vinary.slides.deck :as deck]
            [vinary.slides.notes :as notes]))

;; ---- runs ---------------------------------------------------------------------------------------------------------

(defn- run
  "An upright text run: `s` at origin (x, y), height h."
  [s x y h]
  {:str s :t [h 0 0 h x y]})

(defn- turned
  "A run rotated by 90°, as Beamer draws its note date."
  [s x y h]
  {:str s :t [0 h (- h) 0 x y]})

(defn- thumbnail
  "The runs of `slide` drawn at scale σ with offset (tx, ty), as a note template's thumbnail draws them."
  [slide sigma tx ty]
  (mapv (fn [{s :str [a b c d e f] :t}] {:str s :t [(* sigma a) (* sigma b) (* sigma c) (* sigma d)
                                                     (+ tx (* sigma e)) (+ ty (* sigma f))]})
        slide))

(def ^:private paper [362.835 272.126])                         ; Beamer's 128 mm × 96 mm, in points
(def ^:private diag (js/Math.hypot 362.835 272.126))

(def ^:private intro
  [(run "Introduction" 28 230 14.3) (run "First point of the talk" 34 180 10.9)
   (run "Second point, with more words" 34 160 10.9) (run "Speaker notes fixture" 120 8 5.5)])

(def ^:private middle
  [(run "Middle" 28 230 14.3) (run "Another slide entirely" 34 180 10.9) (run "Speaker notes fixture" 120 8 5.5)])

(defn- note-page
  "A default-template note page for `slide`: its thumbnail at σ = 0.25, top right; the frame title repeated at
   σ ≈ 0.69 (the template's own title); the rotated date; and the notes."
  [slide]
  (into (thumbnail slide 0.25 268.8 204.1)
        [(run (:str (first slide)) 10 250 9.9)
         (turned "2026-10-17" 20 120 5.5)
         (run "Say the first thing." 10 150 10.9)
         (run "Then the second." 10 135 10.9)]))

;; ---- labels and the automaton -------------------------------------------------------------------------------------

(deftest integer-labels-are-canonical-non-negative-integers
  (is (= [1 2 1 4] (notes/integer-labels ["1" "2" "1" "4"])))
  (is (= [0 10] (notes/integer-labels ["0" "10"])))
  (is (nil? (notes/integer-labels ["1" "ii"])) "one non-integer label and the automaton does not run")
  (is (nil? (notes/integer-labels ["01" "2"])) "a leading zero is not how Beamer or hyperref write a number")
  (is (nil? (notes/integer-labels ["-1" "2"])))
  (is (nil? (notes/integer-labels ["1 " "2"])))
  (is (nil? (notes/integer-labels [])))
  (is (nil? (notes/integer-labels nil))))

(deftest the-show-notes-fixture-is-proven
  ;; test/fixtures/beamer-shownotes.tex, as LuaLaTeX labels it: Intro o1, note, Intro o2, note, a between-frames note,
  ;; Middle, Outro o1, Outro o2, note, Middle again (\againframe)
  (is (= {:status :proven :note-pages #{1 3 4 8}} (notes/interleaving [1 2 1 4 5 2 3 3 9 4]))))

(deftest only-notes-is-invalid
  ;; beamer-onlynotes.pdf: page 3's label 3 is neither frame 1 nor 2, nor its page number 4
  (is (= {:status :invalid} (notes/interleaving [1 1 3 3]))))

(deftest a-deck-without-note-pages
  (is (= {:status :none} (notes/interleaving [1 1 2 3 4 4])) "overlays and frames, no label equals its page number")
  (is (= {:status :none} (notes/interleaving [1])) "one page")
  (is (= {:status :none} (notes/interleaving []))))

(deftest a-plain-run-is-ambiguous
  ;; beamer-plain.pdf: one page per frame, labelled 1 to 5. Every page from 1 on could be the first note page.
  (is (= {:status :ambiguous :candidates [1 2 3 4] :run-end 4 :base #{} :fallback? true}
         (notes/interleaving [1 2 3 4 5])))
  (is (= {:status :ambiguous :candidates [1] :run-end 1 :base #{} :fallback? true}
         (notes/interleaving [1 2 2 3]))
      "1 2 2 3: page 1 is a note (frame 1 continues) or frame 2"))

(deftest the-fallback-keeps-later-note-pages
  ;; 1 2 2 4 3 6: pages 3 and 5 can only be note pages, whatever page 1 is. The parse with no note page in the plain
  ;; run is not "all slides" (ADR-0048 §2.1 as first written said so): it is {3 5}.
  (let [il (notes/interleaving [1 2 2 4 3 6])]
    (is (= {:status :ambiguous :candidates [1] :run-end 1 :base #{3 5} :fallback? true} il))
    (is (= #{1 3 5} (notes/candidate-notes il 1)))
    (is (= #{3 5} (notes/fallback-notes il)))))

(deftest a-run-whose-every-choice-has-notes-has-no-fallback
  ;; 1 2 3 4 5 2 3: page 5's label 2 needs frame 1 or 2 before it, so pages 2 … 4 are notes; page 1 may be either
  (let [il (notes/interleaving [1 2 3 4 5 2 3])]
    (is (= {:status :ambiguous :candidates [1 2] :run-end 4 :base #{} :fallback? false} il))
    (is (= #{2 3 4} (notes/candidate-notes il 2)))
    (is (nil? (notes/fallback-notes il)))))

(deftest a-first-label-of-zero-is-never-ambiguous
  (is (= {:status :proven :note-pages #{1 2}} (notes/interleaving [0 2 3])))
  (is (= {:status :proven :note-pages #{2 3}} (notes/interleaving [0 1 3 4])))
  (is (= {:status :none} (notes/interleaving [0 1 2 2]))))

(deftest a-frame-counter-set-by-hand-is-undecidable
  ;; L0 = 2: parses {1 3} and {1} … share their first note page, which the probe cannot tell apart
  (is (= {:status :undecidable} (notes/interleaving [2 2 3 4]))))

(deftest a-label-that-drops-below-the-frame-is-invalid
  (is (= {:status :invalid} (notes/interleaving [1 1 2 2 3 1 2])) "a restart after overlays")
  (is (= {:status :invalid} (notes/interleaving [1 2 3 3 1 2])) "a restart after an overlay")
  (is (= {:status :invalid} (notes/interleaving [2 1])) "a label below the first"))

(deftest the-automaton-agrees-with-brute-force
  ;; every parse of every label sequence of length ≤ 5 over 0 … 7, enumerated directly from ADR-0048 §2.1's rules,
  ;; against the gap decomposition: the same valid parses, so the same status and note pages
  (let [valid? (fn [L roles]
                 (loop [i 1, f (nth L 0)]
                   (if (= i (count L))
                     true
                     (let [l (nth L i)]
                       (case (nth roles i)
                         :slide (if (or (= l f) (= l (inc f))) (recur (inc i) l) false)
                         :note  (if (= l (inc i)) (recur (inc i) f) false))))))
        parses (fn [L]
                 (let [n (count L)]
                   (for [mask (range (bit-shift-left 1 (dec n)))
                         :let [roles (into [:slide] (map #(if (bit-test mask %) :note :slide)) (range (dec n)))]
                         :when (valid? L roles)]
                     (into #{} (filter #(= :note (nth roles %))) (range n)))))
        seqs   (fn seqs [n] (if (zero? n) [[]] (for [s (seqs (dec n)) l (range 8)] (conj s l))))
        checked (atom 0)]
    (doseq [n (range 1 6)
            L (seqs n)]
      (let [ps (set (parses L))
            il (notes/interleaving L)]
        (swap! checked inc)
        (case (:status il)
          :invalid     (is (empty? ps) (str L))
          :none        (is (= #{#{}} ps) (str L))
          :proven      (is (= #{(:note-pages il)} ps) (str L))
          :ambiguous   (do (is (= ps (cond-> (set (map #(notes/candidate-notes il %) (:candidates il)))
                                       (:fallback? il) (conj (:base il))))
                               (str L))
                           (is (= (count ps) (+ (count (:candidates il)) (if (:fallback? il) 1 0))) (str L)))
          :undecidable (is (and (> (count ps) 1) (>= (first L) 2)) (str L)))))
    (is (= (reduce + (map #(js/Math.pow 8 %) (range 1 6))) @checked))))

(deftest a-restarted-frame-counter-is-not-proof
  ;; NEGATIVE CONTROL for the guard: the automaton alone "proves" that a plain deck whose appendix restarts the frame
  ;; counter (appendixnumberbeamer) is one slide and four note pages …
  (is (= {:status :proven :note-pages #{1 2 3 4}} (notes/interleaving [1 2 3 4 5 1 2])))
  ;; … and restartable? names that reading, while no real notes deck of the fixtures reads so
  (is (notes/restartable? [1 2 3 4 5 1 2]))
  (is (notes/restartable? [1 2 3]))
  (is (not (notes/restartable? [1 2 1 4 5 2 3 3 9 4])) "1 → 4 jumps by three")
  (is (not (notes/restartable? [0 2 3]))))

;; ---- the note-page signature --------------------------------------------------------------------------------------

(deftest a-thumbnail-is-a-scaled-copy
  (is (true? (notes/note-page-signature? intro (note-page intro) diag)))
  (is (true? (notes/note-page-signature? intro (thumbnail intro 0.125 300 230) diag)) "the compressed template's σ"))

(deftest the-next-slide-is-not-a-note-page
  ;; the footline repeats on every slide at σ = 1, which the bound excludes
  (is (false? (notes/note-page-signature? intro middle diag)))
  (is (false? (notes/note-page-signature? intro intro diag)) "the same slide again: an overlay"))

(deftest the-templates-own-title-is-not-a-thumbnail
  (is (false? (notes/note-page-signature? intro [(run "Introduction" 10 250 9.9)] diag)) "σ ≈ 0.69 > 0.4")
  (is (false? (notes/note-page-signature? intro (thumbnail intro 0.45 200 150) diag)) "just above the bound"))

(deftest half-the-characters-suffice
  ;; pdf.js may split the thumbnail's runs differently from the slide's: half of the slide's characters is enough
  (let [chars   (fn [rs] (reduce + (map #(count (str/replace (:str %) #"\s" "")) rs)))
        total   (chars intro)
        partial (fn [k] (thumbnail (subvec intro 0 k) 0.25 268.8 204.1))]
    (is (= 75 total))
    (is (= 31 (chars (subvec intro 0 2))))
    (is (false? (notes/note-page-signature? intro (partial 2) diag)) "31 of 75 characters")
    (is (= 56 (chars (subvec intro 0 3))))
    (is (true? (notes/note-page-signature? intro (partial 3) diag)) "56 of 75 characters")))

(deftest one-copy-not-the-sum-of-several
  ;; two partial thumbnails, of 31 and 19 of the slide's 75 characters, cover 50 together but each less than half;
  ;; at two offsets, or at two scales, they are two copies, not one
  (let [a (thumbnail (subvec intro 0 2) 0.25 268.8 204.1)
        b (thumbnail [(nth intro 3)] 0.25 20.0 20.0)]
    (is (false? (notes/note-page-signature? intro (into a b) diag)))
    (is (true? (notes/note-page-signature? intro (into a (thumbnail [(nth intro 3)] 0.25 268.8 204.1)) diag))
        "the same two parts at one offset are one copy (positive control)"))
  (testing "nor several scales"
    (let [a (thumbnail (subvec intro 0 2) 0.25 268.8 204.1)
          b (thumbnail [(nth intro 3)] 0.125 268.8 204.1)]
      (is (false? (notes/note-page-signature? intro (into a b) diag))))))

(deftest a-repeated-string-counts-once
  ;; a note page that repeats one short string of the slide many times, small, is no copy of the slide
  (let [slide [(run "x" 10 10 10) (run "a much longer line of text here" 10 30 10)]
        note  (mapv #(run "x" (+ 100 (* 0.001 %)) 50 2.5) (range 200))]
    (is (false? (notes/note-page-signature? slide note diag)))))

(deftest a-copy-across-a-grid-boundary-is-found
  ;; the offsets of a copy that spread less than half the tolerance land in one cell of one of the shifted grids,
  ;; wherever the grid's boundaries fall: place the copy's offset on a boundary of the unshifted grid and jitter it
  (let [tol    (* notes/offset-tolerance diag)
        jitter [-0.2 0.2 -0.2 0.2]]
    (doseq [tx [(* 37 tol) (* 37.5 tol) (* 37.99 tol)]]
      (let [note (mapv (fn [{s :str [_ _ _ _ e f] :t} j]
                         {:str s :t [2.5 0 0 2.5 (+ tx (* 0.25 e) (* j tol)) (+ (* 20 tol) (* 0.25 f) (* j tol))]})
                       (map #(assoc % :t [10 0 0 10 (get-in % [:t 4]) (get-in % [:t 5])]) intro)
                       jitter)
            slide (mapv #(assoc % :t [10 0 0 10 (get-in % [:t 4]) (get-in % [:t 5])]) intro)]
        (is (true? (notes/note-page-signature? slide note diag)) (str "offset " (/ tx tol) " cells"))))))

(deftest an-empty-slide-asks-the-date
  (is (true? (notes/note-page-signature? [] [(turned "2026-10-17" 20 120 5.5) (run "notes" 10 150 10.9)] diag)))
  (is (false? (notes/note-page-signature? [] [(run "notes" 10 150 10.9)] diag)))
  (is (nil? (notes/note-page-signature? [] [] diag)) "no text at all: no evidence")
  (is (nil? (notes/note-page-signature? [(run "   " 10 10 10)] [(run " " 1 1 1)] diag)) "white space is no text")
  (is (true? (notes/note-page-signature? [(turned "Rotated title" 10 10 10)] [(turned "2026-10-17" 20 120 5.5)] diag))
      "a slide whose only text is rotated counts as empty"))

(deftest hostile-pages-give-no-evidence
  (let [many (mapv #(run "x" % 10 10) (range (inc notes/max-signature-runs)))]
    (is (nil? (notes/note-page-signature? many (note-page intro) diag)))
    (is (nil? (notes/note-page-signature? intro many diag))))
  (let [slide (mapv #(run "same" % 10 10) (range 300))
        note  (mapv #(run "same" % 50 2.5) (range 300))]
    (is (nil? (notes/note-page-signature? slide note diag)) "300 × 300 pairs exceed max-signature-matches")))

(deftest the-rotated-date
  (is (true? (notes/rotated-date? [(turned "2026-10-17" 20 120 5.5)])))
  (is (true? (notes/rotated-date? [{:str "2026-10-17" :t [0 -5.5 5.5 0 20 120]}])) "−90° too")
  (is (true? (notes/rotated-date? [(turned "2026-" 20 120 5.5) (turned "10-17" 20 140 5.5)])) "split by pdf.js")
  (is (false? (notes/rotated-date? [(run "2026-10-17" 20 120 5.5)])) "upright")
  (is (false? (notes/rotated-date? [(turned "October 17, 2026" 20 120 5.5)])))
  (is (false? (notes/rotated-date? [(turned "2026-10-17 draft" 20 120 5.5)])))
  (is (false? (notes/rotated-date? []))))

;; ---- the probe, the scan, the confirmation --------------------------------------------------------------------------

(def ^:private plain-run (notes/interleaving [1 2 3 4 5]))

(deftest the-probe-walks-back-from-the-last-candidate
  (is (= {:read 4} (notes/probe-next plain-run [])))
  (is (= {:note-pages #{4} :via 4} (notes/probe-next plain-run [{:sig true :dated? true}])))
  (is (= {:read 3} (notes/probe-next plain-run [{:sig false :dated? true}])) "a dated page: a note of an earlier slide")
  (is (= {:note-pages #{2 3 4} :via 2}
         (notes/probe-next plain-run [{:sig false :dated? true} {:sig false :dated? true} {:sig true :dated? true}]))))

(deftest a-deck-without-notes-costs-one-pair
  (is (= {:note-pages #{} :via :fallback} (notes/probe-next plain-run [{:sig false :dated? false}])))
  (is (= {:note-pages #{} :via :fallback} (notes/probe-next plain-run [{:sig nil :dated? false}])) "no evidence"))

(deftest the-probe-without-a-valid-fallback-shows-the-deck-as-it-is
  (let [il (notes/interleaving [1 2 3 4 5 2 3])]
    (is (= {:read 2} (notes/probe-next il [])))
    (is (= {:note-pages nil :via :fallback} (notes/probe-next il [{:sig false :dated? false}])))
    (is (= {:note-pages nil :via :fallback}
           (notes/probe-next il [{:sig false :dated? true} {:sig false :dated? true}]))
        "every candidate failed")))

(deftest the-scan
  (let [sigs  {1 true, 2 false, 3 true, 4 true, 5 false}
        state (reduce (fn [st j] (notes/scan-step st j (get sigs j))) {:slide 0 :notes #{}} (range 1 6))]
    (is (= {:slide 5 :notes #{1 3 4}} state)))
  (is (= {:slide 1 :notes #{}} (notes/scan-step {:slide 0 :notes #{}} 1 nil)) "no evidence: a slide"))

(deftest the-confirmation-pairs-each-note-page-with-its-slide
  (is (= [[0 1] [2 3] [2 4] [7 8]] (notes/confirm-pairs 10 #{1 3 4 8})))
  (is (= [] (notes/confirm-pairs 5 #{})))
  (is (true? (notes/confirmed? true false)))
  (is (true? (notes/confirmed? false true)) "the date alone")
  (is (false? (notes/confirmed? nil false))))

(deftest which-producers-write-their-labels-in-the-same-run
  (is (notes/trusts-labels? "LuaTeX-1.18.0"))
  (is (notes/trusts-labels? "LuaHBTeX-1.24.0"))
  (is (notes/trusts-labels? "pdfTeX-1.40.26"))
  (is (not (notes/trusts-labels? "xdvipdfmx (20240305)")))
  (is (not (notes/trusts-labels? "dvipdfmx (20240305)")))
  (is (not (notes/trusts-labels? "GPL Ghostscript 10.03.1")) "dvips + ps2pdf")
  (is (not (notes/trusts-labels? nil))))

;; ---- pdfpc's XMP keys -----------------------------------------------------------------------------------------------

(def ^:private pdfpc-packet
  ;; as hyperxmp + pdfpc.sty write it: the pdfpc block nested as a second rdf:Description (not valid RDF/XML)
  (str "<?xpacket begin=\"﻿\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n"
       "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">\n"
       "  <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n"
       "    <rdf:Description rdf:about=\"\" xmlns:pdf=\"http://ns.adobe.com/pdf/1.3/\">\n"
       "      <pdf:Producer>LuaTeX-1.24.0</pdf:Producer>\n"
       "      <rdf:Description xmlns:pdfpc=\"https://github.com/pdfpc/pdfpc\">\n"
       "        <pdfpc:Duration>20</pdfpc:Duration>\n"
       "        <pdfpc:NotesPosition>none</pdfpc:NotesPosition>\n"
       "        <pdfpc:DisableMarkdown>false</pdfpc:DisableMarkdown>\n"
       "      </rdf:Description>\n"
       "    </rdf:Description>\n"
       "  </rdf:RDF>\n"
       "</x:xmpmeta>\n"
       "<?xpacket end=\"w\"?>"))

(deftest pdfpc-reads-its-keys-by-local-name
  (is (= {"Duration" "20" "NotesPosition" "none" "DisableMarkdown" "false"} (notes/pdfpc-xmp pdfpc-packet))))

(deftest the-last-key-wins-and-references-decode
  (is (= {"EndTime" "10:30" "StartTime" "<09:15> & é 😀"}
         (notes/pdfpc-xmp (str "<r><pdfpc:EndTime>9:00</pdfpc:EndTime><pdfpc:EndTime>10:30</pdfpc:EndTime>"
                               "<pdfpc:StartTime>&lt;09:15&gt; &amp; &#233; &#x1F600;</pdfpc:StartTime></r>")))))

(deftest a-markup-error-applies-no-key
  (is (= {} (notes/pdfpc-xmp "<r><pdfpc:Duration>20</pdfpc:Duration><pdfpc:EndTime>10:30</r>")) "unbalanced")
  (is (= {} (notes/pdfpc-xmp "<r><pdfpc:Duration>20&nbsp;</pdfpc:Duration></r>")) "an entity GMarkup does not know")
  (is (= {} (notes/pdfpc-xmp "<r><pdfpc:Duration>20 & 5</pdfpc:Duration></r>")) "a bare ampersand")
  (is (= {} (notes/pdfpc-xmp "<r><pdfpc:Duration>&#0;</pdfpc:Duration></r>")) "a character XML does not permit")
  (is (= {} (notes/pdfpc-xmp "<r><pdfpc:Duration>20</pdfpc:Duration>")) "unterminated document")
  (is (= {} (notes/pdfpc-xmp "<r><!-- no end <pdfpc:Duration>20</pdfpc:Duration></r>")) "unterminated comment")
  (is (= {} (notes/pdfpc-xmp "<r><pdfpc:Duration>20</pdfpc:Duration></r")) "unterminated tag"))

(deftest markup-that-is-not-a-key
  (is (= {} (notes/pdfpc-xmp "<rdf:Description pdfpc:Duration=\"20\"/>")) "attribute-form properties are ignored")
  (is (= {"Duration" "20"} (notes/pdfpc-xmp "<r a=\"x > y\"><!-- <pdfpc:Duration>9</pdfpc:Duration> -->
                                             <pdfpc:Duration><![CDATA[20]]></pdfpc:Duration></r>")))
  (is (= {"DisableMarkdown" ""} (notes/pdfpc-xmp "<r><pdfpc:DisableMarkdown/></r>")) "an empty element")
  (is (= {"NotesPosition" "xght"} (notes/pdfpc-xmp "<r><pdfpc:NotesPosition>ri<b>x</b>ght</pdfpc:NotesPosition></r>"))
      "the text since the last start tag, <b>")
  (is (= {} (notes/pdfpc-xmp "")))
  (is (nil? (notes/pdfpc-xmp nil))))

;; ---- pdfpc's settings -----------------------------------------------------------------------------------------------

(deftest pdfpc-parses-values-its-own-way
  (let [s (notes/pdfpc-settings {"Duration" " 20min" "EndUserSlide" "3" "NotesPosition" "RIGHT"
                                 "DisableMarkdown" "True" "LastMinutes" "x"} nil)]
    (is (= 20 (:duration-min s)) "atoi: leading space, trailing junk")
    (is (= 2 (:end-frame s)) "EndUserSlide is 1-based")
    (is (= :right (:notes-position s)) "any case")
    (is (true? (:markdown? s)) "only exactly \"true\" disables Markdown")
    (is (= 0 (:last-minutes s)) "atoi of no digits is 0"))
  (let [s (notes/pdfpc-settings {"Duration" "abc" "EndUserSlide" "0" "NotesPosition" " right"
                                 "DisableMarkdown" "true"} nil)]
    (is (nil? (:duration-min s)) "0 minutes: no countdown")
    (is (nil? (:end-frame s)) "slide 0 is before the first")
    (is (nil? (:notes-position s)) "no trimming")
    (is (false? (:markdown? s))))
  (is (= {:notes-position nil :duration-min nil :start-time nil :end-time nil :last-minutes 5 :end-frame nil
          :markdown? true}
         (notes/pdfpc-settings nil nil))
      "pdfpc's defaults"))

(deftest xmp-before-the-sidecar
  (let [sc (notes/parse-sidecar (js/JSON.stringify (clj->js {:pdfpcFormat 2 :duration 45 :endSlide 6 :lastMinutes 3
                                                             :startTime "09:00" :beamerNotePosition "LEFT"
                                                             :disableMarkdown true :pages []})))]
    (is (= {:notes-position :left :duration-min 45 :start-time "09:00" :end-time nil :last-minutes 3 :end-frame 6
            :markdown? false}
           (notes/pdfpc-settings {} sc)))
    (is (= {:notes-position nil :duration-min 20 :start-time "09:00" :end-time nil :last-minutes 3 :end-frame 6
            :markdown? true}
           (notes/pdfpc-settings {"Duration" "20" "NotesPosition" "none" "DisableMarkdown" "false"} sc))
        "the XMP's none and false win over the sidecar's left and true")))

(deftest positions-and-axes
  (is (= :horizontal (notes/split-axis :left)))
  (is (= :horizontal (notes/split-axis :right)))
  (is (= :vertical (notes/split-axis :top)))
  (is (= :vertical (notes/split-axis :bottom)))
  (is (nil? (notes/split-axis nil)))
  (is (= :left (notes/notes-position :left :right)) "an explicit preference first")
  (is (= :off (notes/notes-position :off :right)))
  (is (= :right (notes/notes-position :auto :right)) "then pdfpc's")
  (is (= :auto (notes/notes-position :auto nil)))
  (is (= :auto (notes/notes-position nil nil)))
  (is (= :bottom (notes/notes-position :sideways :bottom)) "an unknown preference is :auto"))

;; ---- the sidecar ----------------------------------------------------------------------------------------------------

(defn- json-sidecar [m] (notes/parse-sidecar (js/JSON.stringify (clj->js m))))

(deftest sidecar-formats
  (is (nil? (notes/parse-sidecar nil)))
  (is (= {:status :ignored :why :format :format 3} (json-sidecar {:pdfpcFormat 3 :pages []})))
  (is (= {:status :ignored :why :not-an-object} (notes/parse-sidecar "[1, 2]")))
  (is (= {:status :ignored :why :not-an-object} (notes/parse-sidecar "null")))
  (is (= {:status :ignored :why :not-an-object} (notes/parse-sidecar "5")))
  (is (= {:status :ignored :why :unrecognized} (notes/parse-sidecar "just some text")) "not JSON, and no [section]")
  (is (= :read (:status (json-sidecar {:pages []}))) "a missing pdfpcFormat is accepted")
  (is (= 0 (:format (json-sidecar {:pages []}))))
  (is (= :legacy (:format (notes/parse-sidecar "[duration]\n20\n")))))

(deftest json-notes-go-to-their-pages
  (let [labels ["1" "2" "2" "3"]
        sc     (json-sidecar {:pdfpcFormat 2
                              :pages [{:idx 0 :label "1" :overlay 0 :note "first"}
                                      {:idx 2 :label "2" :overlay 1 :note "second, overlay 2"}
                                      {:idx 3 :label "3" :overlay 0 :hidden true}
                                      {:idx 1 :label "" :overlay 0 :forcedOverlay true}
                                      {:idx 9 :label "" :overlay 0 :note "out of range"}
                                      {:idx 0 :label "" :overlay 0 :forcedOverlay true}]})]
    (is (= {:notes {0 "first" 2 "second, overlay 2"} :hidden #{3} :forced #{1}}
           (notes/sidecar-pages sc labels 4 nil))
        "an overlay is found in its run of labels; an index out of range is ignored; page 0 is never an overlay")))

(deftest json-notes-follow-inserted-pages
  ;; written for the deck 1 2 2 3; an overlay was then added to frame 1, so the deck is 1 1 2 2 3 (pdfpc's resolve)
  (let [sc (json-sidecar {:pages [{:idx 1 :label "2" :overlay 0 :note "frame 2"}
                                  {:idx 2 :label "2" :overlay 1 :note "frame 2, overlay 2"}
                                  {:idx 3 :label "3" :overlay 0 :note "frame 3"}]})]
    (is (= {1 "frame 2" 2 "frame 2, overlay 2" 3 "frame 3"} (:notes (notes/sidecar-pages sc ["1" "2" "2" "3"] 4 nil)))
        "the deck it was written for")
    (is (= {2 "frame 2" 3 "frame 2, overlay 2" 4 "frame 3"} (:notes (notes/sidecar-pages sc ["1" "1" "2" "2" "3"] 5 nil)))
        "every note follows its label and overlay")))

(deftest a-note-whose-label-is-gone-stays-at-its-index
  (let [sc (json-sidecar {:pages [{:idx 1 :label "7" :overlay 0 :note "no page is labelled 7"}
                                  {:idx 0 :label "1" :overlay 2 :note "label 1 has no third page"}]})]
    (is (= {1 "no page is labelled 7" 0 "label 1 has no third page"}
           (:notes (notes/sidecar-pages sc ["1" "2" "3"] 3 nil))))))

(deftest a-later-entry-unhides
  (let [sc (json-sidecar {:pages [{:idx 1 :label "" :hidden true} {:idx 1 :label "" :note "n"}]})]
    (is (= {:notes {1 "n"} :hidden #{} :forced #{}} (notes/sidecar-pages sc nil 3 nil)))))

(deftest without-labels-pdfpc-sees-page-numbers
  (let [sc (json-sidecar {:pages [{:idx 0 :label "2" :overlay 0 :note "was page 2"}]})]
    (is (= {1 "was page 2"} (:notes (notes/sidecar-pages sc nil 3 nil))))))

(deftest an-empty-note-is-no-note
  (let [sc (json-sidecar {:pages [{:idx 0 :label "" :note ""}]})]
    (is (= {} (:notes (notes/sidecar-pages sc nil 1 nil))))))

(deftest a-sidecar-that-was-not-read-does-nothing
  (is (= {:notes {} :hidden #{} :forced #{}} (notes/sidecar-pages {:status :ignored :why :format} nil 3 nil))))

(def ^:private legacy-text
  (str "[file]\ntalk.pdf\n[duration]\n20\n[end_user_slide]\n3\n[notes]\n"
       "### 1\nOpen with a \\[question\\].\n### 3\nUse \\#hashtags here.\n### x\nno user slide\n"))

(deftest legacy-notes-go-to-every-page-of-their-user-slide
  (let [sc (notes/parse-sidecar legacy-text)]
    (is (= {:duration 20 :start-time nil :end-time nil :last-minutes nil :end-slide 3 :notes-position nil
            :legacy-notes? true}
           (:settings sc)))
    (is (= {:notes  {0 "Open with a [question]." 2 "Use #hashtags here." 3 "Use #hashtags here."
                     4 "Use #hashtags here."}
            :hidden #{} :forced #{}}
           (notes/sidecar-pages sc ["1" "2" "3" "3" "3"] 5 nil))
        "user slides are runs of equal labels, 1 | 2 | 3 3 3, so user slide 3 is pages 2 to 4; ### x names none")))

(deftest legacy-skip-turns-grouping-off
  (let [sc (notes/parse-sidecar (str legacy-text "[skip]\n2,4,5"))]
    (is (= #{1 3} (:skip sc)) "the field after the last comma is ignored")
    (is (= {:notes {0 "Open with a [question]." 2 "Use #hashtags here."} :hidden #{} :forced #{1 3}}
           (notes/sidecar-pages sc ["1" "2" "3" "3" "3"] 5 nil))
        "with [skip], user slide n is page n − 1")))

(deftest a-notes-position-turns-grouping-off
  (let [sc (notes/parse-sidecar legacy-text)]
    (is (= {0 "Open with a [question]." 2 "Use #hashtags here."}
           (:notes (notes/sidecar-pages sc ["1" "2" "3" "3" "3"] 5 :right))))))

(deftest legacy-notes-force-plain-text
  (is (false? (:markdown? (notes/pdfpc-settings nil (notes/parse-sidecar legacy-text)))))
  (is (true? (:markdown? (notes/pdfpc-settings nil (notes/parse-sidecar "[duration]\n20\n"))))))

;; ---- text notes -----------------------------------------------------------------------------------------------------

(deftest pdfdoc-bytes-inverts-pdfjs-decoding
  (is (= [0x63 0x61 0x66 0xc3 0xa9] (vec (array-seq (notes/pdfdoc-bytes "cafÃ©")))))
  (is (= [0xe2 0x80 0xa2] (vec (array-seq (notes/pdfdoc-bytes "â•¢")))) "0x80 decodes to •")
  (is (= [0xc3 0xa0] (vec (array-seq (notes/pdfdoc-bytes "Ã€")))) "0xA0 decodes to €")
  (is (= [0xc4 0x9f] (vec (array-seq (notes/pdfdoc-bytes "Ä\u009f")))) "0x9F decodes to itself")
  (is (= [0x18] (vec (array-seq (notes/pdfdoc-bytes "˘")))) "0x18 decodes to ˘")
  (is (nil? (notes/pdfdoc-bytes "˙")) "the table's 0x1B entry is never used: 0x1B starts an escape")
  (is (nil? (notes/pdfdoc-bytes "\u0080")) "0x80 never decodes to U+0080")
  (is (nil? (notes/pdfdoc-bytes " ")) "nor 0xA0 to U+00A0")
  (is (nil? (notes/pdfdoc-bytes "\u001b")))
  (is (nil? (notes/pdfdoc-bytes "☃")) "a character no byte decodes to"))

(deftest repair-only-pdftex-mojibake
  (is (= "café" (notes/repair "cafÃ©" "pdfTeX-1.40.26")))
  (is (= "• €, à, ğ" (notes/repair "â•¢ â‡¬, Ã€, Ä\u009f" "pdfTeX-1.40.26")))
  (is (= "cafÃ©" (notes/repair "cafÃ©" "LuaTeX-1.18.0")) "no other engine writes UTF-8 into a literal string")
  (is (= "cafÃ©" (notes/repair "cafÃ©" nil)))
  (is (= "café" (notes/repair "café" "pdfTeX-1.40.26")) "Latin-1 text decoded right: E9 alone is not UTF-8")
  (is (= "plain ASCII" (notes/repair "plain ASCII" "pdfTeX-1.40.26")))
  (is (= "snow ☃" (notes/repair "snow ☃" "pdfTeX-1.40.26")) "already Unicode")
  (is (nil? (notes/repair nil "pdfTeX-1.40.26"))))

(deftest annotation-notes-are-joined-in-annots-order
  (let [annots [{:subtype "Link" :contents "not a note"}
                {:subtype "Text" :contents "first" :dir "ltr"}
                {:subtype "Popup" :contents "nor this"}
                {:subtype "Highlight" :contents "  " :dir "ltr"}
                {:subtype "FreeText" :contents "second" :dir "ltr"}
                {:subtype "Squiggly" :contents "third" :dir "ltr"}]]
    (is (= {:text "first\nsecond\nthird" :dir "ltr"} (notes/annotation-text annots "LuaTeX-1.18.0"))))
  (is (= {:text "שלום\nhello" :dir "auto"}
         (notes/annotation-text [{:subtype "Text" :contents "שלום" :dir "rtl"}
                                 {:subtype "Underline" :contents "hello" :dir "ltr"}] nil))
      "mixed directions")
  (is (= {:text "café" :dir "ltr"} (notes/annotation-text [{:subtype "Text" :contents "cafÃ©" :dir "ltr"}] "pdfTeX")))
  (is (nil? (notes/annotation-text [{:subtype "Link" :contents "x"}] nil)))
  (is (nil? (notes/annotation-text [] nil))))

(deftest both-notes-are-shown-once
  (is (= [{:text "a" :dir "ltr" :from :annotation} {:text "b" :dir "auto" :from :sidecar}]
         (notes/page-notes {:text "a" :dir "ltr"} "b")))
  (is (= [{:text "same" :dir "ltr" :from :annotation}] (notes/page-notes {:text "same" :dir "ltr"} " same\n")))
  (is (= [{:text "b" :dir "auto" :from :sidecar}] (notes/page-notes nil "b")))
  (is (= [] (notes/page-notes nil "  ")))
  (is (= [] (notes/page-notes nil nil))))

;; ---- the model ------------------------------------------------------------------------------------------------------

(defn- evidence
  "Beamer evidence for a deck of `labels` (nil: none) and `n` pages."
  [producer labels n]
  {:creator "LaTeX with Beamer class" :producer producer :creator-tool nil :page-mode nil
   :labels labels :sizes (vec (repeat n paper)) :pdfpc nil})

(def ^:private shownotes-labels ["1" "2" "1" "4" "5" "2" "3" "3" "9" "4"])

(deftest a-proven-parse-from-pdftex-or-luatex-hides-pages-at-once
  (let [m (notes/initial (evidence "LuaTeX-1.18.0" shownotes-labels 10) nil)]
    (is (= :interleaved (:style m)))
    (is (= #{1 3 4 8} (:note-pages m)))
    (is (= [:labels] (:proof m)))
    (is (nil? (:pending m)))))

(deftest labels-that-lag-a-pass-are-confirmed-first
  (let [m (notes/initial (evidence "xdvipdfmx (20240305)" shownotes-labels 10) nil)]
    (is (= :none (:style m)) "nothing is hidden before the confirmation")
    (is (= :confirm (:pending m)))
    (is (= #{1 3 4 8} (get-in m [:interleaving :note-pages])))))

(deftest a-restarted-counter-is-confirmed-even-from-luatex
  (let [m (notes/initial (evidence "LuaTeX-1.18.0" ["1" "2" "3" "4" "5" "1" "2"] 7) nil)]
    (is (= :none (:style m)))
    (is (= :confirm (:pending m)))))

(deftest an-ambiguous-deck-waits-for-the-probe
  (let [m (notes/initial (evidence "LuaTeX-1.18.0" ["1" "2" "3" "4" "5"] 5) nil)]
    (is (= [:none :probe false] [(:style m) (:pending m) (:confirm? m)])))
  (let [m (notes/initial (evidence "xdvipdfmx (20240305)" ["1" "2" "3" "4" "5"] 5) nil)]
    (is (= [:none :probe true] [(:style m) (:pending m) (:confirm? m)]) "and then for the confirmation")))

(deftest a-deck-without-labels-is-scanned
  (is (= :scan (:pending (notes/initial (evidence "xdvipdfmx (20240305)" nil 10) nil)))))

(deftest decks-that-are-shown-as-they-are
  (is (= :not-beamer (:reason (notes/initial (assoc (evidence "Skia/PDF" shownotes-labels 10) :creator "Impress") nil))))
  (is (= :one-page (:reason (notes/initial (evidence "LuaTeX-1.18.0" ["1"] 1) nil))))
  (is (= :invalid (:reason (notes/initial (evidence "LuaTeX-1.18.0" ["1" "1" "3" "3"] 4) nil))))
  (is (= :undecidable (:reason (notes/initial (evidence "LuaTeX-1.18.0" ["2" "2" "3" "4"] 4) nil))))
  (is (= :labels-not-integers (:reason (notes/initial (evidence "LuaTeX-1.18.0" ["i" "ii"] 2) nil))))
  (is (= :no-note-pages (:reason (notes/initial (evidence "LuaTeX-1.18.0" ["1" "1" "2"] 3) nil)))))

(defn- alternating-labels
  "The labels of an n-page deck whose first frame has two overlays and whose every later slide is followed by one note
   page: 1 1, then a note page (labelled with its page number, i + 1) and the next frame's slide, in turn."
  [n]
  (mapv (fn [i] (str (cond (< i 2) 1, (even? i) (inc i), :else (quot (inc i) 2)))) (range n)))

(deftest work-that-would-read-too-many-pages-is-refused
  (let [cap notes/max-content-pages
        xdv "xdvipdfmx (20240305)"]
    (is (= [:scan nil] ((juxt :pending :reason) (notes/initial (evidence xdv nil cap) nil))) "a scan of exactly the cap")
    (is (= [nil :too-many-pages] ((juxt :pending :reason) (notes/initial (evidence xdv nil (inc cap)) nil)))
        "one page more is refused before a page is read")
    (let [L  (alternating-labels (inc cap))
          il (notes/interleaving (notes/integer-labels L))]
      (is (= :proven (:status il)))
      (is (= cap (notes/confirm-page-count (inc cap) (:note-pages il))) "every page but page 0: each note page and its slide")
      (is (= [:confirm nil] ((juxt :pending :reason) (notes/initial (evidence xdv L (inc cap)) nil)))))
    (let [L  (alternating-labels (+ cap 3))
          il (notes/interleaving (notes/integer-labels L))]
      (is (= (+ cap 2) (notes/confirm-page-count (+ cap 3) (:note-pages il))))
      (is (= [nil :too-many-pages] ((juxt :pending :reason) (notes/initial (evidence xdv L (+ cap 3)) nil))))
      (is (= :interleaved (:style (notes/initial (evidence "LuaTeX-1.18.0" L (+ cap 3)) nil)))
          "labels the run wrote need no page read at all"))))

(deftest split-notes
  (let [doubled (assoc (evidence "LuaTeX-1.18.0" nil 4) :sizes (vec (repeat 4 [725.67 272.126])))]
    (is (= :split (:style (notes/initial doubled nil))) "Beamer's second screen: geometry"))
  (let [ev (assoc (evidence "LuaTeX-1.18.0" shownotes-labels 10) :pdfpc {"NotesPosition" "right"})
        m  (notes/initial ev nil)]
    (is (= :split (:style m)) "pdfpc's notes position on a deck whose pages are not doubled")
    (is (= :horizontal (:split-axis (notes/deck-evidence m :auto))))
    (is (nil? (:split-axis (notes/deck-evidence m :off))) ":off splits nothing")))

(deftest the-model-carries-pdfpc
  (let [sc (notes/parse-sidecar (js/JSON.stringify (clj->js {:pdfpcFormat 2
                                                             :pages [{:idx 2 :label "1" :overlay 1 :note "n"}
                                                                     {:idx 5 :label "2" :overlay 0 :hidden true}]})))
        m  (notes/initial (evidence "LuaTeX-1.18.0" shownotes-labels 10) sc)]
    (is (true? (:pdfpc? m)))
    (is (true? (:markdown? m)))
    (is (= {:status :read :format 2 :notes {2 "n"} :hidden #{5} :forced #{}} (:sidecar m))))
  (let [m (notes/initial (evidence "LuaTeX-1.18.0" shownotes-labels 10) nil)]
    (is (false? (:pdfpc? m)))
    (is (false? (:markdown? m)) "without pdfpc metadata a note is plain text")
    (is (nil? (:sidecar m))))
  (let [m (notes/initial (assoc (evidence "LuaTeX-1.18.0" shownotes-labels 10) :pdfpc {"DisableMarkdown" "true"}) nil)]
    (is (true? (:pdfpc? m)))
    (is (false? (:markdown? m))))
  (is (= {:status :ignored :why :format :format 3}
         (:sidecar (notes/initial (evidence "LuaTeX-1.18.0" shownotes-labels 10) {:status :ignored :why :format :format 3})))))

(deftest decided-models
  (let [m (notes/initial (evidence "xdvipdfmx (20240305)" shownotes-labels 10) nil)]
    (is (= {:style :interleaved :note-pages #{1 3 4 8} :proof [:labels :confirm] :pending nil :reason nil}
           (select-keys (notes/decided m #{1 3 4 8} [:labels :confirm] nil) [:style :note-pages :proof :pending :reason])))
    (is (= {:style :none :note-pages #{} :proof nil :pending nil :reason :confirm-failed}
           (select-keys (notes/decided m nil nil :confirm-failed) [:style :note-pages :proof :pending :reason])))))

(deftest the-deck-evidence-honours-the-preference
  (let [m (notes/initial (evidence "LuaTeX-1.18.0" shownotes-labels 10) nil)]
    (is (= #{1 3 4 8} (:hidden (notes/deck-evidence m :auto))))
    (is (nil? (:hidden (notes/deck-evidence m :off))) ":off shows every page as it is")
    (let [d (deck/deck (merge (evidence "LuaTeX-1.18.0" shownotes-labels 10) (notes/deck-evidence m :auto)))]
      (is (= [0 2 5 6 7 9] (:shown d))))))

(deftest where-the-presenter-finds-the-notes
  (let [m (notes/initial (evidence "LuaTeX-1.18.0" shownotes-labels 10) nil)
        sizes (vec (repeat 10 paper))
        d (deck/deck (merge (evidence "LuaTeX-1.18.0" shownotes-labels 10) (notes/deck-evidence m :auto)))]
    (is (= [{:kind :region :page 3 :rect {:x 0 :y 0 :w 362.835 :h 272.126}}
            {:kind :region :page 4 :rect {:x 0 :y 0 :w 362.835 :h 272.126}}
            {:kind :text :page 2}]
           (notes/sources-for m d sizes 2 :auto))
        "an overlay with an in-frame note and a between-frames note")
    (is (= (notes/sources-for m d sizes 2 :auto) (notes/sources-for m d sizes 3 :auto)) "a note page snaps to its slide")
    (is (= [{:kind :region :page 1 :rect {:x 0 :y 0 :w 362.835 :h 272.126}} {:kind :text :page 0}]
           (notes/sources-for m d sizes 0 :auto)))
    (is (= [{:kind :text :page 5}] (notes/sources-for m d sizes 5 :auto)) "a slide without notes"))
  (let [doubled (assoc (evidence "LuaTeX-1.18.0" nil 2) :sizes [[725.67 272.126] [725.67 272.126]])
        m (notes/initial doubled nil)
        d (deck/deck doubled)]
    (is (= [{:kind :region :page 1 :rect {:x 362.835 :y 0 :w 362.835 :h 272.126}} {:kind :text :page 1}]
           (notes/sources-for m d (:sizes doubled) 1 :auto))
        "notes on the right by default")
    (is (= [{:kind :region :page 1 :rect {:x 0 :y 0 :w 362.835 :h 272.126}} {:kind :text :page 1}]
           (notes/sources-for m d (:sizes doubled) 1 :left)))
    (is (= [{:kind :text :page 1}] (notes/sources-for m d (:sizes doubled) 1 :off)))))

(deftest the-summary
  (let [m (notes/initial (evidence "LuaTeX-1.18.0" shownotes-labels 10) nil)]
    (is (= {:style :interleaved :note-pages 4 :proof [:labels] :pending nil :reason nil :pdfpc? false
            :markdown? false :sidecar nil :has-notes? true}
           (notes/summary m))))
  (is (false? (:has-notes? (notes/summary (notes/initial (evidence "LuaTeX-1.18.0" ["1" "1" "2"] 3) nil))))))
