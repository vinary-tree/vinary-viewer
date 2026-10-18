;; docs/theory/12 §7: load the PURE functions of src/vinary/slides/notes.cljs VERBATIM into a JVM Clojure namespace,
;; so the analyses of docs/theory/12 run the model's own source text rather than a hand port. Only forms without
;; JavaScript interop are evaluated; every other form is read (to keep the reader in step) and skipped.
;;
;; The repository root is $VV_ROOT when it is set, and otherwise the directory four levels above this file
;; (docs/theory/formal/speaker-notes/ → the root).
(ns notes-extract
  (:require [clojure.java.io :as io]))

(def repo-root
  (or (System/getenv "VV_ROOT")
      (-> (io/file (str *file*)) .getAbsoluteFile
          .getParentFile                                   ; speaker-notes
          .getParentFile                                   ; formal
          .getParentFile                                   ; theory
          .getParentFile                                   ; docs
          .getParentFile                                   ; the repository root
          .getPath)))

(def notes-path (str repo-root "/src/vinary/slides/notes.cljs"))

(def wanted
  "The top-level definitions evaluated as they are written in notes.cljs."
  '#{gap-choices gap-notes interleaving restartable? candidate-notes fallback-notes probe-next scan-step
     confirm-pairs confirmed? max-content-pages confirm-page-count})

(defn- def-name [form]
  (when (and (seq? form) ('#{defn defn- def} (first form)))
    (second form)))

(defn load-notes!
  "Evaluate the wanted forms of notes.cljs in the namespace `notes`. Returns the names loaded, in file order, and
   throws unless every wanted form was found."
  []
  (let [target (create-ns 'notes)
        loaded (binding [*ns* target
                         *default-data-reader-fn* (fn [_tag value] value)]
                 (refer-clojure)
                 (with-open [r (java.io.PushbackReader. (io/reader notes-path))]
                   (loop [loaded []]
                     (let [form (read {:eof ::eof :read-cond :allow :features #{:cljs}} r)]
                       (if (= form ::eof)
                         loaded
                         (let [nm (def-name form)]
                           (if (contains? wanted nm)
                             (do (eval form) (recur (conj loaded nm)))
                             (recur loaded))))))))]
    (when-not (= wanted (set loaded))
      (throw (ex-info "notes.cljs is missing a form the analyses need" {:missing (remove (set loaded) wanted)})))
    (println "loaded from" notes-path ":" (count loaded) "forms")
    loaded))
