(ns vinary.ui.access-keys
  "Shared helpers for desktop-style Alt access keys in custom renderer UI."
  (:require [clojure.string :as str]))

(defn normalize-key [k]
  (some-> k str str/lower-case not-empty))

(defn event-letter
  "Return a lower-case printable access-key letter/digit for a KeyboardEvent, or nil."
  [^js e]
  (let [k (.-key e)]
    (when (and (string? k)
               (= 1 (count k))
               (not (.-ctrlKey e))
               (not (.-metaKey e))
               (re-matches #"[A-Za-z0-9]" k))
      (str/lower-case k))))

(defn match? [access-key k]
  (= (normalize-key access-key) (normalize-key k)))

(defn consume! [^js e]
  (.preventDefault e)
  (.stopPropagation e))

(defn label
  "Render label text with its access-key character underlined while active?."
  [text access-key active?]
  (let [text (str text)
        k    (normalize-key access-key)]
    (if (and active? k)
      (let [idx (first (keep-indexed (fn [i ch]
                                       (when (= k (str/lower-case (str ch))) i))
                                     text))]
        (if (some? idx)
          [:span.vv-access-label {:data-vv-access-key k}
           (subs text 0 idx)
           [:span.vv-access-key (subs text idx (inc idx))]
           (subs text (inc idx))]
          [:span.vv-access-label {:data-vv-access-key k} text]))
      [:span.vv-access-label (cond-> {:data-vv-access-key k} (nil? k) (dissoc :data-vv-access-key)) text])))

(defn access-attrs [access-key]
  (when-let [k (normalize-key access-key)]
    {:data-vv-access-key k
     :aria-keyshortcuts (str "Alt+" (str/upper-case k))}))

(defn focus-selector! [^js root selector]
  (when-let [el (and root (.querySelector root selector))]
    (.focus el)
    (when (.-select el) (.select el))
    true))

(defn choose-key
  "Choose a unique access key for label, preferring preferred when available."
  [used label preferred]
  (let [used (set (map normalize-key used))
        pref (normalize-key preferred)
        chars (->> (str label)
                   (map str)
                   (map normalize-key)
                   (filter #(and % (re-matches #"[a-z0-9]" %)))
                   distinct)]
    (or (when (and pref (not (contains? used pref))) pref)
        (first (remove used chars)))))

(defn annotate-rows
  "Attach generated :access-key values to row maps, preserving separators."
  [rows preferred]
  (loop [in rows used #{} out []]
    (if-let [row (first in)]
      (if (= row :sep)
        (recur (rest in) used (conj out row))
        (let [label (:label row)
              k     (or (:access-key row) (choose-key used label (get preferred label)))]
          (recur (rest in) (cond-> used k (conj k)) (conj out (assoc row :access-key k)))))
      out)))
