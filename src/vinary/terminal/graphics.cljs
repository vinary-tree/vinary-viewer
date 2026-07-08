(ns vinary.terminal.graphics
  "Headless terminal image graphics — the terminal analog of an <img>. Decodes a raster (PNG/JPEG/GIF) or vector
   (SVG) image to RGBA pixels, then encodes it either as a KITTY graphics escape (raw RGBA, f=32) or a SIXEL DCS
   string, sized to fit a column budget while preserving aspect. Degrades to a labelled `[image: …]` placeholder
   where the terminal lacks graphics (piped / unsupported TERM / --no-graphics), the format isn't decodable, or a
   decode fails — degradation is a first-class outcome, per the chosen design (`sixel/kitty where supported,
   degrade elsewhere`).

   Pure of the IR: the ANSI backend never imports this; the CLI builds the `:image` port closure (which resolves a
   node's src to bytes, needing fs + the doc dir) around `encode` and injects it. web-tree-sitter-style two-phase
   WASM: SVG needs @resvg/resvg-wasm, which loads async — `ensure-ready!` pre-loads it (Promise) before the
   otherwise-synchronous ANSI render, exactly as terminal.syntax pre-loads grammars."
  (:require ["pngjs" :refer [PNG]]
            ["jpeg-js" :as jpeg]
            ["omggif" :refer [GifReader]]
            ["sixel" :refer [image2sixel]]
            ["@resvg/resvg-wasm" :as resvg]
            ["fs" :as fs]
            ["path" :as path]
            [clojure.string :as str]))

;; ─────────────────────────────── constants ───────────────────────────────────
(def ^:private ESC "\u001b")
;; a terminal cell is taller than wide; without a reliable query we assume ~10×20 px (a 1:2 w:h ratio matches most
;; monospace fonts) — used only to translate pixel dimensions into row/column cell counts for layout.
(def ^:private default-cell-w 10)
(def ^:private default-cell-h 20)
;; never emit an escape larger than this (a runaway image would flood the terminal / stall a pipe) → placeholder
(def ^:private default-max-bytes (* 8 1024 1024))

;; ─────────────────────────────── resvg WASM (async, once) ─────────────────────
(defn- res-dir [] (path/join js/__dirname ".." ".." "resources" "public"))
(defonce ^:private resvg-state (atom :unloaded))     ; :unloaded | :loading | :ready | :failed
(defonce ^:private resvg-promise (atom nil))

(defn ensure-ready!
  "Idempotently initialise the resvg WASM (needed ONLY for SVG images). Returns a Promise resolving to :ready or
   :failed. Safe to call repeatedly and concurrently (initWasm must run exactly once — guarded by the state atom)."
  []
  (when (= :unloaded @resvg-state)
    (reset! resvg-state :loading)
    (reset! resvg-promise
            (-> (js/Promise.resolve (.readFileSync fs (path/join (res-dir) "js" "resvg.wasm")))
                (.then (fn [buf] (.initWasm resvg buf)))
                (.then (fn [_] (reset! resvg-state :ready) :ready))
                (.catch (fn [_] (reset! resvg-state :failed) :failed)))))
  (or @resvg-promise (js/Promise.resolve @resvg-state)))

(defn- resvg-ready? [] (= :ready @resvg-state))

;; ─────────────────────────────── format sniff ────────────────────────────────
(defn- sniff
  "Identify the image format from magic bytes (authoritative), falling back to the extension. Returns one of
   :png :jpeg :gif :svg :webp :avif :bmp :ico | nil."
  [^js buf ext]
  (let [b (fn [i] (when (< i (.-length buf)) (aget buf i)))
        e (some-> ext str/lower-case (str/replace #"^\." ""))]
    (cond
      (and (= 0x89 (b 0)) (= 0x50 (b 1)) (= 0x4E (b 2)) (= 0x47 (b 3)))          :png
      (and (= 0xFF (b 0)) (= 0xD8 (b 1)))                                        :jpeg
      (and (= 0x47 (b 0)) (= 0x49 (b 1)) (= 0x46 (b 2)))                         :gif   ; "GIF"
      (and (= 0x42 (b 0)) (= 0x4D (b 1)))                                        :bmp   ; "BM"
      (and (= 0x52 (b 0)) (= 0x49 (b 1)) (= 0x46 (b 2)) (= 0x46 (b 3))           ; "RIFF"…
           (= 0x57 (b 8)) (= 0x45 (b 9)) (= 0x42 (b 10)) (= 0x50 (b 11)))        :webp  ; …"WEBP" (not WAV/AVI)
      (= "svg" e)                                                               :svg
      (contains? #{"png" "jpg" "jpeg" "gif" "webp" "avif" "bmp" "ico"} e)       (keyword (if (= e "jpg") "jpeg" e))
      ;; no extension match (e.g. a data: URI or an odd name) — an SVG is XML text, sniff a leading <svg …>
      (re-find #"(?i)<svg[\s>]" (.toString buf "utf8" 0 (min 256 (.-length buf)))) :svg
      :else                                                                     nil)))

;; ─────────────────────────────── decode → RGBA ───────────────────────────────
;; returns {:rgba <Uint8Array RGBA> :w int :h int} or nil (undecodable)
(defn- decode-png [^js buf]
  (let [^js png (.read (.-sync PNG) buf)] {:rgba (.-data png) :w (.-width png) :h (.-height png)}))

(defn- decode-jpeg [^js buf]
  (let [^js img (.decode jpeg buf #js {:useTArray true})] {:rgba (.-data img) :w (.-width img) :h (.-height img)}))

(defn- decode-gif [^js buf]
  (let [^js r (GifReader. buf)
        w (.-width r) h (.-height r)
        out (js/Uint8Array. (* w h 4))]
    (.decodeAndBlitFrameRGBA r 0 out)                     ; first frame only (a terminal preview is a still)
    {:rgba out :w w :h h}))

(defn- decode-svg
  "SVG → RGBA, rasterised crisply at min(intrinsic width, `target-px-w`) — a small badge/icon keeps its intrinsic
   size (NOT blown up to full width), a large diagram is capped at the display width. nil if resvg isn't ready
   (caller pre-loads via ensure-ready!) or the SVG is invalid."
  [^js buf target-px-w]
  (when (resvg-ready?)
    (let [svg       (.toString buf "utf8")
          ^js probe (resvg/Resvg. svg)                    ; parse only (cheap) → read the intrinsic size
          iw        (or (.-width probe) 0)
          w         (max 1 (js/Math.round (if (and target-px-w (pos? target-px-w) (< target-px-w iw)) target-px-w iw)))
          ^js r     (if (= w (js/Math.round iw)) probe (resvg/Resvg. svg #js {:fitTo #js {:mode "width" :value w}}))
          ^js img   (.render r)]
      (decode-png (js/Buffer.from (.asPng img))))))

(defn- decode
  "Bytes + format + target display width (px) → {:rgba :w :h} | nil. Wrapped so a decoder throw becomes nil."
  [^js buf fmt target-px-w]
  (try
    (case fmt
      :png  (decode-png buf)
      :jpeg (decode-jpeg buf)
      :gif  (decode-gif buf)
      :svg  (decode-svg buf target-px-w)
      nil)
    (catch :default _ nil)))

;; ─────────────────────────────── aspect-preserving fit ───────────────────────
(defn fit
  "Fit a `w`×`h` px image into at most `max-cols` terminal columns, preserving aspect. Returns
   {:cols :rows :px-w :px-h} — cell counts for cursor advancement + pixel dims for the raster resize."
  [w h {:keys [max-cols cell-w cell-h]}]
  (let [cw   (or cell-w default-cell-w)
        ch   (or cell-h default-cell-h)
        maxc (max 1 (or max-cols 80))
        ncol (max 1 (js/Math.ceil (/ w cw)))
        cols (min ncol maxc)
        scale (/ (* cols cw) w)
        px-w (max 1 (js/Math.round (* w scale)))
        px-h (max 1 (js/Math.round (* h scale)))
        ;; CEIL, not round: a 25px-tall image in 20px cells occupies 2 rows, not 1 — the row count is the image's
        ;; cursor-advancement footprint, so under-counting (round(1.25)=1) would let following text overprint it.
        rows (max 1 (js/Math.ceil (/ px-h ch)))]
    {:cols cols :rows rows :px-w px-w :px-h px-h}))

;; nearest-neighbour resize of an RGBA buffer to dst-w×dst-h (pure; adequate for a terminal preview). Returns the
;; same buffer untouched when already at target size.
(defn- resize-rgba [^js rgba w h dst-w dst-h]
  (if (and (= w dst-w) (= h dst-h))
    rgba
    (let [out (js/Uint8Array. (* dst-w dst-h 4))]
      (dotimes [y dst-h]
        (let [sy (min (dec h) (js/Math.floor (/ (* y h) dst-h)))]
          (dotimes [x dst-w]
            (let [sx (min (dec w) (js/Math.floor (/ (* x w) dst-w)))
                  si (* 4 (+ (* sy w) sx))
                  di (* 4 (+ (* y dst-w) x))]
              (aset out di       (aget rgba si))
              (aset out (+ di 1) (aget rgba (+ si 1)))
              (aset out (+ di 2) (aget rgba (+ si 2)))
              (aset out (+ di 3) (aget rgba (+ si 3)))))))
      out)))

;; ─────────────────────────────── kitty / sixel encoders ──────────────────────
;; both take pixels ALREADY resized to the DISPLAY size (px-w×px-h) — kitty must not transmit native-resolution
;; RGBA (a 1920×1080 photo would be an 11 MB base64 escape), so `encode` downscales once and both encoders share it.
(defn kitty
  "Kitty graphics escape for a raw-RGBA image (f=32) of `w`×`h` display pixels, boxed into `cols`×`rows` cells.
   C=1 keeps kitty from moving the cursor, so the caller advances exactly `rows` rows for deterministic block
   layout. base64 chunked ≤4096."
  [^js rgba w h cols rows]
  (let [b64 (.toString (js/Buffer.from rgba) "base64")
        n   (.-length b64)
        chunk 4096
        first-ctrl (str "a=T,f=32,s=" w ",v=" h ",c=" cols ",r=" rows ",C=1")]
    (loop [i 0 out (str)]
      (if (>= i n)
        out
        (let [piece (subs b64 i (min n (+ i chunk)))
              more  (if (< (+ i chunk) n) 1 0)
              ctrl  (if (zero? i) (str first-ctrl ",m=" more) (str "m=" more))]
          (recur (+ i chunk) (str out ESC "_G" ctrl ";" piece ESC "\\")))))))

(defn sixel
  "Sixel DCS string for a raw-RGBA image of `w`×`h` display pixels, quantised to ≤256 colours (jerch/sixel)."
  [^js rgba w h]
  (image2sixel rgba w h 256))

;; ─────────────────────────────── the encode entry point ──────────────────────
(defn encode
  "Bytes + ext + opts → {:escape s :cols c :rows r} (drawable) | {:placeholder reason} (degraded). opts:
     {:graphics :kitty|:sixel|nil  :max-cols int  :cell-w :cell-h :max-bytes}.
   Never throws; any failure degrades to a {:placeholder …}. The caller renders {:escape} as a block (advancing
   :rows rows) or the labelled placeholder text."
  [^js buf ext {:keys [graphics max-cols cell-w max-bytes] :as opts}]
  (let [fmt (sniff buf ext)]
    (cond
      (nil? graphics)              {:placeholder :no-graphics}
      (nil? fmt)                   {:placeholder :unknown-format}
      (not (#{:png :jpeg :gif :svg} fmt)) {:placeholder (keyword (str "undecodable-" (name fmt)))}
      :else
      (let [;; SVG rasterises at min(intrinsic, display) width; rasters decode native then downscale to display size
            target-px-w (when (= :svg fmt) (* (max 1 (or max-cols 80)) (or cell-w default-cell-w)))
            img (decode buf fmt target-px-w)]
        (if (nil? img)
          {:placeholder (if (= :svg fmt) :svg-unavailable :decode-failed)}
          (let [{:keys [w h rgba]} img
                {:keys [cols rows px-w px-h]} (fit w h opts)
                ;; projected escape size for raw RGBA (base64 = 4/3 of the pixel bytes) — checked BEFORE building
                ;; the string, so a runaway image is rejected without first allocating the flood. Display-sized, so
                ;; a normal image is well under the cap; only an enormous terminal × tall image trips it.
                proj (js/Math.ceil (* px-w px-h 4 (/ 4 3)))]
            (if (> proj (or max-bytes default-max-bytes))
              {:placeholder :too-large}
              (let [resized (resize-rgba rgba w h px-w px-h)     ; ONE downscale, shared by kitty + sixel
                    escape  (case graphics
                              :kitty (kitty resized px-w px-h cols rows)
                              :sixel (sixel resized px-w px-h)
                              nil)]
                (if (nil? escape)
                  {:placeholder :no-graphics}
                  {:escape escape :cols cols :rows rows})))))))))
