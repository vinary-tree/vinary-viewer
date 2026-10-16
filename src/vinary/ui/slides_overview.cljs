(ns vinary.ui.slides-overview
  "The slide overview grid (ADR-0045 §Overview): one tile per FRAME of the deck in the slides layout — Tab / o,
   or View ▸ Slide Overview — to see the whole talk and jump anywhere in it.

   A separate overlay in .vv-content-wrap (a sibling of .vv-content, z 8), NOT a layout of the slides view: the
   slides view stays mounted underneath (closing is instant, the position is kept), and the overlay is invisible
   to in-page find, the Contents scroll-spy and the content MutationObserver. It borrows the mounted view's
   PDFDocumentProxy through pdf-cache/slides-snapshot (never a second proxy) and draws thumbnails lazily
   (vinary.renderer.pdf-thumbs: a bounded set near the viewport).

   A focused [data-vv-keynav] surface with its own keyboard model (like the Commit Graph): arrows, PageUp /
   PageDown and Home / End move the selection through the grid (vinary.slides.deck/grid-move); digits select a
   frame by its label (the number on a Beamer slide); Enter opens the selected frame — fully built by default
   (pdfpc's convention; Preferences can make it the frame's first overlay) — and Shift+Enter its other end; a
   click opens a tile (Shift+click the other end); Escape, Tab or o close it. Ctrl / Alt / Meta chords pass
   through to the app (Ctrl+F still finds)."
  (:require [reagent.core :as r]
            [re-frame.core :as rf]
            [vinary.renderer.pdf-cache :as pdf-cache]
            [vinary.renderer.pdf-layout :as layout]
            [vinary.renderer.pdf-thumbs :as thumbs]
            [vinary.renderer.scroll :as scroll]
            [vinary.slides.deck :as deck]))

(def ^:private typed-timeout-ms 1500)   ; a typed frame label forgets itself after this long

(def ^:private nav-keys
  {"ArrowLeft" :left "ArrowRight" :right "ArrowUp" :up "ArrowDown" :down
   "PageUp" :page-up "PageDown" :page-down "Home" :home "End" :end})

(defn- consume! [^js e] (.preventDefault e) (.stopPropagation e))

(defn- shown-size
  "The size [w h] page `idx` is shown at — a second-screen deck's slide half when `side` is set — or nil."
  [sizes idx side]
  (when-let [[w h :as size] (get sizes idx)]
    (when (and (number? w) (number? h) (pos? w) (pos? h))
      (deck/display-size size side))))

(defn- page-aspect
  "height / width of page `idx` as shown (shown-size), or 16:9."
  [sizes idx side]
  (if-let [[w h] (shown-size sizes idx side)] (/ h w) 0.5625))

(defn- tile-of-page
  "The tile index (= frame index) showing `page`."
  [d page]
  (deck/frame-of d (deck/clamp-page d (or page 0))))

