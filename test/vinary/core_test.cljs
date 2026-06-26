(ns vinary.core-test
  "Unit tests for the pure, DOM-free logic: key normalization, keymap merge, the keybinding resolver,
   command gating, and DataScript helpers. Run with: npx shadow-cljs compile test && node dist/test/test.js"
  (:require [cljs.test :refer [deftest is testing run-tests]]
            [clojure.string :as str]
            [datascript.core :as d]
            [vinary.input.keys :as keys]
            [vinary.input.keymap :as keymap]
            [vinary.input.resolver :as resolver]
            [vinary.input.keymaps-registry :as registry]
            [vinary.input.kbedit-history :as hist]
            [vinary.app.commands :as commands]
            [vinary.app.nav :as nav]
            [vinary.app.link :as link]
            [vinary.app.ds :as ds]
            [vinary.grammar-catalog :as grammar-catalog]
            [vinary.renderer.hints :as hints]
            [vinary.renderer.media :as media]))

(def ^:private empty-tabs {:ui {:tabs [] :active-tab nil :next-tab-id 0}})
(def ^:private empty-keymaps {:ui {:keymaps {:active "default" :order [] :sets {}}}})

(defn- ev [m] (clj->js m))

(deftest key-normalization
  (testing "modifier folding + named keys"
    (is (= "C-f"   (keys/event->chord (ev {:key "f" :ctrlKey true}) false)))
    (is (= "C-f"   (keys/event->chord (ev {:key "f" :metaKey true}) true)) "⌘ folds to C- on mac")
    (is (= "M-left" (keys/event->chord (ev {:key "ArrowLeft" :altKey true}) false)))
    (is (= "G"     (keys/event->chord (ev {:key "G" :shiftKey true}) false)) "Shift folds into a printable")
    (is (= "S-tab" (keys/event->chord (ev {:key "Tab" :shiftKey true}) false)) "Shift kept for named keys")
    (is (= "space" (keys/event->chord (ev {:key " "}) false)))
    (is (nil? (keys/event->chord (ev {:key "Control"}) false)) "modifier-only → nil"))
  (testing "bare-printable?"
    (is (true? (keys/bare-printable? "g")))
    (is (false? (keys/bare-printable? "C-g")))
    (is (false? (keys/bare-printable? "space")))))

(deftest keymap-merge
  (testing "install-user! {:extends :vim} keeps the vim modes (deep-merge nil bug regression)"
    (keymap/install-user! {:extends :vim})
    (let [modes (keymap/modes)]
      (is (= :nav/scroll-down (get-in modes [:normal "j"])))
      (is (map? (get-in modes [:normal "g"])) "g is a prefix")
      (is (= :normal (keymap/initial-mode))))
    (testing "user override + :unbind"
      (keymap/install-user! {:extends :vim :keymaps {:normal {"H" :history/back "gt-not-real" :unbind}}})
      (is (= :history/back (get-in (keymap/modes) [:normal "H"])))))
  (testing "default keymap reproduces the baseline"
    (keymap/install! :default)
    (is (= :search/start (get-in (keymap/modes) [:all "C-f"])))
    (is (= :history/back (get-in (keymap/modes) [:all "M-left"])))))

(deftest resolver-step
  (let [modes {:normal {"j" :nav/scroll-down "g" {"g" :nav/scroll-top}} :all {"escape" :input/escape}}]
    (testing "leaf → dispatch"
      (is (= {:action :dispatch :command :nav/scroll-down}
             (resolver/step modes :normal [] "j" {:in-input? false}))))
    (testing "prefix → :prefix then leaf"
      (is (= :prefix (:action (resolver/step modes :normal [] "g" {:in-input? false}))))
      (is (= :nav/scroll-top (:command (resolver/step modes :normal ["g"] "g" {:in-input? false})))))
    (testing "miss at start in normal mode consumes; in insert passes"
      (is (= :consume (:action (resolver/step modes :normal [] "z" {:in-input? false}))))
      (is (= :pass    (:action (resolver/step modes :insert [] "z" {:in-input? false})))))
    (testing "miss mid-sequence retries"
      (is (= :retry (:action (resolver/step modes :normal ["g"] "z" {:in-input? false})))))))

(deftest command-gating
  (testing ":when predicates gate run/all-visible"
    (is (true?  (commands/allowed? :tab/next {:tabs [1 2]})))
    (is (false? (commands/allowed? :tab/next {:tabs []})) ":has-tabs fails with no tabs")
    (is (false? (commands/allowed? :history/back {:can-back? false})))
    (is (true?  (commands/allowed? :search/start {}))  "no :when ⇒ always"))
  (testing "all-visible filters by :when"
    (let [ids (set (map :id (commands/all-visible {:tabs [] :can-back? false})))]
      (is (not (contains? ids :tab/next)))
      (is (contains? ids :search/start)))))

