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
            [vinary.renderer.math :as math]
            [vinary.renderer.markdown :as md]
            [vinary.ir.backend.html :as ir-html]
            [vinary.renderer.scroll :as scroll]
            [vinary.renderer.pdf :as pdf]
            [vinary.renderer.pdf-cache :as pdf-cache]
            [vinary.ui.tabs :as tabs]
            [vinary.ui.icons :as icons]
            [vinary.ui.platform :as platform]
            [vinary.ui.sidebar :as sidebar]
            [vinary.ui.palette :as palette]
            [vinary.ui.menubar :as menubar]
            [vinary.ui.zoombar :as zoombar]
            [vinary.ui.context-menu :as ctx-menu]
            [vinary.ui.preview-context :as preview-ctx]
            [vinary.ui.preview-navigation :as preview-nav]
            [vinary.ui.settings :as settings-ui]
            [vinary.ui.about :as about]
            [vinary.ui.ext-toolbar :as ext-toolbar]
            [vinary.ui.extensions :as extensions-ui]
            [vinary.ui.passwords :as passwords-ui]
            [vinary.ui.keybindings-editor :as kbedit]
            [vinary.renderer.toc :as toc]
            [vinary.stream.flag :as stream-flag]
            [vinary.stream.scheduler :as stream-scheduler]
            [vinary.renderer.media :as media]
            [vinary.renderer.mermaid :as mermaid]
            [vinary.renderer.syntax :as syntax]
            [vinary.renderer.source-nav :as source-nav]
            [vinary.renderer.virtual-layout :as virtual-layout]))

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

(defn- source-lc
  "The {:line :column} source position for a right-clicked preview `node`, or nil when the node has no
   data-vv-source-* ancestor. The load-bearing map behind BOTH 'Copy source location' (formatted to a string)
   and the 'Go to source' jump (which needs the :line integer)."
  [source node offset text]
  (when-let [el (and node (source-element node))]
    (let [{:keys [kind start-line start-column start-offset end-offset]} (source-span el)
          exact-text? (= "text" kind)
          source-offset (cond
                          (and exact-text? (number? start-offset) (number? offset))
                          (+ start-offset offset)

                          (and (seq text) (number? start-offset))
                          (preview-ctx/best-source-offset source start-offset end-offset text start-offset)

                          :else start-offset)]
      (or (preview-ctx/offset->line-column source source-offset)
          {:line start-line :column start-column}))))

(defn- source-location [source path node offset text]
  (preview-ctx/location-string path (source-lc source node offset text)))

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
       :source-location (source-location source path a nil nil)
       :source-line (:line (source-lc source a nil nil))})))

(defn- source-location-for-pos [path view pos]
  (some->> (syntax/line-info-at view pos)
           (preview-ctx/location-string path)))

(defn- source-term-at [view pos]
  (when-let [{:keys [text line-from]} (syntax/line-info-at view pos)]
    (when-let [{term-text :text start :start} (preview-ctx/term-at text (- pos line-from))]
      {:text term-text
       :pos (+ line-from start)})))

