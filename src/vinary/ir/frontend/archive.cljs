(ns vinary.ir.frontend.archive
  "Archive / directory front-end: a listing envelope ({:entries [{:name :path :dir? :size :mtime}]}) → the
   common document IR as a :list of :list-item nodes, each tagged :directory or :file with its path/size in
   metadata (a navigable listing outline). The interactive Reagent dir-view still renders + navigates; this
   IR is the canonical listing parse. Pure + DOM-free."
  (:require [vinary.ir.node :as node]))

(defn- entry->ir [{:keys [name path dir? size mtime]}]
  (node/node :list-item [(node/leaf :text (str name))]
             {:role (if dir? :directory :file) :path path :size size :mtime mtime}))

(defn entries->ir
  "A seq of listing entries → a :document holding a :list of :list-items."
  [entries]
  (node/node :document [(node/node :list (mapv entry->ir entries) {})] {}))

(defn payload->ir [{:keys [entries]}] (entries->ir (or entries [])))
