(ns vinary.search.match-test
  "One test per mode, plus the scoring properties and the config defaults that keep this a
   de-duplication rather than a behaviour change."
  (:require [cljs.test :refer-macros [deftest testing is]]
            [vinary.search.config :as config]
            [vinary.search.match :as m]))

(defn- spans [r] (:spans r))
(defn- hit? [s q opts] (some? (m/match s q opts)))

(deftest substring-mode
  (let [opts {:mode :substring}]
    (is (true?  (hit? "views.cljs" "iew" opts)))
    (is (false? (hit? "views.cljs" "vwc" opts)))
    (is (= [[1 4]] (spans (m/match "views.cljs" "iew" opts))))
    (testing "every occurrence is reported, non-overlapping"
      (is (= [[0 1] [2 3]] (spans (m/match "aXa" "a" opts)))))
    (testing "folding is applied to both sides"
      (is (true? (hit? "VIEWS.CLJS" "iew" opts))))))

(deftest subsequence-mode
  (let [opts {:mode :subsequence}]
    (is (true?  (hit? "views.cljs" "vwc" opts)))
    (is (true?  (hit? "Open in new Tab" "ot" opts)))
    (is (false? (hit? "abc" "cba" opts)))
    (testing "adjacent hits merge into one span, so highlighting reads as a run"
      (is (= [[0 3]] (spans (m/match "abcdef" "abc" opts))))
      (is (= [[0 1] [2 3] [4 5]] (spans (m/match "aXbXc" "abc" opts)))))))

(deftest prefix-mode
  (let [opts {:mode :prefix}]
    (is (true?  (hit? "views.cljs" "vie" opts)))
    (is (false? (hit? "views.cljs" "iew" opts)))
    (is (= [[0 3]] (spans (m/match "views.cljs" "vie" opts))))))

(deftest word-prefix-mode
  (let [opts {:mode :word-prefix}]
    (testing "a word start is index 0, a position after a separator, or a lower→upper transition"
      (is (true?  (hit? "src/vinary/views.cljs" "vie" opts)))
      (is (true?  (hit? "some_file_name" "nam" opts)))
      (is (true?  (hit? "findScanBuffer" "sca" opts)))       ; camelCase boundary
      (is (false? (hit? "abcdef" "cde" opts))))               ; mid-word: not a word prefix
    (is (= [[11 14]] (spans (m/match "src/vinary/views.cljs" "vie" opts))))))

(deftest regex-mode
  (let [opts {:mode :regex}]
    (is (true?  (hit? "views.cljs" "v.*s" opts)))
    (is (true?  (hit? "abc123" "[0-9]+" opts)))
    (is (false? (hit? "abc" "[0-9]+" opts)))
    (is (= [[3 6]] (spans (m/match "abc123" "[0-9]+" opts))))
    (testing "an invalid pattern matches nothing instead of throwing — the user is mid-typing"
      (is (false? (hit? "anything" "(" opts)))
      (is (false? (hit? "anything" "[" opts)))
      (is (false? (hit? "anything" "a{2,1}" opts))))
    (testing "a zero-width pattern terminates"
      ;; if the lastIndex nudge were missing this would hang rather than fail
      (is (nil? (m/match "abc" "x*" opts))))))

(deftest blank-query-matches-everything
  (testing "every mode treats a blank query as 'no filter'"
    (doseq [mode [:substring :subsequence :prefix :word-prefix :regex]]
      (is (= {:score 1000 :spans []} (m/match "anything" "" {:mode mode}))
          (str "mode " mode)))))

(deftest unknown-mode-falls-back-to-substring
  (testing "a stale settings value cannot produce a matcher that silently matches nothing"
    (is (true? (hit? "views.cljs" "iew" {:mode :no-such-mode})))
    (is (false? (hit? "views.cljs" "vwc" {:mode :no-such-mode})))))

