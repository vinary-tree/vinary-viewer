(ns vinary.input.keymap
  "Keymap registry: the bundled presets (embedded at compile time), key normalization, and the
   preset⊕user merge. The active keymap lives in an atom (large + static-ish; modal/sequence state is in
   app-db instead). (Strategy pattern: the active keymap + mode select which bindings resolve keys.)

   The preset EDN is inlined via shadow.resource/inline — which TRACKS each file as a compile dependency,
   so editing resources/keymaps/*.edn triggers a recompile (a plain compile-time slurp would not, leaving
   the bundled keymaps stale until a cache-clearing rebuild)."
  (:require [clojure.walk :as walk]
            [cljs.reader :as reader]
            [shadow.resource :as rc]
            [vinary.input.keys :as keys]))

(def bundled
  {:default (reader/read-string (rc/inline "keymaps/default.edn"))
   :vim     (reader/read-string (rc/inline "keymaps/vim.edn"))
   :emacs   (reader/read-string (rc/inline "keymaps/emacs.edn"))})

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

(defn preset
  "The bundled preset keymap for a name (:default/:vim/:emacs), defaulting to :default."
  [km-name]
  (get bundled km-name (:default bundled)))

(defn merge-user
  "PURE: merge a user config delta onto the preset it :extends → a fully-resolved keymap (normalized,
   :unbind leaves stripped), WITHOUT installing. The editor reads this to show a set's effective bindings.
   user-cfg = {:extends :vim :initial-mode … :timeout-ms … :leader … :keymaps {…modes…}}."
  [user-cfg]
  (-> (preset (:extends user-cfg :default))
      (cond-> (:initial-mode user-cfg) (assoc :initial-mode (:initial-mode user-cfg)))
      (cond-> (:timeout-ms user-cfg)   (assoc :timeout-ms (:timeout-ms user-cfg)))
      (cond-> (:leader user-cfg)       (assoc :leader (:leader user-cfg)))
      (update :modes deep-merge (:keymaps user-cfg))
      strip-unbinds
      normalize-keys))

(defn install-user!
  "Merge a user config delta onto its :extends preset and install. Returns the merged keymap."
  [user-cfg]
  (reset! active (merge-user user-cfg))
  @active)

(defn modes        [] (:modes @active))
(defn initial-mode [] (:initial-mode @active :insert))
(defn timeout-ms   [] (:timeout-ms @active 1000))
(defn keymap-name  [] (:name @active))
