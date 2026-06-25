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
            [vinary.ui.settings :as settings-ui]
            [vinary.ui.about :as about]
            [vinary.ui.keybindings-editor :as kbedit]
            [vinary.renderer.toc :as toc]
            [vinary.renderer.figures :as figures]
            [vinary.renderer.syntax :as syntax]))

(defn- set-inner! [^js node html]
  (when node (set! (.-innerHTML node) (or html ""))))

(defn- link-display [{:keys [kind path]}]
  (case kind :anchor (str "#" path) :http path (:file :dir) path nil))

(defn markdown-body
  "A .markdown-body whose innerHTML tracks the HTML passed in (content-view holds the subscription).
   Intercepts link clicks (→ in-pane navigation, never replacing the window), shows the hovered link's URI
   in the status bar, font-matches embedded SVG figures, restores the per-history scroll position, and
   opens a themed context menu on right-click. Middle-click / Ctrl+click open a link in a new tab."
  [_html]
  (let [node (atom nil)
        raf  (atom false)
        last-link (atom nil)
        on-resize (fn []
                    (when-not @raf
                      (reset! raf true)
                      (js/requestAnimationFrame
                       (fn [] (reset! raf false) (figures/scale-figures! @node)))))
        follow (fn [^js a new-tab? ^js e]
                 (when-let [target (link/classify (link/target-for-anchor a) (.-textContent a))]
                   (.preventDefault e)
                   (case (:kind target)
                     :anchor       (when-let [^js el (.getElementById js/document (:path target))]
                                     (.scrollIntoView el #js {:behavior "smooth" :block "start"}))
                     (:http :file) (rf/dispatch [(if new-tab? :doc/open-new :doc/open) (:path target)])
                     :dir          (rf/dispatch [:shell/open-path (:path target)])
                     nil)))
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
                   (when-let [^js a (.closest (.-target e) "a")]
                     (when-let [target (link/classify (link/target-for-anchor a) (.-textContent a))]
                       (when (#{:file :dir :http} (:kind target))
                         (.preventDefault e)
                         (.stopPropagation e)   ; don't also trigger the content-pane's doc menu
                         (rf/dispatch [:context-menu/show {:x (.-clientX e) :y (.-clientY e) :target target}])))))]
    (r/create-class
     {:display-name "vv-markdown-body"
      :component-did-mount  (fn [this]
                              (set-inner! @node (second (r/argv this)))
                              (figures/scale-figures! @node)
                              (scroll/apply! @node)
                              (.addEventListener js/window "resize" on-resize)
                              (.addEventListener @node "click" on-click)
                              (.addEventListener @node "auxclick" on-aux)
                              (.addEventListener @node "mouseover" on-over)
                              (.addEventListener @node "mouseleave" on-leave)
                              (.addEventListener @node "contextmenu" on-ctx))
      :component-did-update (fn [this]
                              (set-inner! @node (second (r/argv this)))
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
      :reagent-render       (fn [_html] [:div.markdown-body {:ref (fn [el] (reset! node el))}])})))

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
  [_path]
  (let [node (atom nil)]
    (r/create-class
     {:display-name "vv-image-view"
      :component-did-mount  (fn [_] (scroll/apply! @node))
      :component-did-update (fn [_] (scroll/apply! @node))
      :reagent-render       (fn [path] [:div.vv-image-view {:ref (fn [el] (reset! node el))}
                                        [:img {:src (str "file://" path) :alt path}]])})))

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
     {:on-scroll (fn [^js e] (toc/spy! (.-currentTarget e)))
      ;; right-click the markdown content (not a link) → a doc context menu (View Source / Copy)
      :on-context-menu (fn [^js e]
                         (when (and (= "markdown" (:doc/kind doc)) (not (.closest (.-target e) "a")))
                           (.preventDefault e)
                           (rf/dispatch [:context-menu/show
                                         {:x (.-clientX e) :y (.-clientY e)
                                          :target {:kind :doc :path (:doc/path doc)}}])))}
     (cond
       (empty? tabs)               [watermark]
       (uri/http? uri)             [web-host uri]
       (:doc/error doc)            [:div.vv-error "Error: " (:doc/error doc)]
       (= "pdf" (:doc/kind doc))   [pdf-host (:doc/path doc)]
       (= "image" (:doc/kind doc)) ^{:key (:doc/path doc)} [image-view (:doc/path doc)]
       (= "diagram" (:doc/kind doc)) [:div.vv-diagram [markdown-body (:doc/html doc)]]
       (= "source" (:doc/kind doc)) ^{:key (:doc/path doc)} [source-view (:doc/text doc) (:doc/path doc)]
       ;; "View Source" on a markdown doc → show its raw source in the pane (not replacing the window)
       (and vs? (= "markdown" (:doc/kind doc)) (:doc/text doc))
       ^{:key (str "src:" (:doc/path doc))} [source-view (:doc/text doc) (:doc/path doc)]
       (:doc/html doc)             [markdown-body (:doc/html doc)]
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
     [content-view]
     [find-bar]
     [status-bar]
     [mode-line]]]
   [palette/command-palette]
   [ctx-menu/context-menu]
   [settings-ui/dialog]
   [about/dialog]
   (when @(rf/subscribe [:kbedit/open?]) [kbedit/dialog])
   [hints-overlay]])
