(ns vinary.ui.views
  "Renderer views: the app shell = tab strip + content area. The active document's HTML is written into
   a DOM node imperatively via a ref (set once per content change, no VDOM-diffing the document body).
   Empty (no tabs) shows the Vinary Tree shield watermark."
  (:require [reagent.core :as r]
            [re-frame.core :as rf]
            [clojure.string :as str]
            [vinary.app.uri :as uri]
            [vinary.app.nav :as nav]
            [vinary.app.link :as link]
            [vinary.renderer.scroll :as scroll]
            [vinary.renderer.pdf :as pdf]
            [vinary.renderer.pdf-cache :as pdf-cache]
            [vinary.ui.tabs :as tabs]
            [vinary.ui.icons :as icons]
            [vinary.ui.platform :as platform]
            [vinary.ui.sidebar :as sidebar]
            [vinary.ui.palette :as palette]
            [vinary.ui.menubar :as menubar]
            [vinary.ui.context-menu :as ctx-menu]
            [vinary.ui.preview-context :as preview-ctx]
            [vinary.ui.preview-navigation :as preview-nav]
            [vinary.ui.settings :as settings-ui]
            [vinary.ui.about :as about]
            [vinary.ui.ext-toolbar :as ext-toolbar]
            [vinary.ui.extensions :as extensions-ui]
            [vinary.ui.keybindings-editor :as kbedit]
            [vinary.renderer.toc :as toc]
            [vinary.renderer.figures :as figures]
            [vinary.renderer.media :as media]
            [vinary.renderer.mermaid :as mermaid]
            [vinary.renderer.syntax :as syntax]))

(defn- set-inner! [^js node html]
  (when node (set! (.-innerHTML node) (or html ""))))

(defn- link-display [{:keys [kind path]}]
  (case kind :anchor (str "#" path) :http path (:file :dir) path nil))

(defn- text-node? [^js node]
  (= 3 (.-nodeType node)))

(defn- node-contained? [^js root ^js node]
  (when (and root node)
    (or (identical? root node)
        (when-let [el (if (= 1 (.-nodeType node)) node (.-parentElement node))]
          (.contains root el)))))

