(ns vinary.renderer.mermaid
  "Mermaid rendering helpers for fenced Markdown diagrams and direct Mermaid files."
  (:require [clojure.string :as str]
            [goog.string :as gstr]
            [vinary.renderer.figures :as figures]))

(def ^:private max-cache-entries 128)

(defonce ^:private initialized? (atom false))
(defonce ^:private counter (atom 0))
(defonce ^:private svg-cache (atom {:entries {} :order []}))
(defonce ^:private load-promise (atom nil))

(defn- remember [state k v]
  (let [order   (vec (cons k (remove #{k} (:order state))))
        entries (assoc (:entries state) k v)]
    (if (<= (count order) max-cache-entries)
      {:entries entries :order order}
      (let [drop-keys (subvec order max-cache-entries)]
        {:entries (apply dissoc entries drop-keys)
         :order   (subvec order 0 max-cache-entries)}))))

(defn- mermaid-api []
  (.-mermaid js/globalThis))

(defn- mermaid-src []
  (str (js/URL. "../../node_modules/mermaid/dist/mermaid.min.js"
                (.-baseURI js/document))))

(defn- load-api! []
  (if-let [api (mermaid-api)]
    (js/Promise.resolve api)
    (if-not (exists? js/document)
      (js/Promise.reject (js/Error. "Mermaid rendering requires a browser document"))
      (if-let [p @load-promise]
        p
        (let [p (js/Promise.
                 (fn [resolve reject]
                   (let [doc    js/document
                         script (.createElement doc "script")]
                     (set! (.-src script) (mermaid-src))
                     (set! (.-async script) true)
                     (set! (.-onload script)
                           (fn []
                             (if-let [api (mermaid-api)]
                               (resolve api)
                               (do
                                 (reset! load-promise nil)
                                 (reject (js/Error. "Mermaid browser bundle did not expose window.mermaid"))))))
                     (set! (.-onerror script)
                           (fn []
                             (reset! load-promise nil)
                             (reject (js/Error. "Unable to load Mermaid browser bundle"))))
                     (.appendChild (.-head doc) script))))]
          (reset! load-promise p)
          p)))))

(defn- ensure-init! [^js api]
  (when-not @initialized?
    (.initialize api
                 (clj->js {:startOnLoad false
                            :securityLevel "strict"
                            :theme "base"}))
    (reset! initialized? true))
  api)

(defn- next-id []
  (str "vv-mermaid-" (swap! counter inc)))

(defn error-html [message source]
  (str "<div class=\"vv-mermaid-error\"><strong>Mermaid error:</strong> "
       (gstr/htmlEscape (or message "Unable to render diagram"))
       (when (seq source)
         (str "<pre><code>" (gstr/htmlEscape source) "</code></pre>"))
       "</div>"))

(defn render-source
  "Return a Promise resolving to SVG markup for Mermaid source."
  [source]
  (let [source (str/trim (or source ""))]
    (if-let [svg (get-in @svg-cache [:entries source])]
      (js/Promise.resolve svg)
      (-> (load-api!)
          (.then ensure-init!)
          (.then (fn [^js api]
                   (.render api (next-id) source)))
          (.then (fn [^js result]
                   (let [svg (or (.-svg result) result "")]
                     (swap! svg-cache remember source svg)
                     svg)))))))

(defn size-mermaid-svg!
  "Font-match a rendered Mermaid <svg> ELEMENT to `doc-font`: strip Mermaid's inline max-width (which clamps the
   figure to its own natural width and blocks font-match up-scaling), then stamp width + aspect-ratio (CSS
   `.vv-mermaid svg { max-width:100%; height:auto }` owns the column cap + proportional height). `svg-text` is
   the SVG source, used to parse the root viewBox width + dominant font. When Mermaid emits no dominant font,
   figures/svg-style falls back to the natural viewBox width (CSS-capped) — safe, no worse than before."
  [^js svg svg-text doc-font]
  (when svg
    (.removeProperty (.-style svg) "max-width")
    (when-let [{:keys [width aspect-ratio]} (figures/svg-style (figures/parse-svg-meta svg-text) doc-font)]
      (.setProperty (.-style svg) "width" width)
      (when aspect-ratio (.setProperty (.-style svg) "aspect-ratio" aspect-ratio)))))

(defn size-mermaid-svg-string
  "Return `svg-text` with its root <svg> font-matched to the document font — for the standalone Mermaid view,
   which builds its HTML string and sizes it BEFORE insertion (no post-insert flash). Pass-through without a
   DOMParser (:node-test)."
  ([svg-text] (size-mermaid-svg-string svg-text (figures/doc-font-px)))
  ([svg-text doc-font]
   (if-not (exists? js/DOMParser)
     svg-text
     (let [doc (.parseFromString (js/DOMParser.) (or svg-text "") "text/html")]
       (if-let [^js svg (.querySelector doc "svg")]
         (do (size-mermaid-svg! svg svg-text doc-font)
             (.-outerHTML svg))
         svg-text)))))

(defn refit-all!
  "Re-fit live Mermaid diagrams to the CURRENT document font across the whole document — the Mermaid parallel of
   figures/refit-all!, called when the font-size preference changes (which mutates --vv-font-size with no
   re-render). No-op without a document (:node-test)."
  []
  (when (exists? js/document)
    (let [doc-font (figures/doc-font-px)
          svgs     (.querySelectorAll js/document ".vv-mermaid svg")]
      (dotimes [i (.-length svgs)]
        (let [^js svg (aget svgs i)]
          (size-mermaid-svg! svg (.-outerHTML svg) doc-font))))))

(defn- mermaid-code? [^js code]
  (let [classes (.-classList code)]
    (or (.contains classes "language-mermaid")
        (.contains classes "lang-mermaid"))))

(defn- replace-code! [^js doc ^js code doc-font]
  (when (mermaid-code? code)
    (let [source (.-textContent code)
          wrapper (.createElement doc "div")
          target (or (.-parentElement code) code)]
      (.add (.-classList wrapper) "vv-mermaid")
      (-> (render-source source)
          (.then (fn [svg]
                   ;; size the parsed <svg> in this off-DOM doc → baked into the returned string (no flash)
                   (set! (.-innerHTML wrapper) svg)
                   (size-mermaid-svg! (.querySelector wrapper "svg") svg doc-font)
                   (.replaceWith target wrapper)))
          (.catch (fn [e]
                    (set! (.-innerHTML wrapper) (error-html (.-message e) source))
                    (.replaceWith target wrapper)))))))

(defn render-html-diagrams
  "Replace Mermaid fenced-code blocks in rendered Markdown HTML with SVG diagrams, each font-matched to the
   document font. Runs inside the off-DOM DOMParser doc so the sized SVG is baked into the returned string."
  [html]
  (if-not (exists? js/DOMParser)
    (js/Promise.resolve html)
    (let [parser    (js/DOMParser.)
          doc       (.parseFromString parser (or html "") "text/html")
          node-list (.querySelectorAll doc "pre > code.language-mermaid, pre > code.lang-mermaid")
          doc-font  (figures/doc-font-px)
          jobs      (array)]
      (dotimes [i (.-length node-list)]
        (when-let [job (replace-code! doc (.item node-list i) doc-font)]
          (.push jobs job)))
      (if (pos? (.-length jobs))
        (-> (js/Promise.all jobs)
            (.then (fn [_] (.-innerHTML (.-body doc)))))
        (js/Promise.resolve html)))))
