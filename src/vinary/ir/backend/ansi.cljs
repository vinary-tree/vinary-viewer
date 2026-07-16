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

(defn attr
  "Read HAST attribute `k` off IR node `n`. `:attrs` is a raw JS `properties` object for the markdown/office
   front-ends but a cljs map for the pure front-ends (logs/table) — handle both. (Public: the CLI's :image port
   reads a node's src/alt through it.)"
  [n k]
  (let [a (:attrs (node/node-meta n))]
    (cond (nil? a) nil, (map? a) (get a k), :else (aget a k))))

(defn- classes [n]
  (let [cn (attr n "className")]
    (cond (nil? cn) [], (array? cn) (array-seq cn), (sequential? cn) cn, :else [(str cn)])))

(defn- math-node? [n] (boolean (some #(str/starts-with? (str %) "math") (classes n))))

(defn- diff-line-style
  "Terminal style for a unified-diff line node, keyed by its `vv-diff-*` class (added → green, removed → red,
   hunk header → bold cyan, note → dim, context → default). nil for any non-diff node, so ordinary blocks are
   unaffected. The diff front-end (ir.frontend.diff) tags each line with these classes; this is the ANSI analog
   of the GUI's CSS line colouring, so a colored unified diff renders in `vv-cli`/the TUI with no bespoke arm."
  [n]
  (let [cs (set (classes n))]
    (cond
      (contains? cs "vv-diff-insert")  {:fg :green}
      (contains? cs "vv-diff-delete")  {:fg :red}
      (contains? cs "vv-diff-hunk")    {:fg :cyan :bold? true}
      (contains? cs "vv-diff-note")    {:fg :gray :italic? true}
      (contains? cs "vv-diff-context") {}
      :else                            nil)))

(defn- inline->spans
  "Flatten an inline IR subtree to spans under the accumulated `style` and `opts`."
  [n style opts]
  (let [tag  (:tag (node/node-meta n))
        kind (node/kind n)]
    (cond
      ;; a soft line break (a literal \n in inline text) collapses to a space, matching HTML whitespace handling
      (= :text kind)    (when (seq (or (node/text n) "")) [{:text (str/replace (node/text n) #"[ \t]*\r?\n[ \t]*" " ") :style style}])
      (= :comment kind) nil
      ;; a text-carrying LEAF (:line/:run/:plain from the pure log/table/pdf front-ends — text lives in :text,
      ;; not a :text child); the markdown log-stream :line is instead an element wrapping a :text child (below).
      (and (node/leaf? n) (seq (or (node/text n) ""))) [{:text (node/text n) :style style}]
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

(defn- coalesce-spans
  "Merge adjacent spans that share the same style + link into one. Word-wrap (wrap-spans/split-words) fragments a
   run of same-styled text into per-word/-space tokens; re-joining them here makes a single-style line emit ONE
   SGR pair (and ONE OSC-8 hyperlink) instead of one per word — correct output, but ~5× fewer bytes on coloured
   logs/prose (material over a streamed million-line log)."
  [spans]
  (reduce (fn [acc s]
            (let [prev (peek acc)]
              (if (and prev (not (:br prev)) (not (:br s))
                       (= (:style prev) (:style s)) (= (:link prev) (:link s)))
                (conj (pop acc) (update prev :text str (:text s)))
                (conj acc s))))
          [] spans))

(defn- render-line [spans opts] (apply str (map #(emit-span % opts) (coalesce-spans spans))))

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
    ;; wrap each source line (as a colour-styled span) then render each wrapped sub-line via render-line
    (mapcat (fn [ln] (map #(str indent (render-line % opts))
                          (wrap-spans [{:text ln :style (when color {:fg color})}] (max 1 (- width (count indent))))))
            lines)))

(defn- code-block-lang [n]
  (some #(when (str/starts-with? (str %) "language-") (subs (str %) 9)) (classes (or (first (node/children n)) n))))

(defn- code->lines [n opts indent width]
  (let [code    (str/replace (node/text-content n) #"\n$" "")
        lang    (code-block-lang n)
        hlspans (when (and (:highlight opts) lang) ((:highlight opts) lang code))    ; nil → plain code
        gutter  (str indent (emit-span {:text "▏" :style {:fg :gray}} opts) " ")
        strs    (if hlspans
                  (map #(render-line % opts) hlspans)                                ; per-token styled lines
                  (map #(emit-span {:text % :style {:fg :bright-green}} opts) (str/split code #"\n" -1)))]
    (map #(str gutter %) strs)))

(defn code-languages
  "Distinct code-block languages present in `ir` (from `language-X` classNames), for pre-loading a highlighter."
  [ir]
  (->> (node/preorder ir) (filter #(= :code-block (node/kind %))) (keep code-block-lang) distinct vec))

(defn- attr-of
  "Read one HTML attribute off an IR node's :meta :attrs, which is a verbatim JS `properties` object for the
   tree front-ends (markdown/org) and a cljs map for the pure ones (tables/logs)."
  [attrs k]
  (cond
    (nil? attrs) nil
    (map? attrs) (get attrs k)
    :else        (aget attrs k)))

(defn- checkbox-state
  "GFM task-list state for a `:list-item`, or nil when it is an ordinary bullet. Both the Markdown and the Org
   front-ends emit GitHub's shape — `<li class=\"task-list-item\"><input type=\"checkbox\" [checked]>` — so the
   terminal reads the state for either. Without this the terminal renders a checked and an unchecked item
   identically, silently dropping the one bit that matters in a TODO list."
  [item]
  (some (fn [child]
          (let [m (node/node-meta child)]
            (when (= "input" (:tag m))
              (let [attrs (:attrs m)]
                (when (= "checkbox" (attr-of attrs "type"))
                  (if (attr-of attrs "checked") :checked :unchecked))))))
        (node/preorder item)))

(defn- ->ordinal
  "Parse an ordered-list `start` / list-item `value` attribute to an int, or nil when absent/non-numeric."
  [x] (when (some? x) (let [n (js/parseInt x 10)] (when-not (js/isNaN n) n))))

(defn- list->lines [n opts indent width]
  (let [ordered? (= "ol" (:tag (node/node-meta n)))
        start    (or (->ordinal (attr n "start")) 1)          ; <ol start=N> — the SAME :attrs the HTML backend emits
        items    (filter #(= :list-item (node/kind %)) (node/children n))]
    ;; Thread the running ordinal so `start` and a per-item `value` (Org `[@n]`) drive BOTH backends off ONE IR
    ;; source (the node :attrs), instead of the terminal re-deriving ordinals as (inc idx) and diverging from the
    ;; GUI's <ol start>/<li value>. Plain lists (no start/value) are unchanged: start 1, +1 per item.
    (first
     (reduce
      (fn [[lines ord] item]
        (let [ord    (or (->ordinal (attr item "value")) ord)   ; a per-item value overrides AND resets the run
              state  (checkbox-state item)
              box    (case state :checked "☑ " :unchecked "☐ " nil "")
              ;; an ordered task list keeps its ordinal AND gains a box; an unordered one swaps the bullet for
              ;; the box (a bullet plus a box reads as noise)
              marker (str (cond ordered?     (str ord ". ")
                                (some? state) ""
                                :else        "• ")
                          box)
              m-ind  (str indent (emit-span {:text marker :style {:fg :gray}} opts))
              mw     (display-width marker)
              cont   (str indent (apply str (repeat mw " ")))
              inner  (max 4 (- width mw (count indent)))
              ;; render the item's content unprefixed (inline runs, nested lists, etc.), then prefix line 0 with
              ;; the marker and every continuation line with the aligned indent — robust to the source-positions
              ;; <span> wrapper and to nested blocks / loose <p> items alike
              body   (remove str/blank? (mapcat #(block->lines % opts "" inner) (node/children item)))]
          [(into lines (map-indexed (fn [i ln] (str (if (zero? i) m-ind cont) ln)) body))
           (inc ord)]))
      [[] start]
      items))))

(defn- blockquote->lines [n opts indent width]
  (let [inner (str indent (emit-span {:text "│ " :style {:fg :green}} opts))
        body  (mapcat #(block->lines % opts "" width) (node/children n))]
    (map (fn [ln] (str inner ln)) (remove str/blank? body))))

;; -- images (terminal graphics) --
(defn- sole-image
  "If block `n` presents a single image on its own — a bare :image, or a chain of single-meaningful-child wrappers
   (the markdown front-end's `:paragraph → a.vv-figure-link → :image`, including the source-positions <span>)
   ending in exactly one :image — return that :image node; else nil. Wrapper-agnostic: unwraps ANY single
   non-blank-child container (not specific tags), so it survives the figure-link + source-position wrapping and an
   office <figure><img>. Mixed content (image + text) → nil → the image renders inline as its placeholder span."
  [n]
  (loop [n n depth 0]
    (cond
      (nil? n)                 nil
      (= :image (node/kind n)) n
      (> depth 8)              nil
      :else
      (let [kids (remove #(and (= :text (node/kind %)) (str/blank? (or (node/text %) ""))) (node/children n))]
        (if (= 1 (count kids)) (recur (first kids) (inc depth)) nil)))))

(defn- placeholder-line [img opts indent]
  (str indent (emit-span {:text (str "🖼 " (or (attr img "alt") "image")) :style {:fg :cyan :italic? true}} opts)))

(defn- image-lines
  "Render a block-level :image. Calls the injected `:image` port — a `(fn [img-node width] → str|nil)` that resolves
   the src to bytes and encodes a kitty/sixel escape WITH its own row footprint (or a labelled text placeholder).
   With no port (or a blank result) falls back to a `🖼 alt` placeholder line. A graphics escape must start at
   column 0 (indent would shift the raster), so the port's string is emitted un-indented; only the fallback
   placeholder is indented."
  [img opts indent width]
  (let [port (:image opts)
        s    (when port (port img (max 1 (- width (count indent)))))]
    (if (and (string? s) (seq s))
      [s]
      [(placeholder-line img opts indent)])))

(defn block->lines
  "Render a block IR node `n` to a vector of terminal lines (already indented + styled). `indent` is the
   left-margin prefix string; `width` the terminal columns."
  [n opts indent width]
  (case (node/kind n)
    :document      (mapcat #(block->lines % opts indent width) (node/children n))
    :heading       (wrapped n (heading-style (:level (node/node-meta n))) indent width opts)
    ;; a paragraph that is JUST an image (`![](x)` → figure-link → img) is a block image, not wrapped prose
    :paragraph     (if-let [img (sole-image n)] (image-lines img opts indent width) (wrapped n {} indent width opts))
    :image         (image-lines n opts indent width)
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
    ;; a class-tagged unified-diff line → colour it by its vv-diff-* class (checked BEFORE the generic
    ;; image/inline/recurse fallbacks, since a diff line is a plain div that would otherwise render un-styled)
    (if-let [ds (diff-line-style n)]
      (wrapped n ds indent width opts)
      ;; generic element (div/details/section/figure/…): a lone image → block image; inline content → wrap; else recurse
      (if-let [img (sole-image n)]
        (image-lines img opts indent width)
        (if (inline-container? n)
          (wrapped n {} indent width opts)
          (mapcat #(block->lines % opts indent width) (node/children n)))))))

;; ─────────────────────────────── public API ──────────────────────────────────
(def ^:private defaults {:width 80 :color? true :truecolor? false :hyperlinks? false :block-sep "\n\n"})

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
          (str/join (:block-sep opts))))))

(defn render-lines
  "Like `render`, but returns {:lines [str …] :anchors {id line-index …}} — the flat vector of visual content lines
   (`:lines` equals `(str/split (render ir opts) #\"\\n\")`) plus a map from each id-bearing top-level block's anchor
   id to the 0-based line index where it starts. This is the TUI's source for TOC jump (toc entry id → line) and the
   find/scroll line model — the backend, not fragile text-matching, tells you where each heading/record landed."
  ([ir] (render-lines ir {}))
  ([ir opts]
   (let [opts       (merge defaults opts)
         width      (:width opts)
         sep-blanks (max 0 (dec (count (re-seq #"\n" (:block-sep opts)))))   ; "\n\n"→1 blank between blocks, "\n"→0
         blocks     (->> (filter block? (if (= :document (node/kind ir)) (node/children ir) [ir]))
                         (map (fn [b] [b (vec (mapcat #(str/split % #"\n" -1) (block->lines b opts "" width)))]))
                         (remove (fn [[_ bl]] (empty? bl))))]
     (loop [bs blocks, lines [], anchors {}, first? true]
       (if (empty? bs)
         {:lines lines :anchors anchors}
         (let [[b bl]   (first bs)
               pre      (if first? [] (vec (repeat sep-blanks "")))
               start    (+ (count lines) (count pre))
               ;; the anchor id the SAME way ir.capability.toc/toc-of reads it: the hast `id` property (markdown)
               ;; or a pure front-end's node-meta :id (log records) — so anchors are keyed by the toc entries' ids
               id       (or (attr b "id") (:id (node/node-meta b)))
               anchors' (if (and id (not (str/blank? (str id)))) (assoc anchors (str id) start) anchors)]
           (recur (rest bs) (into (into lines pre) bl) anchors' false)))))))

(defn lower
  "IR → ANSI string (the ir.backend.html/lower analog). Convenience 1-arg over `render` with defaults."
  [ir] (render ir {}))
