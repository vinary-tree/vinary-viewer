(ns vinary.renderer.math
  "MathJax rendering helpers for Markdown math.

   TeX is converted to SVG before the Markdown HTML is committed to the preview DOM. The conversion is
   synchronous and cached, which avoids the visible post-insert typesetting pass that makes large documents
   feel sluggish.

   ONE engine, built from MathJax's `js/` source (shadow-cljs bundles it), used for BOTH the browser renderer
   and :node-test — no separate es5 `tex-svg.js` script, no async load. Only the SAFE TeX package
   configurations are imported: base ams amscd boldsymbol newcommand configmacros noerrors noundefined. The
   dangerous html(\\href)/require/autoload packages are NEVER imported, so they are not in the module graph at
   all — author TeX cannot emit active markup (\\href{javascript:…}) into the SVG, which is injected
   post-sanitize (the one MathJax vector the HTML sanitizer can't see)."
  (:require ;; MUST load before any mathjax-full/js module: defines PACKAGE_VERSION so version.js skips its
            ;; Node-only eval('require') branch and the js/ source bundles for :browser (see the shim ns).
            [vinary.renderer.mathjax-version-shim]
            [clojure.string :as str]
            [goog.string :as gstr]
            ;; MathJax composed from source — shadow-cljs bundles these for BOTH :browser and :node-script
            ;; (a runtime (js/require …) only works under Node, so it must be an ns :require to reach the browser).
            ;; NB: js/mathjax.js is deliberately NOT imported — it pulls in components/version.js, which reads
            ;; package.json via eval('require')/__dirname (Node-only, unbundleable). We build the MathDocument
            ;; directly with handler.create instead, which needs none of that.
            ["mathjax-full/js/input/tex.js" :as tex-mod]
            ["mathjax-full/js/output/svg.js" :as svg-mod]
            ["mathjax-full/js/adaptors/liteAdaptor.js" :as adaptor-mod]
            ["mathjax-full/js/handlers/html.js" :as html-mod]
            ["mathjax-full/js/a11y/assistive-mml.js" :as a11y-mod]
            ;; TeX package configurations — imported for their registration side effects (see safe-packages).
            ;; The dangerous html/require/autoload configs are deliberately NOT imported, so they are absent
            ;; from the module graph entirely.
            ["mathjax-full/js/input/tex/base/BaseConfiguration.js" :as base-config]
            ["mathjax-full/js/input/tex/ams/AmsConfiguration.js" :as ams-config]
            ["mathjax-full/js/input/tex/amscd/AmsCdConfiguration.js" :as amscd-config]
            ["mathjax-full/js/input/tex/boldsymbol/BoldsymbolConfiguration.js" :as boldsymbol-config]
            ["mathjax-full/js/input/tex/newcommand/NewcommandConfiguration.js" :as newcommand-config]
            ["mathjax-full/js/input/tex/configmacros/ConfigMacrosConfiguration.js" :as configmacros-config]
            ["mathjax-full/js/input/tex/noerrors/NoErrorsConfiguration.js" :as noerrors-config]
            ["mathjax-full/js/input/tex/noundefined/NoUndefinedConfiguration.js" :as noundefined-config]))

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
  #js ["base" "ams" "amscd" "boldsymbol" "newcommand" "configmacros" "noerrors" "noundefined"])

;; Reference each package-configuration module so its import (and thus its package registration) is retained.
;; Each config self-registers with MathJax's ConfigurationHandler when its module initialises.
(def ^:private loaded-configs
  #js [base-config ams-config amscd-config boldsymbol-config
       newcommand-config configmacros-config noerrors-config noundefined-config])

(defn- mj-engine!
  "Build the engine once (memoized). The package configs are registered by their ns imports (loaded-configs);
   output/svg.js pulls in the TeXFont so glyphs render; AssistiveMmlHandler adds screen-reader MathML (kept out
   of the copied text + visually hidden via CSS). liteAdaptor makes .convert return an outerHTML string."
  []
  (or @engine
      (let [_          loaded-configs                          ; ensure the package configs are registered
            adaptor    ((.-liteAdaptor adaptor-mod))
            handler    ((.-RegisterHTMLHandler html-mod) adaptor)
            _          ((.-AssistiveMmlHandler a11y-mod) handler)   ; augments handler.documentClass in place
            tex-input  (new (.-TeX tex-mod) #js {:packages safe-packages})
            svg-output (new (.-SVG svg-mod) #js {:fontCache "none"})
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

(defn- math-code? [^js code]
  (let [classes (.-classList code)]
    (or (.contains classes "language-math")
        (.contains classes "lang-math")
        (.contains classes "math-inline")
        (.contains classes "math-display"))))

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

(defn error-html [message source]
  (str "<div class=\"vv-math-error\"><strong>MathJax error:</strong> "
       (gstr/htmlEscape (or message "Unable to render math"))
       (when (seq source)
         (str "<pre><code>" (gstr/htmlEscape source) "</code></pre>"))
       "</div>"))
