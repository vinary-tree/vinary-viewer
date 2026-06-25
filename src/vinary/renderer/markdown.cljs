(ns vinary.renderer.markdown
  "Markdown → HTML via the unified/remark/rehype pipeline (GFM + heading slugs + syntax highlighting),
   plus a small rehype step that rewrites RELATIVE img/link URLs to absolute file:// against the source
   document's directory. Without it, an embedded `![](../diagrams/foo.svg)` renders to a relative
   `<img src>` that — shown via innerHTML inside resources/public/index.html — resolves against
   resources/public/ (the renderer's file:// base), not the doc's dir, so the figure is blank.
   Runs in the renderer (Chromium): the all-ESM remark stack bundles cleanly and the browser URL
   constructor does the path join (Node `path` is stubbed here). Pure transform; returns Promise<string>."
  (:require ["unified" :refer [unified]]
            ["remark-parse$default"     :as remark-parse]
            ["remark-gfm$default"       :as remark-gfm]
            ["remark-rehype$default"    :as remark-rehype]
            ["rehype-slug$default"      :as rehype-slug]
            ["rehype-highlight$default" :as rehype-highlight]
            ["rehype-stringify$default" :as rehype-stringify]
            [clojure.string :as str]))

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

(defn- absolutize
  "Resolve a possibly-relative URL against base (an absolute file:// dir URL, trailing slash). Leaves
   already-absolute urls untouched — scheme: (http:/file:/data:/mailto:), //host, #anchor — and passes a
   malformed url through unchanged."
  [base url]
  (if (or (not (string? url)) (str/blank? url)
          (re-find #"^(?:[a-zA-Z][a-zA-Z0-9+.-]*:|//|#)" url))
    url
    (try (.-href (js/URL. url base)) (catch :default _ url))))

(defn- walk-rewrite!
  "Depth-first walk of a hast tree, rewriting relative URL attributes on element nodes (no external
   visitor dep). Mutates node.properties in place."
  [^js node base]
  (when node
    (when-let [attrs (and (= "element" (.-type node)) (get url-attrs (.-tagName node)))]
      (when-let [props (.-properties node)]
        (doseq [a attrs]
          (let [v (aget props a)]
            (when (string? v) (aset props a (absolutize base v)))))))
    (when-let [^js kids (.-children node)]
      (dotimes [i (.-length kids)] (walk-rewrite! (aget kids i) base)))))

(defn- rewrite-urls
  "A rehype (hast) transformer plugin: rewrite relative element URLs to absolute file:// against base-dir."
  [base-dir]
  (fn [_opts]
    (fn [tree _file]
      (when (and base-dir (not (str/blank? base-dir)))
        (walk-rewrite! tree (str "file://" base-dir "/")))
      tree)))

(defn render
  "Render a Markdown string to an HTML string. base-dir (the source doc's absolute directory, or nil) is
   used to resolve relative img/link URLs to absolute file://. Returns a Promise<string>."
  [^String md base-dir]
  (-> (unified)
      (.use remark-parse)
      (.use remark-gfm)
      (.use remark-rehype)
      (.use rehype-slug)
      (.use rehype-highlight)
      (.use (rewrite-urls base-dir))
      (.use rehype-stringify)
      (.process md)
      (.then (fn [file] (str file)))))
