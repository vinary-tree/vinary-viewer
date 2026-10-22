(ns vinary.ui.preview-strip
  "The right-side Preview Strip: selectable PDF pages or a bounded visual map of a continuous document."
  (:require [reagent.core :as r]
            [re-frame.core :as rf]
            [vinary.app.uri :as uri]
            [vinary.renderer.media :as media]
            [vinary.renderer.pdf-cache :as pdf-cache]
            [vinary.renderer.scroll :as scroll]
            [vinary.slides.deck :as deck]
            [vinary.slides.presenter :as presenter]
            [vinary.ui.slides-presenter :as slides-presenter]
            [vinary.ui.vertical-strip :as vertical-strip]))

(def ^:private max-map-height 4800)
(def ^:private max-blocks 180)

(defn map-height [document-height viewport-height]
  (min max-map-height (max viewport-height (* 0.12 document-height))))

(defn viewport-rect [document-height viewport-height scroll-top map-h]
  (let [total (max 1 document-height)
        h (min map-h (max 8 (* map-h (/ viewport-height total))))]
    {:top (-> (* map-h (/ scroll-top total)) (max 0) (min (- map-h h)))
     :height h}))

(defn- source-blocks [text map-h]
  (let [n (count text)
        samples (min max-blocks (max 1 (int (/ n 80))))]
    (mapv (fn [i]
            (let [start (int (* (/ i samples) n))
                  end (min n (+ start 120))
                  stop (.indexOf text "\n" start)
                  length (max 1 (- (if (and (>= stop 0) (< stop end)) stop end) start))]
              {:top (* (/ i samples) map-h) :width (min 0.95 (+ 0.12 (/ length 140)))
               :height (max 1 (/ map-h samples)) :tone :text}))
          (range samples))))

