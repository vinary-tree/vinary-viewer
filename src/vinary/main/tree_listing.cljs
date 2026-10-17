(ns vinary.main.tree-listing
  "The Files tree's listings, run asynchronously and one per root at a time (docs/scientific/10 §11; ADR-0034's
   2026-10-17 amendment). Electron-free, so the node :test build runs it against real git:

     git-stdout   an asynchronous `git <args>` — the synchronous helper it replaces blocked main's event loop for as
                  long as git ran, which is what froze typing in the Files filter (§11's attribution)
     toplevel     the repository containing a directory
     repo-files   a repository's navigable files
     list-key     the real lister: a repository, or the walks of a synthetic root's requested directories
     the runner   interprets vinary.main.listing-flight's effects: at most one listing per root at a time, a request
                  never joining the listing already running, a burst of requests meanwhile sharing ONE trailing
                  listing

   Every listing is numbered, and an entry built from it carries the number as its STAMP, {:epoch :n}: the renderer
   applies a root's entries as though they had arrived in stamp order (vinary.app.projects), so a reply that waited —
   an aggregated Refresh All, a target daemon's forwarded reply — can never put an older listing over a newer one.
   `epoch` (this process's start) orders stamps across a daemon restart."
  (:require ["child_process" :as cp]
            [clojure.string :as str]
            [vinary.main.dir-walk :as dir-walk]
            [vinary.main.listing-flight :as lf]))

(defonce epoch (js/Date.now))

(defn stamp
  "The stamp of listing number `n`: {:epoch :n}, compared as [epoch n]."
  [n]
  {:epoch epoch :n n})

;; ---- census ----------------------------------------------------------------------------------------------------

(def ^:private empty-totals
  {:requests      {:call 0 :watch 0}   ; listing requests, by requester kind
   :collapsed     0                    ; requests equal to one already queued (they share its delivery)
   :listings      {:git 0 :walk 0}     ; listings launched, by key kind
   :failed        0                    ; listings that produced nothing (git failed, timed out, or the lister threw)
   :waited-behind {}                   ; listings each delivered request waited behind → count (the model: 0 or 1)
   :peak-running  0                    ; the most listings of ONE key running at once (the model: 1)
   :git           {}})                 ; git processes started, by subcommand

(defonce ^:private totals (atom empty-totals))

(defn census
  "The process-wide listing census (a map; see `empty-totals`)."
  []
  @totals)

(defn ^:export stats
  "The census as JS — what find-e2e's diagnostics print (global.vinary.main.tree_listing.stats(); exported, so a
   release build keeps the name)."
  []
  (clj->js @totals))

;; ---- git -------------------------------------------------------------------------------------------------------

(def max-output-bytes
  "The most stdout a listing may produce. A monorepo's `ls-files` is tens of MiB at worst; beyond this the listing
   fails (as the synchronous helper's maxBuffer made it fail) rather than growing main without bound."
  (* 64 1024 1024))

(def timeout-ms
  "How long a git process may run before it is killed and its listing fails: a guard against a hung process (a dead
   network mount), not a performance bound — the flight of its root waits for it."
  120000)

(defn git-stdout
  "A Promise of the trimmed stdout of `git <args>` run in `cwd`, or of nil — git missing, a non-zero exit, more than
   max-output-bytes, or killed after timeout-ms. Never rejects. An argv array and no shell; stdin and stderr are
   ignored (so a chatty stderr can never fail a listing). `opts` {:env :timeout-ms :max-bytes} exists for the tests.

   The child's stdout chunks are concatenated ONCE at exit and decoded then, so a multi-byte character split across
   two chunks decodes intact."
  ([args cwd] (git-stdout args cwd nil))
  ([args cwd {:keys [env] :as opts}]
   (js/Promise.
    (fn [resolve _]
      (let [done?  (volatile! false)
            timer  (volatile! nil)
            finish (fn [v]
                     (when-not @done?
                       (vreset! done? true)
                       (some-> @timer js/clearTimeout)
                       (resolve v)))]
        (try
          (let [^js child (cp/spawn "git" (clj->js args)
                                    (cond-> #js {:cwd cwd :stdio #js ["ignore" "pipe" "ignore"] :windowsHide true}
                                      env (doto (aset "env" (clj->js env)))))
                chunks    #js []
                size      (volatile! 0)]
            (swap! totals update-in [:git (first args)] (fnil inc 0))
            (vreset! timer (doto (js/setTimeout (fn [] (.kill child) (finish nil)) (or (:timeout-ms opts) timeout-ms))
                             (.unref)))
            (.on (.-stdout child) "data"
                 (fn [^js b]
                   (when-not @done?
                     (vswap! size + (.-length b))
                     (if (> @size (or (:max-bytes opts) max-output-bytes))
                       (do (.kill child) (finish nil))
                       (.push chunks b)))))
            (.on child "error" (fn [_] (finish nil)))
            (.on child "close" (fn [code _]
                                 (finish (when (= 0 code)
                                           (str/trim (.toString (js/Buffer.concat chunks) "utf8")))))))
          (catch :default _ (finish nil))))))))

