(ns vinary.slides.evidence-test
  "Evidence gathering from a pdf.js document proxy — exercised with FAKE proxies, so the node test build never
   loads pdf.js: every call may resolve, reject, or throw synchronously, and gather must still resolve. The notes
   readers and `settle` (ADR-0048 §2.3–§2.5) run over fake pages whose text runs are plain data; the real decks in
   test/fixtures are checked end to end by test/cli-smoke.js."
  (:require [cljs.test :refer-macros [deftest is async]]
            [vinary.slides.evidence :as evidence]
            [vinary.slides.notes :as notes]))

(defn- fake-metadata [m] #js {:get (fn [k] (get m k))})

(deftest gathers-every-signal
  (async done
    (let [doc #js {:getMetadata   (fn [] (js/Promise.resolve
                                          #js {:info #js {:Creator "LaTeX with Beamer class" :Producer "LuaTeX-1.18.0"}
                                               :metadata (fake-metadata {"xmp:creatortool" "LaTeX with Beamer class"})}))
                   :getPageMode   (fn [] (js/Promise.resolve "FullScreen"))
                   :getPageLabels (fn [] (js/Promise.resolve #js ["1" "2" "2"]))}]
      (-> (evidence/gather doc)
          (.then (fn [ev]
                   (is (= {:creator "LaTeX with Beamer class" :producer "LuaTeX-1.18.0"
                           :creator-tool "LaTeX with Beamer class" :page-mode "FullScreen" :labels ["1" "2" "2"]
                           :pdfpc nil}
                          ev))
                   (done)))))))

(deftest survives-rejections-and-sync-throws
  (async done
    (let [doc #js {:getMetadata   (fn [] (js/Promise.reject (js/Error. "corrupt Info")))
                   :getPageMode   (fn [] (throw (js/Error. "destroyed")))
                   :getPageLabels (fn [] (js/Promise.resolve nil))}]
      (-> (evidence/gather doc)
          (.then (fn [ev]
                   (is (= {:creator nil :producer nil :creator-tool nil :page-mode nil :labels nil :pdfpc nil} ev))
                   (done)))))))

