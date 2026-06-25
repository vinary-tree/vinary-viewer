(ns vinary.app.link
  "One source of truth for 'what does following this rendered-document link do'. Used by the doc-link
   context menu, the markdown link-click interception, and the Vimium hint activation."
  (:require [clojure.string :as str]))

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
      (let [p (-> href (subs 7) (str/replace #"[?#].*$" "") js/decodeURI)]
        {:kind (if (str/ends-with? p "/") :dir :file) :path p :text text})
      :else nil)))

(defn target-for-anchor
  "Read the right href off an <a> for classify: the raw attribute for #fragments (so the DOM doesn't
   resolve them against index.html), the resolved absolute href otherwise."
  [^js a]
  (let [raw (or (.getAttribute a "href") "")]
    (if (str/starts-with? raw "#") raw (.-href a))))