(defn toplevel
  "A Promise of the root of the git repository containing directory `dir`, or of nil (no repository, or no git)."
  ([dir] (toplevel dir nil))
  ([dir opts]
   (-> (git-stdout ["rev-parse" "--show-toplevel"] dir opts)
       (.then (fn [out] (when-not (str/blank? out) out))))))

(defn repo-files
  "A Promise of the navigable files of the repository rooted at `root` (repo-relative, git's order), or of nil when git
   fails. `--cached` (tracked) plus `--others --exclude-standard` (untracked, not ignored) shows the files being
   written right now — including the one just opened — while keeping build output out. `--cached` describes the
   INDEX, so a tracked path deleted or renamed only in the working tree is still in it: git's `--deleted` set is
   subtracted, keeping a rename's destination (which `--others` lists). The two run one after the other: one git
   process per root at a time."
  ([root] (repo-files root nil))
  ([root opts]
   (-> (git-stdout ["ls-files" "--cached" "--others" "--exclude-standard"] root opts)
       (.then (fn [out]
                (when out
                  (-> (git-stdout ["ls-files" "--deleted"] root opts)
                      (.then (fn [deleted-out]
                               (when deleted-out
                                 (let [deleted (into #{} (remove str/blank?) (str/split deleted-out #"\n"))]
                                   (into [] (comp (remove str/blank?) (remove deleted)) (str/split out #"\n")))))))))))))

(defn list-key
  "The real lister (the service's runner `:list!`): a Promise of the listing of flight key `k` for the directories
   `needs`. A git key [:git root] lists the whole repository (`needs` does not matter: every scope is a projection of
   one listing). A synthetic key [:walk root] walks each needed directory — each from itself, as its own depth and
   entry bounds require — one after another, into {directory → files relative to it}."
  [[kind root] needs]
  (case kind
    :git  (repo-files root)
    :walk (reduce (fn [p dir]
                    (.then p (fn [m] (.then (dir-walk/walk-dir dir) (fn [files] (assoc m dir files))))))
                  (js/Promise.resolve {})
                  (sort needs))))

;; ---- the runner ------------------------------------------------------------------------------------------------
;; Requesters (opaque to the model):
;;   [:call n need]     a Promise-returning caller (listing!); its resolve waits in :calls under n
;;   [:watch scope gen] a watcher's refresh (request-watch!); equal ones collapse, and a round's are delivered at once

(defn make-runner
  "A listing runner. `list!` (fn [key needs]) → a Promise of the listing, nil on failure; `defer!` (fn [thunk]) runs
   thunk on a LATER macrotask — js/setImmediate in the service — so every request of one event-loop turn shares the
   listing it launches; `on-watch` (fn [key n due result]) receives every watcher refresh listing n serves, all at
   once: `due` is the set of [scope gen] and `result` the raw listing (nil when it failed)."
  [{:keys [list! defer! on-watch]}]
  (atom {:flights      lf/empty-flights
         :calls        {}            ; call number → resolve
         :next         0             ; the last call number issued
         :launched     {}            ; key → listings launched so far
         :launch-index {}            ; key → the launch index of its running listing
         :running      {}            ; key → how many of its listings are running (the model allows one)
         :asked        {}            ; [key requester] → FIFO of {:launched :running?}, one per membership
         :opts         {:list! list! :defer! defer! :on-watch on-watch}}))

(defn- step!
  "Apply listing-flight transition `f` to the runner's flights. → its effects."
  [rn f & args]
  (let [[flights effects] (apply f (:flights @rn) args)]
    (swap! rn assoc :flights flights)
    effects))

(defn- need-of [[kind a b]]
  (case kind
    :call  b            ; [:call n need]
    :watch (second a)   ; [:watch [root directory] gen]
    nil))

(defn- record-request!
  "Census and waited-behind bookkeeping for requester `r` of key `k`, BEFORE the model sees it."
  [rn k r]
  (swap! totals update-in [:requests (first r)] inc)
  (let [{:keys [flights launched]} @rn]
    (if (contains? (lf/queued flights k) r)
      (swap! totals update :collapsed inc)          ; it joins the identical queued request's delivery
      (swap! rn update-in [:asked [k r]] (fnil conj [])
             {:launched (get launched k 0) :running? (some? (lf/running flights k))}))))

(defn- waited-behind
  "How many listings of its key a delivered request waited behind: one if a listing was running when it asked, plus
   every listing launched after it that is not the one serving it — 0 or 1 by the model (served by the FIRST listing
   launched after it, behind at most the one then running). A negative value would mean a listing launched BEFORE
   the request served it."
  [{:keys [launched running?]} serving-index]
  (+ (if running? 1 0) (- serving-index launched 1)))

(declare run-effects!)

(defn- launch!
  "Effect [:list k s requesters]: start listing `s`. The lister is called synchronously, so the listing begins after
   every request it serves."
  [rn k s requesters]
  (let [index   (inc (get-in @rn [:launched k] 0))
        running (inc (get-in @rn [:running k] 0))]
    (swap! rn #(-> % (assoc-in [:launched k] index) (assoc-in [:launch-index k] index) (assoc-in [:running k] running)))
    (swap! totals #(-> % (update-in [:listings (first k)] inc) (update :peak-running max running)))
    (-> (try (js/Promise.resolve ((:list! (:opts @rn)) k (into #{} (keep need-of) requesters)))
             (catch :default e (js/Promise.reject e)))
        (.catch (fn [e] (js/console.error "[tree] listing" (pr-str k) "failed:" e) nil))
        (.then (fn [result] (run-effects! rn (step! rn lf/complete k s) result)))
        (.catch (fn [e] (js/console.error "[tree] delivering listing" (pr-str k) "failed:" e))))))

(defn- deliver!
  "Effect [:deliver k s requesters]: hand listing `s`'s result to every requester — each call's Promise, then the
   round's watcher refreshes in ONE on-watch call. One failing requester never keeps the others from theirs."
  [rn k s requesters result]
  (let [index (get-in @rn [:launch-index k])]
    (swap! rn (fn [st]
                (let [st      (update st :launch-index dissoc k)
                      running (dec (get-in st [:running k] 1))]
                  (if (pos? running) (assoc-in st [:running k] running) (update st :running dissoc k)))))
    (when (nil? result) (swap! totals update :failed inc))
    (doseq [r requesters]
      (let [[rec & more] (get-in @rn [:asked [k r]])]
        (when rec
          (swap! totals update-in [:waited-behind (waited-behind rec index)] (fnil inc 0))
          (swap! rn (fn [st] (if (seq more) (assoc-in st [:asked [k r]] (vec more)) (update st :asked dissoc [k r])))))))
    (doseq [[_ n need] (filter #(= :call (first %)) requesters)]
      (when-let [resolve (get-in @rn [:calls n])]
        (swap! rn update :calls dissoc n)
        (resolve {:seq    s
                  :result (case (first k)
                            :git  result
                            :walk (when result (get result need)))})))
    (when-let [due (seq (into #{} (keep (fn [[kind scope gen]] (when (= :watch kind) [scope gen]))) requesters))]
      (try ((:on-watch (:opts @rn)) k s (set due) result)
           (catch :default e (js/console.error "[tree] delivering watcher refreshes of" (pr-str k) "failed:" e))))))

(defn- run-effects!
  "Interpret listing-flight effects; `result` is the listing a [:deliver] hands out."
  [rn effects result]
  (doseq [[op k x y] effects]
    (case op
      :schedule ((:defer! (:opts @rn)) (fn [] (run-effects! rn (step! rn lf/start k) nil)))
      :list     (launch! rn k x y)
      :deliver  (deliver! rn k x y result))))

(defn listing!
  "A Promise of {:seq n :result r} — listing n of key `k`, the first launched after this call, so it reflects every
   change made before it. For a git key r is the repository's files; for a synthetic key, the files under directory
   `need` (relative to it). r is nil when the listing failed. Never rejects."
  [rn k need]
  (js/Promise.
   (fn [resolve _]
     (let [n (:next (swap! rn update :next inc))
           r [:call n need]]
       (swap! rn assoc-in [:calls n] resolve)
       (record-request! rn k r)
       (run-effects! rn (step! rn lf/request k r) nil)))))

(defn request-watch!
  "Ask for a listing of `k` on behalf of the watcher of `scope` ([root directory]) in generation `gen`; it reaches
   on-watch with the round's other refreshes."
  [rn k scope gen]
  (let [r [:watch scope gen]]
    (record-request! rn k r)
    (run-effects! rn (step! rn lf/request k r) nil)))