(deftest datascript-helpers
  (let [conn (d/create-conn {:doc/path {:db/unique :db.unique/identity}})]
    (d/transact! conn [{:doc/path "/a" :doc/order 0 :doc/open? true :doc/kind "markdown"}
                       {:doc/path "/b" :doc/order 1 :doc/open? true :doc/kind "text"}])
    (is (= 2 (ds/next-order @conn)) "next-order = max+1")
    (is (= ["/a" "/b"] (mapv :path (ds/open-docs @conn))) "ordered by :doc/order")
    (is (some? (ds/eid-for-path @conn "/a")))
    (is (= "markdown" (ds/doc-attr @conn "/a" :doc/kind)))))

;; ---- navigation: per-tab history with scroll (Phase A) + reorder/view-source (Phase B) ----
(deftest nav-history-scroll
  (let [db1 (nav/add-tab empty-tabs "/a")
        db2 (nav/nav-active db1 "/b" 100)]          ; leave /a at scroll 100, go to /b
    (testing "add-tab creates a {:uri :scroll} history entry"
      (is (= "/a" (nav/active-uri db1)))
      (is (= [{:uri "/a" :scroll 0}] (get-in (nav/active-tab db1) [:hist :stack]))))
    (testing "nav-active saves the leaving scroll + pushes a new entry"
      (is (= "/b" (nav/active-uri db2)))
      (is (= 100 (get-in (nav/active-tab db2) [:hist :stack 0 :scroll])) "/a's scroll saved")
      (is (= 1 (get-in (nav/active-tab db2) [:hist :idx]))))
    (testing "step back returns the prior uri + its saved scroll"
      (let [[db3 uri sc] (nav/step db2 -1 50)]       ; leave /b at scroll 50
        (is (= "/a" uri))
        (is (= 100 sc) "restores /a's scroll")
        (is (= 50 (get-in (nav/active-tab db3) [:hist :stack 1 :scroll])) "/b's scroll saved")))))

(deftest nav-reorder-and-source
  (let [db (-> empty-tabs (nav/add-tab "/a") (nav/add-tab "/b") (nav/add-tab "/c"))]  ; ids 0,1,2
    (testing "reorder moves a tab to an insertion gap"
      (is (= ["/c" "/a" "/b"] (mapv :uri (nav/tabs (nav/reorder db 2 0)))) "c → front")
      (is (= ["/a" "/c" "/b"] (mapv :uri (nav/tabs (nav/reorder db 2 1)))) "c → gap 1"))
    (testing "view-source toggles per tab"
      (is (false? (nav/view-source? db)))
      (is (true?  (nav/view-source? (nav/toggle-source db))))
      (is (false? (nav/view-source? (nav/toggle-source (nav/toggle-source db))))))))

