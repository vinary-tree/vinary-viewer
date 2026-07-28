(ns vinary.app.palette
  "Pure candidate model for the command palette — what the overlay shows, given a source, a query, and the
   data each source draws on.

   WHY IT IS NOT IN THE VIEW. It used to run inside the palette's render function, and the `:file` source
   allocated one map per file across every open project BEFORE filtering any of them: on this repository
   that is ~6 500 maps built and thrown away per keystroke, plus two folded strings per candidate inside
   the subsequence test. The `(take 60)` at the end bounded what was DISPLAYED, never what was built.

   Here the order is inverted — rank the raw strings, then materialise items only for the survivors — and
   the whole thing is a memoised subscription instead of render-time work."
  (:require [vinary.search.match :as match]))

(def ^:private limit
  "How many rows the overlay will show. Applied per source AND again across sources, so the cap bounds
   allocation rather than merely truncating the display."
  60)

(def themes
  "The selectable themes, as palette items. Data rather than a literal buried in a `case`, so the theme
   list has one home."
  [{:label "Spacemacs Dark"  :theme "spacemacs-dark"  :kind :theme}
   {:label "Spacemacs Light" :theme "spacemacs-light" :kind :theme}])

(defn- rank-items
  "Rank pre-built item maps by their :label."
  [items query opts]
  (match/rank items query (assoc opts :key-fn :label :limit limit)))

(defn- file-candidates
  "Rank each project's RAW relative paths, then build an item only for the survivors.

   Ranking per project rather than over one merged list is what avoids the concatenation: `files` is
   already a vector of plain strings, so matching allocates nothing until something matches."
  [projects query opts]
  (->> projects
       (mapcat (fn [{:keys [root files]}]
                 (map (fn [{:keys [item score spans]}]
                        {:item  {:label item :path (str root "/" item) :kind :file}
                         :score score :spans spans})
                      (match/rank files query (assoc opts :limit limit)))))
       (take limit)
       vec))

(defn candidates
  "The palette's rows: a vector of {:item :score :spans}.

   `visible-commands` is the already-`:when`-filtered command list — resolving that needs a context the
   caller owns, so it is passed in rather than reached for. `match-opts` comes from
   `vinary.search.config`, which is what makes the matching mode configurable."
  [source query projects visible-commands match-opts]
  (case source
    :file  (file-candidates projects query match-opts)
    :theme (rank-items themes query match-opts)
    (rank-items (mapv (fn [c] {:label (:title c) :command (:id c) :category (:category c) :kind :command})
                      visible-commands)
                query match-opts)))
