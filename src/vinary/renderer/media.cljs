(ns vinary.renderer.media
  "Helpers for local media URLs rendered inside Markdown previews."
  (:require [clojure.string :as str]))

(def ^:private media-ext-re #"\.(svg|png|jpe?g|gif|webp|bmp|ico|avif)$")
(def ^:private media-selector "img[src],source[src],video[src],video[poster]")

(defn- parse-url [url]
  (try
    (when (string? url) (js/URL. url))
    (catch :default _ nil)))

(defn- local-media-url [url]
  (when-let [^js u (parse-url url)]
    (let [path (.-pathname u)]
      (when (and (= "file:" (.-protocol u))
                 (re-find media-ext-re (str/lower-case path)))
        u))))

(defn local-media-path
  "Return the decoded filesystem path for a local file:// media URL, ignoring query/hash cache tokens."
  [url]
  (when-let [^js u (local-media-url url)]
    (js/decodeURIComponent (.-pathname u))))

(defn path->file-url
  "Encode an absolute POSIX filesystem path as a file:// URL."
  [path]
  (when (string? path)
    (str "file://" (->> (str/split path #"/")
                        (map js/encodeURIComponent)
                        (str/join "/")))))

(defn cache-bust-local-media-url
  "Append or replace vv-cache on local file:// media URLs; leave every other URL untouched."
  [url token]
  (if (and token (local-media-url url))
    (let [^js u (js/URL. url)]
      (.set (.-searchParams u) "vv-cache" (str token))
      (.-href u))
    url))

(defn- attr-url [^js el attr]
  (or (aget el attr) (.getAttribute el attr)))

(defn local-media-paths-from-root
  "Collect distinct local media file paths referenced below a DOM root."
  [^js root]
  (if-not root
    []
    (let [els (.querySelectorAll root media-selector)]
      (->> (for [i (range (.-length els))
                 :let [^js el (aget els i)]
                 attr ["src" "poster"]
                 :let [path (local-media-path (attr-url el attr))]
                 :when path]
             path)
           distinct
           vec))))

(defn local-media-paths-from-html
  "Collect local media file paths from a rendered HTML string."
  [html]
  (if (str/blank? html)
    []
    (let [doc (.parseFromString (js/DOMParser.) html "text/html")]
      (local-media-paths-from-root doc))))

(defn remote-url? [url] (boolean (and (string? url) (re-find #"(?i)^s(?:sh|ftp)://" url))))

(defn remote->vv-remote-url
  "Map a remote doc URI (ssh://user@host:port/path) to the privileged vv-remote:// scheme the web view loads for
   live-rendering remote HTML. The whole ssh tree is remapped 1:1, so the page's relative assets resolve back to
   vv-remote:// URLs that main serves over SFTP (main/web.cljs). Returns nil for a non-remote uri."
  [uri]
  (when-let [m (re-find #"(?i)^s(?:sh|ftp)://(.*)$" (str uri))]
    (str "vv-remote://" (second m))))

(defn resolve-remote-media!
  "Post-DOM pass for a REMOTE document: replace every remote (ssh://sftp://) media URL under `node` with a data:
   URL fetched over the vv bridge (vv:load-remote-asset → main reads the bytes over SFTP). Neither the sandboxed
   renderer nor file:// can reach the host, so inlining as data: is the only CSP-safe path. Async + best-effort
   per element; a failed fetch simply leaves the broken image (no crash)."
  [^js node doc-path]
  (when-let [^js vv (.-vv js/window)]
    (when (and node (.-loadRemoteAsset vv))
      (let [els (.querySelectorAll node media-selector)]
        (dotimes [i (.-length els)]
          (let [^js el (aget els i)]
            (doseq [attr ["src" "poster"]]
              (let [url (.getAttribute el attr)]
                (when (remote-url? url)
                  (-> (.loadRemoteAsset vv (clj->js {:uri url :relativeTo doc-path}))
                      (.then (fn [data-url] (when (and data-url (.-isConnected el)) (.setAttribute el attr data-url))))
                      (.catch (fn [_] nil))))))))))))
