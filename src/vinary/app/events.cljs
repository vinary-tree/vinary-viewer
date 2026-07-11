(ns vinary.app.events
  "re-frame events. Content arriving from main (live, on every file change) is transacted into
   DataScript; Markdown is rendered via the :markdown/render fx and the HTML comes back on
   :content/rendered. Tab open/activate/close drive the multi-tab model. A content update never
   touches scroll/UI state (that's in app-db) — that's how live-refresh preserves where you are."
  (:require [re-frame.core :as rf]
            [clojure.string :as str]
            [goog.string :as gstr]
            [cljs.reader :as reader]
            [vinary.app.db :as db]
            [vinary.app.ds :as ds]
            [vinary.stream.flag :as stream-flag]
            [vinary.app.nav :as nav]
            [vinary.app.uri :as uri]
            [vinary.app.zoom :as zoom]
            [vinary.renderer.pdf-layout :as pdf-layout]
            [vinary.app.fx]
            [vinary.input.fx]))

(rf/reg-event-db :db/init    (fn [_ _] db/default-db))
(rf/reg-event-db :ds/changed (fn [db _] (update db :ds/rev inc)))

;; ---- the browser-tab model (transforms live in vinary.app.nav; tabs = views, DataScript caches content) ----
(defn- plain-html [text]
  (str "<pre class=\"vv-plain\">" (gstr/htmlEscape (or text "")) "</pre>"))

(defn- load-fx
  "FX to (re)load a uri's content — local files only (http is shown by the web view, Phase 3). main's
   :vv/open is idempotent (an already-watched path just re-sends), so back/forward/reload are safe."
  [uri]
  (if-let [p (uri/file-path uri)] [[:vv/open p]] []))

(defn- retention-fx
  "Sync main-process file watchers and evict unretained cached docs after a tab/history change."
  [db]
  (let [retained (nav/retained-file-paths db)
        tx       (ds/retract-unretained-tx (ds/snapshot) retained)]
    (cond-> []
      (seq tx) (conj [:ds/transact tx])
      true     (conj [:vv/sync-retained-files retained])
      true     (conj [:pdf/evict retained]))))   ; evict cached PDF bytes for retired docs

(defn- with-retention [result db]
  (update result :fx #(into (vec (or % [])) (retention-fx db))))

;; ---- recent navigation memory (persisted to recent.edn): dir→child trail + recent-files MRU ----
(def ^:private max-recent-files 10)
(def ^:private max-trail 200)

(defn record-recent
  "Update [:ui :recent] for a forward navigation to local path `p`: record the dir→child trail for every
   ancestor step (root→p), and — for a FILE (not a directory) — unshift p onto the recent-files MRU."
  [db p is-dir?]
  (let [pairs (partition 2 1 (uri/segments p))
        trail (reduce (fn [m [parent child]] (assoc m (:path parent) (:path child)))
                      (get-in db [:ui :recent :trail] {})
                      pairs)
        trail (if (> (count trail) max-trail)
                (into {} (take-last max-trail (sort-by key trail)))
                trail)
        files (when-not is-dir?
                (->> (get-in db [:ui :recent :recent-files] [])
                     (remove #(= % p)) (cons p) (take max-recent-files) vec))]
    (cond-> (assoc-in db [:ui :recent :trail] trail)
      files (assoc-in [:ui :recent :recent-files] files))))

(def ^:private max-web-history 300)

(defn record-web-history
  "Unshift an http(s) `url` onto the [:ui :recent :web-history] MRU (deduped, bounded). Powers the
   address bar's browser-history completion for web pages (analogous to recent-files for local files)."
  [db url]
  (if (and url (uri/http? url))
    (let [hist (->> (get-in db [:ui :recent :web-history] [])
                    (remove #(= % url)) (cons url) (take max-web-history) vec)]
      (assoc-in db [:ui :recent :web-history] hist))
    db))

(rf/reg-event-fx
 :content/received
 (fn [{:keys [db]} [_ {:keys [path kind text html entries bytes stamp sheets page meta dataUrl
                            sourceable paged pdfSibling sourceSibling] :as payload}]]
   (let [snap    (ds/snapshot)
         eid     (ds/eid-for-path snap path)
         cur-err (and eid (ds/doc-attr snap path :doc/error))
         stamp   (if (some? stamp) stamp (js/Date.now))
         ir-office? (= kind "office")   ; office always renders via :office/render (IR → HTML + TOC; ADR-0017)
         ;; A large document of an implemented streaming kind renders as a bounded-memory INCREMENTAL stream
         ;; (ir-stream-body drives it from the file path). Small docs stay on the batch path (byte-identical).
         stream? (stream-flag/enabled? kind (:size meta) (get-in db [:ui :settings :stream?]))
         ;; DataScript is the content cache keyed by :doc/path; absence = "no value" (it rejects nil).
         ;; Pre-rendered html goes straight in; markdown's html arrives async (:content/rendered); a
         ;; directory carries its :doc/entries listing instead.
         attrs   (cond-> {:doc/kind kind :doc/stamp stamp :doc/streaming? (boolean stream?)}
                   text                  (assoc :doc/text text)
                   (and html (not ir-office?)) (assoc :doc/html html)   ; office-IR fills :doc/html via :office/render
                   (#{"directory" "archive"} kind) (assoc :doc/entries (vec entries))
                   sheets                (assoc :doc/sheets (vec sheets))
                   page                  (assoc :doc/page page)
                   meta                  (assoc :doc/meta meta)
                   dataUrl               (assoc :doc/data-url dataUrl)
                   (contains? payload :sourceable) (assoc :doc/sourceable? (boolean sourceable))
                   (contains? payload :paged)      (assoc :doc/paged? (boolean paged))
                   pdfSibling            (assoc :doc/pdf-sibling pdfSibling)   ; collocated exported PDF (Doc↔PDF switch)
                   sourceSibling         (assoc :doc/source-sibling sourceSibling) ; a PDF's collocated source (PDF→Doc switch)
                   (= kind "text")       (assoc :doc/html (plain-html text))   ; plain text
                   ;; markdown/office/org/latex/diff derive their :doc/toc + :doc/assets from the IR render (arriving
                   ;; async via :content/rendered), so DON'T reset those here; every other kind clears them.
                   (and (not= kind "markdown") (not ir-office?) (not= kind "org") (not= kind "latex") (not= kind "diff"))
                   (assoc :doc/toc [] :doc/assets []))
         ;; update by :db/id when cached, create by :doc/path otherwise — the :doc/path upsert/lookup-ref
         ;; does not resolve under :advanced compilation.
         base    (if eid (assoc attrs :db/id eid) (assoc attrs :doc/path path))
         tx      (cond-> [base] cur-err (conj [:db/retract eid :doc/error cur-err]))
         ;; the CLI/initial file arrives before any tab exists → it opens the first tab
         db'     (if (empty? (nav/tabs db)) (nav/add-tab db path) db)
         ;; record recent navigation only for the ACTIVE tab's path (a forward nav / revisit), never a
         ;; background live-refresh — so the MRU + trail track where the user actually went.
         active? (= path (nav/active-path db'))
         db'     (if active? (record-recent db' path (#{"directory" "archive"} kind)) db')]
     (with-retention
       {:db db'
        :fx (cond-> [[:ds/transact tx]]
              ;; a streaming doc is driven by ir-stream-body from the file path — skip the batch render fx
              (and (= kind "markdown") (not stream?))
              (conj [:markdown/render {:text text :path path :stamp stamp
                                       :on-done [:content/rendered path stamp]}])
              ;; office (docx/ODF) → the common-IR render (HTML + heading TOC) when :vv/ir is on
              ir-office?          (conj [:office/render {:html html :path path
                                                         :on-done [:content/rendered path stamp]}])
              ;; org (.org) → the common-IR render via uniorg (HTML + heading TOC + assets), like markdown.
              ;; A streaming doc is driven by ir-stream-body from the file path → skip the batch render fx.
              (and (= kind "org") (not stream?))
              (conj [:org/render {:text text :path path :stamp stamp
                                  :on-done [:content/rendered path stamp]}])
              ;; latex (.tex) → the common-IR render via unified-latex (HTML + heading TOC + assets), like org.
              ;; LaTeX always batch-renders (not in stream-flag/streamable-kinds), but keep the guard for symmetry.
              (and (= kind "latex") (not stream?))
              (conj [:latex/render {:text text :path path :stamp stamp
                                    :on-done [:content/rendered path stamp]}])
              ;; diff (.diff/.patch) → the unified colored HTML + per-file Contents outline (ir.frontend.diff).
              (= kind "diff")
              (conj [:diff/render {:text text :path path :on-done [:content/rendered path stamp]}])
              ;; a live-refresh of a diff whose side-by-side view was already built → rebuild it against the new text
              (and (= kind "diff") (ds/doc-attr snap path :doc/diff-split-html))
              (conj [:diff/build-split {:path path :text text}])
              ;; pdf bytes go to the renderer byte cache (keyed by :doc/path), never DataScript (ADR-0010)
              (= kind "pdf")      (conj [:pdf/cache-bytes {:path path :bytes bytes}])
              ;; a doc with a collocated sibling PDF: eagerly load its bytes into pdf-cache so the Document↔PDF
              ;; switch (and a PDF-first default) shows the PDF with no wait. Byte-only — spawns no tab.
              pdfSibling (conj [:pdf/ensure-sibling-bytes {:path pdfSibling}])
              active? (conj [:vv/save-recent (pr-str (get-in db' [:ui :recent]))]))}
       db'))))

(rf/reg-event-fx
 :content/rendered
 (fn [_ [_ path stamp {:keys [html toc assets]}]]
   (let [snap (ds/snapshot)]
     (when-let [eid (ds/eid-for-path snap path)]
       (when (= stamp (ds/doc-attr snap path :doc/stamp))
         {:fx [[:ds/transact [[:db/add eid :doc/html html]   ; add by entity-id, not :doc/path upsert
                               [:db/add eid :doc/toc (vec (or toc []))]
                               [:db/add eid :doc/assets (vec (or assets []))]]]
               [:vv/watch-assets {:doc-path path :paths assets}]]})))))

;; ---- document streaming (bounded-memory incremental render; vinary.stream.*) --------------------------------
(rf/reg-event-fx
 :stream/progress
 (fn [_ [_ path progress]]
   (when-let [eid (ds/eid-for-path (ds/snapshot) path)]
     {:fx [[:ds/transact [[:db/add eid :doc/stream-progress progress]]]]})))

(rf/reg-event-fx
 :stream/toc-append
 (fn [_ [_ path entries]]
   (let [snap (ds/snapshot)]
     (when-let [eid (ds/eid-for-path snap path)]
       {:fx [[:ds/transact [[:db/add eid :doc/toc (into (vec (or (ds/doc-attr snap path :doc/toc) [])) entries)]]]]}))))

(rf/reg-event-fx
 :stream/done
 (fn [_ [_ path]]
   (when-let [eid (ds/eid-for-path (ds/snapshot) path)]
     {:fx [[:ds/transact [[:db/add eid :doc/stream-progress 1]]]]})))

;; markdown progressive stream: the whole outline + asset list are known upfront (one base-pipeline pass), so
;; set :doc/toc + :doc/assets at once and start watching the assets (image live-refresh parity with the batch).
(rf/reg-event-fx
 :stream/md-ready
 (fn [_ [_ path toc assets]]
   (when-let [eid (ds/eid-for-path (ds/snapshot) path)]
     {:fx [[:ds/transact [[:db/add eid :doc/toc (vec (or toc []))]
                          [:db/add eid :doc/assets (vec (or assets []))]]]
           [:vv/watch-assets {:doc-path path :paths assets}]]})))

;; (The :vv/ir migration flag + :ir/set-enabled toggle are RETIRED — the common IR is now the unconditional
;;  render path for Markdown and office; see ADR-0017 and vinary.ir.flag.)

;; Set a document's Contents outline (:doc/toc) out of band — used by the source-code view, which derives a
;; code outline from its tree-sitter parse (common IR) after mounting, and by any other format whose outline
;; is computed in its own view rather than at :content/rendered.
(rf/reg-event-fx
 :toc/set
 (fn [_ [_ path toc]]
   (let [snap (ds/snapshot)]
     (when-let [eid (ds/eid-for-path snap path)]
       {:fx [[:ds/transact [[:db/add eid :doc/toc (vec (or toc []))]]]]}))))

(defn content-error-tx
  "Create/update a document error transaction for path, even if no content entity exists yet."
  [snap path message stamp]
  (when path
    (let [message (or message "Unknown content error")]
      (if-let [eid (ds/eid-for-path snap path)]
        [[:db/add eid :doc/error message]
         [:db/add eid :doc/stamp stamp]]
        [{:doc/path path
          :doc/kind "text"
          :doc/error message
          :doc/stamp stamp}]))))

(rf/reg-event-fx
 :content/error
 (fn [{:keys [db]} [_ {:keys [path message stamp]}]]
   (let [tx    (content-error-tx (ds/snapshot) path message (or stamp (js/Date.now)))
         db'   (if (and path (empty? (nav/tabs db))) (nav/add-tab db path) db)]
     (with-retention
       (cond-> {:db db'}
         (seq tx) (assoc :fx [[:ds/transact tx]]))
       db'))))

;; FX helper: load the uri's content + (for a local file) restore the target history scroll. The web view
;; scrolls itself, so http never requests a content-pane restore.
(defn- nav-fx [uri scroll] (cond-> (load-fx uri) (uri/file-path uri) (conj [:scroll/restore scroll])))

(defn- nav-result [db uri scroll]
  ;; navigating to an http(s) page records it in browser history (→ address-bar history completion)
  (let [db (record-web-history db uri)]
    {:db db
     :fx (cond-> (into (retention-fx db) (nav-fx uri scroll))
           (and uri (uri/http? uri)) (conj [:vv/save-recent (pr-str (get-in db [:ui :recent]))]))}))

;; navigate the ACTIVE tab to uri (left-click / URI bar); creates the first tab if none. The leaving
;; scroll is saved into history; the new entry starts at the top.
(rf/reg-event-fx
 :tab/navigate
 [(rf/inject-cofx :content-scroll)]
 (fn [{:keys [db content-scroll]} [_ uri]]
   (let [db' (if (nav/active-tab db) (nav/nav-active db uri content-scroll) (nav/add-tab db uri))]
     (nav-result db' uri 0))))

;; open uri in a NEW tab (Ctrl+click) — save the current tab's scroll first
(rf/reg-event-fx
 :tab/open
 [(rf/inject-cofx :content-scroll)]
 (fn [{:keys [db content-scroll]} [_ uri]]
   (let [db' (nav/add-tab (nav/save-scroll db content-scroll) uri)]
     (nav-result db' uri 0))))

(rf/reg-event-fx
 :tab/new-blank
 [(rf/inject-cofx :content-scroll)]
 (fn [{:keys [db content-scroll]} _]
   (let [db' (nav/add-tab (nav/save-scroll db content-scroll) nil)]
     (with-retention {:db db'} db'))))

;; "Open" (left-click / context menu): focus an existing tab for uri (restoring its scroll), else navigate
(rf/reg-event-fx
 :doc/open
 [(rf/inject-cofx :content-scroll)]
 (fn [{:keys [db content-scroll]} [_ uri]]
   (if-let [t (nav/find-tab db uri)]
     (let [db' (nav/activate (nav/save-scroll db content-scroll) (:id t))]
       (with-retention
         {:db db' :fx (cond-> [] (uri/file-path uri) (conj [:scroll/restore (nav/cur-scroll db')]))}
         db'))
     (let [db' (if (nav/active-tab db) (nav/nav-active db uri content-scroll) (nav/add-tab db uri))]
       (nav-result db' uri 0)))))

;; "Open in new tab" (Ctrl+click / context menu): focus an existing tab for uri, else a new tab
(rf/reg-event-fx
 :doc/open-new
 [(rf/inject-cofx :content-scroll)]
 (fn [{:keys [db content-scroll]} [_ uri]]
   (if-let [t (nav/find-tab db uri)]
     (let [db' (nav/activate (nav/save-scroll db content-scroll) (:id t))]
       (with-retention
         {:db db' :fx (cond-> [] (uri/file-path uri) (conj [:scroll/restore (nav/cur-scroll db')]))}
         db'))
     (let [db' (nav/add-tab (nav/save-scroll db content-scroll) uri)]
       (nav-result db' uri 0)))))

;; switch tabs — save the leaving tab's scroll, restore the target tab's
(rf/reg-event-fx
 :tab/activate
 [(rf/inject-cofx :content-scroll)]
 (fn [{:keys [db content-scroll]} [_ id]]
   (let [db'    (nav/activate (nav/save-scroll db content-scroll) id)
         target (nav/active-uri db')]
     (with-retention
       {:db db' :fx (cond-> [] (uri/file-path target) (conj [:scroll/restore (nav/cur-scroll db')]))}
       db'))))

(rf/reg-event-fx
 :tab/duplicate
 [(rf/inject-cofx :content-scroll)]
 (fn [{:keys [db content-scroll]} [_ id]]
   (let [db'    (cond-> db (= id (nav/active-id db)) (nav/save-scroll content-scroll))
         db''   (nav/duplicate-tab db' id)
         target (nav/active-uri db'')]
     (with-retention
       {:db db''
        :fx (cond-> [] (and (not= db' db'') (uri/file-path target))
              (conj [:scroll/restore (nav/cur-scroll db'')]))}
       db''))))

(rf/reg-event-fx
 :tab/close
 (fn [{:keys [db]} [_ id]]
   (let [[db' _uri _still?] (nav/close db id)]
     (with-retention {:db db'} db'))))

(rf/reg-event-fx
 :tab/close-active
 (fn [{:keys [db]} _] (if-let [id (nav/active-id db)] {:fx [[:dispatch [:tab/close id]]]} {})))

(rf/reg-event-fx :tab/reload (fn [{:keys [db]} _] {:fx (load-fx (nav/active-uri db))}))

;; toggle a markdown tab (the active one, or a given id) between rendered + source — in the pane, the
;; content text is already cached, so no window replacement
(rf/reg-event-db :tab/toggle-source
                 (fn [db [_ id]] (if id (nav/toggle-source db id) (nav/toggle-source db))))

;; Document↔PDF representation switch (a doc with a collocated sibling PDF). Setting :pdf ensures the sibling's
;; bytes are cached (byte-only; no tab) so pdf-view can mount them in-place.
(rf/reg-event-fx :tab/set-representation
                 (fn [{:keys [db]} [_ id rep]]
                   (let [db' (if id (nav/set-representation db id rep) (nav/set-representation db rep))
                         sib (ds/doc-attr (ds/snapshot) (nav/active-path db') :doc/pdf-sibling)]
                     (cond-> {:db db'}
                       (and (= rep :pdf) sib) (assoc :fx [[:pdf/ensure-sibling-bytes {:path sib}]])))))

;; a collocated sibling PDF's bytes finished loading into pdf-cache → record it so content-view can mount pdf-view
(rf/reg-event-db :pdf/sibling-ready
                 (fn [db [_ path]] (update-in db [:ui :pdf-sibling-loaded] (fnil conj #{}) path)))

;; flip the active doc's representation (:document ↔ :pdf) — the command-palette / keybinding entry. No-op unless
;; the doc has a collocated sibling PDF. Delegates to :tab/set-representation (which ensures the PDF bytes).
(rf/reg-event-fx :tab/toggle-representation
                 (fn [{:keys [db]} _]
                   (if (ds/doc-attr (ds/snapshot) (nav/active-path db) :doc/pdf-sibling)
                     (let [cur (or (nav/representation db) (get-in db [:ui :settings :collocated-default] :pdf))
                           nxt (if (= cur :pdf) :document :pdf)]
                       {:fx [[:dispatch [:tab/set-representation nil nxt]]]})
                     {})))

;; PDF→source: the "Doc" side of [Doc | PDF] on an opened PDF that is collocated with a previewable source. Unlike
;; the in-place source→PDF switch, here the PDF IS the tab's doc, so "Doc" NAVIGATES the tab to the rendered source
;; (which knows its own PDF sibling → "PDF" returns) and forces :document so the collocated-default :pdf pref, which
;; would otherwise bounce straight back to the PDF, does not win. Reuses the whole Document↔PDF machinery.
(rf/reg-event-fx :tab/open-representation-source
                 (fn [{:keys [db]} [_ id]]
                   (if-let [src (ds/doc-attr (ds/snapshot) (nav/active-path db) :doc/source-sibling)]
                     {:fx [[:dispatch [:tab/navigate src]]
                           [:dispatch [:tab/set-representation (or id (nav/active-id db)) :document]]]}
                     {})))

;; Diff unified⇄split view switch (a .diff/.patch doc). Selecting :split builds the side-by-side HTML the first
;; time (baseline immediately, then enriched with on-disk sources) — see the :diff/build-split fx.
(rf/reg-event-fx :tab/set-diff-view
                 (fn [{:keys [db]} [_ id view]]
                   (let [id    (or id (nav/active-id db))
                         db'   (nav/set-diff-view db id view)
                         path  (nav/active-path db')
                         snap  (ds/snapshot)
                         text  (ds/doc-attr snap path :doc/text)
                         built? (some? (ds/doc-attr snap path :doc/diff-split-html))]
                     (cond-> {:db db'}
                       (and (= view :split) text (not built?))
                       (assoc :fx [[:diff/build-split {:path path :text text}]])))))

;; flip the active diff's view (:unified ↔ :split) — the command-palette / keybinding entry. No-op unless the
;; active doc is a diff. Delegates to :tab/set-diff-view (which builds the split HTML on demand).
(rf/reg-event-fx :tab/toggle-diff-view
                 (fn [{:keys [db]} _]
                   (if (= "diff" (ds/doc-attr (ds/snapshot) (nav/active-path db) :doc/kind))
                     (let [nxt (if (= (nav/effective-diff-view (nav/diff-view db)) :split) :unified :split)]
                       {:fx [[:dispatch [:tab/set-diff-view nil nxt]]]})
                     {})))

;; the side-by-side (split) HTML for a diff finished building (baseline or on-disk-enriched) → store it on the doc
(rf/reg-event-fx :diff/split-ready
                 (fn [_ [_ path html]]
                   (let [snap (ds/snapshot)]
                     (when-let [eid (ds/eid-for-path snap path)]
                       {:fx [[:ds/transact [[:db/add eid :doc/diff-split-html html]]]]}))))

;; ── bidirectional source⇄preview jump ("Go to source" / "Go to preview" context-menu items + keymap) ──
;; The EVENT decides whether the pane must toggle (it knows the current view), and the FX either scrolls the
;; already-mounted view NOW or stashes the target line for the view that is about to mount (consumed across the
;; toggle-driven remount — mirrors renderer.scroll want!→apply!).
(rf/reg-event-fx
 :source/goto-line                                        ; preview → source
 (fn [{:keys [db]} [_ line]]
   (when (number? line)
     (if (nav/view-source? db)
       {:fx [[:source/scroll-line line]]}                 ; already source: scroll the live view now
       {:db  (nav/toggle-source db)                       ; entering source: defer until create-source-view mounts
        :fx  [[:source/want-line line]]}))))
(rf/reg-event-fx
 :preview/goto-line                                       ; source → preview
 (fn [{:keys [db]} [_ line]]
   (when (number? line)
     (if (nav/view-source? db)
       {:db  (nav/toggle-source db)                       ; leaving source: defer until the preview mounts
        :fx  [[:preview/want-line line]]}
       {:fx [[:preview/scroll-line line]]}))))            ; already preview: scroll now

;; keyboard / command-palette entry points (no click target): only fire in the meaningful direction, and only
;; for a previewable doc (markdown/org — the kinds that stamp data-vv-source-* and have both views).
(defn- previewable-doc? [db]
  (contains? #{"markdown" "org"} (:doc/kind (ds/active-doc (ds/snapshot) (nav/active-path db)))))
(rf/reg-event-fx
 :jump/goto-source                                        ; from preview → source (derives the viewport line)
 (fn [{:keys [db]} _]
   (when (and (not (nav/view-source? db)) (previewable-doc? db)) {:fx [[:jump/to-source-current nil]]})))
(rf/reg-event-fx
 :jump/goto-preview                                       ; from source → preview (derives the cursor line)
 (fn [{:keys [db]} _]
   (when (and (nav/view-source? db) (previewable-doc? db)) {:fx [[:jump/to-preview-current nil]]})))

;; drag-reorder: drop tab `from-id` before/after `to-id` (after? = cursor past the target's midpoint)
(rf/reg-event-db
 :tab/reorder
 (fn [db [_ from-id to-id after?]]
   (let [ts  (nav/tabs db)
         toi (first (keep-indexed #(when (= (:id %2) to-id) %1) ts))]
     (if toi (nav/reorder db from-id (+ toi (if after? 1 0))) db))))

;; tab drag insertion indicator: which tab the cursor is over + which side (before/after its midpoint)
(rf/reg-event-db :tab/drop-set   (fn [db [_ over after?]] (assoc-in db [:ui :tab-drop] {:over over :after? (boolean after?)})))
(rf/reg-event-db :tab/drop-clear (fn [db _] (assoc-in db [:ui :tab-drop] nil)))

(rf/reg-event-fx
 :tab/close-others
 (fn [{:keys [db]} [_ id]]
   {:fx (->> (nav/tabs db) (remove #(= (:id %) id)) (mapv (fn [t] [:dispatch [:tab/close (:id t)]])))}))

(rf/reg-event-fx
 :tab/close-right
 (fn [{:keys [db]} [_ id]]
   (let [ts  (vec (nav/tabs db))
         idx (first (keep-indexed #(when (= (:id %2) id) %1) ts))]
     {:fx (if idx (mapv (fn [t] [:dispatch [:tab/close (:id t)]]) (subvec ts (inc idx))) [])})))

;; back/forward act on the ACTIVE tab's own history (per-tab, browser-like) + restore that entry's scroll
(rf/reg-event-fx
 :history/back
 [(rf/inject-cofx :content-scroll)]
 (fn [{:keys [db content-scroll]} _]
   (if-let [[db' uri sc] (nav/step db -1 content-scroll)]
     (nav-result db' uri sc)
     {})))

(rf/reg-event-fx
 :history/forward
 [(rf/inject-cofx :content-scroll)]
 (fn [{:keys [db content-scroll]} _]
   (if-let [[db' uri sc] (nav/step db 1 content-scroll)]
     (nav-result db' uri sc)
     {})))

;; Alt+Up: navigate the active tab to the PARENT directory of the current file:// uri (no-op for http /
;; at the filesystem root). The came-from child is pre-highlighted so Alt+Down returns to it.
(rf/reg-event-fx
 :nav/parent
 [(rf/inject-cofx :content-scroll)]
 (fn [{:keys [db content-scroll]} _]
   (let [cur (nav/active-uri db)]
     (if-let [parent (uri/dirname cur)]
       (let [db' (-> (nav/nav-active db parent content-scroll)
                     (assoc-in [:ui :dir-selected] (uri/file-path cur)))]
         (nav-result db' parent 0))
       {}))))

;; Alt+Down: open the highlighted target of the active directory view (file → open; subdir → descend).
;; Inert unless a directory listing is showing.
(rf/reg-event-fx
 :nav/open-target
 (fn [{:keys [db]} _]
   (let [dir (nav/active-path db)
         doc (when dir (ds/active-doc (ds/snapshot) dir))]
     (if (= "directory" (:doc/kind doc))
       (if-let [sel (nav/effective-selected dir (:doc/entries doc)
                                            (get-in db [:ui :dir-selected])
                                            (get-in db [:ui :recent :trail]))]
         {:fx [[:dispatch [:doc/open sel]]]}
         {})
       {}))))

;; URI bar Enter: open a complete existing path (file or directory), else the most-likely prefix match,
;; else a non-intrusive inline error (no dialog). http(s) bypasses path completion.
(rf/reg-event-fx
 :uri/navigate
 (fn [{:keys [db]} [_ text]]
   (cond
     (str/blank? text) {}
     (uri/http? text)  {:fx [[:dispatch [:tab/navigate (uri/normalize text)]]]}
     :else
     (let [uc           (get-in db [:ui :uri-complete])
           [dir-part _] (uri/complete-split text)
           [last-dir _] (uri/complete-split (or (:input uc) ""))]
       (if (and (:input uc) (= dir-part last-dir))
         {:fx [[:dispatch [:uri-complete/decide-enter (assoc uc :input text)]]]}
         {:fx [[:vv/complete-path {:input text :tag :enter}]]})))))

;; ---- URI-bar path auto-completion ----
(defn- prefix-matches
  "Entries whose basename prefix-matches `base` (dotfiles hidden unless base starts with '.'), sorted
   dirs-first then by name (the directory browser's order)."
  [entries base]
  (nav/sort-entries (filter #(uri/matches-prefix? (:name %) base) entries)))

(def ^:private uri-complete-empty
  {:input nil :dir nil :entries [] :target nil :exists? false :dir? false
   :selected -1 :dismissed? false :error? false})

(rf/reg-event-db :uri-complete/clear       (fn [db _] (assoc-in db [:ui :uri-complete] uri-complete-empty)))
(rf/reg-event-db :uri-complete/set         (fn [db [_ m]] (update-in db [:ui :uri-complete] merge m)))
(rf/reg-event-db :uri-complete/clear-error (fn [db _] (assoc-in db [:ui :uri-complete :error?] false)))

;; move the dropdown selection (computed in the event, off the live :selected, so rapid ↑/↓ don't
;; both read a stale view-closure value); -1 = "none" → first ↓ lands on 0, first ↑ wraps to last
(rf/reg-event-db
 :uri-complete/move
 (fn [db [_ dir n]]
   (if (pos? n)
     (let [cur  (get-in db [:ui :uri-complete :selected])
           base (if (neg? cur) (if (pos? dir) -1 0) cur)]
       (-> db
           (assoc-in [:ui :uri-complete :selected] (mod (+ base dir) n))
           (assoc-in [:ui :uri-complete :dismissed?] false)))
     db)))

(rf/reg-event-fx
 :uri-complete/typed
 (fn [{:keys [db]} [_ text]]
   (if (or (str/blank? text) (uri/http? text))
     {:db (assoc-in db [:ui :uri-complete] uri-complete-empty)}
     {:db (update-in db [:ui :uri-complete] merge {:selected -1 :dismissed? false :error? false})
      :fx [[:vv/complete-path {:input text :tag :live}]]})))

(rf/reg-event-fx
 :uri-complete/decide-enter
 (fn [{:keys [db]} [_ {:keys [input entries exists? target]}]]
   (let [[_ base] (uri/complete-split (or input ""))
         ml       (first (prefix-matches entries base))]
     (cond
       exists? {:fx [[:dispatch [:tab/navigate target]] [:dispatch [:uri-complete/clear]]]}
       ml      {:fx [[:dispatch [:tab/navigate (:path ml)]] [:dispatch [:uri-complete/clear]]]}
       :else   {:db (assoc-in db [:ui :uri-complete :error?] true)
                :fx [[:uri-complete/error-timeout]]}))))

(rf/reg-event-fx
 :uri-complete/result
 (fn [{:keys [db]} [_ tag payload]]
   (if (= tag :enter)
     {:fx [[:dispatch [:uri-complete/decide-enter payload]]]}
     {:db (update-in db [:ui :uri-complete] merge
                     (-> (select-keys payload [:input :dir :entries :exists? :dir? :target])
                         (update :entries vec)))})))

;; ---- in-app HTTP web view ----
;; the web view navigated → record it onto the tab that OWNS the view (`tab`), NOT the active tab: the http
;; tab may have been switched away from (to a PDF/etc.) while the page was still loading, so applying it to
;; the active tab would hijack THAT tab. No-op if the owner tab was closed, or the url already equals its uri
;; (our own loadURL echoes did-navigate). The web view scrolls itself; we only record browser history.
(rf/reg-event-fx
 :http/navigated
 (fn [{:keys [db]} [_ {:keys [url tab]}]]
   (let [owner (some #(when (= (:id %) tab) %) (nav/tabs db))]
     (if (and url (uri/http? url) owner (not= url (:uri owner)))
       (let [db' (record-web-history (nav/nav-tab db tab url) url)]
         (with-retention {:db db' :fx [[:vv/save-recent (pr-str (get-in db' [:ui :recent]))]]} db'))
       {:db db}))))

;; the web view's heading outline (for the Contents/TOC tab — HTML sections, like Markdown)
(rf/reg-event-db :web/toc (fn [db [_ headings]] (assoc-in db [:ui :web-toc] (vec headings))))
;; scroll-spy active heading reported by the web view's preload
(rf/reg-event-db :web/active-heading (fn [db [_ id]] (assoc-in db [:ui :active-heading] id)))

(rf/reg-event-fx
 :theme/set
 (fn [{:keys [db]} [_ theme]]
   (let [settings (assoc (get-in db [:ui :settings]) :theme theme)]
     {:db (-> db (assoc-in [:ui :theme] theme) (assoc-in [:ui :settings] settings))
      :fx [[:theme/apply theme] [:vv/save-settings (pr-str settings)]]})))

;; multi-project file trees: accumulate one {:root :files} per git root (updated in place by root, so
;; re-opening a file from a known project refreshes its tree without reordering the sidebar)
(rf/reg-event-db
 :tree/received
 (fn [db [_ {:keys [root files]}]]
   (update-in db [:ui :projects]
              (fn [projects]
                (let [projects (vec projects)
                      idx      (first (keep-indexed #(when (= (:root %2) root) %1) projects))
                      entry    {:root root :files (vec files)}]
                  (if idx (assoc projects idx entry) (conj projects entry)))))))

(rf/reg-event-db
 :tree/filter
 (fn [db [_ q]] (assoc-in db [:ui :tree-filter] q)))

;; ---- in-page find ----
(rf/reg-event-fx
 :find/toggle
 (fn [{:keys [db]} _]
   (let [vis (not (get-in db [:ui :find :visible?]))]
     (cond-> {:db (assoc-in db [:ui :find :visible?] vis)}
       (not vis) (assoc :fx [[:find/clear]])))))

(rf/reg-event-fx
 :find/set-query
 (fn [{:keys [db]} [_ q]]
   {:db (assoc-in db [:ui :find :query] q)
    :fx [[:find/run q]]}))

(rf/reg-event-db
 :find/count
 (fn [db [_ n]] (-> db (assoc-in [:ui :find :count] n)
                    (assoc-in [:ui :find :idx] (if (pos? n) 1 0)))))

(rf/reg-event-db
 :find/idx
 (fn [db [_ i]] (assoc-in db [:ui :find :idx] i)))

(rf/reg-event-fx
 :find/cycle
 (fn [_ [_ dir]] {:fx [[:find/cycle dir]]}))

(rf/reg-event-fx
 :find/close
 (fn [{:keys [db]} _]
   {:db (assoc-in db [:ui :find :visible?] false)
    :fx [[:find/clear]]}))

;; ---- table of contents ----
;; jump to a section: in the web view (HTTP) ask its preload to scroll; in Markdown scroll the content
(rf/reg-event-fx
 :toc/goto
 (fn [{:keys [db]} [_ id]]
   (if (uri/http? (nav/active-uri db))
     {:fx [[:vv/http-toc-goto id]]}
     {:fx [[:toc/scroll id]]})))

(rf/reg-event-db
 :toc/active-heading
 (fn [db [_ id]] (assoc-in db [:ui :active-heading] id)))

;; ---- command-target events (the keybinding command registry dispatches these) ----
(def ^:private theme-cycle ["spacemacs-dark" "spacemacs-light"])

(rf/reg-event-db :tab/next (fn [db _] (if-let [id (nav/nth-id db 1)]  (nav/activate db id) db)))
(rf/reg-event-db :tab/prev (fn [db _] (if-let [id (nav/nth-id db -1)] (nav/activate db id) db)))

(rf/reg-event-fx
 :sidebar/toggle
 (fn [{:keys [db]} _]
   (let [vis      (not (get-in db [:ui :sidebar-visible?]))
         settings (assoc (get-in db [:ui :settings]) :sidebar-visible? vis)]
     {:db (-> db (assoc-in [:ui :sidebar-visible?] vis) (assoc-in [:ui :settings] settings))
      :fx [[:vv/save-settings (pr-str settings)]]})))

(rf/reg-event-db :sidebar/tab   (fn [db [_ tab]] (assoc-in db [:ui :sidebar-tab] tab)))
(rf/reg-event-fx
 :sidebar/width
 (fn [{:keys [db]} [_ w]]
   (let [w        (-> w (max 140) (min 720))
         settings (assoc (get-in db [:ui :settings]) :sidebar-width w)]
     {:db (-> db (assoc-in [:ui :sidebar-width] w) (assoc-in [:ui :settings] settings))
      :fx [[:vv/save-settings (pr-str settings)]]})))
;; show the Files tab (used by "Reveal in tree" + the directory context menu); the active file's ancestors
;; are auto-expanded by file-tree's reveal-active!
(rf/reg-event-db :sidebar/reveal
                 (fn [db _] (-> db (assoc-in [:ui :sidebar-visible?] true) (assoc-in [:ui :sidebar-tab] :files))))
(rf/reg-event-db :sidebar/show
                 (fn [db [_ tab]] (-> db (assoc-in [:ui :sidebar-visible?] true) (assoc-in [:ui :sidebar-tab] tab))))

;; ---- menu bar (custom, theme-matched) ----
(rf/reg-event-db :access-keys/set
                 (fn [db [_ active?]] (assoc-in db [:ui :access-keys-active?] (boolean active?))))
(rf/reg-event-db :menu/open
                 (fn [db [_ label]] (-> db
                                         (assoc-in [:ui :menu] label)
                                         (assoc-in [:ui :menu-submenu] nil)
                                         (assoc-in [:ui :menu-focus] nil)
                                         (assoc-in [:ui :menu-submenu-focus] nil))))
(rf/reg-event-db :menu/close
                 (fn [db _] (-> db
                                 (assoc-in [:ui :menu] nil)
                                 (assoc-in [:ui :menu-submenu] nil)
                                 (assoc-in [:ui :menu-focus] nil)
                                 (assoc-in [:ui :menu-submenu-focus] nil)
                                 (assoc-in [:ui :access-keys-active?] false))))
(rf/reg-event-db :menu/toggle
                 (fn [db [_ label]]
                   (if (= (get-in db [:ui :menu]) label)
                     (-> db
                         (assoc-in [:ui :menu] nil)
                         (assoc-in [:ui :menu-submenu] nil)
                         (assoc-in [:ui :menu-focus] nil)
                         (assoc-in [:ui :menu-submenu-focus] nil)
                         (assoc-in [:ui :access-keys-active?] false))
                     (-> db
                         (assoc-in [:ui :menu] label)
                         (assoc-in [:ui :menu-submenu] nil)
                         (assoc-in [:ui :menu-focus] nil)
                         (assoc-in [:ui :menu-submenu-focus] nil)))))
(rf/reg-event-db :menu/submenu
                 (fn [db [_ submenu]] (-> db
                                           (assoc-in [:ui :menu-submenu] submenu)
                                           (assoc-in [:ui :menu-submenu-focus] nil))))
(rf/reg-event-db :menu/focus
                 (fn [db [_ idx]] (assoc-in db [:ui :menu-focus] idx)))
(rf/reg-event-db :menu/submenu-focus
                 (fn [db [_ idx]] (assoc-in db [:ui :menu-submenu-focus] idx)))

;; ---- menu shell actions (cross the IPC seam to main) ----
(defn open-dialog-mode [mode]
  (if (= mode :new-tab) :new-tab :current))

(defn files-opened-fx
  "Dispatches for files chosen from the native Open dialog OR named on the command line. `focus-first?`
   (the command-line launch path) re-activates the FIRST path's tab once all have opened — the Open
   dialog leaves the last-opened tab active (default; a single/empty selection needs no re-activation)."
  ([mode paths] (files-opened-fx mode paths false))
  ([mode paths focus-first?]
   (let [paths (vec (or paths []))
         base  (case (open-dialog-mode mode)
                 :new-tab (mapv (fn [p] [:dispatch [:doc/open-new p]]) paths)
                 (case (count paths)
                   0 []
                   1 [[:dispatch [:doc/open (first paths)]]]
                   (vec (cons [:dispatch [:doc/open (first paths)]]
                              (map (fn [p] [:dispatch [:doc/open-new p]]) (rest paths))))))]
     (cond-> base
       (and focus-first? (> (count paths) 1)) (conj [:dispatch [:doc/open (first paths)]])))))

(rf/reg-event-fx
 :file/open-dialog
 (fn [{:keys [db]} [_ mode]]
   {:db (assoc-in db [:ui :open-dialog-mode] (open-dialog-mode mode))
    :fx [[:vv/open-dialog]]}))
(rf/reg-event-fx :app/quit         (fn [_ _] {:fx [[:vv/quit]]}))
(rf/reg-event-fx
 :view/zoom
 (fn [{:keys [db]} [_ dir]]
   ;; context-aware: PDF → in-renderer pdf scale; web tab → the native web view; else → app window
   (case (zoom/context db)
     :pdf    {:fx [[:dispatch [:pdf/zoom (case dir 1 :in -1 :out :reset)]]]}
     :web    {:fx [[:vv/http-zoom dir]]}
     :window {:fx [[:vv/zoom dir]]})))

(rf/reg-event-fx
 :view/zoom-set
 (fn [{:keys [db]} [_ pct]]
   ;; absolute zoom to `pct`% (zoom-bar input / preset), routed to the active surface
   (let [f (/ (max 10 (min 800 pct)) 100.0)]
     (case (zoom/context db)
       :pdf    {:db (-> db (assoc-in [:ui :pdf :scale] (pdf-layout/clamp-zoom f)) (assoc-in [:ui :pdf :fit] nil))}
       :web    {:fx [[:vv/http-zoom-set f]]}
       :window {:fx [[:vv/zoom-set f]]}))))

;; main reports the resolved app-window / web-view zoom factor so the bar shows the live %
(rf/reg-event-db
 :view/zoom-changed
 (fn [db [_ p]]
   (let [m (js->clj p :keywordize-keys true)]
     (assoc-in db [:ui (if (= "web" (:context m)) :web-zoom :window-zoom)] (or (:factor m) 1.0)))))

(rf/reg-event-fx :view/devtools    (fn [_ _] {:fx [[:vv/devtools]]}))

;; ---- in-renderer PDF view-state (zoom / fit / dark-invert); fit + invert persist in settings.edn ----
(rf/reg-event-fx
 :pdf/zoom
 (fn [{:keys [db]} [_ dir]]
   {:db (-> db
            (assoc-in [:ui :pdf :scale] (pdf-layout/zoom-step (get-in db [:ui :pdf :scale] 1.0) dir))
            (assoc-in [:ui :pdf :fit] nil))}))   ; an explicit zoom overrides the fit mode

;; the pdf engine reports its fit-resolved scale back so the zoom bar shows the live % even while fitting
;; (keeps :fit so the View ▸ Fit radio stays marked)
(rf/reg-event-db
 :pdf/scale-resolved
 (fn [db [_ scale]] (assoc-in db [:ui :pdf :scale] scale)))

(rf/reg-event-fx
 :pdf/fit
 (fn [{:keys [db]} [_ mode]]
   (let [settings (assoc (get-in db [:ui :settings]) :pdf-fit mode)]
     {:db (-> db (assoc-in [:ui :pdf :fit] mode) (assoc-in [:ui :settings] settings))
      :fx [[:vv/save-settings (pr-str settings)]]})))

(rf/reg-event-fx
 :pdf/invert-toggle
 (fn [{:keys [db]} _]
   (let [inv      (not (get-in db [:ui :pdf :invert?]))
         settings (assoc (get-in db [:ui :settings]) :pdf-invert? inv)]
     {:db (-> db (assoc-in [:ui :pdf :invert?] inv) (assoc-in [:ui :settings] settings))
      :fx [[:vv/save-settings (pr-str settings)]]})))

;; which representation a doc collocated with an exported PDF opens in by default (:pdf — the faithful compiler
;; output — or :document — the rendered preview). Persisted; a per-tab toggle can still override per document.
(rf/reg-event-fx
 :settings/set-collocated-default
 (fn [{:keys [db]} [_ mode]]
   (let [mode     (if (= mode :document) :document :pdf)
         settings (assoc (get-in db [:ui :settings]) :collocated-default mode)]
     {:db (assoc-in db [:ui :settings :collocated-default] mode)
      :fx [[:vv/save-settings (pr-str settings)]]})))

;; Opt-in PDF text reflow (ADR-0017): show the extracted text as reflowable prose instead of the fixed-layout
;; canvas. The canvas facet is untouched; enabling recomputes the reflow HTML for the active PDF.
(rf/reg-event-fx
 :pdf/reflow-toggle
 (fn [{:keys [db]} _]
   (let [on       (not (get-in db [:ui :pdf :reflow?]))
         settings (assoc (get-in db [:ui :settings]) :pdf-reflow? on)]
     {:db (-> db (assoc-in [:ui :pdf :reflow?] on) (assoc-in [:ui :settings] settings))
      :fx (cond-> [[:vv/save-settings (pr-str settings)]]
            on (conj [:pdf/reflow {}]))})))   ; the fx keys off the mounted PDF's own path

(rf/reg-event-fx
 :pdf/reflowed
 (fn [_ [_ path html]]
   (when-let [eid (ds/eid-for-path (ds/snapshot) path)]
     {:fx [[:ds/transact [[:db/add eid :doc/reflow-html html]]]]})))

(rf/reg-event-fx
 :pdf/outline
 (fn [_ [_ path toc]]
   (when-let [eid (ds/eid-for-path (ds/snapshot) path)]
     {:fx [[:ds/transact [[:db/add eid :doc/toc (vec toc)]]]]})))
(rf/reg-event-fx
 :view/re-frame-10x
 (fn [{:keys [db]} _]
   (let [open? (not (get-in db [:ui :re-frame-10x-open?]))]
     {:db (assoc-in db [:ui :re-frame-10x-open?] open?)
      :fx [[:devtools/re-frame-10x open?]]})))

(rf/reg-event-fx
 :view/re-frame-10x-hide
 (fn [{:keys [db]} _]
   {:db (assoc-in db [:ui :re-frame-10x-open?] false)
    :fx [[:devtools/re-frame-10x false]]}))

;; the Open dialog returns chosen paths; the pending mode decides current-tab vs new-tab handling.
(rf/reg-event-fx
 :files/opened
 (fn [{:keys [db]} [_ {:keys [paths focus-first]}]]
   (let [mode (get-in db [:ui :open-dialog-mode])
         fx   (files-opened-fx mode paths focus-first)]
     (cond-> {:db (assoc-in db [:ui :open-dialog-mode] :current)}
       (seq fx) (assoc :fx fx)))))

;; ---- Preferences / settings (theme persists via :theme/set; fonts via :settings/set) ----
(rf/reg-event-db :settings/open  (fn [db _] (assoc-in db [:ui :settings-open?] true)))
(rf/reg-event-db :settings/close (fn [db _] (assoc-in db [:ui :settings-open?] false)))

;; persisted settings arrived from main (EDN text) → merge + apply theme + fonts
(rf/reg-event-fx
 :settings/received
 (fn [{:keys [db]} [_ text]]
   (let [s        (when (and (string? text) (seq (str/trim text)))
                    (try (reader/read-string text) (catch :default _ nil)))
         settings (merge (get-in db [:ui :settings]) s)]
     {:db (cond-> (assoc-in db [:ui :settings] settings)
            (:theme s)                      (assoc-in [:ui :theme] (:theme s))
            (contains? s :sidebar-visible?) (assoc-in [:ui :sidebar-visible?] (:sidebar-visible? s))
            (:sidebar-width s)              (assoc-in [:ui :sidebar-width] (:sidebar-width s))
            (:pdf-fit s)                    (assoc-in [:ui :pdf :fit] (:pdf-fit s))
            (contains? s :pdf-invert?)      (assoc-in [:ui :pdf :invert?] (:pdf-invert? s))
            (contains? s :pdf-reflow?)      (assoc-in [:ui :pdf :reflow?] (:pdf-reflow? s)))
      :fx (cond-> [[:fonts/apply settings]]
            (:theme s) (conj [:theme/apply (:theme s)]))})))

;; change one setting (a font family/size) → apply + persist
(rf/reg-event-fx
 :settings/set
 (fn [{:keys [db]} [_ k v]]
   (let [settings (assoc (get-in db [:ui :settings]) k v)]
     {:db (assoc-in db [:ui :settings] settings)
      :fx [[:fonts/apply settings] [:vv/save-settings (pr-str settings)]]})))

;; ---- recent navigation memory (recent.edn): dir→child trail + recent-files MRU ----
(rf/reg-event-db
 :recent/received
 (fn [db [_ text]]
   (let [r (when (and (string? text) (seq (str/trim text)))
             (try (reader/read-string text) (catch :default _ nil)))]
     (cond-> db
       (map? r) (assoc-in [:ui :recent] (merge {:trail {} :recent-files [] :web-history []} r))))))

;; File ▸ Open Recent ▸ Clear Recent
(rf/reg-event-fx
 :recent/clear
 (fn [{:keys [db]} _]
   (let [recent (assoc (get-in db [:ui :recent]) :recent-files [])]
     {:db (assoc-in db [:ui :recent] recent)
      :fx [[:vv/save-recent (pr-str recent)]]})))

;; ---- extensions + ad-blocking ----
(defn- ext-config-edn
  "Serialize the persisted extension/ad-block prefs (extensions.edn) from app-db."
  [db]
  (pr-str {:adblock    (select-keys (get-in db [:ui :adblock]) [:enabled? :lists :last-updated :update-every-hours])
           :extensions {:enabled?     (get-in db [:ui :extensions :enabled?])
                        :disabled-ids (->> (get-in db [:ui :extensions :installed])
                                           (remove :enabled?) (map :id) set)}}))

(rf/reg-event-db :extensions/open  (fn [db _] (assoc-in db [:ui :extensions-open?] true)))
(rf/reg-event-db :extensions/close (fn [db _] (assoc-in db [:ui :extensions-open?] false)))

(rf/reg-event-db
 :ext-config/received
 (fn [db [_ text]]
   (let [r (when (and (string? text) (seq (str/trim text)))
             (try (reader/read-string text) (catch :default _ nil)))]
     (cond-> db
       (map? (:adblock r))    (update-in [:ui :adblock] merge (:adblock r))
       (contains? (:extensions r) :enabled?) (assoc-in [:ui :extensions :enabled?] (get-in r [:extensions :enabled?]))))))

(rf/reg-event-db
 :ext/state-received
 (fn [db [_ text]]
   (let [r (when (and (string? text) (seq (str/trim text)))
             (try (reader/read-string text) (catch :default _ nil)))]
     (cond-> db
       (map? r) (update-in [:ui :extensions] merge (select-keys r [:enabled? :installed]))))))

(rf/reg-event-db :ext/install-result (fn [db [_ p]] (assoc-in db [:ui :extensions :install-status] (js->clj p :keywordize-keys true))))
(rf/reg-event-db :ext/update-result  (fn [db [_ p]] (assoc-in db [:ui :extensions :update-status]  (js->clj p :keywordize-keys true))))

(rf/reg-event-fx
 :adblock/status-received
 (fn [{:keys [db]} [_ p]]
   (let [m   (js->clj p :keywordize-keys true)
         db' (cond-> (assoc-in db [:ui :adblock :status] (keyword (:status m)))   ; clj->js stringified the kw
               (:last-updated m) (assoc-in [:ui :adblock :last-updated] (:last-updated m)))]
     ;; persist only when a real update landed (:last-updated present); ext-config-edn filters out the
     ;; transient :status so "updating" can never leak into extensions.edn
     (cond-> {:db db'}
       (:last-updated m) (assoc :fx [[:vv/save-ext-config (ext-config-edn db')]])))))

(rf/reg-event-fx :extensions/install        (fn [_ [_ s]]  {:fx [[:vv/ext-install s]]}))
(rf/reg-event-fx :extensions/remove         (fn [_ [_ id]] {:fx [[:vv/ext-remove id]]}))
(rf/reg-event-fx :extensions/check-updates  (fn [_ _]      {:fx [[:vv/ext-check-updates]]}))
(rf/reg-event-fx :extensions/action-clicked (fn [_ [_ id popup bounds]] {:fx [[:vv/ext-action-clicked {:id id :popup popup :bounds bounds}]]}))
(rf/reg-event-fx :extensions/popup-close    (fn [_ _]      {:fx [[:vv/ext-popup-close]]}))
(rf/reg-event-fx :adblock/refresh           (fn [_ _]      {:fx [[:vv/adblock-refresh]]}))

(rf/reg-event-fx
 :extensions/set-enabled
 (fn [{:keys [db]} [_ id on?]]
   (let [db' (update-in db [:ui :extensions :installed]
                        (fn [xs] (mapv (fn [x] (if (= (:id x) id) (assoc x :enabled? on?) x)) xs)))]
     {:db db' :fx [[:vv/ext-set-enabled {:id id :on on?}] [:vv/save-ext-config (ext-config-edn db')]]})))

(rf/reg-event-fx
 :extensions/toggle
 (fn [{:keys [db]} _]
   (let [db' (update-in db [:ui :extensions :enabled?] not)]
     {:db db' :fx [[:vv/save-ext-config (ext-config-edn db')]]})))

(rf/reg-event-fx
 :adblock/toggle
 (fn [{:keys [db]} _]
   (let [on (not (get-in db [:ui :adblock :enabled?]))
         db' (assoc-in db [:ui :adblock :enabled?] on)]
     {:db db' :fx [[:vv/adblock-set-enabled on] [:vv/save-ext-config (ext-config-edn db')]]})))

(rf/reg-event-fx
 :adblock/set-lists
 (fn [{:keys [db]} [_ kw]]
   (let [db' (assoc-in db [:ui :adblock :lists] kw)]
     {:db db' :fx [[:vv/adblock-set-lists kw] [:vv/save-ext-config (ext-config-edn db')]]})))

;; ---- native password-manager bridge ----
(rf/reg-event-fx
 :passwords/open
 (fn [{:keys [db]} _]
   (let [active-uri (nav/active-uri db)]
     {:db (-> db
              (assoc-in [:ui :passwords :open?] true)
              (assoc-in [:ui :passwords :result] nil)
              (assoc-in [:ui :passwords :error] nil))
      :fx (cond-> [[:vv/password-state nil]]
            (uri/http? active-uri) (conj [:vv/password-search active-uri]))})))

(rf/reg-event-db :passwords/close (fn [db _] (assoc-in db [:ui :passwords :open?] false)))

(rf/reg-event-fx
 :passwords/retry
 (fn [{:keys [db]} _]
   (let [active-uri (nav/active-uri db)]
     {:db (assoc-in db [:ui :passwords :result] nil)
      :fx (cond-> [[:vv/password-state nil]]
            (uri/http? active-uri) (conj [:vv/password-search active-uri]))})))

(rf/reg-event-db
 :passwords/state-received
 (fn [db [_ p]]
   (let [m (js->clj p :keywordize-keys true)]
     (update-in db [:ui :passwords] merge
                (select-keys m [:providers :forms :busy? :error])))))

(rf/reg-event-db
 :passwords/items-received
 (fn [db [_ p]]
   (let [m (js->clj p :keywordize-keys true)]
     (-> db
         (assoc-in [:ui :passwords :items] (vec (:items m)))
         (assoc-in [:ui :passwords :busy?] false)))))

(rf/reg-event-db
 :passwords/result-received
 (fn [db [_ p]]
   (let [m (js->clj p :keywordize-keys true)
         ok? (boolean (:ok m))]
     (cond-> (assoc-in db [:ui :passwords :result] m)
       (and ok? (= "fill" (:action m))) (assoc-in [:ui :passwords :open?] false)
       (and ok? (= "save" (:action m))) (assoc-in [:ui :passwords :save-prompt] nil)))))

(rf/reg-event-db
 :passwords/save-prompt
 (fn [db [_ p]]
   (assoc-in db [:ui :passwords :save-prompt]
             (when p (js->clj p :keywordize-keys true)))))

(rf/reg-event-fx :passwords/fill (fn [_ [_ item]] {:fx [[:vv/password-fill item]]}))

(rf/reg-event-fx
 :passwords/save
 (fn [_ [_ token provider]]
   {:fx [[:vv/password-save {:token token :provider provider}]]}))

(rf/reg-event-fx
 :passwords/dismiss-save
 (fn [{:keys [db]} [_ token]]
   {:db (assoc-in db [:ui :passwords :save-prompt] nil)
    :fx [[:vv/password-dismiss-save token]]}))

;; ---- About dialog ----
(rf/reg-event-db :about/open       (fn [db _] (assoc-in db [:ui :about-open?] true)))
(rf/reg-event-db :about/close      (fn [db _] (assoc-in db [:ui :about-open?] false)))
(rf/reg-event-db :app-info/received (fn [db [_ info]] (assoc-in db [:ui :app-info] info)))

;; ---- context menu + clipboard / shell openers ----
(rf/reg-event-db :context-menu/show  (fn [db [_ m]] (assoc-in db [:ui :context-menu] m)))
(rf/reg-event-db :context-menu/close (fn [db _]     (assoc-in db [:ui :context-menu] nil)))
(rf/reg-event-db :ui/hover-link      (fn [db [_ uri]] (assoc-in db [:ui :hover-link] uri)))
(rf/reg-event-db :ui/set-ctrl-held   (fn [db [_ held?]] (assoc-in db [:ui :ctrl-held?] (boolean held?))))
(rf/reg-event-fx :clipboard/copy     (fn [_ [_ text]] {:fx [[:vv/copy text]]}))
(rf/reg-event-fx :shell/open-path     (fn [_ [_ path]] {:fx [[:vv/open-path path]]}))
(rf/reg-event-fx :shell/open-external (fn [_ [_ url]]  {:fx [[:vv/open-external url]]}))

(rf/reg-event-fx
 :theme/cycle
 (fn [{:keys [db]} _]
   (let [cur (get-in db [:ui :theme])
         idx (or (first (keep-indexed #(when (= %2 cur) %1) theme-cycle)) 0)
         nxt (nth theme-cycle (mod (inc idx) (count theme-cycle)))]
     {:fx [[:dispatch [:theme/set nxt]]]})))

(rf/reg-event-fx :nav/focus  (fn [_ [_ target]] {:fx [[:dom/focus target]]}))
(rf/reg-event-fx :nav/scroll (fn [_ [_ opts]]   {:fx [[:dom/scroll opts]]}))

;; "open in tab": new? → a new tab, else navigate the active tab (both focus an existing tab for the path)
(rf/reg-event-fx :doc/open-in-tab
                 (fn [_ [_ path new?]] {:fx [[:dispatch [(if new? :doc/open-new :doc/open) path]]]}))

(defn- visible-tree-paths [db]
  (let [projects (get-in db [:ui :projects])
        q        (some-> (get-in db [:ui :tree-filter]) str/trim str/lower-case not-empty)]
    (vec (mapcat (fn [{:keys [root files]}]
                   (->> files
                        (filter #(or (nil? q) (str/includes? (str/lower-case %) q)))
                        (map #(str root "/" %))))
                 projects))))

(rf/reg-event-db
 :tree/move
 (fn [db [_ dir]]
   (let [paths (visible-tree-paths db) n (count paths)]
     (if (pos? n)
       (let [cur (get-in db [:ui :tree-selected])
             idx (or (first (keep-indexed #(when (= %2 cur) %1) paths)) -1)]
         (assoc-in db [:ui :tree-selected] (nth paths (mod (+ idx dir) n))))
       db))))

(rf/reg-event-fx
 :tree/activate
 (fn [{:keys [db]} _]
   (when-let [sel (get-in db [:ui :tree-selected])] {:fx [[:dispatch [:doc/open sel]]]})))

;; ---- in-pane directory browser ----
;; highlight an entry (click); Alt+Down / Enter (:nav/open-target) opens whatever is highlighted
(rf/reg-event-db :dir/select (fn [db [_ path]] (assoc-in db [:ui :dir-selected] path)))

;; ---- Vimium-style link hints (f) ----
(rf/reg-event-fx :hint/start (fn [_ _] {:fx [[:hints/collect]]}))

(rf/reg-event-db :hints/activate
                 (fn [db [_ targets]]
                   (if (seq targets)
                     (assoc-in db [:ui :hints] {:active? true :targets targets :typed ""})
                     db)))

(rf/reg-event-db :hints/cancel    (fn [db _] (assoc-in db [:ui :hints] {:active? false :targets [] :typed ""})))
(rf/reg-event-db :hints/backspace (fn [db _] (update-in db [:ui :hints :typed] #(subs % 0 (max 0 (dec (count %)))))))

;; type a label char: a single remaining match (even a unique prefix) activates; no match cancels
(rf/reg-event-fx
 :hints/type
 (fn [{:keys [db]} [_ ch]]
   (let [typed   (str (get-in db [:ui :hints :typed]) (str/upper-case ch))
         targets (get-in db [:ui :hints :targets])
         matches (filter #(str/starts-with? (:label %) typed) targets)]
     (cond
       (= 1 (count matches)) {:db (assoc-in db [:ui :hints] {:active? false :targets [] :typed ""})
                              :fx [[:hints/follow (first matches)]]}
       (empty? matches)      {:db (assoc-in db [:ui :hints] {:active? false :targets [] :typed ""})}
       :else                 {:db (assoc-in db [:ui :hints :typed] typed)}))))
