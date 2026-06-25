(ns vinary.input.keymap
  "Keymap registry: the bundled presets (embedded at compile time), key normalization, and the
   preset⊕user merge. The active keymap lives in an atom (large + static-ish; modal/sequence state is in
   app-db instead). (Strategy pattern: the active keymap + mode select which bindings resolve keys.)"
  (:require-macros [vinary.input.presets :as presets])
  (:require [clojure.walk :as walk]
            [vinary.input.keys :as keys]))

(def bundled (presets/bundled))

(defn- normalize-keys
  "Recursively normalize authored keymap KEYS (SPC→space, etc.) so they compare equal to runtime tokens."
  [m]
  (walk/postwalk
   (fn [x]
     (if (map? x)
       (into {} (map (fn [[k v]] [(if (string? k) (keys/normalize-token k) k) v])) x)
       x))
   m))

(defn- deep-merge [a b]
  (cond
    (nil? b)                a                  ; a user delta with no value for this key keeps the preset
    (and (map? a) (map? b)) (merge-with deep-merge a b)
    :else                   b))

(defn- strip-unbinds [m]
  (walk/postwalk
   (fn [x] (if (map? x) (into {} (remove (fn [[_ v]] (= v :unbind))) x) x))
   m))

(defonce ^:private active (atom (normalize-keys (:default bundled))))

(defn install!
  "Install a bundled preset by name (:default/:vim/:emacs)."
  [km-name]
  (reset! active (normalize-keys (get bundled km-name (:default bundled)))))

(defn install-user!
  "Merge a user config delta onto the preset it :extends, then install. Returns the merged keymap.
   user-cfg = {:extends :vim :initial-mode … :timeout-ms … :leader … :keymaps {…modes…}}; a binding
   value of :unbind removes an inherited binding."
  [user-cfg]
  (let [preset (get bundled (:extends user-cfg :default) (:default bundled))
        merged (-> preset
                   (cond-> (:initial-mode user-cfg) (assoc :initial-mode (:initial-mode user-cfg)))
                   (cond-> (:timeout-ms user-cfg)   (assoc :timeout-ms (:timeout-ms user-cfg)))
                   (cond-> (:leader user-cfg)       (assoc :leader (:leader user-cfg)))
                   (update :modes deep-merge (:keymaps user-cfg)))]
    (reset! active (normalize-keys (strip-unbinds merged)))
    @active))

(defn modes        [] (:modes @active))
(defn initial-mode [] (:initial-mode @active :insert))
(defn timeout-ms   [] (:timeout-ms @active 1000))
(defn keymap-name  [] (:name @active))
