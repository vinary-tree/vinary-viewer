(ns vinary.input.keymaps-registry
  "The in-renderer registry of keymap SETS — the three read-only built-ins (Standard/Vim/Emacs) plus the
   user's named CUSTOM sets — over the app-db slice [:ui :keymaps] {:active :order :sets}. Mirrors
   vinary.app.nav (pure reads + transforms over app-db) and bridges to the single live keymap atom via
   install-active!. The Settings ▸ Key Bindings submenu and the editor both read these. Custom sets persist
   to ~/.config/vinary-viewer/keybindings.edn as the {:active :order :sets} envelope."
  (:require [clojure.string :as str]
            [vinary.input.keymap :as keymap]))

(def builtins
  [{:id "default" :name "Standard Mode"}
   {:id "vim"     :name "Vim Mode"}
   {:id "emacs"   :name "Emacs Mode"}])

(def ^:private builtin-ids #{"default" "vim" "emacs"})
(defn builtin? [id] (contains? builtin-ids id))

;; ---- reads (over [:ui :keymaps]) ----
(defn registry  [db] (get-in db [:ui :keymaps]))
(defn active-id [db] (get-in db [:ui :keymaps :active] "default"))
(defn order     [db] (get-in db [:ui :keymaps :order] []))
(defn sets      [db] (get-in db [:ui :keymaps :sets] {}))
(defn set-ids   [db] (into (mapv :id builtins) (order db)))          ; built-ins first, then custom in order
(defn custom?   [db id] (contains? (sets db) id))

(defn display-name [db id]
  (or (some #(when (= (:id %) id) (:name %)) builtins) id))           ; built-in label, or the custom name (= id)

(defn entry
  "The user-cfg for a set id: the stored custom map, or a synthetic {:extends id :keymaps {}} for a built-in."
  [db id]
  (if (builtin? id) {:extends (keyword id) :keymaps {}} (get-in db [:ui :keymaps :sets id])))

(defn effective-modes
  "The merged :modes of a set — what its bindings actually resolve to (for the editor; no install)."
  [db id]
  (:modes (keymap/merge-user (entry db id))))

(defn active-entry [db] (entry db (active-id db)))

(defn modal?
  "Whether set `id` is modal (its initial mode ≠ :insert — i.e. vim-like). The editor hides :mode/* actions
   for non-modal sets and binds new chords in :normal (modal) vs :insert (non-modal)."
  [db id]
  (not= :insert (:initial-mode (keymap/merge-user (entry db id)) :insert)))

(defn default-mode [db id] (if (modal? db id) :normal :insert))

;; ---- naming ----
(defn- taken? [db nm]
  (or (builtin? nm) (contains? (sets db) nm) (some #(= nm (:name %)) builtins)))

(defn fresh-name [db]
  (loop [n 1] (let [nm (str "Custom " n)] (if (taken? db nm) (recur (inc n)) nm))))

(defn clone-name [db base]
  (loop [n 1] (let [nm (str base " Copy" (when (> n 1) (str " " n)))]
                (if (taken? db nm) (recur (inc n)) nm))))

;; ---- transforms (pure db→db) ----
(defn select [db id] (assoc-in db [:ui :keymaps :active] id))

(defn put-entry [db id cfg] (assoc-in db [:ui :keymaps :sets id] cfg))

(defn add-custom
  "Add a new custom set `nm` (cfg), appended to the order and made active."
  [db nm cfg]
  (-> db
      (assoc-in [:ui :keymaps :sets nm] cfg)
      (update-in [:ui :keymaps :order] (fnil conj []) nm)
      (assoc-in [:ui :keymaps :active] nm)))

(defn delete-custom [db nm]
  (cond-> db
    true (update-in [:ui :keymaps :sets] dissoc nm)
    true (update-in [:ui :keymaps :order] (fn [o] (vec (remove #(= % nm) o))))
    (= (active-id db) nm) (assoc-in [:ui :keymaps :active] "default")))

(defn rename-custom [db old new]
  (if (or (builtin? old) (taken? db new) (str/blank? new) (= old new))
    db
    (let [cfg (get-in db [:ui :keymaps :sets old])]
      (cond-> db
        true (update-in [:ui :keymaps :sets] (fn [s] (-> s (dissoc old) (assoc new cfg))))
        true (update-in [:ui :keymaps :order] (fn [o] (mapv #(if (= % old) new %) o)))
        (= (active-id db) old) (assoc-in [:ui :keymaps :active] new)))))

(defn reorder [db from-id to-idx]
  (let [o    (vec (order db))
        from (first (keep-indexed #(when (= %2 from-id) %1) o))]
    (if (and from to-idx)
      (let [item    (get o from)
            removed (vec (concat (subvec o 0 from) (subvec o (inc from))))
            ins     (max 0 (min (count removed) (if (< from to-idx) (dec to-idx) to-idx)))]
        (assoc-in db [:ui :keymaps :order] (vec (concat (subvec removed 0 ins) [item] (subvec removed ins)))))
      db)))

(defn clone-set
  "Clone any set (built-in or custom) into a NEW custom set; snapshots its cfg (no :extends chaining)."
  [db src-id]
  (let [nm  (clone-name db (display-name db src-id))
        cfg (if (builtin? src-id) {:extends (keyword src-id) :keymaps {}} (get-in db [:ui :keymaps :sets src-id]))]
    (add-custom db nm cfg)))

;; ---- the active set's effective bindings, reverse-indexed by action (for the editor) ----
(defn- leaf-id [v] (cond (keyword? v) v (and (map? v) (:id v)) (:id v) :else nil))
(defn- subtrie [v] (when (and (map? v) (not (:id v))) v))
(defn- paths-to [trie action prefix]
  (mapcat (fn [[chord v]]
            (let [p (conj prefix chord)]
              (cond
                (= (leaf-id v) action) [p]
                (subtrie v)            (paths-to v action p)
                :else                  [])))
          trie))

(defn bindings-for
  "Every chord-sequence in set `id` resolving to `action-id`, as [[mode [chord…]] …]."
  [db id action-id]
  (vec (for [[mode trie] (effective-modes db id)
             path (paths-to trie action-id [])]
         [mode path])))

(defn- all-paths [trie prefix]
  (mapcat (fn [[chord v]]
            (let [p (conj prefix chord)]
              (cond
                (leaf-id v) [[(leaf-id v) p]]
                (subtrie v)  (all-paths v p)
                :else        [])))
          trie))

(defn action-index
  "Reverse-index set `id`'s effective bindings by action: {action-id → [[mode [chord…]] …]} (the editor
   shows each action's current bindings as chips)."
  [db id]
  (reduce (fn [acc [mode trie]]
            (reduce (fn [a [action path]] (update a action (fnil conj []) [mode path]))
                    acc (all-paths trie [])))
          {} (effective-modes db id)))

(defn conflict
  "A binding already at, or strictly under, [mode chords…] in set id (so a new binding would collide /
   shadow a sequence). Returns the conflicting chord-path or nil."
  [db id mode chords]
  (let [trie (get (effective-modes db id) mode)
        node (get-in trie (vec chords))]
    (cond
      (leaf-id node)        chords                                  ; exact binding exists
      (and (map? node) (seq node)) chords                           ; chords is a prefix of a longer sequence
      :else nil)))

;; ---- config envelope (on disk: ~/.config/vinary-viewer/keybindings.edn) ----
(defn normalize-config
  "Coerce a parsed keybindings.edn into the registry envelope {:active :order :sets} (idempotent).
   Back-compat: a legacy single-delta {:extends :keymaps} wraps as one custom set named \"Custom\"."
  [cfg]
  (cond
    (and (map? cfg) (:sets cfg))
    {:active (or (:active cfg) "default") :order (vec (:order cfg)) :sets (:sets cfg)}
    (and (map? cfg) (or (:extends cfg) (:keymaps cfg)))
    {:active "Custom" :order ["Custom"] :sets {"Custom" cfg}}
    :else
    {:active "default" :order [] :sets {}}))

(defn ->edn [db] (pr-str (registry db)))

;; ---- side-effecting bridge to the live keymap atom ----
(defn install-active!
  "Install the active set into the live keymap (the resolver reads it)."
  [db]
  (let [id (active-id db)]
    (if (builtin? id) (keymap/install! (keyword id)) (keymap/install-user! (entry db id)))))
