(ns vinary.stream.transport
  "Renderer-side pull client for the main-process document-stream sessions (window.vv.stream{Open,Pull,Close}).
   DOM-free — it only issues IPC Promises — so it is node-testable by injecting a mock IPC surface via
   `set-vv-provider!`. Double-buffers: after each batch resolves it prefetches the next, so the main-process
   pull latency overlaps the renderer's processing of the current batch. Bounded on both sides (credit ~1):
   main reads at most one batch ahead, the renderer holds at most one in-flight prefetch.")

;; the IPC surface — overridable in tests. In the app it is window.vv; the default is only invoked by
;; open!/pull!/close!, never at load time, so this ns loads cleanly under :node-test too.
(defonce ^:private vv-provider (atom (fn [] (.-vv js/window))))
(defn set-vv-provider! [f] (reset! vv-provider f))
(defn- vv [] ((deref vv-provider)))

(defn- mode-for [kind] (if (= kind "markdown") "bytes" "lines"))

(defn- issue-pull [session]
  (-> (.streamPull ^js (vv) #js {:sessionId (:id session)})
      (.then (fn [^js b]
               {:seq      (.-seq b)
                :done     (boolean (.-done b))
                :progress (or (.-progress b) 0)
                :lines    (when (.-lines b) (vec (.-lines b)))
                :text     (.-text b)
                ;; a mid-stream drop (a remote SSH source) ends the stream flagged partial with a message —
                ;; the scheduler surfaces this as a non-fatal note, keeping the already-committed blocks
                :error    (.-error b)
                :partial  (boolean (.-partial b))}))))

(defn open!
  "Open a stream session for `path` of `kind`. Returns Promise<session> where session is an opaque map
   {:id :size :mode + double-buffer atoms}."
  [path kind]
  (-> (.streamOpen ^js (vv) #js {:path path :mode (mode-for kind)})
      (.then (fn [^js s]
               {:id (.-sessionId s) :size (or (.-size s) 0) :mode (.-mode s)
                :next (atom nil) :done? (atom false)}))))

(defn pull!
  "Return Promise<batch> for the next batch, prefetching the following one (double-buffer). batch =
   {:seq :done :progress :lines?|:text?}. After a :done batch, further pulls are unnecessary."
  [session]
  (let [pending (or @(:next session) (issue-pull session))]
    (-> pending
        (.then (fn [batch]
                 (reset! (:done? session) (:done batch))
                 (reset! (:next session) (when-not (:done batch) (issue-pull session)))
                 batch)))))

(defn close!
  "Close the session (releases the main-process fd). Idempotent."
  [session]
  (when session
    (reset! (:next session) nil)
    (.streamClose ^js (vv) #js {:sessionId (:id session)})))
