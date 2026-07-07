(ns vinary.renderer.markdown
  "Markdown → HTML via the unified/remark/rehype pipeline (GFM + math + heading slugs + syntax highlighting).
   Rehype transforms rewrite relative URLs to source-relative file:// URIs, wrap bare preview images in
   links, and preserve Markdown source positions as data attributes for preview context-menu actions.
   Runs in the renderer (Chromium): the all-ESM remark stack bundles cleanly and the browser URL
   constructor resolves relative paths without Node path APIs. Pure transform; returns
   Promise<{:html :toc :assets}>."
  (:require ["unified" :refer [unified]]
            ["remark-parse$default"     :as remark-parse]
            ["remark-gfm$default"       :as remark-gfm]
            ["remark-math$default"      :as remark-math]
            ["remark-rehype$default"    :as remark-rehype]
            ["rehype-slug$default"      :as rehype-slug]
            ["rehype-highlight$default" :as rehype-highlight]
            ["rehype-raw$default"       :as rehype-raw]
            ["rehype-sanitize$default"  :as rehype-sanitize]
            ["rehype-sanitize$defaultSchema" :as default-schema]
            ["rehype-stringify$default" :as rehype-stringify]
            [clojure.string :as str]
            [vinary.renderer.media :as media]
            [vinary.renderer.math :as math]
            [vinary.renderer.mermaid :as mermaid]
            [vinary.renderer.syntax :as syntax]))

(defn dir-of
  "The directory of an absolute POSIX path (\"/a/b/c.md\" → \"/a/b\"), or nil if there is no \"/\"."
  [p]
  (when (and p (str/index-of p "/"))
    (subs p 0 (.lastIndexOf p "/"))))

;; hast element tagName → the attributes whose relative URLs get absolutized against the doc dir
(def ^:private url-attrs
  {"img"    ["src"]
   "a"      ["href"]
   "source" ["src"]
   "video"  ["src" "poster"]
   "link"   ["href"]})

