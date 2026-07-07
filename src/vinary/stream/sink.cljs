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
   append them to `node` in one insertion, joined by `sep`. `sep` is a LEADING separator on every append EXCEPT
   the very first to `node` (tracked by `started?*`, an atom<bool> per controller), so the streamed body carries
   no trailing separator. Two callers:

   • **logs** pass `sep = \"\\n\"`: records don't carry separators, so the sink supplies exactly one `\\n`
     between them — `record₁\\nrecord₂\\n…` with no trailing newline.
   • **markdown** passes `sep = \"\"`: the emitted blocks ARE the batch IR document's children, INCLUDING the
     inter-block whitespace `:text` leaves that already carry remark-rehype's exact separators, so concatenating
     them with no added separator is byte-identical to `lower(whole-document)`.

   `alive?` (0-arg) gates the async completion. `q` is an atom holding the serializing Promise; returns it."
  [node q blocks alive? posts-fn started?* sep]
  (when (seq blocks)
    (let [htmls (mapv ir-html/lower blocks)]
      (reset! q
        (-> @q
            (.then (fn [_]
                     (when (alive?)
                       (if posts-fn
                         ;; skip the post-passes for whitespace-only fragments (markdown's inter-block `\n`
                         ;; separator leaves) — they carry no math/mermaid/code, and running them through a
                         ;; parse+serialize post-pass would normalize the whitespace away and break parity.
                         (-> (js/Promise.all (into-array (map (fn [h] (if (str/blank? h) (js/Promise.resolve h) (posts-fn h))) htmls)))
                             (.then (fn [arr] (str/join sep (array-seq arr)))))
                         (str/join sep htmls)))))
            (.then (fn [joined]
                     (when (and joined (alive?))
                       (let [piece (if @started?* (str sep joined) joined)]
                         (reset! started?* true)
                         (.insertAdjacentHTML ^js node "beforeend" piece)))))
            (.catch (fn [_] nil))))))
  @q)

;; re-exported for callers that want the markdown post-passes without pulling in renderer.markdown directly
(def apply-posts md/apply-posts)
