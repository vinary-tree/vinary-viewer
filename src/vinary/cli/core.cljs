(ns vinary.cli.core
  "vv-cli — a headless terminal document renderer. Reads a file with the (Electron-free) content_service,
   lowers it to ANSI via the shared IR front-ends + ir.backend.ansi, and writes it to stdout. Large logs/text
   stream bounded-memory through the WPDA log-stream parser (content_service.streamOpen/streamPull), so
   `vv-cli huge.log | less` never holds the whole file. Colour/graphics auto-disable when piped (isatty) or
   under NO_COLOR."
  (:require ["../main/content_service.js" :as cs]
            ["../main/ssh_transport.js" :as ssh-transport]
            ["path" :as path]
            ["fs" :as fs]
            ["readline" :as readline]
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
            [vinary.grammar-catalog :as gc]))

(def ^:private version "vv --cli 0.3.0")
(def ^:private usage
  (str/join "\n"
    ["vv --cli — preview documents in the terminal"
     ""
     "Usage: vv --cli [options] [files…]        files may be local paths or URIs (ssh:// sftp://)"
     "       git diff | vv --cli -t diff        piped stdin becomes the FIRST document ('-' repositions it)"
     ""
     "Options:"
     "  -t, --type TYPE     file type for the Nth file (repeatable; stdin is file 0; untyped files deduce"
     "                      by extension, else plain text). TYPE is a MIME type (text/x-diff), a short"
     "                      alias (diff, md, csv, log), or a grammar language (python, rust). --file-type"
     "                      is an accepted alias. NOTE: -t used to mean --toc; that short form moved here."
     "      --toc           print the document outline (Contents) first"
     "      --width N       wrap column (default: terminal width or 80)"
     "      --no-color      disable ANSI colour (also auto-off when piped / NO_COLOR)"
     "      --color         force colour even when not a TTY"
     "      --no-graphics   disable terminal image graphics (sixel/kitty)"
     "      --graphics P    force the image protocol P (kitty|sixel), bypassing terminal detection"
     "  -p, --plain         plain text: no colour, no graphics, no hyperlinks"
     "  -h, --help          show this help"
     "  -V, --version       show the version"]))

