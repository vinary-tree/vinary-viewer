(ns vinary.cli.render
  "Turn a content_service payload into terminal ANSI, reusing the GUI's IR front-ends verbatim and lowering
   through ir.backend.ansi. Pure of Electron/DOM (the markdown parse runs the shared DOM-free base-pipeline in
   Node). Shared by vv-cli (one-shot) and the TUI. `:highlight` (a `(fn [lang code] → per-line spans)` from
   terminal.syntax) and `:image` ports are threaded into the ANSI opts; both default to nil (plain code /
   placeholder). Each kind returns {:ir :toc :block-sep} — logs/text use a single-newline block separator (a
   line-structured document), prose kinds a blank line."
  (:require ["rehype-stringify$default" :as rehype-stringify]
            [clojure.string :as str]
            [vinary.renderer.markdown-pipeline :as pipeline]
            [vinary.ir.frontend.markdown :as ir-md]
            [vinary.ir.frontend.office :as ir-office]
            [vinary.ir.frontend.table :as ir-table]
            [vinary.ir.frontend.archive :as ir-archive]
            [vinary.ir.frontend.log :as ir-log]
            [vinary.ir.frontend.log-stream :as log-stream]
            [vinary.ir.capability.toc :as ir-toc]
            [vinary.ir.node :as node]
            [vinary.ir.backend.ansi :as ansi]
            [vinary.terminal.syntax :as tsyntax]
            [vinary.grammar-catalog :as gc]
            [vinary.stream.protocol :as proto]))

(defn markdown->ir
  "Markdown text → Promise<IR> via the shared DOM-free base-pipeline (byte-for-byte the GUI's parse)."
  [text base-dir]
  (let [captured (atom nil)]
    (-> (pipeline/base-pipeline (atom {:toc [] :assets #{}}) base-dir nil)
        (.use (pipeline/capture-hast captured))
        (.use rehype-stringify)
        (.process (or text ""))
        (.then (fn [_] (ir-md/hast->ir @captured))))))

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
  [{:keys [kind text html path entries] :as payload} opts]
  (letfn [(done [ir sep] (js/Promise.resolve {:ir ir :toc (ir-toc/toc-of ir) :block-sep sep}))]
    (case kind
      "markdown" (-> (markdown->ir text (pipeline/dir-of path))
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
      ;; pdf and anything unrecognised: fall back to any :text as plain lines (pdf gets its own path in vv-cli)
      (done (ir-log/text->ir (or text (str "[" kind " — no terminal renderer]"))) "\n"))))

(defn render-payload
  "content_service payload + ANSI opts → Promise<{:body :toc}> (the rendered terminal document + its outline).
   Pre-loads the tree-sitter grammars the document's code blocks need, then renders synchronously with the
   already-configured :highlight port."
  [payload opts]
  (-> (payload->ir payload opts)
      (.then (fn [{:keys [ir toc block-sep]}]
               (-> (tsyntax/ensure-grammars! (ansi/code-languages ir))
                   (.then (fn [_] {:body (ansi/render ir (assoc opts :block-sep block-sep)) :toc toc})))))))

;; a single streamed batch of log lines → an ANSI chunk (for the streaming CLI/TUI path)
(defn render-record-blocks
  "Lower a vector of streamed :record IR blocks to an ANSI string (single-newline separated), for the
   bounded-memory streaming path."
  [blocks opts]
  (->> blocks
       (map #(str/join "\n" (ansi/block->lines % (merge {:width 80 :color? true :block-sep "\n"} opts) "" (:width opts 80))))
       (remove str/blank?)
       (str/join "\n")))
