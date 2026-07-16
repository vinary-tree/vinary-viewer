(ns vinary.renderer.markdown
  "Markdown → HTML for the GUI. The DOM-FREE parse+transform pipeline (remark→rehype→sanitize→slug→highlight→
   url-rewrite→source-positions→metadata) lives in vinary.renderer.markdown-pipeline so the terminal front-end
   reuses the identical parsing/sanitizing/slugging/positions. This namespace keeps only the renderer-coupled
   parts: the string POST-PASSES `apply-posts` (MathJax SVG, then Mermaid SVG, then tree-sitter fenced-code
   highlighting — the last two touch the DOM), and the IR render/stream entry points that lower to HTML through
   the IR back-end. Returns Promise<{:html :toc :assets}> / Promise<{:blocks :toc :assets}>."
  (:require ["rehype-stringify$default" :as rehype-stringify]
            [vinary.ir.node :as node]
            [vinary.ir.frontend.markdown :as ir-md]
            [vinary.ir.frontend.office :as ir-office]
            [vinary.ir.backend.html :as ir-html]
            [vinary.ir.capability.toc :as ir-toc]
            [vinary.renderer.latex :as latex]
            [vinary.renderer.markdown-pipeline :as pipeline]
            [vinary.renderer.math :as math]
            [vinary.renderer.mermaid :as mermaid]
            [vinary.renderer.figures :as figures]
            [vinary.renderer.syntax :as syntax]))

;; re-exported so existing callers (app.fx, stream.scheduler, ui.views) keep using `md/dir-of` unchanged
(def dir-of pipeline/dir-of)

(defn apply-posts
  "The shared string post-passes applied to serialized HTML: MathJax SVG (synchronous), then Org
   `#+BEGIN_EXPORT latex` blocks (attempt MathJax, else leave the code block — a no-op for every other format),
   then Mermaid SVG (async, font-matched), then figure geometry (async — bakes font-matched SVG width / raster
   box reservation into <img> tags so they render at final size with no post-insert re-scale), then tree-sitter
   fenced-code highlighting (async). All operate on self-contained elements, so they distribute over block
   concatenation → byte-identical whether run whole (render-ir) or per streamed block (sink). Returns
   Promise<html>. Public so the streaming sink can run the identical passes per appended block.

   render-tex-blocks MUST precede the syntax pass: a block that fails to typeset falls back to a
   `language-latex` code block, which the syntax pass then highlights."
  [html]
  (-> (js/Promise.resolve (math/render-html-math html))
      (.then math/render-tex-blocks)
      (.then mermaid/render-html-diagrams)
      (.then figures/scale-figures-html)
      (.then syntax/highlight-html-code-blocks)))

