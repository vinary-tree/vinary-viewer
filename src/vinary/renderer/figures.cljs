(ns vinary.renderer.figures
  "SVG figure font-matching for embedded markdown figures: size each embedded .svg <img> so its internal
   text matches the document's font — width = docFont · viewBoxWidth / svgDominantFontSize — scaling down
   as well as up; an oversized figure falls back to its natural viewBox width capped to the column; raster
   images keep their natural size (centered by CSS). Ported from the v0.1.0 vmd scaleFigures, but FETCHES
   the SVG text (the sandboxed renderer has no fs) and memoizes the parsed geometry by URL.

   (A d2/PlantUML SVG often has only a viewBox and no width/height, so the <img> is intrinsic-size-less
   and the browser otherwise stretches it to the full column, magnifying its text.)"
  (:require [clojure.string :as str]))

(defonce ^:private meta-cache (atom {}))   ; abs svg url → {:v viewBox-width :f dominant-font}
(defonce ^:private inflight   (atom {}))   ; abs svg url → Promise (dedupe concurrent fetches)

(defn- parse-svg-meta
  "Extract {:v viewBox-width :f dominant-font-size} from SVG source text. viewBox width = the 3rd number
   of `viewBox=\"minX minY W H\"` (fallback: a `<svg width=\"Npx\">`); dominant font = the most-frequent
   rounded `font-size:` value across the file. Either may be 0 → natural sizing."
  [txt]
  (let [vb (re-find #"viewBox\s*=\s*[\"']\s*[-\d.eE]+\s+[-\d.eE]+\s+([-\d.eE]+)\s+([-\d.eE]+)" txt)
        w  (when-not vb (re-find #"<svg[^>]*\swidth\s*=\s*[\"']([\d.]+)(?:px)?[\"']" txt))
        v  (cond vb (js/parseFloat (nth vb 1)) w (js/parseFloat (nth w 1)) :else 0)
        counts (let [re (js/RegExp. "font-size\\s*[:=]\\s*[\"']?\\s*([\\d.]+)" "g")]
                 (loop [acc {}]
                   (if-let [m (.exec re txt)]
                     (let [k (js/Math.round (js/parseFloat (aget m 1)))]
                       (recur (if (pos? k) (update acc k (fnil inc 0)) acc)))
                     acc)))
        f  (->> counts (sort-by val >) ffirst (#(or % 0)))]
    {:v v :f f}))

(defn- fetch-meta
  "Promise<{:v :f}> for an absolute file:// svg url; memoized + in-flight de-duped. Unreadable → {:v 0 :f 0}."
  [url]
  (or (some-> (get @meta-cache url) js/Promise.resolve)
      (get @inflight url)
      (let [p (-> (js/fetch url)
                  (.then (fn [^js r] (.text r)))
                  (.then (fn [txt]
                           (let [m (parse-svg-meta txt)]
                             (swap! meta-cache assoc url m)
                             (swap! inflight dissoc url)
                             m)))
                  (.catch (fn [_]
                            (let [m {:v 0 :f 0}]
                              (swap! meta-cache assoc url m)
                              (swap! inflight dissoc url)
                              m))))]
        (swap! inflight assoc url p)
        p)))

(defn scale-figures!
  "Size each embedded SVG <img> in `body` (a .markdown-body element) so its text == the doc font. Async
   per <img> (fetch); applies the width when the fetch resolves. No-op inside the diagram view and for
   emoji / non-file / non-svg images (those clear any inline width → CSS owns them)."
  [^js body]
  (when (and body (not (.closest body ".vv-diagram")))
    (let [cs       (js/getComputedStyle body)
          avail    (- (.-clientWidth body)
                      (js/parseFloat (or (.-paddingLeft cs) "0"))
                      (js/parseFloat (or (.-paddingRight cs) "0")))
          doc-font (or (js/parseFloat (.-fontSize cs)) 16)
          imgs     (.querySelectorAll body "img")]
      (dotimes [i (.-length imgs)]
        (let [^js img (aget imgs i)
              src     (or (.getAttribute img "src") "")
              url     (.-src img)                          ; resolved absolute URL
              path    (str/replace url #"[?#].*$" "")]
          (cond
            (re-find #"(?i)^emoji" src)         nil
            (not (re-find #"(?i)^file://" url)) (set! (.. img -style -width) "")
            (not (re-find #"(?i)\.svg$" path))  (set! (.. img -style -width) "")
            :else
            (-> (fetch-meta url)
                (.then (fn [{:keys [v f]}]
                         (if (and v (pos? v))
                           (let [target (if (and (pos? f) (pos? avail))
                                          (let [matched (* doc-font (/ v f))]
                                            (if (<= matched avail) matched (js/Math.min v avail)))
                                          (if (pos? avail) (js/Math.min v avail) v))]
                             (set! (.. img -style -width) (str (js/Math.round target) "px")))
                           (set! (.. img -style -width) "")))))))))))
