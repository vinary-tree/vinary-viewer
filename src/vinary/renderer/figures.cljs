(ns vinary.renderer.figures
  "Embedded-image sizing for markdown figures. Two jobs:
     1. local .svg figures: font-match — size each <img> so its internal text matches the document font
        (width = docFont · viewBoxWidth / svgDominantFontSize, scaling down as well as up; an oversized
        figure falls back to its natural viewBox width capped to the column).
     2. raster (png/jpg/gif/webp/…) + remote images: RESERVE their layout box from intrinsic dimensions —
        stamp width/height ATTRIBUTES (from a header-parse for local files, else an off-DOM Image decode),
        so the browser reserves the correctly-proportioned box (via CSS max-width:100%; height:auto) BEFORE
        the bytes decode. Without this, an image lays out at ~0 height then pops to full height as it decodes
        while scrolling, shoving following content — the markdown 'jumpy scrolling' bug.
   The sandboxed renderer has no fs, so geometry is FETCHED and memoized by URL (so re-renders are stable).

   (A d2/PlantUML SVG often has only a viewBox and no width/height, so the <img> is intrinsic-size-less
   and the browser otherwise stretches it to the full column, magnifying its text.)"
  (:require [clojure.string :as str]))

(defonce ^:private meta-cache (atom {}))   ; abs svg url -> {:v viewBox-width :h viewBox-height :f dominant-font}
(defonce ^:private inflight   (atom {}))   ; abs svg url → Promise (dedupe concurrent fetches)

(defn- parse-positive-float [s]
  (let [n (js/parseFloat s)]
    (if (and (not (js/isNaN n)) (pos? n)) n 0)))

(defn- positive-number? [n]
  (and (number? n) (not (js/isNaN n)) (pos? n)))

