(ns vinary.cli.render
  "Turn a content_service payload into terminal ANSI, reusing the GUI's IR front-ends verbatim and lowering
   through ir.backend.ansi. Pure of Electron/DOM (the markdown parse runs the shared DOM-free base-pipeline in
   Node). Shared by vv-cli (one-shot) and the TUI. `:highlight` (a `(fn [lang code] → per-line spans)` from
   terminal.syntax) and `:image` ports are threaded into the ANSI opts; both default to nil (plain code /
   placeholder). Each kind returns {:ir :toc :block-sep} — logs/text use a single-newline block separator (a
   line-structured document), prose kinds a blank line."
  (:require ["rehype-stringify$default" :as rehype-stringify]
            ["path" :as path]
            ["fs" :as fs]
            [clojure.string :as str]
            ;; unified-latex reached through the runtime registry (populated eagerly under Node by
            ;; heavy-node/install!, called from cli.core / tui.core) rather than a static require — the same
            ;; decoupling the renderer uses so the shared pipeline carries no unified-latex edge.
            [vinary.renderer.heavy-registry :as registry]
            [vinary.renderer.markdown-pipeline :as pipeline]
            [vinary.renderer.media :as media]
            [vinary.ir.frontend.markdown :as ir-md]
            [vinary.ir.frontend.office :as ir-office]
            [vinary.ir.frontend.table :as ir-table]
            [vinary.ir.frontend.archive :as ir-archive]
            [vinary.ir.frontend.log :as ir-log]
            [vinary.ir.frontend.log-stream :as log-stream]
            [vinary.ir.frontend.diff :as ir-diff]
            [vinary.ir.capability.toc :as ir-toc]
            [vinary.ir.node :as node]
            [vinary.ir.backend.ansi :as ansi]
            [vinary.terminal.syntax :as tsyntax]
            [vinary.terminal.graphics :as gfx]
            [vinary.terminal.pdf :as tpdf]
            [vinary.grammar-catalog :as gc]
            [vinary.stream.protocol :as proto]))

