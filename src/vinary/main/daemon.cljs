(ns vinary.main.daemon
  "The resident-server IPC socket — a systemd-INDEPENDENT way to reach the warm process. Any primary (the
   single-instance lock holder) listens on a per-user Unix domain socket; `vv <file>` (scripts/vv-open.mjs)
   connects and sends the file paths, so the resident process opens them in a NEW window without a second cold
   start. It works with OR without systemd — systemd, if present, merely runs the process with `--daemon`; the
   `vv` launcher spawns `--daemon` itself when no socket is reachable.

   TWO message shapes, one connection each (the client half-closes; the server replies, then ends):

     {\"args\": [\"/abs/file\", \"https://…\"], \"cwd\": \"/abs\"}   open — one JSON line, unchanged since v0.2
     vv1 ping                                                  control — status of the resident process
     vv1 stop                                                  control — quit gracefully (release lock + socket)

   The control frames are deliberately NOT valid JSON. A pre-`vv1` daemon parses every message with
   `JSON.parse` inside a try/catch and only opens a window on success, so it rejects a control frame as
   `bad request` and does nothing — where a JSON-shaped control message (no `args`) would have opened a
   stray empty window on it. That is what lets a NEW client safely probe an OLD daemon (scripts/vv-daemon.mjs
   detects the silence and falls back to terminating the socket owner by pid)."
  (:require [clojure.string :as str]
            ["net" :as net]
            ["fs" :as fs]
            ["path" :as path]))

(def ^:private control-prefix
  "Marks a control frame. Chosen so `JSON.parse` throws on it — see the ns docstring."
  "vv1 ")

(def ^:private stop-watchdog-ms
  "How long a graceful stop may take before the process is forced down. A wedged `before-quit` must never
   leave a primary holding the single-instance lock: the next launch would lose the lock race, quit with
   status 0, and the user would silently keep running the OLD build (the bug this protocol exists to fix)."
  5000)

(defn socket-path
  "The daemon's Unix socket: `$XDG_RUNTIME_DIR/vinary-viewer.sock` (per-user, cleaned on logout), else a
   uid-qualified path under the temp dir. Mirrored verbatim by scripts/daemon-socket.mjs (the one JS copy,
   imported by both scripts/vv-open.mjs and scripts/vv-daemon.mjs) — change the two together."
  []
  (let [rt (.. js/process -env -XDG_RUNTIME_DIR)]
    (if (and rt (not= rt ""))
      (path/join rt "vinary-viewer.sock")
      (path/join (or (.. js/process -env -TMPDIR) "/tmp") (str "vinary-viewer-" (.getuid js/process) ".sock")))))

(defn- end! [^js conn payload]
  (try (if payload (.end conn (str payload "\n")) (.end conn)) (catch :default _ nil)))

(defn- status-js
  "The `ping`/`stop` reply. Built here, in ONE place, so the wire contract (camelCase, consumed by
   scripts/vv-daemon.mjs) is stated once and does not depend on how the caller spells its status map."
  [status]
  (let [{:keys [pid version bundle-mtime-ms windows daemon?]} (when status (status))]
    #js {:ok            true
         :pid           pid
         :version       version
         :bundleMtimeMs bundle-mtime-ms
         :windows       windows
         :daemon        (boolean daemon?)}))

(defn- stop!
  "Quit the resident process. `on-stop` is the graceful path (core hands us `app.quit`, so `before-quit`
   runs: SSH pools torn down, window bounds persisted); the watchdog is the guarantee that it terminates."
  [on-stop]
  (try (when on-stop (on-stop))
       (catch :default e (js/console.error "[vv daemon] stop failed:" (str e))))
  (doto ^js (js/setTimeout (fn [] (.exit js/process 0)) stop-watchdog-ms)
    (.unref)))                          ; never hold the event loop open just for the watchdog

(defn- handle!
  "Dispatch one complete message. Every path ends the connection, so a client that waits for `close`
   (scripts/vv-open.mjs) is never left hanging — the server is created with `allowHalfOpen` so it can
   still reply after the client's FIN."
  [^js conn msg {:keys [on-open on-stop status]}]
  (if (str/starts-with? msg control-prefix)
    (let [cmd (str/trim (subs msg (count control-prefix)))]
      (case cmd
        "ping" (end! conn (js/JSON.stringify (status-js status)))
        "stop" (do (end! conn (js/JSON.stringify (status-js status)))
                   ;; next tick, so the reply is flushed before the process starts tearing down
                   (js/setTimeout #(stop! on-stop) 0))
        (end! conn (js/JSON.stringify #js {:ok false :error (str "unknown command: " cmd)}))))
    (try
      ;; a valid open message: {"args": [...], "instanceId": "..."} — empty args = a New-Tab window, like
      ;; bare `vv`. `instanceId` is the launch's idempotency key: the daemon opens at most one window per id,
      ;; so redundant signals of one `vinary-viewer` invocation (socket + second-instance racing) coalesce to
      ;; a single window at the source. A pre-instance-id client simply omits the field (id → nil).
      (let [^js parsed (js/JSON.parse msg)]
        (on-open {:args        (vec (js->clj (.-args parsed)))
                  :instance-id (not-empty (.-instanceId parsed))}))
      (end! conn nil)
      (catch :default e
        (js/console.error "[vv daemon] bad request:" (str e))
        (end! conn nil)))))

(defn listen!
  "Start the daemon socket server. Handlers: `:on-open` receives `{:args [...] :instance-id \"…\"|nil}` for
   each open message (args are cwd-resolved by the client; instance-id is the launch idempotency key);
   `:on-stop` quits the process for `vv1 stop` and for
   SIGTERM/SIGINT; `:status` returns {:pid :version :bundle-mtime-ms :windows :daemon?} for `vv1 ping`.
   Returns the server (or nil if the socket could not be bound — e.g. a live daemon already owns it, which
   shouldn't happen since only the single-instance primary calls this)."
  [{:keys [on-stop] :as handlers}]
  (let [sock (socket-path)]
    (try (.unlinkSync fs sock) (catch :default _ nil))    ; clear a stale socket left by an unclean exit
    (let [server (.createServer net
                   ;; allowHalfOpen: the client half-closes to frame its message, and a control frame is
                   ;; answered AFTER that FIN — without this, node ends the writable side automatically on
                   ;; `end` and the reply would be a write-after-end. Every path in handle! closes the conn.
                   #js {:allowHalfOpen true}
                   (fn [^js conn]
                     (let [chunks (atom "")]
                       (.on conn "data"  (fn [d] (swap! chunks str d)))
                       (.on conn "error" (fn [_] nil))
                       (.on conn "end"   (fn [] (handle! conn @chunks handlers))))))]
      (.on server "error" (fn [^js e] (js/console.error "[vv daemon] socket error:" (.-message e))))
      (.listen server sock)
      ;; best-effort socket cleanup so the next launch binds cleanly
      (let [cleanup (fn [] (try (.close server) (catch :default _ nil)) (try (.unlinkSync fs sock) (catch :default _ nil)))]
        (.on js/process "exit" cleanup)
        ;; SIGTERM/SIGINT (systemctl restart, launchctl kickstart -k, Ctrl-C) take the SAME graceful path as
        ;; `vv1 stop` — they used to `process.exit(0)` outright, skipping before-quit (SSH pools, window bounds).
        (.on js/process "SIGTERM" (fn [] (stop! on-stop)))
        (.on js/process "SIGINT"  (fn [] (stop! on-stop))))
      server)))
