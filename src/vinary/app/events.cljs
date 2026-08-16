(ns vinary.app.events
  "re-frame events. Content arriving from main (live, on every file change) is transacted into
   DataScript; Markdown is rendered via the :markdown/render fx and the HTML comes back on
   :content/rendered. Tab open/activate/close drive the multi-tab model. A content update never
   touches scroll/UI state (that's in app-db) — that's how live-refresh preserves where you are."
  (:require [re-frame.core :as rf]
            [clojure.set :as set]
            [clojure.string :as str]
            [goog.string :as gstr]
            [cljs.reader :as reader]
            [vinary.app.db :as db]
            [vinary.app.ds :as ds]
            [vinary.app.facet :as facet]
            [vinary.stream.flag :as stream-flag]
            [vinary.app.nav :as nav]
            [vinary.app.projects :as projects]
            [vinary.app.commits :as commits]
            [vinary.git.graph :as graph]
            [vinary.git.graph-geometry :as ggeo]
            [vinary.app.tree-state :as tree-state]
            [vinary.app.uri :as uri]
            [vinary.app.zoom :as zoom]
            [vinary.renderer.pdf-layout :as pdf-layout]
            [vinary.app.fx]
            [vinary.input.fx]))

(rf/reg-event-db :db/init    (fn [_ _] db/default-db))
(rf/reg-event-db :ds/changed (fn [db _] (update db :ds/rev inc)))

;; ---- the browser-tab model (transforms live in vinary.app.nav; tabs = views, DataScript caches content) ----
(defn- conj-some
  "conj x onto coll only when x is non-nil — so a cond-> step can contribute an OPTIONAL fx (e.g. a facet-aware
   position restore that is nil when a source entry captured no line)."
  [coll x]
  (if (some? x) (conj coll x) coll))

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
      true     (conj [:pdf/evict retained])       ; evict cached PDF bytes for retired docs
      true     (conj [:render-cache/retain-only retained])
      ;; ADR-0038: transient Files-tree extras (adopted stdin diffs) die with retention, exactly when
      ;; main unlinks their spill. Conditional so the common no-extras case dispatches nothing.
      (some (comp seq :extras) (get-in db [:ui :projects]))
      (conj [:dispatch [:tree/prune-extras retained]]))))

