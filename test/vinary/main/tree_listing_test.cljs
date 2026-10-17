(ns vinary.main.tree-listing-test
  "The Files tree's listing runner and git listers (vinary.main.tree-listing; docs/scientific/10 §11).

   The runner is driven with a FAKE lister whose listings the test completes by hand and a manual defer queue, so the
   coalescing is observed exactly: one listing per event-loop turn's requests, a request never joining the running
   listing, a burst meanwhile costing one trailing listing, failures caching nothing. The listers run REAL git against
   a throwaway repository with a hermetic environment (no global or system config, HOME in the temp directory), and a
   fake `git` on PATH stands in for the failures real git cannot be made to produce on demand (a hang, a flood)."
  (:require [cljs.test :refer [deftest is testing async]]
            ["child_process" :as cp]
            ["fs" :as fs]
            ["os" :as os]
            ["path" :as path]
            [vinary.main.tree-listing :as tl]))

;; ---- a hand-driven runner -----------------------------------------------------------------------------------------

(defn- deferred []
  (let [d #js {}]
    (set! (.-promise d) (js/Promise. (fn [resolve reject] (set! (.-resolve d) resolve) (set! (.-reject d) reject))))
    d))

(defn- fake-runner
  "A runner whose lister records each call and returns a deferred the test settles, and whose defer! queues thunks
   until (drain! f) runs them. → {:rn :calls (atom [[key needs deferred]…]) :watched (atom [[key n due result]…])
   :drain! fn}"
  ([] (fake-runner nil))
  ([{:keys [on-watch list!]}]
   (let [calls   (atom [])
         queue   (atom [])
         watched (atom [])
         rn      (tl/make-runner
                  {:list!    (or list!
                                 (fn [k needs]
                                   (let [d (deferred)]
                                     (swap! calls conj [k needs d])
                                     (.-promise d))))
                   :defer!   (fn [thunk] (swap! queue conj thunk))
                   :on-watch (or on-watch (fn [k n due result] (swap! watched conj [k n due result])))})]
     {:rn      rn
      :calls   calls
      :watched watched
      :drain!  (fn [] (let [thunks @queue] (reset! queue []) (doseq [t thunks] (t)) (count thunks)))})))

(defn- settle
  "A Promise that resolves after pending Promise continuations (and the next macrotask) have run."
  []
  (js/Promise. (fn [resolve] (js/setTimeout resolve 0))))

(defn- census-delta
  "The census numbers that changed between `before` and now: requests, listings, failed, git, waited-behind."
  [before]
  (let [now (tl/census)]
    {:requests      (merge-with - (:requests now) (:requests before))
     :listings      (merge-with - (:listings now) (:listings before))
     :failed        (- (:failed now) (:failed before))
     :collapsed     (- (:collapsed now) (:collapsed before))
     :git           (into {} (remove (comp zero? val)) (merge-with - (:git now) (:git before)))
     :waited-behind (into {} (remove (comp zero? val)) (merge-with - (:waited-behind now) (:waited-behind before)))}))

(def ^:private k [:git "/repo"])

(deftest one-turn-of-requests-shares-one-listing
  (async done
    (let [{:keys [rn calls drain!]} (fake-runner)
          before (tl/census)
          ps     (mapv #(tl/listing! rn k (str "/repo/d" %)) (range 5))]
      (testing "nothing is listed until the deferred start runs: every request of this turn joins it"
        (is (= [] @calls)))
      (is (= 1 (drain!)) "one start was scheduled, not five")
      (is (= 1 (count @calls)))
      (is (= k (ffirst @calls)))
      (.resolve (nth (first @calls) 2) ["a.md" "b.md"])
      (-> (js/Promise.all (into-array ps))
          (.then (fn [results]
                   (is (= (repeat 5 {:seq (:seq (first results)) :result ["a.md" "b.md"]}) (vec results))
                       "every caller receives the same listing")
                   (let [d (census-delta before)]
                     (is (= 5 (get-in d [:requests :call])))
                     (is (= 1 (get-in d [:listings :git])))
                     (is (= {0 5} (:waited-behind d)) "none of them waited behind a running listing"))))
          (.finally done)))))

(deftest a-burst-during-a-listing-costs-one-trailing-listing
  (async done
    (let [{:keys [rn calls drain!]} (fake-runner)
          before    (tl/census)
          first-seq (volatile! nil)
          first*    (tl/listing! rn k "/repo")]
      (drain!)
      (let [later (mapv (fn [_] (tl/listing! rn k "/repo")) (range 3))]
        (testing "requests while a listing runs never join it, and launch nothing yet"
          (is (= 0 (drain!)))
          (is (= 1 (count @calls))))
        (.resolve (nth (first @calls) 2) ["v1.md"])
        (-> first*
            (.then (fn [r]
                     (is (= ["v1.md"] (:result r)) "the running listing serves only the request made before it")
                     (vreset! first-seq (:seq r))
                     (testing "its completion scheduled exactly one trailing listing for all three"
                       (is (= 1 (drain!)))
                       (is (= 2 (count @calls))))
                     (.resolve (nth (second @calls) 2) ["v1.md" "v2.md"])
                     (js/Promise.all (into-array later))))
            (.then (fn [results]
                     (is (every? #(= ["v1.md" "v2.md"] (:result %)) results))
                     (is (apply = (map :seq results)) "one trailing listing served all three")
                     (is (> (:seq (first results)) @first-seq) "…and it is a newer listing")
                     (let [d (census-delta before)]
                       (is (= 2 (get-in d [:listings :git])) "four requests, two listings")
                       (is (= {0 1 1 3} (:waited-behind d)) "each later request waited behind exactly one listing"))))
            (.finally done))))))

(deftest a-failed-listing-caches-nothing
  (async done
    (let [{:keys [rn calls drain!]} (fake-runner)
          before (tl/census)
          p1     (tl/listing! rn k "/repo")]
      (drain!)
      (.resolve (nth (first @calls) 2) nil)
      (-> p1
          (.then (fn [r]
                   (is (nil? (:result r)) "a failed listing reaches its callers as nil")
                   (let [p2 (tl/listing! rn k "/repo")]
                     (drain!)
                     (is (= 2 (count @calls)) "the next request lists afresh")
                     (.reject (nth (second @calls) 2) (js/Error. "boom"))
                     p2)))
          (.then (fn [r]
                   (is (nil? (:result r)) "a rejecting lister also reaches its callers as nil")
                   (is (= 2 (:failed (census-delta before))))))
          (.finally done)))))

(deftest a-throwing-lister-still-completes-its-round
  (async done
    (let [n     (atom 0)
          {:keys [rn drain!]} (fake-runner {:list! (fn [_ _] (swap! n inc) (throw (js/Error. "synchronous boom")))})
          p     (tl/listing! rn k "/repo")]
      (drain!)
      (-> p
          (.then (fn [r]
                   (is (nil? (:result r)))
                   (let [p2 (tl/listing! rn k "/repo")]
                     (drain!)
                     p2)))
          (.then (fn [r]
                   (is (nil? (:result r)))
                   (is (= 2 @n) "the key was idle again: the second request launched a second listing")))
          (.finally done)))))

(deftest watcher-refreshes-arrive-together-and-a-throwing-delivery-blocks-nothing
  (async done
    (let [seen   (atom [])
          {:keys [rn calls drain!]} (fake-runner {:on-watch (fn [k n due result]
                                                               (swap! seen conj [k n due result])
                                                               (throw (js/Error. "a window vanished mid-send")))})]
      (tl/request-watch! rn k ["/repo" "/repo/a"] 1)
      (tl/request-watch! rn k ["/repo" "/repo/b"] 2)
      (tl/request-watch! rn k ["/repo" "/repo/a"] 1)                        ; the same watcher again: collapses
      (drain!)
      (let [p (tl/listing! rn k "/repo")]                                   ; arrives during the listing
        (.resolve (nth (first @calls) 2) ["a/x.md" "b/y.md"])
        (-> (settle)
            (.then (fn [_]
                     (is (= 1 (count @seen)) "one on-watch call for the whole round")
                     (let [[k' n due result] (first @seen)]
                       (is (= k k'))
                       (is (number? n))
                       (is (= #{[["/repo" "/repo/a"] 1] [["/repo" "/repo/b"] 2]} due))
                       (is (= ["a/x.md" "b/y.md"] result)))
                     (testing "the throw did not keep the trailing round from starting"
                       (is (= 1 (drain!)))
                       (is (= 2 (count @calls))))
                     (.resolve (nth (second @calls) 2) ["a/x.md"])
                     p))
            (.then (fn [r] (is (= ["a/x.md"] (:result r)))))
            (.finally done))))))

(deftest a-synthetic-round-walks-the-union-of-its-directories
  (async done
    (let [{:keys [rn calls drain!]} (fake-runner)
          w  [:walk "/notes"]
          pa (tl/listing! rn w "/notes")
          pb (tl/listing! rn w "/notes/sub")]
      (tl/request-watch! rn w ["/notes" "/notes/deep"] 7)
      (drain!)
      (let [[key needs d] (first @calls)]
        (is (= w key))
        (is (= #{"/notes" "/notes/sub" "/notes/deep"} needs) "every requested directory, once")
        (.resolve d {"/notes" ["a.md" "sub/b.md"] "/notes/sub" ["b.md"] "/notes/deep" ["c.md"]}))
      (-> (js/Promise.all #js [pa pb])
          (.then (fn [[a b]]
                   (is (= ["a.md" "sub/b.md"] (:result a)) "each caller gets the walk of ITS directory")
                   (is (= ["b.md"] (:result b)))))
          (.finally done)))))

(deftest listing-numbers-increase-and-stamps-carry-the-epoch
  (async done
    (let [{:keys [rn calls drain!]} (fake-runner)
          p1 (tl/listing! rn [:git "/one"] "/one")
          p2 (tl/listing! rn [:git "/two"] "/two")]
      (drain!)
      (doseq [[_ _ d] @calls] (.resolve d []))
      (-> (js/Promise.all #js [p1 p2])
          (.then (fn [[a b]]
                   (is (not= (:seq a) (:seq b)))
                   (is (= {:epoch tl/epoch :n (:seq a)} (tl/stamp (:seq a))))
                   (is (number? tl/epoch))))
          (.finally done)))))

;; ---- real git ------------------------------------------------------------------------------------------------------

(defn- tmp-dir! []
  (.realpathSync fs (.mkdtempSync fs (path/join (.tmpdir os) "vv-tree-listing-"))))

(defn- hermetic-env
  "A git environment that ignores the host's global and system configuration (a global core.excludesFile would
   change what --exclude-standard excludes)."
  [tmp]
  ;; process.env is an exotic object js->clj leaves unconverted: copy it key by key
  (merge (into {} (map (fn [k] [k (aget (.-env js/process) k)])) (js-keys (.-env js/process)))
         {"HOME"                tmp
          "GIT_CONFIG_GLOBAL"   (path/join tmp "no-global-gitconfig")
          "GIT_CONFIG_SYSTEM"   (path/join tmp "no-system-gitconfig")
          "GIT_CONFIG_NOSYSTEM" "1"
          "GIT_TERMINAL_PROMPT" "0"}))

(defn- git! [env cwd & args]
  (cp/execFileSync "git" (clj->js args) #js {:cwd cwd :env (clj->js env) :stdio #js ["ignore" "ignore" "ignore"]}))

(defn- write! [root rel content]
  (let [abs (path/join root rel)]
    (.mkdirSync fs (path/dirname abs) #js {:recursive true})
    (.writeFileSync fs abs content)
    abs))

(defn- make-repo!
  "A repository with a tracked file, a nested tracked file, an untracked file, an ignored file and an unstaged rename."
  [tmp env]
  (let [repo (path/join tmp "repo")]
    (write! repo "README.md" "# r\n")
    (write! repo "docs/old-name.md" "tracked\n")
    (write! repo ".gitignore" "*.log\n")
    (git! env repo "-c" "init.defaultBranch=main" "init" "-q")
    (git! env repo "add" "README.md" "docs/old-name.md" ".gitignore")
    (git! env repo "-c" "user.email=vv@example.com" "-c" "user.name=VV" "-c" "commit.gpgsign=false"
          "commit" "-q" "-m" "init")
    (write! repo "docs/fresh.md" "untracked\n")
    (write! repo "noise.log" "ignored\n")
    (.renameSync fs (path/join repo "docs/old-name.md") (path/join repo "docs/new-name.md"))
    repo))

(defn- with-repo
  "Run (f tmp repo env) — a Promise — against a fresh repository, always cleaning up, then call `done`."
  [done f]
  (let [tmp (tmp-dir!)
        env (hermetic-env tmp)]
    (-> (js/Promise.resolve nil)
        (.then (fn [_] (f tmp (make-repo! tmp env) env)))
        (.catch (fn [e] (is (nil? e) (str "unexpected error: " e))))
        (.finally (fn [] (.rmSync fs tmp #js {:recursive true :force true}) (done))))))

(deftest repo-files-lists-what-the-files-tree-should-show
  (async done
    (with-repo done
      (fn [_ repo env]
        (-> (tl/repo-files repo {:env env})
            (.then (fn [files]
                     (let [fs* (set files)]
                       (is (contains? fs* "README.md") "tracked")
                       (is (contains? fs* ".gitignore") "tracked, hidden")
                       (is (contains? fs* "docs/fresh.md") "untracked and not ignored: the file just written appears")
                       (is (not (contains? fs* "noise.log")) "ignored")
                       (is (contains? fs* "docs/new-name.md") "an unstaged rename's destination appears…")
                       (is (not (contains? fs* "docs/old-name.md")) "…and its deleted source does not")
                       (is (= (count files) (count fs*)) "no path twice")))))))))

(deftest toplevel-finds-the-repository-or-nothing
  (async done
    (with-repo done
      (fn [tmp repo env]
        (-> (js/Promise.all #js [(tl/toplevel (path/join repo "docs") {:env env})
                                 (tl/toplevel tmp {:env env})
                                 (tl/toplevel (path/join tmp "does-not-exist") {:env env})])
            (.then (fn [[nested outside missing]]
                     (is (= repo nested) "from a nested directory, the repository's root")
                     (is (nil? outside) "a directory in no repository")
                     (is (nil? missing) "a directory that does not exist"))))))))

(deftest git-stdout-fails-closed
  (async done
    (with-repo done
      (fn [tmp repo env]
        (-> (tl/git-stdout ["rev-parse" "--verify" "no-such-ref"] repo {:env env})
            (.then (fn [out] (is (nil? out) "a non-zero exit is nil, not an exception")))
            (.then (fn [_] (tl/git-stdout ["rev-parse" "--show-toplevel"] repo {:env (assoc env "PATH" (path/join tmp "empty-path"))})))
            (.then (fn [out] (is (nil? out) "no git on PATH is nil"))))))))

(defn- fake-git!
  "A directory holding an executable `git` that runs `body` (POSIX sh), for the failures real git does not produce."
  [tmp body]
  (let [bin (path/join tmp "fake-bin")]
    (.mkdirSync fs bin #js {:recursive true})
    (write! bin "git" (str "#!/bin/sh\n" body "\n"))
    (.chmodSync fs (path/join bin "git") 0755)
    bin))

(deftest a-hung-or-flooding-git-fails-without-blocking
  (async done
    (if (= "win32" (.-platform js/process))
      (do (is true "POSIX shell fakes only") (done))
      (with-repo done
        (fn [tmp repo env]
          (let [hang  (fake-git! (path/join tmp "h") "exec sleep 30")
                flood (fake-git! (path/join tmp "f") "exec head -c 200000 /dev/zero")
                t0    (js/Date.now)]
            (-> (tl/git-stdout ["ls-files"] repo {:env (assoc env "PATH" hang) :timeout-ms 150})
                (.then (fn [out]
                         (is (nil? out) "a hung git is killed and its listing fails")
                         (is (< (- (js/Date.now) t0) 5000) "…at the timeout, not when git would have finished")))
                (.then (fn [_] (tl/git-stdout ["ls-files"] repo {:env (assoc env "PATH" flood) :max-bytes 1000})))
                (.then (fn [out] (is (nil? out) "output beyond the bound fails the listing"))))))))))

(deftest ten-same-turn-requests-cost-one-repository-listing
  (async done
    (with-repo done
      (fn [_ repo env]
        (let [rn     (tl/make-runner {:list!    (fn [[_ root] _] (tl/repo-files root {:env env}))
                                      :defer!   js/setImmediate
                                      :on-watch (fn [& _])})
              before (tl/census)]
          (-> (js/Promise.all (into-array (repeatedly 10 #(tl/listing! rn [:git repo] repo))))
              (.then (fn [results]
                       (is (apply = (map :result results)) "one listing, ten identical answers")
                       (is (seq (:result (first results))))
                       (is (= {"ls-files" 2} (:git (census-delta before)))
                           "two ls-files processes (--cached… and --deleted), not twenty")))))))))

(deftest list-key-walks-a-synthetic-root-per-directory
  (async done
    (let [tmp (tmp-dir!)]
      (write! tmp "a.md" "a")
      (write! tmp "sub/b.md" "b")
      (-> (tl/list-key [:walk tmp] #{tmp (path/join tmp "sub")})
          (.then (fn [m]
                   (is (= #{"a.md" "sub/b.md"} (set (get m tmp))))
                   (is (= ["b.md"] (get m (path/join tmp "sub"))) "each directory walked from itself")))
          (.finally (fn [] (.rmSync fs tmp #js {:recursive true :force true}) (done)))))))

(deftest stats-is-the-census-as-js
  (let [^js s (tl/stats)]
    (is (object? s))
    (is (= #{"requests" "collapsed" "listings" "failed" "waited-behind" "peak-running" "git"} (set (js-keys s))))
    (is (= (get-in (tl/census) [:listings :git]) (.. s -listings -git)))))