(defn- first-text-node [^js node]
  (when node
    (if (text-node? node)
      node
      (let [kids (.-childNodes node)]
        (first (keep #(first-text-node (aget kids %)) (range (.-length kids))))))))

(defn- last-text-node [^js node]
  (when node
    (if (text-node? node)
      node
      (let [kids (.-childNodes node)]
        (first (keep #(last-text-node (aget kids %)) (range (dec (.-length kids)) -1 -1)))))))

(defn- text-node-at [^js node offset]
  (cond
    (not node) nil
    (text-node? node) [node offset]
    :else
    (let [kids (.-childNodes node)
          len  (.-length kids)
          i    (max 0 (min len (or offset 0)))]
      (or (when (< i len)
            (when-let [t (first-text-node (aget kids i))]
              [t 0]))
          (when (pos? i)
            (when-let [t (last-text-node (aget kids (dec i)))]
              [t (count (.-nodeValue t))]))
          (when-let [t (first-text-node node)]
            [t 0])))))

(defn- caret-at [x y]
  (let [doc js/document]
    (or (when-let [f (aget doc "caretPositionFromPoint")]
          (when-let [p (.call f doc x y)]
            [(.-offsetNode p) (.-offset p)]))
        (when-let [f (aget doc "caretRangeFromPoint")]
          (when-let [r (.call f doc x y)]
            [(.-startContainer r) (.-startOffset r)])))))

(defn- active-selection [^js body]
  (when-let [sel (.getSelection js/window)]
    (when (and (pos? (.-rangeCount sel)) (not (.-isCollapsed sel)))
      (let [range (.getRangeAt sel 0)
            text  (str sel)]
        (when (and (seq (str/trim text))
                   (node-contained? body (.-startContainer range))
                   (node-contained? body (.-endContainer range)))
          {:text text :node (.-startContainer range) :offset (.-startOffset range)})))))

(defn- current-term [^js body ^js e]
  (when-let [[node offset] (caret-at (.-clientX e) (.-clientY e))]
    (when-let [[text-node text-offset] (text-node-at node offset)]
      (when (node-contained? body text-node)
        (when-let [{:keys [text start]} (preview-ctx/term-at (.-nodeValue text-node) text-offset)]
          {:text text :node text-node :offset start})))))

(defn- selection-or-term [^js body ^js e]
  (or (active-selection body) (current-term body e)))

(defn- source-element [^js node]
  (loop [el (if (= 1 (.-nodeType node)) node (.-parentElement node))]
    (when el
      (if (.hasAttribute el "data-vv-source-start-line")
        el
        (recur (.-parentElement el))))))

(defn- source-span [^js el]
  {:kind         (.getAttribute el "data-vv-source-kind")
   :start-line   (preview-ctx/parse-int-or-nil (.getAttribute el "data-vv-source-start-line"))
   :start-column (preview-ctx/parse-int-or-nil (.getAttribute el "data-vv-source-start-column"))
   :start-offset (preview-ctx/parse-int-or-nil (.getAttribute el "data-vv-source-start-offset"))
   :end-offset   (preview-ctx/parse-int-or-nil (.getAttribute el "data-vv-source-end-offset"))})

(defn- source-location [source path node offset text]
  (when-let [el (and node (source-element node))]
    (let [{:keys [kind start-line start-column start-offset end-offset] :as span} (source-span el)
          exact-text? (= "text" kind)
          source-offset (cond
                          (and exact-text? (number? start-offset) (number? offset))
                          (+ start-offset offset)

                          (and (seq text) (number? start-offset))
                          (preview-ctx/best-source-offset source start-offset end-offset text start-offset)

                          :else start-offset)
          lc (or (preview-ctx/offset->line-column source source-offset)
                 {:line start-line :column start-column})]
      (preview-ctx/location-string path lc))))

(defn- link-text [^js a]
  (or (not-empty (str/trim (.-textContent a)))
      (when-let [^js img (.querySelector a "img[alt]")]
        (not-empty (str/trim (or (.getAttribute img "alt") ""))))
      ""))

(defn- preview-link-target [^js a source path]
  (let [href (link/target-for-anchor a)
        text (link-text a)]
    (when-let [{link-kind :kind link-path :path} (link/classify href text)]
      {:kind :preview-link
       :link-kind link-kind
       :path link-path
       :uri href
       :text text
       :source-location (source-location source path a nil nil)})))

(defn- source-location-for-pos [path view pos]
  (some->> (syntax/line-info-at view pos)
           (preview-ctx/location-string path)))

(defn- source-term-at [view pos]
  (when-let [{:keys [text line-from]} (syntax/line-info-at view pos)]
    (when-let [{term-text :text start :start} (preview-ctx/term-at text (- pos line-from))]
      {:text term-text
       :pos (+ line-from start)})))

(defn markdown-body
  "A .markdown-body whose innerHTML tracks the HTML passed in (content-view holds the subscription).
   Intercepts link clicks (→ in-pane navigation, never replacing the window), shows the hovered link's URI
   in the status bar, font-matches embedded SVG figures, restores the per-history scroll position, and
   opens a themed context menu on right-click. Middle-click / Ctrl+click open a link in a new tab."
  [_html _source _path]
  (let [node (atom nil)
        html* (atom nil)
        source* (atom nil)
        path* (atom nil)
        render-token (atom 0)
        raf  (atom false)
        resize-observer (atom nil)
        last-link (atom nil)
        refresh-toc! (fn []
                       (when-let [^js content (some-> @node (.closest ".vv-content"))]
                         (toc/refresh! content)))
        after-figures! (fn [token f]
                         (-> (figures/scale-figures! @node)
                             (.then (fn [_]
                                      (when (and @node (or (nil? token) (= token @render-token)))
                                        (refresh-toc!)
                                        (when f (f)))))))
        render-html! (fn [html]
                       (let [token (swap! render-token inc)]
                         (reset! html* html)
                         (set-inner! @node html)
                         (after-figures! token #(scroll/apply! @node))))
        on-resize (fn []
                    (when (and @node (not @raf))
                      (reset! raf true)
                      (js/requestAnimationFrame
                       (fn []
                         (reset! raf false)
                         (when @node
                           (after-figures! nil nil))))))
        observe-resize! (fn []
                          (if (exists? js/ResizeObserver)
                            (let [o (js/ResizeObserver. (fn [_] (on-resize)))]
                              (.observe o @node)
                              (when-let [^js content (.closest @node ".vv-content")]
                                (.observe o content))
                              (reset! resize-observer o))
                            (.addEventListener js/window "resize" on-resize)))
        follow (fn [^js a new-tab? ^js e]
                 (when-let [target (link/classify (link/target-for-anchor a) (.-textContent a))]
                   (when-let [event (preview-nav/open-event target new-tab?)]
                     (.preventDefault e)
                     (rf/dispatch event))))
        on-click (fn [^js e] (when-let [^js a (.closest (.-target e) "a")] (follow a (.-ctrlKey e) e)))
        on-aux   (fn [^js e] (when (= 1 (.-button e))
                               (when-let [^js a (.closest (.-target e) "a")] (follow a true e))))
        on-over  (fn [^js e]
                   (let [^js a (.closest (.-target e) "a")
                         href  (when a (link/target-for-anchor a))]
                     (when (not= href @last-link)
                       (reset! last-link href)
                       (rf/dispatch [:ui/hover-link (when a (link-display (link/classify href "")))]))))
        on-leave (fn [_] (reset! last-link nil) (rf/dispatch [:ui/hover-link nil]))
        on-ctx   (fn [^js e]
                   (let [source @source*
                         path @path*
                         ^js body @node
                         ^js a (.closest (.-target e) "a")
                         target (or (when a (preview-link-target a source path))
                                    (let [{:keys [text node offset] :as current} (selection-or-term body e)
                                          source-node (or node (.-target e))]
                                      {:kind :preview-body
                                       :text (or text "")
                                       :source-location (source-location source path source-node offset text)}))]
                     (.preventDefault e)
                     (.stopPropagation e)
                     (rf/dispatch [:context-menu/show {:x (.-clientX e) :y (.-clientY e) :target target}])))]
    (r/create-class
     {:display-name "vv-markdown-body"
      :component-did-mount  (fn [this]
                              (let [[_ html source path] (r/argv this)]
                                (reset! source* source)
                                (reset! path* path)
                                (render-html! html))
                              (observe-resize!)
                              (.addEventListener @node "click" on-click)
                              (.addEventListener @node "auxclick" on-aux)
                              (.addEventListener @node "mouseover" on-over)
                              (.addEventListener @node "mouseleave" on-leave)
                              (.addEventListener @node "contextmenu" on-ctx))
      :component-did-update (fn [this]
                              (let [[_ html source path] (r/argv this)]
                                (reset! source* source)
                                (reset! path* path)
                                (when (not= html @html*)
                                  (render-html! html))))
      :component-will-unmount (fn [_]
                                (.removeEventListener js/window "resize" on-resize)
                                (when @resize-observer
                                  (let [^js o @resize-observer]
                                    (.disconnect o))
                                  (reset! resize-observer nil))
                                (when @node
                                  (.removeEventListener @node "click" on-click)
                                  (.removeEventListener @node "auxclick" on-aux)
                                  (.removeEventListener @node "mouseover" on-over)
                                  (.removeEventListener @node "mouseleave" on-leave)
                                  (.removeEventListener @node "contextmenu" on-ctx))
                                (rf/dispatch [:ui/hover-link nil]))
      :reagent-render       (fn [_html _source _path] [:div.markdown-body {:ref (fn [el] (reset! node el))}])})))

(defn- pdf-rect [^js node]
  (let [r (.getBoundingClientRect node)]
    #js {:x (.-left r) :y (.-top r) :width (.-width r) :height (.-height r)}))

;; RETIRED — the native PDF WebContentsView is superseded by the in-renderer pdf.js `pdf-view` (ADR 0013);
;; kept recoverable per the repo's comment-don't-delete rule. (`pdf-rect` above STAYS — web-host uses it.)
#_
(defn pdf-host
  "A placeholder div the size/position of which drives a main-owned native PDF view (positioned over
   it). On mount/update we send show+bounds; a ResizeObserver + window resize keep the bounds synced;
   on unmount we hide the native view. (Imperative DOM, like markdown-body — the native view is opaque
   and sits on top of this div.)"
  [_path]
  (let [node     (atom nil)
        obs      (atom nil)
        overlay? (atom false)
        vv       (fn [] (.-vv js/window))
        show!    (fn [path] (when-let [^js v (vv)] (when (and (.-pdfShow v) @node) (.pdfShow v path (pdf-rect @node)))))
        hide!    (fn [] (when-let [^js v (vv)] (when (.-pdfHide v) (.pdfHide v))))
        bounds!  (fn [] (when-let [^js v (vv)] (when (and (.-pdfBounds v) @node) (.pdfBounds v (pdf-rect @node)))))
        ;; the native view always paints above the DOM, so hide it while any menu/dialog overlay is open
        sync!    (fn [this] (if @overlay? (hide!) (show! (second (r/argv this)))))]
    (r/create-class
     {:display-name "vv-pdf-host"
      :component-did-mount
      (fn [this]
        (sync! this)
        (when (exists? js/ResizeObserver)
          (let [o (js/ResizeObserver. (fn [_] (bounds!)))] (.observe o @node) (reset! obs o)))
        (.addEventListener js/window "resize" bounds!)
        (scroll/apply! @node))
      :component-did-update   (fn [this] (sync! this))
      :component-will-unmount (fn [_]
                                (hide!)
                                (when @obs (.disconnect ^js @obs))
                                (.removeEventListener js/window "resize" bounds!))
      :reagent-render         (fn [_path]
                                (reset! overlay? @(rf/subscribe [:ui/overlay-open?]))
                                [:div.vv-pdf-host {:ref (fn [el] (reset! node el))}])})))

(defn web-host
  "A placeholder div over which the main-owned HTTP web view is positioned (the native view paints on top).
   On mount/update we send show+url+bounds; a ResizeObserver + window resize keep bounds synced; on unmount
   we hide the view.

   Because the native view always paints above the DOM, a DOM overlay cannot draw over it. A full-window
   MODAL (settings/about/keybinding dialog or the command palette) hides the view outright. A transient
   MENU / context-menu instead FREEZES the page: we capture it to an inert raster (vv:http-snapshot), show
   that <img> here, and hide the native view — so the menu floats over the still page — then restore the
   live view (and drop the snapshot) once the overlay closes."
  [_url]
  (let [node    (atom nil)
        obs     (atom nil)
        snap    (r/atom nil)          ; frozen-page data-URL while a menu/context-menu is open, else nil
        modal?  (atom false)
        menu?   (atom false)
        vv      (fn [] (.-vv js/window))
        show!   (fn [url] (when-let [^js v (vv)] (when (and (.-httpShow v) @node) (.httpShow v url (pdf-rect @node)))))
        hide!   (fn [] (when-let [^js v (vv)] (when (.-httpHide v) (.httpHide v))))
        bounds! (fn [] (when-let [^js v (vv)] (when (and (.-httpBounds v) @node) (.httpBounds v (pdf-rect @node)))))
        sync!   (fn [this]
                  (let [url (second (r/argv this))]
                    (cond
                      ;; a full-window dialog/palette covers the content — hide the native view entirely
                      @modal? (do (hide!) (when @snap (reset! snap nil)))
                      ;; a menu/context-menu is open — capture, then hide the view so the DOM menu shows over
                      ;; the still image (skip if already frozen; re-check on resolve in case it closed first)
                      @menu?  (when (not @snap)
                                (when-let [^js v (vv)]
                                  (when (.-httpSnapshot v)
                                    (-> (.httpSnapshot v)
                                        (.then (fn [data]
                                                 (when (and data @menu? (not @modal?))
                                                   (reset! snap data)
                                                   (hide!))))))))
                      ;; nothing open — show the live view on top, then drop the (now-occluded) snapshot
                      :else   (do (show! url) (when @snap (reset! snap nil))))))]
    (r/create-class
     {:display-name "vv-web-host"
      :component-did-mount
      (fn [this]
        (sync! this)
        (when (exists? js/ResizeObserver)
          (let [o (js/ResizeObserver. (fn [_] (bounds!)))] (.observe o @node) (reset! obs o)))
        (.addEventListener js/window "resize" bounds!)
        (scroll/apply! @node))
      :component-did-update   (fn [this] (sync! this))
      :component-will-unmount (fn [_]
                                (hide!)
                                (when @obs (.disconnect ^js @obs))
                                (.removeEventListener js/window "resize" bounds!))
      :reagent-render         (fn [_url]
                                (reset! modal? @(rf/subscribe [:ui/modal-open?]))
                                (reset! menu? (boolean (or @(rf/subscribe [:ui/menu])
                                                           @(rf/subscribe [:ui/context-menu]))))
                                [:div.vv-web-host {:ref (fn [el] (reset! node el))}
                                 (when @snap [:img.vv-web-snap {:src @snap}])])})))

(defn source-view
  "A read-only CodeMirror 6 view of a source file, highlighted via web-tree-sitter when a grammar is
   registered for its extension. Re-created on live-refresh (text change)."
  [_text _path]
  (let [node (atom nil) view (atom nil) path* (atom nil)]
    (letfn [(build! [this]
              (let [[_ text path] (r/argv this)]
                (reset! path* path)
                (reset! view (syntax/create-source-view @node text (syntax/grammar-for path)))))
            (destroy! [] (when @view (.destroy ^js @view) (reset! view nil)))
            (on-ctx [^js e]
              (.preventDefault e)
              (.stopPropagation e)
              (let [v        @view
                    path     @path*
                    selected (syntax/selected-text v)
                    sel-pos  (syntax/selection-start v)
                    pos      (or sel-pos (syntax/pos-at-coords v (.-clientX e) (.-clientY e)))
                    term     (when-not (seq selected) (source-term-at v pos))
                    text     (or selected (:text term) "")
                    loc-pos  (or sel-pos (:pos term) pos)]
                (rf/dispatch [:context-menu/show {:x (.-clientX e)
                                                  :y (.-clientY e)
                                                  :target {:kind :source-body
                                                           :path path
                                                           :text text
                                                           :source-location (source-location-for-pos path v loc-pos)}}])))]
      (r/create-class
       {:display-name           "vv-source-view"
        :component-did-mount     (fn [this] (build! this) (scroll/apply! @node))
        :component-did-update    (fn [this] (destroy!) (build! this) (scroll/apply! @node))
        :component-will-unmount  (fn [_] (destroy!))
        :reagent-render          (fn [_text _path] [:div.vv-source {:ref (fn [el] (reset! node el))
                                                                     :on-context-menu on-ctx}])}))))

(defn mermaid-view
  "A directly-opened Mermaid source file rendered to inline SVG in the renderer."
  [_source _path]
  (let [node (atom nil)
        token (atom 0)]
    (letfn [(render! [source]
              (let [t (swap! token inc)]
                (-> (mermaid/render-source source)
                    (.then (fn [svg]
                             (when (and @node (= t @token))
                               (set-inner! @node (str "<div class=\"vv-mermaid\">" svg "</div>"))
                               (scroll/apply! @node))))
                    (.catch (fn [e]
                              (when (and @node (= t @token))
                                (set-inner! @node (mermaid/error-html (.-message e) source))
                                (scroll/apply! @node)))))))]
      (r/create-class
       {:display-name "vv-mermaid-view"
        :component-did-mount  (fn [this] (render! (second (r/argv this))))
        :component-did-update (fn [this] (render! (second (r/argv this))))
        :component-will-unmount (fn [_] (swap! token inc))
        :reagent-render (fn [_source _path] [:div.vv-mermaid-view {:ref (fn [el] (reset! node el))}])}))))

(defn image-view
  "A directly-opened image filling the content width; consumes any pending scroll-restore on mount."
  [_path _stamp]
  (let [node (atom nil)]
    (r/create-class
     {:display-name "vv-image-view"
      :component-did-mount  (fn [_] (scroll/apply! @node))
      :component-did-update (fn [_] (scroll/apply! @node))
      :reagent-render       (fn [path stamp] [:div.vv-image-view {:ref (fn [el] (reset! node el))}
                                              [:img {:src (media/cache-bust-local-media-url (media/path->file-url path) stamp)
                                                     :alt path}]])})))

(defn pdf-view
  "Renders a PDF in-DOM via pdf.js (bytes from the renderer cache, keyed by :doc/path). The .vv-pdf-doc
   div is the scroll-restore / find / selection / context-menu root and lives inside the scrolling
   .vv-content, so PDFs inherit the app's in-page find, copy, themed context menu, and smooth-scroll —
   parity with the Markdown/source previews. A new [path stamp] remounts; zoom/fit/invert re-drive
   pdf/update!. Right-click reuses the markdown :preview-body context menu (Copy)."
  [_path _stamp]
  (let [node   (atom nil)
        ctrl   (atom nil)            ; the pdf engine controller (pdf/mount! return)
        cur    (atom nil)            ; [path stamp] currently mounted
        vstate (atom nil)            ; latest :pdf/view-state (captured reactively in render)
        on-ctx (fn [^js e]
                 (let [{:keys [text]} (selection-or-term @node e)]
                   (.preventDefault e) (.stopPropagation e)
                   (rf/dispatch [:context-menu/show {:x (.-clientX e) :y (.-clientY e)
                                                     :target {:kind :preview-body :text (or text "")}}])))
        remount! (fn [path stamp]
                   (when @ctrl (pdf/unmount! @ctrl) (reset! ctrl nil))
                   (when @node (set! (.-innerHTML @node) ""))
                   (when-let [bytes (pdf-cache/get-bytes path)]
                     (reset! ctrl (pdf/mount! @node bytes path @vstate))
                     (reset! cur [path stamp])
                     (scroll/apply! @node)))]
    (r/create-class
     {:display-name "vv-pdf-view"
      :component-did-mount    (fn [this]
                                (let [[_ path stamp] (r/argv this)]
                                  (.addEventListener @node "contextmenu" on-ctx)
                                  (remount! path stamp)))
      :component-did-update   (fn [this]
                                (let [[_ path stamp] (r/argv this)]
                                  (if (not= [path stamp] @cur)
                                    (remount! path stamp)             ; new doc / live-refresh → reload
                                    (when @ctrl (pdf/update! @ctrl @vstate)))))  ; zoom/fit/invert
      :component-will-unmount (fn [_]
                                (when @node (.removeEventListener @node "contextmenu" on-ctx))
                                (when @ctrl (pdf/unmount! @ctrl) (reset! ctrl nil)))
      :reagent-render         (fn [_path _stamp]
                                (reset! vstate @(rf/subscribe [:pdf/view-state]))  ; reactive → did-update
                                [:div.vv-pdf-doc {:ref (fn [el] (reset! node el))}])})))

(defn watermark []
  [:div.vv-watermark
   [:img.vv-watermark-logo {:src "assets/vinary-tree-logo.svg" :alt ""}]])

;; ---- in-pane directory browser ----
(defn- human-size [n]
  (cond
    (nil? n)         ""
    (< n 1024)       (str n " B")
    (< n 1048576)    (str (.toFixed (/ n 1024) 1) " KB")
    (< n 1073741824) (str (.toFixed (/ n 1048576) 1) " MB")
    :else            (str (.toFixed (/ n 1073741824) 1) " GB")))

(defn- human-mtime [ms]
  (if (nil? ms) "" (try (.toLocaleString (js/Date. ms)) (catch :default _ ""))))

(defn- dir-entry [{:keys [name path dir?] :as entry} selected?]
  [:div {:class           (str "vv-fb-row" (when selected? " vv-fb-sel"))
         :title           path
         :data-path       path
         :data-dir        (str (boolean dir?))
         :on-click        (fn [^js e]
                            (rf/dispatch [:dir/select path])           ; always highlight (Alt+Down target)
                            (cond
                              (.-ctrlKey e)                 (rf/dispatch [:doc/open-new path])
                              (platform/single-click-open?) (rf/dispatch [:doc/open path])))
         :on-double-click (fn [^js e]
                            (when-not (platform/single-click-open?)
                              (rf/dispatch [(if (.-ctrlKey e) :doc/open-new :doc/open) path])))
         :on-context-menu (fn [^js e]
                            (.preventDefault e) (.stopPropagation e)
                            (rf/dispatch [:context-menu/show
                                          {:x (.-clientX e) :y (.-clientY e)
                                           :target {:kind (if dir? :dir :file) :path path}}]))}
   (if dir? (icons/icon :folder) (icons/file-icon name))
   [:span.vv-fb-name name]
   [:span.vv-fb-size (when-not dir? (human-size (:size entry)))]
   [:span.vv-fb-mtime (human-mtime (:mtime entry))]])

(defn dir-view
  "A directory rendered in the preview pane as a detailed list (name · size · modified). Entries arrive
   from main as :doc/entries; the explicit selection + the persisted dir→child trail pick the highlighted
   target (double-click / Enter / Alt+Down opens it, Ctrl+click opens it in a new tab); arrow keys scroll
   the listing. Pure Reagent (interactive rows), restoring per-history scroll on mount/update like
   image-view."
  [_path _entries]
  (let [node (atom nil)]
    (r/create-class
     {:display-name         "vv-dir-view"
      :component-did-mount  (fn [_] (scroll/apply! @node) (when-let [^js n @node] (.focus n)))
      :component-did-update (fn [_] (scroll/apply! @node))
      :reagent-render
      (fn [path entries]
        (let [dsel     @(rf/subscribe [:ui/dir-selected])
              trail    (:trail @(rf/subscribe [:ui/recent]))
              sorted   (nav/sort-entries entries)
              selected (nav/effective-selected path entries dsel trail)
              label    (let [n (uri/basename path)] (if (str/blank? n) path n))]
          [:div.vv-fb
           {:tab-index   0
            :ref         (fn [el] (reset! node el))
            :on-key-down (fn [^js e]
                           (when (and (= (.-key e) "Enter")
                                      (not (.-altKey e)) (not (.-ctrlKey e)) (not (.-metaKey e)))
                             (.preventDefault e) (rf/dispatch [:nav/open-target])))}
           [:div.vv-fb-head
            [:span.vv-fb-path {:title (uri/display path)}
             (str label "  ·  " (count sorted) (if (= 1 (count sorted)) " item" " items"))]]
           (when (seq sorted)
             [:div.vv-fb-head-row [:span.vv-fb-col-icon] [:span "Name"] [:span "Size"] [:span "Modified"]])
           (if (seq sorted)
             (into [:div.vv-fb-body]
                   (for [entry sorted]
                     ^{:key (:path entry)} [dir-entry entry (= (:path entry) selected)]))
             [:div.vv-fb-empty "Empty directory"])]))})))

(defn content-view
  "Renderer registry (Strategy): the active tab's content is shown by its URI scheme (http → web view)
   or, for a local file, its :doc/kind."
  []
  (let [doc  @(rf/subscribe [:doc/active])
        tabs @(rf/subscribe [:ui/tabs])
        uri  @(rf/subscribe [:ui/active-uri])
        vs?  @(rf/subscribe [:ui/active-view-source?])]
    [:div.vv-content
     {:class (when (uri/http? uri) "vv-content-web")   ; web view is edge-to-edge (no document gutter)
      :on-scroll (fn [^js e] (toc/spy! (.-currentTarget e)))}
     (cond
       (empty? tabs)               [watermark]
       (nil? uri)                  [:div.vv-empty "New Tab"]
       (uri/http? uri)             [web-host uri]
       (:doc/error doc)            [:div.vv-error "Error: " (:doc/error doc)]
       (= "pdf" (:doc/kind doc))   ^{:key (str "pdf:" (:doc/path doc))}
                                   [pdf-view (:doc/path doc) (:doc/stamp doc)]
       (= "image" (:doc/kind doc)) ^{:key (str (:doc/path doc) ":" (:doc/stamp doc))}
                                   [image-view (:doc/path doc) (:doc/stamp doc)]
       (= "diagram" (:doc/kind doc)) [:div.vv-diagram [markdown-body (:doc/html doc) (:doc/text doc) (:doc/path doc)]]
       (= "mermaid" (:doc/kind doc)) ^{:key (str "mermaid:" (:doc/path doc) ":" (:doc/stamp doc))}
                                     [mermaid-view (:doc/text doc) (:doc/path doc)]
       (= "source" (:doc/kind doc)) ^{:key (:doc/path doc)} [source-view (:doc/text doc) (:doc/path doc)]
       ;; "View Source" on a markdown doc → show its raw source in the pane (not replacing the window)
       (and vs? (= "markdown" (:doc/kind doc)) (:doc/text doc))
       ^{:key (str "src:" (:doc/path doc))} [source-view (:doc/text doc) (:doc/path doc)]
       (= "directory" (:doc/kind doc)) ^{:key (str "dir:" (:doc/path doc))}
                                       [dir-view (:doc/path doc) (:doc/entries doc)]
       (:doc/html doc)             [markdown-body (:doc/html doc) (:doc/text doc) (:doc/path doc)]
       :else                       [:div.vv-empty "Rendering…"])]))

(defn- breadcrumb
  "The active local path rendered as clickable folder segments (root → leaf). Clicking a segment
   navigates the active tab there (a folder → the directory browser); hovering shows it in the status
   bar. Replaces the address input while Ctrl is held and the cursor is over the URI bar."
  [active-uri]
  (into [:div.vv-breadcrumb {:title "Click a folder to open it"}]
        (apply concat
               (map-indexed
                (fn [i {:keys [name path]}]
                  (let [crumb ^{:key path}
                              [:a.vv-crumb
                               {:href           "#"
                                :on-mouse-enter #(rf/dispatch [:ui/hover-link path])
                                :on-mouse-leave #(rf/dispatch [:ui/hover-link nil])
                                :on-click       (fn [^js e] (.preventDefault e) (rf/dispatch [:tab/navigate path]))}
                               (if (= name "/") "/" name)]]
                    (if (zero? i)
                      [crumb]
                      [^{:key (str "sep" path)} [:span.vv-crumb-sep "›"] crumb])))
                (uri/segments active-uri)))))

(defn uri-bar
  "Browser-style nav row: back / forward / reload + the address bar. The input shows the active tab's
   URI; typing edits a local draft and triggers Fish-style path auto-completion — an inline ghost
   suggestion plus a dropdown of matching children that appears only when the completion is ambiguous.
   Tab fills the matches' common prefix (accepting outright when one remains); →/End accept the ghost;
   ↑/↓ move the dropdown selection (the ghost reflects it, the typed text stays put so the match set is
   stable); Enter opens the complete path or the most-likely prefix match, else a non-intrusive inline
   error (never a dialog); Esc closes the dropdown, then reverts. Holding Ctrl while hovering turns a
   local path into a clickable breadcrumb. (Per-tab history: back/forward act on the active tab.)"
  []
  (let [draft  (r/atom nil)
        hover? (r/atom false)]
    (fn []
      (let [active-uri  @(rf/subscribe [:ui/active-uri])
            active-path @(rf/subscribe [:ui/active-path])
            ctrl?       @(rf/subscribe [:ui/ctrl-held?])
            back?       @(rf/subscribe [:history/can-back?])
            fwd?        @(rf/subscribe [:history/can-forward?])
            uc          @(rf/subscribe [:ui/uri-complete])
            web-history @(rf/subscribe [:ui/web-history])
            shown       (if (nil? @draft) (uri/display active-uri) @draft)
            crumbs?     (and ctrl? @hover? active-path)
            editing?    (some? @draft)
            ;; editing an http(s) URL completes from browser history; a path completes from the filesystem
            web?        (uri/http? shown)
            [dir-part base] (if web? ["" shown] (uri/complete-split shown))
            [last-dir _]    (uri/complete-split (or (:input uc) ""))
            ;; filesystem completion data is "fresh" only while it still describes the directory the draft
            ;; points into (the IPC echoes its request as :input)
            fresh?      (and (:input uc) (= dir-part last-dir) (not web?))
            matches     (cond
                          (not editing?) nil
                          web?  (mapv (fn [u] {:name u :path u :dir? false :web? true})
                                      (uri/web-matches web-history shown 12))
                          fresh? (nav/sort-entries (filter #(uri/matches-prefix? (:name %) base) (:entries uc)))
                          :else nil)
            sel         (:selected uc)
            chosen      (cond (empty? matches)                          nil
                              (and (>= sel 0) (< sel (count matches)))  (nth matches sel)
                              :else                                     (first matches))
            ghost       (when (and chosen (uri/matches-prefix? (:name chosen) base))
                          (subs (:name chosen) (count base)))
            ;; the dropdown auto-shows whenever the completion is ambiguous (>1 match) and the user
            ;; hasn't dismissed it (Esc); a single match shows only the inline ghost
            dropdown?   (boolean (and matches (> (count matches) 1) (not (:dismissed? uc))))
            accept!     (fn [m]                ; commit a chosen entry; re-list if it is a directory
                          (let [nd (str dir-part (:name m) (when (:dir? m) (platform/path-sep)))]
                            (reset! draft nd)
                            (rf/dispatch [:uri-complete/typed nd])))]
        [:div.vv-uribar {:on-mouse-enter #(reset! hover? true)
                         :on-mouse-leave #(reset! hover? false)}
         [:button.vv-nav-btn {:disabled (not back?) :title "Back (Alt+←)"
                              :on-click #(rf/dispatch [:history/back])} (icons/icon :back)]
         [:button.vv-nav-btn {:disabled (not fwd?) :title "Forward (Alt+→)"
                              :on-click #(rf/dispatch [:history/forward])} (icons/icon :forward)]
         [:button.vv-nav-btn {:title "Reload (Ctrl+R)" :on-click #(rf/dispatch [:tab/reload])} (icons/icon :reload)]
         (if crumbs?
           [breadcrumb active-uri]
           [:div.vv-uri-field {:class (when (:error? uc) "vv-uri-error")}
            ;; ghost layer (behind the transparent input): a transparent copy of the typed text spaces
            ;; the dimmed suggestion suffix to begin exactly where the cursor sits
            [:div.vv-uri-ghost {:aria-hidden "true"}
             [:span.vv-uri-ghost-typed shown]
             (when (and ghost (seq ghost)) [:span.vv-uri-ghost-suffix ghost])]
            [:input.vv-uri-input
             {:value       shown
              :placeholder "Enter a file path or http(s):// URL"
              :spellCheck  false
              :on-focus    (fn [^js e]
                             (rf/dispatch [:input/set-in-input true])
                             (let [v (uri/display active-uri)]
                               (reset! draft v)
                               (.select (.-target e))
                               (rf/dispatch [:uri-complete/typed v])))
              :on-blur     (fn [_]
                             (rf/dispatch [:input/set-in-input false])
                             (reset! draft nil)
                             (rf/dispatch [:uri-complete/clear]))
              :on-change   (fn [^js e]
                             (let [v (.. e -target -value)]
                               (reset! draft v)
                               (rf/dispatch [:uri-complete/typed v])))
              :on-key-down
              (fn [^js e]
                (let [k       (.-key e)
                      ^js t   (.-target e)
                      at-end? (= (.-selectionStart t) (.-selectionEnd t) (count (.-value t)))]
                  (case k
                    "Tab"
                    (do (.preventDefault e)
                        (when (seq matches)
                          (if (= 1 (count matches))
                            (accept! (first matches))          ; unambiguous → accept outright
                            (let [cp (uri/common-prefix (map :name matches))]
                              (if (> (count cp) (count base))  ; extend to the matches' common prefix…
                                (let [nd (str dir-part cp)]
                                  (reset! draft nd)
                                  (rf/dispatch [:uri-complete/typed nd]))
                                (rf/dispatch [:uri-complete/set {:dismissed? false :selected -1}]))))))  ; …else just list
                    ("ArrowRight" "End")
                    (when (and at-end? chosen ghost (seq ghost))
                      (.preventDefault e)
                      (accept! chosen))
                    "ArrowDown"
                    (when (seq matches)
                      (.preventDefault e)
                      (rf/dispatch [:uri-complete/move 1 (count matches)]))
                    "ArrowUp"
                    (when (seq matches)
                      (.preventDefault e)
                      (rf/dispatch [:uri-complete/move -1 (count matches)]))
                    "Enter"
                    (do (.preventDefault e)
                        (if (and (>= sel 0) chosen)
                          ;; a dropdown row is highlighted → open exactly that target
                          (do (rf/dispatch [:doc/open (:path chosen)])
                              (rf/dispatch [:uri-complete/clear]))
                          ;; else resolve the typed text (exact path / most-likely match / inline error)
                          (rf/dispatch [:uri/navigate (or @draft "")]))
                        (reset! draft nil)
                        (.blur t))
                    "Escape"
                    (if dropdown?
                      (do (.preventDefault e) (rf/dispatch [:uri-complete/set {:dismissed? true :selected -1}]))
                      (do (reset! draft nil) (rf/dispatch [:uri-complete/clear]) (.blur t)))
                    nil)))}]
            (when dropdown?
              [:ul.vv-uri-complete
               (doall
                (map-indexed
                 (fn [i m]
                   ^{:key (:path m)}
                   [:li.vv-uri-opt
                    {:class          (str (when (= i sel) "vv-uri-opt-sel") (when (:dir? m) " vv-uri-opt-dir"))
                     :on-mouse-enter #(rf/dispatch [:uri-complete/set {:selected i}])
                     :on-mouse-down  (fn [^js e] (.preventDefault e) (accept! m))}
                    [:span.vv-uri-opt-icon (cond (:web? m) (icons/icon :globe)
                                                 (:dir? m) (icons/icon :folder)
                                                 :else     (icons/file-icon (:name m)))]
                    [:span.vv-uri-opt-name (:name m)]])
                 matches))])
            (when (:error? uc)
              [:div.vv-uri-errmsg "No matching file or directory"])])
         [ext-toolbar/ext-toolbar]]))))

(defn find-bar []
  (let [{:keys [visible? query count idx]} @(rf/subscribe [:ui/find])]
    (when visible?
      [:div.vv-find
       [:input.vv-find-input
        {:placeholder "Find" :value query :auto-focus true
         :on-focus    #(rf/dispatch [:input/set-in-input true])
         :on-blur     #(rf/dispatch [:input/set-in-input false])
         :on-change   #(rf/dispatch [:find/set-query (.. % -target -value)])
         :on-key-down (fn [^js e]
                        (case (.-key e)
                          "Enter"  (do (.preventDefault e) (.stopPropagation e)
                                       (rf/dispatch [:find/cycle (if (.-shiftKey e) -1 1)]))
                          "Escape" (do (.stopPropagation e) (rf/dispatch [:find/close]))
                          nil))}]
       [:span.vv-find-count (if (pos? count) (str idx "/" count) "0/0")]
       [:button.vv-find-btn {:title "Previous (⇧⏎)" :on-click #(rf/dispatch [:find/cycle -1])} (icons/icon :find-prev)]
       [:button.vv-find-btn {:title "Next (⏎)" :on-click #(rf/dispatch [:find/cycle 1])} (icons/icon :find-next)]
       [:button.vv-find-btn {:title "Close (Esc)" :on-click #(rf/dispatch [:find/close])} (icons/icon :close)]])))

(defn mode-line
  "Shows the modal state + any pending key-sequence (hidden in non-modal insert with no pending)."
  []
  (let [mode    @(rf/subscribe [:input/mode])
        pending @(rf/subscribe [:input/pending])]
    (when (or (not= mode :insert) (seq pending))
      [:div.vv-modeline
       [:span.vv-modeline-mode (str/upper-case (name mode))]
       (when (seq pending) [:span.vv-modeline-seq (str/join " " pending)])])))

(defn status-bar
  "Bottom-left link-hover URI indicator (like a browser's status bar); shown only while hovering a link."
  []
  (when-let [uri @(rf/subscribe [:ui/hover-link])]
    [:div.vv-statusbar uri]))

(defn hints-overlay
  "Vimium link-hint labels positioned over the visible links; only labels still matching what's typed show
   (the typed prefix is highlighted). The capture-phase key listener that drives this is in renderer.core."
  []
  (let [{:keys [active? targets typed]} @(rf/subscribe [:ui/hints])]
    (when active?
      [:div.vv-hint-overlay
       (for [{:keys [label x y]} targets
             :when (str/starts-with? label typed)]
         ^{:key label}
         [:div.vv-hint {:style {:left (str x "px") :top (str y "px")}}
          (when (seq typed) [:span.vv-hint-typed typed])
          [:span.vv-hint-rest (subs label (count typed))]])])))

(defn root []
  [:div.vv-root
   [menubar/menubar]
   [:div.vv-app
    [sidebar/sidebar]
    [:div.vv-pane
     [tabs/tab-strip]
     [uri-bar]
     ;; content + its top-right floating find bar share a positioning context that starts BELOW the chrome,
     ;; so the find bar floats over the document (not over the tab strip / address bar)
     [:div.vv-content-wrap
      [content-view]
      [find-bar]]
     [status-bar]
     [mode-line]]]
   [palette/command-palette]
   [ctx-menu/context-menu]
   [settings-ui/dialog]
   [extensions-ui/dialog]
   [about/dialog]
   (when @(rf/subscribe [:kbedit/open?]) [kbedit/dialog])
   [hints-overlay]])
