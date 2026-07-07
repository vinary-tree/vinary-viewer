(ns vinary.stream.scheduler
  "Drives a document stream into the DOM: open a transport session, then on each idle tick pull one batch,
   feed it to the StreamParser, and append the completed blocks — paced across idle frames so the UI never
   blocks (the transport double-buffers, so the next pull overlaps this batch's append). One controller per
   streaming view; `stop!` cancels it and closes the session.

   Bounded memory end-to-end: the parser holds only the open block + its (single) WPDA config; the transport
   holds ≤2 batches; main reads ≤1 ahead. We deliberately do NOT snapshot the whole rendered innerHTML on
   completion — that would hold the entire document in memory and defeat the point — so a re-mount (tab switch
   back) simply re-streams, which stays bounded. A bounded Contents outline (error/warn records, capped) grows
   as records arrive."
  (:require [re-frame.core :as rf]
            [clojure.string :as str]
            [vinary.stream.transport :as transport]
            [vinary.stream.sink :as sink]
            [vinary.stream.protocol :as proto]
            [vinary.ir.node :as node]
            [vinary.ir.frontend.log-stream :as log-stream]
            [vinary.renderer.markdown :as md]))

(def ^:private toc-cap 1000)                                  ; bound the outline so a log of errors can't grow it unboundedly
(def ^:private log-level-re #"(?i)\b(ERROR|WARN|WARNING|FATAL|CRITICAL)\b")

(defn- parser-for [kind]
  (case kind
    ("log" "text") (log-stream/parser)
    (log-stream/parser)))                                    ; markdown/pdf front-ends land in later phases

(defn- posts-for [kind] (case kind "markdown" md/apply-posts nil))
(defn- input-for [kind batch] (if (= "markdown" kind) (:text batch) (:lines batch)))

(defn- toc-entries
  "Bounded Contents entries for a batch of log records: those whose first line names a warning/error level."
  [blocks]
  (into []
        (comp (filter #(and (= :log-record (:role (node/node-meta %)))
                            (re-find log-level-re (or (some-> % node/children first node/text-content) ""))))
              (map (fn [rec] {:level 1
                              :text  (str/trim (subs (node/text-content rec) 0 (min 120 (count (node/text-content rec)))))
                              :id    (:id (node/node-meta rec))})))
        blocks))

(defn- ric [f]
  (if (exists? js/requestIdleCallback)
    ;; :timeout bounds idle starvation — the batch still fires within 100 ms even under sustained main-thread
    ;; load (or when the window is backgrounded and idle periods never arrive), so the stream always progresses.
    (js/requestIdleCallback f #js {:timeout 100})
    (js/requestAnimationFrame (fn [_] (f #js {:timeRemaining (fn [] 8)})))))

(defn start!
  "Begin streaming `path` (of `kind`) into DOM `node`. Returns a controller atom → pass to stop!. Progress is
   reported by the transport per batch (bytes/lines read over the total), so the byte size isn't needed here."
  [node path kind]
  (let [ctrl   (atom {:node node :path path :kind kind :destroyed? false :toc-n 0
                      :parser (parser-for kind) :posts (posts-for kind)
                      :q (atom (js/Promise.resolve nil)) :session nil})
        alive? (fn [] (not (:destroyed? @ctrl)))]
    (letfn [(emit-blocks [blocks]
              (let [{:keys [node q posts]} @ctrl]
                (sink/append-blocks! node q blocks alive? posts)
                (when (< (:toc-n @ctrl) toc-cap)
                  (let [entries (toc-entries blocks)
                        room    (- toc-cap (:toc-n @ctrl))
                        entries (vec (take room entries))]
                    (when (seq entries)
                      (swap! ctrl update :toc-n + (count entries))
                      (rf/dispatch [:stream/toc-append path entries]))))))
            (handle [batch]
              (let [{p :parser blocks :blocks} (proto/feed (:parser @ctrl) (input-for (:kind @ctrl) batch))]
                (swap! ctrl assoc :parser p)
                (emit-blocks blocks)
                (rf/dispatch [:stream/progress path (:progress batch)])
                (when (:done batch)
                  (emit-blocks (:blocks (proto/finish (:parser @ctrl))))
                  (rf/dispatch [:stream/done path]))))
            (tick [_]
              (when (alive?)
                (-> (transport/pull! (:session @ctrl))
                    (.then (fn [batch]
                             (when (alive?)
                               (handle batch)
                               (when-not (:done batch) (ric tick)))))
                    (.catch (fn [_] (rf/dispatch [:stream/done path]))))))]
      (-> (transport/open! path kind)
          (.then (fn [session] (when (alive?) (swap! ctrl assoc :session session) (ric tick))))
          (.catch (fn [e] (rf/dispatch [:content/error {:path path :message (str "stream error: " (.-message e))}]))))
      ctrl)))

(defn stop!
  "Cancel a stream: mark destroyed (in-flight appends bail via alive?) and release the main-process session."
  [ctrl]
  (when ctrl
    (swap! ctrl assoc :destroyed? true)
    (when-let [s (:session @ctrl)] (transport/close! s))))
