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
            [vinary.app.nav :as nav]
            [vinary.app.uri :as uri]
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

(rf/reg-event-fx
 :content/received
 (fn [{:keys [db]} [_ {:keys [path kind text html]}]]
   (let [snap    (ds/snapshot)
         eid     (ds/eid-for-path snap path)
         cur-err (and eid (ds/doc-attr snap path :doc/error))
         ;; DataScript is the content cache keyed by :doc/path; absence = "no value" (it rejects nil).
         ;; Synchronous html (text/diagram) goes straight in; markdown's html arrives async (:content/rendered).
         attrs   (cond-> {:doc/kind kind}
                   text            (assoc :doc/text text)
                   html            (assoc :doc/html html)                ; diagram: pre-rendered SVG
                   (= kind "text") (assoc :doc/html (plain-html text)))  ; plain text
         ;; update by :db/id when cached, create by :doc/path otherwise — the :doc/path upsert/lookup-ref
         ;; does not resolve under :advanced compilation.
         base    (if eid (assoc attrs :db/id eid) (assoc attrs :doc/path path))
         tx      (cond-> [base] cur-err (conj [:db/retract eid :doc/error cur-err]))]
     ;; the CLI/initial file arrives before any tab exists → it opens the first tab
     {:db (if (empty? (nav/tabs db)) (nav/add-tab db path) db)
      :fx (cond-> [[:ds/transact tx]]
            (= kind "markdown") (conj [:markdown/render {:text text :path path :on-done [:content/rendered path]}]))})))

(rf/reg-event-fx
 :content/rendered
 (fn [_ [_ path html]]
   (when-let [eid (ds/eid-for-path (ds/snapshot) path)]
     {:fx [[:ds/transact [[:db/add eid :doc/html html]]]]})))   ; add by entity-id, not :doc/path upsert

(rf/reg-event-fx
 :content/error
 (fn [_ [_ {:keys [path message]}]]
   (if-let [eid (and path (ds/eid-for-path (ds/snapshot) path))]
     {:fx [[:ds/transact [[:db/add eid :doc/error message]]]]}
     {})))

;; navigate the ACTIVE tab to uri (left-click / URI bar); creates the first tab if none
(rf/reg-event-fx
 :tab/navigate
 (fn [{:keys [db]} [_ uri]]
   {:db (if (nav/active-tab db) (nav/nav-active db uri) (nav/add-tab db uri)) :fx (load-fx uri)}))

;; open uri in a NEW tab (Ctrl+click)
(rf/reg-event-fx
 :tab/open
 (fn [{:keys [db]} [_ uri]] {:db (nav/add-tab db uri) :fx (load-fx uri)}))

;; "Open" (left-click / context menu): focus an existing tab for uri, else navigate the active tab
(rf/reg-event-fx
 :doc/open
 (fn [{:keys [db]} [_ uri]]
   (if-let [t (nav/find-tab db uri)]
     {:db (nav/activate db (:id t))}
     {:db (if (nav/active-tab db) (nav/nav-active db uri) (nav/add-tab db uri)) :fx (load-fx uri)})))

;; "Open in new tab" (Ctrl+click / context menu): focus an existing tab for uri, else a new tab
(rf/reg-event-fx
 :doc/open-new
 (fn [{:keys [db]} [_ uri]]
   (if-let [t (nav/find-tab db uri)]
     {:db (nav/activate db (:id t))}
     {:db (nav/add-tab db uri) :fx (load-fx uri)})))

(rf/reg-event-db :tab/activate (fn [db [_ id]] (nav/activate db id)))

