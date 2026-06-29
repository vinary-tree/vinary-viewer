(ns vinary.main.password-config
  "Main-side password-manager bridge config. This file is non-secret: it only controls provider ordering,
   disabled provider ids, executable names/paths, and optional restricted JSON-command adapters. Master
   passwords, vault passwords, access tokens, and item secrets must never be stored here."
  (:require ["fs" :as fs]
            ["path" :as path]
            ["os" :as os]
            [cljs.reader :as reader]
            [clojure.string :as str]))

(def default-config
  {:enabled? true
   :default-provider nil
   :disabled-provider-ids #{}
   :builtins {}
   :providers []})

(defn- conf-dir []
  (let [home (or (.. js/process -env -XDG_CONFIG_HOME) (path/join (os/homedir) ".config"))]
    (path/join home "vinary-viewer")))

(defn config-path [] (path/join (conf-dir) "passwords.edn"))

(defn- config-text []
  (let [p (config-path)]
    (try (if (.existsSync fs p) (.readFileSync fs p "utf8") "")
         (catch :default _ ""))))

(defn- normalize-disabled [ids]
  (->> ids (map str) set))

(defn- normalize-builtins [m]
  (into {}
        (map (fn [[k v]] [(str (name k)) (cond-> (or v {})
                                           (:id v) (update :id str)
                                           (:bin v) (update :bin str)
                                           (:label v) (update :label str))]))
        (or m {})))

(defn- normalize-custom-provider [p]
  (when (map? p)
    (let [id (some-> (:id p) str str/trim)]
      (when (seq id)
        (-> p
            (assoc :id id
                   :kind (keyword (or (:kind p) :json-command))
                   :label (or (:label p) id))
            (update :enabled? #(if (nil? %) true (boolean %))))))))

(defn merge-config [m]
  (let [m (if (map? m) m {})]
    (-> default-config
        (merge (select-keys m [:enabled? :default-provider]))
        (assoc :disabled-provider-ids (normalize-disabled (:disabled-provider-ids m))
               :builtins (normalize-builtins (:builtins m))
               :providers (vec (keep normalize-custom-provider (:providers m)))))))

(defn load-config []
  (let [t (config-text)
        m (when (seq (str/trim t))
            (try (reader/read-string t) (catch :default _ nil)))]
    (merge-config m)))
