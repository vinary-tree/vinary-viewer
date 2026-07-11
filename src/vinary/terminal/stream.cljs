(ns vinary.terminal.stream
  "The sink-agnostic, CANCELLABLE terminal streaming engine: open a file as a line stream (content_service, in
   process), feed the WPDA log-stream StreamParser, and deliver completed :record blocks to `on-blocks` — paced by
   an injected scheduler. The CLI's on-blocks writes stdout; the TUI's appends to the viewport. One implementation of
   the open→pull→feed→emit→finish→close sequence (was duplicated + stdout-hard-wired in cli.core, and re-frame/DOM-
   coupled in stream.scheduler). The working set is bounded (the open record + the single WPDA config); the caller's
   sink bounds retention."
  (:require ["../main/content_service.js" :as cs]
            [vinary.ir.frontend.log-stream :as log-stream]
            [vinary.stream.protocol :as proto]))

(defn stream-records!
  "Stream `file`'s lines through the log parser. Opts:
     :on-blocks   (fn [blocks])      — completed :record IR blocks for this batch (required)
     :on-progress (fn [fraction])    — 0..1 read progress (optional)
     :on-done     (fn [])            — end of input, after the final flush (optional)
     :on-error    (fn [err])         — a stream/parse error (optional; else rethrown)
     :pace        (fn [thunk])       — schedule the next pull (default: synchronous/tight; TUI passes setImmediate)
   Returns a `stop!` fn that cancels the stream and closes the session (idempotent)."
  [file {:keys [on-blocks on-progress on-done on-error pace]}]
  (let [pace       (or pace (fn [f] (f)))
        stopped    (atom false)
        parser     (atom (log-stream/parser))
        sid*       (atom nil)                      ; streamOpen is async (remote sources stat/open over SFTP)
        close!     (fn [] (when-let [sid @sid*] (try (.streamClose cs #js {:sessionId sid}) (catch :default _ nil))))]
    (letfn [(finish! []
              (when-not @stopped
                (reset! stopped true)
                (let [blocks (:blocks (proto/finish @parser))]
                  (when (seq blocks) (on-blocks blocks)))
                (close!)
                (when on-done (on-done))))
            (pump []
              (when-not @stopped
                (-> (.streamPull cs #js {:sessionId @sid*})
                    (.then (fn [^js batch]
                             (when-not @stopped
                               (let [{p :parser blocks :blocks} (proto/feed @parser (vec (.-lines batch)))]
                                 (reset! parser p)
                                 (when (seq blocks) (on-blocks blocks))
                                 (when on-progress (on-progress (.-progress batch)))
                                 (if (.-done batch) (finish!) (pace pump))))))
                    (.catch (fn [e]
                              (when-not @stopped
                                (reset! stopped true)
                                (close!)
                                (if on-error (on-error e) (throw e))))))))]
      ;; open the session (async: a remote source stats + opens over SFTP), then start the pull loop
      (-> (.streamOpen cs #js {:path file :mode "lines"})
          (.then (fn [^js session]
                   (reset! sid* (.-sessionId session))
                   (when-not @stopped (pace pump))))
          (.catch (fn [e] (when-not @stopped (reset! stopped true)
                            (if on-error (on-error e) (throw e))))))
      (fn stop! [] (when-not @stopped (reset! stopped true) (close!))))))
