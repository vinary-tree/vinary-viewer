(ns vinary.tui.core
  "vv-tui — the interactive full-screen terminal viewer. Wires the raw-terminal driver (vinary.tui.term) → key
   parser (vinary.tui.keys) → pure reducer (vinary.tui.state) and paints the viewport/find/toc models, reusing the
   CLI's IR front-ends + the shared WPDA streaming engine (vinary.terminal.stream). Graphics are forced OFF (images
   → placeholder lines) so the scrolling viewport is line-exact. Batch docs retain their IR so SIGWINCH re-wraps at
   the new width without re-reading; large logs stream into a bounded viewport ring. `--drive <keyfile>` (with
   --no-tty) replays keys through the SAME keys→state→frame pipeline and dumps the final frame deterministically —
   the headless test seam that needs no pseudo-tty."
  (:require ["../main/content_service.js" :as cs]
            ["path" :as path]
            ["fs" :as fs]
            ["tty" :as tty]
            [clojure.string :as str]
            [vinary.cli.render :as render]
            ;; eager (non-lazy) population of the shared renderer.heavy-registry — Node has no shadow.lazy, so
            ;; unified-latex/uniorg are bundled + wired at startup (see main) instead of code-split like the renderer.
            [vinary.renderer.heavy-node :as heavy-node]
            [vinary.terminal.caps :as caps]
            [vinary.terminal.stdin :as stdin]
            [vinary.terminal.syntax :as tsyntax]
            [vinary.terminal.stream :as tstream]
            [vinary.file-type :as ft]
            [vinary.grammar-catalog :as gc]
            [vinary.tui.term :as term]
            [vinary.tui.keys :as keys]
            [vinary.tui.state :as state]
            [vinary.tui.viewport :as vp]
            [vinary.tui.find :as find]
            [vinary.tui.toc :as toc]))

(def ^:private version "vv --tui 0.3.0")
(def ^:private usage
  (str/join "\n"
    ["vv --tui — interactively page a document in the terminal"
     "" "Usage: vv --tui [options] <file>"
     "       git diff | vv --tui -t diff     page a piped document (keys reopen on /dev/tty)" ""
     "Keys:  ↑/k ↓/j scroll · Space/b page · g/G top/bottom · / find (n/N next/prev) · t contents · q quit" ""
     "Options:"
     "  -t, --type TYPE     file type: a MIME type (text/x-diff), a short alias (diff, md, csv, log), or"
     "                      a grammar language (python, rust); --file-type is an accepted alias. Without"
     "                      it the type deduces from the extension, else plain text."
     "      --width N       wrap column (default: terminal width)"
     "      --no-color      disable ANSI colour"
     "      --drive FILE    (test) replay key bytes from FILE headlessly and dump the final frame"
     "  -h, --help          show this help"
     "  -V, --version       show the version"]))

