(ns vinary.app.fx
  "re-frame effects — the only place async/IO/DataScript-mutation touches the world (effects at the
   edge → replay/time-travel)."
  (:require [re-frame.core :as rf]
            [datascript.core :as d]
            [vinary.app.ds :as ds]
            [vinary.diff :as diff]
            [vinary.ir.frontend.diff :as ir-diff]
            [vinary.ir.backend.html :as ir-html]
            [vinary.renderer.markdown :as md]
            [vinary.renderer.scroll :as scroll]
            [vinary.renderer.diff-view :as diff-view]
            [vinary.renderer.hints :as hints]
            [vinary.renderer.cm :as cm]
            [vinary.renderer.figures :as figures]
            [vinary.renderer.mermaid :as mermaid]
            [vinary.renderer.source-nav :as source-nav]
            [vinary.renderer.pdf-cache :as pdf-cache]
            [vinary.renderer.warm-cache :as warm-cache]
            [vinary.renderer.input-trace :as input-trace]
            [vinary.renderer.tree-reveal :as tree-reveal]
            [vinary.async.scheduler :as sched]
            [vinary.renderer.find :as finder]))

;; ---- deferral, shared ---------------------------------------------------------------------------------
;; The generic edge onto vinary.async.scheduler, so every widget that needs "do this once typing stops"
;; expresses it the same way. Distinct from re-frame's own :dispatch-later in the way that matters here:
;; ONE live timer per :key, replaced on each arming, and genuinely cancellable. The pattern it replaces —
;; schedule a timer per keystroke and let the stale ones fire into a generation check — leaves one timer
;; per character alive and cannot express cancellation at all.
(rf/reg-fx :async/debounce
           (fn [{:keys [key ms dispatch]}]
             (sched/debounce! key ms #(rf/dispatch dispatch))))

(rf/reg-fx :async/cancel (fn [key] (sched/cancel! key)))

;; content-pane view position: a cofx reads the leaving view's position — both the preview pixel scrollTop AND the
;; source viewport line (each nav event saves the one its facet makes authoritative, via nav/capture-pos — so a
;; :source facet restores its LINE, a preview facet its pixel :scroll); the fx requests the next-rendered document
;; be restored to a saved pixel position (the source-line restore rides the :source/want-line fx instead).
(rf/reg-cofx :view-pos (fn [cofx _] (assoc cofx :view-pos {:scroll (scroll/current)
                                                           :line   (cm/current-viewport-line)})))
(rf/reg-fx   :scroll/restore (fn [n] (scroll/want! n)))

;; `:tree/received` commits project data first, then this schedules the DOM follow-up after Reagent has rendered
;; the new row. Active-path changes use the same coalesced scheduler from the tree component lifecycle.
(rf/reg-fx :tree/reveal-active (fn [_] (tree-reveal/schedule!)))

;; send a user-typed SSH secret straight to main (vv:ssh-prompt-reply) — the ONLY secret-bearing channel; the
;; value came from the prompt modal's local state and is never stored in app-db.
(rf/reg-fx :ssh/reply
           (fn [{:keys [promptId secret]}]
             (when-let [^js vv (.-vv js/window)]
               (.sshPromptReply vv promptId secret))))

;; Vimium link hints: collect visible links + assign labels (→ :hints/activate); follow a chosen target
(rf/reg-fx :hints/collect
           (fn [_]
             ;; the in-pane directory browser lives inside .vv-content (always hinted). The sidebar git tree is a
             ;; SIBLING of .vv-content, so hinting it leaked file-row labels over a PDF/preview; include it ONLY
             ;; when it actually holds focus (same activeElement discrimination input/fx uses for :dom/focus).
             (let [content (.querySelector js/document ".vv-content")
                   tree    (.querySelector js/document ".vv-tree")
                   tree?   (and tree (.contains tree (.-activeElement js/document)))]
               (rf/dispatch [:hints/activate
                             (hints/with-labels (hints/collect (if tree? [content tree] [content])))]))))

(rf/reg-fx :hints/follow
           (fn [{:keys [kind path x y]}]
             ;; Targets are serialized (no DOM node), so for a real link we RE-FIND the live element at its
             ;; stamped viewport position and fire its OWN click — a PDF intra-doc link carries its destination
             ;; only in a click listener (href="#"), so deriving nav from href is a no-op; .click() runs the
             ;; listener exactly like a user click (intra-doc → scroll-to-page, external → open). The hint overlay
             ;; is pointer-events:none, so elementFromPoint passes through to the link.
             (let [^js el (some-> (.elementFromPoint js/document (inc x) (inc y)) (.closest "a[href]"))]
               (cond
                 el                         (.click el)
                 ;; a diff file banner (ADR-0037): re-find by its stable vv-diff-file-N id — more robust
                 ;; than the stamped position (survives a wheel-scroll between collect and follow) — and
                 ;; fire its OWN click, so the hint rides the same delegated toggle branch as a mouse
                 ;; click (one behavior source; the Bug-B2 fidelity argument above). `el` is always nil
                 ;; here — a banner has no a[href] ancestor.
                 (= kind :toggle)           (some-> (.getElementById js/document path) (.click))
                 ;; file/dir rows ([data-path], no href): keep :doc/open — their on-click is platform single/
                 ;; double-click gated, so a synthetic .click() would only open on single-click platforms.
                 (#{:http :file :dir} kind) (rf/dispatch [:doc/open path])
                 ;; fallback for a non-PDF in-page #anchor not found by elementFromPoint
                 (= kind :anchor)           (when-let [^js a (.getElementById js/document path)]
                                              (.scrollIntoView a #js {:behavior "smooth" :block "start"}))
                 :else nil))))

;; DataScript writes go through this fx (keeps event handlers pure).
(rf/reg-fx :ds/transact (fn [tx] (d/transact! ds/conn tx)))

;; Markdown render → dispatch the HTML back into the loop. The common IR IS the render path now (ADR-0017):
;; render-ir builds the IR from the pipeline HAST and lowers it back through the single sanitizer, producing
;; byte-identical output to the retired legacy string render (proven by ir.parity-test + the electron smoke).
(rf/reg-fx
 :markdown/render
 (fn [{:keys [text path stamp base-dir on-done]}]
   ;; the common IR IS the render path (ADR-0017/0029): remark-parse + remark-gfm (micromark) → mdast → hast →
   ;; IR → the shared app-hast-suffix + apply-posts. base-dir resolves relative URLs → file:// — an explicit
   ;; :base-dir (a piped document's invoking cwd, ADR-0036) overrides the path-derived one.
   (-> (md/render-ir text (or base-dir (md/dir-of path)) stamp)
       (.then (fn [result] (rf/dispatch (conj on-done result))))
       (.catch (fn [e] (rf/dispatch [:content/error {:path path :message (str "render error: " (.-message e))}]))))))

;; Office render (docx / ODF HTML) through the common IR when :vv/ir is on → HTML + a heading TOC (office
;; previously produced neither a TOC nor went through the GitHub-allowlist sanitizer).
(rf/reg-fx
 :office/render
 (fn [{:keys [html path on-done]}]
   (-> (md/render-office-ir html)
       (.then (fn [result] (rf/dispatch (conj on-done result))))
       (.catch (fn [e] (rf/dispatch [:content/error {:path path :message (str "office render error: " (.-message e))}]))))))

;; Org (.org) render through the common IR via uniorg (HTML + heading TOC + assets), modeled on :markdown/render
;; — base-dir resolves relative Org image URLs to file://, and nested #+begin_src blocks highlight via apply-posts.
(rf/reg-fx
 :org/render
 (fn [{:keys [text path stamp base-dir on-done]}]
   (-> (md/render-org-ir text (or base-dir (md/dir-of path)) stamp)   ; the proven uniorg pipeline (the sole Org path)
       (.then (fn [result] (rf/dispatch (conj on-done result))))
       (.catch (fn [e] (rf/dispatch [:content/error {:path path :message (str "org render error: " (.-message e))}]))))))

;; LaTeX (.tex) render through the common IR via unified-latex (HTML + heading TOC + assets), modeled on
;; :org/render — base-dir resolves relative \includegraphics image URLs to file://, and the preserved TeX +
;; fenced code highlight via apply-posts.
(rf/reg-fx
 :latex/render
 (fn [{:keys [text path stamp base-dir on-done]}]
   (-> (md/render-latex-ir text (or base-dir (md/dir-of path)) stamp)
       (.then (fn [result] (rf/dispatch (conj on-done result))))
       (.catch (fn [e] (rf/dispatch [:content/error {:path path :message (str "latex render error: " (.-message e))}]))))))

;; Diff (.diff/.patch) UNIFIED render → the colored single-column HTML + a per-file Contents outline. Pure
;; (ir.frontend.diff → ir.backend.html): the HTML is fixed structure + escaped text nodes, so it needs no
;; post-passes and no runtime sanitizer (there is no raw-HTML vector — untrusted content is only ever text).
(rf/reg-fx
 :diff/render
 (fn [{:keys [text path on-done]}]
   (try
     (let [ir (ir-diff/diff->ir text)]
       (rf/dispatch (conj on-done {:html (ir-html/lower ir) :toc (ir-diff/outline ir) :assets []})))
     (catch :default e
       (rf/dispatch [:content/error {:path path :message (str "diff render error: " (.-message e))}])))))

;; Diff SIDE-BY-SIDE (split) build: parse once, emit the baseline two-column HTML immediately (derivable from the
;; hunks alone), then ask main to resolve the referenced files on disk and, when any are found, re-emit an
;; ENRICHED split with full-file context. Both land on the doc as :doc/diff-split-html (:diff/split-ready).
(rf/reg-fx
 :diff/build-split
 (fn [{:keys [path text]}]
   (let [model (diff/parse text)]
     (rf/dispatch [:diff/split-ready path (diff/split-html model)])          ; instant, sources-free baseline
     (when-let [^js v (.-vv js/window)]
       (when (.-loadDiffSources v)
         (-> (.loadDiffSources v (clj->js {:diffPath path :files (diff/referenced-paths model)}))
             (.then (fn [srcs]
                      (let [sources (js->clj srcs)]
                        (when (seq sources)
                          (rf/dispatch [:diff/split-ready path (diff/split-html model sources)])))))
             (.catch (fn [_] nil))))))))

;; project the per-tab collapsed diff-file set onto the mounted DOM (ADR-0037) — the state-change half;
;; markdown-body calls the same applier synchronously after every innerHTML rebuild (the re-render half).
(rf/reg-fx
 :diff/apply-collapsed
 (fn [collapsed] (diff-view/apply-collapsed! collapsed)))

;; swap the active theme stylesheet (themes are CSS-var palettes; the structural app.css references them)
(rf/reg-fx
 :theme/apply
 (fn [theme]
   (when-let [^js link (.getElementById js/document "vv-theme-link")]
     (set! (.-href link) (str "css/themes/" theme ".css")))))

;; PDF byte cache (keyed by :doc/path; never DataScript — ADR-0010) + retention eviction
(rf/reg-fx :pdf/cache-bytes (fn [{:keys [path bytes]}] (pdf-cache/put-bytes! path bytes)))
(rf/reg-fx :pdf/evict       (fn [keep-paths] (pdf-cache/evict-keep! keep-paths)))
(rf/reg-fx :render-cache/invalidate
           (fn [{:keys [path stamp]}] (warm-cache/invalidate-path! path stamp)))
(rf/reg-fx :render-cache/retain-only (fn [paths] (warm-cache/retain-only! paths)))

;; Load a collocated FACET's content into the cache WITHOUT opening a tab: main reads + routes the file over the
;; vv:open seam and replies vv:content → :content/received transacts its doc entity (and, for a pdf, caches its
;; bytes via :pdf/cache-bytes). Idempotent — a no-op when the entity is already present (and, for a pdf, its bytes
;; are cached). This is how an in-place facet switch shows any sibling representation.
(rf/reg-fx
 :facet/ensure-loaded
 (fn [{:keys [path]}]
   (let [snap (ds/snapshot)
         eid  (ds/eid-for-path snap path)
         pdf? (= "pdf" (ds/doc-attr snap path :doc/kind))]
     (when (and path (or (nil? eid) (and pdf? (nil? (pdf-cache/get-bytes path)))))
       (when-let [^js v (.-vv js/window)]
         (when (.-open v) (.open v path)))))))
;; the :pdf/reflow effect is registered in vinary.renderer.pdf (a renderer-only ns; keeping it there avoids
;; pulling pdf.js — which touches `document` at load — into the DOM-free :node-test build).

;; in-page find (imperative DOM highlight, dispatches the count + index back into the loop). For a PDF,
;; first materialize ALL text layers, and for a streamed document drain it, so find covers the WHOLE
;; document rather than the part that happens to be rendered. That await is why the result carries a
;; generation: it makes the effect asynchronous, so replies can land out of order.
;;
;; The effect is :find/search and the EVENT that schedules it is :find/run — re-frame keeps the two
;; registries separate, but one keyword for both would read as a loop.
;;
;; `input-trace/mark!` closes the keystroke→work-settled interval for the DEV latency tracer. It is a
;; no-op until the tracer has been installed (goog.DEBUG only), so this costs a boolean deref in a
;; release build.
(rf/reg-fx :find/search
           (fn [{:keys [q gen]}]
             (-> (pdf-cache/ensure-active!)
                 (.then (fn [_]
                          (finder/search! q (fn [res]
                                              (rf/dispatch [:find/result (assoc res :gen gen)])
                                              (input-trace/mark! "find/search"))))))))
(rf/reg-fx :find/cycle (fn [{:keys [dir gen]}]
                         (finder/cycle! dir #(rf/dispatch [:find/result (assoc % :gen gen)]))))
(rf/reg-fx :find/clear (fn [_]   (finder/clear!)))

;; scroll a heading/section (by id) to the top of the content. Use a CONFINED scroll of the .vv-content
;; scroller (not el.scrollIntoView, which scrolls every scrollable ancestor up to the viewport and can scroll
;; #app itself when a tall PDF overflows it — pushing the menu bar out of the clipped viewport). The offset
;; formula now lives in vinary.renderer.scroll/confined-top, shared with the source→preview jump and with
;; in-page find, and unit-tested there; `:block :start :margin 0` is exactly what this call site always did.
(rf/reg-fx
 :toc/scroll
 (fn [id]
   (if-let [^js el (.getElementById js/document id)]
     (if (.closest el ".vv-content")
       (scroll/scroll-el-to! el {:block :start :behavior "smooth"})
       (.scrollIntoView el #js {:block "start" :behavior "smooth"}))            ; fallback: not inside a scroller
     ;; no DOM anchor: a source Contents "L<line>" id — scroll the CodeMirror source view to that line.
     (when-let [[_ n] (re-matches #"L(\d+)" (str id))]
       (cm/scroll-source-to-line! (js/parseInt n))))))

;; bidirectional source⇄preview jump (dispatched by :source/goto-line, :preview/goto-line). *-scroll-line acts
;; on the already-mounted view now; *-want-line stashes the target for the view that is about to remount.
(rf/reg-fx :source/scroll-line  (fn [line] (cm/scroll-source-to-line! line)))
(rf/reg-fx :source/want-line    (fn [line] (cm/want-source-line! line)))
(rf/reg-fx :preview/scroll-line (fn [line] (source-nav/scroll-preview-to-line! line)))
(rf/reg-fx :preview/want-line   (fn [line] (source-nav/want-preview-line! line)))
;; keyboard/palette entry points: derive the "current" line from the DOM (no click target), then dispatch the
;; parameterized jump. No-op when the anchor can't be resolved (e.g. no preview / no mounted source view).
(rf/reg-fx :jump/to-source-current  (fn [_] (when-let [line (source-nav/current-preview-line)] (rf/dispatch [:source/goto-line line]))))
(rf/reg-fx :jump/to-preview-current (fn [_] (when-let [line (cm/current-source-line)] (rf/dispatch [:preview/goto-line line]))))

;; renderer → main (over the contextBridge seam)
(rf/reg-fx :vv/open  (fn [path] (when-let [^js vv (.-vv js/window)] (.open vv path))))
;; Settings ▸ File Type — main registers the override and re-sends the doc (ADR-0036). Guarded like every
;; newer preload fn (a stale preload from a not-yet-restarted daemon simply lacks it).
(rf/reg-fx :vv/set-file-type
           (fn [{:keys [path kind language]}]
             (when-let [^js vv (.-vv js/window)]
               (when (.-setFileType vv)
                 (.setFileType vv (clj->js (cond-> {:path path :kind kind}
                                             language (assoc :language language))))))))
(rf/reg-fx :vv/close (fn [path] (when-let [^js vv (.-vv js/window)] (.close vv path))))
(rf/reg-fx :vv/watch-assets
           (fn [{:keys [doc-path paths]}]
             (when-let [^js vv (.-vv js/window)]
               (when (.-watchAssets vv) (.watchAssets vv doc-path (clj->js (or paths [])))))))
(rf/reg-fx :vv/sync-retained-files
           (fn [paths]
             (when-let [^js vv (.-vv js/window)]
               (when (.-syncRetainedFiles vv) (.syncRetainedFiles vv (clj->js (or paths [])))))))
(rf/reg-fx :vv/sync-tree-roots
           (fn [roots]
             (when-let [^js vv (.-vv js/window)]
               (when (.-syncTreeRoots vv) (.syncTreeRoots vv (clj->js (or roots [])))))))
(rf/reg-fx :vv/sync-tree-expanded
           (fn [scopes]
             (when-let [^js vv (.-vv js/window)]
               (when (.-syncTreeExpanded vv) (.syncTreeExpanded vv (clj->js (or scopes [])))))))

(defn- tree-refresh-error [e]
  (or (some-> e .-message) (str e) "tree refresh failed"))

(rf/reg-fx
 :vv/refresh-tree
 (fn [{:keys [root path on-success on-failure]}]
   (if-let [^js vv (.-vv js/window)]
     (if (.-refreshTree vv)
       (-> (.refreshTree vv (clj->js {:root root :path path}))
           (.then (fn [entry]
                    (rf/dispatch (conj (vec on-success)
                                       (js->clj entry :keywordize-keys true)))))
           (.catch (fn [e]
                     (rf/dispatch (conj (vec on-failure) (tree-refresh-error e))))))
       (rf/dispatch (conj (vec on-failure) "tree refresh API unavailable")))
     (rf/dispatch (conj (vec on-failure) "tree bridge unavailable")))))

(rf/reg-fx
 :vv/refresh-all-trees
 (fn [{:keys [on-success on-failure]}]
   (if-let [^js vv (.-vv js/window)]
     (if (.-refreshAllTrees vv)
       (-> (.refreshAllTrees vv)
           (.then (fn [entries]
                    (rf/dispatch (conj (vec on-success)
                                       (js->clj entries :keywordize-keys true)))))
           (.catch (fn [e]
                     (rf/dispatch (conj (vec on-failure) (tree-refresh-error e))))))
       (rf/dispatch (conj (vec on-failure) "tree refresh API unavailable")))
     (rf/dispatch (conj (vec on-failure) "tree bridge unavailable")))))

(rf/reg-fx
 :vv/refresh-trees
 (fn [{:keys [scopes on-complete]}]
   (if-let [^js vv (.-vv js/window)]
     (if (.-refreshTree vv)
       (let [requests
             (mapv (fn [{:keys [root path] :as scope}]
                     (-> (.refreshTree vv (clj->js {:root root :path path}))
                         (.then (fn [entry]
                                  {:scope scope :entry (js->clj entry :keywordize-keys true)}))
                         (.catch (fn [e]
                                   {:scope scope :error (tree-refresh-error e)}))))
                   scopes)]
         (-> (js/Promise.all (into-array requests))
             (.then (fn [results]
                      (rf/dispatch (conj (vec on-complete) (vec results)))))))
       (rf/dispatch (conj (vec on-complete)
                          (mapv (fn [scope] {:scope scope :error "tree refresh API unavailable"}) scopes))))
     (rf/dispatch (conj (vec on-complete)
                        (mapv (fn [scope] {:scope scope :error "tree bridge unavailable"}) scopes))))))
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

(rf/reg-fx :vv/open-dialog   (fn [seeds] (when-let [^js v (vv)] (when (.-openDialog v)  (.openDialog v (clj->js seeds))))))
(rf/reg-fx :vv/quit          (fn [_]    (when-let [^js v (vv)] (when (.-quit v)         (.quit v)))))
(rf/reg-fx :vv/zoom          (fn [dir]  (when-let [^js v (vv)] (when (.-zoom v)         (.zoom v dir)))))
(rf/reg-fx :vv/zoom-set      (fn [f]    (when-let [^js v (vv)] (when (.-zoomSet v)      (.zoomSet v f)))))
(rf/reg-fx :vv/http-zoom     (fn [dir]  (when-let [^js v (vv)] (when (.-httpZoom v)     (.httpZoom v dir)))))
(rf/reg-fx :vv/http-zoom-set (fn [f]    (when-let [^js v (vv)] (when (.-httpZoomSet v)  (.httpZoomSet v f)))))
(rf/reg-fx :vv/devtools      (fn [_]    (when-let [^js v (vv)] (when (.-toggleDevtools v) (.toggleDevtools v)))))
(rf/reg-fx :vv/copy          (fn [text] (when-let [^js v (vv)] (when (.-copyText v)     (.copyText v (str text))))))
(rf/reg-fx :vv/save-settings
           ;; debounced — the sidebar resize splitter writes :sidebar-width on every mousemove, and every
           ;; character typed into a Preferences field writes a font name. Through the shared scheduler:
           ;; the hand-rolled clear-timer/set-timer pair this replaces was one of the four ad-hoc deferral
           ;; idioms vinary.async.scheduler exists to unify.
           (fn [edn]
             (sched/debounce! ::save-settings 300
                              (fn [] (when-let [^js v (vv)] (when (.-saveSettings v) (.saveSettings v edn)))))))
(rf/reg-fx :vv/save-keymap   (fn [edn]  (when-let [^js v (vv)] (when (.-saveKeymap v) (.saveKeymap v edn)))))
(rf/reg-fx :vv/save-recent
           ;; debounced (Alt+Up/Down and breadcrumb clicks can rewrite the trail rapidly)
           (fn [edn]
             (sched/debounce! ::save-recent 300
                              (fn [] (when-let [^js v (vv)] (when (.-saveRecent v) (.saveRecent v edn)))))))

;; URI-bar path completion: invoke main (request/response); debounced for live typing, immediate for Enter.
;; The debounce protects the MAIN process, not the renderer: `complete` there does a readdir plus a statSync
;; per entry, so one request per character would put a synchronous filesystem walk on the main process for
;; every keystroke.
(rf/reg-fx :vv/complete-path
           (fn [{:keys [input tag]}]
             (let [go (fn [] (when-let [^js v (vv)]
                               (when (.-completePath v)
                                 (-> (.completePath v input)
                                     (.then (fn [res] (rf/dispatch [:uri-complete/result tag (js->clj res :keywordize-keys true)])))
                                     (.catch (fn [_] nil))))))]
               (if (= tag :enter)
                 ;; Enter is a commit, not typing: run it now, and cancel any live typing request so a
                 ;; late reply cannot re-open the dropdown over a navigation that has already happened
                 (do (sched/cancel! ::complete-path) (go))
                 (sched/debounce! ::complete-path 90 go)))))
(rf/reg-fx :uri-complete/error-timeout
           (fn [_] (js/setTimeout #(rf/dispatch [:uri-complete/clear-error]) 2500)))

;; ---- extensions + ad-blocking effects (renderer → main over the seam) ----
(rf/reg-fx :vv/ext-install        (fn [s]   (when-let [^js v (vv)] (when (.-extInstall v) (.extInstall v s)))))
(rf/reg-fx :vv/ext-remove         (fn [id]  (when-let [^js v (vv)] (when (.-extRemove v) (.extRemove v id)))))
(rf/reg-fx :vv/ext-set-enabled    (fn [{:keys [id on]}] (when-let [^js v (vv)] (when (.-extSetEnabled v) (.extSetEnabled v id on)))))
(rf/reg-fx :vv/ext-check-updates  (fn [_]   (when-let [^js v (vv)] (when (.-extCheckUpdates v) (.extCheckUpdates v)))))
(rf/reg-fx :vv/ext-action-clicked (fn [{:keys [id popup bounds]}]
                                    (when-let [^js v (vv)] (when (.-extActionClicked v) (.extActionClicked v id popup (clj->js bounds))))))
(rf/reg-fx :vv/ext-popup-close    (fn [_]   (when-let [^js v (vv)] (when (.-extPopupClose v) (.extPopupClose v)))))
(rf/reg-fx :vv/adblock-set-enabled (fn [on] (when-let [^js v (vv)] (when (.-adblockSetEnabled v) (.adblockSetEnabled v on)))))
(rf/reg-fx :vv/adblock-set-lists  (fn [kw]  (when-let [^js v (vv)] (when (.-adblockSetLists v) (.adblockSetLists v (name kw))))))
(rf/reg-fx :vv/adblock-refresh    (fn [_]   (when-let [^js v (vv)] (when (.-adblockRefresh v) (.adblockRefresh v)))))
(rf/reg-fx :vv/password-state      (fn [_]   (when-let [^js v (vv)] (when (.-passwordState v) (.passwordState v)))))
(rf/reg-fx :vv/password-search     (fn [url] (when-let [^js v (vv)] (when (.-passwordSearch v) (.passwordSearch v url)))))
(rf/reg-fx :vv/password-fill       (fn [item] (when-let [^js v (vv)] (when (.-passwordFill v) (.passwordFill v (clj->js item))))))
(rf/reg-fx :vv/password-save       (fn [payload] (when-let [^js v (vv)] (when (.-passwordSave v) (.passwordSave v (clj->js payload))))))
(rf/reg-fx :vv/password-dismiss-save (fn [token] (when-let [^js v (vv)] (when (.-passwordDismissSave v) (.passwordDismissSave v token)))))
(rf/reg-fx :vv/save-ext-config    ; debounced — toggles can fire rapidly
           (fn [edn]
             (sched/debounce! ::save-ext-config 300
                              (fn [] (when-let [^js v (vv)] (when (.-saveExtConfig v) (.saveExtConfig v edn)))))))
(rf/reg-fx :vv/open-path     (fn [p]    (when-let [^js v (vv)] (when (.-openPath v)     (.openPath v p)))))
(rf/reg-fx :vv/open-external (fn [url]  (when-let [^js v (vv)] (when (.-openExternal v) (.openExternal v url)))))
(rf/reg-fx :devtools/re-frame-10x (fn [visible?] (set-re-frame-10x! visible?)))

;; apply font preferences as CSS custom properties on :root (consumed by app.css with fallbacks)
(rf/reg-fx
 :fonts/apply
 (fn [{:keys [font-variable font-latex font-fixed font-size code-font-size code-ligatures?]}]
   (let [^js root (.. js/document -documentElement -style)]
     (when (seq font-variable) (.setProperty root "--vv-font-variable" font-variable))
     (when (seq font-latex)    (.setProperty root "--vv-font-latex" font-latex))
     (when (seq font-fixed)    (.setProperty root "--vv-font-fixed" font-fixed))
     ;; Fira Code ligatures: nil (unset) keeps the app.css default (none / off); a stored boolean maps to the
     ;; CSS font-variant-ligatures keyword applied to every mono surface.
     (when (some? code-ligatures?) (.setProperty root "--vv-code-liga" (if code-ligatures? "normal" "none")))
     (when font-size
       (.setProperty root "--vv-font-size" (str font-size "px"))
       ;; the document font changed with NO re-render (the CSS var is applied live), so re-fit any figures /
       ;; mermaid already on screen to the new size — the one place figure sizing runs post-DOM (idempotent).
       (figures/refit-all!)
       (mermaid/refit-all!))
     (when code-font-size      (.setProperty root "--vv-code-font-size" (str code-font-size "px"))))))
