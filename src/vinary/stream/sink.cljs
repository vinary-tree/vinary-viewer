(ns vinary.stream.sink
  "Append-mode render sink for streaming documents: lower IR blocks to HTML and APPEND them to the stream body
   (never a whole-innerHTML replace). A whole batch of blocks is lowered and inserted in ONE DOM operation
   (order-preserving) so a 1000-line batch is one insert, not a thousand. Appends are serialized through a
   per-controller promise queue so async post-passes (mermaid/syntax, for markdown) still land in document
   order; each append is cancel-aware (skipped if the stream was torn down while its post-passes were running)."
  (:require [clojure.string :as str]
            [vinary.ir.backend.html :as ir-html]
            [vinary.renderer.markdown :as md]))

(defn append-blocks!
  "Lower `blocks`, optionally run `posts-fn` (md/apply-posts, or nil to skip — logs need no post-passes), and
   append them to `node` in one insertion, separated by `\\n` (matching remark-rehype's inter-block text nodes
   so streamed output matches the batch render). `alive?` (0-arg) gates the async completion. `q` is an atom
   holding the serializing Promise; returns the updated Promise."
  [node q blocks alive? posts-fn]
  (when (seq blocks)
    (let [htmls (mapv ir-html/lower blocks)]
      (reset! q
        (-> @q
            (.then (fn [_]
                     (when (alive?)
                       (if posts-fn
                         (-> (js/Promise.all (into-array (map posts-fn htmls)))
                             (.then (fn [arr] (str/join "\n" (array-seq arr)))))
                         (str/join "\n" htmls)))))
            (.then (fn [joined]
                     (when (and joined (alive?))
                       (.insertAdjacentHTML ^js node "beforeend" (str joined "\n")))))
            (.catch (fn [_] nil))))))
  @q)

;; re-exported for callers that want the markdown post-passes without pulling in renderer.markdown directly
(def apply-posts md/apply-posts)
