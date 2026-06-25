(ns vinary.app.ds
  "DataScript — the relational single-source-of-truth for documents/tabs. app-db holds only ephemeral
   UI (active tab, theme, find). One open file = one :doc entity keyed by :doc/path; tabs are the open
   docs ordered by :doc/order.

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

(defn active-doc
  "Pull the doc entity for path as {:doc/path :doc/kind :doc/html :doc/error}, or nil. Pulls by entity
   id (not a [:doc/path …] lookup-ref) — the lookup-ref form does not resolve under :advanced."
  [db path]
  (when-let [eid (eid-for-path db path)]
    (d/pull db [:doc/path :doc/kind :doc/text :doc/html :doc/error] eid)))
