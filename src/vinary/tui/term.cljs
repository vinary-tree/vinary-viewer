(ns vinary.tui.term
  "The raw-terminal driver — the ONLY impure, Node-coupled TUI module. Owns the alternate screen, raw mode, cursor
   hiding, and bracketed-paste enable, and — the safety crux — a teardown that returns the terminal to normal on
   EVERY exit path so a crash / SIGINT / SSH-drop / Ctrl-Z never leaves a wedged (raw-mode, cursor-hidden,
   alt-screen) shell. Teardown notes (Node semantics, per the red-team): `process.on('exit')` does NOT fire on a
   signal kill, so SIGINT/TERM/HUP/QUIT get explicit handlers; the restore is written with fs.writeSync (process.exit
   truncates async stdout, dropping an ordinary write); setRawMode throws off a TTY, so it is guarded by isTTY + a
   was-raw flag; SIGKILL/SIGSTOP are uncatchable — the acknowledged residual. `--no-tty` (the test seam) skips raw
   mode / alt-screen entirely and just reads stdin."
  (:require ["fs" :as fs]))

(def ^:private ESC (str (char 27)))
(defn- csi [s] (str ESC "[" s))
;; enter: alt-screen + hide cursor + bracketed paste on;  leave: paste off + show cursor + leave alt-screen
(def ^:private enter-seq (str (csi "?1049h") (csi "?25l") (csi "?2004h") (csi "2J") (csi "H")))
(def ^:private leave-seq (str (csi "?2004l") (csi "?25h") (csi "?1049l")))

(defonce ^:private st (atom {:raw? false :active? false :done? false :no-tty? false :on-resume nil :input nil}))

(defn stdin-tty? [] (boolean (and js/process.stdin (.-isTTY js/process.stdin))))

;; The ACTIVE key-input stream. Default: process.stdin. A piped-stdin document (ADR-0036) hands the pipe
;; to the document loader and reopens /dev/tty for keys — that tty.ReadStream arrives via init! :input,
;; and every raw-mode enter/leave/resume/data binding below operates on it instead of process.stdin.
(defn- input-stream ^js [] (or (:input @st) js/process.stdin))
(defn- input-tty? [] (let [^js in (input-stream)] (boolean (and in (.-isTTY in)))))

(defn size []
  (let [o js/process.stdout]
    {:w (or (and o (pos? (or (.-columns o) 0)) (.-columns o)) 80)
     :h (or (and o (pos? (or (.-rows o) 0)) (.-rows o)) 24)}))

(defn write! [s] (when-not (:done? @st) (.write js/process.stdout s)))

(defn- write-sync! [s] (try (.writeSync fs 1 s) (catch :default _ nil)))

(defn- leave!
  "Return the terminal to normal WITHOUT latching done (so SIGTSTP can re-enter). fs.writeSync + guards."
  []
  (when (and (:active? @st) (not (:no-tty? @st)))
    (write-sync! leave-seq)
    (when (and (:raw? @st) (input-tty?))
      (try (let [^js in (input-stream)] (.setRawMode in false)) (catch :default _ nil)))))

(defn- enter! []
  (when-not (:no-tty? @st)
    (when (input-tty?)
      (try (let [^js in (input-stream)] (.setRawMode in true) (swap! st assoc :raw? true))
           (catch :default _ nil)))
    (write-sync! enter-seq)))

(defn restore!
  "Idempotent, exactly-once terminal restore (the final teardown). Safe to call from any handler."
  []
  (when (and (:active? @st) (not (:done? @st)))
    (swap! st assoc :done? true)
    (leave!)))

(defn- exit-with [code] (restore!) (js/process.exit code))

(defn- install-handlers! []
  (.on js/process "exit" (fn [_] (restore!)))                 ; normal exit (NOT signal kills — those below)
  (doseq [[sig code] [["SIGINT" 130] ["SIGTERM" 143] ["SIGHUP" 129] ["SIGQUIT" 131]]]
    (.on js/process sig (fn [] (exit-with code))))
  (.on js/process "uncaughtException"  (fn [e] (restore!) (js/console.error e) (js/process.exit 1)))
  (.on js/process "unhandledRejection" (fn [e] (restore!) (js/console.error e) (js/process.exit 1)))
  ;; Ctrl-Z: restore, then re-raise the default suspend; on resume, re-enter raw/alt-screen + repaint
  (.on js/process "SIGCONT" (fn []
                              (when-not (:done? @st)
                                (enter!)
                                (when-let [f (:on-resume @st)] (f)))))
  (.on js/process "SIGTSTP" (fn []
                              (when-not (:done? @st)
                                (leave!)
                                (.removeAllListeners js/process "SIGTSTP")
                                (js/process.kill (.-pid js/process) "SIGTSTP")))))

(defn init!
  "Enter the full-screen raw terminal and wire input. opts:
     :on-key    (fn [buf])   — raw key bytes (a Node Buffer)
     :on-resize (fn [])      — SIGWINCH
     :on-resume (fn [])      — repaint after a Ctrl-Z / SIGCONT resume
     :no-tty?   bool         — skip raw mode + alt-screen (the --drive test seam); input is still read
     :input     stream       — the key-input stream (default process.stdin). A piped-stdin document
                               passes a /dev/tty tty.ReadStream here (ADR-0036) so the pipe can carry
                               the DOCUMENT while the terminal still carries the KEYS.
   Returns nil; call `restore!` (or exit) to tear down."
  [{:keys [on-key on-resize on-resume no-tty? input]}]
  (swap! st assoc :active? true :no-tty? (boolean no-tty?) :on-resume on-resume :done? false :input input)
  (enter!)
  (install-handlers!)
  (when-let [^js in (input-stream)]
    (when (and (not no-tty?) (input-tty?)) (.resume in))
    (.on in "data" (fn [buf] (when (and on-key (not (:done? @st))) (on-key buf)))))
  (when (and on-resize js/process.stdout)
    (.on js/process.stdout "resize" (fn [] (when-not (:done? @st) (on-resize)))))
  nil)

;; cursor to (row,col) 1-based; a full clear; clear to end of line
(defn cursor [row col] (csi (str row ";" col "H")))
(defn clear-eol [] (csi "K"))
(defn home [] (csi "H"))
