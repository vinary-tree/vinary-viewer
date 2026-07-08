(ns vinary.cli.core
  "vv-cli — a headless terminal document renderer. Reads a file with the (Electron-free) content_service,
   lowers it to ANSI via the shared IR front-ends + ir.backend.ansi, and writes it to stdout. Large logs/text
   stream bounded-memory through the WPDA log-stream parser (content_service.streamOpen/streamPull), so
   `vv-cli huge.log | less` never holds the whole file. Colour/graphics auto-disable when piped (isatty) or
   under NO_COLOR."
  (:require ["../main/content_service.js" :as cs]
            ["path" :as path]
            ["fs" :as fs]
            [clojure.string :as str]
            [vinary.cli.render :as render]
            [vinary.terminal.caps :as caps]
            [vinary.terminal.syntax :as tsyntax]
            [vinary.grammar-catalog :as gc]
            [vinary.ir.frontend.log-stream :as log-stream]
            [vinary.stream.protocol :as proto]))

(def ^:private version "vv-cli 0.3.0")
(def ^:private usage
  (str/join "\n"
    ["vv-cli — preview documents in the terminal"
     ""
     "Usage: vv-cli [options] <file> [file …]"
     ""
     "Options:"
     "  -t, --toc          print the document outline (Contents) first"
     "      --width N       wrap column (default: terminal width or 80)"
     "      --no-color      disable ANSI colour (also auto-off when piped / NO_COLOR)"
     "      --color         force colour even when not a TTY"
     "      --no-graphics   disable terminal image graphics (sixel/kitty)"
     "      --graphics P    force the image protocol P (kitty|sixel), bypassing terminal detection"
     "  -p, --plain         plain text: no colour, no graphics, no hyperlinks"
     "  -h, --help          show this help"
     "  -V, --version       show the version"]))

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
          (#{"-t" "--toc"} a)      (recur (rest as) files (assoc opts :toc true))
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
  "Stream a large log/text file to stdout bounded-memory: pull line batches, feed the WPDA log-stream parser,
   render each completed record block to ANSI, write incrementally. Returns Promise<nil>. (content_service's
   streamOpen/streamClose are SYNC when called in-process; only streamPull is async.)"
  [file aopts]
  (js/Promise.
   (fn [resolve reject]
     (let [^js session (.streamOpen cs #js {:path file :mode "lines"})
           sid     (.-sessionId session)
           parser  (atom (log-stream/parser))
           started (atom false)
           emit    (fn [blocks]
                     (when (seq blocks)
                       (write (str (when @started "\n") (render/render-record-blocks blocks aopts)))
                       (reset! started true)))]
       (letfn [(pump []
                 (-> (.streamPull cs #js {:sessionId sid})
                     (.then (fn [^js batch]
                              (let [{p :parser blocks :blocks} (proto/feed @parser (vec (.-lines batch)))]
                                (reset! parser p)
                                (emit blocks)
                                (if (.-done batch)
                                  (do (emit (:blocks (proto/finish @parser)))
                                      (write "\n")
                                      (.streamClose cs #js {:sessionId sid})
                                      (resolve nil))
                                  (pump)))))
                     (.catch reject)))]
         (pump))))))

;; text-backed kinds we read directly (classifyName's extension result is authoritative — content_service's
;; openLocal content-sniffs, mis-classifying e.g. a Markdown doc that contains a pipe table as a delimited table)
(def ^:private text-kinds #{"markdown" "text" "log" "mermaid" "html" "source"})
(def ^:private stream-threshold (* 5 1024 1024))
(defn- big? [file] (> (.-size (.statSync fs file)) stream-threshold))

(defn- emit-doc [payload aopts opts]
  (-> (render/render-payload payload aopts)
      (.then (fn [{:keys [body toc]}] (write (str (toc-lines toc opts) body "\n"))))))

(defn- render-file! [file opts]
  (let [aopts (ansi-opts opts)]
    ;; run the kind-detection + read (classifyName / statSync / readFileSync — all of which THROW synchronously on
    ;; a missing/unreadable file) INSIDE .then, so a synchronous throw becomes a rejection the .catch handles
    ;; (a clean `vv-cli: <file>: <msg>` + non-zero exit) instead of an uncaught stack trace that aborts siblings.
    (-> (js/Promise.resolve nil)
        (.then (fn [_]
                 (let [k0   (.classifyName cs file)
                       ;; content_service never returns "source" — a text file whose extension has a bundled
                       ;; tree-sitter grammar (e.g. .cljs, .rs, .py) is a source file → highlight it
                       kind (if (and (= "text" k0) (gc/grammar-for-path file gc/bundled-grammars {})) "source" k0)]
                   (cond
                     ;; large log/text → bounded WPDA stream to stdout
                     (and (#{"log" "text"} kind) (big? file)) (stream-log! file aopts)
                     ;; text kinds: read directly (bypass content_service content-sniffing)
                     (contains? text-kinds kind) (emit-doc {:kind kind :path file :text (.readFileSync fs file "utf8")} aopts opts)
                     ;; a standalone image: the :image port reads + encodes it. content_service's openLocal has NO
                     ;; image branch (it's the GUI's file:// <img> path), so it would mis-return the raw bytes as
                     ;; text — go direct, mirroring the text-kinds bypass.
                     (= "image" kind) (emit-doc {:kind "image" :path file} aopts opts)
                     ;; office/table/pdf/archive/directory → content_service parses them
                     :else (-> (.openUri cs file)
                               (.then (fn [payload] (emit-doc (js->clj payload :keywordize-keys true) aopts opts))))))))
        (.catch (fn [e] (ewrite (str "vv-cli: " file ": " (.-message e))) (set! (.-exitCode js/process) 1))))))

(defn ^:export main []
  (let [{:keys [files opts]} (parse-args (drop 2 (js->clj js/process.argv)))]
    (cond
      (:help opts)      (do (println usage) (js/Promise.resolve nil))
      (:version opts)   (do (println version) (js/Promise.resolve nil))
      (empty? files)    (do (ewrite usage) (set! (.-exitCode js/process) 1) (js/Promise.resolve nil))
      :else
      ;; render each file in argv order (a blank line between multiple docs)
      (reduce (fn [p file] (-> p (.then (fn [_] (render-file! file opts)))))
              (js/Promise.resolve nil) files))))
