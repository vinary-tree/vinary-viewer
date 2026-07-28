(ns vinary.app.tree-model
  "Pure model behind the sidebar file tree: fold each project's flat repo-relative paths into a nested
   folder/file structure, and narrow that structure by a filter query.

   WHY IT IS NOT IN THE VIEW. It used to run inside `vinary.ui.tree`'s render function, which meant every
   keystroke in the filter box re-folded every path of every open project — thousands of `str/split`s and
   an `assoc-in` each — before React had even begun reconciling. Rendering is not the place to compute a
   model, and a render function is the one place a result cannot be memoised.

   Moved here, it is a re-frame layer-3 subscription (`:tree/filtered`): it recomputes only when the
   projects or the query actually change, and it is testable without a DOM. Sitting next to
   `vinary.app.projects`, which owns the merge rules for the same data, for the same reason."
  (:require [clojure.string :as str]
            [vinary.search.match :as match]))

(defn build-tree
  "Flat repo-relative paths → nested map. A folder node has :children; a file node has :file."
  [files]
  (reduce (fn [acc f]
            (let [parts (str/split f #"/")
                  ks    (concat (interpose :children parts) [:file])]
              (assoc-in acc ks f)))
          {} files))

(defn filtered
  "Narrow `projects` (a seq of {:root :files}) by `q` and fold each survivor into a nested tree.

   Returns a vector of {:root :files :nodes :filtered?}, omitting projects with nothing left to show — so
   the view renders what it is given rather than deciding what to hide.

   `match-opts` comes from `vinary.search.config`, which is what makes the matching semantics
   configurable: the default reproduces the previous `str/includes?` on a lower-cased path exactly, and a
   different mode is a keyword rather than a rewrite.

   `:filtered?` is carried out rather than recomputed in the view because it drives whether folders render
   expanded — a filtered tree opens everything so the survivors are visible, an unfiltered one does not."
  [projects q match-opts]
  (let [blank? (str/blank? q)
        m      (when-not blank? (match/matcher q match-opts))]
    (into []
          (keep (fn [{:keys [root files]}]
                  (let [shown (if blank? (vec files) (filterv #(some? (m %)) files))]
                    (when (seq shown)
                      {:root root :files shown :nodes (build-tree shown) :filtered? (not blank?)}))))
          projects)))