(defn parse-svg-meta
  "Extract {:v viewBox-width :h viewBox-height :f dominant-font-size} from SVG source text. viewBox
   dimensions fall back to `<svg width=\"Npx\" height=\"Npx\">`; dominant font is the most-frequent
   rounded `font-size:` value across the file. Missing dimensions may be 0 -> natural sizing."
  [txt]
  ;; Read dimensions from the ROOT <svg> opening tag ONLY. svgbob output has no root viewBox but embeds
  ;; nested <marker>/<symbol> viewBoxes (e.g. 8×8 arrow-heads); a whole-text viewBox scan would grab one of
  ;; those as the figure width and shrink the image to a few px ("tiny white square"). Scoping to the root
  ;; tag falls back to its width/height (the true figure size) instead.
  (let [root (or (re-find #"(?i)<svg\b[^>]*>" txt) "")
        vb (re-find #"viewBox\s*=\s*[\"']\s*[-\d.eE]+\s+[-\d.eE]+\s+([-\d.eE]+)\s+([-\d.eE]+)" root)
        w  (when-not vb (re-find #"\swidth\s*=\s*[\"']([\d.]+)(?:px)?[\"']" root))
        h  (when-not vb (re-find #"\sheight\s*=\s*[\"']([\d.]+)(?:px)?[\"']" root))
        v  (cond vb (parse-positive-float (nth vb 1)) w (parse-positive-float (nth w 1)) :else 0)
        vh (cond vb (parse-positive-float (nth vb 2)) h (parse-positive-float (nth h 1)) :else 0)
        counts (let [re (js/RegExp. "font-size\\s*[:=]\\s*[\"']?\\s*([\\d.]+)" "g")]
                 (loop [acc {}]
                   (if-let [m (.exec re txt)]
                     (let [k (js/Math.round (js/parseFloat (aget m 1)))]
                       (recur (if (pos? k) (update acc k (fnil inc 0)) acc)))
                     acc)))
        f  (->> counts (sort-by val >) ffirst (#(or % 0)))]
    {:v v :h vh :f f}))

(defn- read-dims-from-header
  "Intrinsic {:w :h} from a raster file's HEADER bytes (PNG / GIF / JPEG) WITHOUT decoding — or nil if the
   format isn't one we header-parse (the caller then falls back to an off-DOM Image decode). Header-parsing
   avoids forcing an eager decode of every (incl. offscreen) image. `buf` is a js/ArrayBuffer.
   PNG: IHDR width@16 / height@20 (BE u32). GIF: width@6 / height@8 (LE u16). JPEG: scan to the first SOFn."
  [^js buf]
  (let [n  (.-byteLength buf)
        dv (js/DataView. buf)
        u8 (fn [i] (.getUint8 dv i))]
    (cond
      ;; PNG: \x89 P N G …, IHDR dims at fixed offsets
      (and (>= n 24) (= 0x89 (u8 0)) (= 0x50 (u8 1)) (= 0x4E (u8 2)) (= 0x47 (u8 3)))
      {:w (.getUint32 dv 16 false) :h (.getUint32 dv 20 false)}
      ;; GIF87a / GIF89a: logical-screen width/height (LE u16)
      (and (>= n 10) (= 0x47 (u8 0)) (= 0x49 (u8 1)) (= 0x46 (u8 2)))
      {:w (.getUint16 dv 6 true) :h (.getUint16 dv 8 true)}
      ;; JPEG: \xFF\xD8, then walk segments to the first Start-Of-Frame marker (carries the frame dims)
      (and (>= n 4) (= 0xFF (u8 0)) (= 0xD8 (u8 1)))
      (loop [off 2]
        (cond
          (> (+ off 9) n)      nil
          (not= 0xFF (u8 off)) (recur (inc off))                      ; resync to the next marker byte
          :else
          (let [m (u8 (inc off))]
            (cond
              ;; SOF0..SOF15 carry [precision, height(2), width(2)] — EXCEPT DHT(C4)/JPG(C8)/DAC(CC)
              (and (>= m 0xC0) (<= m 0xCF) (not= m 0xC4) (not= m 0xC8) (not= m 0xCC))
              {:h (.getUint16 dv (+ off 5) false) :w (.getUint16 dv (+ off 7) false)}
              ;; standalone markers with no length payload: TEM(01), RSTn(D0..D7)
              (or (= m 0x01) (and (>= m 0xD0) (<= m 0xD7)))
              (recur (+ off 2))
              :else
              (let [seg (.getUint16 dv (+ off 2) false)]              ; segment length includes its own 2 bytes
                (if (>= seg 2) (recur (+ off 2 seg)) nil))))))
      :else nil)))

(defn- image-dims
  "Promise<{:w :h} | nil> via an off-DOM Image decode — reads naturalWidth/Height (CORS-safe for remote
   images; used for raster formats we don't header-parse and for http(s)/data URLs)."
  [url]
  (js/Promise.
   (fn [resolve _reject]
     (let [^js im (js/Image.)]
       (set! (.-onload im)  (fn [] (let [w (.-naturalWidth im) h (.-naturalHeight im)]
                                     (resolve (when (and (pos? w) (pos? h)) {:w w :h h})))))
       (set! (.-onerror im) (fn [] (resolve nil)))
       (set! (.-src im) url)))))

(defn- fetch-meta
  "Promise<{:v :h :f}> for an absolute file:// svg url; memoized + in-flight de-duped. Unreadable -> zero metadata."
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
                            (let [m {:v 0 :h 0 :f 0}]
                              (swap! meta-cache assoc url m)
                              (swap! inflight dissoc url)
                              m))))]
        (swap! inflight assoc url p)
        p)))

(defn- fetch-raster-dims
  "Promise<{:w :h}> for a raster/remote image url; memoized + in-flight de-duped in meta-cache (so a re-render
   — live-refresh, tab re-nav — stamps the box SYNCHRONOUSLY before the new <img> loads → stable). Local
   file:// → header-parse (no decode); remote / unparseable → off-DOM Image decode. Unreadable → {:w 0 :h 0}."
  [url local?]
  (or (some-> (get @meta-cache url) js/Promise.resolve)
      (get @inflight url)
      (let [finish (fn [m] (let [v (or m {:w 0 :h 0})]
                             (swap! meta-cache assoc url v)
                             (swap! inflight dissoc url)
                             v))
            p (-> (if local?
                    (-> (js/fetch url)
                        (.then (fn [^js r] (.arrayBuffer r)))
                        (.then (fn [buf] (or (read-dims-from-header buf) (image-dims url)))))
                    (image-dims url))
                  (.then finish)
                  (.catch (fn [_] (finish nil))))]
        (swap! inflight assoc url p)
        p)))

(defn- clear-size! [^js img]
  (let [style (.-style img)]
    (.removeProperty style "width")
    (.removeProperty style "height")
    (.removeProperty style "aspect-ratio")))

(defn- set-style! [^js style prop value]
  (when (not= (.getPropertyValue style prop) value)
    (.setProperty style prop value)))

;; Only the superseded apply-svg-size! stamped an !important px height; the pre-DOM path uses aspect-ratio
;; instead (see svg-style), so this helper is #_-disabled (not deleted) per the comment-don't-delete rule.
#_(defn- set-style-important! [^js style prop value]
    (when (or (not= (.getPropertyValue style prop) value)
              (not= (.getPropertyPriority style prop) "important"))
      (.setProperty style prop value "important")))

(defn target-width
  "Font-matched display width (px) for an SVG figure of viewBox width `v` and dominant font-size `f`, so its
   internal text renders at the document font `doc-font`: width = doc-font · v / f. Falls back to the natural
   viewBox width `v` when there is no font to match. The COLUMN CAP is owned by CSS (max-width:100%), NOT this
   function — so the width is resize-independent and identical whether computed pre-DOM (off-DOM string) or on
   a live node. Public so the pre-DOM pass, the Mermaid sizer, and the DOM-free tests can call it."
  [v f doc-font]
  (if (and (positive-number? f) (positive-number? doc-font))
    (* doc-font (/ v f))
    v))

(defn svg-style
  "Pure inline sizing for a font-matched SVG figure from its parsed {:v :h :f} metadata and the document font:
   {:width \"Npx\" :aspect-ratio \"v / h\"} (aspect-ratio omitted when the height is unknown), or nil when the SVG
   has no usable viewBox width. Only width + aspect-ratio are emitted — NO explicit px height: CSS (max-width:
   100%; height:auto) owns the column cap and the proportional height, and a fixed px height would break the
   ratio once a wide figure is capped to a narrow column."
  [{:keys [v h f]} doc-font]
  (when (positive-number? v)
    (cond-> {:width (str (js/Math.round (target-width v f doc-font)) "px")}
      (positive-number? h) (assoc :aspect-ratio (str v " / " h)))))

(defn doc-font-px
  "The document body font size in px, read from the --vv-font-size CSS custom property on :root (the value
   .markdown-body resolves to). Defaults to 15 (app.css's --vv-font-size default) when unset/unreadable.
   Browser-only — callers guard on js/document / js/DOMParser."
  []
  (let [v (some-> (js/getComputedStyle js/document.documentElement)
                  (.getPropertyValue "--vv-font-size")
                  js/parseFloat)]
    (if (positive-number? v) v 15)))

(defn- stamp-svg!
  "Stamp font-matched width + aspect-ratio onto an <img>'s inline style (NO isConnected/ancestor guard — for
   the off-DOM pre-render pass). Idempotent: set-style! writes only on change."
  [^js img meta doc-font]
  (let [style (.-style img)]
    (if-let [{:keys [width aspect-ratio]} (svg-style meta doc-font)]
      (do (set-style! style "width" width)
          (if aspect-ratio
            (set-style! style "aspect-ratio" aspect-ratio)
            (.removeProperty style "aspect-ratio")))
      (clear-size! img))))

(defn- stamp-raster!
  "Reserve a raster/remote <img>'s layout box by stamping width/height ATTRIBUTES (the browser derives
   aspect-ratio; CSS max-width:100%; height:auto reserves the proportioned box before the bytes decode, so the
   image does not pop from ~0 to full height while scrolling). Author-specified dims are respected; unknown
   dims clear inline sizing (CSS owns). Guard-free — for the off-DOM pre-render pass."
  [^js img {:keys [w h]}]
  (if (and (positive-number? w) (positive-number? h))
    (when-not (and (.hasAttribute img "width") (.hasAttribute img "height"))
      (.setAttribute img "width"  (str (js/Math.round w)))
      (.setAttribute img "height" (str (js/Math.round h))))
    (clear-size! img)))

(defn scale-figures-html
  "Pre-DOM figure-sizing post-pass — the twin of mermaid/render-html-diagrams and math/render-html-math. Bakes
   font-matched width (local .svg) or an intrinsic layout box (raster/remote) into every <img> of an off-DOM
   DOMParser document, so figures render at FINAL SIZE on first paint with no post-insert style mutation (no
   flash), and re-renders/streaming reuse the memoized geometry (no re-scale). Pass-through when DOMParser is
   absent (:node-test) or the fragment carries no <img> (returns the original bytes → zero serialization churn
   on the vast majority of blocks). Runs inside apply-posts, so office + PDF-reflow + streaming inherit it.
   Returns Promise<html>."
  [html]
  (if-not (exists? js/DOMParser)
    (js/Promise.resolve html)
    (let [doc  (.parseFromString (js/DOMParser.) (or html "") "text/html")
          imgs (.querySelectorAll doc "img")]
      (if (zero? (.-length imgs))
        (js/Promise.resolve html)
        (let [doc-font (doc-font-px)
              jobs     #js []]
          (dotimes [i (.-length imgs)]
            (let [^js img (aget imgs i)
                  ;; srcs are already absolute (file://, http(s), data:) by apply-posts time via wrap-images /
                  ;; rewrite-urls, so read the ATTRIBUTE — .-src resolves against the DOMParser doc's about:blank
                  ;; base URL and would be wrong here.
                  src    (or (.getAttribute img "src") "")
                  path   (str/replace src #"[?#].*$" "")
                  local? (boolean (re-find #"(?i)^file://" src))]
              (.setAttribute img "draggable" "false")
              (cond
                (re-find #"(?i)^emoji" src) nil            ; emoji: CSS sizes them
                (and local? (re-find #"(?i)\.svg$" path))
                (.push jobs (-> (fetch-meta src)
                                (.then (fn [meta] (stamp-svg! img meta doc-font)))
                                (.catch (fn [_] nil))))
                :else
                (.push jobs (-> (fetch-raster-dims src local?)
                                (.then (fn [dims] (stamp-raster! img dims)))
                                (.catch (fn [_] nil)))))))
          (-> (js/Promise.all jobs)
              (.then (fn [_] (.-innerHTML (.-body doc))))))))))

(defn- refit-svg-live!
  "Guarded live re-fit of one SVG <img> on a mounted .markdown-body (keeps the isConnected/ancestor guards)."
  [^js body ^js img meta doc-font]
  (when (and (.-isConnected img)
             (identical? body (.closest img ".markdown-body")))
    (stamp-svg! img meta doc-font)))

(defn refit-all!
  "Re-fit live SVG figures to the CURRENT document font across every mounted .markdown-body. The font-size
   preference mutates --vv-font-size with NO re-render (see :fonts/apply), so figures sized for the previous
   font are re-stamped here — the one place figure sizing runs post-DOM. Idempotent when the font is unchanged
   (set-style! writes only on change). No-op without a document (:node-test). Geometry is served from the warm
   meta-cache, so it is synchronous in practice."
  []
  (when (exists? js/document)
    (let [doc-font (doc-font-px)
          bodies   (.querySelectorAll js/document ".markdown-body")]
      (dotimes [i (.-length bodies)]
        (let [^js body (aget bodies i)
              imgs     (.querySelectorAll body "img")]
          (dotimes [j (.-length imgs)]
            (let [^js img (aget imgs j)
                  url    (.-src img)                        ; live node: resolved absolute URL (matches cache key)
                  path   (str/replace url #"[?#].*$" "")
                  local? (boolean (re-find #"(?i)^file://" url))]
              (when (and local? (re-find #"(?i)\.svg$" path))
                (-> (fetch-meta url)
                    (.then (fn [meta] (refit-svg-live! body img meta doc-font)))
                    (.catch (fn [_] nil)))))))))))

;; SUPERSEDED by scale-figures-html + refit-all! (pre-DOM figure sizing, ADR-0022). The functions below sized
;; figures on the LIVE DOM after insertion (reading clientWidth for `avail`, stamping an !important px height),
;; which produced a visible post-insert re-scale and re-ran on every resize / live-refresh / streamed block.
;; Kept #_-disabled (not deleted) per the comment-don't-delete rule, for reference / recovery.
#_(defn- apply-svg-size! [^js body ^js img avail doc-font {:keys [v h f]}]
    (when (and (.-isConnected img)
               (identical? body (.closest img ".markdown-body")))
      (if (positive-number? v)
        (let [width (* doc-font (/ v f))
              style (.-style img)]
          (set-style! style "width" (str (js/Math.round width) "px"))
          (if (positive-number? h)
            (let [height (* width (/ h v))]
              (set-style-important! style "height" (str (js/Math.round height) "px"))
              (set-style! style "aspect-ratio" (str v " / " h)))
            (do
              (.removeProperty style "height")
              (.removeProperty style "aspect-ratio"))))
        (clear-size! img))))
#_(defn- apply-intrinsic-size! [^js body ^js img {:keys [w h]}]
    (when (and (.-isConnected img)
               (identical? body (.closest img ".markdown-body")))
      (if (and (positive-number? w) (positive-number? h))
        (when-not (and (.hasAttribute img "width") (.hasAttribute img "height"))
          (.setAttribute img "width"  (str (js/Math.round w)))
          (.setAttribute img "height" (str (js/Math.round h))))
        (clear-size! img))))
#_(defn scale-figures! [^js body]
    (if (and body (not (.closest body ".vv-diagram")))
      (let [cs       (js/getComputedStyle body)
            avail    (- (.-clientWidth body)
                        (js/parseFloat (or (.-paddingLeft cs) "0"))
                        (js/parseFloat (or (.-paddingRight cs) "0")))
            doc-font (or (js/parseFloat (.-fontSize cs)) 16)
            imgs     (.querySelectorAll body "img")
            jobs     #js []]
        (dotimes [i (.-length imgs)]
          (let [^js img (aget imgs i)
                src     (or (.getAttribute img "src") "")
                url     (.-src img)
                path    (str/replace url #"[?#].*$" "")
                local?  (boolean (re-find #"(?i)^file://" url))]
            (set! (.-draggable img) false)
            (.setAttribute img "draggable" "false")
            (cond
              (re-find #"(?i)^emoji" src)               nil
              (and local? (re-find #"(?i)\.svg$" path))
              (.push jobs (-> (fetch-meta url) (.catch (fn [_] nil))))
              :else
              (.push jobs (-> (fetch-raster-dims url local?) (.catch (fn [_] nil)))))))
        (js/Promise.all jobs))
      (js/Promise.resolve nil)))
