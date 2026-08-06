(ns vinary.app.link
  "One source of truth for 'what does following this rendered-document link do'. Used by the doc-link
   context menu, the markdown link-click interception, and the Vimium hint activation."
  (:require [clojure.string :as str]))

(defn- decode-uri-component [s]
  (try (js/decodeURIComponent s) (catch :default _ nil)))

(defn- file-href->path [href]
  (try
    (let [^js u    (js/URL. href)
          host     (.-hostname u)
          decoded  (decode-uri-component (.-pathname u))]
      (when decoded
        (cond
          ;; A non-localhost authority is a UNC host. Forward slashes are accepted by Node's Windows path APIs
          ;; and keep the value platform-neutral while it crosses renderer → main.
          (and (seq host) (not= "localhost" (str/lower-case host)))
          (str "//" host decoded)

          ;; WHATWG file URLs spell a Windows drive as /C:/…; main needs the filesystem path without that
          ;; URL-only leading slash. POSIX /C:/… is theoretically possible but a file URL with this shape is the
          ;; standard, unambiguous Windows drive representation used by Chromium.
          (re-find #"^/[A-Za-z]:(?:/|$)" decoded)
          (subs decoded 1)

          :else decoded)))
    (catch :default _ nil)))

(defn- encode-segments [p]
  (->> (str/split p #"/" -1)
       (map js/encodeURIComponent)
       (str/join "/")))

(defn classify
  "Classify a link href into a navigation target, or nil for an unhandled scheme:
     {:kind :anchor :path <id>}         a same-page #fragment (pass the RAW href for these)
     {:kind :http   :path <url>}        an http(s) URL → the in-app web view
     {:kind :file   :path <abs-path>}   a local file → open in a tab
     {:kind :dir    :path <abs-path>}   a local directory → open in the file manager
   For file/http, pass the resolved absolute href (a.href); for #fragment links pass the raw attribute."
  [href text]
  (let [text (str/trim (or text ""))]
    (cond
      (str/blank? href)               nil
      (str/starts-with? href "#")     {:kind :anchor :path (subs href 1) :text text}
      (re-find #"(?i)^https?://" href) {:kind :http :path href :text text}
      (re-find #"(?i)^file://" href)
      (when-let [p (file-href->path href)]
        {:kind (if (str/ends-with? p "/") :dir :file) :path p :text text})
      ;; Remote document links are main-openable file addresses just like local file:// links. Retain the URI
      ;; verbatim so user/host/port and percent-encoded remote path segments survive the renderer→main hop.
      (re-find #"(?i)^s(?:sh|ftp)://" href)
      {:kind :file :path href :text text}
      :else nil)))

(defn href-for-path
  "Turn a main-resolved path into an anchor href. SSH/SFTP addresses are already absolute URIs. Absolute POSIX,
   Windows drive, and UNC paths become canonical, segment-encoded file URLs so separators remain structural
   while spaces, `#`, `?`, and Unicode filename bytes cannot become URL syntax. Relative/unknown forms fail
   closed because main's successful local resolver always returns an absolute path."
  [path]
  (when (and (string? path) (not (str/blank? path)))
    (cond
      (re-find #"(?i)^s(?:sh|ftp)://" path)
      (try
        (let [^js u (js/URL. path)]
          (when (and (#{"ssh:" "sftp:"} (.-protocol u)) (seq (.-hostname u)))
            (.-href u)))
        (catch :default _ nil))

      ;; UNC: \\server\share\file (or //server/share/file) → file://server/share/file
      (re-find #"^(?:\\\\|//)[^\\/]+[\\/]" path)
      (let [normalized (str/replace path #"\\" "/")
            [_ host tail] (re-matches #"^//([^/]+)/(.*)$" normalized)]
        (when (and host tail)
          (str "file://" host "/" (encode-segments tail))))

      ;; Drive path: C:\dir\file or C:/dir/file → file:///C:/dir/file. Preserve the drive colon.
      (re-find #"^[A-Za-z]:[\\/]" path)
      (let [normalized (str/replace path #"\\" "/")]
        (str "file:///" (subs normalized 0 2) "/" (encode-segments (subs normalized 3))))

      (str/starts-with? path "/")
      (str "file://" (encode-segments path))

      :else nil)))

(defn target-for-anchor
  "Read the right href off an <a> for classify: the raw attribute for #fragments (so the DOM doesn't
   resolve them against index.html), the resolved absolute href otherwise."
  [^js a]
  (let [raw (or (.getAttribute a "href") "")]
    (if (str/starts-with? raw "#") raw (.-href a))))