(defn- panel
  "The overview itself, mounted only while open. Reads the borrowed controller snapshot when mounted and again
   whenever the slides view reports a new :doc-gen (a live refresh swapped the document in place)."
  []
  (let [node     (atom nil)
        ctrl     (atom nil)                 ; the pdf-thumbs controller
        ro       (atom nil)                 ; ResizeObserver on the panel → grid geometry
        snap     (r/atom (pdf-cache/slides-snapshot))
        geom     (r/atom {:cols 4 :page-rows 2})
        sel      (r/atom (tile-of-page (:deck @snap) (:current @snap)))
        typed    (atom nil)                 ; {:s "12" :timer id} — a frame label being typed
        seen-gen (atom (:doc-gen @snap))   ; the document generation the thumbnails were drawn from
        live-gen (atom (:doc-gen @snap))   ; the generation the last render saw (captured reactively in render)
        tile-el  (fn [i] (some-> ^js @node (.querySelector (str ".vv-slides-tile[data-frame=\"" i "\"]"))))
        reveal!  (fn []
                   (when-let [^js el (tile-el @sel)]
                     (scroll/scroll-rect-to! @node (.getBoundingClientRect el) {:block :nearest :margin 16})))
        measure! (fn []
                   (when-let [^js n @node]
                     (let [{:keys [sizes deck side]} @snap
                           rep (or (some-> deck :frames first second) 0)]
                       (reset! geom (layout/grid-geometry (.-clientWidth n) (.-clientHeight n)
                                                          (page-aspect sizes rep side))))))
        pick!    (fn [tile alt?]
                   (rf/dispatch [:slides/overview-pick (if alt? (:alt tile) (:page tile))]))
        clear-typed! (fn [] (when-let [t (:timer @typed)] (js/clearTimeout t)) (reset! typed nil))
        type-digit!  (fn [k]
                       (let [s (deck/digits-step (:s @typed) k)
                             d (:deck @snap)]
                         (when-let [t (:timer @typed)] (js/clearTimeout t))
                         (reset! typed {:s s :timer (js/setTimeout clear-typed! typed-timeout-ms)})
                         (when-let [page (deck/resolve-goto d s)]
                           (reset! sel (tile-of-page d page))
                           (reveal!))))
        on-key   (fn [tiles ^js e]
                   (let [k (.-key e)]
                     (when-not (or (.-ctrlKey e) (.-altKey e) (.-metaKey e))
                       (if-let [op (nav-keys k)]
                         (let [{:keys [cols page-rows]} @geom]
                           (consume! e)
                           (clear-typed!)
                           (swap! sel deck/grid-move op {:n (count tiles) :cols cols :page-rows page-rows})
                           (reveal!))
                         (cond
                           (= k "Enter")
                           (do (consume! e) (when-let [t (get tiles @sel)] (pick! t (.-shiftKey e))))

                           (#{"Escape" "Tab" "o"} k)
                           (do (consume! e) (rf/dispatch [:slides/overview-close]))

                           (re-matches #"[0-9]" k)
                           (do (consume! e) (type-digit! (js/parseInt k 10)))

                           :else nil)))))]
    (r/create-class
     {:display-name "vv-slides-overview"
      :component-did-mount
      (fn [_]
        (when-let [^js n @node]
          (.focus n #js {:preventScroll true})
          (measure!)
          (when (exists? js/ResizeObserver)
            (reset! ro (js/ResizeObserver. (fn [_] (measure!))))
            (.observe ^js @ro n))
          (r/after-render
           (fn []
             (when (and @node (:doc @snap))
               (reset! ctrl (thumbs/attach! @node (:doc @snap) (:side @snap))))
             (reveal!)))))
      :component-did-update
      (fn [_]
        ;; a live refresh swapped the document in place: re-borrow, keep the selection in range, redraw
        (let [gen @live-gen]
          (when (not= gen @seen-gen)
            (reset! seen-gen gen)
            (reset! snap (pdf-cache/slides-snapshot))
            (swap! sel #(min % (max 0 (dec (count (:frames (:deck @snap)))))))
            (r/after-render #(when @ctrl (thumbs/rebuild! @ctrl (:doc @snap) (:side @snap)))))))
      :component-will-unmount
      (fn [_]
        (clear-typed!)
        (when-let [^js o @ro] (.disconnect o))
        (thumbs/detach! @ctrl)
        (reset! ctrl nil))
      :reagent-render
      (fn []
        (let [_         (reset! live-gen @(rf/subscribe [:slides/doc-gen]))   ; a refresh re-renders → did-update
              invert?   @(rf/subscribe [:pdf/invert?])
              rep       @(rf/subscribe [:slides/overview-page])
              {:keys [deck sizes current side]} @snap
              tiles     (deck/overview-tiles deck rep)
              cur-tile  (tile-of-page deck current)
              ;; deref the selection HERE, eagerly: a deref inside the lazy `for` below is not tracked by
              ;; Reagent, so the grid would never re-render when the selection moves
              selected  @sel
              {:keys [cols]} @geom]
          [:div.vv-slides-overview {:ref         #(reset! node %)
                                    :class       (when invert? "vv-pdf-invert")
                                    :tab-index   0
                                    :data-vv-keynav ""
                                    :role        "dialog"
                                    :aria-label  "Slide overview"
                                    :on-key-down (partial on-key tiles)}
           [:div.vv-slides-overview-head
            [:span.vv-slides-overview-title "Slides"]
            [:span.vv-slides-overview-count (str (count tiles) " frames · " (count sizes) " pages")]
            [:span.vv-slides-overview-hint "Enter open · Shift+Enter other end · Esc close"]]
           [:div.vv-slides-overview-grid {:role "listbox"
                                          :style {:grid-template-columns (str "repeat(" cols ", minmax(0, 1fr))")}}
            (for [{:keys [frame page label] :as tile} tiles]
              ^{:key frame}
              [:div.vv-slides-tile {:class         (str (when (= frame selected) "vv-slides-tile-sel ")
                                                        (when (= frame cur-tile) "vv-slides-tile-current"))
                                    :role          "option"
                                    :aria-selected (= frame selected)
                                    :data-frame    frame
                                    :data-page     page
                                    :on-click      (fn [^js e] (reset! sel frame) (pick! tile (.-shiftKey e)))}
               [:div.vv-slides-tile-thumb {:style {:aspect-ratio (if-let [[w h] (shown-size sizes page side)]
                                                                  (str w " / " h)
                                                                  "16 / 9")}}]
               [:div.vv-slides-tile-label (or label (str (inc page)))]])]]))})))

(defn overview
  "The overview overlay while open, else nothing."
  []
  (when @(rf/subscribe [:slides/overview?])
    [panel]))
