(ns vinary.app.ds
  "DataScript — the renderer's bounded content cache. app-db owns tabs/history and other ephemeral UI;
   one cached local file = one :doc entity keyed by :doc/path.

   Reactivity bridge: every transaction bumps :ds/rev in app-db (via a conn listener → re-frame event);
   the conn-reading subscriptions list [:ds/rev] as an input, so they recompute on each change. This is
   an explicit, guaranteed bridge (no reliance on re-posh subscription internals)."
  (:require [datascript.core :as d]
            [re-frame.core :as rf]))

(def schema
  {:doc/path {:db/unique :db.unique/identity}})

(defonce conn (d/create-conn schema))

(defn install-bridge! []
  (d/listen! conn ::reframe (fn [_tx-report] (rf/dispatch [:ds/changed]))))

;; ---- read helpers ----
(defn snapshot [] @conn)

(defn eid-for-path [db path]
  (d/q '[:find ?e . :in $ ?p :where [?e :doc/path ?p]] db path))

(defn order-for-path [db path]
  (d/q '[:find ?o . :in $ ?p :where [?e :doc/path ?p] [?e :doc/order ?o]] db path))

(defn next-order [db]
  (->> (d/q '[:find ?o :where [_ :doc/order ?o]] db)
       (map first) (reduce max -1) inc))

(defn open-docs
  "All open docs as {:path :order :kind}, ordered by :doc/order."
  [db]
  (->> (d/q '[:find ?path ?order ?kind
              :where [?e :doc/open? true] [?e :doc/path ?path]
                     [?e :doc/order ?order] [?e :doc/kind ?kind]] db)
       (sort-by second)
       (mapv (fn [[p o k]] {:path p :order o :kind k}))))

(defn doc-attr [db path attr]
  (d/q '[:find ?v . :in $ ?p ?a :where [?e :doc/path ?p] [?e ?a ?v]] db path attr))

(defn doc-paths
  "All cached document paths."
  [db]
  (->> (d/q '[:find ?p :where [_ :doc/path ?p]] db)
       (map first)
       vec))

(defn retract-unretained-tx
  "Retract cached documents whose paths are no longer retained by any open tab history."
  [db retained-paths]
  (let [retained (set retained-paths)]
    (vec (keep (fn [path]
                 (when-not (contains? retained path)
                   (when-let [eid (eid-for-path db path)]
                     [:db/retractEntity eid])))
               (doc-paths db)))))

(defn active-doc
  "Pull the doc entity for path, or nil. Pulls by entity id (not a [:doc/path ...] lookup-ref) because
   the lookup-ref form does not resolve under :advanced."
  [db path]
  (when-let [eid (eid-for-path db path)]
    (d/pull db [:doc/path :doc/kind :doc/text :doc/html :doc/toc :doc/assets :doc/entries
                :doc/error :doc/stamp :doc/sheets :doc/page :doc/paged? :doc/meta
                :doc/sourceable? :doc/data-url :doc/reflow-html :doc/pdf-sibling
                :doc/source-sibling :doc/diff-split-html
                :doc/streaming? :doc/stream-progress :doc/stream-note] eid)))
