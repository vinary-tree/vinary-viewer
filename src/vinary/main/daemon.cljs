(ns vinary.main.daemon
  "The resident-server IPC socket — a systemd-INDEPENDENT way to reach the warm process. A primary (a
   single-instance lock holder) listens on a per-user Unix domain socket — the first to publish it, until it
   quits (see socket ownership, below); `vv <file>` (scripts/vv-open.mjs)
   connects and sends the file paths, so the resident process opens them in a NEW window without a second cold
   start. It works with OR without systemd — systemd, if present, merely runs the process with `--daemon`; the
   `vv` launcher spawns `--daemon` itself when no socket is reachable.

   TWO message shapes, one connection each (the client half-closes; the server replies, then ends):

     {\"args\": [\"/abs/file\", \"sftp://…\"],       open — one JSON line. v2 (ADR-0036) ADDS optional
      \"types\": [\"diff\", …],                     fields: `types` (raw `-t` tokens, flag order — the Nth
      \"stdinIndex\": 0, \"cwd\": \"/abs\",           type applies to the Nth arg), `stdinIndex` (which arg
      \"instanceId\": \"…\"}                        is the spilled stdin document), `cwd` (the invoking
                                                 dir, for stdin diff enrichment). A legacy client omits
                                                 them; a legacy daemon ignores them — both skews stay
                                                 well-formed. The open path replies ONLY on rejection
                                                 (one JSON line {\"ok\":false,\"error\":\"vv: …\"}), so a
                                                 legacy client — which reads nothing — sees no change.
     vv1 ping                                    control — status of the resident process
     vv1 stop                                    control — quit gracefully (release lock + socket)

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
      ;; v2 (ADR-0036): optional `types`/`stdinIndex`/`cwd` ride along (all nil-safe for legacy clients).
      ;; `on-open` may return {:error "vv: …"} — an invalid open (unknown type, mispaired types) — which is
      ;; written back as the ONLY open-path reply so the new client can print it to the invoking terminal;
      ;; a valid open still ends the connection silently (what a legacy client expects).
      (let [^js parsed (js/JSON.parse msg)
            result     (on-open {:args        (vec (js->clj (.-args parsed)))
                                 :types       (vec (js->clj (or (.-types parsed) #js [])))
                                 :stdin-index (let [i (.-stdinIndex parsed)] (when (number? i) i))
                                 :cwd         (not-empty (.-cwd parsed))
                                 :instance-id (not-empty (.-instanceId parsed))})]
        (if-let [err (:error result)]
          (end! conn (js/JSON.stringify #js {:ok false :error (str err)}))
          (end! conn nil)))
      (catch :default e
        (js/console.error "[vv daemon] bad request:" (str e))
        (end! conn nil)))))

;; ── socket ownership ──────────────────────────────────────────────────────────────────────────────────────
;; The single-instance lock is per userData directory, and userData follows $XDG_CONFIG_HOME, so two primaries
;; CAN run at once — a test harness or a second profile beside the user's daemon. The socket path is per USER,
;; so it needs an ownership rule of its own: the instance that published the socket owns it until it quits;
;; another primary probes it and, finding it answered, runs WITHOUT the socket. Only a socket nobody answers on
;; (the leftover of an unclean exit) is ever removed, and only the inode that failed the probe. This replaced an
;; unconditional unlink-then-bind that let any second primary take a live daemon's socket: the daemon kept
;; listening on an inode no path named, and `vv` could not reach it again until it restarted.

(def ^:private probe-timeout-ms
  "How long the ownership probe keeps its connection to an owner that never answers `vv1 ping` (connecting at
   all already proved the owner live; the answer is not needed)."
  2000)

(defonce ^:private published
  ;; {:path :ino :server} — the socket THIS process published, or nil
  (atom nil))

(defonce ^:private hooks-installed? (atom false))

(defn- inode-of
  "The inode at `p` (not following a symlink), or nil when nothing is there."
  [p]
  (try (.-ino (.lstatSync fs p)) (catch :default _ nil)))

(defn- probe
  "Who answers at the socket path? Resolves to
     :live     a server accepted the connection — another running instance owns the socket. It is sent
               `vv1 ping`, so a current daemon answers it and logs nothing (an empty connection would log
               `bad request` in the owner);
     :stale    nothing listens (ECONNREFUSED) — the leftover of an unclean exit (SIGKILL, a crash, app.exit);
     :absent   no file (ENOENT);
     :unknown  anything else (EACCES, a path longer than sun_path, a full backlog…) — leave the path alone."
  [sock]
  (js/Promise.
   (fn [resolve _]
     (let [^js c (.connect net sock)
           ^js t (js/setTimeout (fn [] (.destroy c)) probe-timeout-ms)]
       (.unref t)
       (.on c "connect" (fn [] (resolve :live) (.end c (str control-prefix "ping\n"))))
       (.on c "data" (fn [_] nil))
       (.on c "close" (fn [_] (js/clearTimeout t)))
       (.on c "error" (fn [^js e]
                        (resolve (case (.-code e) "ECONNREFUSED" :stale "ENOENT" :absent :unknown))))))))

(defn- remove-stale!
  "Unlink the stale socket at `sock` — only if it is STILL the socket inode `ino` that refused the probe, so a
   socket another instance has bound since is never removed. True when the path is now free to bind. (POSIX
   has no unlink-if-inode; what remains is the microseconds between this check and the unlink.)"
  [sock ino]
  (let [^js st (try (.lstatSync fs sock) (catch :default _ nil))]
    (cond
      (nil? st) true                                      ; gone since the probe: free
      (and (.isSocket st) (= ino (.-ino st))) (do (try (.unlinkSync fs sock) (catch :default _ nil)) true)
      :else (do (js/console.warn "[vv daemon]" sock "changed since the probe or is not a socket — left untouched")
                false))))

(defn- bind!
  "Listen on `sock`. bind(2) fails with EADDRINUSE rather than replace an existing path, so a socket another
   instance published since the probe is never taken over. Resolves to the server, or nil."
  [sock handlers]
  (js/Promise.
   (fn [resolve _]
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
       (.on server "error" (fn [^js e]
                             (js/console.error "[vv daemon] socket error:" (.-message e))
                             (resolve nil)))
       (.listen server sock
                (fn []
                  (reset! published {:path sock :ino (inode-of sock) :server server})
                  (resolve server)))))))

(defn release!
  "Withdraw the socket this process published, so the next launch binds cleanly. Only while the path still
   names OUR socket's inode: the server is closed — libuv unlinks the name it bound — and the path is unlinked
   explicitly as well. A path that now names another socket is left alone. Idempotent. core calls it at
   `will-quit`, BEFORE Electron releases the single-instance lock, so a successor can never find this socket
   still answering and stand down; the process `exit` hook covers app.exit and the stop watchdog.
   Residual (measured, docs/architecture/03 §6): Electron's shutdown closes every handle still open — after
   app.quit, and after app.exit once the process has had a window — and libuv unlinks a server's bound name on
   close without checking what the name now holds. That can only remove another instance's socket if ours was
   first deleted from outside and that instance then bound the name."
  []
  (when-let [{:keys [path ino ^js server]} @published]
    (reset! published nil)
    (when (and (some? ino) (= ino (inode-of path)))
      (try (.close server) (catch :default _ nil))
      (try (.unlinkSync fs path) (catch :default _ nil)))))

(defn- install-process-hooks!
  "Once per process, whether or not it owns the socket: release the socket at exit, and let SIGTERM/SIGINT
   (systemctl restart, launchctl kickstart -k, Ctrl-C) take the SAME graceful path as `vv1 stop` — they used to
   `process.exit(0)` outright, skipping before-quit (SSH pools, window bounds)."
  [on-stop]
  (when (compare-and-set! hooks-installed? false true)
    (.on js/process "exit" (fn [] (release!)))
    (.on js/process "SIGTERM" (fn [] (stop! on-stop)))
    (.on js/process "SIGINT"  (fn [] (stop! on-stop)))))

(defn listen!
  "Start the daemon socket server. Handlers: `:on-open` receives
   `{:args [...] :types [...] :stdin-index int|nil :cwd \"…\"|nil :instance-id \"…\"|nil}` for each open
   message (args are cwd-resolved by the client; types/stdin-index/cwd are the ADR-0036 open grammar, nil
   for a legacy client; instance-id is the launch idempotency key) and may return {:error \"vv: …\"} to
   reject the open — the error is written back to the client as the connection's only reply;
   `:on-stop` quits the process for `vv1 stop` and for
   SIGTERM/SIGINT; `:status` returns {:pid :version :bundle-mtime-ms :windows :daemon?} for `vv1 ping`.
   The socket is probed first (see `probe`): an absent path is bound, a stale socket is replaced, and a socket
   another running instance answers on is left to it. Resolves to the server, or nil when this process does
   not serve the socket (another instance owns it, a bind race was lost, or the path cannot be used) — it then
   runs as an ordinary primary that `vv` does not reach."
  [{:keys [on-stop] :as handlers}]
  (install-process-hooks! on-stop)
  (let [sock (socket-path)
        ino  (inode-of sock)]
    (-> (probe sock)
        (.then (fn [state]
                 (case state
                   :absent (bind! sock handlers)
                   :stale  (if (remove-stale! sock ino) (bind! sock handlers) nil)
                   :live   (do (js/console.warn "[vv daemon] another running instance serves" sock
                                                "— this one runs without the socket")
                               nil)
                   (do (js/console.warn "[vv daemon] cannot probe" sock "— left untouched") nil)))))))
