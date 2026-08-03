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
            [vinary.async.scheduler :as sched :refer [ric]]
            [vinary.stream.transport :as transport]
            [vinary.stream.sink :as sink]
            [vinary.stream.protocol :as proto]
            [vinary.ir.node :as node]
            [vinary.ir.frontend.log-stream :as log-stream]
            [vinary.renderer.markdown :as md]))

(def ^:private toc-cap 1000)                                  ; bound the outline so a log of errors can't grow it unboundedly
(def ^:private log-level-re #"(?i)\b(ERROR|WARN|WARNING|FATAL|CRITICAL)\b")
(def ^:private md-drain-batch 48)                             ; top-level Markdown blocks committed per idle frame
;; …and per frame while find-materialize is rushing. Sized so a 1.1 MB document (≈8 000 blocks) lands in
;; ~10 frames rather than ~170, without any single frame becoming a visible stall.
(def ^:private md-rush-batch 768)

(defn- parser-for [kind]
  (case kind
    ("log" "text") (log-stream/parser)
    (log-stream/parser)))                                    ; markdown/pdf front-ends land in later phases

;; markdown/org reuse the batch string post-passes per block; pdf-reflow's batch view (markdown-body reflow-html)
;; runs NONE (reflow-html! is a bare lower), so its stream must skip them too for byte-parity; logs need none.
(defn- posts-for [kind] (case kind ("markdown" "org" "latex") md/apply-posts nil))
(defn- input-for [kind batch] (if (= "markdown" kind) (:text batch) (:lines batch)))
;; progressive kinds emit pre-rendered IR children whose whitespace/structure already carries every separator →
;; concatenate with ""; the transport (log/text) kinds carry no separators → the sink supplies one "\n".
(defn- sep-for [kind] (if (#{"markdown" "org" "latex" "pdf-reflow"} kind) "" "\n"))

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

;; `ric` — the visible/hidden-aware tick — now lives in vinary.async.scheduler, where the chunked find
;; uses it too. It was written here first; sharing it rather than copying it is the point of that
;; namespace.

(defn start!
  "Begin streaming `path` (of `kind`) into DOM `node`; returns a controller atom → pass to stop!. Two engines:

   • **transport engine** (log/text): pull bounded LINE batches from the main-process session, feed the WPDA
     StreamParser, append completed records. Bytes are not in renderer memory → genuinely bounded-memory.
   • **progressive engine** (markdown): the whole text is already in `:doc/text` (opts `:text`), so run the
     batch base-pipeline ONCE and commit the resulting top-level blocks across idle frames — a non-blocking,
     byte-parity-exact progressive paint (see md/stream-blocks, ADR-0018).

   `opts` carries `:text`/`:stamp` for the progressive engine (ignored by the transport engine)."
  [node path kind opts]
  (let [done-res (atom nil)
        done-p   (js/Promise. (fn [res _] (reset! done-res res)))   ; resolves once the whole stream has been fed
        ctrl   (atom {:node node :path path :kind kind :destroyed? false :toc-n 0
                      :parser (parser-for kind) :posts (posts-for kind)
                      :sep (sep-for kind)
                      :rush? (atom false)                          ; find-materialize: drain without idle pacing
                      :done-p done-p
                      :q (atom (js/Promise.resolve nil)) :started? (atom false) :session nil})
        alive? (fn [] (not (:destroyed? @ctrl)))
        finish! (fn [] (when-let [r @done-res] (r nil) (reset! done-res nil)) (rf/dispatch [:stream/done path]))]
    (letfn [(emit-blocks [blocks]
              (let [{:keys [node q posts started? sep]} @ctrl]
                (sink/append-blocks! node q blocks alive? posts started? sep)
                (when (< (:toc-n @ctrl) toc-cap)
                  (let [entries (toc-entries blocks)
                        room    (- toc-cap (:toc-n @ctrl))
                        entries (vec (take room entries))]
                    (when (seq entries)
                      (swap! ctrl update :toc-n + (count entries))
                      (rf/dispatch [:stream/toc-append path entries]))))))
            ;; ---- transport engine (log/text) ----
            (handle [batch]
              (let [{p :parser blocks :blocks} (proto/feed (:parser @ctrl) (input-for (:kind @ctrl) batch))]
                (swap! ctrl assoc :parser p)
                (emit-blocks blocks)
                (rf/dispatch [:stream/progress path (:progress batch)])
                (when (:done batch)
                  (emit-blocks (:blocks (proto/finish (:parser @ctrl))))
                  ;; a mid-stream drop (remote SSH): keep every committed block and show a NON-FATAL note — never
                  ;; :content/error, which views.cljs tests before :doc/streaming? and would blank the streamed DOM.
                  (when (:error batch)
                    (rf/dispatch [:stream/interrupted path (:error batch)]))
                  (finish!))))
            (tick [_]
              (when (alive?)
                (-> (transport/pull! (:session @ctrl))
                    (.then (fn [batch]
                             (when (alive?)
                               (handle batch)
                               ;; find-materialize rushes the pull loop (no idle wait between batches).
                               ;; Skipping `ric` here is safe in a way that skipping it in `drain` is not:
                               ;; every iteration still awaits `transport/pull!`, an IPC round trip whose
                               ;; reply arrives as a task, so the event loop — and therefore input — runs
                               ;; between batches regardless.
                               (when-not (:done batch) (if @(:rush? @ctrl) (tick nil) (ric tick))))))
                    (.catch (fn [_] (finish!))))))
            ;; ---- progressive engine (markdown) ----
            (drain [blocks total idx]
              (when (alive?)
                (if (>= idx total)
                  (finish!)
                  (let [rush? @(:rush? @ctrl)
                        end (min total (+ idx (if rush? md-rush-batch md-drain-batch)))]
                    (emit-blocks (subvec blocks idx end))
                    (rf/dispatch [:stream/progress path (/ end total)])
                    ;; Two paces, and which one is right depends on WHY we are committing.
                    ;;
                    ;; Ordinary streaming paints for a reader, so it paces to the display: `ric`, one
                    ;; modest batch per frame. Producing DOM faster than the screen can show it buys
                    ;; nothing.
                    ;;
                    ;; find-materialize is not painting for a reader — it is racing to get the whole
                    ;; document into the DOM so a search can cover it. So it takes a bigger batch AND the
                    ;; input-paced yield, which resumes on the next task instead of the next frame. What it
                    ;; must NOT do is what `rush?` used to do: recur straight into `drain`, committing
                    ;; every remaining block in a single task. On a 1.1 MB document that is a multi-second
                    ;; freeze, and it is triggered by the very first character typed into find.
                    (if rush?
                      (sched/yield! (fn [] (drain blocks total end)))
                      (ric (fn [_] (drain blocks total end))))))))
            ;; progressive engine (markdown, pdf-reflow): opts :blocks-fn is a 0-arg fn → Promise<{:blocks :toc
            ;; :assets}> rendered whole (full document context = byte-parity), committed across idle frames.
            (start-progressive []
              (-> ((:blocks-fn opts))
                  (.then (fn [{:keys [blocks toc assets]}]
                           (when (alive?)
                             (rf/dispatch [:stream/md-ready path toc assets])
                             (let [bs (vec blocks)]
                               (if (seq bs) (drain bs (count bs) 0) (rf/dispatch [:stream/done path]))))))
                  (.catch (fn [e] (rf/dispatch [:content/error {:path path :message (str "stream error: " (.-message e))}])))))]
      (if (:blocks-fn opts)
        (start-progressive)
        (-> (transport/open! path kind)
            ;; The heavy surface is mounted only after the tab chrome has painted. Pull the
            ;; first transport batch immediately after IPC open; later batches retain the
            ;; visible/idle pacing in `tick`.
            (.then (fn [session] (when (alive?) (swap! ctrl assoc :session session) (tick nil))))
            (.catch (fn [e] (rf/dispatch [:content/error {:path path :message (str "stream error: " (.-message e))}])))))
      ctrl)))

(defn stop!
  "Cancel a stream: mark destroyed (in-flight appends bail via alive?) and release the main-process session."
  [ctrl]
  (when ctrl
    (swap! ctrl assoc :destroyed? true)
    (when-let [s (:session @ctrl)] (transport/close! s))))

(defn when-settled
  "Promise resolved once the WHOLE document has been fed AND every queued append has landed in the DOM — with
   NO rushing (unlike materialize!). Used by the scroll re-anchor so it restores against the fully laid-out
   document. Immediately resolved when `ctrl` is nil."
  [ctrl]
  (if ctrl
    (-> ^js (:done-p @ctrl) (.then (fn [_] @(:q @ctrl))))
    (js/Promise.resolve nil)))

(defn materialize!
  "Fast-forward the stream to completion (the find-materialize hook, analogous to pdf ensure-all-text!): drop
   the idle pacing so the WHOLE document lands in the DOM, then resolve once every queued append has settled —
   so an in-page find run right after covers the entire document, not just the streamed-so-far prefix. Returns
   a Promise (immediately resolved when `ctrl` is nil or already torn down)."
  [ctrl]
  (if (and ctrl (not (:destroyed? @ctrl)))
    (do (reset! (:rush? @ctrl) true)
        (-> ^js (:done-p @ctrl) (.then (fn [_] @(:q @ctrl)))))
    (js/Promise.resolve nil)))
