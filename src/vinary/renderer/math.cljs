(ns vinary.renderer.math
  "MathJax rendering helpers for Markdown math.

   TeX is converted to SVG before the Markdown HTML is committed to the preview DOM. The conversion is
  synchronous and cached, which avoids the visible post-insert typesetting pass that makes large
  documents feel sluggish."
  (:require [clojure.string :as str]
            [goog.string :as gstr]))

(def ^:private max-cache-entries 512)

(defonce ^:private svg-cache (atom {:entries {} :order []}))
(defonce ^:private node-engine (atom nil))
(defonce ^:private browser-load-promise (atom nil))

(defn- remember [state k v]
  (let [order   (vec (cons k (remove #{k} (:order state))))
        entries (assoc (:entries state) k v)]
    (if (<= (count order) max-cache-entries)
      {:entries entries :order order}
      (let [drop-keys (subvec order max-cache-entries)]
        {:entries (apply dissoc entries drop-keys)
         :order   (subvec order 0 max-cache-entries)}))))

(defn- browser-document? []
  (exists? js/document))

(defn- mathjax-api []
  (.-MathJax js/globalThis))

(defn- browser-ready? [^js api]
  (and api (exists? (.-tex2svg api))))

(defn- mathjax-src []
  (str (js/URL. "../../node_modules/mathjax-full/es5/tex-svg.js"
                (.-baseURI js/document))))

(defn- configure-browser! []
  (when-not (mathjax-api)
    (set! (.-MathJax js/globalThis)
          (clj->js {:startup {:typeset false}
                    :tex     {:packages {"[+]" ["noerrors" "noundefined"]}}
                    :svg     {:fontCache "none"}}))))

(defn- load-browser-api! []
  (let [api (mathjax-api)]
    (cond
      (browser-ready? api)
      (js/Promise.resolve api)

      (not (browser-document?))
      (js/Promise.reject (js/Error. "MathJax browser rendering requires a browser document"))

      @browser-load-promise
      @browser-load-promise

      :else
      (let [p (js/Promise.
               (fn [resolve reject]
                 (configure-browser!)
                 (let [doc    js/document
                       script (.createElement doc "script")]
                   (set! (.-src script) (mathjax-src))
                   (set! (.-async script) true)
                   (set! (.-onload script)
                         (fn []
                           (let [api (mathjax-api)
                                 ready (or (some-> api .-startup .-promise)
                                           (js/Promise.resolve api))]
                             (-> ready
                                 (.then (fn []
                                          (let [api (mathjax-api)]
                                            (if (browser-ready? api)
                                              (resolve api)
                                              (do
                                                (reset! browser-load-promise nil)
                                                (reject (js/Error. "MathJax browser component did not expose tex2svg")))))))
                                 (.catch (fn [e]
                                           (reset! browser-load-promise nil)
                                           (reject e)))))))
                   (set! (.-onerror script)
                         (fn []
                           (reset! browser-load-promise nil)
                           (reject (js/Error. "Unable to load MathJax browser component"))))
                   (.appendChild (.-head doc) script))))]
        (reset! browser-load-promise p)
        p))))

(defn- node-engine! []
  (or @node-engine
      (let [mathjax-mod (js/require "mathjax-full/js/mathjax.js")
            tex-mod     (js/require "mathjax-full/js/input/tex.js")
            svg-mod     (js/require "mathjax-full/js/output/svg.js")
            adaptor-mod (js/require "mathjax-full/js/adaptors/liteAdaptor.js")
            html-mod    (js/require "mathjax-full/js/handlers/html.js")
            adaptor     ((.-liteAdaptor adaptor-mod))
            tex-input   (new (.-TeX tex-mod) #js {:packages #js ["base" "ams" "noerrors" "noundefined"]})
            svg-output  (new (.-SVG svg-mod) #js {:fontCache "none"})
            _           ((.-RegisterHTMLHandler html-mod) adaptor)
            math-doc    (.document (.-mathjax mathjax-mod)
                                   ""
                                   #js {:InputJax tex-input
                                        :OutputJax svg-output})
            engine      {:adaptor adaptor :math-doc math-doc}]
        (reset! node-engine engine)
        engine)))

(defn- remember-render! [k html]
  (swap! svg-cache remember k html)
  html)

(defn- render-browser-tex [^js api source display? k]
  (let [node (.tex2svg api source #js {:display (boolean display?)})]
    (remember-render! k (.-outerHTML node))))

(defn- render-node-tex [source display? k]
  (let [{:keys [^js adaptor ^js math-doc]} (node-engine!)
        node (.convert math-doc source #js {:display (boolean display?)})]
    (remember-render! k (.outerHTML adaptor node))))

(defn render-tex
  "Render TeX to a MathJax SVG container string. display? selects inline vs display math metrics."
  [source display?]
  (let [source (or source "")
        k      [(boolean display?) source]]
    (if-let [html (get-in @svg-cache [:entries k])]
      html
      (if-let [api (and (browser-document?) (mathjax-api))]
        (if (browser-ready? api)
          (render-browser-tex api source display? k)
          (throw (js/Error. "MathJax browser component is not ready; use render-tex-async")))
        (render-node-tex source display? k)))))

(defn- render-tex-async [source display?]
  (let [source (or source "")
        k      [(boolean display?) source]]
    (if-let [html (get-in @svg-cache [:entries k])]
      (js/Promise.resolve html)
      (if (browser-document?)
        (-> (load-browser-api!)
            (.then (fn [api] (render-browser-tex api source display? k))))
        (js/Promise.resolve (render-node-tex source display? k))))))

(defn normalize-github-math-escapes
  "GitHub supports dollar-delimited math with backtick-wrapped contents, e.g. $`x^2`$.

   remark-math does not parse that escape form directly, so normalize it before parsing."
  [markdown]
  (str/replace (or markdown "") #"\$`([^`]+)`\$"
               (fn [[_ source]] (str "$" source "$"))))

(defn- display-code? [^js code]
  (or (.contains (.-classList code) "math-display")
      (some-> code .-parentElement .-tagName (= "PRE"))))

(defn- math-code? [^js code]
  (let [classes (.-classList code)]
    (or (.contains classes "language-math")
        (.contains classes "lang-math")
        (.contains classes "math-inline")
        (.contains classes "math-display"))))

(defn render-html-math
  "Replace remark-math code placeholders in rendered Markdown HTML with cached MathJax SVG."
  [html]
  (if-not (exists? js/DOMParser)
      (js/Promise.resolve html)
    (let [parser    (js/DOMParser.)
          doc       (.parseFromString parser (or html "") "text/html")
          node-list (.querySelectorAll doc "code.language-math, code.lang-math, code.math-inline, code.math-display")
          jobs      (array)]
      (dotimes [i (.-length node-list)]
        (let [code     (.item node-list i)
              display? (display-code? code)
              wrapper  (.createElement doc (if display? "div" "span"))
              target   (if display? (.-parentElement code) code)]
          (.add (.-classList wrapper) (if display? "vv-math-display" "vv-math-inline"))
          (.push jobs
                 (-> (render-tex-async (str/trim (.-textContent code)) display?)
                     (.then (fn [svg]
                              (set! (.-innerHTML wrapper) svg)
                              (.replaceWith target wrapper)))
                     (.catch (fn [e]
                               (set! (.-textContent wrapper) (str "MathJax error: " (.-message e)))
                               (.add (.-classList wrapper) "vv-math-error")
                               (.replaceWith target wrapper)))))))
      (if (pos? (.-length jobs))
        (-> (js/Promise.all jobs)
            (.then (fn [_] (.-innerHTML (.-body doc)))))
        (js/Promise.resolve (.-innerHTML (.-body doc)))))))

(defn error-html [message source]
  (str "<div class=\"vv-math-error\"><strong>MathJax error:</strong> "
       (gstr/htmlEscape (or message "Unable to render math"))
       (when (seq source)
         (str "<pre><code>" (gstr/htmlEscape source) "</code></pre>"))
       "</div>"))