(def ^:private media-url-attrs
  #{["img" "src"] ["source" "src"] ["video" "src"] ["video" "poster"]})

(defn- absolutize
  "Resolve a possibly-relative URL against base (an absolute file:// dir URL, trailing slash). Leaves
   already-absolute urls untouched — scheme: (http:/file:/data:/mailto:), //host, #anchor — and passes a
   malformed url through unchanged."
  [base url]
  (if (or (not (string? url)) (str/blank? url)
          (re-find #"^(?:[a-zA-Z][a-zA-Z0-9+.-]*:|//|#)" url))
    url
    (try (.-href (js/URL. url base)) (catch :default _ url))))

(defn- rewrite-url [base tag attr url cache-token]
  (let [url (absolutize base url)]
    (if (contains? media-url-attrs [tag attr])
      (media/cache-bust-local-media-url url cache-token)
      url)))

(defn- walk-rewrite!
  "Depth-first walk of a hast tree, rewriting relative URL attributes on element nodes (no external
   visitor dep). Mutates node.properties in place."
  [^js node base cache-token]
  (when node
    (when-let [attrs (and (= "element" (.-type node)) (get url-attrs (.-tagName node)))]
      (when-let [props (.-properties node)]
        (doseq [a attrs]
          (let [v (aget props a)]
            (when (string? v) (aset props a (rewrite-url base (.-tagName node) a v cache-token)))))))
    (when-let [^js kids (.-children node)]
      (dotimes [i (.-length kids)] (walk-rewrite! (aget kids i) base cache-token)))))

(defn- source-props [pos kind]
  (let [props #js {}]
    (when pos
      (when-let [^js start (.-start pos)]
        (aset props "data-vv-source-start-line" (str (.-line start)))
        (aset props "data-vv-source-start-column" (str (.-column start)))
        (when (some? (.-offset start))
          (aset props "data-vv-source-start-offset" (str (.-offset start)))))
      (when-let [^js end (.-end pos)]
        (aset props "data-vv-source-end-line" (str (.-line end)))
        (aset props "data-vv-source-end-column" (str (.-column end)))
        (when (some? (.-offset end))
          (aset props "data-vv-source-end-offset" (str (.-offset end)))))
      (aset props "data-vv-source-kind" kind))
    props))

(defn- merge-source-props! [^js node kind]
  (when-let [pos (.-position node)]
    (let [props (or (.-properties node) #js {})]
      (when-not (.-properties node) (set! (.-properties node) props))
      (let [src (source-props pos kind)]
        (doseq [k ["data-vv-source-start-line"
                   "data-vv-source-start-column"
                   "data-vv-source-start-offset"
                   "data-vv-source-end-line"
                   "data-vv-source-end-column"
                   "data-vv-source-end-offset"
                   "data-vv-source-kind"]]
          (when-let [v (aget src k)]
            (aset props k v)))))))

(defn- source-text-span [^js node]
  #js {:type "element"
       :tagName "span"
       :properties (source-props (.-position node) "text")
       :children #js [node]
       :position (.-position node)})

(defn- annotate-source! [^js node]
  (when node
    (when (= "element" (.-type node))
      (merge-source-props! node "element"))
    (when-let [^js kids (.-children node)]
      (dotimes [i (.-length kids)]
        (let [child (aget kids i)]
          (if (and (= "text" (.-type child))
                   (.-position child)
                   (not (str/blank? (.-value child))))
            (aset kids i (source-text-span child))
            (annotate-source! child)))))))

(defn- wrap-image-node [^js img]
  (let [props (or (.-properties img) #js {})
        src   (aget props "src")]
    (when (and (string? src) (not (str/blank? src)))
      #js {:type "element"
           :tagName "a"
           :properties #js {:href src :className #js ["vv-figure-link"]}
           :children #js [img]
           :position (.-position img)})))

(defn- wrap-unlinked-images! [^js node parent-tag]
  (when node
    (when-let [^js kids (.-children node)]
      (dotimes [i (.-length kids)]
        (let [child (aget kids i)
              tag   (.-tagName child)]
          (if (and (= "element" (.-type child))
                   (= "img" tag)
                   (not= "a" parent-tag))
            (when-let [wrapped (wrap-image-node child)]
              (aset kids i wrapped))
            (wrap-unlinked-images! child tag)))))))

(defn- hast-text
  "Text content for a HAST subtree, matching the browser's rendered text closely enough for TOC labels."
  [^js node]
  (cond
    (nil? node) ""
    (= "text" (.-type node)) (or (.-value node) "")
    :else
    (if-let [^js kids (.-children node)]
      (apply str (for [i (range (.-length kids))] (hast-text (aget kids i))))
      "")))

(defn- heading-level [tag]
  (when-let [[_ n] (and (string? tag) (re-matches #"h([1-6])" tag))]
    (js/parseInt n)))

(defn- collect-metadata!
  "Collect derived render metadata from the already-mutated HAST tree. This avoids reparsing the final
   HTML just to find headings and local media paths."
  [state ^js node]
  (when node
    (when (= "element" (.-type node))
      (let [tag   (.-tagName node)
            props (.-properties node)]
        (when-let [level (heading-level tag)]
          (when-let [id (and props (aget props "id"))]
            (when-not (str/blank? id)
              (swap! state update :toc conj {:level level :text (str/trim (hast-text node)) :id id}))))
        (when-let [attrs (seq (for [[media-tag attr] media-url-attrs
                                    :when (= media-tag tag)]
                                attr))]
          (doseq [attr attrs
                  :let [url (and props (aget props attr))
                        path (media/local-media-path url)]
                  :when path]
            (swap! state update :assets conj path)))))
    (when-let [^js kids (.-children node)]
      (dotimes [i (.-length kids)] (collect-metadata! state (aget kids i))))))

(defn- rewrite-urls
  "A rehype (hast) transformer plugin: rewrite relative element URLs to absolute file:// against base-dir."
  [base-dir cache-token]
  (fn [_opts]
    (fn [tree _file]
      (when (and base-dir (not (str/blank? base-dir)))
        (walk-rewrite! tree (str "file://" base-dir "/") cache-token))
      tree)))

(defn- wrap-images
  "A rehype transformer plugin: wrap bare images in links to their resolved src URI."
  []
  (fn [_opts]
    (fn [tree _file]
      (wrap-unlinked-images! tree nil)
      tree)))

(defn- source-positions
  "A rehype transformer plugin: expose Markdown source positions to the preview DOM."
  []
  (fn [_opts]
    (fn [tree _file]
      (annotate-source! tree)
      tree)))

(defn- collect-metadata
  "A rehype transformer plugin: collect TOC headings and local media asset paths after URL rewriting."
  [state]
  (fn [_opts]
    (fn [tree _file]
      (collect-metadata! state tree)
      tree)))

;; GitHub's HTML sanitize allowlist — hast-util-sanitize's defaultSchema IS the policy GitHub uses — plus
;; two minimal, safe extensions: (1) keep `code.math-inline`/`code.math-display` so render-html-math still
;; distinguishes inline vs display math (defaultSchema only keeps /^language-./); (2) allow `data:` image
;; URIs so `![](data:image/svg+xml,…)` still renders. structuredClone so the shared defaultSchema isn't
;; mutated. Everything dangerous (script/style/iframe/on*-handlers/javascript:/vbscript:/file:/<base>/<meta>)
;; stays blocked by the default schema — the desired GitHub posture.
(def ^:private sanitize-schema
  ;; aget with string literals (not .-prop) so the :advanced release build can't mangle these accesses on
  ;; the external defaultSchema object.
  (let [s     (js/structuredClone default-schema)
        attrs (aget s "attributes")]
    (.push (aget (aget attrs "code") 0) "math-inline" "math-display")
    (.push (aget (aget s "protocols") "src") "data")
    s))

(defn render
  "Render a Markdown string. base-dir (the source doc's absolute directory, or nil) is used to resolve
   relative img/link URLs to absolute file://. Returns a Promise resolving to
   {:html string :toc [{:level :text :id}] :assets [absolute-path ...]}."
  ([^String md base-dir] (render md base-dir nil))
  ([^String md base-dir cache-token]
   (let [metadata (atom {:toc [] :assets #{}})]
     (-> (unified)
         (.use remark-parse)
         (.use remark-gfm)
         (.use remark-math)
         ;; allowDangerousHtml keeps raw HTML as `raw` nodes; rehype-raw parses them into real hast elements;
         ;; rehype-sanitize (GitHub allowlist) then strips anything dangerous. This MUST run before the app's
         ;; own trusted hast plugins (rewrite-urls/wrap-images/source-positions/slug) so their post-sanitize
         ;; additions (file:// srcs, data-vv-source-*, ids, vv-figure-link) survive — sanitize-last would strip
         ;; every app-generated file:// image src and break all local images.
         (.use remark-rehype #js {:allowDangerousHtml true})
         (.use rehype-raw)
         (.use rehype-sanitize sanitize-schema)
         (.use rehype-slug)
         (.use rehype-highlight)
         (.use (rewrite-urls base-dir cache-token))
         (.use (wrap-images))
         (.use (source-positions))
         (.use (collect-metadata metadata))
         (.use rehype-stringify)
         (.process (math/normalize-github-math-escapes md))
         (.then (fn [file] (math/render-html-math (str file))))
         (.then mermaid/render-html-diagrams)
         (.then syntax/highlight-html-code-blocks)
         (.then (fn [html]
                  {:html html
                   :toc (:toc @metadata)
                   :assets (vec (:assets @metadata))}))))))
