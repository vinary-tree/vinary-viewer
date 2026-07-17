(ns vinary.renderer.math
  "MathJax rendering helpers for Markdown math.

   TeX is converted to SVG before the Markdown HTML is committed to the preview DOM. The conversion is
   synchronous and cached, which avoids the visible post-insert typesetting pass that makes large documents
   feel sluggish.

   ONE engine, built from MathJax 4's `@mathjax/src` source (shadow-cljs bundles it), rendering in the
   Latin-Modern-derived MathJax Modern font, used for BOTH the browser renderer and :node-test — no separate es5
   `tex-svg.js` script, no async load — MathJax 4's dynamic font chunks are preloaded synchronously at engine
   build (see mj-engine!). Only the SAFE TeX package configurations are imported: base ams amscd
   boldsymbol newcommand configmacros noerrors noundefined textmacros. The
   dangerous html(\\href)/require/autoload packages are NEVER imported, so they are not in the module graph at
   all — author TeX cannot emit active markup (\\href{javascript:…}) into the SVG, which is injected
   post-sanitize (the one MathJax vector the HTML sanitizer can't see).

   textmacros is what makes the CONTENT of \\text{} parse as text — see safe-packages. It is required for
   GitHub parity: GitHub's MathJax loads it, and without it this previewer disagrees with the publishing
   target in both directions at once (mangling `\\text{a\\_b}` into a literal backslash, while silently
   accepting `\\text{a_b}` that GitHub rejects). A previewer that is MORE permissive than its target hides
   real errors, which is the worse failure."
  (:require [clojure.string :as str]
            [goog.string :as gstr]
            ;; the single sanitize schema — for `tex-attempt-class`, the marker the Org frontend stamps on a
            ;; `#+BEGIN_EXPORT latex` block and the schema deliberately preserves (see render-tex-blocks).
            [vinary.ir.backend.sanitize :as sanitize]
            ;; MathJax 4 composed from source — @mathjax/src/cjs (CommonJS; shadow-cljs bundles it for BOTH :browser
            ;; and :node-script). As in v3 we build the MathDocument directly with handler.create (NOT js/mathjax.js)
            ;; and import only the SAFE TeX package configs — the dangerous html(\\href)/require/autoload configs are
            ;; never in the module graph, so author TeX cannot emit active markup into the post-sanitize-injected SVG.
            ["@mathjax/src/cjs/input/tex.js" :as tex-mod]
            ["@mathjax/src/cjs/output/svg.js" :as svg-mod]
            ["@mathjax/src/cjs/adaptors/liteAdaptor.js" :as adaptor-mod]
            ["@mathjax/src/cjs/handlers/html.js" :as html-mod]
            ["@mathjax/src/cjs/a11y/assistive-mml.js" :as a11y-mod]
            ;; the shared `mathjax` singleton — its asyncLoad / asyncIsSynchronous flags gate synchronous vs
            ;; retry-throwing dynamic font loading (flipped to synchronous in mj-engine!).
            ["@mathjax/src/cjs/mathjax.js" :as mathjax-core]
            ;; the Latin-Modern-derived "MathJax Modern" SVG font, passed as :fontData — MathJax 4's built-in default
            ;; is Newcm (New Computer Modern), so the Modern font must be supplied explicitly.
            ["@mathjax/mathjax-modern-font/cjs/svg.js" :as modern-font]
            ;; MathJax 4 ships the SVG font as a small base set + 26 dynamically-loaded glyph chunks. Import every
            ;; chunk for its registration side effect (MathJaxModernFont.dynamicSetup) so mj-engine! can install
            ;; them synchronously — otherwise any glyph in a chunk (\mathtt/\mathbb/\mathfrak/\mathscr/\mathsf/…,
            ;; extended symbols/arrows/accents/shapes/PUA) makes the synchronous .convert throw MathJax's async
            ;; "retry" error. Retained in loaded-dynamic-font-modules so shadow-cljs cannot elide them.
            ["@mathjax/mathjax-modern-font/cjs/svg/dynamic/accents.js" :as mm-accents]
            ["@mathjax/mathjax-modern-font/cjs/svg/dynamic/accents-b-i.js" :as mm-accents-b-i]
            ["@mathjax/mathjax-modern-font/cjs/svg/dynamic/arrows.js" :as mm-arrows]
            ["@mathjax/mathjax-modern-font/cjs/svg/dynamic/calligraphic.js" :as mm-calligraphic]
            ["@mathjax/mathjax-modern-font/cjs/svg/dynamic/double-struck.js" :as mm-double-struck]
            ["@mathjax/mathjax-modern-font/cjs/svg/dynamic/fraktur.js" :as mm-fraktur]
            ["@mathjax/mathjax-modern-font/cjs/svg/dynamic/latin.js" :as mm-latin]
            ["@mathjax/mathjax-modern-font/cjs/svg/dynamic/latin-b.js" :as mm-latin-b]
            ["@mathjax/mathjax-modern-font/cjs/svg/dynamic/latin-bi.js" :as mm-latin-bi]
            ["@mathjax/mathjax-modern-font/cjs/svg/dynamic/latin-i.js" :as mm-latin-i]
            ["@mathjax/mathjax-modern-font/cjs/svg/dynamic/math.js" :as mm-math]
            ["@mathjax/mathjax-modern-font/cjs/svg/dynamic/monospace.js" :as mm-monospace]
            ["@mathjax/mathjax-modern-font/cjs/svg/dynamic/monospace-ex.js" :as mm-monospace-ex]
            ["@mathjax/mathjax-modern-font/cjs/svg/dynamic/monospace-l.js" :as mm-monospace-l]
            ["@mathjax/mathjax-modern-font/cjs/svg/dynamic/PUA.js" :as mm-pua]
            ["@mathjax/mathjax-modern-font/cjs/svg/dynamic/sans-serif.js" :as mm-sans-serif]
            ["@mathjax/mathjax-modern-font/cjs/svg/dynamic/sans-serif-b.js" :as mm-sans-serif-b]
            ["@mathjax/mathjax-modern-font/cjs/svg/dynamic/sans-serif-bi.js" :as mm-sans-serif-bi]
            ["@mathjax/mathjax-modern-font/cjs/svg/dynamic/sans-serif-ex.js" :as mm-sans-serif-ex]
            ["@mathjax/mathjax-modern-font/cjs/svg/dynamic/sans-serif-i.js" :as mm-sans-serif-i]
            ["@mathjax/mathjax-modern-font/cjs/svg/dynamic/sans-serif-r.js" :as mm-sans-serif-r]
            ["@mathjax/mathjax-modern-font/cjs/svg/dynamic/script.js" :as mm-script]
            ["@mathjax/mathjax-modern-font/cjs/svg/dynamic/shapes.js" :as mm-shapes]
            ["@mathjax/mathjax-modern-font/cjs/svg/dynamic/symbols.js" :as mm-symbols]
            ["@mathjax/mathjax-modern-font/cjs/svg/dynamic/symbols-b-i.js" :as mm-symbols-b-i]
            ["@mathjax/mathjax-modern-font/cjs/svg/dynamic/variants.js" :as mm-variants]
            ;; TeX package configurations — imported for their registration side effects (see safe-packages).
            ["@mathjax/src/cjs/input/tex/base/BaseConfiguration.js" :as base-config]
            ["@mathjax/src/cjs/input/tex/ams/AmsConfiguration.js" :as ams-config]
            ["@mathjax/src/cjs/input/tex/amscd/AmsCdConfiguration.js" :as amscd-config]
            ["@mathjax/src/cjs/input/tex/color/ColorConfiguration.js" :as color-config]
            ["@mathjax/src/cjs/input/tex/boldsymbol/BoldsymbolConfiguration.js" :as boldsymbol-config]
            ["@mathjax/src/cjs/input/tex/newcommand/NewcommandConfiguration.js" :as newcommand-config]
            ["@mathjax/src/cjs/input/tex/configmacros/ConfigMacrosConfiguration.js" :as configmacros-config]
            ["@mathjax/src/cjs/input/tex/noerrors/NoErrorsConfiguration.js" :as noerrors-config]
            ["@mathjax/src/cjs/input/tex/noundefined/NoUndefinedConfiguration.js" :as noundefined-config]
            ;; textmacros — the text-mode parser for the CONTENT of \text{}/\texttt{}/… (see safe-packages).
            ["@mathjax/src/cjs/input/tex/textmacros/TextMacrosConfiguration.js" :as textmacros-config]))

(def ^:private max-cache-entries 512)

(defonce ^:private svg-cache (atom {:entries {} :order []}))
(defonce ^:private engine (atom nil))

(defn- remember [state k v]
  (let [order   (vec (cons k (remove #{k} (:order state))))
        entries (assoc (:entries state) k v)]
    (if (<= (count order) max-cache-entries)
      {:entries entries :order order}
      (let [drop-keys (subvec order max-cache-entries)]
        {:entries (apply dissoc entries drop-keys)
         :order   (subvec order 0 max-cache-entries)}))))

;; ---- the single MathJax TeX→SVG engine (browser + node), built from the shadow-bundled js/ source ----
(def ^:private safe-packages
  ;; NO html/require/autoload — see the ns docstring. Adding amscd (\begin{CD}) + boldsymbol to the standard set.
  ;; color (\textcolor[rgb]{…}) is added for the LaTeX previewer: latex-structure/sanitize-math! resolves a
  ;; document's \definecolor named colours to the rgb spec MathJax's color package understands (it never sees the
  ;; .tex preamble). Its ColorConfiguration imports only internal MathJax modules — safe by the same test as amscd.
  ;;
  ;; textmacros parses the CONTENT of \text{}/\texttt{}/… . \text itself is base (BaseMethods.HBox); what base
  ;; lacks without textmacros is the `internalMath` hook (ParseUtil.internalMath delegates to
  ;; options.internalMath if installed — only TextMacrosConfiguration installs it). Without the hook \text{}
  ;; content is near-literal, so `\text{a\_b}` renders a LITERAL BACKSLASH; with it, `\_` is an underscore and
  ;; `_`/`^` correctly become math-mode-only errors. GitHub's MathJax loads textmacros, so omitting it made this
  ;; previewer disagree with the publishing target in BOTH directions — silently accepting `\text{a_b}` that
  ;; GitHub rejects, while mangling the `\_` that GitHub renders. Safe by the same test as the rest of this list:
  ;; it pulls in no html/require/autoload (only internal MathJax modules), and defaults to a restricted
  ;; `packages: ["text-base"]` inside \text{}. Its one autoload touchpoint (CheckAutoload, for \color inside
  ;; \text) merely reads packageData and no-ops when autoload is absent — which here it always is.
  #js ["base" "ams" "amscd" "color" "boldsymbol" "newcommand" "configmacros" "noerrors" "noundefined" "textmacros"])

;; Reference each package-configuration module so its import (and thus its package registration) is retained.
;; Each config self-registers with MathJax's ConfigurationHandler when its module initialises.
(def ^:private loaded-configs
  #js [base-config ams-config amscd-config color-config boldsymbol-config
       newcommand-config configmacros-config noerrors-config noundefined-config
       textmacros-config])

;; Reference each dynamic-font module so its dynamicSetup side effect is retained (shadow-cljs would otherwise
;; elide these side-effect-only imports). The glyph data is installed into the font instance in mj-engine!.
(def ^:private loaded-dynamic-font-modules
  #js [mm-accents mm-accents-b-i mm-arrows mm-calligraphic mm-double-struck mm-fraktur
       mm-latin mm-latin-b mm-latin-bi mm-latin-i mm-math mm-monospace mm-monospace-ex mm-monospace-l
       mm-pua mm-sans-serif mm-sans-serif-b mm-sans-serif-bi mm-sans-serif-ex mm-sans-serif-i mm-sans-serif-r
       mm-script mm-shapes mm-symbols mm-symbols-b-i mm-variants])

(defn- mj-engine!
  "Build the engine once (memoized). The package configs are registered by their ns imports (loaded-configs); the
   26 MathJaxModern dynamic-font chunks by loaded-dynamic-font-modules. output/svg.js pulls in the font so glyphs
   render; AssistiveMmlHandler adds screen-reader MathML (kept out of the copied text + visually hidden via CSS).
   liteAdaptor makes .convert return an outerHTML string.

   MathJax 4 loads font glyph data in dynamic chunks; under our SYNCHRONOUS .convert, a glyph in an unloaded chunk
   would throw MathJax's async \"retry\" error. So we flip the shared `mathjax` singleton to synchronous font
   loading (a no-op asyncLoad suffices — every chunk is already bundled + registered by loaded-dynamic-font-modules)
   and eagerly install every range via loadDynamicFilesSync. Because mj-engine! is memoized (defonce engine),
   exactly one MathJaxModernFont exists per JS context; a SECOND one built in the same context after
   loadDynamicFilesSync would recurse on the static FontData.dynamicFiles registry, so this must stay the sole engine."
  []
  (or @engine
      (let [_          loaded-configs                          ; register the SAFE TeX package configs
            _          loaded-dynamic-font-modules             ; register all 26 dynamic-font glyph chunks
            _          (set! (.. mathjax-core -mathjax -asyncLoad) (fn [_name] nil)) ; no-op: chunks already bundled
            _          (set! (.. mathjax-core -mathjax -asyncIsSynchronous) true)    ; take the sync path, not retryAfter
            adaptor    ((.-liteAdaptor adaptor-mod))
            handler    ((.-RegisterHTMLHandler html-mod) adaptor)
            _          ((.-AssistiveMmlHandler a11y-mod) handler)   ; augments handler.documentClass in place
            tex-input  (new (.-TeX tex-mod)
                            #js {:packages safe-packages
                                 ;; \llbracket / \rrbracket are stmaryrd macros MathJax doesn't bundle. Bind them to
                                 ;; the bare U+27E6/U+27E7 chars — already MO.OPEN / MO.CLOSE in MathJax's operator
                                 ;; dictionary and stretchy in the font — so they act as standard container
                                 ;; delimiters (correct spacing bare; stretch under \left…\right). No \mathopen
                                 ;; wrapper: that would break \left\llbracket, which needs a bare delimiter token.
                                 :macros #js {"llbracket" "⟦"    ; ⟦
                                              "rrbracket" "⟧"}}) ; ⟧
            ;; linebreaks.inline MUST be false. MathJax 4 turned automatic INLINE line-breaking on by default,
            ;; and its break opportunities are exactly `mo` (operators) and `mspace` (spacing). We typeset
            ;; off-DOM through liteAdaptor, so MathJax has no container to measure and takes EVERY break: it
            ;; emits one <svg> per "line", and the first one carries ALL the ink inside a degenerate viewBox
            ;; (`\implies` → three siblings, the first 16 units wide holding the whole ⟹ path). The browser's
            ;; `svg:not(:root){overflow:hidden}` then clipped it to an invisible blank gap of about the right
            ;; width. Breaking is meaningless here anyway: each expression is rendered once, cached, and injected
            ;; as a static SVG string into a variable-width column it can never measure or reflow into.
            ;; Display math is unaffected either way (it is governed by displayOverflow, default "overflow").
            svg-output (new (.-SVG svg-mod) #js {:fontCache "none"
                                                 :linebreaks #js {:inline false}
                                                 :fontData (new (.-MathJaxModernFont modern-font))})
            _          (.loadDynamicFilesSync ^js (.-font svg-output)) ; install all 26 ranges up front (needs the sync flag)
            ;; handler.create(document, options) builds the MathDocument (what mathjax.document would do), with
            ;; no dependency on the singleton in js/mathjax.js.
            math-doc   (.create handler ""
                                #js {:InputJax tex-input
                                     :OutputJax svg-output
                                     :enableAssistiveMml true})
            eng        {:adaptor adaptor :math-doc math-doc}]
        (reset! engine eng)
        eng)))

