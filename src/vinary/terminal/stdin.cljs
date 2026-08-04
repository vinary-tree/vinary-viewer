(ns vinary.terminal.stdin
  "Piped-stdin document intake — the shared drain/spill core (ADR-0036). Consumed by vv-cli and vv-tui
   (drain in-process, spill only where a real path is required) and by the Electron main daemon (the
   boot-time stale sweep). The GUI client's twin is the ~25-line drain/spill block in scripts/vv-open.mjs,
   which cannot import cljs bundles — the daemon-socket.mjs precedent; change the placement rules together.

   A piped document is a SNAPSHOT: stdin is drained to EOF (binary-safe — `cat x.pdf | vv -t pdf` works),
   then treated like an immutable file. Zero bytes means NO stdin document (`vv < /dev/null` behaves like
   plain `vv`), so type tokens shift to the named files. Spills land in a per-invocation uuid directory
   under the per-user runtime dir, private (0700/0600), unlinked when their document is released and
   age-swept at daemon boot for crashed invocations."
  (:require ["crypto" :as crypto]
            ["fs" :as fs]
            ["path" :as path]))

(defn piped?
  "Is a pipe (or any non-terminal) on stdin? TTY stdin is never drained."
  []
  (not (.-isTTY js/process.stdin)))

(defn read-all!
  "Drain process.stdin to one Buffer → Promise<Buffer|nil> (nil when stdin is a TTY or yielded zero
   bytes). Blocks until the producer closes its end — a piped document is a snapshot at EOF, by design
   (`tail -f x | vv` therefore waits forever; documented)."
  []
  (if-not (piped?)
    (js/Promise.resolve nil)
    (js/Promise.
     (fn [resolve reject]
       (let [chunks #js []]
         (.on js/process.stdin "data"  (fn [c] (.push chunks c)))
         (.on js/process.stdin "error" reject)
         (.on js/process.stdin "end"
              (fn []
                (let [buf (.concat js/Buffer chunks)]
                  (resolve (when (pos? (.-length buf)) buf))))))))))

(defn stdin-root
  "The per-user stdin spill directory: `$XDG_RUNTIME_DIR/vinary-viewer/stdin` (tmpfs, cleaned on logout),
   else a uid-qualified path under the temp dir — the same placement rules as the daemon socket
   (vinary.main.daemon/socket-path and scripts/daemon-socket.mjs)."
  []
  (let [rt (.. js/process -env -XDG_RUNTIME_DIR)]
    (if (and rt (not= rt ""))
      (path/join rt "vinary-viewer" "stdin")
      (path/join (or (.. js/process -env -TMPDIR) "/tmp")
                 (str "vinary-viewer-" (.getuid js/process)) "stdin"))))

(defn spill!
  "Write `buf` to a fresh private spill file `<stdin-root>/<uuid>/stdin` (dir 0700, file 0600) and return
   its absolute path. The basename `stdin` is what the tab label shows (uri/basename). `cleanup-on-exit?`
   (default true — vv-cli/vv-tui, whose spill dies with the process) registers a best-effort unlink on
   process exit; the GUI hand-off must NOT clean up (the resident daemon owns the file's lifetime)."
  ([buf] (spill! buf {:cleanup-on-exit? true}))
  ([buf {:keys [cleanup-on-exit?]}]
   (let [dir (path/join (stdin-root) (.randomUUID crypto))
         p   (path/join dir "stdin")]
     (.mkdirSync fs dir #js {:recursive true :mode 0700})
     (.writeFileSync fs p buf #js {:mode 0600})
     (when cleanup-on-exit?
       (.once js/process "exit"
              (fn []
                (try (.rmSync fs p #js {:force true}) (catch :default _ nil))
                (try (.rmdirSync fs dir) (catch :default _ nil)))))
     p)))

(defn sweep-stale!
  "Delete spill dirs older than `max-age-ms` (mtime of the uuid dir) — crashed/abandoned invocations. The
   age gate exists for one race: vv-open.mjs spills BEFORE spawning the daemon that runs this sweep, so a
   just-written file must never qualify (spill→send is sub-second; the gate is minutes). Errors are
   swallowed — a sweep must never break boot."
  [max-age-ms]
  (try
    (let [root (stdin-root)
          now  (js/Date.now)]
      (doseq [name (array-seq (.readdirSync fs root))]
        (let [dir (path/join root name)]
          (try
            (when (> (- now (.-mtimeMs (.statSync fs dir))) max-age-ms)
              (.rmSync fs dir #js {:recursive true :force true}))
            (catch :default _ nil)))))
    (catch :default _ nil)))