(defn- with-retention [result db]
  (update result :fx #(into (vec (or % [])) (retention-fx db))))

(defn active-tab-focus-target
  "The keyboard owner for an explicitly foregrounded tab. A nil URI is a genuine blank tab even when the
   URI component is holding an unfinished per-tab draft; every non-nil URI belongs to the document surface."
  [db]
  (when (nav/active-tab db)
    (if (nil? (nav/active-uri db)) :uri :content)))

(defn- with-active-tab-focus [result db]
  (if-let [target (active-tab-focus-target db)]
    (update result :fx #(conj (vec (or % [])) [:dom/focus target]))
    result))

(rf/reg-event-db
 :tree/prune-extras
 (fn [db [_ retained]]
   (update-in db [:ui :projects] projects/prune-extras (set retained))))

;; ══ the Commits surfaces (ADR-0039) ═════════════════════════════════════════════════════════════
;; Per-repo state lives under [:ui :commits :repos <root>]. The lane fold (:graph) is stored
;; INCREMENTALLY on every received page (R2) so the sidebar rail and the Commit Graph document read
;; one source of truth; stale async replies are dropped by generation (:gen), the :find pattern.

(def ^:private commits-page 250)

(defn- commits-path [root & ks] (into [:ui :commits :repos root] ks))

;; The window's watched-repo set is the UNION of the mounted surfaces' interests — the sidebar
;; panel and the Commit Graph document each own one slot, so either unmounting never silently
;; releases the other's `.git` watcher (vv:git-watch REPLACES the window's set).
(rf/reg-event-fx
 :commits/sync-watch
 (fn [{:keys [db]} _]
   {:fx [[:vv/git-watch (into [] (distinct (keep val (get-in db [:ui :commits :watch-owners]))))]]}))

(rf/reg-event-fx
 :commits/shown
 (fn [{:keys [db]} [_ root]]
   (when root
     {:db (-> db
              (assoc-in [:ui :commits :last-root] root)
              (assoc-in [:ui :commits :watch-owners :panel] root))
      :fx [[:dispatch [:commits/sync-watch]]
           [:dispatch [:commits/ensure root]]]})))

(rf/reg-event-fx
 :commits/hidden
 (fn [{:keys [db]} _]
   {:db (assoc-in db [:ui :commits :watch-owners :panel] nil)
    :fx [[:dispatch [:commits/sync-watch]]]}))

(rf/reg-event-fx
 :commits/set-root
 (fn [{:keys [db]} [_ root]]
   {:db (-> db
            (assoc-in [:ui :commits :root] root)   ; a non-nil pin IS "stop following the active doc"
            (assoc-in [:ui :commits :watch-owners :panel] root))
    :fx [[:dispatch [:commits/sync-watch]]
         [:dispatch [:commits/ensure root]]]}))

(rf/reg-event-fx
 :commits/ensure
 (fn [{:keys [db]} [_ root]]
   (when root
     (let [repo (get-in db (commits-path root))]
       {:db (update-in db (commits-path root) #(or % {}))
        :fx (cond-> []
              (nil? (:branches repo))
              (conj [:vv/git-branches {:root root
                                       :on-done  [:commits/branches-received root]
                                       :on-error [:commits/log-error root]}])
              (and (empty? (:commits repo)) (not (:loading? repo)))
              (conj [:dispatch [:commits/load root {:skip 0}]]))}))))

(rf/reg-event-fx
 :commits/load
 (fn [{:keys [db]} [_ root {:keys [skip]}]]
   (let [repo (get-in db (commits-path root))
         gen  (inc (long (or (:gen repo) 0)))]
     {:db (-> db
              (assoc-in (commits-path root :loading?) true)
              (assoc-in (commits-path root :gen) gen))
      :fx [[:vv/git-log (let [hist (commits/history-args {:mode   (:mode repo)
                                                          :target (:history-target repo)})]
                          (cond-> {:root root :skip (long (or skip 0)) :limit commits-page
                                   :on-done  [:commits/log-received root gen (zero? (long (or skip 0)))]
                                   :on-error [:commits/log-error root]}
                            hist (merge hist)
                            ;; a ref applies only to the plain branch log — history follows HEAD
                            (and (nil? hist) (:ref repo)) (assoc :ref (:ref repo))))]]})))

(rf/reg-event-fx
 :commits/load-more
 (fn [{:keys [db]} [_ root]]
   (let [{:keys [loading? exhausted? commits]} (get-in db (commits-path root))]
     (when-not (or loading? exhausted?)
       {:fx [[:dispatch [:commits/load root {:skip (count commits)}]]]}))))

(rf/reg-event-db
 :commits/log-received
 (fn [db [_ root gen page0? {:keys [commits exhausted empty error]}]]
   (if (not= gen (get-in db (commits-path root :gen)))
     db                                                    ; superseded request → drop the reply
     (if error
       (update-in db (commits-path root) merge {:loading? false :error error})
       (let [prev  (get-in db (commits-path root))
             all   (if page0? (vec commits) (into (vec (:commits prev)) commits))
             state (if page0? (graph/init-state) (get-in prev [:graph :state] (graph/init-state)))
             ;; a HISTORY listing is non-contiguous — lanes would lie, so the fold stays empty and
             ;; both surfaces draw dots-only rows for it
             fold  (if (contains? #{:file-history :line-history} (:mode prev))
                     {:rows [] :state (graph/init-state)}
                     (graph/assign state (if page0? all (vec commits))))
             rows  (if page0? (:rows fold) (into (get-in prev [:graph :rows] []) (:rows fold)))
             kept  (when page0?
                     ;; a refresh replaced the pages — hash-keyed UI state survives by hash
                     (select-keys (commits/keep-surviving prev (map :hash all))
                                  [:selection :expanded :bodies]))]
         (update-in db (commits-path root) merge kept
                    {:commits all :loading? false :error nil
                     :exhausted? (boolean exhausted) :empty? (boolean empty)
                     :graph {:rows rows :state (:state fold)
                             :max-lane (if page0?
                                         (graph/max-lane rows)
                                         (max (long (get-in prev [:graph :max-lane] 0))
                                              (graph/max-lane (:rows fold))))}}))))))

(rf/reg-event-db
 :commits/log-error
 (fn [db [_ root msg]]
   (update-in db (commits-path root) merge {:loading? false :error (str msg)})))

(rf/reg-event-db
 :commits/branches-received
 (fn [db [_ root {:keys [error] :as payload}]]
   (if error
     (update-in db (commits-path root) merge {:error error})
     (let [names (into #{} (map :name) (:branches payload))
           db    (assoc-in db (commits-path root :branches) payload)
           ref   (get-in db (commits-path root :ref))]
       (cond-> db
         ;; the viewed ref vanished (branch deleted) → fall back to HEAD (D8)
         (and ref (not (contains? names ref)))
         (assoc-in (commits-path root :ref) nil))))))

(rf/reg-event-fx
 :commits/set-ref
 (fn [{:keys [db]} [_ root ref]]
   {:db (assoc-in db (commits-path root :ref) ref)
    :fx [[:dispatch [:commits/load root {:skip 0}]]]}))

(rf/reg-event-fx
 :commits/activate
 (fn [_ [_ root hash]]
   ;; diff against the first parent — main resolves <hash>^, empty tree for a root commit (R4)
   {:fx [[:vv/git-open-diff {:root root :to hash :parent? true
                             :on-error [:commits/open-diff-error root]}]]}))

(rf/reg-event-db
 :commits/select
 (fn [db [_ root hash mode]]
   (let [order (mapv :hash (get-in db (commits-path root :commits)))]
     (update-in db (commits-path root :selection)
                (fn [sel] (commits/select (or sel {}) order hash mode))))))

(rf/reg-event-fx
 :commits/cursor-to
 (fn [{:keys [db]} [_ root idx {:keys [extend?]}]]
   (let [order (mapv :hash (get-in db (commits-path root :commits)))]
     (when-let [hash (get order idx)]
       ;; plain movement is cursor-only — :selected is Ctrl/Shift marking exclusively, and the
       ;; opened-commit highlight derives from the active document (ADR-0042)
       {:db (update-in db (commits-path root :selection)
                       (fn [sel]
                         (if extend?
                           (commits/select (or sel {}) order hash :range)
                           (assoc (or sel {}) :cursor hash))))
        :fx [[:git-graph/reveal-row idx]]}))))

;; the graph's plain-click target: move the keyboard cursor without touching the stored
;; multi-select (plain interactions never select under ADR-0042)
(rf/reg-event-db
 :commits/cursor-set
 (fn [db [_ root hash]]
   (update-in db (commits-path root :selection) #(assoc (or % {}) :cursor hash))))

;; Escape in the Commit Graph: drop the Ctrl/Shift marks but KEEP the keyboard cursor —
;; clearing the marking must not teleport keyboard focus (ADR-0042)
(rf/reg-event-db
 :commits/clear-selection
 (fn [db [_ root]]
   (update-in db (commits-path root :selection) #(assoc (or % {}) :anchor nil :selected #{}))))

(rf/reg-event-fx
 :commits/diff-selected
 (fn [{:keys [db]} [_ root]]
   (let [order (mapv :hash (get-in db (commits-path root :commits)))]
     (when-let [[older newer] (commits/diff-pair (get-in db (commits-path root :selection)) order)]
       {:fx [[:vv/git-open-diff {:root root :from older :to newer
                                 :on-error [:commits/open-diff-error root]}]]}))))

(rf/reg-event-fx
 :commits/toggle-expand
 (fn [{:keys [db]} [_ root hash]]
   (let [expanded? (contains? (get-in db (commits-path root :expanded) #{}) hash)
         body      (some #(when (= (:hash %) hash) (:body %))
                         (get-in db (commits-path root :commits)))
         db'       (update-in db (commits-path root :expanded)
                              (fn [s] (let [s (or s #{})]
                                        (if expanded? (disj s hash) (conj s hash)))))]
     (cond-> {:db db'}
       ;; first expansion renders the GFM body lazily (D3); cached until the next page-0 refresh
       (and (not expanded?)
            (not (str/blank? body))
            (not (contains? (get-in db (commits-path root :bodies) {}) hash)))
       (assoc :fx [[:commits/render-body {:root root :hash hash :body body}]])))))

(rf/reg-event-db
 :commits/body-rendered
 (fn [db [_ root hash html]]
   (assoc-in db (commits-path root :bodies hash) html)))

(rf/reg-event-db
 :commits/range-input
 (fn [db [_ root s]]
   (-> db
       (assoc-in (commits-path root :range-input) s)
       (assoc-in (commits-path root :range-error) nil))))

(rf/reg-event-fx
 :commits/range-submit
 (fn [{:keys [db]} [_ root]]
   (let [raw    (get-in db (commits-path root :range-input))
         parsed (commits/parse-range raw)]
     (if (nil? parsed)
       (when-not (str/blank? (str raw))
         {:db (assoc-in db (commits-path root :range-error) "unrecognized range")})
       {:fx [[:vv/git-open-diff (assoc parsed :root root
                                       :on-error [:commits/open-diff-error root])]]}))))

(rf/reg-event-db
 :commits/open-diff-error
 (fn [db [_ root msg]]
   (assoc-in db (commits-path root :range-error) (str msg))))

(rf/reg-event-fx
 :commits/git-changed
 (fn [{:keys [db]} [_ {:keys [root]}]]
   ;; conservative refresh (D8): branches + page 0 only, for repos the panel actually loaded;
   ;; the :gen bump makes any in-flight page reply stale, and keep-surviving preserves selection
   (when (get-in db (commits-path root))
     {:fx [[:vv/git-branches {:root root
                              :on-done  [:commits/branches-received root]
                              :on-error [:commits/log-error root]}]
           [:dispatch [:commits/load root {:skip 0}]]]})))

;; ── history modes: file history (--follow) + line-range history (-L) — ADR-0040 ────────────────
;; A history listing REPLACES the repo's log in the shared store, so the sidebar list and the
;; Commit Graph both show it; a dismissible chip in each header exits back to the branch log.

(defn- enter-history
  "Reset a repo's listing into `mode` for `target`, pin the panel to that repo (the results must be
   LOOKED AT — a pin elsewhere would hide them), and reveal the Commits tab."
  [db root mode target]
  (-> db
      (assoc-in [:ui :commits :root] root)
      (update-in (commits-path root) merge
                 {:mode mode :history-target target
                  :commits [] :graph nil :selection nil :expanded #{} :bodies {}
                  :exhausted? false :empty? false :error nil})))

(rf/reg-event-fx
 :git/file-history
 (fn [{:keys [db]} [_ {:keys [file]}]]
   (let [file (or file (nav/active-path db))
         root (when file (commits/derive-root {} (get-in db [:ui :projects]) file))]
     (when root
       {:db (enter-history db root :file-history {:file file})
        :fx [[:dispatch [:commits/load root {:skip 0}]]
             [:dispatch [:sidebar/show]]
             [:dispatch [:sidebar/tab :commits]]]}))))

(rf/reg-event-fx
 :git/line-history
 (fn [{:keys [db]} [_ {:keys [file start end]}]]
   (let [root (when file (commits/derive-root {} (get-in db [:ui :projects]) file))
         [start end] (if (> (long start) (long end)) [end start] [start end])]
     (when root
       {:db (enter-history db root :line-history {:file file :start start :end end})
        :fx [[:dispatch [:commits/load root {:skip 0}]]
             [:dispatch [:sidebar/show]]
             [:dispatch [:sidebar/tab :commits]]]}))))

(rf/reg-event-fx
 :git/line-history-from-selection
 (fn [{:keys [db]} _]
   ;; the palette/menu entry with no explicit range: the mounted source view's selection (or its
   ;; cursor line, twice) names the lines — read through an fx, the DOM's business
   (when-let [file (nav/active-path db)]
     {:fx [[:git/selection-line-history file]]})))

(rf/reg-event-fx
 :git/history-exit
 (fn [{:keys [db]} [_ root]]
   {:db (update-in db (commits-path root) merge
                   {:mode :log :history-target nil
                    :commits [] :graph nil :selection nil :expanded #{} :bodies {}
                    :exhausted? false :empty? false :error nil})
    :fx [[:dispatch [:commits/load root {:skip 0}]]]}))

;; ── the Commit Graph document (ADR-0040) ────────────────────────────────────────────────────────

(rf/reg-event-fx
 :git-graph/open
 (fn [{:keys [db]} [_ root]]
   ;; explicit root (a project-header menu, the panel button) must be a GIT project; the no-arg
   ;; palette form derives one exactly like the sidebar panel does
   (let [projs (get-in db [:ui :projects])
         root  (or root
                   (commits/derive-root {:pinned    (get-in db [:ui :commits :root])
                                         :last-root (get-in db [:ui :commits :last-root])}
                                        projs
                                        (nav/active-path db)))]
     (when (and root
                (not (:synthetic? (some #(when (= (:root %) root) %) projs))))
       {:fx [[:dispatch [:doc/open (str "vv-git-graph://" root)]]]}))))

(rf/reg-event-fx
 :git-graph/data-ensure
 (fn [_ [_ root]]
   (when root {:fx [[:dispatch [:commits/ensure root]]]})))

(rf/reg-event-fx
 :git-graph/shown
 (fn [{:keys [db]} [_ root]]
   ;; also record last-root: commits/derive-root falls back to it when the active doc is a spill
   ;; (outside every project), so a diff activated from the graph still highlights in a Commits
   ;; panel opened only AFTERWARDS (ADR-0042 — without this, a graph-only flow leaves it nil)
   {:db (-> db
            (assoc-in [:ui :commits :last-root] root)
            (assoc-in [:ui :commits :watch-owners :graph] root))
    :fx [[:dispatch [:commits/sync-watch]]
         [:dispatch [:commits/ensure root]]]}))

(rf/reg-event-fx
 :git-graph/hidden
 (fn [{:keys [db]} _]
   {:db (assoc-in db [:ui :commits :watch-owners :graph] nil)
    :fx [[:dispatch [:commits/sync-watch]]]}))

(rf/reg-event-fx
 :git-graph/near-end
 (fn [{:keys [db]} [_ root approx-hi]]
   (let [{:keys [commits loading? exhausted?]} (get-in db (commits-path root))]
     (when (and (pos? (count commits))
                (>= (long approx-hi) (- (count commits) 30))
                (not loading?) (not exhausted?))
       {:fx [[:dispatch [:commits/load-more root]]]}))))

(rf/reg-event-fx
 :git-graph/cursor-move
 (fn [{:keys [db]} [_ root key {:keys [extend? vis-rows]}]]
   (let [order (mapv :hash (get-in db (commits-path root :commits)))
         n     (count order)
         cur   (get-in db (commits-path root :selection :cursor))
         idx   (first (keep-indexed #(when (= %2 cur) %1) order))
         nidx  (if (nil? idx)
                 (when (pos? n) 0)                       ; no cursor yet → the newest commit
                 (ggeo/next-cursor idx n key vis-rows))]
     (when (some? nidx)
       {:fx [[:dispatch [:commits/cursor-to root nidx {:extend? extend?}]]]}))))

(rf/reg-event-fx
 :git-graph/toggle-at-cursor
 (fn [{:keys [db]} [_ root]]
   (when-let [cur (get-in db (commits-path root :selection :cursor))]
     {:fx [[:dispatch [:commits/select root cur :toggle]]]})))

(rf/reg-event-fx
 :git-graph/activate-cursor
 (fn [{:keys [db]} [_ root]]
   (when-let [cur (get-in db (commits-path root :selection :cursor))]
     {:fx [[:dispatch [:commits/activate root cur]]]})))

;; ══ git blame (ADR-0040) ════════════════════════════════════════════════════════════════════════
;; One GLOBAL mode flag: whenever a source view mounts while blame is on, the gutter is (re)ensured
;; for that file — facet flips, tab switches, and live refreshes all fall out of the single
;; :blame/source-mounted hook. One `git blame` per (file, stamp); the reply is stamp-gated so a
;; live-refresh race can never paint a stale gutter.

(defn- blameable?
  "Blame serves LOCAL plain-path files only (no remote URIs, no virtual schemes). A local file
   outside any repository — a /tmp scratch note, a stdin spill — still passes here and simply gets
   main's honest \"not in a git repository\" reply in the gutterless error slot."
  [file]
  (boolean (and (string? file)
                (not (uri/remote? file))
                (some? (uri/file-path file)))))

(rf/reg-event-fx
 :blame/source-mounted
 (fn [{:keys [db]} [_ {:keys [file stamp]}]]
   (let [db' (-> db
                 (assoc-in [:ui :blame :file] file)
                 (assoc-in [:ui :blame :stamp] stamp))]
     (cond-> {:db db'}
       (get-in db [:ui :blame :on?])
       (assoc :fx [[:dispatch [:blame/ensure]]])))))

(rf/reg-event-fx
 :blame/toggle
 (fn [{:keys [db]} _]
   (let [{:keys [on? file]} (get-in db [:ui :blame])]
     (cond
       on?
       {:db (assoc-in db [:ui :blame :on?] false)
        :fx [[:blame/clear-view nil]]}

       ;; self-gating (the palette pattern): no mounted local source target → a silent no-op
       (blameable? file)
       {:db (assoc-in db [:ui :blame :on?] true)
        :fx [[:dispatch [:blame/ensure]]]}

       :else nil))))

(rf/reg-event-fx
 :blame/ensure
 (fn [{:keys [db]} _]
   (let [{:keys [on? file stamp hunks] :as blame} (get-in db [:ui :blame])]
     (when (and on? (blameable? file))
       (if (and hunks (= [file stamp] [(:hunks-file blame) (:hunks-stamp blame)]))
         {:fx [[:blame/apply-view hunks]]}
         {:db (-> db
                  (assoc-in [:ui :blame :loading?] true)
                  (assoc-in [:ui :blame :error] nil))
          :fx [[:vv/git-blame {:file file :stamp stamp}]]})))))

(rf/reg-event-fx
 :blame/received
 (fn [{:keys [db]} [_ file stamp {:keys [root hunks error]}]]
   (when (= [file stamp] [(get-in db [:ui :blame :file]) (get-in db [:ui :blame :stamp])])
     (if error
       {:db (-> db
                (assoc-in [:ui :blame :loading?] false)
                (assoc-in [:ui :blame :error] (str error)))}
       {:db (update-in db [:ui :blame] merge
                       {:loading? false :error nil :root root :hunks hunks
                        :hunks-file file :hunks-stamp stamp})
        :fx (when (get-in db [:ui :blame :on?])
              [[:blame/apply-view hunks]])}))))

(rf/reg-event-db
 :blame/error
 (fn [db [_ msg]]
   (-> db
       (assoc-in [:ui :blame :loading?] false)
       (assoc-in [:ui :blame :error] (str msg)))))

(rf/reg-event-fx
 :blame/line-click
 (fn [{:keys [db]} [_ hunk]]
   ;; the gutter hands back the resolved hunk; an uncommitted (zero-hash) line has no diff to open
   (let [{:keys [root]} (get-in db [:ui :blame])]
     (when (and root hunk (not (:uncommitted hunk)))
       {:fx [[:dispatch [:git/open-commit-diff root {:to (:hash hunk)}]]]}))))

(rf/reg-event-fx
 :git/open-commit-diff
 (fn [_ [_ root {:keys [from to]}]]
   ;; the shared open-a-commit-diff entry (blame click, graph activation): a nil FROM asks main for
   ;; <to>'s first parent, with the empty tree closing the root-commit case (R4)
   {:fx [[:vv/git-open-diff (cond-> {:root root :to to :on-error [:blame/error]}
                              from       (assoc :from from)
                              (nil? from) (assoc :parent? true))]]}))

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
                            sourceable paged siblings language stdin baseDir git] :as payload}]]
   (let [snap    (ds/snapshot)
         eid     (ds/eid-for-path snap path)
         cur-err (and eid (ds/doc-attr snap path :doc/error))
         ;; :doc/language must NOT linger: Settings ▸ File Type ▸ Source (auto) clears the override
         ;; main-side, and the re-send then carries no :language — retract the stale attr (an upsert
         ;; only overwrites present keys) or the grammar pick would stay pinned to the old language.
         cur-lang (and eid (ds/doc-attr snap path :doc/language))
         ;; :doc/git likewise must not linger: only a payload that carries the commit-diff facts keeps
         ;; it (a doc re-typed away from "diff", or re-sent without its override, must drop the stale
         ;; mapping or the derived open-commit highlight would mark the wrong rows; ADR-0042).
         cur-git  (and eid (ds/doc-attr snap path :doc/git))
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
                   siblings              (assoc :doc/siblings (vec siblings))   ; the collocated document group (Preview/Source combo)
                   language              (assoc :doc/language language)  ; explicit source grammar (ADR-0036)
                   baseDir               (assoc :doc/base-dir baseDir)   ; a piped doc's invoking cwd (asset base)
                   (= kind "git-graph")  (assoc :doc/git-root (:root git))   ; the Commit Graph's repo (ADR-0040)
                   ;; a commit-diff spill's {root from to range} facts → the derived Commits-panel
                   ;; "open commit" highlight (ADR-0042); wire `range` (no ?) becomes :range?
                   (and (= kind "diff") git)
                   (assoc :doc/git {:root   (:root git)
                                    :from   (:from git)
                                    :to     (:to git)
                                    :range? (boolean (:range git))})
                   (= kind "text")       (assoc :doc/html (plain-html text))   ; plain text
                   ;; Resolution is stamp-scoped and asynchronous. Clear the previous diff's targets immediately
                   ;; so a live refresh cannot leave a now-missing header path clickable while main re-checks it.
                   (= kind "diff")       (assoc :doc/diff-targets {})
                   ;; markdown/office/org/latex/diff derive their :doc/toc + :doc/assets from the IR render (arriving
                   ;; async via :content/rendered), so DON'T reset those here; every other kind clears them.
                   (and (not= kind "markdown") (not ir-office?) (not= kind "org") (not= kind "latex") (not= kind "diff"))
                   (assoc :doc/toc [] :doc/assets []))
         ;; update by :db/id when cached, create by :doc/path otherwise — the :doc/path upsert/lookup-ref
         ;; does not resolve under :advanced compilation.
         base    (if eid (assoc attrs :db/id eid) (assoc attrs :doc/path path))
         tx      (cond-> [base]
                   cur-err (conj [:db/retract eid :doc/error cur-err])
                   (and cur-lang (nil? language)) (conj [:db/retract eid :doc/language cur-lang])
                   (and cur-git (or (not= kind "diff") (nil? git)))
                   (conj [:db/retract eid :doc/git cur-git]))
         ;; the CLI/initial file arrives before any tab exists → it opens the first tab
         opened-first-tab? (empty? (nav/tabs db))
         db'     (if opened-first-tab? (nav/add-tab db path) db)
         ;; record recent navigation only for the ACTIVE tab's path (a forward nav / revisit), never a
         ;; background live-refresh — so the MRU + trail track where the user actually went. A piped-stdin
         ;; document (payload :stdin) never enters the MRU: its spill path dies with the tab.
         active? (= path (nav/active-path db'))
         ;; …nor does a Commit Graph virtual uri: Open Recent must offer real, reopenable documents
         db'     (if (and active? (not stdin) (not= kind "git-graph"))
                   (record-recent db' path (#{"directory" "archive"} kind))
                   db')
         ;; on a fresh open of a group doc (no stored facet yet), resolve + store its default view facet so the
         ;; pane shows it and retention keeps its file; the fx below loads that file when it isn't the one received
         ;; (the PDF-first default). Computed from the PAYLOAD group — the :doc/siblings tx is not yet applied.
         ;; Scoped to the OWNING TABS rather than the active one (ADR-0044): a group doc opened into a
         ;; BACKGROUND tab must get its default facet now, or activating it later would show a facet whose
         ;; file was never loaded (nothing re-resolves it on mount).
         facet-tab-ids (when (seq siblings)
                         (into [] (keep (fn [t] (when (and (nil? (:facet t))
                                                           (= path (uri/file-path (:uri t))))
                                                  (:id t))))
                               (nav/tabs db')))
         fresh-facet (when (seq facet-tab-ids)
                       (facet/default-facet (vec siblings) path
                                            (get-in db' [:ui :settings :collocated-default] :pdf)))
         db'     (if fresh-facet
                   (reduce (fn [d id] (nav/set-facet d id (:path fresh-facet) (:type fresh-facet)))
                           db' facet-tab-ids)
                   db')]
     (cond->
       (with-retention
        {:db db'
         :fx (cond-> [[:render-cache/invalidate {:path path :stamp stamp}]
                     [:ds/transact tx]]
              ;; the Commit Graph reads [:ui :commits] — ensure its repo's branches + first page (R9)
              (= kind "git-graph") (conj [:dispatch [:git-graph/data-ensure (:root git)]])
              ;; a streaming doc is driven by ir-stream-body from the file path — skip the batch render fx.
              ;; :base-dir (a piped document's invoking cwd, ADR-0036) overrides the path-derived asset base
              ;; so `cat README.md | vv -t md` resolves relative images against the directory it was piped from.
              (and (= kind "markdown") (not stream?))
              (conj [:markdown/render {:text text :path path :stamp stamp :base-dir baseDir
                                       :on-done [:content/rendered path stamp]}])
              ;; office (docx/ODF) → the common-IR render (HTML + heading TOC) when :vv/ir is on
              ir-office?          (conj [:office/render {:html html :path path
                                                         :on-done [:content/rendered path stamp]}])
              ;; org (.org) → the common-IR render via uniorg (HTML + heading TOC + assets), like markdown.
              ;; A streaming doc is driven by ir-stream-body from the file path → skip the batch render fx.
              (and (= kind "org") (not stream?))
              (conj [:org/render {:text text :path path :stamp stamp :base-dir baseDir
                                  :on-done [:content/rendered path stamp]}])
              ;; latex (.tex) → the common-IR render via unified-latex (HTML + heading TOC + assets), like org.
              ;; LaTeX always batch-renders (not in stream-flag/streamable-kinds), but keep the guard for symmetry.
              (and (= kind "latex") (not stream?))
              (conj [:latex/render {:text text :path path :stamp stamp :base-dir baseDir
                                    :on-done [:content/rendered path stamp]}])
              ;; diff (.diff/.patch) → the unified colored HTML + per-file Contents outline (ir.frontend.diff).
              (= kind "diff")
              (conj [:diff/render {:text text :path path :stamp stamp
                                   :on-done [:content/rendered path stamp]}])
              ;; a live-refresh of a diff whose side-by-side view was already built → rebuild it against the new text
              (and (= kind "diff") (ds/doc-attr snap path :doc/diff-split-html))
              (conj [:diff/build-split {:path path :text text :stamp stamp}])
              ;; pdf bytes go to the renderer byte cache (keyed by :doc/path), never DataScript (ADR-0010)
              (= kind "pdf")      (conj [:pdf/cache-bytes {:path path :bytes bytes}])
              ;; the default facet points at a DIFFERENT collocated file (the PDF-first default) → load it in place
              ;; (no tab). Loading gives a pdf facet BOTH its doc entity and its cached bytes (via :content/received).
              (and fresh-facet (not= (:path fresh-facet) path) (nil? (ds/eid-for-path snap (:path fresh-facet))))
              (conj [:facet/ensure-loaded {:path (:path fresh-facet)}])
               active? (conj [:vv/save-recent (pr-str (get-in db' [:ui :recent]))]))}
        db')
       opened-first-tab? (with-active-tab-focus db')))))

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

;; A streamed remote doc's connection dropped mid-stream. NON-fatal: the already-committed blocks stay in the
;; DOM; we only set a light note (shown in the progress strip). Never :doc/error (that would blank the content).
(rf/reg-event-fx
 :stream/interrupted
 (fn [_ [_ path _msg]]
   (when-let [eid (ds/eid-for-path (ds/snapshot) path)]
     {:fx [[:ds/transact [[:db/add eid :doc/stream-note "Connection lost — showing partial content. Reopen to retry."]]]]})))

;; ---- SSH/SFTP: auth prompts + connection errors (main → renderer) ---------------------------------------------
;; The prompt REQUEST (non-secret: kind/host/user/attempt/prompt) is stored in app-db so the modal can render;
;; the typed SECRET never lands here — the modal holds it locally and dispatches :ssh/prompt-reply, which sends
;; it straight to main over vv:ssh-prompt-reply.
(rf/reg-event-db :ssh/prompt        (fn [db [_ req]]  (assoc-in db [:ui :ssh-prompt] req)))
(rf/reg-event-fx :ssh/prompt-reply
                 (fn [{:keys [db]} [_ prompt-id secret]]
                   {:db (assoc-in db [:ui :ssh-prompt] nil)
                    :fx [[:ssh/reply {:promptId prompt-id :secret secret}]]}))
(rf/reg-event-db :ssh/error         (fn [db [_ info]] (assoc-in db [:ui :ssh-error] info)))
(rf/reg-event-db :ssh/dismiss-error (fn [db _]        (assoc-in db [:ui :ssh-error] nil)))
;; connection status (connecting/ready/closed) — kept for a future indicator; harmless if unused
(rf/reg-event-db :ssh/status        (fn [db [_ info]] (assoc-in db [:ui :ssh-status] info)))
;; persisted (non-secret) connection metadata pushed as raw EDN text — parsed and stored for host/URI hints
(rf/reg-event-db :connections/received
                 (fn [db [_ text]]
                   (assoc-in db [:ui :connections]
                             (when (and (string? text) (not (str/blank? text)))
                               (try (reader/read-string text) (catch :default _ nil))))))

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

;; Set a document's SOURCE-view Contents outline (:doc/source-toc) out of band — the source-code view derives it
;; from its tree-sitter parse (common IR) after mounting. Kept SEPARATE from the preview's :doc/toc (written at
;; :content/rendered): the two use different id-spaces (source = `L<line>` for CodeMirror nav, preview = rehype
;; slug ids for DOM nav), so writing the same attr clobbered whichever rendered last and left the inactive view's
;; Contents un-navigable. The :doc/toc sub selects between them by the active view.
(rf/reg-event-fx
 :toc/set
 (fn [_ [_ path toc]]
   (let [snap (ds/snapshot)]
     (when-let [eid (ds/eid-for-path snap path)]
       {:fx [[:ds/transact [[:db/add eid :doc/source-toc (vec (or toc []))]]]]}))))

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
   (let [tx                (content-error-tx (ds/snapshot) path message (or stamp (js/Date.now)))
         opened-first-tab? (and path (empty? (nav/tabs db)))
         db'               (if opened-first-tab? (nav/add-tab db path) db)]
     (cond->
       (with-retention
        (cond-> {:db db'}
          (seq tx) (assoc :fx [[:ds/transact tx]]))
        db')
       opened-first-tab? (with-active-tab-focus db')))))

;; FX helper: load the uri's content + (for a local file) restore the target history scroll. The web view
;; scrolls itself, so http never requests a content-pane restore.
(defn- nav-fx [uri scroll] (cond-> (load-fx uri) (uri/file-path uri) (conj [:scroll/restore scroll])))

(defn- nav-result [db uri scroll]
  ;; navigating to an http(s) page records it in browser history (→ address-bar history completion)
  (let [db (record-web-history db uri)]
    (with-active-tab-focus
      {:db db
       :fx (cond-> (into (retention-fx db) (nav-fx uri scroll))
             (and uri (uri/http? uri)) (conj [:vv/save-recent (pr-str (get-in db [:ui :recent]))]))}
      db)))

;; FX helper: restore a history entry's view position, facet-aware. A :source entry stashes its :line for the
;; source view's imminent (re)mount (create-source-view consumes it); a :preview entry with a :line stashes it for
;; the preview remount; otherwise the pixel :scroll is restored on the next content render (scroll/apply!). nil when
;; a source entry has no captured line (nothing to restore → stay at the top).
(defn- entry-restore-fx [{:keys [scroll line facet]}]
  (case (:type facet)
    :source (when line [:source/want-line line])
    (if line [:preview/want-line line] [:scroll/restore (or scroll 0)])))

(defn- history-result
  "Back/Forward (or a tab switch) landed on `entry` {:uri :scroll :line :facet}. Reload the primary doc
   (idempotent), ensure the shown facet's sibling file is present, and restore the position facet-aware. Mirrors
   nav-result's retention + http recent-save. The facet is already written onto the tab by nav/step, so the
   :content/received fresh-facet guard (nil? facet) is false → the restored view is not clobbered."
  [db uri entry]
  (let [db         (record-web-history db uri)
        facet-path (get-in entry [:facet :path])
        restore    (entry-restore-fx entry)]
    (with-active-tab-focus
      {:db db
       :fx (cond-> (retention-fx db)
             facet-path                       (conj [:facet/ensure-loaded {:path facet-path}])
             (uri/file-path uri)              (into (load-fx uri))
             (and restore (uri/file-path uri)) (conj restore)
             (and uri (uri/http? uri))        (conj [:vv/save-recent (pr-str (get-in db [:ui :recent]))]))}
      db)))

;; navigate the ACTIVE tab to uri (left-click / URI bar); creates the first tab if none. The leaving
;; scroll is saved into history; the new entry starts at the top.
(rf/reg-event-fx
 :tab/navigate
 [(rf/inject-cofx :view-pos)]
 (fn [{:keys [db view-pos]} [_ uri]]
   (let [db' (if (nav/active-tab db) (nav/nav-active db uri view-pos) (nav/add-tab db uri))]
     (nav-result db' uri 0))))

;; open uri in a NEW, FOCUSED tab (Ctrl+Shift+click / Shift+middle-click / context menu) — save the
;; current tab's view position first
(rf/reg-event-fx
 :tab/open
 [(rf/inject-cofx :view-pos)]
 (fn [{:keys [db view-pos]} [_ uri]]
   (let [db' (nav/add-tab (nav/save-scroll db view-pos) uri)]
     (nav-result db' uri 0))))

;; open uri in a NEW BACKGROUND tab (Ctrl+click / middle-click — ADR-0044). The active tab does not
;; change, so there is no leaving view position to save and nothing to scroll: the fx are the content
;; load alone (retention is added below). Content arrives through the ordinary vv:open → vv:content →
;; :content/received path, which is keyed by :doc/path and has no active-tab dependency; when the tab
;; is later activated, its position restore runs through :tab/activate like any other tab.
;; With no tab to stay on (a fresh window, or every tab closed) "background" is meaningless — the open
;; degenerates to the focused variant rather than leaving the window showing nothing.
(rf/reg-event-fx
 :tab/open-background
 (fn [{:keys [db]} [_ uri]]
   (if (nav/active-tab db)
     (let [db' (record-web-history (nav/add-tab-background db uri) uri)]
       (with-retention
         {:db db'
          :fx (cond-> (load-fx uri)
                (and uri (uri/http? uri)) (conj [:vv/save-recent (pr-str (get-in db' [:ui :recent]))]))}
         db'))
     {:fx [[:dispatch [:tab/open uri]]]})))

(rf/reg-event-fx
 :tab/new-blank
 [(rf/inject-cofx :view-pos)]
 (fn [{:keys [db view-pos]} _]
   (let [db' (nav/add-tab (nav/save-scroll db view-pos) nil)]
     (with-active-tab-focus (with-retention {:db db'} db') db'))))

;; "Open" (left-click / context menu): focus an existing tab for uri (restoring its view position, facet-aware),
;; else navigate
(rf/reg-event-fx
 :doc/open
 [(rf/inject-cofx :view-pos)]
 (fn [{:keys [db view-pos]} [_ uri]]
   (if-let [t (nav/find-tab db uri)]
     (let [db' (nav/activate (nav/save-scroll db view-pos) (:id t))]
       (with-active-tab-focus
         (with-retention
           {:db db' :fx (cond-> [] (uri/file-path uri) (conj-some (entry-restore-fx (nav/cur-entry db'))))}
           db')
         db'))
     (let [db' (if (nav/active-tab db) (nav/nav-active db uri view-pos) (nav/add-tab db uri))]
       (nav-result db' uri 0)))))

;; "Open in new tab" (Ctrl+click / context menu): focus an existing tab for uri, else a new tab
(rf/reg-event-fx
 :doc/open-new
 [(rf/inject-cofx :view-pos)]
 (fn [{:keys [db view-pos]} [_ uri]]
   (if-let [t (nav/find-tab db uri)]
     (let [db' (nav/activate (nav/save-scroll db view-pos) (:id t))]
       (with-active-tab-focus
         (with-retention
           {:db db' :fx (cond-> [] (uri/file-path uri) (conj-some (entry-restore-fx (nav/cur-entry db'))))}
           db')
         db'))
     (let [db' (nav/add-tab (nav/save-scroll db view-pos) uri)]
       (nav-result db' uri 0)))))

;; switch tabs — save the leaving tab's view position, restore the target tab's (facet-aware)
(defn- activate-tab-result [db view-pos id]
  (let [db'    (nav/activate (nav/save-scroll db view-pos) id)
        target (nav/active-uri db')]
    (with-active-tab-focus
      (with-retention
        {:db db' :fx (cond-> [] (uri/file-path target) (conj-some (entry-restore-fx (nav/cur-entry db'))))}
        db')
      db')))

(rf/reg-event-fx
 :tab/activate
 [(rf/inject-cofx :view-pos)]
 (fn [{:keys [db view-pos]} [_ id]]
   (activate-tab-result db view-pos id)))

(rf/reg-event-fx
 :tab/duplicate
 [(rf/inject-cofx :view-pos)]
 (fn [{:keys [db view-pos]} [_ id]]
   (let [db'    (cond-> db (= id (nav/active-id db)) (nav/save-scroll view-pos))
         db''   (nav/duplicate-tab db' id)
         target (nav/active-uri db'')]
     (cond->
       (with-retention
         {:db db''
          :fx (cond-> [] (and (not= db' db'') (uri/file-path target))
                (conj-some (entry-restore-fx (nav/cur-entry db''))))}
         db'')
       (not= db' db'') (with-active-tab-focus db'')))))

(rf/reg-event-fx
 :tab/close
 (fn [{:keys [db]} [_ id]]
   (let [closing-active?       (= id (nav/active-id db))
         [db' _uri _still?] (nav/close db id)]
     (cond-> (with-retention {:db db'} db')
       closing-active? (with-active-tab-focus db')))))

(rf/reg-event-fx
 :tab/close-active
 (fn [{:keys [db]} _] (if-let [id (nav/active-id db)] {:fx [[:dispatch [:tab/close id]]]} {})))

(rf/reg-event-fx :tab/reload (fn [{:keys [db]} _] {:fx (load-fx (nav/active-uri db))}))

;; ── Settings ▸ File Type (ADR-0036): re-interpret the SHOWN document under an explicit type ──
;; The override must live main-side (send-content! re-classifies from the path on EVERY send, including
;; watcher live-refreshes), so this only names the file and the chosen type over vv:set-file-type; main
;; registers it and re-sends through the full open pipeline — the :tab/reload shape. No optimistic ds
;; write: :content/received stays the single ingestion point (the re-sent payload carries the new kind).
;; `language` is a grammar catalog id for a "source with THIS grammar" pick, nil for a plain kind pick.
(rf/reg-event-fx
 :doc/set-file-type
 (fn [{:keys [db]} [_ {:keys [kind language]}]]
   (when-let [p (some-> (facet/active-content-path db) uri/file-path)]
     (when (string? kind)
       {:fx [[:vv/set-file-type {:path p :kind kind :language language}]]}))))

;; ── the view FACET (which collocated representation is shown, as preview/source) — see vinary.app.facet ──
;; Flip the ACTIVE file between preview + source (the [Preview|Source] main flip / C-S-s / "View Source"). Lands on
;; the OTHER type's main target for the group; no-op when the other type has no options (e.g. a PDF-only doc).
(rf/reg-event-fx :tab/toggle-source
                 (fn [{:keys [db]} [_ id]]
                   (let [primary (nav/active-path db)]
                     (if-let [tgt (facet/toggle-target (facet/active-type db)
                                                       (facet/group-of (ds/snapshot) primary)
                                                       primary (nav/facet-mru db))]
                       {:fx [[:dispatch [:tab/set-facet (or id (nav/active-id db)) (:path tgt) (:type tgt)]]]}
                       {}))))

;; Show `path` as `type` (:preview/:source) on the tab as a HISTORY event (nav/push-facet), so Back/Forward returns
;; to the previous view + location. Loads the file when needed (idempotent) and syncs retention so the shown facet's
;; doc is watched + not evicted. The combo menu-pick + main-region + toggle-source entry point. A PDF facet gets its
;; doc entity and cached bytes via :content/received. Re-selecting the already-shown facet is a no-op (no entry).
(rf/reg-event-fx :tab/set-facet
                 [(rf/inject-cofx :view-pos)]
                 (fn [{:keys [db view-pos]} [_ id path type]]
                   (if (= {:path path :type type} (facet/resolve-facet db))
                     {:fx [[:facet/ensure-loaded {:path path}]]}          ; already the shown view → don't push history
                     (let [db' (nav/push-facet db (or id (nav/active-id db)) path type view-pos nil)]
                       (with-retention {:db db' :fx [[:facet/ensure-loaded {:path path}]]} db')))))

;; The combo MAIN region (left of the divider): activate `type`'s main target — its most-recently-used file, else
;; the first/default option. No-op when the type has no options.
(rf/reg-event-fx :tab/activate-facet-type
                 (fn [{:keys [db]} [_ id type]]
                   (let [primary (nav/active-path db)
                         group   (facet/group-of (ds/snapshot) primary)
                         opts    (if (= type :source) (facet/source-options group primary)
                                     (facet/preview-options group primary))]
                     (if-let [tgt (facet/main-target opts (get (nav/facet-mru db) type))]
                       {:fx [[:dispatch [:tab/set-facet (or id (nav/active-id db)) tgt type]]]}
                       {}))))

;; Diff unified⇄split view switch (a .diff/.patch doc). Selecting :split builds the side-by-side HTML the first
;; time (baseline immediately, then enriched with on-disk sources) — see the :diff/build-split fx.
(rf/reg-event-fx :tab/set-diff-view
                 (fn [{:keys [db]} [_ id view]]
                   (let [id    (or id (nav/active-id db))
                         db'   (nav/set-diff-view db id view)
                         path  (facet/active-content-path db')   ; the shown facet (a diff may be one of several)
                         snap  (ds/snapshot)
                         text  (ds/doc-attr snap path :doc/text)
                         stamp (ds/doc-attr snap path :doc/stamp)
                         built? (some? (ds/doc-attr snap path :doc/diff-split-html))]
                     (cond-> {:db db'}
                       (and (= view :split) text (not built?))
                       (assoc :fx [[:diff/build-split {:path path :text text :stamp stamp}]])))))

;; flip the active diff's view (:unified ↔ :split) — the command-palette / keybinding entry. No-op unless the
;; active doc is a diff. Flips off the EFFECTIVE view (which is width-aware for an unchosen tab, ADR-0043)
;; and delegates to :tab/set-diff-view (which builds the split HTML on demand) — so the flip always writes
;; an explicit, sticky choice.
(rf/reg-event-fx :tab/toggle-diff-view
                 (fn [{:keys [db]} _]
                   (if (= "diff" (ds/doc-attr (ds/snapshot) (facet/active-content-path db) :doc/kind))
                     (let [eff (nav/effective-diff-view (nav/diff-view db)
                                                        (nav/split-wide? (get-in db [:ui :content-width])))
                           nxt (if (= eff :split) :unified :split)]
                       {:fx [[:dispatch [:tab/set-diff-view nil nxt]]]})
                     {})))

;; ONE idempotent gate for "the shown doc should be Split but the side-by-side HTML isn't built":
;; dispatched from the unified diff view's MOUNT (views/diff-unified-body — covering every
;; navigation path: open, tab switch, history Back/Forward, focus-existing, close-reveal, live
;; refresh (the stamp-keyed remount), facet flip) and from :ui/content-width (the live width
;; crossing while mounted). Guards: the shown facet is a diff ∧ the effective view is :split ∧
;; text present ∧ split not built. The stamp rides to the stamp-gated :diff/split-ready, so a
;; mid-build refresh drops stale HTML (ADR-0043).
(rf/reg-event-fx :diff/ensure-split
                 (fn [{:keys [db]} _]
                   (let [path (facet/active-content-path db)
                         snap (ds/snapshot)]
                     (when (and (= "diff" (ds/doc-attr snap path :doc/kind))
                                (= :split (nav/effective-diff-view
                                           (nav/diff-view db)
                                           (nav/split-wide? (get-in db [:ui :content-width]))))
                                (nil? (ds/doc-attr snap path :doc/diff-split-html)))
                       (when-let [text (ds/doc-attr snap path :doc/text)]
                         {:fx [[:diff/build-split {:path  path
                                                   :text  text
                                                   :stamp (ds/doc-attr snap path :doc/stamp)}]]})))))

;; ── per-file diff collapse (ADR-0037). State = the active tab's :diff-collapsed id set; every write is
;; followed by :diff/apply-collapsed, which projects the set onto the mounted <details> wrappers (both
;; views share the vv-diff-file-N id space, so the set applies to whichever layout is up). All four events
;; self-gate on the shown facet being a diff PREVIEW — the :tab/toggle-diff-view pattern, since palette
;; commands and the vim z M / z R chords carry no doc-kind :when predicate. ──

;; header click / hint activation: flip ONE file (the id is the summary's — "vv-diff-file-N")
(rf/reg-event-fx :diff/toggle-file
                 (fn [{:keys [db]} [_ file-id]]
                   (if (and (string? file-id) (facet/diff-preview-active? db))
                     (let [db' (nav/toggle-diff-collapsed db (nav/active-id db) file-id)]
                       {:db db' :fx [[:diff/apply-collapsed (nav/diff-collapsed db')]]})
                     {})))

;; collapse every file — the id list comes from the diff's Contents outline (:doc/toc), never the DOM
(rf/reg-event-fx :diff/collapse-all
                 (fn [{:keys [db]} _]
                   (if (facet/diff-preview-active? db)
                     (let [db' (nav/set-diff-collapsed db (set (facet/diff-file-ids db)))]
                       {:db db' :fx [[:diff/apply-collapsed (nav/diff-collapsed db')]]})
                     {})))

(rf/reg-event-fx :diff/expand-all
                 (fn [{:keys [db]} _]
                   (if (facet/diff-preview-active? db)
                     (let [db' (nav/set-diff-collapsed db #{})]
                       {:db db' :fx [[:diff/apply-collapsed #{}]]})
                     {})))

;; the View-menu item's single static event: expand when everything is collapsed, else collapse — the
;; menu's dynamic label ("Collapse All Files" ↔ "Expand All Files") flips off the same predicate
(rf/reg-event-fx :diff/toggle-collapse-all
                 (fn [{:keys [db]} _]
                   {:fx [[:dispatch (if (facet/diff-all-collapsed? db) [:diff/expand-all] [:diff/collapse-all])]]}))

;; main resolved diff-relative names to existing local/remote files. Stamp-gated because the stat walk is async
;; and live refresh can replace the file list while it is in flight.
(rf/reg-event-fx :diff/targets-ready
                 (fn [_ [_ path stamp targets]]
                   (let [snap (ds/snapshot)]
                     (when-let [eid (ds/eid-for-path snap path)]
                       (when (= stamp (ds/doc-attr snap path :doc/stamp))
                         {:fx [[:ds/transact [[:db/add eid :doc/diff-targets (or targets {})]]]]})))))

;; the side-by-side (split) HTML for a diff finished building (baseline or on-disk-enriched) → store it on the
;; doc, but never let an older async grammar/read result overwrite a live-refreshed document.
(rf/reg-event-fx :diff/split-ready
                 (fn [_ [_ path stamp html]]
                   (let [snap (ds/snapshot)]
                     (when-let [eid (ds/eid-for-path snap path)]
                       (when (= stamp (ds/doc-attr snap path :doc/stamp))
                         {:fx [[:ds/transact [[:db/add eid :doc/diff-split-html html]]]]})))))

;; ── bidirectional source⇄preview jump ("Go to source" / "Go to preview" context-menu items + keymap) ──
;; The EVENT decides whether the pane must toggle (it knows the current view), and the FX either scrolls the
;; already-mounted view NOW or stashes the target line for the view that is about to mount (consumed across the
;; toggle-driven remount — mirrors renderer.scroll want!→apply!).
(rf/reg-event-fx
 :source/goto-line                                        ; preview → source
 [(rf/inject-cofx :view-pos)]
 (fn [{:keys [db view-pos]} [_ line]]
   (when (number? line)
     (if (= :source (facet/active-type db))
       {:fx [[:source/scroll-line line]]}                 ; already source: scroll the live view now (no view switch)
       ;; entering source: a VIEW SWITCH (+ jump) → push a history entry carrying the source facet and its target
       ;; line (same file, already loaded), and defer the scroll until create-source-view mounts
       {:db  (nav/push-facet db (nav/active-id db) (facet/active-content-path db) :source view-pos {:line line})
        :fx  [[:source/want-line line]]}))))
(rf/reg-event-fx
 :preview/goto-line                                       ; source → preview
 [(rf/inject-cofx :view-pos)]
 (fn [{:keys [db view-pos]} [_ line]]
   (when (number? line)
     (if (= :source (facet/active-type db))
       ;; leaving source: a VIEW SWITCH (+ jump) → push a preview entry carrying its target line; defer the scroll
       ;; until the preview remounts
       {:db  (nav/push-facet db (nav/active-id db) (facet/active-content-path db) :preview view-pos {:line line})
        :fx  [[:preview/want-line line]]}
       {:fx [[:preview/scroll-line line]]}))))            ; already preview: scroll now (no view switch)

;; keyboard / command-palette entry points (no click target): only fire in the meaningful direction, and only
;; for a previewable doc (markdown/org — the kinds that stamp data-vv-source-* and have both views).
(defn- previewable-doc? [db]
  (contains? #{"markdown" "org"} (:doc/kind (ds/active-doc (ds/snapshot) (facet/active-content-path db)))))
(rf/reg-event-fx
 :jump/goto-source                                        ; from preview → source (derives the viewport line)
 (fn [{:keys [db]} _]
   (when (and (not= :source (facet/active-type db)) (previewable-doc? db)) {:fx [[:jump/to-source-current nil]]})))
(rf/reg-event-fx
 :jump/goto-preview                                       ; from source → preview (derives the cursor line)
 (fn [{:keys [db]} _]
   (when (and (= :source (facet/active-type db)) (previewable-doc? db)) {:fx [[:jump/to-preview-current nil]]})))

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

;; back/forward act on the ACTIVE tab's own history (per-tab, browser-like) + restore that entry's VIEW (facet) and
;; position (facet-aware). nav/step returns the target entry; history-result reloads + ensure-loads the facet file.
(rf/reg-event-fx
 :history/back
 [(rf/inject-cofx :view-pos)]
 (fn [{:keys [db view-pos]} _]
   (if-let [[db' uri entry] (nav/step db -1 view-pos)]
     (history-result db' uri entry)
     {})))

(rf/reg-event-fx
 :history/forward
 [(rf/inject-cofx :view-pos)]
 (fn [{:keys [db view-pos]} _]
   (if-let [[db' uri entry] (nav/step db 1 view-pos)]
     (history-result db' uri entry)
     {})))

;; Alt+Up: navigate the active tab to the PARENT directory of the current file:// uri (no-op for http /
;; at the filesystem root). The came-from child is pre-highlighted so Alt+Down returns to it.
(rf/reg-event-fx
 :nav/parent
 [(rf/inject-cofx :view-pos)]
 (fn [{:keys [db view-pos]} _]
   (let [cur (nav/active-uri db)]
     (if-let [parent (uri/dirname cur)]
       (let [db' (-> (nav/nav-active db parent view-pos)
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
 (fn [{:keys [db]} [_ tab-id text]]
   ;; A path-completion reply is asynchronous. If the user has already changed tabs, the abandoned Enter
   ;; must not navigate whichever tab happens to be active when that reply lands.
   (if (not= tab-id (nav/active-id db))
     {}
     (cond
       (str/blank? text) {}
       (uri/http? text)  {:fx [[:dispatch [:tab/navigate (uri/normalize text)]]]}
       :else
       (let [uc (get-in db [:ui :uri-complete])]
         (if (and (= tab-id (:tab-id uc))
                  (= text (:typed-input uc))
                  (= text (:input uc)))
           {:fx [[:dispatch [:uri-complete/decide-enter tab-id (assoc uc :input text)]]]}
           {:db (update-in db [:ui :uri-complete] merge {:tab-id tab-id :typed-input text})
            :fx [[:vv/complete-path {:input text
                                     :tag {:kind :enter :tab-id tab-id :input text}}]]}))))))

;; ---- URI-bar path auto-completion ----
(defn- prefix-matches
  "Entries whose basename prefix-matches `base` (dotfiles hidden unless base starts with '.'), sorted
   dirs-first then by name (the directory browser's order)."
  [entries base]
  (nav/sort-entries (filter #(uri/matches-prefix? (:name %) base) entries)))

(def ^:private uri-complete-empty
  {:tab-id nil :typed-input nil :input nil :dir nil :entries [] :target nil :exists? false :dir? false
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
 (fn [{:keys [db]} [_ tab-id text]]
   (if (not= tab-id (nav/active-id db))
     {}
     (if (or (str/blank? text) (uri/http? text))
       {:db (assoc-in db [:ui :uri-complete]
                     (assoc uri-complete-empty :tab-id tab-id :typed-input text))}
       {:db (update-in db [:ui :uri-complete] merge
                       {:tab-id tab-id :typed-input text
                        :selected -1 :dismissed? false :error? false})
        :fx [[:vv/complete-path {:input text
                                 :tag {:kind :live :tab-id tab-id :input text}}]]}))))

(rf/reg-event-fx
 :uri-complete/decide-enter
 (fn [{:keys [db]} [_ tab-id {:keys [input entries exists? target]}]]
   (if (or (not= tab-id (nav/active-id db))
           (not= tab-id (get-in db [:ui :uri-complete :tab-id]))
           (not= input (get-in db [:ui :uri-complete :typed-input])))
     {}
     (let [[_ base] (uri/complete-split (or input ""))
           ml       (first (prefix-matches entries base))]
       (cond
         exists? {:fx [[:dispatch [:tab/navigate target]] [:dispatch [:uri-complete/clear]]]}
         ml      {:fx [[:dispatch [:tab/navigate (:path ml)]] [:dispatch [:uri-complete/clear]]]}
         :else   {:db (assoc-in db [:ui :uri-complete :error?] true)
                  :fx [[:uri-complete/error-timeout]]})))))

(rf/reg-event-fx
 :uri-complete/result
 (fn [{:keys [db]} [_ tag payload]]
   (let [{:keys [kind tab-id input]} tag
         current? (and (= tab-id (nav/active-id db))
                       (= tab-id (get-in db [:ui :uri-complete :tab-id]))
                       (= input (get-in db [:ui :uri-complete :typed-input])))]
     (cond
       (not current?) {}
       (= kind :enter) {:fx [[:dispatch [:uri-complete/decide-enter tab-id payload]]]}
       :else {:db (update-in db [:ui :uri-complete] merge
                            (-> (select-keys payload [:input :dir :entries :exists? :dir? :target])
                                (update :entries vec)))}))))

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

;; A link inside a web page asked for a popup (target=_blank, middle-click, Ctrl+click, window.open).
;; Main DENIES the native window and relays it here (ADR-0044), so the destination lands in an ordinary
;; app tab — background for a middle-/Ctrl-click gesture, focused otherwise (Chromium's disposition,
;; mapped main-side by vinary.main.web-policy). Fail closed: only http(s) urls become tabs.
(rf/reg-event-fx
 :web/open-tab
 (fn [_ [_ {:keys [url mode]}]]
   (when (and url (uri/http? url))
     {:fx [[:dispatch [(if (= mode "background") :tab/open-background :tab/open) url]]]})))

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

;; ---- Files tree: project data + controlled expansion -----------------------------------------------
(defn- tree-root-sync-fx [projects]
  [:vv/sync-tree-roots (mapv :root projects)])

(defn- prune-tree-ui [db]
  (let [projects (get-in db [:ui :projects])]
    (-> db
        (update-in [:ui :tree-open] #(tree-state/prune-scopes projects (or % #{})))
        (update-in [:ui :tree-expanding] #(tree-state/prune-scopes projects (or % #{}))))))

(defn- apply-tree-entry [db entry]
  (let [before   (into #{} (map :root) (get-in db [:ui :projects]))
        projects (projects/apply-tree-update (get-in db [:ui :projects]) entry)
        after    (into #{} (map :root) projects)
        added    (set/difference after before)]
    (-> db
        (assoc-in [:ui :projects] projects)
        ;; A newly delivered project may render open immediately: this very payload is its fresh listing.
        (update-in [:ui :tree-open] (fnil into #{}) (map (fn [root] [root root]) added))
        prune-tree-ui)))

;; Main pushes both initial/full entries and scoped updates produced by expanded-directory watchers.
;; The post-render continuation opens the active row's ancestors declaratively, then scrolls it.
(rf/reg-event-fx
 :tree/received
 (fn [{:keys [db]} [_ entry]]
   (let [db' (apply-tree-entry db entry)]
     {:db db'
      :fx (cond-> [(tree-root-sync-fx (get-in db' [:ui :projects]))]
            ;; Scoped watcher updates must not tug sidebar scroll back to the active row. Initial project
            ;; arrivals still reveal it (notably when command-line activation won the race with vv:tree).
            (nil? (:scope entry)) (conj [:dispatch [:tree/reveal-active]]))})))

;; drop a project from the sidebar (project-header context menu). It returns if a file under it is
;; opened again — send-tree! runs from main's open!, not from a watcher refresh.
(rf/reg-event-fx
 :tree/remove-project
 (fn [{:keys [db]} [_ root]]
   (let [db' (-> db
                 (update-in [:ui :projects] projects/remove-project root)
                 prune-tree-ui)]
     {:db db' :fx [(tree-root-sync-fx (get-in db' [:ui :projects]))]})))

(rf/reg-event-fx
 :tree/reveal-active
 (fn [{:keys [db]} _]
   (let [scopes (tree-state/active-scopes (get-in db [:ui :projects]) (nav/active-path db))]
     {:db (cond-> db (seq scopes) (update-in [:ui :tree-open] (fnil into #{}) scopes))
      :fx [[:tree/reveal-active nil]]})))

(rf/reg-event-fx
 :tree/expand
 (fn [{:keys [db]} [_ root directory]]
   (let [scope [root directory]]
     (when-not (or (contains? (get-in db [:ui :tree-open] #{}) scope)
                   (contains? (get-in db [:ui :tree-expanding] #{}) scope))
       {:db (update-in db [:ui :tree-expanding] (fnil conj #{}) scope)
        :fx [[:vv/refresh-tree {:root root :path directory
                                :on-success [:tree/expand-ready scope]
                                :on-failure [:tree/expand-failed scope]}]]}))))

(rf/reg-event-fx
 :tree/expand-ready
 (fn [{:keys [db]} [_ scope entry]]
   ;; A project can be removed while the invoke is in flight; only a still-pending expansion may reopen.
   (if-not (contains? (get-in db [:ui :tree-expanding] #{}) scope)
     {:db db}
     (let [db'   (-> db (apply-tree-entry entry)
                     (update-in [:ui :tree-expanding] disj scope))
           known (tree-state/directory-scopes (get-in db' [:ui :projects]))
           db'   (cond-> db' (contains? known scope) (update-in [:ui :tree-open] conj scope))]
       {:db db'}))))

(rf/reg-event-db
 :tree/expand-failed
 (fn [db [_ scope _message]]
   (update-in db [:ui :tree-expanding] disj scope)))

(rf/reg-event-db
 :tree/collapse
 (fn [db [_ root directory]]
   (update-in db [:ui :tree-open] disj [root directory])))

(rf/reg-event-fx
 :tree/refresh
 (fn [_ [_ {:keys [root path]}]]
   {:fx [[:vv/refresh-tree {:root root :path path
                            :on-success [:tree/refresh-ready root]
                            :on-failure [:tree/refresh-failed]}]]}))

(rf/reg-event-fx
 :tree/refresh-ready
 (fn [{:keys [db]} [_ requested-root entry]]
   ;; Removing a project is authoritative for the current sidebar. A reply that was already in flight may
   ;; refresh an extant root, but it must not resurrect the root after Remove from Files.
   (if-not (some #(= requested-root (:root %)) (get-in db [:ui :projects]))
     {:db db}
     (let [db' (apply-tree-entry db entry)]
       {:db db' :fx [(tree-root-sync-fx (get-in db' [:ui :projects]))]}))))

(rf/reg-event-db :tree/refresh-failed (fn [db _] db))

(rf/reg-event-fx
 :tree/refresh-all
 (fn [_ _]
   {:fx [[:vv/refresh-all-trees {:on-success [:tree/refresh-all-ready]
                                 :on-failure [:tree/refresh-all-failed]}]]}))

(rf/reg-event-fx
 :tree/refresh-all-ready
 (fn [{:keys [db]} [_ entries]]
   (let [present (into #{} (map :root) (get-in db [:ui :projects]))
         ;; As above, Refresh All operates on the set that is still present when its reply commits.
         db'     (reduce apply-tree-entry db (filter #(contains? present (:root %)) entries))]
     {:db db' :fx [(tree-root-sync-fx (get-in db' [:ui :projects]))]})))

(rf/reg-event-db :tree/refresh-all-failed (fn [db _] db))

(rf/reg-event-fx
 :tree/sync-expanded
 (fn [_ [_ scopes]]
   {:fx [[:vv/sync-tree-expanded
          (mapv (fn [[root directory]] {:root root :path directory}) scopes)]]}))

;; Debounced, because committing the query is what re-narrows and re-folds every path of every open
;; project (:tree/filtered). The field itself never waits — vinary.ui.text-input owns its DOM value — so
;; the only thing this defers is the LIST catching up, once per pause instead of once per character.
(def ^:private tree-debounce-ms 90)

(rf/reg-event-fx
 :tree/filter
 (fn [_ [_ q]]
   {:fx [[:async/debounce {:key :tree/filter :ms tree-debounce-ms :dispatch [:tree/filter-commit q]}]]}))

(rf/reg-event-db
 :tree/filter-commit
 (fn [db [_ q]] (assoc-in db [:ui :tree-filter] q)))

;; ---- in-page find ----
;; Every request carries a GENERATION. A search is asynchronous (it first materializes a PDF's text layers
;; or drains a stream, so it covers the whole document rather than the rendered-so-far prefix), and typing
;; issues one per keystroke — so without a generation the reply from an earlier, shorter query could land
;; last and overwrite the counter for the query actually in the box.
;;
;; The generation is no longer ALSO the debounce: :async/debounce keeps one live timer per key and cancels
;; it, so a superseded request stops instead of firing into a check that discards it. The generation stays
;; because it answers the other question — an in-flight search that has already begun cannot be un-begun,
;; and its reply must still be recognised as stale when it lands (find-e2e ASSERT H10).
;;
;; 40 ms, down from 100: the search is chunked now (vinary.renderer.find), so arming it costs a frame of
;; sliced work that the next keystroke cancels, not a full document walk that must be waited out. The
;; debounce is here to avoid churning that work per character, not to protect the thread from it.
(def ^:private find-debounce-ms 40)
(def ^:private find-debounce-key :find/search)

(defn- bump-gen [db] (update-in db [:ui :find :gen] (fnil inc 0)))

(rf/reg-event-fx
 :find/toggle
 (fn [{:keys [db]} _]
   (let [vis (not (get-in db [:ui :find :visible?]))
         q   (get-in db [:ui :find :query])
         db' (-> db (assoc-in [:ui :find :visible?] vis) bump-gen)]
     (if vis
       ;; re-opening with a query already in the box re-runs it, so the counter and the highlights match
       ;; what is shown instead of being a stale number over an unpainted document
       {:db db'
        :fx (if (str/blank? q) [] [[:find/search {:q q :gen (get-in db' [:ui :find :gen])}]])}
       ;; closing cancels a pending debounce as well as clearing the paint: without it, a search armed by
       ;; the last character typed would land after the bar is gone and re-paint highlights over a
       ;; document the user is no longer searching
       {:db db' :fx [[:async/cancel find-debounce-key] [:find/clear]]}))))

(rf/reg-event-fx
 :find/set-query
 (fn [{:keys [db]} [_ q]]
   (let [db' (-> db (assoc-in [:ui :find :query] q) bump-gen)
         gen (get-in db' [:ui :find :gen])]
     {:db db'
      :fx [[:async/debounce {:key find-debounce-key :ms find-debounce-ms :dispatch [:find/run gen]}]]})))

;; the debounce landing point: run only if this is still the newest request
(rf/reg-event-fx
 :find/run
 (fn [{:keys [db]} [_ gen]]
   (when (= gen (get-in db [:ui :find :gen]))
     {:fx [[:find/search {:q (get-in db [:ui :find :query]) :gen gen}]]})))

;; banks BOTH scalars at once. cycle! can change the COUNT as well as the index — reconciling stale ranges
;; against a changed document is part of cycling — which the old count-only / index-only pair could not
;; express, so a re-collect left the counter lying.
(rf/reg-event-db
 :find/result
 (fn [db [_ {:keys [gen count idx]}]]
   (if (or (nil? gen) (= gen (get-in db [:ui :find :gen])))
     (-> db (assoc-in [:ui :find :count] count) (assoc-in [:ui :find :idx] idx))
     db)))

(rf/reg-event-fx
 :find/cycle
 (fn [{:keys [db]} [_ dir]]
   {:fx [[:find/cycle {:dir dir :gen (get-in db [:ui :find :gen])}]]}))

;; a different document is showing: drop the highlights and the counter, KEEP the query text so re-opening
;; find re-runs it against what is now on screen
(rf/reg-event-fx
 :find/reset
 (fn [{:keys [db]} _]
   {:db (-> db (assoc-in [:ui :find :count] 0) (assoc-in [:ui :find :idx] 0) bump-gen)
    :fx [[:async/cancel find-debounce-key] [:find/clear]]}))

(rf/reg-event-fx
 :find/close
 (fn [{:keys [db]} _]
   {:db (-> db (assoc-in [:ui :find :visible?] false) bump-gen)
    :fx [[:async/cancel find-debounce-key] [:find/clear]]}))

;; ---- table of contents ----
;; jump to a section: in the web view (HTTP) ask its preload to scroll; in Markdown scroll the content
(rf/reg-event-fx
 :toc/goto
 (fn [{:keys [db]} [_ id]]
   (if (uri/http? (nav/active-uri db))
     {:fx [[:vv/http-toc-goto id]]}
     ;; a Contents click on a COLLAPSED diff file is explicit intent to SEE it: auto-expand the target
     ;; (state first, DOM applied before the scroll so the offset measures the expanded layout — ADR-0037)
     (if (and (facet/diff-preview-active? db) (contains? (nav/diff-collapsed db) id))
       (let [db' (nav/toggle-diff-collapsed db (nav/active-id db) id)]
         {:db db' :fx [[:diff/apply-collapsed (nav/diff-collapsed db')]
                       [:toc/scroll id]]})
       {:fx [[:toc/scroll id]]}))))

(rf/reg-event-db
 :toc/active-heading
 (fn [db [_ id]] (assoc-in db [:ui :active-heading] id)))

;; ---- command-target events (the keybinding command registry dispatches these) ----
(def ^:private theme-cycle ["spacemacs-dark" "spacemacs-light"])

(rf/reg-event-fx
 :tab/next
 [(rf/inject-cofx :view-pos)]
 (fn [{:keys [db view-pos]} _]
   (when-let [id (nav/nth-id db 1)] (activate-tab-result db view-pos id))))

(rf/reg-event-fx
 :tab/prev
 [(rf/inject-cofx :view-pos)]
 (fn [{:keys [db view-pos]} _]
   (when-let [id (nav/nth-id db -1)] (activate-tab-result db view-pos id))))

(defn- files-restore-result [db]
  (let [scopes (tree-state/open-project-roots (get-in db [:ui :projects])
                                               (get-in db [:ui :tree-open] #{}))
        db'    (-> db
                   (assoc-in [:ui :sidebar-visible?] true)
                   (assoc-in [:ui :sidebar-tab] :files)
                   (assoc-in [:ui :tree-restoring?] (boolean (seq scopes))))]
    (cond-> {:db db'}
      (seq scopes) (assoc :fx [[:vv/refresh-trees {:scopes scopes
                                                   :on-complete [:tree/restore-ready]}]]))))

(rf/reg-event-fx
 :tree/restore-ready
 (fn [{:keys [db]} [_ results]]
   (let [db' (reduce (fn [state {:keys [entry]}]
                       (if entry (apply-tree-entry state entry) state))
                     db results)
         db' (reduce (fn [state {:keys [scope error]}]
                       (if error
                         (update-in state [:ui :tree-open] disj [(:root scope) (:path scope)])
                         state))
                     db' results)
         db' (assoc-in db' [:ui :tree-restoring?] false)]
     {:db db'
      :fx [(tree-root-sync-fx (get-in db' [:ui :projects]))
           [:dispatch [:tree/reveal-active]]]})))

(rf/reg-event-fx
 :sidebar/toggle
 (fn [{:keys [db]} _]
   (let [vis      (not (get-in db [:ui :sidebar-visible?]))
         settings (assoc (get-in db [:ui :settings]) :sidebar-visible? vis)
         base     (-> db (assoc-in [:ui :sidebar-visible?] vis) (assoc-in [:ui :settings] settings))
         result   (if (and vis (= :files (get-in db [:ui :sidebar-tab])))
                    (files-restore-result base)
                    {:db base})]
     (update result :fx #(conj (vec (or % [])) [:vv/save-settings (pr-str settings)])))))

(rf/reg-event-fx
 :sidebar/tab
 (fn [{:keys [db]} [_ tab]]
   (if (and (= tab :files) (not= :files (get-in db [:ui :sidebar-tab])))
     (files-restore-result db)
     {:db (-> db
              (assoc-in [:ui :sidebar-tab] tab)
              (cond-> (not= tab :files) (assoc-in [:ui :tree-restoring?] false)))})))
(rf/reg-event-fx
 :sidebar/width
 (fn [{:keys [db]} [_ w]]
   ;; min 180: the TOTAL sidebar width includes the 36px icon rail (ADR-0041) — the panel column
   ;; keeps ≥ ~143px beside it. An old persisted 140-179 self-heals via the CSS min-width.
   (let [w        (-> w (max 180) (min 720))
         settings (assoc (get-in db [:ui :settings]) :sidebar-width w)]
     {:db (-> db (assoc-in [:ui :sidebar-width] w) (assoc-in [:ui :settings] settings))
      :fx [[:vv/save-settings (pr-str settings)]]})))
;; the .vv-content ResizeObserver mirror (views/content-width-ref, ADR-0043). Value-guarded so the
;; debounced observer may dispatch freely (coalesced repeats are free); every real change re-checks
;; the active diff's auto-split — crossing into "wide" with an unchosen diff builds its side-by-side
;; HTML right then (window resize, the sidebar splitter/toggle, and zoom all funnel through here).
(rf/reg-event-fx
 :ui/content-width
 (fn [{:keys [db]} [_ w]]
   (when (not= w (get-in db [:ui :content-width]))
     {:db (assoc-in db [:ui :content-width] w)
      :fx [[:dispatch [:diff/ensure-split]]]})))
;; show the Files tab (used by "Reveal in tree" + the directory context menu); the active file's ancestors
;; are auto-expanded by file-tree's reveal-active!
(rf/reg-event-fx :sidebar/reveal (fn [{:keys [db]} _] (files-restore-result db)))
(rf/reg-event-fx
 :sidebar/show
 (fn [{:keys [db]} [_ tab]]
   (if (= tab :files)
     (files-restore-result db)
     {:db (-> db (assoc-in [:ui :sidebar-visible?] true) (assoc-in [:ui :sidebar-tab] tab))})))

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
   ;; seed the native dialog's folder from an ORDERED candidate chain: the active file/dir, then the
   ;; most-recently-opened files (persisted recent-files MRU). Main opens in the first candidate that still
   ;; resolves to a real directory (a file → its parent, a dir → itself), else the OS home dir — walking the
   ;; chain lets a since-deleted higher-priority path fall through to the next instead of skipping to home.
   (let [seeds (->> (cons (nav/dialog-seed-path db)
                          (filter nav/local-fs-path? (get-in db [:ui :recent :recent-files])))
                    (keep identity) distinct vec)]
     {:db (assoc-in db [:ui :open-dialog-mode] (open-dialog-mode mode))
      :fx [[:vv/open-dialog seeds]]})))
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

;; Change one setting (a font family/size) → apply + persist.
;;
;; The db write is immediate — the dialog must reflect the choice at once — but APPLYING it is debounced.
;; :fonts/apply re-measures every figure and every Mermaid diagram on screen whenever a size changes
;; (figures/refit-all!, mermaid/refit-all!), and running that per character typed into a font field is
;; what made the Preferences inputs drop keys: typing `Noto Sans` produced `Not Sans`, with the tracer
;; recording the clobbering write as `Noto` → `Not` (docs/scientific/10). Live preview is kept — the user
;; asked for it — it simply lands once per pause instead of once per character.
;;
;; `pr-str` moves behind the same debounce: serialising the whole settings map per keystroke was pure
;; waste, since only :vv/save-settings (itself debounced) ever reads the string.
(def ^:private settings-apply-ms 150)

(rf/reg-event-fx
 :settings/set
 (fn [{:keys [db]} [_ k v]]
   {:db (assoc-in db [:ui :settings k] v)
    :fx [[:async/debounce {:key :settings/apply :ms settings-apply-ms
                           :dispatch [:settings/apply]}]]}))

(rf/reg-event-fx
 :settings/apply
 (fn [{:keys [db]} _]
   (let [settings (get-in db [:ui :settings])]
     {:fx [[:fonts/apply settings] [:vv/save-settings (pr-str settings)]]})))

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
    (vec (mapcat (fn [{:keys [root files extras]}]
                   (concat
                    ;; ADR-0038 extras render pinned first, so keyboard order matches visual order;
                    ;; they filter by display name (they have no root-relative path)
                    (->> extras
                         (filter #(or (nil? q) (str/includes? (str/lower-case (str (:name %))) q)))
                         (sort-by (comp str :name))
                         (map :path))
                    (->> files
                         (filter #(or (nil? q) (str/includes? (str/lower-case %) q)))
                         (map #(str root "/" %)))))
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
