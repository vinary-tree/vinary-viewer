(ns vinary.app.commits
  "Pure renderer model for the Commits surfaces (ADR-0039): repo derivation for the sidebar panel,
   the free-form ref-range grammar, the R3 selection reducers shared by the sidebar list and the
   Commit Graph document, and refresh-survival pruning. DOM-free; covered by the node :test build."
  (:require [clojure.string :as str]
            [vinary.app.projects :as projects]))

(defn git-projects
  "The non-synthetic [:ui :projects] entries — the only roots git commands can serve."
  [projs]
  (into [] (remove :synthetic?) projs))

(defn derive-root
  "The repo the Commits panel shows: the pinned root while its project is still open; else the
   deepest git project containing active-path; else the last root shown (the panel must not thrash
   while the user browses non-repo files); else the first git project. nil ⇔ no git project open."
  [{:keys [pinned last-root]} projs active-path]
  (let [gits    (git-projects projs)
        root-of (fn [r] (some #(when (= (:root %) r) r) gits))]
    (or (root-of pinned)
        (when active-path
          (->> gits
               (filter #(projects/under? active-path (:root %)))
               (sort-by (comp - count :root))
               first
               :root))
        (root-of last-root)
        (:root (first gits)))))

(defn parse-range
  "The free-form ref input grammar: \"A...B\" → {:from \"A\" :to \"B\" :dots \"...\"};
   \"A..B\" → {:from \"A\" :to \"B\"}; a single token R → {:to \"R\" :parent? true} (diff R against
   its parent — the same semantics as activating R in the list). nil for blank input or a range
   with an empty side. Whitespace around the input and around each side is trimmed; validation of
   the refs themselves is main's job (rev-parse --verify), never the renderer's."
  [s]
  (let [s (str/trim (str s))]
    (when-not (str/blank? s)
      (if-let [[_ from dots to] (re-matches #"(.*?)(\.\.\.?)(.*)" s)]
        (let [from (str/trim from)
              to   (str/trim to)]
          (when (and (seq from) (seq to))
            (cond-> {:from from :to to}
              (= dots "...") (assoc :dots "..."))))
        {:to s :parent? true}))))

(defn select
  "R3 selection reducer over {:cursor :anchor :selected #{hash}}. `order` is the loaded hash vector
   (newest-first). :single → cursor=anchor=hash, selected #{hash}; :toggle → flip membership,
   cursor=anchor=hash; :range → selected = every hash between anchor and hash inclusive (falls back
   to :single when either end left the loaded window)."
  [selection order hash mode]
  (case mode
    :single {:cursor hash :anchor hash :selected #{hash}}
    :toggle (let [sel (set (:selected selection))
                  sel (if (contains? sel hash) (disj sel hash) (conj sel hash))]
              {:cursor hash :anchor hash :selected sel})
    :range  (let [order  (vec order)
                  anchor (or (:anchor selection) hash)
                  idx    (fn [h] (first (keep-indexed #(when (= %2 h) %1) order)))
                  ai     (idx anchor)
                  hi     (idx hash)]
              (if (and ai hi)
                {:cursor hash :anchor anchor
                 :selected (set (subvec order (min ai hi) (inc (max ai hi))))}
                {:cursor hash :anchor hash :selected #{hash}}))))

(defn diff-pair
  "[older newer] when EXACTLY two commits are selected (log order is newest-first, so the higher
   index is the older commit); nil otherwise — the \"Diff selected\" affordance shows only then."
  [selection order]
  (let [sel (vec (:selected selection))]
    (when (= 2 (count sel))
      (let [order (vec order)
            idx   (fn [h] (first (keep-indexed #(when (= %2 h) %1) order)))
            [a b] sel
            ia    (idx a)
            ib    (idx b)]
        (when (and ia ib)
          (if (> ia ib) [a b] [b a]))))))

(defn history-args
  "The vv:git-log request additions for a repo's panel MODE: file history follows renames, line
   history rides the lineRange key, plain :log adds nothing (nil). History always walks HEAD — a
   branch pick would contradict working-file lineage — so callers must add :ref only when this
   returns nil."
  [{:keys [mode target]}]
  (case mode
    :file-history {:file (:file target) :follow true}
    :line-history {:lineRange {:file  (:file target)
                               :start (:start target)
                               :end   (:end target)}}
    nil))

(defn keep-surviving
  "After a page-0 refresh replaces :commits (vv:git-changed), prune hash-keyed UI state to hashes
   that still exist: selection members, the expanded set, and the rendered-body cache. cursor and
   anchor fall to nil when their commit vanished (rewritten history)."
  [repo-state hashes]
  (let [live (set hashes)
        {:keys [selection expanded bodies]} repo-state
        sel  (into #{} (filter live) (:selected selection))]
    (assoc repo-state
           :selection {:cursor   (when (contains? live (:cursor selection)) (:cursor selection))
                       :anchor   (when (contains? live (:anchor selection)) (:anchor selection))
                       :selected sel}
           :expanded (into #{} (filter live) (or expanded #{}))
           :bodies   (into {} (filter (comp live key)) (or bodies {})))))