(defn- dom-blocks [^js scroller map-h]
  (let [^js root (or (.querySelector scroller ".markdown-body, .vv-fb-list, .vv-dir-list, .vv-log-view, .vv-table-view")
                     scroller)
        nodes (.querySelectorAll root "h1,h2,h3,h4,h5,h6,p,li,pre,blockquote,table,img,.vv-fb-row,.vv-log-row,.vv-table-row")
        n (.-length nodes)
        step (max 1 (int (js/Math.ceil (/ n max-blocks))))
        rect (.getBoundingClientRect scroller)
        total (max 1 (.-scrollHeight scroller))]
    (mapv (fn [i]
            (let [^js el (.item nodes i)
                  box (.getBoundingClientRect el)
                  top (+ (.-scrollTop scroller) (- (.-top box) (.-top rect)))
                  tag (.-tagName el)]
              {:top (* map-h (/ top total))
               :height (max 1 (min 24 (* map-h (/ (.-height box) total))))
               :width (-> (/ (.-width box) (max 1 (.-clientWidth scroller))) (max 0.08) (min 0.95))
               :tone (cond (re-matches #"H[1-6]" tag) :heading
                           (= "IMG" tag) :image
                           (= "PRE" tag) :code
                           :else :text)}))
          (range 0 n step))))

(defn- paint! [^js canvas {:keys [height blocks]}]
  (when (and canvas height)
    (let [w (max 1 (.-clientWidth canvas))
          h (max 1 (int height))
          ctx (.getContext canvas "2d")]
      (when (or (not= (.-width canvas) w) (not= (.-height canvas) h))
        (set! (.-width canvas) w)
        (set! (.-height canvas) h))
      (.clearRect ctx 0 0 w h)
      (doseq [{:keys [top width height tone]} blocks]
        (set! (.-fillStyle ctx) (case tone
                                 :heading "#7c9dc7" :image "#77987a" :code "#a78bbf" "#777b84"))
        (.fillRect ctx 5 top (* (- w 10) width) height)))))

(defn- minimap
  "Continuous-document overview. Wheel scrolling browses it alone; click/drag moves the document."
  [doc]
  (let [node (atom nil)
        canvas (atom nil)
        content (atom nil)
        observer (atom nil)
        mutations (atom nil)
        mutation-timer (atom nil)
        raf (atom nil)
        dirty? (atom true)
        dragging? (atom false)
        model (r/atom {:height 0 :viewport {:top 0 :height 0} :blocks []})
        scroller (fn [] (or (.querySelector js/document ".vv-content .cm-scroller")
                            (.querySelector js/document ".vv-content")))
        reveal! (fn [top height]
                  (when-let [^js pane @node]
                    (let [st (.-scrollTop pane) bottom (+ st (.-clientHeight pane))]
                      (cond (< top st) (set! (.-scrollTop pane) (max 0 (- top 20)))
                            (> (+ top height) bottom)
                            (set! (.-scrollTop pane) (+ top height 20 (- (.-clientHeight pane))))))))
        measure! (fn []
                   (when-let [^js c @content]
                     (let [total (max 1 (.-scrollHeight c))
                           vh (.-clientHeight c)
                           h (map-height total (max 1 (some-> @node .-clientHeight)))
                           viewport (viewport-rect total vh (.-scrollTop c) h)
                           blocks (if @dirty?
                                    (if (or (= "source" (:doc/kind doc))
                                            (.contains (.-classList c) "cm-scroller"))
                                      (source-blocks (or (:doc/text doc) "") h)
                                      (dom-blocks c h))
                                    (:blocks @model))]
                       (reset! dirty? false)
                       (reset! model {:height h :viewport viewport :blocks blocks})
                       (reveal! (:top viewport) (:height viewport)))))
        schedule! (fn [] (when-not @raf
                           (reset! raf (js/requestAnimationFrame
                                        (fn [] (reset! raf nil) (measure!))))))
        jump! (fn [^js e]
                (when-let [^js c @content]
                  (let [track (.querySelector ^js @node ".vv-preview-minimap-track")
                        rect (.getBoundingClientRect track)
                        fraction (-> (/ (- (.-clientY e) (.-top rect)) (max 1 (.-height rect))) (max 0) (min 1))]
                    (set! (.-scrollTop c)
                          (-> (- (* fraction (.-scrollHeight c)) (/ (.-clientHeight c) 2))
                              (max 0) (min (max 0 (- (.-scrollHeight c) (.-clientHeight c)))))))))]
    (r/create-class
     {:display-name "vv-preview-minimap"
      :component-did-mount
      (fn [_]
        (reset! content (scroller))
        (when-let [^js c @content]
          (.addEventListener c "scroll" schedule!)
          (when (exists? js/ResizeObserver)
            (let [o (js/ResizeObserver. (fn [_] (reset! dirty? true) (schedule!)))]
              (.observe o c) (.observe o @node) (reset! observer o)))
          (when (and (not= "source" (:doc/kind doc))
                     (not (.contains (.-classList c) "cm-scroller"))
                     (exists? js/MutationObserver))
            (let [o (js/MutationObserver.
                     (fn [_]
                       (reset! dirty? true)
                       (when-not @mutation-timer
                         (reset! mutation-timer
                                 (js/setTimeout (fn [] (reset! mutation-timer nil) (schedule!)) 200)))))]
              (.observe o c #js {:childList true :subtree true})
              (reset! mutations o))))
        (schedule!))
      :component-did-update (fn [_ _] (paint! @canvas @model))
      :component-will-unmount
      (fn [_]
        (when-let [^js c @content] (.removeEventListener c "scroll" schedule!))
        (when-let [^js o @observer] (.disconnect o))
        (when-let [^js o @mutations] (.disconnect o))
        (when-let [id @mutation-timer] (js/clearTimeout id))
        (when-let [id @raf] (js/cancelAnimationFrame id)))
      :reagent-render
      (fn [_]
        (let [{:keys [height viewport]} @model]
          [:div.vv-preview-minimap {:ref #(reset! node %)
                                    :aria-label "Preview Strip — document minimap"}
           [:div.vv-preview-minimap-track
            {:style {:height (str height "px")}
             :on-pointer-down (fn [e] (reset! dragging? true)
                                (.setPointerCapture (.-currentTarget e) (.-pointerId e))
                                (jump! e))
             :on-pointer-move (fn [e] (when @dragging? (jump! e)))
             :on-pointer-up (fn [_] (reset! dragging? false))
             :on-pointer-cancel (fn [_] (reset! dragging? false))}
            [:canvas.vv-preview-minimap-canvas {:ref #(reset! canvas %)
                                                 :style {:height (str height "px")}}]
            [:div.vv-preview-viewport {:style {:top (str (:top viewport) "px")
                                                :height (str (:height viewport) "px")}}]]]))})))

(defn- page-viewport [id visual-height]
  (when-let [^js page (.querySelector js/document (str "#vv-pdf-page-" (inc id)))]
    (when-let [^js pane (.querySelector js/document ".vv-content")]
      (let [page-box (.getBoundingClientRect page)
            pane-box (.getBoundingClientRect pane)
            visible-top (max 0 (- (.-top pane-box) (.-top page-box)))
            visible-bottom (min (.-height page-box) (- (.-bottom pane-box) (.-top page-box)))
            scale (/ visual-height (max 1 (.-height page-box)))]
        (when (> visible-bottom visible-top)
          {:top (str (+ 2 (* scale visible-top)) "px")
           :height (str (max 3 (* scale (- visible-bottom visible-top))) "px")})))))

(defn- page-strip-content [snapshot _position]
  (let [{:keys [doc sizes deck current side doc-gen layout]} snapshot
        slides? (= layout :slides)
        items (if slides?
                (presenter/preview-items deck sizes side current)
                (mapv (fn [i [w h]] {:id i :label (str "Page " (inc i))
                                      :rect {:x 0 :y 0 :w w :h h}})
                      (range (count sizes)) sizes))
        {:keys [w h]} (:rect (first items))
        ratio (if (pos? (or w 0)) (/ h w) 1.3)]
    [vertical-strip/strip
     {:items items :current current :ratio ratio
      :class "vv-preview-pages" :item-class "vv-preview-page"
      :aria-label (if slides? "Preview Strip — slides" "Preview Strip — PDF pages")
      :item-attrs (fn [item] {:data-page (:id item)})
      :on-select (fn [item]
                   (if slides?
                     (rf/dispatch [:slides/command [:jump (:id item)]])
                     (scroll/scroll-el-to!
                      (.querySelector js/document (str "#vv-pdf-page-" (inc (:id item))))
                      {:block :start :behavior "smooth"})))
      :render-item (fn [{:keys [id rect]} visual-height]
                     [:<>
                      [slides-presenter/region-box
                       {:gen doc-gen
                        :attrs {:class "vv-presenter-preview-box"
                                :style {:height (str visual-height "px")}}
                        :request {:doc doc :page id :rect rect :text? false}}]
                      (when (and (not slides?) (= current id))
                        (when-let [style (page-viewport id visual-height)]
                          [:div.vv-preview-page-viewport {:style style}]))])}]))

(defn- page-strip [snapshot _position]
  (let [page (r/atom nil)
        scroller (atom nil)
        frame (atom nil)
        measure! (fn []
                   (when-let [^js pane @scroller]
                     (let [top (+ 4 (.-top (.getBoundingClientRect pane)))
                           pages (.querySelectorAll pane ".vv-pdf-page")]
                       (loop [i 0]
                         (when (< i (.-length pages))
                           (let [^js el (.item pages i)]
                             (if (or (>= (.-bottom (.getBoundingClientRect el)) top)
                                     (= i (dec (.-length pages))))
                               (do
                                 (when (not= i @page) (reset! page i))
                                 (when-let [^js marker (.querySelector js/document ".vv-preview-page-current .vv-preview-page-viewport")]
                                   (when-let [^js box (.querySelector js/document ".vv-preview-page-current .vv-presenter-preview-box")]
                                     (when-let [style (page-viewport i (.-clientHeight box))]
                                       (set! (.-top (.-style marker)) (:top style))
                                       (set! (.-height (.-style marker)) (:height style))))))
                               (recur (inc i)))))))))
        schedule! (fn [] (when-not @frame
                           (reset! frame (js/requestAnimationFrame
                                          (fn [] (reset! frame nil) (measure!))))))]
    (r/create-class
     {:display-name "vv-pdf-preview-strip"
      :component-did-mount
      (fn [_]
        (reset! scroller (.querySelector js/document ".vv-content"))
        (when-let [^js pane @scroller]
          (.addEventListener pane "scroll" schedule! #js {:passive true}))
        (schedule!))
      :component-will-unmount
      (fn [_]
        (when-let [^js pane @scroller] (.removeEventListener pane "scroll" schedule!))
        (when-let [id @frame] (js/cancelAnimationFrame id)))
      :reagent-render
      (fn [snapshot position]
        [page-strip-content
         (if (= :slides (:layout snapshot)) snapshot
             (assoc snapshot :current (or @page (:current snapshot))))
         position])})))

(defn- web-minimap [uri _data]
  (let [node (atom nil)
        canvas (atom nil)
        observer (atom nil)
        size (r/atom 0)
        last-scroll (atom nil)
        dragging? (atom false)
        jump! (fn [^js e]
                (let [box (.getBoundingClientRect (.-currentTarget e))
                      fraction (-> (/ (- (.-clientY e) (.-top box)) (max 1 (.-height box)))
                                   (max 0) (min 1))]
                  (when-let [^js vv (.-vv js/window)]
                    (when (.-httpScrollFraction vv) (.httpScrollFraction vv fraction)))))]
    (r/create-class
     {:display-name "vv-web-preview-minimap"
      :component-did-mount
      (fn [_]
        (when (and @node (exists? js/ResizeObserver))
          (let [o (js/ResizeObserver. (fn [_] (reset! size (.-clientHeight ^js @node))))]
            (.observe o @node) (reset! observer o)))
        (reset! size (.-clientHeight ^js @node)))
      :component-did-update
      (fn [this _]
        (let [data (nth (r/argv this) 2 nil)
              data (when (= uri (:url data)) data)
              total (max 1 (or (:total data) 1))
              h (map-height total @size)
              blocks (mapv (fn [{:keys [top height width tag]}]
                             {:top (* h (/ top total))
                              :height (max 1 (min 24 (* h (/ height total))))
                              :width (or width 0.5)
                              :tone (cond (re-matches #"H[1-6]" (or tag "")) :heading
                                          (= tag "IMG") :image (= tag "PRE") :code :else :text)})
                           (:blocks data))
              viewport (viewport-rect total (or (:viewport data) 1) (or (:scroll data) 0) h)]
          (paint! @canvas {:height h :blocks blocks})
          (when (not= (:scroll data) @last-scroll)
            (reset! last-scroll (:scroll data))
            (when-let [^js pane @node]
              (let [st (.-scrollTop pane) bottom (+ st (.-clientHeight pane))
                    top (:top viewport) end (+ top (:height viewport))]
                (cond (< top st) (set! (.-scrollTop pane) (max 0 (- top 20)))
                      (> end bottom) (set! (.-scrollTop pane) (+ end 20 (- (.-clientHeight pane))))))))))
      :component-will-unmount (fn [_] (when-let [^js o @observer] (.disconnect o)))
      :reagent-render
      (fn [uri data]
        (let [data (when (= uri (:url data)) data)
              total (max 1 (or (:total data) 1))
              h (map-height total @size)
              viewport (viewport-rect total (or (:viewport data) 1) (or (:scroll data) 0) h)]
          [:div.vv-preview-minimap {:ref #(reset! node %)
                                    :aria-label "Preview Strip — web page minimap"}
           [:div.vv-preview-minimap-track
            {:style {:height (str h "px")}
             :on-pointer-down (fn [e]
                                (reset! dragging? true)
                                (.setPointerCapture (.-currentTarget e) (.-pointerId e))
                                (jump! e))
             :on-pointer-move (fn [e] (when @dragging? (jump! e)))
             :on-pointer-up (fn [_] (reset! dragging? false))
             :on-pointer-cancel (fn [_] (reset! dragging? false))}
            [:canvas.vv-preview-minimap-canvas {:ref #(reset! canvas %)
                                                 :style {:height (str h "px")}}]
            [:div.vv-preview-viewport {:style {:top (str (:top viewport) "px")
                                                :height (str (:height viewport) "px")}}]]]))})))

(defn panel []
  (let [visible? @(rf/subscribe [:ui/preview-strip-visible?])
        console? @(rf/subscribe [:slides/console?])
        audience? @(rf/subscribe [:slides/chromeless?])
        doc @(rf/subscribe [:doc/active])
        uri @(rf/subscribe [:ui/active-uri])
        source? @(rf/subscribe [:ui/active-view-source?])
        slides @(rf/subscribe [:slides/state])
        web-map @(rf/subscribe [:web/minimap])
        snapshot (pdf-cache/slides-snapshot)
        web? (and (not source?)
                  (or (= "html" (:doc/kind doc))
                      (and (string? uri) (re-find #"^https?://" uri))))
        web-url (if (= "html" (:doc/kind doc))
                  (if (uri/remote? (:doc/path doc))
                    (media/remote->vv-remote-url (:doc/path doc))
                    (media/path->file-url (:doc/path doc)))
                  uri)]
    (when (and (not console?) (not audience?)
               (or web? (:doc/kind doc) (:doc/path doc)))
      (if-not visible?
        [:button.vv-preview-reopen {:type "button" :title "Show Preview Strip"
                                    :aria-label "Show Preview Strip"
                                    :on-click #(rf/dispatch [:view/preview-strip-toggle])} "‹"]
        [:aside.vv-preview-rail {:aria-label "Preview Strip"}
       [:div.vv-preview-head
        [:span "Preview Strip"]
        [:button.vv-preview-collapse {:type "button" :title "Collapse Preview Strip"
                                      :aria-label "Collapse Preview Strip"
                                      :on-click #(rf/dispatch [:view/preview-strip-toggle])} "›"]]
       (if (and (not source?) (= "pdf" (:doc/kind doc)) (:doc snapshot) (seq (:sizes snapshot)))
         ^{:key (str "pdf:" (:doc/path doc) ":" (:doc-gen snapshot))} [page-strip snapshot slides]
         (if web?
           ^{:key (str "web:" web-url)} [web-minimap web-url web-map]
           ^{:key (str "map:" (:doc/path doc) ":" (:doc/stamp doc) ":" source?)} [minimap doc]))]))))
