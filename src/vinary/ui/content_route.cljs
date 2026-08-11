(ns vinary.ui.content-route
  "Pure renderer selection. Explicit user-selected facets outrank rendering policies such
   as large-document streaming; keeping the precedence here prevents a new policy branch
   from silently making an existing view unreachable."
  (:require [vinary.app.uri :as uri]
            [vinary.ir.backend.html :as ir-html]))

(def source-kinds #{"markdown" "mermaid" "source" "org" "latex" "diff"})

(defn source-view? [doc requested?]
  (boolean (and requested?
                (:doc/text doc)
                (or (:doc/sourceable? doc)
                    (contains? source-kinds (:doc/kind doc))))))

(defn route
  [{:keys [doc tabs uri source? diff-view reflow? stream-setting?]}]
  (let [kind (:doc/kind doc)]
    (cond
      (empty? tabs)                                      :watermark
      (nil? uri)                                         :empty
      (and (uri/http? uri) (= "pdf" kind))              :http-pdf
      (uri/http? uri)                                    :http
      (= "html" kind)                                   :html
      (and (nil? doc) uri)                               :loading
      (:doc/error doc)                                   :error
      ;; A chosen facet is user intent. Streaming is only a Preview implementation detail.
      (source-view? doc source?)                         :source
      (:doc/streaming? doc)                              :stream
      (= "pdf" kind)                                    (cond
                                                           (and reflow? (:doc/reflow-html doc) stream-setting?) :pdf-reflow-stream
                                                           (and reflow? (:doc/reflow-html doc))                  :pdf-reflow
                                                           :else                                                :pdf)
      (= "image" kind)                                  :image
      (= "diff" kind)                                   (cond
                                                           (and (= :split diff-view) (:doc/diff-split-html doc)) :diff-split
                                                           (:doc/html doc)                                      :diff
                                                           :else                                                :diff-rendering)
      (= "diagram" kind)                                :diagram
      (= "mermaid" kind)                                :mermaid
      (= "source" kind)                                 :source
      (= "office" kind)                                 :office
      (= "table" kind)                                  :table
      (= "log" kind)                                    :log
      (= "git-graph" kind)                              :git-graph
      (#{"directory" "archive"} kind)                    :directory
      (and (some? (:doc/html doc)) (ir-html/blank? (:doc/html doc))) :blank
      (:doc/html doc)                                    :html-doc
      :else                                              :rendering)))
