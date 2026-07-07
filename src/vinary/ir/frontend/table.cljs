(ns vinary.ir.frontend.table
  "Table front-end: a content-service table envelope (workbook sheets, or one page of rows) → the common
   document IR as :table/:row/:cell nodes; a multi-sheet workbook wraps each sheet in a :section headed by
   the sheet name (a navigable sheet outline). The interactive Reagent table-view still renders + pages the
   data — this IR is the canonical structural parse every format yields under the common-IR design. Pure +
   DOM-free."
  (:require [vinary.ir.node :as node]))

(defn- cell->ir [v] (node/leaf :cell (if (nil? v) "" (str v))))
(defn- row->ir  [cells] (node/node :row (mapv cell->ir cells)))
(defn rows->ir  "A seq of rows (each a seq of cell values) → a :table node." [rows]
  (node/node :table (mapv row->ir rows)))

(defn sheet->ir
  "A sheet {:name :rows} → a :section: a level-2 :heading (the sheet name) followed by its :table."
  [{:keys [name rows]}]
  (node/node :section
             [(node/node :heading [(node/leaf :text (str name))] {:level 2})
              (rows->ir (or rows []))]))

(defn payload->ir
  "A table payload → IR. Multi-sheet workbooks become a :document of per-sheet :sections; a single page of
   rows becomes a :document holding one bare :table."
  [{:keys [sheets page]}]
  (cond
    (seq sheets) (node/node :document (mapv sheet->ir sheets) {})
    page         (node/node :document [(rows->ir (or (:rows page) []))] {})
    :else        (node/node :document [] {})))
