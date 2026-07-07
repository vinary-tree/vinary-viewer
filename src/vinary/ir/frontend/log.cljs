(ns vinary.ir.frontend.log
  "Log front-end: a content-service log envelope (full text, or one page of lines) → the common document IR
   as a :document of :line leaves. The interactive Reagent log-view still renders + pages; this IR is the
   canonical line-structured parse. Multi-line-record grouping (stack traces, JSON entries spanning lines) is
   genuinely ambiguous — the weighted lattice/Earley machinery (vinary.ir.{lattice,earley,forest}) can rank
   those segmentations as a later refinement; a physical line is the baseline unit. Pure + DOM-free."
  (:require [clojure.string :as str]
            [vinary.ir.node :as node]))

(defn lines->ir "A seq of line strings → a :document of :line leaves." [lines]
  (node/node :document (mapv #(node/leaf :line (str %)) lines) {}))

(defn text->ir "A log text blob → IR (split on newlines)." [text]
  (lines->ir (str/split (or text "") #"\n")))

(defn payload->ir
  "A log payload → IR (full :text, or a :page of :lines)."
  [{:keys [text page]}]
  (cond
    (some? text) (text->ir text)
    page         (lines->ir (or (:lines page) []))
    :else        (node/node :document [] {})))
