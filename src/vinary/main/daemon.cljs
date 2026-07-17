(ns vinary.main.daemon
  "The resident-server IPC socket — a systemd-INDEPENDENT way to reach the warm process. Any primary (the
   single-instance lock holder) listens on a per-user Unix domain socket; `vv <file>` (scripts/vv-open.mjs)
   connects and sends the file paths, so the resident process opens them in a NEW window without a second cold
   start. It works with OR without systemd — systemd, if present, merely runs the process with `--daemon`; the
   `vv` launcher spawns `--daemon` itself when no socket is reachable. Message: one JSON line
   `{\"args\": [\"/abs/file\", \"https://…\"], \"cwd\": \"/abs\"}` terminated by the connection's end."
  (:require ["net" :as net]
            ["fs" :as fs]
            ["path" :as path]))

(defn socket-path
  "The daemon's Unix socket: `$XDG_RUNTIME_DIR/vinary-viewer.sock` (per-user, cleaned on logout), else a
   uid-qualified path under the temp dir. Shared verbatim with scripts/vv-open.mjs."
  []
  (let [rt (.. js/process -env -XDG_RUNTIME_DIR)]
    (if (and rt (not= rt ""))
      (path/join rt "vinary-viewer.sock")
      (path/join (or (.. js/process -env -TMPDIR) "/tmp") (str "vinary-viewer-" (.getuid js/process) ".sock")))))

(defn listen!
  "Start the daemon socket server. `on-open` is called with a vector of doc-uri args (already cwd-resolved by the
   client) for each connection. Returns the server (or nil if the socket could not be bound — e.g. a live daemon
   already owns it, which shouldn't happen since only the single-instance primary calls this)."
  [on-open]
  (let [sock (socket-path)]
    (try (.unlinkSync fs sock) (catch :default _ nil))    ; clear a stale socket left by an unclean exit
    (let [server (.createServer net
                   (fn [^js conn]
                     (let [chunks (atom "")]
                       (.on conn "data"  (fn [d] (swap! chunks str d)))
                       (.on conn "error" (fn [_] nil))
                       (.on conn "end"
                            (fn []
                              (try
                                ;; a valid message opens a window (empty args → a New-Tab window, like bare `vv`)
                                (on-open (vec (js->clj (.-args (js/JSON.parse @chunks)))))
                                (catch :default e
                                  (js/console.error "[vv daemon] bad request:" (str e)))))))))]
      (.on server "error" (fn [^js e] (js/console.error "[vv daemon] socket error:" (.-message e))))
      (.listen server sock)
      ;; best-effort socket cleanup so the next launch binds cleanly
      (let [cleanup (fn [] (try (.close server) (catch :default _ nil)) (try (.unlinkSync fs sock) (catch :default _ nil)))]
        (.on js/process "exit" cleanup)
        (.on js/process "SIGTERM" (fn [] (cleanup) (.exit js/process 0)))
        (.on js/process "SIGINT"  (fn [] (cleanup) (.exit js/process 0))))
      server)))