(defn- attach-content-interactions!
  "Wire the shared preview interactions onto a content `node`: in-pane link navigation (click / middle-click /
   Ctrl+click, always fail-closed so an unrecognized href can never navigate the privileged frame), the
   status-bar link-hover indicator, and the themed right-click context menu (link target, else selection/term
   plus any math). Shared by the batch `markdown-body` and the streaming `ir-stream-body` so a streamed
   document behaves identically. `source*`/`path*` are atoms the caller keeps pointed at the current doc;
   `last-link` is a scratch atom for hover de-duping. Returns a 0-arg detach fn."
  [^js node source* path* last-link]
  (letfn [(follow [^js a new-tab? ^js e]
            ;; Fail closed: prevent default navigation for EVERY in-content <a> click, so a link the app
            ;; doesn't recognize (or a javascript:/data: one the sanitizer somehow missed) can never navigate
            ;; the privileged app frame. Only recognized safe targets then dispatch an open event.
            (.preventDefault e)
            (when-let [target (link/classify (link/target-for-anchor a) (.-textContent a))]
              (when-let [event (preview-nav/open-event target new-tab?)]
                (rf/dispatch event))))]
    (let [on-click (fn [^js e] (when-let [^js a (.closest (.-target e) "a")] (follow a (.-ctrlKey e) e)))
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
                           ^js body node
                           ^js a (.closest (.-target e) "a")
                           target (or (when a (preview-link-target a source path))
                                      (let [{:keys [text node offset]} (selection-or-term body e)
                                            source-node (or node (.-target e))
                                            ^js math-el (.closest (.-target e) ".vv-math-inline, .vv-math-display")]
                                        {:kind :preview-body
                                         :text (or text "")
                                         :math-tex (when math-el
                                                     (math/delimit-tex (.contains (.-classList math-el) "vv-math-display")
                                                                       (.getAttribute math-el "data-tex")))
                                         :source-location (source-location source path source-node offset text)
                                         :source-line (:line (source-lc source source-node offset text))}))]
                       (.preventDefault e)
                       (.stopPropagation e)
                       (rf/dispatch [:context-menu/show {:x (.-clientX e) :y (.-clientY e) :target target}])))]
      (.addEventListener node "click" on-click)
      (.addEventListener node "auxclick" on-aux)
      (.addEventListener node "mouseover" on-over)
      (.addEventListener node "mouseleave" on-leave)
      (.addEventListener node "contextmenu" on-ctx)
      (fn detach! []
        (.removeEventListener node "click" on-click)
        (.removeEventListener node "auxclick" on-aux)
        (.removeEventListener node "mouseover" on-over)
        (.removeEventListener node "mouseleave" on-leave)
        (.removeEventListener node "contextmenu" on-ctx)
        (rf/dispatch [:ui/hover-link nil])))))

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
        detach* (atom nil)
        refresh-toc! (fn []
                       (when-let [^js content (some-> @node (.closest ".vv-content"))]
                         ;; feed the generalized spy this doc's :doc/toc ids (== the rendered heading ids;
                         ;; collect-metadata! runs after rehype-slug) so spy ids match the sidebar exactly.
                         (toc/refresh! content (mapv :id @(rf/subscribe [:doc/toc])))))
        after-render! (fn [token f]
                        ;; Figures / MathJax / Mermaid are pre-sized in the HTML string (apply-posts →
                        ;; scale-figures-html etc.), so layout is FINAL at set-inner! time — no post-insert
                        ;; re-scale. We only re-measure the scroll-spy and run the continuation on the next
                        ;; frame (one frame lets the browser flush layout so a scroll restore targets the
                        ;; final document height).
                        (js/requestAnimationFrame
                         (fn [_]
                           (when (and @node (or (nil? token) (= token @render-token)))
                             (refresh-toc!)
                             (when f (f))
                             ;; consume a pending source→preview jump (deferred across the toggle remount); a
                             ;; jump is not a nav, so scroll/apply! (f) no-ops and the jump wins
                             (when-let [l (source-nav/take-preview-line!)]
                               (source-nav/scroll-preview-to-line! l))))))
        render-html! (fn [html]
                       (let [token (swap! render-token inc)]
                         (reset! html* html)
                         (set-inner! @node html)
                         (after-render! token #(scroll/apply! @node))))
        on-resize (fn []
                    (when (and @node (not @raf))
                      (reset! raf true)
                      (js/requestAnimationFrame
                       (fn []
                         (reset! raf false)
                         (when @node
                           ;; Figure widths are resize-independent now (font-matched, CSS-capped), so only the
                           ;; scroll-spy offsets need re-measuring when prose reflows at a new column width.
                           (refresh-toc!))))))
        observe-resize! (fn []
                          (if (exists? js/ResizeObserver)
                            (let [o (js/ResizeObserver. (fn [_] (on-resize)))]
                              (.observe o @node)
                              (when-let [^js content (.closest @node ".vv-content")]
                                (.observe o content))
                              (reset! resize-observer o))
                            (.addEventListener js/window "resize" on-resize)))]
    (r/create-class
     {:display-name "vv-markdown-body"
      :component-did-mount  (fn [this]
                              (let [[_ html source path] (r/argv this)]
                                (reset! source* source)
                                (reset! path* path)
                                (render-html! html))
                              (observe-resize!)
                              (reset! detach* (attach-content-interactions! @node source* path* last-link)))
      :component-did-update (fn [this]
                              (let [[_ html source path] (r/argv this)
                                    doc-changed? (not= path @path*)]
                                (reset! source* source)
                                (reset! path* path)
                                ;; a new doc with byte-identical HTML doesn't re-render (html unchanged) but the
                                ;; scroll-spy's doc-key changed → re-measure so the highlight follows the new doc.
                                (cond
                                  (not= html @html*) (render-html! html)
                                  doc-changed?       (refresh-toc!))))
      :component-will-unmount (fn [_]
                                (.removeEventListener js/window "resize" on-resize)
                                (when @resize-observer
                                  (let [^js o @resize-observer]
                                    (.disconnect o))
                                  (reset! resize-observer nil))
                                (when @detach* (@detach*) (reset! detach* nil)))
      :reagent-render       (fn [_html _source _path] [:div.markdown-body {:ref (fn [el] (reset! node el))}])})))

;; Scroll re-anchor across a streamed doc's remount (live-refresh bumps :doc/stamp → the [path stamp]-keyed
;; ir-stream-body remounts and re-streams from the top; a tab-switch away/back likewise re-streams). We save the
;; .vv-content scrollTop on unmount (keyed by path) and restore it as the re-stream grows tall enough — so a
;; live log/markdown edit doesn't jump the reader back to the top.
(defonce ^:private stream-scroll (atom {}))                    ; :doc/path → last scrollTop