(defn- remember-render! [k html]
  (swap! svg-cache remember k html)
  html)

(defn render-tex
  "Render TeX to a MathJax SVG container string (synchronous, cached). display? selects inline vs display
   math metrics. Same engine in the browser and in :node-test."
  [source display?]
  (let [source (or source "")
        k      [(boolean display?) source]]
    (if-let [html (get-in @svg-cache [:entries k])]
      html
      (let [{:keys [^js adaptor ^js math-doc]} (mj-engine!)
            node (.convert math-doc source #js {:display (boolean display?)})]
        (remember-render! k (.outerHTML adaptor node))))))

(defn engine-font-name
  "The SVG output font's NAME (e.g. \"MathJaxModern\" — the Latin-Modern-derived font). For tests + diagnostics;
   building the engine is memoised, so this is cheap after the first call."
  []
  (.. ^js (:math-doc (mj-engine!)) -outputJax -font -constructor -NAME))

;; ── MathJax's own stylesheet ────────────────────────────────────────────────────────────────────────────────
;;
;; MathJax normally inserts this itself when it typesets a live document. We drive it through `liteAdaptor` and
;; serialize with `.outerHTML`, so it never touches a real <head> and the CSS never arrives. Two of its rules
;; are load-bearing:
;;
;;   mjx-container[jax="SVG"] > svg        { overflow: visible; }   ← without it the UA rule
;;                                                                    `svg:not(:root){overflow:hidden}` clips any
;;                                                                    ink outside the viewBox. Correct MathJax
;;                                                                    output spills a few percent (italic
;;                                                                    correction, accents): even `x^2` loses the
;;                                                                    tail of its exponent.
;;   mjx-container[jax="SVG"] path[data-c] { stroke-width: 3; }     ← the emitted root <g> carries the
;;                                                                    presentation attribute stroke-width="0";
;;                                                                    CSS beats presentation attributes, so this
;;                                                                    is how MathJax fattens its glyph outlines.
;;
;; It also carries the `mjx-assistive-mml` clip rule (because AssistiveMmlHandler is registered on our handler),
;; which keeps the screen-reader MathML in the DOM but out of the picture and out of copied text. app.css used to
;; hand-copy that one rule; injecting the real sheet makes that copy redundant and undriftable.

(def ^:private stylesheet-id "vv-mathjax-style")

(defn engine-stylesheet
  "MathJax's own CSS for this engine, as a string. DOM-free — safe to call from :node-test / vv-cli / vv-tui."
  []
  (let [{:keys [^js adaptor ^js math-doc]} (mj-engine!)]
    (.textContent adaptor (.styleSheet ^js (.-outputJax math-doc) math-doc))))

(defn install-stylesheet!
  "Insert `engine-stylesheet` into the live document once, as <style id=\"vv-mathjax-style\"> appended to <head>.
   Idempotent. Browser-only: guarded on js/document so requiring this namespace stays safe in the DOM-free Node
   builds (:test, :cli, :tui all pull it in transitively through renderer.markdown-pipeline). Appended AFTER
   app.css, but every app.css math rule is more specific than MathJax's, so the app still wins each tie."
  []
  (when (and (exists? js/document)
             (nil? (.getElementById js/document stylesheet-id)))
    (let [^js style (.createElement js/document "style")]
      (set! (.-id style) stylesheet-id)
      (set! (.-textContent style) (engine-stylesheet))
      (.appendChild (.-head js/document) style)
      style)))

(defn strip-math-fence
  "Clean the TeX of a math node produced from GitHub's backtick-wrapped inline form.

   GitHub writes inline math as $`x^2`$ (backticks INSIDE the dollars). remark-math DOES parse that as an
   inlineMath node, but keeps the backticks in the value (`x^2`), so MathJax would see literal backticks.
   This strips a balanced leading/trailing backtick run → x^2. A math value with no wrapping backticks
   (ordinary $x$) is returned unchanged. This runs ONLY on math nodes; a code span `$x$` is an inlineCode
   node — never a math node — so it is untouched and stays literal (GitHub does not render math in code).

   The former string-level normalize (a raw-markdown regex) was replaced by this mdast-level cleanup because
   a pre-parse regex cannot see code-span boundaries and corrupted documents containing multiple `$…$`
   inline-code examples."
  [tex]
  (let [tex (or tex "")]
    (if-let [[_ open inner close] (re-matches #"^(`+)([\s\S]*?)(`+)$" tex)]
      (if (and (= (count open) (count close)) (pos? (count inner)))
        inner
        tex)
      tex)))

(defn- display-code? [^js code]
  (or (.contains (.-classList code) "math-display")
      (some-> code .-parentElement .-tagName (= "PRE"))))

(defn delimit-tex
  "Wrap raw TeX in markdown math delimiters so it round-trips into Markdown.
   display? → $$…$$, else $…$. Blank/nil source → nil (nothing copyable)."
  [display? tex]
  (let [tex (str/trim (or tex ""))]
    (when (seq tex)
      (if display? (str "$$" tex "$$") (str "$" tex "$")))))

(defn render-html-math
  "Replace remark-math code placeholders in rendered Markdown HTML with cached MathJax SVG. The math render is
   SYNCHRONOUS now (the engine is shadow-bundled — no async load), but this still returns a Promise so it
   composes with the async mermaid/syntax post-passes. In :node-test (no DOMParser) it is a pass-through, so
   math placeholders survive as `code.language-math` for the parity/code-span tests."
  [html]
  (if-not (exists? js/DOMParser)
      (js/Promise.resolve html)
    (let [parser    (js/DOMParser.)
          doc       (.parseFromString parser (or html "") "text/html")
          node-list (.querySelectorAll doc "code.language-math, code.lang-math, code.math-inline, code.math-display")]
      (dotimes [i (.-length node-list)]
        (let [code     (.item node-list i)
              display? (display-code? code)
              source   (str/trim (.-textContent code))
              wrapper  (.createElement doc (if display? "div" "span"))
              target   (if display? (.-parentElement code) code)]
          (.add (.-classList wrapper) (if display? "vv-math-display" "vv-math-inline"))
          ;; Stash the raw LaTeX so the copy paths (Ctrl+C selection rewrite, "Copy LaTeX" menu) can recover
          ;; the source the SVG can't carry. .setAttribute lets the innerHTML serializer escape backslashes/quotes.
          (.setAttribute wrapper "data-tex" source)
          (try
            (set! (.-innerHTML wrapper) (render-tex source display?))
            (.replaceWith target wrapper)
            (catch :default e
              (set! (.-textContent wrapper) (str "MathJax error: " (.-message e)))
              (.add (.-classList wrapper) "vv-math-error")
              (.replaceWith target wrapper)))))
      (js/Promise.resolve (.-innerHTML (.-body doc))))))

;; ── Org `#+BEGIN_EXPORT latex` blocks: attempt MathJax, fall back to a highlighted code block ────────────────
;;
;; MathJax does NOT throw on bad TeX here: the engine loads the `noerrors` + `noundefined` packages (see
;; safe-packages), so a bad macro or environment renders an error node instead of raising. The reliable failure
;; signal is therefore the error node itself, not an exception — `\begin{center}` yields
;; `data-mjx-error="Unknown environment 'center'"`. We check for both, plus a real throw (a font "retry").

(defn tex-error?
  "Did MathJax fail to typeset? True when the rendered container carries an error node. `render-tex` returns
   normally in that case (noerrors/noundefined are loaded), so this — not try/catch — is the fallback signal."
  [html]
  (boolean (re-find #"data-mjx-error|<merror" (or html ""))))

;; MathJax typesets MATH, not document markup. An export block full of \begin{center} / tabular / itemize is
;; not math; worse, a block of prose built only from math-legal macros (`\textbf{Hi} \\ World`) typesets
;; "successfully" into garbage that no error check can catch. So attempt a conversion only when the block
;; positively looks like math AND carries no document-structure macro.
(def ^:private math-env-re
  #"\\begin\{(equation|align|gather|multline|split|aligned|alignat|array|[bBpvV]?matrix|cases|eqnarray|CD)\*?\}|\\\[|\$\$|\\\(")

(def ^:private non-math-re
  #"\\begin\{(center|flushleft|flushright|itemize|enumerate|description|tabular\w*|longtable|figure|table|minipage|verbatim|quote|quotation|abstract|document)\}|\\(includegraphics|usepackage|section|subsection|maketitle|tableofcontents|newpage)\b")

(defn tex-block-math?
  "Should this `#+BEGIN_EXPORT latex` body even be ATTEMPTED as math? See math-env-re / non-math-re."
  [source]
  (let [source (or source "")]
    (and (boolean (re-find math-env-re source))
         (not (re-find non-math-re source)))))

(defn render-tex-blocks
  "Replace every `code.vv-tex-attempt` (an Org `#+BEGIN_EXPORT latex` block) with MathJax SVG when it typesets
   cleanly; otherwise drop the marker class and LEAVE the code block, so the later tree-sitter pass highlights
   it as `language-latex`. Must run post-sanitize (MathJax's <svg> is not in the allowlist) and BEFORE the
   syntax pass. Returns Promise<html> so it composes in `apply-posts`.

   Fast-pathed on a substring test: Markdown/office/PDF documents never carry the marker, so they never pay for
   a DOMParser round-trip — which matters because the streaming sink runs `apply-posts` once per block. In
   :node-test / vv-cli / vv-tui there is no DOMParser, so the block simply stays a highlighted code block."
  [html]
  (if-not (and (exists? js/DOMParser)
               (str/includes? (or html "") sanitize/tex-attempt-class))
    (js/Promise.resolve html)
    (let [parser    (js/DOMParser.)
          doc       (.parseFromString parser (or html "") "text/html")
          node-list (.querySelectorAll doc (str "code." sanitize/tex-attempt-class))]
      (dotimes [i (.-length node-list)]
        (let [^js code  (.item node-list i)
              source    (str/trim (.-textContent code))
              rendered  (when (tex-block-math? source)
                          (try (let [svg (render-tex source true)]
                                 (when-not (tex-error? svg) svg))
                               (catch :default _ nil)))]
          (if rendered
            (let [wrapper (.createElement doc "div")
                  target  (or (.-parentElement code) code)]   ; the <pre>
              (.add (.-classList wrapper) "vv-math-display")
              ;; keep the LaTeX recoverable by the copy paths, exactly as render-html-math does
              (.setAttribute wrapper "data-tex" source)
              (set! (.-innerHTML wrapper) rendered)
              (.replaceWith target wrapper))
            (.remove (.-classList code) sanitize/tex-attempt-class))))
      (js/Promise.resolve (.-innerHTML (.-body doc))))))

(defn error-html [message source]
  (str "<div class=\"vv-math-error\"><strong>MathJax error:</strong> "
       (gstr/htmlEscape (or message "Unable to render math"))
       (when (seq source)
         (str "<pre><code>" (gstr/htmlEscape source) "</code></pre>"))
       "</div>"))
