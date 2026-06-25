(ns vinary.input.kbedit-history
  "Undo/redo for the key-binding editor — the Command pattern as DATA. Every edit is a self-contained
   command map {:op …} with a pure apply-cmd (db→db') and a pure invert (cmd→inverse-cmd) carrying enough
   captured state (previous binding value, removed set's cfg + index) to be perfectly reversible. The
   editor pushes commands onto [:ui :kbedit :undo]; undo applies the inverse, redo re-applies the original.

   Ops:
     :put         set/replace/clear a binding   {:set-id :mode :chords [chord…] :value <action|:unbind|nil> :prev}
     :insert-set  add a custom set              {:name :cfg :idx}
     :remove-set  delete a custom set           {:name :cfg :idx}      (mutual inverse of :insert-set)
     :rename-set  rename a custom set           {:old :new}
     :reorder     move a custom set             {:name :from-idx :to-idx}"
  (:require [vinary.input.keymaps-registry :as registry]))

(defn- dissoc-in
  "dissoc a nested key path, pruning now-empty parent maps."
  [m [k & ks]]
  (if ks
    (let [v (dissoc-in (get m k) ks)]
      (if (seq v) (assoc m k v) (dissoc m k)))
    (dissoc m k)))

(defn- bind-path [set-id mode chords]
  (into [:ui :keymaps :sets set-id :keymaps mode] chords))

(defn current-binding
  "The current delta value at [set-id mode chords] (an action keyword, :unbind, a sub-trie, or nil)."
  [db set-id mode chords]
  (get-in db (bind-path set-id mode chords)))

(defn put-binding
  "Set the custom set's delta at [mode chords] to value; value nil clears the path (pruning empties)."
  [db set-id mode chords value]
  (let [path (bind-path set-id mode chords)]
    (if (nil? value) (dissoc-in db path) (assoc-in db path value))))

(defn- insert-set [db idx nm cfg]
  (-> db
      (assoc-in [:ui :keymaps :sets nm] cfg)
      (update-in [:ui :keymaps :order]
                 (fn [o] (let [o (vec o) i (max 0 (min (count o) idx))]
                           (vec (concat (subvec o 0 i) [nm] (subvec o i))))))))

(defn apply-cmd [db {:keys [op] :as c}]
  (case op
    :put        (put-binding db (:set-id c) (:mode c) (:chords c) (:value c))
    :insert-set (insert-set db (:idx c) (:name c) (:cfg c))
    :remove-set (registry/delete-custom db (:name c))
    :rename-set (registry/rename-custom db (:old c) (:new c))
    :reorder    (registry/reorder db (:name c) (:to-idx c))
    db))

(defn invert [{:keys [op] :as c}]
  (case op
    :put        (assoc c :value (:prev c) :prev (:value c))
    :insert-set (assoc c :op :remove-set)
    :remove-set (assoc c :op :insert-set)
    :rename-set (assoc c :old (:new c) :new (:old c))
    :reorder    (assoc c :from-idx (:to-idx c) :to-idx (:from-idx c))
    c))
