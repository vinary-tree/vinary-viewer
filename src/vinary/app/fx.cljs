(ns vinary.app.fx
  "re-frame effects — the only place async/IO/DataScript-mutation touches the world (effects at the
   edge → replay/time-travel)."
  (:require [re-frame.core :as rf]
            [datascript.core :as d]
            [vinary.app.ds :as ds]
            [vinary.renderer.markdown :as md]
            [vinary.renderer.media :as media]
            [vinary.renderer.scroll :as scroll]
            [vinary.renderer.hints :as hints]
            [vinary.renderer.find :as finder]))

;; content-pane scroll: a cofx reads the current scrollTop (so nav events save the leaving position into
;; history); the fx requests the next-rendered document be restored to a saved position.
(rf/reg-cofx :content-scroll (fn [cofx _] (assoc cofx :content-scroll (scroll/current))))
(rf/reg-fx   :scroll/restore (fn [n] (scroll/want! n)))

;; Vimium link hints: collect visible links + assign labels (→ :hints/activate); follow a chosen target
(rf/reg-fx :hints/collect
           (fn [_]
             (let [el (.querySelector js/document ".vv-content")]
               (rf/dispatch [:hints/activate (hints/with-labels (hints/collect el))]))))

(rf/reg-fx :hints/follow
           (fn [target]
             (case (:kind target)
               :anchor       (when-let [^js el (.getElementById js/document (:path target))]
                               (.scrollIntoView el #js {:behavior "smooth" :block "start"}))
               (:http :file) (rf/dispatch [:doc/open (:path target)])
               :dir          (rf/dispatch [:shell/open-path (:path target)])
               nil)))

;; DataScript writes go through this fx (keeps event handlers pure).
(rf/reg-fx :ds/transact (fn [tx] (d/transact! ds/conn tx)))

;; Markdown render (async unified pipeline) → dispatch the HTML back into the loop.
(rf/reg-fx
 :markdown/render
 (fn [{:keys [text path stamp on-done]}]
   (-> (md/render text (md/dir-of path) stamp)   ; base-dir resolves relative img/link URLs → absolute file://
       (.then (fn [html] (rf/dispatch (conj on-done {:html html :assets (media/local-media-paths-from-html html)}))))
       (.catch (fn [e] (rf/dispatch [:content/error {:path path :message (str "render error: " (.-message e))}]))))))

;; swap the active theme stylesheet (themes are CSS-var palettes; the structural app.css references them)
(rf/reg-fx
 :theme/apply
 (fn [theme]
   (when-let [^js link (.getElementById js/document "vv-theme-link")]
     (set! (.-href link) (str "css/themes/" theme ".css")))))

;; in-page find (imperative DOM highlight, dispatches counts/index back into the loop)
(rf/reg-fx :find/run   (fn [q]   (rf/dispatch [:find/count (finder/search! q)])))
(rf/reg-fx :find/cycle (fn [dir] (rf/dispatch [:find/idx (finder/cycle! dir)])))
(rf/reg-fx :find/clear (fn [_]   (finder/clear!)))

;; scroll a heading (by rehype-slug id) to the top of the content
(rf/reg-fx
 :toc/scroll
 (fn [id]
   (when-let [^js el (.getElementById js/document id)]
     (.scrollIntoView el #js {:block "start" :behavior "smooth"}))))

;; renderer → main (over the contextBridge seam)
(rf/reg-fx :vv/open  (fn [path] (when-let [^js vv (.-vv js/window)] (.open vv path))))
(rf/reg-fx :vv/close (fn [path] (when-let [^js vv (.-vv js/window)] (.close vv path))))
(rf/reg-fx :vv/watch-assets
           (fn [{:keys [doc-path paths]}]
             (when-let [^js vv (.-vv js/window)]
               (when (.-watchAssets vv) (.watchAssets vv doc-path (clj->js (or paths [])))))))
;; ask the HTTP web view's preload to scroll to a heading id (Contents/TOC click on an HTML page)
(rf/reg-fx :vv/http-toc-goto
           (fn [id] (when-let [^js vv (.-vv js/window)] (when (.-httpTocGoto vv) (.httpTocGoto vv id)))))

;; ---- menu shell effects (renderer → main over the seam) ----
(defn- vv [] (.-vv js/window))
(defn- js-get-in [obj ks]
  (reduce (fn [o k] (when o (aget o k))) obj ks))

(defn- set-re-frame-10x! [visible?]
  (when-let [show! (js-get-in js/window ["day8" "re_frame_10x" "show_panel_BANG_"])]
    (when (fn? show!)
      (show! (boolean visible?)))))

(rf/reg-fx :vv/open-dialog   (fn [_]    (when-let [^js v (vv)] (when (.-openDialog v)   (.openDialog v)))))
(rf/reg-fx :vv/quit          (fn [_]    (when-let [^js v (vv)] (when (.-quit v)         (.quit v)))))
(rf/reg-fx :vv/zoom          (fn [dir]  (when-let [^js v (vv)] (when (.-zoom v)         (.zoom v dir)))))
(rf/reg-fx :vv/devtools      (fn [_]    (when-let [^js v (vv)] (when (.-toggleDevtools v) (.toggleDevtools v)))))
(rf/reg-fx :vv/copy          (fn [text] (when-let [^js v (vv)] (when (.-copyText v)     (.copyText v (str text))))))
(rf/reg-fx :vv/save-settings (fn [edn]  (when-let [^js v (vv)] (when (.-saveSettings v) (.saveSettings v edn)))))
(rf/reg-fx :vv/save-keymap   (fn [edn]  (when-let [^js v (vv)] (when (.-saveKeymap v) (.saveKeymap v edn)))))
(rf/reg-fx :vv/open-path     (fn [p]    (when-let [^js v (vv)] (when (.-openPath v)     (.openPath v p)))))
(rf/reg-fx :vv/open-external (fn [url]  (when-let [^js v (vv)] (when (.-openExternal v) (.openExternal v url)))))
(rf/reg-fx :devtools/re-frame-10x (fn [visible?] (set-re-frame-10x! visible?)))

;; apply font preferences as CSS custom properties on :root (consumed by app.css with fallbacks)
(rf/reg-fx
 :fonts/apply
 (fn [{:keys [font-variable font-fixed font-size code-font-size]}]
   (let [^js root (.. js/document -documentElement -style)]
     (when (seq font-variable) (.setProperty root "--vv-font-variable" font-variable))
     (when (seq font-fixed)    (.setProperty root "--vv-font-fixed" font-fixed))
     (when font-size           (.setProperty root "--vv-font-size" (str font-size "px")))
     (when code-font-size      (.setProperty root "--vv-code-font-size" (str code-font-size "px"))))))