(def ^:private stream-threshold (* 5 1024 1024))
(def ^:private stream-cap 100000)                          ; bounded viewport ring for a streamed log
(def ^:private text-kinds #{"markdown" "org" "latex" "text" "log" "mermaid" "html" "source"})

(defn- parse-args [args]
  (loop [as args file nil opts {}]
    (if (empty? as)
      {:file file :opts opts}
      (let [a (first as)]
        (cond
          (#{"-h" "--help"} a)    (recur (rest as) file (assoc opts :help true))
          (#{"-V" "--version"} a) (recur (rest as) file (assoc opts :version true))
          (= "--no-color" a)      (recur (rest as) file (assoc opts :no-color true))
          (= "--width" a)         (recur (drop 2 as) file (assoc opts :width (js/parseInt (or (second as) "80"))))
          (= "--drive" a)         (recur (drop 2 as) file (assoc opts :drive (second as)))
          (str/starts-with? a "-") (recur (rest as) file opts)               ; ignore unknown flags
          :else                    (recur (rest as) (or file a) opts))))))    ; first non-flag arg is the file

(defn- ansi-opts [opts w]
  (let [c (caps/detect (assoc opts :force-color (when (:drive opts) true)))]
    {:width      (or (:width opts) w (:width c))
     :color?     (if (:no-color opts) false (:color? c))
     :truecolor? (:truecolor? c)
     :hyperlinks? false
     :graphics   nil                                        ; the scrolling TUI uses placeholders, never inline images
     :highlight  (when (and (not (:no-color opts)) (:color? c)) (tsyntax/highlighter))
     :image      nil}))

(defn- kind-of [file]
  (let [k0 (.classifyName cs file)]
    (if (and (= "text" k0) (gc/grammar-for-path file gc/bundled-grammars {})) "source" k0)))

(defn- effective-kind
  "The kind a spec renders as: the explicit type (ADR-0036) wins; a bare stdin document is plain text (an
   extensionless snapshot); everything else classifies by name with the text→source grammar upgrade."
  [{:keys [uri kind stdin?]}]
  (or kind (if stdin? "text" (kind-of uri))))

(defn- big? [file] (> (.-size (.statSync fs file)) stream-threshold))

;; ── loading a document → {:lines :anchors :toc :ir :base-dir :streaming? :file} ──────
(defn- load-batch [payload aopts base-dir]
  (-> (render/render-doc payload aopts)
      (.then (fn [{:keys [ir toc lines anchors]}]
               {:lines lines :anchors anchors :toc toc :ir ir :base-dir base-dir :streaming? false}))))

(defn- load-doc
  "Promise<{:lines :anchors :toc :ir? :base-dir :streaming? :file}> for one resolved open spec
   ({:uri :kind? :language? :delimiter? :stdin?} — vinary.file-type). Text kinds read directly (bypassing
   content_service content-sniffing); a large log/text streams (empty initial lines, filled by the
   stream — :file is the READABLE source path, which for a large piped document is its spill). A piped
   snapshot renders straight from `buf`, spilling only where a real path is required (the stream engine,
   the image port, the table parser); its base-dir is the invoking cwd."
  [{:keys [uri language delimiter stdin?] :as spec} buf aopts]
  (let [kind      (effective-kind spec)
        explicit  (some? (:kind spec))
        base-dir  (if stdin? (.cwd js/process) (some->> uri (.dirname path)))
        with-lang (fn [payload] (cond-> payload language (assoc :language language)))
        cs-opts   (clj->js (cond-> {}
                             explicit  (assoc :explicit true)
                             delimiter (assoc :delimiter delimiter)))]
    (cond
      stdin?
      (cond
        (= "pdf" kind)   (load-batch {:kind "pdf" :path uri :bytes buf} aopts base-dir)
        (= "image" kind) (load-batch {:kind "image" :path (stdin/spill! buf)} aopts base-dir)
        (and (#{"log" "text"} kind) (> (.-length buf) stream-threshold))
        (js/Promise.resolve {:lines [] :anchors {} :toc [] :ir nil :base-dir base-dir :streaming? true
                             :file (stdin/spill! buf)})
        (= "table" kind)
        (-> (.openUri cs (stdin/spill! buf) kind cs-opts)
            (.then (fn [p] (load-batch (with-lang (js->clj p :keywordize-keys true)) aopts base-dir))))
        :else (load-batch (with-lang {:kind kind :path uri :text (.toString buf "utf8")}) aopts base-dir))
      (and (#{"log" "text"} kind) (big? uri))
      (js/Promise.resolve {:lines [] :anchors {} :toc [] :ir nil :base-dir base-dir :streaming? true :file uri})
      (contains? text-kinds kind)
      (load-batch (with-lang {:kind kind :path uri :text (.readFileSync fs uri "utf8")}) aopts base-dir)
      (= "image" kind)
      (load-batch {:kind "image" :path uri} aopts base-dir)
      (= "pdf" kind)
      (load-batch {:kind "pdf" :path uri :bytes (.readFileSync fs uri)} aopts base-dir)
      :else
      ;; office/table/log-paged/archive/directory/remote — content_service parses them; an explicit kind
      ;; threads through (openLocal treats its presence as authoritative; openRemoteUri via opts.explicit).
      (-> (.openUri cs uri (when explicit kind) cs-opts)
          (.then (fn [p] (load-batch (with-lang (js->clj p :keywordize-keys true)) aopts base-dir)))))))

;; ── frame composition (pure over the state + size) ────────────────────────────
(defn- body-rows [st w body-h]
  (if (= :toc (:mode st))
    (toc/overlay-lines (:toc st) w body-h)
    (let [{:keys [slice top]} (vp/visible (assoc (:vp st) :h body-h))
          f (:find st)]
      (map-indexed (fn [i line] (if f (find/highlight line (find/line-spans f (+ top i))) line)) slice))))

(defn- status-row [st name total body-h]
  (let [top (get-in st [:vp :top]) dropped (get-in st [:vp :dropped] 0)
        pos (str (min total (+ top body-h)) "/" total)]
    (case (:mode st)
      :find (str "/" (:query st))
      :toc  "  ↑/↓ select · Enter jump · t/Esc close · q quit"
      (str " " name "  " pos (when (pos? dropped) (str "  (+" dropped " earlier)"))
           "  —  j/k ↑↓ · Space page · / find · t toc · q quit"))))

(defn- compose-frame [st w h name]
  (let [body-h (max 1 (dec h))
        rows   (vec (body-rows st w body-h))
        rows   (into rows (repeat (max 0 (- body-h (count rows))) ""))
        all    (conj (subvec rows 0 body-h) (status-row st name (get-in st [:vp :total] (count (get-in st [:vp :lines]))) body-h))]
    (str (term/home)
         (str/join "" (map-indexed (fn [r ln] (str (term/cursor (inc r) 1) (term/clear-eol) ln)) all)))))

;; ── the interactive app ───────────────────────────────────────────────────────
;; `key-input` (nil = process.stdin) is the /dev/tty stream a piped-stdin document reopens for keys.
(defn- run-interactive [doc file opts key-input]
  (let [{:keys [w h]} (term/size)
        aopts   (atom (ansi-opts opts w))
        name    (.basename path file)
        ir      (atom (:ir doc))
        base    (:base-dir doc)
        st      (atom (state/init (-> (vp/viewport w (max 1 (dec h)) (when (:streaming? doc) stream-cap))
                                      (vp/set-lines (:lines doc)))
                                  (toc/build (:toc doc) (:anchors doc))))
        pending (atom [])                                   ; keys.feed partial-escape buffer
        esc-timer (atom nil)
        stop-stream (atom nil)
        painting (atom false)
        paint! (fn [] (when-not @painting
                        (reset! painting true)
                        (js/queueMicrotask (fn [] (reset! painting false)
                                             (let [{:keys [w h]} (term/size)]
                                               (term/write! (compose-frame @st w h name)))))))
        apply-ev (fn [ev]
                   (swap! st state/step ev)
                   (when (:quit? @st)
                     (when-let [s @stop-stream] (s))
                     (term/restore!) (js/process.exit 0))
                   (paint!))
        on-key (fn [buf]
                 (let [[p evs] (keys/feed @pending (js/Array.from buf))]
                   (reset! pending p)
                   (doseq [ev evs] (apply-ev ev))
                   ;; lone-ESC flush: if a bare ESC is held, emit it as :escape after a short delay
                   (when @esc-timer (js/clearTimeout @esc-timer))
                   (when (seq @pending)
                     (reset! esc-timer (js/setTimeout (fn []
                                                        (let [[p2 evs2] (keys/flush @pending)]
                                                          (reset! pending p2)
                                                          (doseq [ev evs2] (apply-ev ev)))) 40)))))
        relayout (fn []                                     ; SIGWINCH: re-wrap batch docs at the new width; re-slice logs
                   (let [{:keys [w h]} (term/size)
                         body-h (max 1 (dec h))]
                     (if (and @ir (not= w (:width @aopts)))
                       (let [_ (swap! aopts assoc :width w)
                             {:keys [lines anchors]} (render/render-ir-lines @ir @aopts base)]
                         (swap! st state/resize w body-h lines (toc/build (:toc doc) anchors)))
                       (swap! st update :vp vp/resize w body-h (get-in @st [:vp :lines])))
                     (paint!)))
        resize-timer (atom nil)
        on-resize (fn [] (when @resize-timer (js/clearTimeout @resize-timer))
                    (reset! resize-timer (js/setTimeout relayout 90)))]   ; debounce a SIGWINCH storm
    ;; stream a large log into the viewport ring — from the doc's READABLE source (a piped document's spill)
    (when (:streaming? doc)
      (reset! stop-stream
              (tstream/stream-records!
               (or (:file doc) file)
               {:pace     (fn [f] (js/setImmediate f))
                :on-blocks (fn [blocks]
                             (swap! st state/append-lines (str/split (render/render-record-blocks blocks @aopts) #"\n" -1))
                             (paint!))
                :on-error (fn [e] (term/restore!) (.error js/console (str "vv-tui: " (.-message e))) (js/process.exit 1))})))
    (term/init! {:on-key on-key :on-resize on-resize :on-resume paint! :no-tty? false :input key-input})
    (paint!)))

;; ── --drive: replay keys headlessly, dump the final frame (deterministic test seam) ──
(defn- run-drive [doc file opts drive-file]
  (let [w (or (:width opts) 80) h 24
        aopts (ansi-opts opts w)
        name  (.basename path file)
        st    (atom (state/init (-> (vp/viewport w (max 1 (dec h)) (when (:streaming? doc) stream-cap))
                                    (vp/set-lines (:lines doc)))
                                (toc/build (:toc doc) (:anchors doc))))
        drive (fn []
                (let [bytes (js/Array.from (.readFileSync fs drive-file))
                      [pending evs] (keys/feed [] bytes)
                      [_ evs2] (keys/flush pending)]
                  (doseq [ev (concat evs evs2)] (swap! st state/step ev)))
                (.write js/process.stdout (compose-frame @st w h name))
                (.write js/process.stdout "\n")
                (js/process.exit 0))]
    (if (:streaming? doc)
      ;; drain the stream fully first (test logs are small), then replay keys — from the READABLE source
      (tstream/stream-records!
       (or (:file doc) file)
       {:on-blocks (fn [blocks] (swap! st state/append-lines (str/split (render/render-record-blocks blocks aopts) #"\n" -1)))
        :on-done drive
        :on-error (fn [e] (.error js/console (str "vv-tui: " (.-message e))) (js/process.exit 1))})
      (drive))))

(defn- ewrite [s] (.write js/process.stderr (str s "\n")))

(defn- key-input-for
  "The key-input stream for the session: nil (process.stdin) normally; for a piped-stdin document the
   terminal is reopened at /dev/tty (the pipe carried the DOCUMENT, so keys need their own fd). When
   /dev/tty cannot be opened (no controlling terminal) the session is view-only: a warning is printed and
   key input is simply absent — Ctrl+C (SIGINT → the term teardown handlers) still exits cleanly."
  [spec opts]
  (when (and (:stdin? spec) (not (:drive opts)))
    (try
      (tty/ReadStream. (.openSync fs "/dev/tty" "r"))
      (catch :default _
        (ewrite "vv-tui: keyboard unavailable (/dev/tty could not be opened): view-only, Ctrl+C to quit")
        nil))))

(defn ^:export main []
  (heavy-node/install!)   ; wire unified-latex/uniorg into the shared pipeline before any doc renders (Node: no shadow.lazy)
  ;; the shared open grammar (files, -t/--type tokens, the `-` stdin placeholder) is scanned FIRST — the
  ;; parity core (ADR-0036); vv-tui's own flags parse from its :passthrough (--width/--drive take values).
  (let [args (drop 2 (js->clj js/process.argv))
        scan (ft/scan-open-args args {:value-flags #{"--width" "--drive"}})
        opts (:opts (parse-args (:passthrough scan)))]
    (cond
      (:error scan)   (do (ewrite (:error scan)) (set! (.-exitCode js/process) 1) (js/Promise.resolve nil))
      (:help opts)    (do (println usage) (js/Promise.resolve nil))
      (:version opts) (do (println version) (js/Promise.resolve nil))
      :else
      ;; unknown type tokens fail fast, BEFORE the stdin drain blocks on the producer's EOF
      (if-let [bad (first (remove ft/resolve-type-token (:types scan)))]
        (do (ewrite (str "vv: unknown type '" bad "' — " (ft/valid-tokens-hint)))
            (set! (.-exitCode js/process) 1)
            (js/Promise.resolve nil))
        (-> (stdin/read-all!)
            (.then
             (fn [buf]
               (let [result (ft/resolve-specs {:files      (vec (:files scan))
                                               :types      (:types scan)
                                               :stdin      (when buf {:path "stdin" :cwd (.cwd js/process)})
                                               :dash-index (:dash-index scan)})
                     specs  (:specs result)]
                 (cond
                   (:error result)
                   (do (ewrite (:error result)) (set! (.-exitCode js/process) 1))

                   (empty? specs)
                   (do (ewrite usage) (set! (.-exitCode js/process) 1))

                   :else
                   (let [spec (first specs)                 ; the TUI pages ONE document at a time
                         file (:uri spec)]
                     (when (> (count specs) 1)
                       (ewrite (str "vv-tui: one document at a time — ignoring "
                                    (str/join ", " (map :uri (rest specs))))))
                     (let [{:keys [w]} (term/size)]
                       (-> (load-doc spec (when (:stdin? spec) buf) (ansi-opts opts w))
                           (.then (fn [doc]
                                    (if (:drive opts)
                                      (run-drive doc file opts (:drive opts))
                                      (run-interactive doc file opts (key-input-for spec opts)))))
                           (.catch (fn [e]
                                     (term/restore!)
                                     (ewrite (str "vv-tui: " file ": " (.-message e)))
                                     (js/process.exit 1)))))))))))))))