(deftest tolerates-partial-metadata
  (async done
    (let [doc #js {:getMetadata   (fn [] (js/Promise.resolve #js {:info #js {:Producer "pdfTeX-1.40.26"} :metadata nil}))
                   :getPageMode   (fn [] (js/Promise.resolve "UseOutlines"))
                   :getPageLabels (fn [] (js/Promise.resolve #js []))}]
      (-> (evidence/gather doc)
          (.then (fn [ev]
                   (is (= nil (:creator ev)))
                   (is (= "pdfTeX-1.40.26" (:producer ev)))
                   (is (= nil (:creator-tool ev)))
                   (is (= nil (:labels ev)) "an empty label array means no labels")
                   (done)))))))

(deftest xmp-property-name-is-looked-up-case-robustly
  (async done
    (let [doc #js {:getMetadata   (fn [] (js/Promise.resolve
                                          #js {:info #js {} :metadata (fake-metadata {"xmp:CreatorTool" "LaTeX with Beamer class"})}))
                   :getPageMode   (fn [] (js/Promise.resolve "UseNone"))
                   :getPageLabels (fn [] (js/Promise.resolve nil))}]
      (-> (evidence/gather doc)
          (.then (fn [ev] (is (= "LaTeX with Beamer class" (:creator-tool ev))) (done)))))))

(deftest gathers-pdfpcs-xmp-keys
  (async done
    (let [packet (str "<?xpacket begin=\"\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?><x:xmpmeta xmlns:x=\"adobe:ns:meta/\">"
                      "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"
                      "<rdf:Description rdf:about=\"\" xmlns:pdfpc=\"https://pdfpc.github.io/\">"
                      "<pdfpc:Duration>20</pdfpc:Duration><pdfpc:NotesPosition>right</pdfpc:NotesPosition>"
                      "</rdf:Description></rdf:RDF></x:xmpmeta><?xpacket end=\"w\"?>")
          doc-of (fn [metadata]
                   #js {:getMetadata   (fn [] (js/Promise.resolve #js {:info #js {} :metadata metadata}))
                        :getPageMode   (fn [] (js/Promise.resolve "UseNone"))
                        :getPageLabels (fn [] (js/Promise.resolve nil))})]
      (-> (evidence/gather (doc-of #js {:get (fn [_] nil) :getRaw (fn [] packet)}))
          (.then (fn [ev]
                   (is (= {"Duration" "20" "NotesPosition" "right"} (:pdfpc ev)))
                   (evidence/gather (doc-of #js {:get (fn [_] nil) :getRaw (fn [] (throw (js/Error. "gone")))}))))
          (.then (fn [ev]
                   (is (nil? (:pdfpc ev)) "a packet that cannot be read is no packet")
                   (is (= "UseNone" (:page-mode ev)) "and the rest is still read")
                   (done)))))))

;; ---- the notes readers and settle (ADR-0048 §2.3–§2.5) -----------------------------------------------------------

(defn- run [s x y h] {:str s :t [h 0 0 h x y]})
(defn- turned [s x y h] {:str s :t [0 h (- h) 0 x y]})

(defn- thumbnail [slide sigma tx ty]
  (mapv (fn [{s :str [a b c d e f] :t}] {:str s :t [(* sigma a) (* sigma b) (* sigma c) (* sigma d)
                                                     (+ tx (* sigma e)) (+ ty (* sigma f))]})
        slide))

(def ^:private paper [362.835 272.126])

(defn- slide [title & lines]
  (into [(run title 28 230 14.3)] (map-indexed (fn [k l] (run l 34 (- 180 (* 20 k)) 10.9))) lines))

(defn- note-of
  "A default-template note page: the slide's thumbnail at σ = 0.25, the rotated date, the notes."
  [s]
  (into (thumbnail s 0.25 268.8 204.1) [(turned "2026-10-17" 20 120 5.5) (run "Say this." 10 150 10.9)]))

(def ^:private dated-notes-only
  ;; a default-template note page whose thumbnail is of no slide in the deck
  [(turned "2026-10-17" 20 120 5.5) (run "Notes" 10 150 10.9)])

(def ^:private A  (slide "Introduction" "First point of the talk" "Second point, with more words"))
(def ^:private A2 (slide "Introduction" "First point of the talk" "Second point, with more words" "And a third"))
(def ^:private B  (slide "Middle" "Another slide entirely"))
(def ^:private C  (slide "Results" "What was measured" "What it means"))
(def ^:private C2 (slide "Results" "What was measured" "What it means" "What comes next"))
(def ^:private D  (slide "Conclusion" "Thank you"))

(def ^:private shownotes-labels ["1" "2" "1" "4" "5" "2" "3" "3" "9" "4"])
(def ^:private shownotes-pages [A (note-of A) A2 (note-of A2) (note-of A2) B C C2 (note-of C2) D])

(defn- fake-doc
  "A fake PDFDocumentProxy: page p (0-based) has the text runs (nth pages p), or text that cannot be read where that
   is :unreadable, and the annotations (get annots p). `reads` (an atom) counts getTextContent calls; `intents` (an
   atom) records the intent each getAnnotations call asks for."
  [pages & {:keys [annots reads intents]}]
  #js {:numPages (count pages)
       :getPage  (fn [pnum]
                   (let [p (dec pnum)]
                     (js/Promise.resolve
                      #js {:getTextContent (fn []
                                             (when reads (swap! reads inc))
                                             (let [runs (nth pages p)]
                                               (if (= :unreadable runs)
                                                 (js/Promise.reject (js/Error. "unreadable"))
                                                 (js/Promise.resolve
                                                  #js {:items (into-array
                                                               (map (fn [{s :str t :t}] #js {:str s :transform (into-array t)})
                                                                    runs))}))))
                           :getAnnotations (fn [^js opts]
                                             (when intents (swap! intents conj (some-> opts .-intent)))
                                             (js/Promise.resolve (clj->js (get annots p []))))})))})

(defn- evidence-of [producer labels n]
  {:creator "LaTeX with Beamer class" :producer producer :creator-tool nil :page-mode nil
   :labels labels :sizes (vec (repeat n paper)) :pdfpc nil})

(defn- settled
  "Promise<model> of settling `pages` labelled `labels` from `producer`."
  [pages labels producer & {:keys [reads alive?]}]
  (let [ev (evidence-of producer labels (count pages))]
    (evidence/settle (fake-doc pages :reads reads) (notes/initial ev nil) (:sizes ev) (or alive? (constantly true)))))

(defn- outcome
  "A settled model's decision, every key present (a model decided from its labels alone has no :pending key at all)."
  [m]
  (into {} (map (fn [k] [k (get m k)])) [:style :note-pages :proof :pending :reason]))

(deftest page-runs-are-plain-data
  (async done
    (let [doc (fake-doc [[(run "Introduction" 28 230 14.3)] :unreadable])]
      (-> (evidence/page-runs doc 0)
          (.then (fn [runs]
                   (is (= [{:str "Introduction" :t [14.3 0 0 14.3 28 230]}] runs))
                   (evidence/page-runs doc 1)))
          (.then (fn [runs]
                   (is (nil? runs) "a page whose text cannot be read has no runs")
                   (evidence/page-runs #js {:getPage (fn [_] (throw (js/Error. "destroyed")))} 0)))
          (.then (fn [runs] (is (nil? runs) "nor does a page that cannot be had") (done)))))))

(deftest annotations-are-read-hidden-ones-included
  (async done
    (let [intents (atom [])
          doc     (fake-doc [[]] :intents intents
                            :annots {0 [{:subtype "Text" :contentsObj {:str "First." :dir "ltr"}}
                                        {:subtype "Link"}
                                        {:subtype "FreeText" :contentsObj {:str "Second." :dir "ltr"}}]})]
      (-> (evidence/page-annotations doc 0)
          (.then (fn [annots]
                   (is (= [{:subtype "Text" :contents "First." :dir "ltr"}
                           {:subtype "Link" :contents nil :dir nil}
                           {:subtype "FreeText" :contents "Second." :dir "ltr"}]
                          annots))
                   (is (= ["any"] @intents) "pdfpc.sty's notes are Hidden + NoView: intent \"display\" drops them")
                   (evidence/text-notes doc 0 "LuaTeX-1.18.0")))
          (.then (fn [note]
                   (is (= {:text "First.\nSecond." :dir "ltr"} note))
                   (done)))))))

(deftest nothing-pending-settles-at-once
  (async done
    (let [reads (atom 0)]
      (-> (settled shownotes-pages shownotes-labels "LuaTeX-1.18.0" :reads reads)
          (.then (fn [m]
                   (is (= {:style :interleaved :note-pages #{1 3 4 8} :proof [:labels] :pending nil :reason nil}
                          (outcome m)))
                   (is (= 0 @reads) "labels the run wrote are read from the labels alone")
                   (done)))))))

(deftest labels-that-lag-a-pass-are-confirmed-by-their-pages
  (async done
    (-> (settled shownotes-pages shownotes-labels "xdvipdfmx (20240305)")
        (.then (fn [m]
                 (is (= {:style :interleaved :note-pages #{1 3 4 8} :proof [:labels :confirm] :pending nil :reason nil}
                        (outcome m)))
                 ;; the same labels over an edited deck: page 3 is a slide now, and the stale parse calls it a note page
                 (settled (assoc shownotes-pages 3 B) shownotes-labels "xdvipdfmx (20240305)")))
        (.then (fn [m]
                 (is (= {:style :none :note-pages #{} :proof nil :pending nil :reason :confirm-failed} (outcome m)))
                 (done))))))

(deftest the-probe-finds-the-first-note-page
  (async done
    (let [reads  (atom 0)
          pages  [A B C (note-of C) (note-of C)]
          labels ["1" "2" "3" "4" "5"]]
      (-> (settled pages labels "LuaTeX-1.18.0" :reads reads)
          (.then (fn [m]
                   (is (= {:style :interleaved :note-pages #{3 4} :proof [:labels :probe] :pending nil :reason nil}
                          (outcome m)))
                   (is (= 3 @reads) "pages 4 and 3 (two note pages of one slide), then 2 (the slide)")
                   (settled pages labels "xdvipdfmx (20240305)")))
          (.then (fn [m]
                   (is (= {:style :interleaved :note-pages #{3 4} :proof [:labels :probe :confirm] :pending nil
                           :reason nil}
                          (outcome m))
                       "a probe's choice from labels that lag a pass is confirmed too")
                   (done)))))))

(deftest a-deck-without-notes-costs-two-pages
  (async done
    (let [reads (atom 0)]
      (-> (settled [A B C D (slide "Questions")] ["1" "2" "3" "4" "5"] "LuaTeX-1.18.0" :reads reads)
          (.then (fn [m]
                   (is (= {:style :none :note-pages #{} :proof nil :pending nil :reason :no-note-pages} (outcome m)))
                   (is (= 2 @reads))
                   (done)))))))

(deftest a-deck-without-labels-is-scanned-once
  (async done
    (let [reads (atom 0)]
      (-> (settled [A (note-of A) B C (note-of C) (note-of C)] nil "xdvipdfmx (20240305)" :reads reads)
          (.then (fn [m]
                   (is (= {:style :interleaved :note-pages #{1 4 5} :proof [:scan] :pending nil :reason nil} (outcome m)))
                   (is (= 6 @reads) "every page once")
                   (settled [A B C] nil "xdvipdfmx (20240305)")))
          (.then (fn [m]
                   (is (= :no-note-pages (:reason m)))
                   (settled [A (note-of A) :unreadable C] nil "xdvipdfmx (20240305)")))
          (.then (fn [m]
                   (is (= {:style :none :note-pages #{} :proof nil :pending nil :reason :unreadable} (outcome m))
                       "a page whose text cannot be read leaves the deck as it is")
                   (done)))))))

(deftest the-background-reads-at-most-max-content-pages
  (async done
    (let [cap     notes/max-content-pages
          walk    (fn [n] (into [A] (repeat (dec n) dated-notes-only)))  ; every pair fails, every page is dated
          labels  (fn [n] (mapv #(str (inc %)) (range n)))
          reads   (atom 0)]
      (-> (settled (walk 6) (labels 6) "LuaTeX-1.18.0")
          (.then (fn [m]
                   (is (= :no-note-pages (:reason m)) "a short walk reaches its end: the parse without notes")
                   (settled (walk (+ cap 5)) (labels (+ cap 5)) "LuaTeX-1.18.0" :reads reads)))
          (.then (fn [m]
                   (is (= {:style :none :note-pages #{} :proof nil :pending nil :reason :too-many-pages} (outcome m)))
                   (is (= cap @reads) "the walk stops at the budget, not after it")
                   (done)))))))

(deftest settling-stops-when-the-document-goes
  (async done
    (let [reads (atom 0)
          open? #(< @reads 2)]                                          ; closed while its second page is read
      (-> (settled [A (note-of A) B C] nil "xdvipdfmx (20240305)" :reads reads :alive? open?)
          (.then (fn [m]
                   (is (nil? m) "nothing to apply")
                   (is (= 2 @reads) "no page is read after the document is closed")
                   (reset! reads 0)
                   (settled [A (note-of A)] nil "xdvipdfmx (20240305)" :reads reads :alive? open?)))
          (.then (fn [m]
                   (is (= 2 @reads) "every page was read")
                   (is (nil? m) "but the document closed before the decision was applied")
                   (done)))))))

(deftest detect-is-the-whole-decision
  (async done
    (let [ev (evidence-of "xdvipdfmx (20240305)" shownotes-labels 10)]
      (-> (evidence/detect (fake-doc shownotes-pages) ev nil)
          (.then (fn [m]
                   (is (= {:style :interleaved :note-pages #{1 3 4 8} :proof [:labels :confirm] :pending nil :reason nil}
                          (outcome m)))
                   (done)))))))