(defn ir-stream-body
  "Streaming counterpart of markdown-body: a .markdown-body whose content is APPENDED incrementally by the
   stream scheduler (bounded memory) instead of written whole. Shares markdown-body's link + context-menu
   interactions and the .vv-content scroll-spy contract, so find / Contents / copy work on the growing
   document. Mounting starts a stream of `path` (of `kind`); unmounting tears it down. The render is never
   snapshotted, so a re-mount (tab-switch back / live-refresh) simply re-streams from the top — keeping the
   working set bounded for arbitrarily large files. A thin top progress bar shows completion while loading.
   `text`/`stamp` feed the progressive (markdown) engine, whose source is the in-memory `:doc/text`."
  [_path _kind _text _stamp]
  (let [node        (atom nil)
        source*     (atom nil)                                 ; logs carry no source-map; kept for the shared ctx menu
        path*       (atom nil)
        last-link   (atom nil)
        detach*     (atom nil)
        ctrl*       (atom nil)
        ensurer*    (atom nil)                                 ; find-materialize hook (drains the stream before search)
        restore-to* (atom nil)                                 ; scrollTop to re-anchor after a live-refresh remount
        last-toc-n  (atom 0)
        refresh-raf (atom false)
        spacer      (atom nil)                                 ; trailing SIBLING spacer (outside .markdown-body)
        stream-ro   (atom nil)                                 ; ResizeObserver on the body → keeps the spacer honest
        ;; Pre-estimated trailing spacer: makes the .vv-content scrollHeight ≈ the WHOLE document from the first
        ;; batch, so the scrollbar reflects true position + size (no growing/jittering thumb; scroll-to-end works
        ;; mid-stream). estimatedTotal = renderedH / progress — ≈ invariant across batches (both grow together),
        ;; so the height stays STABLE and only refines. The spacer is a sibling of .markdown-body, so the
        ;; byte-parity innerHTML capture is untouched.
        size-spacer! (fn []
                       (when-let [^js sp @spacer]
                         (when-let [^js body @node]
                           (let [rendered  (.-offsetHeight body)
                                 progress  @(rf/subscribe [:doc/stream-progress])
                                 estimated (virtual-layout/extrapolate-total rendered progress rendered)]
                             (set! (.. sp -style -height)
                                   (str (js/Math.round (virtual-layout/spacer-height estimated rendered)) "px"))))))
        ;; As blocks append they already carry final figure/math/mermaid geometry (pre-sized in apply-posts), so a
        ;; burst only needs the scroll-spy re-measured (block commits move heading offsets) + the spacer resized.
        ;; rAF-coalesce bursts of appends into one refresh.
        refresh-view! (fn []
                        (when-not @refresh-raf
                          (reset! refresh-raf true)
                          (js/requestAnimationFrame
                           (fn []
                             (reset! refresh-raf false)
                             (when @node
                               (size-spacer!)
                               (when-let [^js content (some-> @node (.closest ".vv-content"))]
                                 (toc/refresh! content (mapv :id @(rf/subscribe [:doc/toc]))))))))) ]
    (r/create-class
     {:display-name "vv-ir-stream-body"
      :component-did-mount  (fn [this]
                              (let [[_ path kind text stamp] (r/argv this)
                                    ;; progressive kinds inject a block-provider (full-document render → parity);
                                    ;; log/text leave it nil and take the bounded transport engine instead.
                                    blocks-fn (case kind
                                                "markdown"   (fn [] (md/stream-blocks text (md/dir-of path) stamp))
                                                "org"        (fn [] (md/org-stream-blocks text (md/dir-of path) stamp))
                                                "latex"      (fn [] (md/latex-stream-blocks text (md/dir-of path) stamp))
                                                "pdf-reflow" (fn [] (pdf/reflow-blocks! path))
                                                nil)]
                                (reset! path* path)
                                (reset! detach* (attach-content-interactions! @node source* path* last-link))
                                ;; keep the pre-estimated spacer honest as the body height settles (late web
                                ;; fonts, mermaid/syntax post-passes, pre-sized figures) — the robust,
                                ;; engine-agnostic height signal
                                (when (exists? js/ResizeObserver)
                                  (let [ro (js/ResizeObserver. (fn [_] (size-spacer!)))]
                                    (.observe ro @node)
                                    (reset! stream-ro ro)))
                                (reset! ctrl* (stream-scheduler/start! @node path kind
                                                                       (cond-> {:text text :stamp stamp}
                                                                         blocks-fn (assoc :blocks-fn blocks-fn))))
                                ;; in-page find materializes the whole stream first (the PDF ensure-all-text! analog):
                                ;; only one content view is active, so a single global ensurer never conflicts
                                (let [ens (fn [] (stream-scheduler/materialize! @ctrl*))]
                                  (reset! ensurer* ens)
                                  (pdf-cache/set-ensurer! ens))
                                ;; consume any saved scroll for this path (from a prior remount) → re-anchor after
                                ;; the whole doc has settled (drain done + appends landed), so the target height
                                ;; exists; the browser clamps if the doc came back shorter
                                (reset! restore-to* (get @stream-scroll path))
                                (swap! stream-scroll dissoc path)
                                (if-let [jump-line (source-nav/take-preview-line!)]
                                  ;; a source→preview jump into a streamed doc: scroll to the target line once the
                                  ;; whole doc has drained (its block may be among the last committed); the jump
                                  ;; wins over any saved scroll restore
                                  (-> (stream-scheduler/when-settled @ctrl*)
                                      (.then (fn [_] (source-nav/scroll-preview-to-line! jump-line))))
                                  (when @restore-to*
                                    (let [set-scroll! (fn [] (when-let [^js content (and @node (some-> @node (.closest ".vv-content")))]
                                                               (set! (.-scrollTop content) @restore-to*)))]
                                      (-> (stream-scheduler/when-settled @ctrl*)
                                          ;; re-apply across a few frames: post-passes (syntax highlight) can grow the
                                          ;; layout height AFTER the appends settle, so one early set would clamp short
                                          (.then (fn [_]
                                                   (set-scroll!)
                                                   (js/requestAnimationFrame
                                                    (fn [_] (set-scroll!)
                                                      (js/requestAnimationFrame (fn [_] (set-scroll!))))))))))) ))
      :component-did-update (fn [this]
                              ;; re-measure on every batch that reports progress (ALL streaming kinds) OR when the
                              ;; outline grew (logs' incremental Contents), so the scroll-spy AND the pre-estimated
                              ;; spacer track the growing document. The rAF guard coalesces bursts.
                              (let [n     (count @(rf/subscribe [:doc/toc]))
                                    grew? (not= n @last-toc-n)
                                    p     @(rf/subscribe [:doc/stream-progress])]
                                (reset! last-toc-n n)
                                (when (or grew? (some? p))
                                  (refresh-view!))))
      :component-will-unmount (fn [_]
                                ;; save scroll so a live-refresh / tab-switch remount re-anchors instead of jumping to top
                                (when-let [^js content (some-> @node (.closest ".vv-content"))]
                                  (when (pos? (.-scrollTop content)) (swap! stream-scroll assoc @path* (.-scrollTop content))))
                                (when @stream-ro (.disconnect ^js @stream-ro) (reset! stream-ro nil))
                                (when @ensurer* (pdf-cache/clear-ensurer! @ensurer*) (reset! ensurer* nil))
                                (when @ctrl* (stream-scheduler/stop! @ctrl*) (reset! ctrl* nil))
                                (when @detach* (@detach*) (reset! detach* nil)))
      :reagent-render (fn [_path _kind _text _stamp]
                        ;; deref the streamed outline + progress so the component re-renders as the stream
                        ;; grows → did-update re-measures the scroll-spy for the newly-appended records
                        @(rf/subscribe [:doc/toc])
                        (let [p        @(rf/subscribe [:doc/stream-progress])
                              loading? (and p (< p 1))]
                          [:<>
                           [:div.vv-stream-progress {:class (when-not loading? "vv-stream-progress-done")}
                            [:div.vv-stream-progress-bar {:style {:width (str (* 100 (or p 0)) "%")}}]]
                           ;; .vv-streamed enables windowed rendering: content-visibility skips layout/paint of
                           ;; off-screen top-level blocks (bounded render on huge docs) while the nodes stay in
                           ;; the DOM, so find / scroll-spy / selection keep working on the whole document
                           [:div.markdown-body.vv-streamed {:ref (fn [el] (reset! node el))}]
                           ;; trailing spacer (SIBLING of .markdown-body — never a child, so the byte-parity
                           ;; innerHTML capture is untouched): height set imperatively to pad the rendered body up
                           ;; to the estimated whole-document height, so the scrollbar matches the whole doc
                           [:div.vv-stream-spacer {:ref (fn [el] (reset! spacer el))}]]))})))

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

   Because the native view always paints above the DOM, NO DOM overlay can draw over it. So for ANY overlay
   (menu, context-menu, dialog, or palette) we FREEZE the page: instantly swap in a PRE-CACHED raster pushed
   from main (vv:http-snapshot-ready) and hide the native view — the overlay then floats over the still,
   present page (dimmed under a dialog's backdrop). No capture on the open path → instant, no flash, never
   blank. The live view is restored (and the snapshot dropped) once every overlay closes."
  [_url]
  (let [node     (atom nil)
        obs      (atom nil)
        snap     (r/atom nil)         ; the frozen-page <img> shown while an overlay is open (else nil)
        pre-snap (atom nil)           ; latest snapshot pushed from main — swapped into `snap` instantly
        unsub    (atom nil)           ; onHttpSnapshotReady unsubscribe
        last-url (atom nil)
        overlay? (atom false)
        owner-id (atom nil)          ; the tab id that owns the web view (this host's tab; captured in render)
        vv       (fn [] (.-vv js/window))
        show!    (fn [url] (when-let [^js v (vv)] (when (and (.-httpShow v) @node) (.httpShow v url (pdf-rect @node) @owner-id))))
        hide!    (fn [] (when-let [^js v (vv)] (when (.-httpHide v) (.httpHide v))))
        bounds!  (fn [] (when-let [^js v (vv)] (when (and (.-httpBounds v) @node) (.httpBounds v (pdf-rect @node)))))
        ;; Hold the native view up until the snapshot <img> is actually DECODED, then hide it — otherwise the
        ;; view hides a frame before the DOM raster is ready to paint (the <img> decode is async), the exact
        ;; one-frame blank "blink" reported. One rAF lets reagent render the <img>; img.decode() resolves when
        ;; it can paint (instant when pre-decoded below); a final rAF lands the paint; then hide. The native
        ;; view paints above the img, so the img is invisible until the hide reveals it → seamless. Guarded by
        ;; @overlay? so a rapid open→close can't hide a view the close path just re-showed.
        hide-after-paint! (fn []
                            (js/requestAnimationFrame
                             (fn []
                               (let [^js img (some-> @node (.querySelector "img.vv-web-snap"))]
                                 (if (and img (.-decode img))
                                   (-> (.decode img)
                                       (.then  (fn [] (js/requestAnimationFrame (fn [] (when @overlay? (hide!))))))
                                       (.catch (fn [] (when @overlay? (hide!)))))
                                   (when @overlay? (hide!)))))))
        freeze!  (fn []
                   ;; instant path: a pushed snapshot is already cached → paint it, then hide the view.
                   (if-let [data @pre-snap]
                     (do (reset! snap data) (hide-after-paint!))
                     ;; cold start (no push yet): pull main's cache via invoke, then hide (only if still open).
                     (if-let [^js v (vv)]
                       (if (.-httpSnapshot v)
                         (-> (.httpSnapshot v)
                             (.then (fn [data]
                                      (when @overlay?
                                        (if data (do (reset! snap data) (hide-after-paint!)) (hide!))))))
                         (hide!))
                       (hide!))))
        sync!    (fn [this]
                   (let [url (second (r/argv this))]
                     ;; navigation → drop a stale snapshot so an overlay can't freeze the previous page
                     (when (not= url @last-url) (reset! last-url url) (reset! pre-snap nil) (reset! snap nil))
                     (if @overlay?
                       (when-not @snap (freeze!))
                       (do (show! url) (when @snap (reset! snap nil))))))]
    (r/create-class
     {:display-name "vv-web-host"
      :component-did-mount
      (fn [this]
        (when-let [^js v (vv)]
          (when (.-onHttpSnapshotReady v)
            (reset! unsub (.onHttpSnapshotReady
                           v (fn [data]
                               (reset! pre-snap data)
                               ;; pre-decode the pushed raster off-DOM so the frozen <img> (same data-URL)
                               ;; paints from the image cache instantly → decode-before-hide resolves at once.
                               (let [^js im (js/Image.)]
                                 (set! (.-src im) data)
                                 (some-> (.decode im) (.catch (fn [_] nil)))))))))
        (sync! this)
        (when (exists? js/ResizeObserver)
          (let [o (js/ResizeObserver. (fn [_] (bounds!)))] (.observe o @node) (reset! obs o)))
        (.addEventListener js/window "resize" bounds!)
        (scroll/apply! @node))
      :component-did-update   (fn [this] (sync! this))
      :component-will-unmount (fn [_]
                                (hide!)
                                (when-let [u @unsub] (u))
                                (when @obs (.disconnect ^js @obs))
                                (.removeEventListener js/window "resize" bounds!))
      :reagent-render         (fn [_url]
                                (reset! overlay? @(rf/subscribe [:ui/overlay-open?]))
                                (reset! owner-id @(rf/subscribe [:ui/active-tab-id]))
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
                (reset! view (syntax/create-source-view @node text (syntax/grammar-for path)))
                ;; derive a code Contents outline from the tree-sitter parse (common IR) -> sidebar :doc/toc
                (-> (syntax/parse-outline text path)
                    (.then (fn [toc] (rf/dispatch [:toc/set path toc])))
                    (.catch (fn [_] nil)))))
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
                                                           :source-location (source-location-for-pos path v loc-pos)
                                                           :source-line (:line (syntax/line-info-at v loc-pos))}}])))]
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
                               ;; size the SVG string BEFORE insertion (font-matched) → no post-insert flash
                               (set-inner! @node (str "<div class=\"vv-mermaid\">" (mermaid/size-mermaid-svg-string svg) "</div>"))
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
  [_path _stamp _data-url]
  (let [node (atom nil)]
    (r/create-class
     {:display-name "vv-image-view"
      :component-did-mount  (fn [_] (scroll/apply! @node))
      :component-did-update (fn [_] (scroll/apply! @node))
      :reagent-render       (fn [path stamp data-url] [:div.vv-image-view {:ref (fn [el] (reset! node el))}
                                                       [:img {:src (or data-url
                                                                       (media/cache-bust-local-media-url
                                                                        (media/path->file-url path) stamp))
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

(defn- content-page [path kind stamp page meta]
  (when-let [^js vv (.-vv js/window)]
    (when (.-contentPage vv)
      (-> (.contentPage vv (clj->js {:path path :kind kind :stamp stamp :page page :meta meta}))
          (.then #(js->clj % :keywordize-keys true))))))

(defn- bounded-cache [m k v]
  (let [m' (assoc m k v)]
    (if (> (count m') 9)
      (dissoc m' (first (sort (keys m'))))
      m')))

(defn- doc-page-size [doc fallback]
  (or (get-in doc [:doc/meta :pageSize]) fallback))

(defn- page-controls [kind path stamp meta page cache* current*]
  (let [{:keys [index hasPrev hasNext]} page
        prefetch! (fn [idx]
                    (when-let [p (content-page path kind stamp idx meta)]
                      (-> p (.then #(swap! cache* bounded-cache idx %)))))
        load! (fn [idx]
                (if-let [cached (get @cache* idx)]
                  (reset! current* cached)
                  (when-let [p (content-page path kind stamp idx meta)]
                    (-> p (.then (fn [res]
                                   (swap! cache* bounded-cache idx res)
                                   (reset! current* res)
                                   ;; finite lookbehind/lookahead: keep nearby pages warm without growing
                                   ;; unbounded renderer state.
                                   (doseq [adj [(dec idx) (inc idx)]
                                           :when (and (>= adj 0) (not (contains? @cache* adj)))]
                                     (prefetch! adj))))))))]
    [:div.vv-pagebar
     [:button.vv-page-btn {:disabled (not hasPrev) :on-click #(load! (dec index))} "Previous"]
     [:span.vv-page-label (str "Page " (inc (or index 0)))]
     [:button.vv-page-btn {:disabled (not hasNext) :on-click #(load! (inc index))} "Next"]]))

(defn table-view
  "Sheet/delimited table preview. Large delimited files use paged row windows fetched from main; small
   tables/workbooks render bounded sheet matrices from the content payload."
  [_doc]
  (let [node (atom nil)
        sheet* (r/atom 0)
        doc-key* (atom nil)
        current* (r/atom nil)
        cache* (r/atom {})]
    (r/create-class
     {:display-name "vv-table-view"
      :component-did-mount  (fn [this]
                              (let [[_ doc] (r/argv this)]
                                (reset! doc-key* [(:doc/path doc) (:doc/stamp doc)])
                                (reset! current* (:doc/page doc))
                                (scroll/apply! @node)))
      :component-did-update (fn [this]
                              (let [[_ doc] (r/argv this)]
                                (when (not= [(:doc/path doc) (:doc/stamp doc)] @doc-key*)
                                  (reset! doc-key* [(:doc/path doc) (:doc/stamp doc)])
                                  (reset! cache* {})
                                  (reset! current* (:doc/page doc)))
                                (scroll/apply! @node)))
      :reagent-render
      (fn [doc]
        (let [paged? (:doc/paged? doc)
              page (or @current* (:doc/page doc))
              page-size (doc-page-size doc 500)
              sheets (vec (:doc/sheets doc))
              sheet (get sheets @sheet* (first sheets))
              rows (if paged? (:rows page) (:rows sheet))]
          [:div.vv-table-doc {:ref (fn [el] (reset! node el))}
           (if paged?
             [page-controls "table" (:doc/path doc) (:doc/stamp doc) (:doc/meta doc) page cache* current*]
             (when (> (count sheets) 1)
               [:div.vv-sheet-tabs
                (for [[i sh] (map-indexed vector sheets)]
                  ^{:key i}
                  [:button.vv-sheet-tab {:class (when (= i @sheet*) "vv-sheet-tab-active")
                                         :on-click #(reset! sheet* i)}
                   (:name sh)])]))
           [:div.vv-table-scroll
            [:table.vv-table
             [:tbody
              (for [[ri row] (map-indexed vector rows)]
                ^{:key ri}
                [:tr
                 [:th.vv-table-rownum (str (inc (+ ri (* (or (:index page) 0) page-size))))]
                 (for [[ci cell] (map-indexed vector row)]
                   ^{:key ci} [:td cell])])]]]
           (when (:truncated sheet)
             [:div.vv-table-note "Preview truncated"])]))})))

(defn- log-level [line]
  (some-> (re-find #"(?i)\b(trace|debug|info|warn|warning|error|fatal|critical)\b" (str line))
          second
          str/lower-case
          (str/replace "warning" "warn")))

(defn log-view
  "Paged static log preview with tolerant timestamp/severity highlighting."
  [_doc]
  (let [node (atom nil)
        current* (r/atom nil)
        cache* (r/atom {})]
    (r/create-class
     {:display-name "vv-log-view"
      :component-did-mount  (fn [this]
                              (let [[_ doc] (r/argv this)]
                                (reset! current* (:doc/page doc))
                                (scroll/apply! @node)))
      :component-did-update (fn [_] (scroll/apply! @node))
      :reagent-render
      (fn [doc]
        (let [paged? (:doc/paged? doc)
              page (or @current* (:doc/page doc))
              page-size (doc-page-size doc 2000)
              lines (if paged?
                      (:lines page)
                      (str/split (or (:doc/text doc) "") #"\r?\n"))]
          [:div.vv-log-doc {:ref (fn [el] (reset! node el))}
           (when paged?
             [page-controls "log" (:doc/path doc) (:doc/stamp doc) (:doc/meta doc) page cache* current*])
           [:pre.vv-log-lines
            (for [[i line] (map-indexed vector lines)
                  :let [level (log-level line)]]
              ^{:key i}
              [:div.vv-log-line {:class (when level (str "vv-log-" level))}
               [:span.vv-log-num (str (inc (+ i (* (or (:index page) 0) page-size))))]
               [:span.vv-log-text line]])]]))})))

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
  (let [doc     @(rf/subscribe [:doc/active])
        tabs    @(rf/subscribe [:ui/tabs])
        uri     @(rf/subscribe [:ui/active-uri])
        vs?     @(rf/subscribe [:ui/active-view-source?])
        rep     @(rf/subscribe [:ui/active-representation])   ; :document | :pdf (only :pdf when a sibling exists)
        dv      @(rf/subscribe [:ui/active-diff-view])         ; :unified | :split (diff docs only)
        sib-loaded @(rf/subscribe [:pdf/sibling-loaded])      ; sibling-PDF paths whose bytes are cached
        reflow? @(rf/subscribe [:pdf/reflow?])
        stream? (stream-flag/flag-on? (:stream? @(rf/subscribe [:ui/settings])))]   ; streaming on → reflow streams too
    [:div.vv-content
     {:class (cond (or (uri/http? uri) (= "html" (:doc/kind doc))) "vv-content-web"        ; web/local-html: edge-to-edge
                   ;; A true PDF OR a previewable doc currently showing its collocated sibling PDF (rep=:pdf) both
                   ;; render the pdf.js canvas, which supplies its own gutter — drop the prose reading gutter so the
                   ;; pages sit flush at the top-left. Without the sibling arm a .tex→PDF preview kept the 32×45
                   ;; padding and the pages fell down-and-right, outside the visible bounds.
                   (or (= "pdf" (:doc/kind doc))
                       (and (= :pdf rep) (:doc/pdf-sibling doc))) "vv-content-pdf-flush"  ; PDF: edge-to-edge (keeps scroll)
                   :else                                           nil)
      ;; per-doc identity for the scroll-spy cache (toc/cached): .vv-content is one identity-stable node
      ;; reused across doc switches, so a stable key that changes per doc invalidates stale offsets. Path/uri
      ;; (not stamp) — a same-path live-refresh re-measures in place, so its cache stays valid.
      :data-doc-key (cond (uri/http? uri) uri :else (:doc/path doc))
      :on-scroll (fn [^js e] (toc/spy! (.-currentTarget e)))}
     (cond
       (empty? tabs)               [watermark]
       (nil? uri)                  [:div.vv-empty "New Tab"]
       (uri/http? uri)             [web-host uri]
       ;; local .html → render live in the web view via its file:// URL (keyed by stamp so a live-refresh
       ;; remounts the host and reloads the page), not shown as escaped source
       (= "html" (:doc/kind doc))  ^{:key (str "html:" (:doc/path doc) ":" (:doc/stamp doc))}
                                   [web-host (media/path->file-url (:doc/path doc))]
       ;; Document↔PDF representation switch: a doc collocated with an exported PDF, currently showing :pdf →
       ;; render that sibling PDF in place (bytes are loaded byte-only into pdf-cache; a brief note until ready).
       ;; Placed above :doc/error / :doc/streaming? so the faithful PDF shows regardless of the doc's own state.
       (and (= :pdf rep) (:doc/pdf-sibling doc))
       (if (contains? sib-loaded (:doc/pdf-sibling doc))
         ^{:key (str "sibling-pdf:" (:doc/pdf-sibling doc))}
         [pdf-view (:doc/pdf-sibling doc) (:doc/stamp doc)]
         [:div.vv-empty "Loading PDF…"])
       (:doc/error doc)            [:div.vv-error "Error: " (:doc/error doc)]
       ;; a large streamable doc renders as a bounded-memory INCREMENTAL stream (ir-stream-body drives it from
       ;; the file path); keyed by [path stamp] so a live-refresh remounts and re-streams. Small docs never set
       ;; :doc/streaming? (stream-flag/enabled?), so they fall through to the byte-identical batch renderers.
       (:doc/streaming? doc)       ^{:key (str "stream:" (:doc/path doc) ":" (:doc/stamp doc))}
                                   [ir-stream-body (:doc/path doc) (:doc/kind doc) (:doc/text doc) (:doc/stamp doc)]
       ;; PDF: the fixed-layout canvas by default; when "Reflow Text" is on and the extracted-text HTML is
       ;; ready, show that reflowable prose instead (an additive facet — the canvas render is untouched).
       (= "pdf" (:doc/kind doc))
       (cond
         ;; reflow + streaming on → progressively commit the extracted-text blocks (pdf/reflow-blocks!, stored
         ;; by the :pdf/reflow fx while the doc was mounted); byte-identical to the batch reflow HTML
         (and reflow? (:doc/reflow-html doc) stream?)
         ^{:key (str "pdf-reflow-stream:" (:doc/path doc) ":" (:doc/stamp doc))}
         [ir-stream-body (:doc/path doc) "pdf-reflow" nil (:doc/stamp doc)]
         (and reflow? (:doc/reflow-html doc))
         ^{:key (str "pdf-reflow:" (:doc/path doc))}
         [markdown-body (:doc/reflow-html doc) nil (:doc/path doc)]
         :else
         ^{:key (str "pdf:" (:doc/path doc))}
         [pdf-view (:doc/path doc) (:doc/stamp doc)])
       (= "image" (:doc/kind doc)) ^{:key (str (:doc/path doc) ":" (:doc/stamp doc))}
                                   [image-view (:doc/path doc) (:doc/stamp doc) (:doc/data-url doc)]
       (and vs?
            (:doc/text doc)
            (or (:doc/sourceable? doc) (#{"markdown" "mermaid" "source" "org" "latex" "diff"} (:doc/kind doc))))
       ^{:key (str "src:" (:doc/path doc) ":" (:doc/stamp doc))}
       [source-view (:doc/text doc) (:doc/path doc)]
       ;; diff (.diff/.patch): the colored UNIFIED HTML by default; the side-by-side SPLIT HTML when chosen and
       ;; built (falls back to unified while the split is still building). Both render through markdown-body, so
       ;; find / scroll-spy / the themed context menu / the Contents outline all work. "Source" (above) shows raw.
       (= "diff" (:doc/kind doc))
       (cond
         (and (= :split dv) (:doc/diff-split-html doc))
         ^{:key (str "diff-split:" (:doc/path doc) ":" (:doc/stamp doc))}
         [markdown-body (:doc/diff-split-html doc) (:doc/text doc) (:doc/path doc)]
         (:doc/html doc)
         ^{:key (str "diff:" (:doc/path doc) ":" (:doc/stamp doc))}
         [markdown-body (:doc/html doc) (:doc/text doc) (:doc/path doc)]
         :else [:div.vv-empty "Rendering…"])
       (= "diagram" (:doc/kind doc)) [:div.vv-diagram [markdown-body (:doc/html doc) (:doc/text doc) (:doc/path doc)]]
       (= "mermaid" (:doc/kind doc)) ^{:key (str "mermaid:" (:doc/path doc) ":" (:doc/stamp doc))}
                                     [mermaid-view (:doc/text doc) (:doc/path doc)]
       (= "source" (:doc/kind doc)) ^{:key (:doc/path doc)} [source-view (:doc/text doc) (:doc/path doc)]
       (= "office" (:doc/kind doc)) [markdown-body (:doc/html doc) (:doc/text doc) (:doc/path doc)]
       (= "table" (:doc/kind doc)) ^{:key (str "table:" (:doc/path doc) ":" (:doc/stamp doc))}
                                   [table-view doc]
       (= "log" (:doc/kind doc))   ^{:key (str "log:" (:doc/path doc) ":" (:doc/stamp doc))}
                                   [log-view doc]
       (#{"directory" "archive"} (:doc/kind doc)) ^{:key (str "dir:" (:doc/path doc))}
                                                   [dir-view (:doc/path doc) (:doc/entries doc)]
       ;; The render FINISHED but produced nothing (`""` — truthy in CLJS, so this must precede the
       ;; `(:doc/html doc)` catch-all or an empty .markdown-body mounts and the pane is silently blank).
       ;; While the render is still in flight :doc/html is nil, so the "Rendering…" branch below still wins.
       (and (some? (:doc/html doc)) (ir-html/blank? (:doc/html doc)))
       [:div.vv-empty "Nothing to preview — this document has no renderable content. Use View Source to see the file."]
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

(defn- seg-button [active? label title on-click]
  [:button.vv-seg-btn {:class (when active? "vv-seg-active") :title title :on-click on-click} label])

(defn view-switch-toolbar
  "Contextual segmented controls in the toolbar: [Doc | PDF] when the active doc has a collocated PDF (a source
   doc's exported PDF, switched in-place) OR is itself a PDF collocated with a source (\"Doc\" navigates to the
   rendered source); [Unified | Split] for a diff's layout; and [Preview | Source] when a previewable document
   (not the PDF) is showing. All also live in the tab right-click menu and the command palette; the toolbar makes
   them discoverable. Renders nothing otherwise."
  []
  (let [kind       @(rf/subscribe [:doc/kind])
        sibling    @(rf/subscribe [:doc/pdf-sibling])       ; a source doc's exported PDF → in-place switch
        source-sib @(rf/subscribe [:doc/source-sibling])    ; a PDF's collocated source → navigate-to switch
        rep        @(rf/subscribe [:ui/active-representation])
        dv         @(rf/subscribe [:ui/active-diff-view])
        vs?        @(rf/subscribe [:ui/active-view-source?])
        id         @(rf/subscribe [:ui/active-tab-id])
        diff?      (= "diff" kind)
        previewable? (contains? #{"markdown" "org" "latex" "mermaid" "diff"} kind)]
    [:<>
     ;; [Doc | PDF] — a source doc with an exported PDF (switched in-place), or a PDF with a collocated source
     ;; (where "Doc" navigates the tab to the rendered source, which itself offers "PDF" to return).
     (cond
       sibling
       [:div.vv-seg {:role "group" :aria-label "Representation"}
        [seg-button (= rep :document) "Doc" "Show the rendered document"
         #(rf/dispatch [:tab/set-representation id :document])]
        [seg-button (= rep :pdf) "PDF" "Show the collocated exported PDF"
         #(rf/dispatch [:tab/set-representation id :pdf])]]
       source-sib
       [:div.vv-seg {:role "group" :aria-label "Representation"}
        [seg-button false "Doc" "Open the collocated source document"
         #(rf/dispatch [:tab/open-representation-source id])]
        [seg-button true "PDF" "Showing this PDF" (fn [] nil)]])
     ;; [Unified | Split] — a diff's layout (hidden while viewing the raw source text)
     (when (and diff? (not vs?))
       [:div.vv-seg {:role "group" :aria-label "Diff layout"}
        [seg-button (= dv :unified) "Unified" "Show the unified (single-column) diff"
         #(when (not= dv :unified) (rf/dispatch [:tab/set-diff-view id :unified]))]
        [seg-button (= dv :split) "Split" "Show the side-by-side diff"
         #(when (not= dv :split) (rf/dispatch [:tab/set-diff-view id :split]))]])
     ;; [Preview | Source] — a previewable document showing its rendered self (never over a PDF)
     (when (and previewable? (not= rep :pdf))
       [:div.vv-seg {:role "group" :aria-label "View"}
        [seg-button (not vs?) "Preview" "Show the rendered preview"
         #(when vs? (rf/dispatch [:tab/toggle-source id]))]
        [seg-button vs? "Source" "Show the source text"
         #(when-not vs? (rf/dispatch [:tab/toggle-source id]))]])]))

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
         [view-switch-toolbar]
         [passwords-ui/toolbar-button]
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

;; mode-line — REMOVED per user (2026-06-28): the Vim NORMAL/VISUAL + pending-keys badge floated over the
;; zoom bar and has no functional purpose in this interface. Kept (commented, not deleted) per the
;; comment-don't-delete rule; the resolver's modal state is unaffected — only the visual badge is gone. To
;; restore, un-discard this and re-add [mode-line] inside .vv-pane in `root`.
#_
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
      [find-bar]
      ;; the hover-URL status bar lives INSIDE this wrap, so its bottom:0 floats above the zoom bar (the
      ;; wrap ends above the bar) instead of overlapping it
      [status-bar]]
     ;; [mode-line] removed per user — the Vim NORMAL/VISUAL badge floated over the zoom bar and serves no
     ;; functional purpose here (the resolver's modal state is unaffected; only the visual badge is gone)
     [zoombar/zoombar]]]
   [palette/command-palette]
   [ctx-menu/context-menu]
   [settings-ui/dialog]
   [extensions-ui/dialog]
   [passwords-ui/dialog]
   [passwords-ui/save-prompt]
   [about/dialog]
   (when @(rf/subscribe [:kbedit/open?]) [kbedit/dialog])
   [hints-overlay]])