;; The legacy string render (direct rehype-stringify) is RETIRED (ADR-0017): the common IR is now the
;; UNCONDITIONAL render path (render-ir below), which lowers the IR back to HTML through the single sanitizer
;; with byte-identical output — ir.parity-test proves HAST -> IR -> HAST -> HTML == HAST -> HTML. Kept
;; #_-disabled for reference rather than deleted (per the repo's comment-don't-delete rule).
#_(defn render
  "Render a Markdown string. base-dir (the source doc's absolute directory, or nil) is used to resolve
   relative img/link URLs to absolute file://. Returns a Promise resolving to
   {:html string :toc [{:level :text :id}] :assets [absolute-path ...]}."
  ([^String md base-dir] (render md base-dir nil))
  ([^String md base-dir cache-token]
   (let [metadata (atom {:toc [] :assets #{}})]
     (-> (pipeline/base-pipeline metadata base-dir cache-token)
         (.use rehype-stringify)
         (.process md)
         (.then (fn [file] (apply-posts (str file))))
         (.then (fn [html]
                  {:html html
                   :toc (:toc @metadata)
                   :assets (vec (:assets @metadata))}))))))

(defn render-ir
  "Build the common document IR from the shared pipeline HAST (ir-md/hast->ir) and lower it back to HTML through
   the IR back-end (ir-html/lower = ir->hast->rehype-stringify), then apply the same post-passes. The :toc is
   derived from the IR (ir-toc/toc-of), the :assets from the shared collect-metadata. Returns
   Promise<{:html :ir :toc :assets}>. Because the IR round-trips the HAST faithfully, :html and :toc are
   byte-equal to a direct stringify (proven by ir.parity-test + the electron smoke)."
  ([^String md base-dir] (render-ir md base-dir nil))
  ([^String md base-dir cache-token]
   (let [metadata (atom {:toc [] :assets #{}})
         captured (atom nil)]
     (-> (pipeline/base-pipeline metadata base-dir cache-token)
         (.use (pipeline/capture-hast captured))
         (.use rehype-stringify)
         (.process md)
         (.then (fn [_file]
                  (let [ir (ir-md/hast->ir @captured)]
                    (-> (apply-posts (ir-html/lower ir))
                        (.then (fn [html]
                                 {:html html
                                  :ir ir
                                  :toc (ir-toc/toc-of ir)
                                  :assets (vec (:assets @metadata))}))))))))))

(defn- stream-blocks*
  "The format-agnostic progressive-commit block provider. Runs the EXACT batch pipeline (`make-pipeline` —
   pipeline/base-pipeline or pipeline/org-pipeline) on the WHOLE `text` once (so heading-slug dedup, reference
   definitions, footnotes, and source positions all have full document context — byte-parity is guaranteed by
   construction), then returns the IR document's top-level children IN FULL — element blocks AND the inter-block
   whitespace `:text` leaves that carry the exact separators. Returns Promise<{:blocks [ir-child] :toc :assets}>;
   the scheduler commits the children across idle frames (pacing the per-block lower + math/mermaid/syntax
   post-passes + DOM writes) and the sink concatenates them with NO separator, so `concat(map lower children)`
   == `lower(whole-document)` == the batch render's `:html`, byte for byte (the separators live in the emitted
   whitespace leaves, not a re-synthesized `\\n`).

   The engine commits IR children, never format-specific nodes, so every tree-producing frontend can stream
   through it by supplying its own parse prefix here.

   (Unlike logs/PDF — whose bytes are NOT in renderer memory and so stream bounded from main — a prose doc's
   text is already fully in `:doc/text`; there is no bounded-parse win to be had, and CommonMark's document-
   global constructs make a byte-parity bounded-parse infeasible, so the win here is a NON-BLOCKING progressive
   paint that never holds the whole HTML string. See ADR-0018 / theory/09.)"
  [make-pipeline text base-dir cache-token]
  (let [metadata (atom {:toc [] :assets #{}})
        captured (atom nil)]
    (-> (make-pipeline metadata base-dir cache-token)
        (.use (pipeline/capture-hast captured))
        (.use rehype-stringify)
        (.process text)
        (.then (fn [_]
                 (let [ir (ir-md/hast->ir @captured)]
                   {:blocks (vec (node/children ir))   ; ALL children (elements + the whitespace separator leaves)
                    :toc    (ir-toc/toc-of ir)
                    :assets (vec (:assets @metadata))}))))))

(defn stream-blocks
  "Progressive-commit streaming for Markdown. See `stream-blocks*`."
  ([text base-dir] (stream-blocks text base-dir nil))
  ([text base-dir cache-token]
   (stream-blocks* pipeline/base-pipeline text base-dir cache-token)))

(defn org-stream-blocks
  "Progressive-commit streaming for Org (.org) — the same engine, the same IR, the same per-block post-passes;
   only the parse prefix differs. See `stream-blocks*`."
  ([text base-dir] (org-stream-blocks text base-dir nil))
  ([text base-dir cache-token]
   (stream-blocks* pipeline/org-pipeline (pipeline/org-preprocess text) base-dir cache-token)))

(defn latex-stream-blocks
  "Progressive-commit streaming for LaTeX (.tex) — the same progressive-paint engine, IR, and per-block
   post-passes as Markdown/Org. LaTeX cannot reuse `stream-blocks*` because `tex-processor` is transform-only
   (renderer.latex converts the WHOLE document to an HTML string synchronously, driven by `.runSync` — there is
   no unified Parser to `.process`). It parses once and returns ALL IR children (elements + the whitespace
   `:text` leaves that carry the exact separators), so `concat(map lower children) == lower(document)` byte for
   byte, exactly as Org's parity test proves — the win is a non-blocking progressive paint, not bounded parsing
   (unified-latex is a whole-document parse; see ADR-0025 / ADR-0018). Returns Promise<{:blocks :toc :assets}>."
  ([text base-dir] (latex-stream-blocks text base-dir nil))
  ([text base-dir cache-token]
   (let [metadata (atom {:toc [] :assets #{}})
         tree     (.runSync ^js (pipeline/tex-processor metadata base-dir cache-token)
                            (pipeline/latex-raw-tree (latex/latex->html text)))
         ir       (ir-md/hast->ir tree)]
     (js/Promise.resolve
      {:blocks (vec (node/children ir))
       :toc    (ir-toc/toc-of ir)
       :assets (vec (:assets @metadata))}))))

(defn render-office-ir
  "Render office HTML through the common IR: parse via rehype-raw + the shared sanitizer + rehype-slug
   (ir-office/html->ir) → hast->IR → lower → post-passes. Returns Promise<{:html :toc}>, giving office
   documents a heading TOC and the single sanitize policy. No base-dir — office HTML carries no relative URLs."
  [html]
  (let [ir (ir-office/html->ir html)]
    (-> (apply-posts (ir-html/lower ir))
        (.then (fn [h] {:html h :toc (ir-toc/toc-of ir)})))))

(defn render-org-ir
  "Render an Org (.org) document through the common IR: the shared org-pipeline (uniorg → hast → the same app
   suffix as Markdown) → capture HAST → hast->IR → lower back to HTML → the SAME post-passes (MathJax, Mermaid,
   figure pre-sizing, tree-sitter fenced-code highlighting — so nested #+begin_src blocks highlight). :toc comes
   from the IR heading roles (ir-toc/toc-of); :assets from collect-metadata (relative Org images resolved
   against base-dir). Modeled on render-ir (async — uniorg-parse is a real Parser). Returns
   Promise<{:html :ir :toc :assets}>."
  ([^String text base-dir] (render-org-ir text base-dir nil))
  ([^String text base-dir cache-token]
   (let [text     (pipeline/org-preprocess text)     ; expand {{{macros}}} / inline src / babel / targets first
         metadata (atom {:toc [] :assets #{}})
         captured (atom nil)]
     (-> (pipeline/org-pipeline metadata base-dir cache-token)
         (.use (pipeline/capture-hast captured))
         (.use rehype-stringify)
         (.process text)
         (.then (fn [_file]
                  (let [ir (ir-md/hast->ir @captured)]
                    (-> (apply-posts (ir-html/lower ir))
                        (.then (fn [html]
                                 {:html html
                                  :ir ir
                                  :toc (ir-toc/toc-of ir)
                                  :assets (vec (:assets @metadata))}))))))))))

(defn render-latex-ir
  "Render a standalone LaTeX (.tex) document through the common IR: the shared tex-pipeline (renderer.latex
   converts LaTeX → an HTML string → a `raw` node, then the same app suffix + tex-normalize as Org) → capture
   HAST → hast->IR → lower back to HTML → the SAME post-passes (MathJax typesets the preserved TeX, Mermaid,
   figure pre-sizing, tree-sitter fenced-code highlighting). :toc from the IR heading roles (ir-toc/toc-of);
   :assets from collect-metadata (relative \\includegraphics images resolved against base-dir). Modeled on
   render-office-ir: renderer.latex converts the LaTeX to an HTML string synchronously, then the transform-only
   tex-processor runs via runSync (no custom Parser); apply-posts still composes through a Promise. Returns
   Promise<{:html :ir :toc :assets}>."
  ([^String text base-dir] (render-latex-ir text base-dir nil))
  ([^String text base-dir cache-token]
   (let [metadata (atom {:toc [] :assets #{}})
         tree     (.runSync ^js (pipeline/tex-processor metadata base-dir cache-token)
                            (pipeline/latex-raw-tree (latex/latex->html text)))
         ir       (ir-md/hast->ir tree)]
     (-> (apply-posts (ir-html/lower ir))
         (.then (fn [html]
                  {:html html
                   :ir ir
                   :toc (ir-toc/toc-of ir)
                   :assets (vec (:assets @metadata))}))))))