(rf/reg-event-fx
 :tab/close
 (fn [{:keys [db]} [_ id]]
   (let [[db' uri still?] (nav/close db id)]
     {:db db'
      ;; stop watching the closed uri only if no remaining tab still shows it (content stays cached)
      :fx (cond-> [] (and uri (not still?) (uri/file-path uri)) (conj [:vv/close (uri/file-path uri)]))})))

(rf/reg-event-fx
 :tab/close-active
 (fn [{:keys [db]} _] (if-let [id (nav/active-id db)] {:fx [[:dispatch [:tab/close id]]]} {})))

(rf/reg-event-fx :tab/reload (fn [{:keys [db]} _] {:fx (load-fx (nav/active-uri db))}))

;; back/forward act on the ACTIVE tab's own history (per-tab, browser-like)
(rf/reg-event-fx
 :history/back
 (fn [{:keys [db]} _] (if-let [[db' uri] (nav/step db -1)] {:db db' :fx (load-fx uri)} {})))

(rf/reg-event-fx
 :history/forward
 (fn [{:keys [db]} _] (if-let [[db' uri] (nav/step db 1)] {:db db' :fx (load-fx uri)} {})))

;; URI bar: parse the typed text (http kept; file:// stripped; else a path) + navigate the active tab
(rf/reg-event-fx
 :uri/navigate
 (fn [_ [_ text]]
   (if-let [uri (uri/normalize text)] {:fx [[:dispatch [:tab/navigate uri]]]} {})))

;; ---- in-app HTTP web view ----
;; the web view navigated (a link clicked on the remote page) → sync the active tab's URI + push history
;; (our own loadURL also fires this, but url then equals the active uri → no-op)
(rf/reg-event-db
 :http/navigated
 (fn [db [_ {:keys [url]}]]
   (if (and url (uri/http? url) (not= url (nav/active-uri db)))
     (nav/nav-active db url)
     db)))

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

(rf/reg-event-db
 :sidebar/toggle
 (fn [db _] (update-in db [:ui :sidebar-visible?] not)))

(rf/reg-event-db :sidebar/tab   (fn [db [_ tab]] (assoc-in db [:ui :sidebar-tab] tab)))
(rf/reg-event-db :sidebar/width (fn [db [_ w]]   (assoc-in db [:ui :sidebar-width] (-> w (max 140) (min 720)))))
;; show the Files tab (used by "Reveal in tree" + the directory context menu); the active file's ancestors
;; are auto-expanded by file-tree's reveal-active!
(rf/reg-event-db :sidebar/reveal
                 (fn [db _] (-> db (assoc-in [:ui :sidebar-visible?] true) (assoc-in [:ui :sidebar-tab] :files))))
(rf/reg-event-db :sidebar/show
                 (fn [db [_ tab]] (-> db (assoc-in [:ui :sidebar-visible?] true) (assoc-in [:ui :sidebar-tab] tab))))

;; ---- menu bar (custom, theme-matched) ----
(rf/reg-event-db :menu/open   (fn [db [_ label]] (assoc-in db [:ui :menu] label)))
(rf/reg-event-db :menu/close  (fn [db _]         (assoc-in db [:ui :menu] nil)))
(rf/reg-event-db :menu/toggle (fn [db [_ label]] (update-in db [:ui :menu] #(if (= % label) nil label))))

;; ---- menu shell actions (cross the IPC seam to main) ----
(rf/reg-event-fx :file/open-dialog (fn [_ _] {:fx [[:vv/open-dialog]]}))
(rf/reg-event-fx :app/quit         (fn [_ _] {:fx [[:vv/quit]]}))
(rf/reg-event-fx :view/zoom        (fn [_ [_ dir]] {:fx [[:vv/zoom dir]]}))
(rf/reg-event-fx :view/devtools    (fn [_ _] {:fx [[:vv/devtools]]}))

;; the Open dialog returns the chosen paths: one → current tab; several → one new tab each
(rf/reg-event-fx
 :files/opened
 (fn [_ [_ {:keys [paths]}]]
   (let [paths (vec paths)]
     (case (count paths)
       0 {}
       1 {:fx [[:dispatch [:doc/open (first paths)]]]}
       {:fx (mapv (fn [p] [:dispatch [:doc/open-new p]]) paths)}))))

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
            (:theme s) (assoc-in [:ui :theme] (:theme s)))
      :fx (cond-> [[:fonts/apply settings]]
            (:theme s) (conj [:theme/apply (:theme s)]))})))

;; change one setting (a font family/size) → apply + persist
(rf/reg-event-fx
 :settings/set
 (fn [{:keys [db]} [_ k v]]
   (let [settings (assoc (get-in db [:ui :settings]) k v)]
     {:db (assoc-in db [:ui :settings] settings)
      :fx [[:fonts/apply settings] [:vv/save-settings (pr-str settings)]]})))

;; ---- About dialog ----
(rf/reg-event-db :about/open       (fn [db _] (assoc-in db [:ui :about-open?] true)))
(rf/reg-event-db :about/close      (fn [db _] (assoc-in db [:ui :about-open?] false)))
(rf/reg-event-db :app-info/received (fn [db [_ info]] (assoc-in db [:ui :app-info] info)))

;; ---- context menu + clipboard / shell openers ----
(rf/reg-event-db :context-menu/show  (fn [db [_ m]] (assoc-in db [:ui :context-menu] m)))
(rf/reg-event-db :context-menu/close (fn [db _]     (assoc-in db [:ui :context-menu] nil)))
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