(deftest scoring
  (let [opts {:mode :subsequence :ranked? true}
        score (fn [s q] (:score (m/match s q opts)))]
    (testing "a contiguous match beats a scattered one"
      (is (> (score "views.cljs" "views") (score "v-i-e-w-s.cljs" "views"))))
    (testing "word-boundary hits beat mid-word ones"
      (is (> (score "view_scan_check" "vsc") (score "aviaskacheck" "vsc"))))
    (testing "an earlier match beats a later one, all else equal"
      (is (> (score "abcXXXXXXXX" "abc") (score "XXXXXXXXabc" "abc"))))
    (testing "a shorter candidate beats a longer one, all else equal"
      (is (> (score "abc" "abc") (score "abcdefghijklmnop" "abc"))))
    (testing "scores stay inside the documented range"
      (doseq [[s q] [["views.cljs" "v"] ["views.cljs" "views.cljs"] ["a" "a"]]]
        (let [x (score s q)]
          (is (<= 0 x 1000) (str s "/" q " scored " x)))))
    (testing "without :ranked? every match scores the same, so order is left alone"
      (is (= 1000 (:score (m/match "v-i-e-w-s.cljs" "views" {:mode :subsequence})))))))

(deftest ranking
  (let [files ["src/vinary/ui/views.cljs" "src/vinary/web/core.cljs" "README.md"]]
    (testing "filters to matches, preserving input order when unranked"
      (is (= ["src/vinary/ui/views.cljs" "src/vinary/web/core.cljs"]
             (mapv :item (m/rank files "v" {:mode :subsequence})))))
    (testing "ranked, the tighter match comes first"
      (is (= "src/vinary/ui/views.cljs"
             (:item (first (m/rank files "views" {:mode :subsequence :ranked? true}))))))
    (testing ":key-fn reaches into maps"
      (let [items [{:label "alpha"} {:label "beta"}]]
        (is (= [{:label "beta"}] (mapv :item (m/rank items "bet" {:key-fn :label}))))))
    (testing ":limit is applied AFTER ranking, so it keeps the best rather than the first"
      (let [xs (into ["zzz-target"] (repeat 80 "zzzzzzzzzzzzzzzzzzz"))
            got (m/rank xs "target" {:mode :subsequence :ranked? true :limit 1})]
        (is (= 1 (count got)))
        (is (= "zzz-target" (:item (first got))))))))

(deftest config-defaults-reproduce-the-previous-behaviour
  (testing "each surface keeps exactly the matching it had before the consolidation"
    (is (= :substring   (:mode (config/mode-for :find))))
    (is (= :substring   (:mode (config/mode-for :tui))))
    (is (= :substring   (:mode (config/mode-for :tree))))
    (is (= :subsequence (:mode (config/mode-for :palette))))
    (is (= :prefix      (:mode (config/mode-for :uri)))))
  (testing "folding is :strict exactly where a span index maps back to a source position"
    (is (= :strict (:fold (config/mode-for :find))))
    (is (= :strict (:fold (config/mode-for :tui))))
    (is (= :simple (:fold (config/mode-for :tree))))
    (is (= :simple (:fold (config/mode-for :palette)))))
  (testing "a user override is honoured"
    (is (= :subsequence (:mode (config/mode-for :tree {:search-modes {:tree :subsequence}})))))
  (testing "an override naming a mode this build does not implement is ignored, not obeyed"
    (is (= :substring (:mode (config/mode-for :tree {:search-modes {:tree :telepathy}})))))
  (testing "an unknown surface falls back to find's options rather than to nil"
    (is (= (config/mode-for :find) (config/mode-for :no-such-surface))))
  (testing "every advertised mode is one the matcher implements"
    (is (= m/mode-ids (into #{} (map :id) m/modes)))
    (doseq [{:keys [id]} m/modes]
      (is (some? (m/match "abc" "" {:mode id})) (str "mode " id " is not usable")))))