(defn- pipeline->ir
  "Run one of the shared DOM-free unified pipelines over `text` and capture its HAST as the common IR.
   `make-pipeline` is (fn [metadata base-dir cache-token] processor) — pipeline/base-pipeline or
   pipeline/org-pipeline — so Markdown and Org differ ONLY in their parse prefix, never in the IR they yield."
  [make-pipeline text base-dir]
  (let [captured (atom nil)]
    (-> (make-pipeline (atom {:toc [] :assets #{}}) base-dir nil)
        (.use (pipeline/capture-hast captured))
        (.use rehype-stringify)
        (.process (or text ""))
        (.then (fn [_] (ir-md/hast->ir @captured))))))

(defn markdown->ir
  "Markdown text → Promise<IR> via the shared DOM-free base-pipeline (byte-for-byte the GUI's parse)."
  [text base-dir]
  (pipeline->ir pipeline/base-pipeline text base-dir))

(defn org->ir
  "Org text → Promise<IR> via the shared DOM-free org-pipeline (byte-for-byte the GUI's parse). The terminal has
   no DOM, so the GUI's MathJax/figure post-passes never run: math stays a `code.math-*` span and a
   `#+BEGIN_EXPORT latex` block stays a `language-latex` code block — which is precisely the GUI's fallback."
  [text base-dir]
  (pipeline->ir pipeline/org-pipeline (pipeline/org-preprocess text) base-dir))

(defn latex->ir
  "LaTeX text → Promise<IR> via the shared DOM-free tex-processor (renderer.latex converts LaTeX → an HTML string
   → a raw node, then the same app suffix + tex-normalize as Org). runSync (not the async pipeline->ir) because
   tex-processor is transform-only — unified-latex parses synchronously and no custom Parser is used. The
   terminal has no DOM, so the GUI's MathJax post-pass never runs: math stays a `code.math-*` span (the GUI's
   fallback), and the layout (tables, styling, lists) is already lowered by unified-latex."
  [text base-dir]
  (let [tree (.runSync ^js (pipeline/tex-processor (atom {:toc [] :assets #{}}) base-dir nil)
                       (pipeline/latex-raw-tree (registry/latex->html text)))]
    (js/Promise.resolve (ir-md/hast->ir tree))))

(defn- log->ir
  "Log text → a :document of WPDA-segmented :record blocks (multi-line stack/JSON entries stay whole,
   severity-coloured by the ANSI backend) — the same log-stream front-end the GUI uses, drained at once."
  [text]
  (node/node :document (vec (proto/drain (log-stream/parser) [(str/split (or text "") #"\n")])) {}))

(defn- code-block-ir [text lang]
  (node/node :code-block
             [(node/node :code [(node/leaf :text (or text ""))]
                         {:tag "code" :attrs {"className" [(str "language-" (or lang ""))]}})]
             {:tag "pre"}))

(defn- image-ir [path]
  (node/node :image [] {:tag "img" :attrs {"src" (str path) "alt" (or (last (str/split (str path) #"/")) "image")}}))

(defn- dir->ir
  "A directory/archive listing → a :document of :line leaves (name, marking directories with a trailing /)."
  [entries]
  (node/node :document
             (mapv (fn [{:keys [name dir?]}] (node/leaf :line (str (if dir? "📁 " "   ") name (when dir? "/"))))
                   (sort-by (juxt (complement :dir?) :name) entries))
             {}))

(defn payload->ir
  "content_service payload → Promise<{:ir :toc :block-sep}>. `opts` may carry :source-ir (a fn text→Promise<IR>
   for the source kind, injected by the CLI since it needs on-disk tree-sitter grammars)."
  [{:keys [kind text html path entries bytes] :as payload} opts]
  (letfn [(done [ir sep] (js/Promise.resolve {:ir ir :toc (ir-toc/toc-of ir) :block-sep sep}))]
    (case kind
      "markdown" (-> (markdown->ir text (pipeline/dir-of path))
                     (.then (fn [ir] {:ir ir :toc (ir-toc/toc-of ir) :block-sep "\n\n"})))
      ;; Org is a semantic superset of GFM: same IR, same ANSI backend — only the parse prefix differs.
      "org"      (-> (org->ir text (pipeline/dir-of path))
                     (.then (fn [ir] {:ir ir :toc (ir-toc/toc-of ir) :block-sep "\n\n"})))
      ;; LaTeX → unified-latex → the same common IR + ANSI backend as Markdown/Org.
      "latex"    (-> (latex->ir text (pipeline/dir-of path))
                     (.then (fn [ir] {:ir ir :toc (ir-toc/toc-of ir) :block-sep "\n\n"})))
      ;; pdf → headless pdf.js text extraction → reflowable prose IR (paragraphs + anchored headings)
      "pdf"      (-> (tpdf/pdf->ir bytes)
                     (.then (fn [ir] {:ir ir :toc (ir-toc/toc-of ir) :block-sep "\n\n"})))
      "log"      (done (log->ir text) "\n")
      "text"     (done (ir-log/text->ir text) "\n")
      ("office") (done (ir-office/html->ir html) "\n\n")
      "html"     (done (ir-office/html->ir text) "\n\n")
      "table"    (done (ir-table/payload->ir payload) "\n\n")
      "mermaid"  (done (code-block-ir text "mermaid") "\n\n")
      "image"    (done (image-ir path) "\n\n")
      ("directory" "archive") (done (dir->ir entries) "\n")
      ;; a source file IS its (highlighted) code — render it as one fenced code block in the file's language
      "source"   (done (code-block-ir text (some-> (gc/grammar-for-path path gc/bundled-grammars {}) :id)) "\n")
      ;; a diff/patch → the unified (colored) IR; the ANSI backend colours ±/hunk lines. Single-newline blocks
      ;; (a line-structured document), like logs — never a blank line between diff lines.
      "diff"     (done (ir-diff/diff->ir text) "\n")
      ;; pdf and anything unrecognised: fall back to any :text as plain lines (pdf gets its own path in vv-cli)
      (done (ir-log/text->ir (or text (str "[" kind " — no terminal renderer]"))) "\n"))))

;; ── image port (terminal graphics) ───────────────────────────────────────────
(defn- data-uri->bytes
  "Decode a data: URI → {:bytes Buffer :ext \".png\"} (or nil). Handles base64 and URL-encoded (inline SVG) data."
  [src]
  (when-let [[_ mime b64 data] (re-find #"(?i)^data:([^;,]*);?(base64)?,([\s\S]*)$" src)]
    (let [bytes (if (seq b64) (js/Buffer.from data "base64") (js/Buffer.from (js/decodeURIComponent data) "utf8"))
          m     (str/lower-case (or mime ""))
          ext   (cond (str/includes? m "svg")  ".svg" (str/includes? m "png")  ".png"
                      (str/includes? m "jpeg") ".jpg" (str/includes? m "jpg")  ".jpg"
                      (str/includes? m "gif")  ".gif" (str/includes? m "webp") ".webp"
                      (str/includes? m "avif") ".avif" (str/includes? m "bmp") ".bmp" :else "")]
      {:bytes bytes :ext ext})))

(defn- read-file-image [p]
  (when (and (string? p) (.existsSync fs p) (.isFile (.statSync fs p)))
    {:bytes (.readFileSync fs p) :ext (.extname path p)}))

(defn- resolve-image
  "Resolve an <img> src to {:bytes :ext} | :remote | nil. The shared markdown pipeline rewrites relative media
   URLs to `file://<dir>/<name>` (optionally `?vv-cache=…`) BEFORE the IR is built, so the common `![](pic.png)`
   case arrives as a file: URL — decoded via media/local-media-path (which strips the cache query). data: URIs are
   decoded inline; office/HTML images may still be relative → resolved against `base-dir`; http(s) is NOT fetched."
  [src base-dir]
  (cond
    (str/blank? src)                nil
    (str/starts-with? src "data:")  (data-uri->bytes src)
    (re-find #"(?i)^https?://" src) :remote
    (str/starts-with? src "file:")  (read-file-image (media/local-media-path src))
    :else
    (read-file-image (if (.isAbsolute path src) src (.join path (or base-dir ".") src)))))

(defn- img-name [src] (or (last (str/split (str src) #"[\\/]")) "image"))

(defn- placeholder-text
  "A labelled `🖼 name — reason` fallback shown where the image can't be drawn (piped, unsupported format, etc.)."
  [src reason]
  (str "🖼 " (img-name src)
       (case reason
         :remote          " — remote image not fetched"
         :not-found       " — not found"
         :unknown-format  " — unrecognised image"
         :too-large       " — too large to display"
         :decode-failed   " — could not decode"
         :svg-unavailable " — SVG renderer unavailable"
         (:no-graphics nil) ""
         (if (and (keyword? reason) (str/starts-with? (name reason) "undecodable-"))
           (str " — " (subs (name reason) 12) " not supported")
           ""))))

(defn image-port
  "The ANSI `:image` port for the CLI: resolve a node's src (data URI / file path; http(s) not fetched) to bytes,
   then encode a kitty/sixel escape sized to the available `width`, appended with its row footprint so following
   content flows below it. Degrades to a labelled placeholder. `opts` carries :graphics (nil → placeholder only,
   without even reading the file)."
  [opts base-dir]
  (fn [img-node width]
    (let [src (ansi/attr img-node "src")]
      (if (nil? (:graphics opts))
        (placeholder-text src :no-graphics)
        (let [r (resolve-image src base-dir)]
          (cond
            (nil? r)      (placeholder-text src :not-found)
            (= :remote r) (placeholder-text src :remote)
            :else
            (let [enc (gfx/encode (:bytes r) (:ext r) {:graphics (:graphics opts) :max-cols width})]
              (if-let [esc (:escape enc)]
                (str esc (apply str (repeat (max 0 (dec (:rows enc))) "\n")))   ; reserve the image's vertical footprint
                (placeholder-text src (:placeholder enc))))))))))

(defn- has-image? [ir] (boolean (some #(= :image (node/kind %)) (node/preorder ir))))

(defn render-payload
  "content_service payload + ANSI opts → Promise<{:body :toc}> (the rendered terminal document + its outline).
   Pre-loads the tree-sitter grammars the document's code blocks need AND (when graphics are on and the document
   has images) the resvg WASM, then renders synchronously with the :highlight + :image ports configured."
  [payload opts]
  (-> (payload->ir payload opts)
      (.then (fn [{:keys [ir toc block-sep]}]
               (let [base-dir (some->> (:path payload) (.dirname path))
                     opts'    (assoc opts :block-sep block-sep :image (image-port opts base-dir))
                     preload  (js/Promise.all
                               #js [(tsyntax/ensure-grammars! (ansi/code-languages ir))
                                    (if (and (:graphics opts) (has-image? ir)) (gfx/ensure-ready!) (js/Promise.resolve nil))])]
                 (.then preload (fn [_] {:body (ansi/render ir opts') :toc toc})))))))

(defn render-doc
  "The TUI's document entry: content_service payload + ANSI opts → Promise<{:ir :toc :block-sep :lines :anchors}>.
   Pre-loads the code grammars, renders the IR to LINES + a heading-id→line-index anchor map (ansi/render-lines), and
   RETAINS the :ir so the driver can re-render at a new width on resize WITHOUT re-reading/re-parsing the file. The
   scrolling viewport forces graphics off (images → placeholder lines), so `opts` should carry :graphics nil."
  [payload opts]
  (-> (payload->ir payload opts)
      (.then (fn [{:keys [ir toc block-sep]}]
               (-> (tsyntax/ensure-grammars! (ansi/code-languages ir))
                   (.then (fn [_]
                            (let [base-dir (some->> (:path payload) (.dirname path))
                                  opts'    (assoc opts :block-sep block-sep :image (image-port opts base-dir))
                                  {:keys [lines anchors]} (ansi/render-lines ir opts')]
                              {:ir ir :toc toc :block-sep block-sep :lines lines :anchors anchors}))))))))

(defn render-ir-lines
  "Re-render an ALREADY-parsed IR to {:lines :anchors} at (possibly new) ANSI opts — the resize path. Synchronous:
   grammars are already loaded from the initial render-doc, so a SIGWINCH re-wrap needs no async work."
  [ir opts base-dir]
  (ansi/render-lines ir (assoc opts :image (image-port opts base-dir))))

;; a single streamed batch of log lines → an ANSI chunk (for the streaming CLI/TUI path)
(defn render-record-blocks
  "Lower a vector of streamed :record IR blocks to an ANSI string (single-newline separated), for the
   bounded-memory streaming path."
  [blocks opts]
  (->> blocks
       (map #(str/join "\n" (ansi/block->lines % (merge {:width 80 :color? true :block-sep "\n"} opts) "" (:width opts 80))))
       (remove str/blank?)
       (str/join "\n")))
