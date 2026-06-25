(ns vinary.app.uri
  "URI helpers. A tab's :uri is the canonical address it shows — an absolute local file path
   (\"/home/u/README.md\") or an http(s) URL. Local paths are displayed file://-prefixed in the URI bar."
  (:require [clojure.string :as str]))

(defn http? [uri] (boolean (and uri (re-find #"(?i)^https?://" uri))))

(defn file-path
  "The local filesystem path for a uri: a leading file:// stripped; http(s) → nil; else the uri as-is."
  [uri]
  (cond (nil? uri)                        nil
        (http? uri)                       nil
        (str/starts-with? uri "file://")  (subs uri 7)
        :else                             uri))

(defn normalize
  "Canonical tab :uri from user/tree input: blank → nil; http(s) kept; file:// stripped to a path; else
   the text as-is (an absolute path)."
  [text]
  (let [t (str/trim (or text ""))]
    (cond (str/blank? t)                  nil
          (http? t)                       t
          (str/starts-with? t "file://")  (subs t 7)
          :else                           t)))

(defn display
  "How a uri appears in the URI bar: http(s) as-is; a local path shown file://-prefixed."
  [uri]
  (cond (nil? uri) "" (http? uri) uri :else (str "file://" uri)))

(defn basename
  "Short tab label: a file's basename, or for http the last path segment (falling back to the host)."
  [uri]
  (cond
    (nil? uri)  ""
    (http? uri) (try (let [u   (js/URL. uri)
                           seg (last (remove str/blank? (str/split (.-pathname u) #"/")))]
                       (or (not-empty seg) (.-hostname u)))
                     (catch :default _ uri))
    :else       (last (str/split uri #"/"))))
