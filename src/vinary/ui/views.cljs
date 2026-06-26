(ns vinary.ui.views
  "Renderer views: the app shell = tab strip + content area. The active document's HTML is written into
   a DOM node imperatively via a ref (set once per content change, no VDOM-diffing the document body).
   Empty (no tabs) shows the Vinary Tree shield watermark."
  (:require [reagent.core :as r]
            [re-frame.core :as rf]
            [clojure.string :as str]
            [vinary.app.uri :as uri]
            [vinary.app.link :as link]
            [vinary.renderer.scroll :as scroll]
            [vinary.ui.tabs :as tabs]
            [vinary.ui.sidebar :as sidebar]
            [vinary.ui.palette :as palette]
            [vinary.ui.menubar :as menubar]
            [vinary.ui.context-menu :as ctx-menu]
            [vinary.ui.preview-context :as preview-ctx]
            [vinary.ui.preview-navigation :as preview-nav]
            [vinary.ui.settings :as settings-ui]
            [vinary.ui.about :as about]
            [vinary.ui.keybindings-editor :as kbedit]
            [vinary.renderer.toc :as toc]
            [vinary.renderer.figures :as figures]
            [vinary.renderer.media :as media]
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

(defn markdown-body
  "A .markdown-body whose innerHTML tracks the HTML passed in (content-view holds the subscription).
   Intercepts link clicks (→ in-pane navigation, never replacing the window), shows the hovered link's URI
   in the status bar, font-matches embedded SVG figures, restores the per-history scroll position, and
   opens a themed context menu on right-click. Middle-click / Ctrl+click open a link in a new tab."
  [_html _source _path]
  (let [node (atom nil)
        source* (atom nil)
        path* (atom nil)
        raf  (atom false)
        last-link (atom nil)
        on-resize (fn []
                    (when-not @raf
                      (reset! raf true)
                      (js/requestAnimationFrame
                       (fn [] (reset! raf false) (figures/scale-figures! @node)))))
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
                                (set-inner! @node html))
                              (figures/scale-figures! @node)
                              (scroll/apply! @node)
                              (.addEventListener js/window "resize" on-resize)
                              (.addEventListener @node "click" on-click)
                              (.addEventListener @node "auxclick" on-aux)
                              (.addEventListener @node "mouseover" on-over)
                              (.addEventListener @node "mouseleave" on-leave)
                              (.addEventListener @node "contextmenu" on-ctx))
      :component-did-update (fn [this]
                              (let [[_ html source path] (r/argv this)]
                                (reset! source* source)
                                (reset! path* path)
                                (set-inner! @node html))
                              (figures/scale-figures! @node)
                              (scroll/apply! @node))
      :component-will-unmount (fn [_]
                                (.removeEventListener js/window "resize" on-resize)
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

(defn pdf-host
  "A placeholder div the size/position of which drives a main-owned native PDF view (positioned over
   it). On mount/update we send show+bounds; a ResizeObserver + window resize keep the bounds synced;
   on unmount we hide the native view. (Imperative DOM, like markdown-body — the native view is opaque
   and sits on top of this div.)"
  [_path]
  (let [node (atom nil)
        obs  (atom nil)
        vv   (fn [] (.-vv js/window))
        show!   (fn [path] (when-let [^js v (vv)] (when (and (.-pdfShow v) @node) (.pdfShow v path (pdf-rect @node)))))
        bounds! (fn [] (when-let [^js v (vv)] (when (and (.-pdfBounds v) @node) (.pdfBounds v (pdf-rect @node)))))]
    (r/create-class
     {:display-name "vv-pdf-host"
      :component-did-mount
      (fn [this]
        (show! (second (r/argv this)))
        (when (exists? js/ResizeObserver)
          (let [o (js/ResizeObserver. (fn [_] (bounds!)))] (.observe o @node) (reset! obs o)))
        (.addEventListener js/window "resize" bounds!)
        (scroll/apply! @node))
      :component-did-update   (fn [this] (show! (second (r/argv this))))
      :component-will-unmount (fn [_]
                                (when-let [^js v (vv)] (when (.-pdfHide v) (.pdfHide v)))
                                (when @obs (.disconnect ^js @obs))
                                (.removeEventListener js/window "resize" bounds!))
      :reagent-render         (fn [_path] [:div.vv-pdf-host {:ref (fn [el] (reset! node el))}])})))

(defn web-host
  "A placeholder div over which the main-owned HTTP web view is positioned (mirrors pdf-host). On
   mount/update we send show+url+bounds; a ResizeObserver + window resize keep bounds synced; on unmount
   we hide the web view. The remote page renders inside the opaque native view on top of this div."
  [_url]
  (let [node (atom nil)
        obs  (atom nil)
        vv   (fn [] (.-vv js/window))
        show!   (fn [url] (when-let [^js v (vv)] (when (and (.-httpShow v) @node) (.httpShow v url (pdf-rect @node)))))
        bounds! (fn [] (when-let [^js v (vv)] (when (and (.-httpBounds v) @node) (.httpBounds v (pdf-rect @node)))))]
    (r/create-class
     {:display-name "vv-web-host"
      :component-did-mount
      (fn [this]
        (show! (second (r/argv this)))
        (when (exists? js/ResizeObserver)
          (let [o (js/ResizeObserver. (fn [_] (bounds!)))] (.observe o @node) (reset! obs o)))
        (.addEventListener js/window "resize" bounds!)
        (scroll/apply! @node))
      :component-did-update   (fn [this] (show! (second (r/argv this))))
      :component-will-unmount (fn [_]
                                (when-let [^js v (vv)] (when (.-httpHide v) (.httpHide v)))
                                (when @obs (.disconnect ^js @obs))
                                (.removeEventListener js/window "resize" bounds!))
      :reagent-render         (fn [_url] [:div.vv-web-host {:ref (fn [el] (reset! node el))}])})))

(defn source-view
  "A read-only CodeMirror 6 view of a source file, highlighted via web-tree-sitter when a grammar is
   registered for its extension. Re-created on live-refresh (text change)."
  [_text _path]
  (let [node (atom nil) view (atom nil)]
    (letfn [(build! [this]
              (let [[_ text path] (r/argv this)]
                (reset! view (syntax/create-source-view @node text (syntax/grammar-for path)))))
            (destroy! [] (when @view (.destroy ^js @view) (reset! view nil)))]
      (r/create-class
       {:display-name           "vv-source-view"
        :component-did-mount     (fn [this] (build! this) (scroll/apply! @node))
        :component-did-update    (fn [this] (destroy!) (build! this) (scroll/apply! @node))
        :component-will-unmount  (fn [_] (destroy!))
        :reagent-render          (fn [_text _path] [:div.vv-source {:ref (fn [el] (reset! node el))}])}))))

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

(defn watermark []
  [:div.vv-watermark
   [:img.vv-watermark-logo {:src "assets/vinary-tree-logo.svg" :alt ""}]])

(defn content-view
  "Renderer registry (Strategy): the active tab's content is shown by its URI scheme (http → web view)
   or, for a local file, its :doc/kind."
  []
  (let [doc  @(rf/subscribe [:doc/active])
        tabs @(rf/subscribe [:ui/tabs])
        uri  @(rf/subscribe [:ui/active-uri])
        vs?  @(rf/subscribe [:ui/active-view-source?])]
    [:div.vv-content
     {:on-scroll (fn [^js e] (toc/spy! (.-currentTarget e)))}
     (cond
       (empty? tabs)               [watermark]
       (nil? uri)                  [:div.vv-empty "New Tab"]
       (uri/http? uri)             [web-host uri]
       (:doc/error doc)            [:div.vv-error "Error: " (:doc/error doc)]
       (= "pdf" (:doc/kind doc))   [pdf-host (:doc/path doc)]
       (= "image" (:doc/kind doc)) ^{:key (str (:doc/path doc) ":" (:doc/stamp doc))}
                                   [image-view (:doc/path doc) (:doc/stamp doc)]
       (= "diagram" (:doc/kind doc)) [:div.vv-diagram [markdown-body (:doc/html doc) (:doc/text doc) (:doc/path doc)]]
       (= "source" (:doc/kind doc)) ^{:key (:doc/path doc)} [source-view (:doc/text doc) (:doc/path doc)]
       ;; "View Source" on a markdown doc → show its raw source in the pane (not replacing the window)
       (and vs? (= "markdown" (:doc/kind doc)) (:doc/text doc))
       ^{:key (str "src:" (:doc/path doc))} [source-view (:doc/text doc) (:doc/path doc)]
       (:doc/html doc)             [markdown-body (:doc/html doc) (:doc/text doc) (:doc/path doc)]
       :else                       [:div.vv-empty "Rendering…"])]))

(defn uri-bar
  "Browser-style nav row: back / forward / reload + the address bar (the active tab's URI). The input
   shows the active tab's URI; typing edits a local draft; Enter navigates; blur/Esc reverts. (Per-tab
   history: back/forward act on the active tab — see vinary.app.nav. Theme lives in the Settings menu.)"
  []
  (let [draft (r/atom nil)]
    (fn []
      (let [active-uri @(rf/subscribe [:ui/active-uri])
            back?      @(rf/subscribe [:history/can-back?])
            fwd?       @(rf/subscribe [:history/can-forward?])
            shown      (if (nil? @draft) (uri/display active-uri) @draft)]
        [:div.vv-uribar
         [:button.vv-nav-btn {:disabled (not back?) :title "Back (Alt+←)"
                              :on-click #(rf/dispatch [:history/back])} "←"]
         [:button.vv-nav-btn {:disabled (not fwd?) :title "Forward (Alt+→)"
                              :on-click #(rf/dispatch [:history/forward])} "→"]
         [:button.vv-nav-btn {:title "Reload (Ctrl+R)" :on-click #(rf/dispatch [:tab/reload])} "⟳"]
         [:input.vv-uri-input
          {:value       shown
           :placeholder "Enter a file path or http(s):// URL"
           :spellCheck  false
           :on-focus    (fn [^js e] (rf/dispatch [:input/set-in-input true]) (.select (.-target e)))
           :on-blur     (fn [_] (rf/dispatch [:input/set-in-input false]) (reset! draft nil))
           :on-change   #(reset! draft (.. % -target -value))
           :on-key-down (fn [^js e]
                          (case (.-key e)
                            "Enter"  (do (.preventDefault e)
                                         (rf/dispatch [:uri/navigate (or @draft "")])
                                         (reset! draft nil)
                                         (.blur (.-target e)))
                            "Escape" (do (reset! draft nil) (.blur (.-target e)))
                            nil))}]]))))

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
       [:button.vv-find-btn {:title "Previous (⇧⏎)" :on-click #(rf/dispatch [:find/cycle -1])} "↑"]
       [:button.vv-find-btn {:title "Next (⏎)" :on-click #(rf/dispatch [:find/cycle 1])} "↓"]
       [:button.vv-find-btn {:title "Close (Esc)" :on-click #(rf/dispatch [:find/close])} "×"]])))

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
   [about/dialog]
   (when @(rf/subscribe [:kbedit/open?]) [kbedit/dialog])
   [hints-overlay]])
