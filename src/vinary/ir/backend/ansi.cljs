(ns vinary.ir.backend.ansi
  "IR → terminal (ANSI) back-end — the terminal analog of `vinary.ir.backend.html`. Lowers the common document
   IR to a styled, width-wrapped string of ANSI lines for a CLI/TUI, sitting exactly where `ir.backend.html`
   sits (same `IR → String` shape) but emitting SGR-styled text + box-drawing instead of HTML. PURE and
   DOM-free (string in/out), so it is fully node-testable with golden outputs and shared by both `vv-cli` and
   the TUI.

   `render` takes an options map:
     {:width int              ; wrap column (default 80)
      :color? bool            ; emit SGR colour/style (false → plain text, for NO_COLOR / a pipe)
      :truecolor? bool        ; 24-bit colour (else the 16-colour fallback)
      :hyperlinks? bool       ; OSC-8 terminal hyperlinks on links
      :highlight (fn [lang code] → [[span …] …]) | nil   ; per-line syntax spans for code blocks (injected;
                                                          ; the headless tree-sitter highlighter, else plain)
      :image (fn [img-node width] → string | nil) | nil} ; terminal-graphics image escape (injected; else the
                                                          ; `[image: alt]` placeholder)
   The `:highlight`/`:image` PORTS keep this backend pure — the DOM-free tree-sitter colourizer and the
   sixel/kitty encoder are provided by the caller, so nothing browser- or filesystem-bound leaks in here."
  (:require [clojure.string :as str]
            [vinary.ir.node :as node]))

;; ─────────────────────────────── SGR / palette ───────────────────────────────
(def ^:private CSI "[")
(def ^:private RESET "[0m")

;; role → 16-colour SGR fg code (universal) + a One-Dark-ish 24-bit rgb (truecolor terminals)
(def ^:private fg16
  {:red 31 :green 32 :yellow 33 :blue 34 :magenta 35 :cyan 36 :white 37 :gray 90
   :bright-red 91 :bright-green 92 :bright-yellow 93 :bright-blue 94 :bright-magenta 95 :bright-cyan 96})
(def ^:private rgb
  {:red [224 108 117] :green [152 195 121] :yellow [229 192 123] :blue [97 175 239]
   :magenta [198 120 221] :cyan [86 182 194] :gray [92 99 112] :white [220 223 228]
   :bright-red [224 108 117] :bright-green [152 195 121] :bright-yellow [229 192 123]
   :bright-blue [97 175 239] :bright-magenta [198 120 221] :bright-cyan [86 182 194]})

(defn- sgr
  "SGR codes for a style map, honouring :truecolor?. Returns the opening escape (empty when the style is bare)."
  [{:keys [fg bold? dim? italic? underline? strike?]} truecolor?]
  (let [codes (cond-> []
                bold?      (conj 1)
                dim?       (conj 2)
                italic?    (conj 3)
                underline? (conj 4)
                strike?    (conj 9)
                fg         (into (if (and truecolor? (rgb fg))
                                   (let [[r g b] (rgb fg)] [38 2 r g b])
                                   [(fg16 fg 39)])))]
    (when (seq codes) (str CSI (str/join ";" codes) "m"))))

(defn- emit-span
  "Render one span {:text :style :link} to a terminal string under `opts`."
  [{:keys [text style link]} {:keys [color? truecolor? hyperlinks?]}]
  (let [t (if (and hyperlinks? link (seq link))
            (str "]8;;" link "" text "]8;;")   ; OSC-8 hyperlink
            text)]
    (if (and color? style (seq style))
      (str (sgr style truecolor?) t RESET)
      t)))