;; Parses vv-cli's OWN presentation flags — the open grammar (files, `-t/--type` tokens, the `-` stdin
;; placeholder) is consumed FIRST by the shared vinary.file-type/scan-open-args (parity core, ADR-0036),
;; and only its :passthrough reaches this loop. `-t` therefore no longer means --toc (a user-approved
;; breaking change: `-t` is --type uniformly across GUI/cli/tui); --toc is long-only now.
(defn- parse-args [args]
  (loop [as args files [] opts {}]
    (if (empty? as)
      {:files files :opts opts}
      (let [a (first as)]
        (cond
          (#{"-h" "--help"} a)     (recur (rest as) files (assoc opts :help true))
          (#{"-V" "--version"} a)  (recur (rest as) files (assoc opts :version true))
          (#{"-p" "--plain"} a)    (recur (rest as) files (assoc opts :no-color true :no-graphics true :no-hyperlinks true))
          (= "--no-color" a)       (recur (rest as) files (assoc opts :no-color true))
          (= "--color" a)          (recur (rest as) files (assoc opts :force-color true))
          (= "--no-graphics" a)    (recur (rest as) files (assoc opts :no-graphics true))
          (= "--graphics" a)       (let [v (keyword (or (second as) ""))]
                                     (recur (drop 2 as) files (cond-> opts (#{:kitty :sixel} v) (assoc :force-graphics v))))
          (str/starts-with? a "--graphics=") (let [v (keyword (subs a 11))]
                                              (recur (rest as) files (cond-> opts (#{:kitty :sixel} v) (assoc :force-graphics v))))
          (= "--no-hyperlinks" a)  (recur (rest as) files (assoc opts :no-hyperlinks true))
          (= "--toc" a)            (recur (rest as) files (assoc opts :toc true))
          (= "--width" a)          (recur (drop 2 as) files (assoc opts :width (js/parseInt (or (second as) "80"))))
          (str/starts-with? a "--width=") (recur (rest as) files (assoc opts :width (js/parseInt (subs a 8))))
          (str/starts-with? a "-") (recur (rest as) files opts)                 ; ignore unknown flags
          :else                    (recur (rest as) (conj files a) opts))))))

(defn- write [s] (.write js/process.stdout s))
(defn- ewrite [s] (.write js/process.stderr (str s "\n")))

(defn- ansi-opts [opts]
  (let [c (caps/detect opts)]
    {:width       (:width c)
     :color?      (:color? c)
     :truecolor?  (:truecolor? c)
     :hyperlinks? (:hyperlinks? c)
     :graphics    (:graphics c)
     :highlight   (when (:color? c) (tsyntax/highlighter))   ; tree-sitter → ANSI spans; nil → plain code
     :image       nil}))                                     ; the :image port is injected per-document by
                                                             ; cli.render/render-payload (it needs the doc dir)

(defn- toc-lines [toc opts]
  (when (and (:toc opts) (seq toc))
    (str "Contents\n"
         (str/join "\n" (map (fn [{:keys [level text]}]
                               (str (apply str (repeat (* 2 (dec (or level 1))) " ")) "• " text))
                             toc))
         "\n\n")))

(defn- stream-log!
  "Stream a large log/text file to stdout bounded-memory via the shared terminal.stream engine: completed record
   blocks are rendered to ANSI and written incrementally (blank-line separated), so `vv-cli huge.log | less` never
   holds the whole file. Returns Promise<nil>."
  [file aopts]
  (js/Promise.
   (fn [resolve reject]
     (let [started (atom false)]
       (tstream/stream-records!
        file
        {:on-blocks (fn [blocks]
                      (write (str (when @started "\n") (render/render-record-blocks blocks aopts)))
                      (reset! started true))
         :on-done   (fn [] (write "\n") (resolve nil))
         :on-error  reject})))))

;; text-backed kinds we read directly (classifyName's extension result is authoritative — content_service's
;; openLocal content-sniffs, mis-classifying e.g. a Markdown doc that contains a pipe table as a delimited table)
(def ^:private text-kinds #{"markdown" "org" "latex" "text" "log" "mermaid" "html" "source"})
(def ^:private stream-threshold (* 5 1024 1024))
(defn- big? [file] (> (.-size (.statSync fs file)) stream-threshold))

(defn- emit-doc [payload aopts opts]
  (-> (render/render-payload payload aopts)
      (.then (fn [{:keys [body toc]}] (write (str (toc-lines toc opts) body "\n"))))))

;; ── remote (ssh://sftp://) terminal auth prompts (TTY-gated; non-interactive → decline, so a piped run relies on
;;    ssh-agent / keys). Configured on the shared transport so a CLI remote open authenticates like the GUI. ──
(defn- read-secret [prompt-str]
  (js/Promise.
   (fn [resolve _reject]
     (if-not (.-isTTY js/process.stdin)
       (resolve nil)
       (let [^js rl (.createInterface readline #js {:input js/process.stdin :output js/process.stderr})
             muted  (atom false)]
         ;; mask keystrokes (only echo newlines) once the prompt itself has been written
         (set! (.-_writeToOutput rl)
               (fn [s] (when (or (not @muted) (re-find #"[\r\n]" s)) (.write js/process.stderr s))))
         (.question rl prompt-str (fn [ans] (.close rl) (.write js/process.stderr "\n") (resolve ans)))
         (reset! muted true))))))

(defn- prompt-host-key-cli [info]
  (js/Promise.
   (fn [resolve _reject]
     (let [m (js->clj info :keywordize-keys true)]
       (if-not (.-isTTY js/process.stdin)
         (resolve false)
         (let [^js rl (.createInterface readline #js {:input js/process.stdin :output js/process.stderr})]
           (.question rl (str "The authenticity of host '" (:host m) "' can't be established.\n"
                              (:keyType m) " key fingerprint is " (:fingerprint m) ".\n"
                              "Are you sure you want to continue connecting (yes/no)? ")
                      (fn [ans] (.close rl) (resolve (boolean (re-find #"(?i)^y" (str ans))))))))))))

(defn- setup-remote! []
  (.configure ssh-transport
              #js {:promptHostKey prompt-host-key-cli
                   :promptSecret  (fn [req]
                                    (let [m    (js->clj req :keywordize-keys true)
                                          what (case (:kind m) "passphrase" "Passphrase" "keyboard-interactive"
                                                 (or (:prompt m) "Response") "Password")]
                                      (read-secret (str what " for " (:user m) "@" (:host m) ": "))))
                   :onError       (fn [info] (ewrite (str "vv-cli: ssh: " (.-message info))))}))

(defn- effective-kind
  "The kind a spec renders as: the explicit type (ADR-0036) wins; a bare stdin document is plain text (an
   extensionless snapshot); everything else classifies by name, upgraded text→source when a grammar knows
   the extension (content_service never returns \"source\")."
  [{:keys [uri kind stdin?]}]
  (or kind
      (if stdin?
        "text"
        (let [k0 (.classifyName cs uri)]
          (if (and (= "text" k0) (gc/grammar-for-path uri gc/bundled-grammars {})) "source" k0)))))

(defn- render-spec!
  "Render one resolved open spec ({:uri :kind? :language? :delimiter? :stdin?} — vinary.file-type). `buf`
   is the drained stdin Buffer for the stdin spec (nil otherwise): text-backed kinds and pdf render from
   it directly; only the paths that genuinely need a file on disk (the bounded stream engine, the image
   port, content_service's table parser) spill it first (vinary.terminal.stdin/spill!, unlinked at exit)."
  [{:keys [uri language delimiter stdin?] :as spec} buf opts]
  (let [aopts (ansi-opts opts)]
    ;; run the kind-detection + read (classifyName / statSync / readFileSync — all of which THROW synchronously on
    ;; a missing/unreadable file) INSIDE .then, so a synchronous throw becomes a rejection the .catch handles
    ;; (a clean `vv-cli: <file>: <msg>` + non-zero exit) instead of an uncaught stack trace that aborts siblings.
    (-> (js/Promise.resolve nil)
        (.then (fn [_]
                 (let [file     uri
                       kind     (effective-kind spec)
                       explicit (some? (:kind spec))
                       ;; explicit :language rides every text payload; the render "source" arm prefers it
                       with-lang (fn [payload] (cond-> payload language (assoc :language language)))]
                   (cond
                     ;; piped stdin — the snapshot is already in memory; spill only where a path is required
                     stdin?
                     (cond
                       (= "pdf" kind)   (emit-doc {:kind "pdf" :path file :bytes buf} aopts opts)
                       (= "image" kind) (emit-doc {:kind "image" :path (stdin/spill! buf)} aopts opts)
                       (and (#{"log" "text"} kind) (> (.-length buf) stream-threshold))
                       (stream-log! (stdin/spill! buf) aopts)
                       (= "table" kind)
                       (-> (.openUri cs (stdin/spill! buf) kind
                                     (clj->js (cond-> {} delimiter (assoc :delimiter delimiter))))
                           (.then (fn [payload] (emit-doc (with-lang (js->clj payload :keywordize-keys true)) aopts opts))))
                       :else (emit-doc (with-lang {:kind kind :path file :text (.toString buf "utf8")}) aopts opts))
                     ;; remote (ssh://sftp://): the fs shortcuts below can't reach the host — route every kind
                     ;; through the remote reader, passing the grammar-aware kind so a remote source highlights
                     ;; (opts.explicit marks a USER-chosen kind — it disables the remote tail sniff).
                     (.isRemoteUri ssh-transport file)
                     (-> (.openRemoteUri cs file kind
                                         (clj->js (cond-> {}
                                                    explicit  (assoc :explicit true)
                                                    delimiter (assoc :delimiter delimiter))))
                         (.then (fn [payload] (emit-doc (with-lang (js->clj payload :keywordize-keys true)) aopts opts))))
                     ;; large log/text → bounded WPDA stream to stdout
                     (and (#{"log" "text"} kind) (big? file)) (stream-log! file aopts)
                     ;; text kinds: read directly (bypass content_service content-sniffing)
                     (contains? text-kinds kind) (emit-doc (with-lang {:kind kind :path file :text (.readFileSync fs file "utf8")}) aopts opts)
                     ;; a standalone image: the :image port reads + encodes it. content_service's openLocal has NO
                     ;; image branch (it's the GUI's file:// <img> path), so it would mis-return the raw bytes as
                     ;; text — go direct, mirroring the text-kinds bypass.
                     (= "image" kind) (emit-doc {:kind "image" :path file} aopts opts)
                     ;; pdf → read the raw bytes (a Buffer; js->clj would mangle it) for headless pdf.js extraction
                     (= "pdf" kind) (emit-doc {:kind "pdf" :path file :bytes (.readFileSync fs file)} aopts opts)
                     ;; office/table/archive/directory → content_service parses them (an explicit kind threads
                     ;; through so `-t csv notes.txt` parses as delimited text, never re-classified or sniffed)
                     :else (-> (.openUri cs file (when explicit kind)
                                         (clj->js (cond-> {} delimiter (assoc :delimiter delimiter))))
                               (.then (fn [payload] (emit-doc (with-lang (js->clj payload :keywordize-keys true)) aopts opts))))))))
        (.catch (fn [e] (ewrite (str "vv-cli: " uri ": " (.-message e))) (set! (.-exitCode js/process) 1))))))

(defn ^:export main []
  (heavy-node/install!)   ; wire unified-latex/uniorg into the shared pipeline before any doc renders (Node: no shadow.lazy)
  ;; the shared open grammar (files, -t/--type tokens, the `-` stdin placeholder) is scanned FIRST — the
  ;; parity core (ADR-0036); vv-cli's own presentation flags parse from its :passthrough. --graphics and
  ;; --width consume a following value, so they are declared to the scanner.
  (let [args (drop 2 (js->clj js/process.argv))
        scan (ft/scan-open-args args {:value-flags #{"--graphics" "--width"}})
        opts (:opts (parse-args (:passthrough scan)))]
    (cond
      (:error scan)   (do (ewrite (:error scan)) (set! (.-exitCode js/process) 1) (js/Promise.resolve nil))
      (:help opts)    (do (println usage) (js/Promise.resolve nil))
      (:version opts) (do (println version) (js/Promise.resolve nil))
      :else
      ;; validate the type tokens BEFORE draining stdin: an unknown token is an error regardless of what
      ;; is piped, and the drain blocks until the producer's EOF — a typo must not wait on that.
      (if-let [bad (first (remove ft/resolve-type-token (:types scan)))]
        (do (ewrite (str "vv: unknown type '" bad "' — " (ft/valid-tokens-hint)))
            (set! (.-exitCode js/process) 1)
            (js/Promise.resolve nil))
      (-> (stdin/read-all!)
          (.then
           (fn [buf]
             ;; the stdin document is file 0 (or wherever `-` placed it); zero piped bytes = no stdin doc.
             ;; :uri "stdin" is a display name only — text/pdf render straight from the Buffer.
             (let [result (ft/resolve-specs {:files      (vec (:files scan))
                                             :types      (:types scan)
                                             :stdin      (when buf {:path "stdin" :cwd (.cwd js/process)})
                                             :dash-index (:dash-index scan)})]
               (cond
                 (:error result)
                 (do (ewrite (:error result)) (set! (.-exitCode js/process) 1))

                 (empty? (:specs result))
                 (do (ewrite usage) (set! (.-exitCode js/process) 1))

                 :else
                 (do
                   (setup-remote!)   ; configure SSH terminal prompts in case any arg is a remote URI
                   ;; render each document in order (a blank line between multiple docs), then close any pooled
                   ;; SSH connection so the process can exit (an open socket would hold the event loop open).
                   (-> (reduce (fn [p spec]
                                 (-> p (.then (fn [_] (render-spec! spec (when (:stdin? spec) buf) opts)))))
                               (js/Promise.resolve nil) (:specs result))
                       (.finally (fn [] (.closeAll ssh-transport))))))))))))))

