(ns vinary.ir.frontend.log-stream-test
  "Tests for the streaming log front-end (vinary.ir.frontend.log-stream): line batches segment into :record
   IR blocks; a header-looking line inside braces does NOT split the record (the WPDA pushdown earns its
   keep); the records are invariant to how lines are split into batches (the open record is retained across
   feeds); and repeated records still get distinct, stable ids."
  (:require [cljs.test :refer [deftest is testing]]
            [vinary.ir.node :as node]
            [vinary.ir.backend.html :as ir-html]
            [vinary.ir.frontend.log-stream :as ls]
            [vinary.stream.protocol :as proto]))

;; each line is a div.vv-log-line ELEMENT wrapping a :text leaf, so its text is read via text-content
(defn- record-texts [blocks] (mapv (fn [rec] (mapv node/text-content (node/children rec))) blocks))

(deftest classify
  (is (ls/continuation-line? "    at Foo.bar"))
  (is (ls/continuation-line? "}"))
  (is (ls/continuation-line? "Caused by: boom"))
  (is (not (ls/continuation-line? "2026 ERROR boom")))
  (is (not (ls/continuation-line? "{")))
  (is (= 1 (ls/net-brace "payload {")))
  (is (= -1 (ls/net-brace "}")))
  (is (= 0 (ls/net-brace "{\"a\":1}"))))

(deftest segments-records
  (testing "header + indented stack frames = one record; a depth-0 header starts the next"
    (let [lines  ["2026 ERROR boom" "    at Foo.bar" "    at Baz.qux" "2026 INFO started" "2026 INFO done"]
          blocks (proto/drain (ls/parser) [lines])]
      (is (= [["2026 ERROR boom" "    at Foo.bar" "    at Baz.qux"] ["2026 INFO started"] ["2026 INFO done"]]
             (record-texts blocks)))
      (is (every? #(= :record (node/kind %)) blocks))
      (is (= "vv-log-record" (first (get-in (node/node-meta (first blocks)) [:attrs "className"])))))))

(deftest brace-keeps-record-whole
  (testing "a header-LOOKING line inside { … } does NOT split the record (context-free brace nesting)"
    (let [lines  ["{" "2026 ERROR looks-like-a-header" "  more: json" "}" "2026 INFO after"]
          blocks (proto/drain (ls/parser) [lines])]
      (is (= [["{" "2026 ERROR looks-like-a-header" "  more: json" "}"] ["2026 INFO after"]]
             (record-texts blocks))))))

(deftest streaming-across-batches-equals-whole
  (testing "the records are invariant to batch splitting (open record retained across feeds)"
    (let [lines ["h1" "  c1" "h2" "  c2" "  c3" "h3"]
          whole (record-texts (proto/drain (ls/parser) [lines]))]
      (doseq [split [[["h1" "  c1"] ["h2" "  c2" "  c3"] ["h3"]]
                     [["h1"] ["  c1" "h2" "  c2"] ["  c3" "h3"]]
                     (mapv vector lines)]]                       ; one line per batch
        (is (= whole (record-texts (proto/drain (ls/parser) split))) (str "batch split " (pr-str split)))))))

(deftest distinct-stable-ids
  (testing "records with identical header text still get distinct anchor ids"
    (let [blocks (proto/drain (ls/parser) [["ERROR same" "ERROR same" "ERROR same"]])
          ids    (mapv #(:id (node/node-meta %)) blocks)]
      (is (= 3 (count blocks)))
      (is (= 3 (count (distinct ids))) (str "ids: " (pr-str ids))))))

(deftest lowers-to-nonempty-html
  (testing "a record lowers to a div.vv-log-record whose lines are div.vv-log-line carrying the ACTUAL text
            (guards the leaf-vs-element trap: a bare :line leaf lowers to an empty <div> and the text vanishes)"
    (let [[rec] (proto/drain (ls/parser) [["2026 ERROR boom &<risky>" "    at Foo.bar"]])
          html  (ir-html/lower rec)]
      (is (re-find #"class=\"vv-log-record\"" html))
      (is (re-find #"id=\"" html) "the record carries its stable anchor id as an element id")
      (is (= 2 (count (re-seq #"class=\"vv-log-line\"" html))) "each source line becomes one div.vv-log-line")
      (is (re-find #"2026 ERROR boom" html) "the line text is present in the lowered HTML (not an empty div)")
      (is (re-find #"risky" html) "the escaped text is still present")
      (is (re-find #"&#x3C;|&lt;" html) "the '<' in log text is escaped to an entity")
      (is (not (re-find #"<risky>" html)) "a <…>-looking log fragment never renders as a raw tag (no markup injection)"))))

(deftest bounded-memory-property
  (testing "after arbitrarily many batches the retained parser holds only the OPEN record + a SINGLE WPDA
            config — the working set is bounded by the largest record, never by the document length"
    (let [;; 500 complete single-line records, then one still-open (unterminated) 3-line record; fed
          ;; one-record-per-batch so we exercise many feeds. A depth-0 header emits the PREVIOUS record,
          ;; so after all feeds exactly the last (open) record is retained.
          batches (conj (mapv (fn [i] [(str "2026 INFO event " i)]) (range 500))
                        ["2026 ERROR boom" "    at A.b" "    at C.d"])
          p       (reduce (fn [p b] (:parser (proto/feed p b))) (ls/parser) batches)]
      ;; the brace grammar is deterministic → the frontier is always a single config, however much was fed
      (is (= 1 (count (:frontier p))) "frontier stays a single config (bounded, not doc-proportional)")
      ;; the retained open record is just the last record's 3 lines — NOT all 503 lines fed
      (is (= ["2026 ERROR boom" "    at A.b" "    at C.d"] (:open p)) "only the open record is retained")
      (is (<= (count (:open p)) 3) "open record bounded by its own size, not the stream length")
      ;; finishing flushes exactly that one retained record
      (is (= 1 (count (:blocks (proto/finish p)))) "finish emits exactly the open record"))))