;; ─────────────────────────────── display width ───────────────────────────────
(def ^:private sgr-re #"\[[0-9;]*m|\]8;;[^]*")

(defn- wide? [cp]
  ;; a coarse East-Asian-wide test (CJK, Hangul, Kana, fullwidth forms) — those glyphs occupy 2 cells
  (or (<= 0x1100 cp 0x115F) (<= 0x2E80 cp 0xA4CF) (<= 0xAC00 cp 0xD7A3)
      (<= 0xF900 cp 0xFAFF) (<= 0xFE30 cp 0xFE4F) (<= 0xFF00 cp 0xFF60) (<= 0xFFE0 cp 0xFFE6)
      (<= 0x1F300 cp 0x1FAFF)))

(defn display-width
  "Terminal cell width of `s`, ignoring ANSI escapes and counting wide glyphs as 2."
  [s]
  (let [clean (str/replace (str s) sgr-re "")]
    (reduce (fn [w cp] (+ w (if (wide? cp) 2 1))) 0 (map #(.codePointAt clean %) (range (count clean))))))

;; ─────────────────────────────── inline → spans ──────────────────────────────
;; A span = {:text string :style {…} :link href?}. Inline IR (text, emphasis, code, links, images, math,
;; breaks) is flattened to spans carrying the accumulated style; a :br yields a hard-break marker span.
(def ^:private BR {:br true})

(defn- attr
  "Read HAST attribute `k` off IR node `n`. `:attrs` is a raw JS `properties` object for the markdown/office
   front-ends but a cljs map for the pure front-ends (logs/table) — handle both."
  [n k]
  (let [a (:attrs (node/node-meta n))]
    (cond (nil? a) nil, (map? a) (get a k), :else (aget a k))))

(defn- classes [n]
  (let [cn (attr n "className")]
    (cond (nil? cn) [], (array? cn) (array-seq cn), (sequential? cn) cn, :else [(str cn)])))

(defn- math-node? [n] (boolean (some #(str/starts-with? (str %) "math") (classes n))))

(defn- inline->spans
  "Flatten an inline IR subtree to spans under the accumulated `style` and `opts`."
  [n style opts]
  (let [tag  (:tag (node/node-meta n))
        kind (node/kind n)]
    (cond
      (= :text kind)    (when (seq (or (node/text n) "")) [{:text (node/text n) :style style}])
      (= :comment kind) nil
      (= "br" tag)      [BR]
      ;; inline code / math → the literal source, styled (math shows its TeX; terminals can't render MathJax)
      (or (= :code kind) (= "code" tag))
      (let [txt (node/text-content n)]
        [{:text txt :style (assoc style :fg (if (math-node? n) :magenta :yellow))}])
      (or (= :math kind) (math-node? n))
      [{:text (node/text-content n) :style (assoc style :fg :magenta)}]
      ;; link → styled children + the resolved href for an OSC-8 hyperlink; show the URL when text == URL-less
      (= :link kind)
      (let [href (attr n "href")
            kids (mapcat #(inline->spans % (assoc style :fg :blue :underline? true) opts) (node/children n))]
        (mapv #(if (:br %) % (assoc % :link href)) (or (seq kids) [{:text (or href "") :style (assoc style :fg :blue :underline? true)}])))
      (= :image kind)
      [{:text (str "🖼 " (or (attr n "alt") "image")) :style (assoc style :fg :cyan :italic? true)}]
      ;; emphasis / other inline elements → fold the tag's style into the children
      :else
      (let [style' (case tag
                     ("strong" "b")  (assoc style :bold? true)
                     ("em" "i")      (assoc style :italic? true)
                     ("del" "s")     (assoc style :strike? true)
                     ("mark")        (assoc style :fg :bright-yellow :bold? true)
                     ("sup" "sub")   style
                     style)]
        (mapcat #(inline->spans % style' opts) (node/children n))))))

;; ─────────────────────────────── word wrap ───────────────────────────────────
(defn- split-words
  "Split a span into word-spans on spaces (keeping a single space between words), preserving style/link."
  [{:keys [text] :as span}]
  (let [parts (str/split text #"(?<= )|(?= )")]        ; keep spaces as their own tokens
    (mapv #(assoc span :text %) (remove empty? parts))))

(defn- wrap-spans
  "Wrap a span sequence to `width` cells; returns a vector of lines (each a vector of spans). :br spans force a
   new line. Long unbreakable words are hard-split at the width boundary."
  [spans width]
  (let [width (max 1 width)]
    (loop [tokens (mapcat #(if (:br %) [%] (split-words %)) spans)
           line [] col 0 lines []]
      (if (empty? tokens)
        (conj lines line)
        (let [{:keys [text] :as tok} (first tokens)]
          (cond
            (:br tok)                (recur (rest tokens) [] 0 (conj lines line))
            (= text " ")             (if (or (zero? col) (>= (inc col) width))
                                       (recur (rest tokens) line col lines)          ; drop a leading/eol space
                                       (recur (rest tokens) (conj line tok) (inc col) lines))
            :else
            (let [w (display-width text)]
              (cond
                (<= (+ col w) width)  (recur (rest tokens) (conj line tok) (+ col w) lines)
                (> w width)           ;; a single word longer than the line — hard-split it
                (let [room (- width col)
                      room (if (pos? room) room width)]
                  (recur (cons (assoc tok :text (subs text room)) (rest tokens))
                         [] 0 (conj lines (conj line (assoc tok :text (subs text 0 room))))))
                :else                 (recur tokens [] 0 (conj lines line))))))))))   ; wrap: retry token on a fresh line

;; ─────────────────────────────── block layout ────────────────────────────────
(declare block->lines)

(defn- render-line [spans opts] (apply str (map #(emit-span % opts) spans)))

(defn- wrapped
  "Render inline IR `n` to indented, wrapped, styled lines."
  [n base-style prefix width opts]
  (let [spans (inline->spans n base-style opts)
        avail (max 1 (- width (display-width prefix)))]
    (mapv (fn [line] (str prefix (render-line line opts)))
          (wrap-spans (vec spans) avail))))

(defn- heading-style [level]
  {:bold true :fg (case (int (or level 1)) 1 :bright-magenta 2 :bright-cyan 3 :bright-blue 4 :green 5 :yellow :gray)})

(defn- rule [width opts]
  [(emit-span {:text (apply str (repeat (max 1 width) "─")) :style {:fg :gray}} opts)])

(defn- inline-container? [n]
  ;; a block whose children are all inline (paragraph-ish) → render as one wrapped run
  (every? (fn [c] (not (#{:paragraph :heading :list :list-item :blockquote :code-block :table
                          :table-head :table-body :row :thematic-break :record} (node/kind c))))
          (node/children n)))

;; -- tables --
(defn- cell-text [cell opts]
  (str/trimr (render-line (inline->spans cell {} opts) opts)))

(defn- table->lines [n opts indent width]
  (let [rows (->> (node/preorder n) (filter #(= :row (node/kind %))) vec)
        grid (mapv (fn [r] (mapv #(cell-text % opts) (filter #(= :cell (node/kind %)) (node/children r)))) rows)
        ncol (apply max 0 (map count grid))
        grid (mapv (fn [r] (into r (repeat (- ncol (count r)) ""))) grid)
        avail (max (* ncol 3) (- width (count indent)))
        widths (mapv (fn [c] (min (max 3 (apply max 1 (map #(display-width (nth % c "")) grid)))
                                  (max 3 (quot avail (max 1 ncol)))))
                     (range ncol))
        bar (fn [l m r] (str indent l (str/join m (map #(apply str (repeat (+ 2 %) "─")) widths)) r))
        pad (fn [s w] (let [s (str s) over (- (display-width s) w)]
                        (if (pos? over) (str (subs s 0 (max 0 (- (count s) over 1))) "…")
                            (str s (apply str (repeat (- w (display-width s)) " "))))))
        row-line (fn [cells] (str indent "│ " (str/join " │ " (map-indexed (fn [i c] (pad c (nth widths i))) cells)) " │"))]
    (when (seq grid)
      (concat [(bar "┌" "┬" "┐") (row-line (first grid)) (bar "├" "┼" "┤")]
              (map row-line (rest grid))
              [(bar "└" "┴" "┘")]))))

;; -- log records --
(def ^:private level-re #"(?i)\b(ERROR|FATAL|CRITICAL|WARN|WARNING|INFO|DEBUG|TRACE)\b")
(defn- level-color [line]
  (some-> (re-find level-re (or line "")) second str/upper-case
          {"ERROR" :bright-red "FATAL" :bright-red "CRITICAL" :bright-red
           "WARN" :bright-yellow "WARNING" :bright-yellow "INFO" :cyan "DEBUG" :gray "TRACE" :gray}))

(defn- record->lines [n opts indent width]
  (let [lines (map node/text-content (node/children n))
        color (some level-color lines)]
    (mapcat (fn [ln] (map #(str indent (emit-span {:text % :style (when color {:fg color})} opts))
                          (wrap-spans [{:text ln :style {}}] (max 1 (- width (count indent))))))
            lines)))

(defn- code->lines [n opts indent width]
  (let [code (str/replace (node/text-content n) #"\n$" "")
        lang (some #(when (str/starts-with? (str %) "language-") (subs (str %) 9))
                   (classes (or (first (node/children n)) n)))
        hl   (:highlight opts)
        raw  (str/split code #"\n" -1)
        gutter (str indent (emit-span {:text "▏" :style {:fg :gray}} opts) " ")]
    (map (fn [line]
           (str gutter (emit-span {:text (or line "") :style {:fg :bright-green}} opts)))
         (if (and hl lang) (map #(render-line % opts) (hl lang code)) raw))))

(defn- list->lines [n opts indent width]
  (let [ordered? (= "ol" (:tag (node/node-meta n)))
        items    (filter #(= :list-item (node/kind %)) (node/children n))]
    (mapcat (fn [idx item]
              (let [marker (if ordered? (str (inc idx) ". ") "• ")
                    m-ind  (str indent (emit-span {:text marker :style {:fg :gray}} opts))
                    cont   (str indent (apply str (repeat (count marker) " ")))
                    kids   (node/children item)]
                ;; first inline run gets the marker prefix; nested blocks get the continuation indent
                (loop [ks kids out [] first? true]
                  (if (empty? ks)
                    out
                    (let [k (first ks)]
                      (if (#{:paragraph :heading} (node/kind k))
                        (let [ls (wrapped k {} (if first? m-ind cont) width opts)]
                          (recur (rest ks) (into out ls) false))
                        (if (= :text (node/kind k))
                          (if (str/blank? (node/text k)) (recur (rest ks) out first?)
                              (let [ls (mapv #(str (if first? m-ind cont) (render-line % opts))
                                             (wrap-spans (vec (inline->spans k {} opts)) (max 1 (- width (count cont)))))]
                                (recur (rest ks) (into out ls) false)))
                          (recur (rest ks) (into out (block->lines k opts cont width)) false))))))))
            (range) items)))

(defn- blockquote->lines [n opts indent width]
  (let [inner (str indent (emit-span {:text "│ " :style {:fg :green}} opts))
        body  (mapcat #(block->lines % opts "" width) (node/children n))]
    (map (fn [ln] (str inner ln)) (remove str/blank? body))))

(defn block->lines
  "Render a block IR node `n` to a vector of terminal lines (already indented + styled). `indent` is the
   left-margin prefix string; `width` the terminal columns."
  [n opts indent width]
  (case (node/kind n)
    :document      (mapcat #(block->lines % opts indent width) (node/children n))
    :heading       (let [lvl (:level (node/node-meta n))]
                     (wrapped n (heading-style lvl) (str indent (apply str (repeat (dec (int (or lvl 1))) " "))) width opts))
    :paragraph     (wrapped n {} indent width opts)
    :list          (list->lines n opts indent width)
    :list-item     (mapcat #(block->lines % opts indent width) (node/children n))
    :blockquote    (blockquote->lines n opts indent width)
    :code-block    (code->lines n opts indent width)
    :table         (vec (table->lines n opts indent width))
    :thematic-break (mapv #(str indent %) (rule (max 1 (- width (count indent))) opts))
    :record        (record->lines n opts indent width)
    (:comment :raw-node :doctype) []
    ;; a bare :text block leaf (whitespace between blocks / plain-text kind) or a generic element container
    :text          (if (str/blank? (node/text n)) [] (wrapped n {} indent width opts))
    ;; generic element (div/details/section/…): inline content → wrap; block content → recurse
    (if (inline-container? n)
      (wrapped n {} indent width opts)
      (mapcat #(block->lines % opts indent width) (node/children n)))))

;; ─────────────────────────────── public API ──────────────────────────────────
(def ^:private defaults {:width 80 :color? true :truecolor? false :hyperlinks? false})

(defn- block? [c] (not (and (= :text (node/kind c)) (str/blank? (node/text c)))))

(defn render
  "Lower document IR `ir` to a terminal string under `opts` (see the ns docstring). Top-level blocks are
   separated by a blank line, mirroring the GUI's rendered spacing."
  ([ir] (render ir {}))
  ([ir opts]
   (let [opts (merge defaults opts)
         width (:width opts)
         blocks (filter block? (if (= :document (node/kind ir)) (node/children ir) [ir]))]
     (->> blocks
          (map #(block->lines % opts "" width))
          (remove empty?)
          (map #(str/join "\n" %))
          (str/join "\n\n")))))

(defn lower
  "IR → ANSI string (the ir.backend.html/lower analog). Convenience 1-arg over `render` with defaults."
  [ir] (render ir {}))