(deftest bundled-grammar-catalog
  (testing "catalog entries expose required runtime fields"
    (is (seq grammar-catalog/bundled-grammars))
    (doseq [g grammar-catalog/bundled-grammars]
      (is (string? (:id g)))
      (is (string? (:language g)))
      (is (seq (:extensions g)))
      (is (every? #(and (string? %) (str/starts-with? % ".")) (:extensions g)))
      (is (string? (:wasm-url g)))
      (is (string? (:scm-url g)))))
  (testing "source extensions are derived from the bundled catalog"
    (is (contains? grammar-catalog/bundled-source-exts ".rho"))))

;; ---- keymap-set registry (Phase C) ----
(deftest keymaps-registry
  (let [db1 (registry/add-custom empty-keymaps "Mine" {:extends :vim :keymaps {}})]
    (testing "built-ins + custom listing"
      (is (= ["default" "vim" "emacs"] (mapv :id registry/builtins)))
      (is (registry/builtin? "vim"))
      (is (not (registry/builtin? "Mine")))
      (is (= ["default" "vim" "emacs" "Mine"] (registry/set-ids db1)))
      (is (registry/custom? db1 "Mine")))
    (testing "add-custom appends + activates"
      (is (= "Mine" (registry/active-id db1)))
      (is (= ["Mine"] (registry/order db1))))
    (testing "rename keeps order + active; delete falls back to default"
      (let [db2 (registry/rename-custom db1 "Mine" "Yours")]
        (is (= ["Yours"] (registry/order db2)))
        (is (= "Yours" (registry/active-id db2))))
      (let [db3 (registry/delete-custom db1 "Mine")]
        (is (= [] (registry/order db3)))
        (is (= "default" (registry/active-id db3)))))
    (testing "clone-set snapshots a built-in's cfg"
      (let [db4 (registry/clone-set empty-keymaps "vim")
            nm  (first (registry/order db4))]
        (is (= 1 (count (registry/order db4))))
        (is (= {:extends :vim :keymaps {}} (get-in db4 [:ui :keymaps :sets nm])))))
    (testing "modal? + default-mode"
      (is (registry/modal? empty-keymaps "vim"))
      (is (not (registry/modal? empty-keymaps "default")))
      (is (= :normal (registry/default-mode empty-keymaps "vim")))
      (is (= :insert (registry/default-mode empty-keymaps "default"))))
    (testing "action-index reverse-indexes effective bindings"
      (let [idx (registry/action-index db1 "Mine")]
        (is (contains? idx :nav/scroll-down))
        (is (some (fn [[_mode path]] (= path ["j"])) (get idx :nav/scroll-down)))))
    (testing "normalize-config envelope + legacy delta"
      (is (= {:active "default" :order [] :sets {}} (registry/normalize-config nil)))
      (is (= "x" (:active (registry/normalize-config {:active "x" :order ["x"] :sets {"x" {}}}))))
      (is (= ["Custom"] (:order (registry/normalize-config {:extends :vim :keymaps {}})))))))

;; ---- key-binding editor undo/redo command model (Phase D) ----
(deftest kbedit-history-roundtrip
  (let [db0 {:ui {:keymaps {:active "default" :order ["M"] :sets {"M" {:extends :vim :keymaps {}}}}}}]
    (testing ":put a chord-sequence, invert prunes back to nil"
      (let [cmd {:op :put :set-id "M" :mode :normal :chords ["C-x" "C-f"] :value :tab/close :prev nil}
            db1 (hist/apply-cmd db0 cmd)]
        (is (= :tab/close (get-in db1 [:ui :keymaps :sets "M" :keymaps :normal "C-x" "C-f"])))
        (is (nil? (get-in (hist/apply-cmd db1 (hist/invert cmd))
                          [:ui :keymaps :sets "M" :keymaps :normal "C-x"])))))
    (testing ":insert-set / :remove-set are mutual inverses (preserve index)"
      (let [add {:op :insert-set :name "N" :cfg {:extends :default :keymaps {}} :idx 1}
            db1 (hist/apply-cmd db0 add)]
        (is (= ["M" "N"] (get-in db1 [:ui :keymaps :order])))
        (is (= ["M"] (get-in (hist/apply-cmd db1 (hist/invert add)) [:ui :keymaps :order])))))
    (testing ":rename-set inverts by swapping; :reorder swaps from/to"
      (is (= {:op :rename-set :old "B" :new "A"} (hist/invert {:op :rename-set :old "A" :new "B"})))
      (is (= {:op :reorder :name "X" :from-idx 2 :to-idx 0}
             (hist/invert {:op :reorder :name "X" :from-idx 0 :to-idx 2}))))))

;; ---- Vimium link hints (Phase E) ----
(deftest hint-labels
  (testing "uniform-length, unique labels"
    (is (= ["S"] (hints/labels 1)))
    (is (= 5 (count (hints/labels 5))))
    (is (every? #(= 1 (count %)) (hints/labels 10)) "≤ alphabet → 1-char")
    (is (every? #(= 2 (count %)) (hints/labels 30)) "> alphabet → 2-char")
    (is (= 30 (count (hints/labels 30))))
    (is (apply distinct? (hints/labels 40)) "labels unique")
    (is (= [] (hints/labels 0)))))

(deftest link-classify
  (is (= :http   (:kind (link/classify "https://example.com" "x"))))
  (is (nil?      (link/classify "" "x")))
  (is (= :anchor (:kind (link/classify "#sec" "x"))))
  (is (= "sec"   (:path (link/classify "#sec" "x"))))
  (is (= :file   (:kind (link/classify "file:///a/b.md" "x"))))
  (is (= "/a/b.md" (:path (link/classify "file:///a/b.md" "x"))))
  (is (= :dir    (:kind (link/classify "file:///a/" "x")))))

(deftest local-media-url-cache-busting
  (testing "local file media urls get a stable cache-busting parameter"
    (is (= "file:///tmp/diagram.svg?vv-cache=42"
           (media/cache-bust-local-media-url "file:///tmp/diagram.svg" 42)))
    (is (= "file:///tmp/diagram.svg?x=1&vv-cache=42#frag"
           (media/cache-bust-local-media-url "file:///tmp/diagram.svg?x=1#frag" 42)))
    (is (= "file:///tmp/diagram.svg?vv-cache=43"
           (media/cache-bust-local-media-url "file:///tmp/diagram.svg?vv-cache=42" 43))))
  (testing "non-local and non-media urls are unchanged"
    (is (= "https://example.com/diagram.svg"
           (media/cache-bust-local-media-url "https://example.com/diagram.svg" 42)))
    (is (= "file:///tmp/readme.md"
           (media/cache-bust-local-media-url "file:///tmp/readme.md" 42)))
    (is (= "docs/diagram.svg"
           (media/cache-bust-local-media-url "docs/diagram.svg" 42))))
  (testing "local media paths strip cache tokens and decode filesystem paths"
    (is (= "/tmp/a b.svg" (media/local-media-path "file:///tmp/a%20b.svg?vv-cache=42")))
    (is (nil? (media/local-media-path "file:///tmp/readme.md"))))
  (testing "filesystem paths are encoded as file urls"
    (is (= "file:///tmp/a%20b%23c.svg" (media/path->file-url "/tmp/a b#c.svg")))))

(defn -main [& _] (run-tests))
