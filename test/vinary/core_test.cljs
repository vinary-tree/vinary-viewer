(ns vinary.core-test
  "Unit tests for the pure, DOM-free logic: key normalization, keymap merge, the keybinding resolver,
   command gating, and DataScript helpers. Run with: npx shadow-cljs compile test && node dist/test/test.js"
  (:require [cljs.test :refer [async deftest is testing run-tests]]
            [clojure.string :as str]
            [datascript.core :as d]
            [vinary.input.keys :as keys]
            [vinary.input.keymap :as keymap]
            [vinary.input.resolver :as resolver]
            [vinary.input.keymaps-registry :as registry]
            [vinary.input.kbedit-history :as hist]
            [vinary.app.commands :as commands]
            [vinary.app.events :as events]
            [vinary.app.nav :as nav]
            [vinary.app.link :as link]
            [vinary.app.ds :as ds]
            [vinary.app.uri :as uri]
            [vinary.grammar-catalog :as grammar-catalog]
            [vinary.main.file-kind :as file-kind]
            [vinary.main.service-util :as service-util]
            [vinary.main.startup :as startup]
            [vinary.main.ext-util :as eu]
            [vinary.main.password-util :as pw-util]
            [vinary.main.password-adapters :as pw-adapters]
            [vinary.renderer.hints :as hints]
            [vinary.renderer.pdf-layout :as pdf-layout]
            [vinary.renderer.virtual-layout :as vl]
            [vinary.renderer.history-input :as history-input]
            [vinary.renderer.markdown :as markdown]
            [vinary.renderer.math :as math]
            [vinary.renderer.media :as media]
            [vinary.ui.access-keys :as access]
            [vinary.ui.context-menu :as context-menu]
            [vinary.ui.palette :as palette]
            [vinary.ui.keybindings-editor :as kbe]
            [vinary.ui.menu-focus :as menu-focus]
            [vinary.ui.menubar :as menubar]
            [vinary.ui.preview-context :as preview-context]
            [vinary.ui.preview-navigation :as preview-nav]))

(def ^:private empty-tabs {:ui {:tabs [] :active-tab nil :next-tab-id 0}})
(def ^:private empty-keymaps {:ui {:keymaps {:active "default" :order [] :sets {}}}})

(defn- ev [m] (clj->js m))

(deftest content-route
  (testing "a real directory lists even when its extensionless name classifies as text (EISDIR fix)"
    (is (= :directory (service-util/route {:directory? true :archive? false :kind "text"})))
    (is (= :directory (service-util/route {:directory? true :archive? false :kind "markdown"})))
    (is (= :directory (service-util/route {:directory? true :archive? false :kind "image"}))))
  (testing "non-directory paths route by archive/kind, unchanged"
    (is (= :parsed (service-util/route {:directory? false :archive? true  :kind "text"})))
    (is (= :parsed (service-util/route {:directory? false :archive? false :kind "text"})))
    (is (= :parsed (service-util/route {:directory? false :archive? false :kind "log"})))
    (is (= :parsed (service-util/route {:directory? false :archive? false :kind "table"})))
    (is (= :parsed (service-util/route {:directory? false :archive? false :kind "office"})))
    (is (= :image (service-util/route {:directory? false :archive? false :kind "image"})))
    (is (= :html  (service-util/route {:directory? false :archive? false :kind "html"})))
    (is (= :pdf   (service-util/route {:directory? false :archive? false :kind "pdf"})))
    (is (= :text  (service-util/route {:directory? false :archive? false :kind "source"})))
    (is (= :text  (service-util/route {:directory? false :archive? false :kind "markdown"})))))

(deftest startup-switches
  (testing "software-compositor present/raster artifact fixes ship as invariant startup switches"
    (is (contains? (set startup/chromium-switches) "ui-disable-partial-swap")
        "core/main must always append --ui-disable-partial-swap (PRESENT stage; defensive, NOT the band fix — that's disable-hardware-acceleration?)")
    (is (contains? (set startup/chromium-switches) "disable-partial-raster")
        "core/main must always append --disable-partial-raster (RASTER stage: tile-content reuse)")
    (is (contains? (set startup/chromium-switches) "disable-gpu-sandbox")
        "core/main must always append --disable-gpu-sandbox (Linux GPU driver access)")))

(deftest gpu-mode
  (testing "Wayland disables GPU rasterization (the band fix; keeps the GPU process on)"
    (is (true?  (startup/disable-gpu-rasterization? {:XDG_SESSION_TYPE "wayland"})))
    (is (true?  (startup/disable-gpu-rasterization? {:WAYLAND_DISPLAY "wayland-0"})))
    (is (false? (startup/disable-gpu-rasterization? {:XDG_SESSION_TYPE "x11"})))
    (is (false? (startup/disable-gpu-rasterization? {})))
    (is (false? (startup/disable-gpu-rasterization? {:WAYLAND_DISPLAY ""})))
    (is (false? (startup/disable-gpu-rasterization? {:VV_GPU_RASTER "1" :XDG_SESSION_TYPE "wayland"}))
        "VV_GPU_RASTER=1 opts out (force full GPU rasterization)"))
  (testing "full-software (remove the GPU process) only when VV_SOFTWARE_GL is set"
    (is (true?  (startup/disable-hardware-acceleration? {:VV_SOFTWARE_GL "1"})))
    (is (false? (startup/disable-hardware-acceleration? {:XDG_SESSION_TYPE "wayland"})))
    (is (false? (startup/disable-hardware-acceleration? {})))))

(deftest cli-doc-args
  (testing "non-flag file/URI command-line arguments → normalized tab uris, in order"
    ;; a fake cwd-relative resolver (node's path.resolve is injected in core.cljs); absolute stays put
    (let [resolve-abs (fn [p] (if (str/starts-with? p "/") p (str "/cwd/" p)))]
      ;; argv[0]=electron, argv[1]=app path; user args from index 2. Flags dropped; order preserved.
      (is (= ["/cwd/a.md" "/abs/b.pdf" "https://example.com"]
             (startup/doc-uris ["electron" "/app" "a.md" "--inspect" "/abs/b.pdf" "https://example.com"]
                               resolve-abs)))
      ;; file:// reduced to its (absolute) path; http(s) and vv-archive:// kept verbatim
      (is (= ["/etc/hostname" "vv-archive://open?chain=%5B%22%2Fz.zip%22%5D"]
             (startup/doc-uris ["e" "/app" "file:///etc/hostname" "vv-archive://open?chain=%5B%22%2Fz.zip%22%5D"]
                               resolve-abs)))
      ;; relative paths resolved against the launch cwd
      (is (= ["/cwd/docs/x.md"] (startup/doc-uris ["e" "/app" "docs/x.md"] resolve-abs)))
      ;; no user args, flags-only, and blanks all drop out
      (is (= [] (startup/doc-uris ["e" "/app"] resolve-abs)))
      (is (= [] (startup/doc-uris ["e" "/app" "--foo" "-x"] resolve-abs)))
      (is (= ["/cwd/a.md"] (startup/doc-uris ["e" "/app" "" "a.md"] resolve-abs))))))

(deftest key-normalization
  (testing "modifier folding + named keys"
    (is (= "C-f"   (keys/event->chord (ev {:key "f" :ctrlKey true}) false)))
    (is (= "C-f"   (keys/event->chord (ev {:key "f" :metaKey true}) true)) "⌘ folds to C- on mac")
    (is (= "M-left" (keys/event->chord (ev {:key "ArrowLeft" :altKey true}) false)))
    (is (nil? (access/event-letter (ev {:key "ArrowLeft" :altKey true})))
        "Alt+Left is not a menu access key")
    (is (= "f" (access/event-letter (ev {:key "f" :altKey true}))))
    (is (= "G"     (keys/event->chord (ev {:key "G" :shiftKey true}) false)) "Shift folds into a printable")
    (is (= "C-S-o" (keys/event->chord (ev {:key "O" :ctrlKey true :shiftKey true}) false))
        "modified shifted letters keep explicit Shift so Ctrl+Shift+O resolves from previews")
    (is (= "C-S-z" (keys/event->chord (ev {:key "Z" :ctrlKey true :shiftKey true}) false))
        "redo uses normalized Ctrl+Shift+Z")
    (is (= "C-+"   (keys/event->chord (ev {:key "+" :ctrlKey true :shiftKey true}) false))
        "modified shifted punctuation stays as the typed printable")
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
    (is (= :history/back (get-in (keymap/modes) [:all "M-left"]))))
  (testing "file dialog shortcuts resolve across bundled keymaps"
    (doseq [preset [:default :vim :emacs]]
      (keymap/install! preset)
      (is (= :file/open-dialog (get-in (keymap/modes) [:all "C-o"]))
          (str preset " C-o"))
      (is (= :file/open-dialog-new-tab (get-in (keymap/modes) [:all "C-S-o"]))
          (str preset " C-S-o")))
    (keymap/install! :vim)
    (is (= :file/open-dialog (:command (resolver/step (keymap/modes) :normal [] "C-o" {:in-input? false})))
        "vim normal C-o opens the file dialog"))
  (testing "Ctrl-t opens a new blank tab across bundled keymaps"
    (doseq [preset [:default :vim :emacs]]
      (keymap/install! preset)
      (is (= :tab/new-blank (get-in (keymap/modes) [:all "C-t"]))
          (str preset " C-t"))))
  (testing "Ctrl-l focuses the URI bar across bundled keymaps"
    (doseq [preset [:default :vim :emacs]]
      (keymap/install! preset)
      (is (= :focus/uri (get-in (keymap/modes) [:all "C-l"]))
          (str preset " C-l"))))
  (testing "Ctrl+PageUp / Ctrl+PageDown cycle tabs left / right across bundled keymaps"
    (doseq [preset [:default :vim :emacs]]
      (keymap/install! preset)
      (is (= :tab/prev (get-in (keymap/modes) [:all "C-prior"])) (str preset " C-prior"))
      (is (= :tab/next (get-in (keymap/modes) [:all "C-next"]))  (str preset " C-next")))
    ;; placed in :all → resolves in every Vim mode, including :insert (not only :normal)
    (keymap/install! :vim)
    (is (= :tab/prev (:command (resolver/step (keymap/modes) :insert [] "C-prior" {:in-input? false})))
        "vim insert-mode Ctrl+PageUp cycles to the previous tab")
    (is (= :tab/next (:command (resolver/step (keymap/modes) :normal [] "C-next" {:in-input? false})))
        "vim normal-mode Ctrl+PageDown cycles to the next tab")))

(deftest keymap-init-applies-persisted-set
  ;; Regression for the init bug (a persisted Vim set was not live until a manual Standard→Vim switch). The
  ;; fix: :keymap/config-received sets [:ui :input :mode] in :db SYNCHRONOUSLY via initial-mode-for, and
  ;; installs the live keymap by id via install-for! — these are the pure pieces that make it correct.
  (testing "initial-mode-for returns each set's modal initial mode (so the mode is set in :db, no async lag)"
    (is (= :normal (registry/initial-mode-for empty-keymaps "vim")))
    (is (= :insert (registry/initial-mode-for empty-keymaps "default")))
    (is (= :insert (registry/initial-mode-for empty-keymaps "emacs"))))
  (testing "install-for! installs the named set live into the keymap atom (the resolver reads it)"
    (registry/install-for! empty-keymaps "vim")
    (is (= :normal (keymap/initial-mode)) "vim is modal → initial mode :normal")
    (is (= :hint/start (get-in (keymap/modes) [:normal "f"])) "vim's normal-mode bindings are live after install-for!")
    (registry/install-for! empty-keymaps "default")
    (is (= :insert (keymap/initial-mode)) "default is non-modal → initial mode :insert")))

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
    (is (true?  (commands/allowed? :tab/new-blank {})))
    (is (true?  (commands/allowed? :file/open-dialog-new-tab {})))
    (is (true?  (commands/allowed? :search/start {}))  "no :when ⇒ always"))
  (testing "all-visible filters by :when"
    (let [ids (set (map :id (commands/all-visible {:tabs [] :can-back? false})))]
      (is (not (contains? ids :tab/next)))
      (is (contains? ids :tab/new-blank))
      (is (contains? ids :file/open-dialog-new-tab))
      (is (contains? ids :search/start)))))

(deftest datascript-helpers
  (let [conn (d/create-conn {:doc/path {:db/unique :db.unique/identity}})]
    (d/transact! conn [{:doc/path "/a" :doc/order 0 :doc/open? true :doc/kind "markdown"}
                       {:doc/path "/b" :doc/order 1 :doc/open? true :doc/kind "text"}])
    (is (= 2 (ds/next-order @conn)) "next-order = max+1")
    (is (= ["/a" "/b"] (mapv :path (ds/open-docs @conn))) "ordered by :doc/order")
    (is (some? (ds/eid-for-path @conn "/a")))
    (is (= "markdown" (ds/doc-attr @conn "/a" :doc/kind)))
    (is (= #{"/a" "/b"} (set (ds/doc-paths @conn))))
    (is (= 1 (count (ds/retract-unretained-tx @conn #{"/a"})))
        "unretained cached docs get an eviction transaction")))

(deftest content-error-transactions
  (testing "an error for an uncached path creates a visible document entity"
    (let [conn (d/create-conn {:doc/path {:db/unique :db.unique/identity}})]
      (d/transact! conn (events/content-error-tx @conn "/tmp/broken.puml" "PlantUML failed" 123))
      (is (= "text" (ds/doc-attr @conn "/tmp/broken.puml" :doc/kind)))
      (is (= "PlantUML failed" (ds/doc-attr @conn "/tmp/broken.puml" :doc/error)))
      (is (= 123 (ds/doc-attr @conn "/tmp/broken.puml" :doc/stamp)))))
  (testing "an error for an existing document updates that entity"
    (let [conn (d/create-conn {:doc/path {:db/unique :db.unique/identity}})]
      (d/transact! conn [{:doc/path "/tmp/readme.md" :doc/kind "markdown" :doc/stamp 1}])
      (d/transact! conn (events/content-error-tx @conn "/tmp/readme.md" "render failed" 2))
      (is (= "markdown" (ds/doc-attr @conn "/tmp/readme.md" :doc/kind)))
      (is (= "render failed" (ds/doc-attr @conn "/tmp/readme.md" :doc/error)))
      (is (= 2 (ds/doc-attr @conn "/tmp/readme.md" :doc/stamp))))))

(deftest open-dialog-file-dispatch
  (testing "current-tab mode opens the first selected file in the current tab"
    (is (= [[:dispatch [:doc/open "/tmp/a.md"]]]
           (events/files-opened-fx :current ["/tmp/a.md"])))
    (is (= [[:dispatch [:doc/open "/tmp/a.md"]]
            [:dispatch [:doc/open-new "/tmp/b.md"]]]
           (events/files-opened-fx :current ["/tmp/a.md" "/tmp/b.md"]))))
  (testing "new-tab mode opens every selected file in a new tab"
    (is (= [[:dispatch [:doc/open-new "/tmp/a.md"]]
            [:dispatch [:doc/open-new "/tmp/b.md"]]]
           (events/files-opened-fx :new-tab ["/tmp/a.md" "/tmp/b.md"]))))
  (testing "empty and unknown modes are stable"
    (is (= [] (events/files-opened-fx :new-tab [])))
    (is (= :current (events/open-dialog-mode nil)))
    (is (= :current (events/open-dialog-mode :unexpected))))
  (testing "command-line launch (focus-first?) re-activates the FIRST tab after opening all"
    ;; ≥2 paths: open the first in the current tab, the rest in new tabs, then re-focus the first
    (is (= [[:dispatch [:doc/open "/tmp/a.md"]]
            [:dispatch [:doc/open-new "/tmp/b.md"]]
            [:dispatch [:doc/open-new "/tmp/c.md"]]
            [:dispatch [:doc/open "/tmp/a.md"]]]
           (events/files-opened-fx :current ["/tmp/a.md" "/tmp/b.md" "/tmp/c.md"] true)))
    ;; a single path is already the active/only tab — no redundant re-activation appended
    (is (= [[:dispatch [:doc/open "/tmp/a.md"]]]
           (events/files-opened-fx :current ["/tmp/a.md"] true)))
    ;; nothing to open, nothing to focus
    (is (= [] (events/files-opened-fx :current [] true)))
    ;; the 2-arity dialog path is unchanged (no re-activation)
    (is (= [[:dispatch [:doc/open "/tmp/a.md"]]
            [:dispatch [:doc/open-new "/tmp/b.md"]]]
           (events/files-opened-fx :current ["/tmp/a.md" "/tmp/b.md"])))))

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

(deftest nav-preview-link-history
  (testing "preview navigation pushes into the active tab and restores the markdown scroll"
    (let [db1 (nav/add-tab empty-tabs "/previous.md")
          db2 (nav/nav-active db1 "/readme.md" 25)
          db3 (nav/nav-active db2 "/diagram.png" 340)
          [db4 uri4 sc4] (nav/step db3 -1 0)
          [db5 uri5 sc5] (nav/step db4 1 345)]
      (is (= "/readme.md" uri4))
      (is (= 340 sc4))
      (is (= "/diagram.png" uri5))
      (is (= 0 sc5))
      (is (= 345 (get-in (nav/active-tab db5) [:hist :stack 1 :scroll])))))
  (testing "an image already open in another tab does not steal focus from preview navigation"
    (let [db0 (-> empty-tabs
                  (nav/add-tab "/readme.md")
                  (nav/add-tab "/diagram.png")
                  (nav/activate 0))
          db1 (nav/nav-active db0 "/diagram.png" 120)
          tabs (nav/tabs db1)]
      (is (= 0 (nav/active-id db1)))
      (is (= "/diagram.png" (nav/active-uri db1)))
      (is (= [{:uri "/readme.md" :scroll 120}
              {:uri "/diagram.png" :scroll 0}]
             (get-in (nav/active-tab db1) [:hist :stack])))
      (is (= "/diagram.png" (:uri (second tabs)))))))

(deftest nav-retained-file-paths
  (testing "retained paths include every URI reachable from open tab histories — local file paths AND raw non-file
            uris (an http(s) URL is retained in :doc/path form so a doc keyed by it — e.g. a PDF opened from a
            web-view link — is not evicted; main only ever watches the local paths)"
    (let [db (-> empty-tabs
                 (nav/add-tab "/a.md")
                 (nav/nav-active "/b.md" 10)
                 (nav/add-tab "https://example.com")
                 (nav/nav-active "/c.md" 0))]
      (is (= ["/a.md" "/b.md" "https://example.com" "/c.md"] (nav/retained-file-paths db)))))
  (testing "history truncation drops no-longer-reachable files from the retained set"
    (let [db0 (-> empty-tabs
                  (nav/add-tab "/a.md")
                  (nav/nav-active "/b.md" 10))
          [db1 _uri _scroll] (nav/step db0 -1 20)
          db2 (nav/nav-active db1 "/c.md" 30)]
      (is (= ["/a.md" "/c.md"] (nav/retained-file-paths db2))))))

(deftest nav-reorder-and-source
  (let [db (-> empty-tabs (nav/add-tab "/a") (nav/add-tab "/b") (nav/add-tab "/c"))]  ; ids 0,1,2
    (testing "reorder moves a tab to an insertion gap"
      (is (= ["/c" "/a" "/b"] (mapv :uri (nav/tabs (nav/reorder db 2 0)))) "c → front")
      (is (= ["/a" "/c" "/b"] (mapv :uri (nav/tabs (nav/reorder db 2 1)))) "c → gap 1"))
    (testing "the view facet is stored on the active tab"
      (is (nil? (nav/facet db)))
      (is (= {:path "/c" :type :source} (nav/facet (nav/set-facet db "/c" :source)))))))

(deftest nav-duplicate-and-blank-tabs
  (testing "duplicate inserts immediately after the source tab and preserves tab state"
    (let [db0  (-> empty-tabs
                   (nav/add-tab "/a.md")
                   (nav/nav-active "/b.md" 42)
                   (nav/set-facet 0 "/b.md" :source))
          db1  (nav/duplicate-tab db0 0)
          tabs (nav/tabs db1)]
      (is (= [0 1] (mapv :id tabs)))
      (is (= ["/b.md" "/b.md"] (mapv :uri tabs)))
      (is (= 1 (nav/active-id db1)))
      (is (= 2 (get-in db1 [:ui :next-tab-id])))
      (is (= (:hist (first tabs)) (:hist (second tabs))))
      (is (= {:path "/b.md" :type :source} (:facet (second tabs))) "duplicate preserves the view facet")))
  (testing "duplicating an inactive tab still inserts next to that tab"
    (let [db0  (-> empty-tabs
                   (nav/add-tab "/a.md")
                   (nav/add-tab "/b.md")
                   (nav/add-tab "/c.md"))
          db1  (nav/duplicate-tab db0 0)
          tabs (nav/tabs db1)]
      (is (= [0 3 1 2] (mapv :id tabs)))
      (is (= ["/a.md" "/a.md" "/b.md" "/c.md"] (mapv :uri tabs)))
      (is (= 3 (nav/active-id db1)))))
  (testing "blank tabs have nil uri and browser-style display text"
    (let [db (nav/add-tab empty-tabs nil)]
      (is (nil? (nav/active-uri db)))
      (is (nil? (uri/file-path (nav/active-uri db))))
      (is (= "" (uri/display (nav/active-uri db))))
      (is (= "New Tab" (uri/basename (nav/active-uri db)))))))

(deftest file-kind-classification
  (let [source? (fn [path] (contains? grammar-catalog/bundled-source-exts
                                      (file-kind/extension path)))]
    (doseq [[path expected] [["README.md" "markdown"]
                             ["diagram.png" "image"]
                             ["diagram.svg" "image"]
                             ["manual.pdf" "pdf"]
                             ["index.html" "html"]
                             ["notes.docx" "office"]
                             ["notes.odt" "office"]
                             ["slides.odp" "office"]
                             ["formula.odf" "office"]
                             ["data.csv" "table"]
                             ["data.tsv" "table"]
                             ["book.xlsx" "table"]
                             ["book.ods" "table"]
                             ["app.log" "log"]
                             ["syslog" "log"]
                             ["app.log.1" "log"]
                             ["app.log.gz" "log"]
                             ["bundle.zip" "archive"]
                             ["bundle.tar" "archive"]
                             ["bundle.tar.gz" "archive"]
                             ["bundle.tgz" "archive"]
                             ["vv-archive://open?chain=%5B%22%2Ftmp%2Fbundle.zip%22%5D" "archive"]
                             ["page.htm" "html"]
                             ["workflow.d2" "source"]
                             ["workflow.puml" "source"]
                             ["workflow.plantuml" "source"]
                             ["workflow.mmd" "mermaid"]
                             ["workflow.mermaid" "mermaid"]
                             ["workflow.dot" "source"]
                             ["grammar.cf" "source"]
                             ["grammar.bnfc" "source"]
                             ["program.rho" "source"]
                             ["notes.unknown" "text"]]]
      (is (= expected (file-kind/kind-of source? path)) path))))

(deftest archive-uri-helpers
  (let [uri "vv-archive://open?chain=%5B%22%2Ftmp%2Fbundle.zip%22%2C%22logs%2Fapp.log%22%5D"]
    (is (true? (uri/archive? uri)))
    (is (= ["/tmp/bundle.zip" "logs/app.log"] (uri/archive-chain uri)))
    (is (= "app.log" (uri/basename uri)))
    (is (= "file:///tmp/bundle.zip!/logs/app.log" (uri/display uri)))
    (is (= uri (uri/file-path uri)))))

(deftest bundled-grammar-catalog
  (testing "catalog entries expose required runtime fields"
    (is (seq grammar-catalog/bundled-grammars))
    (doseq [g grammar-catalog/bundled-grammars]
      (is (string? (:id g)))
      (is (string? (:language g)))
      (is (vector? (:extensions g)))
      (is (every? #(and (string? %) (str/starts-with? % ".")) (:extensions g)))
      (is (string? (:wasm-url g)))
      (is (string? (:scm-url g)))))
  (testing "source extensions are derived from the bundled catalog"
    (is (contains? grammar-catalog/bundled-source-exts ".rho"))
    (is (contains? grammar-catalog/bundled-source-exts ".d2"))
    (is (contains? grammar-catalog/bundled-source-exts ".cf"))
    (is (contains? grammar-catalog/bundled-source-exts ".bnfc"))
    (is (contains? grammar-catalog/bundled-source-exts ".md"))
    (is (not (contains? grammar-catalog/bundled-source-exts ""))))
  (testing "grammars resolve by path, id, and language alias"
    (is (= "d2" (:id (grammar-catalog/grammar-for-path "workflow.d2" grammar-catalog/bundled-grammars))))
    (is (= "d2" (:id (grammar-catalog/grammar-for-language "d2" grammar-catalog/bundled-grammars))))
    (is (= "bnfc" (:id (grammar-catalog/grammar-for-path "grammar.cf" grammar-catalog/bundled-grammars))))
    (is (= "bnfc" (:id (grammar-catalog/grammar-for-path "grammar.bnfc" grammar-catalog/bundled-grammars))))
    (is (= "bnfc" (:id (grammar-catalog/grammar-for-language "bnfc" grammar-catalog/bundled-grammars))))
    (is (= "bnfc" (:id (grammar-catalog/grammar-for-language "lbnf" grammar-catalog/bundled-grammars))))
    (is (= "bnfc" (:id (grammar-catalog/grammar-for-language "cf" grammar-catalog/bundled-grammars))))
    (is (= "markdown" (:id (grammar-catalog/grammar-for-path "README.md" grammar-catalog/bundled-grammars))))
    (is (= "markdown-inline" (:id (grammar-catalog/grammar-for-id "markdown-inline"
                                                                    grammar-catalog/bundled-grammars))))
    (is (= "markdown-inline" (:id (grammar-catalog/grammar-for-language "markdown_inline"
                                                                         grammar-catalog/bundled-grammars))))
    (is (= "javascript" (:id (grammar-catalog/grammar-for-language "js" grammar-catalog/bundled-grammars))))
    (is (= "markdown" (:id (grammar-catalog/grammar-for-language "gfm" grammar-catalog/bundled-grammars)))))
  (testing "filename and glob filetype mappings resolve before extension lookup"
    (is (= "toml" (:id (grammar-catalog/grammar-for-path "/tmp/Cargo.lock"
                                                          grammar-catalog/bundled-grammars)))
        "Cargo.lock is highlighted as TOML by default")
    (is (= "toml" (:id (grammar-catalog/grammar-for-path
                        "/tmp/service.custom"
                        grammar-catalog/bundled-grammars
                        {:patterns {"*.custom" "toml"}}))))
    (is (= "json" (:id (grammar-catalog/grammar-for-path
                        "/tmp/service/custom.lock"
                        grammar-catalog/bundled-grammars
                        {:filenames {"custom.lock" "json"}}))))
    (is (nil? (grammar-catalog/grammar-for-path
               "/tmp/service.custom"
               grammar-catalog/bundled-grammars
               {:patterns {"*.custom" "not-a-real-filetype"}})))))

(deftest mathjax-svg-rendering
  (testing "MathJax renders cached SVG without DOM typesetting"
    (let [svg (math/render-tex "x^2" false)]
      (is (str/includes? svg "MathJax"))
      (is (str/includes? svg "<svg"))
      (is (= svg (math/render-tex "x^2" false)))))
  (testing "GitHub backtick-dollar inline math ($`x^2`$) has its fence stripped to clean TeX"
    ;; the former raw-string normalize was replaced by an mdast-level fence strip (math/strip-math-fence),
    ;; so a code span `$x$` stays literal; full-pipeline behavior is covered by markdown-code-span-vs-math
    (is (= "x^2" (math/strip-math-fence "`x^2`"))))
  (testing "AMS family (boldsymbol + amscd) render via the shadow-bundled engine, with assistive MathML"
    (let [bs (math/render-tex "\\boldsymbol{x}" false)
          cd (math/render-tex "\\begin{CD} A @>f>> B \\end{CD}" true)]
      (is (str/includes? bs "<svg") "boldsymbol renders an SVG")
      (is (str/includes? bs "mjx-assistive-mml") "assistive MathML is emitted (a11y preserved)")
      (is (re-find #"bold-italic" bs) "boldsymbol → bold-italic MathML (amsbsy package active)")
      (is (str/includes? cd "<svg") "amscd renders an SVG")
      (is (re-find #"<mtable" cd) "amscd \\begin{CD} builds an mtable (amscd package active)")))
  (testing "the html package is absent from the engine — \\href cannot inject a link (renders inert)"
    (let [out (math/render-tex "\\href{javascript:alert(1)}{x}" false)]
      (is (not (re-find #"<a\b" out)) "no anchor element is produced")
      (is (not (re-find #"href=" out)) "no href attribute is produced"))))

(deftest menu-access-keys
  (testing "top-level Alt access keys resolve to menus"
    (is (= {:action :open-menu :label "File"} (menubar/access-action {:ui {}} "f")))
    (is (= {:action :open-menu :label "View"} (menubar/access-action {:ui {}} "v")))
    (is (= "Settings" (:label (menubar/menu-for-access-key "s")))))
  (testing "View menu keeps re-frame-10x directly beneath Developer Tools"
    (let [view-menu (first (filter #(= "View" (:label %)) menubar/menus))
          labels    (->> (:items view-menu) (filter map?) (map :label) vec)
          idx       (first (keep-indexed (fn [i label] (when (= "Developer Tools" label) i)) labels))]
      (is (number? idx))
      (is (= "re-frame-10x" (get labels (inc idx))))))
  (testing "Settings exposes Theme and Key Bindings as the first two submenus"
    (let [settings-menu (first (filter #(= "Settings" (:label %)) menubar/menus))]
      (is (= ["Theme" "Key Bindings"]
             (->> (:items settings-menu) (filter map?) (take 2) (map :submenu) vec)))))
  (testing "open menu access keys resolve actions and submenus"
    (is (= {:action :dispatch :event [:file/open-dialog]}
           (menubar/access-action {:ui {:menu "File"}} "o")))
    (is (= {:action :dispatch :event [:file/open-dialog :new-tab]}
           (menubar/access-action {:ui {:menu "File"}} "n")))
    (is (= {:action :open-submenu :submenu "Theme"}
           (menubar/access-action {:ui {:menu "Settings"}} "t"))))
  (testing "submenu rows get access keys from current app state"
    (let [db (assoc-in empty-keymaps [:ui :menu] "Settings")
          db (-> db
                 (assoc-in [:ui :menu-submenu] "Theme")
                 (assoc-in [:ui :theme] "spacemacs-dark"))]
      (is (= {:action :dispatch :event [:theme/set "spacemacs-light"]}
             (menubar/access-action db "l"))))))

(deftest menu-focus-helpers
  (let [rows [{:label "Open"}
              :sep
              nil
              {:label "Disabled" :disabled? true}
              {:label "Close"}
              {:label "Reload"}]]
    (testing "focus indexes skip nils, separators, and disabled rows"
      (is (= [0 4 5] (menu-focus/focusable-indexes rows)))
      (is (= 0 (menu-focus/first-index rows)))
      (is (= 5 (menu-focus/last-index rows))))
    (testing "movement wraps and recovers from nil or invalid focus"
      (is (= 0 (menu-focus/move-index rows nil 1)))
      (is (= 5 (menu-focus/move-index rows nil -1)))
      (is (= 4 (menu-focus/move-index rows 0 1)))
      (is (= 0 (menu-focus/move-index rows 5 1)))
      (is (= 5 (menu-focus/move-index rows 99 -1))))
    (testing "item-at only returns focusable rows"
      (is (= {:label "Close"} (menu-focus/item-at rows 4)))
      (is (nil? (menu-focus/item-at rows 1)))
      (is (nil? (menu-focus/item-at rows 3))))))

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

(deftest preview-navigation-events
  (testing "preview links navigate the active tab with browser-like semantics"
    (is (= [:tab/navigate "/tmp/diagram.png"]
           (preview-nav/open-event {:kind :file :path "/tmp/diagram.png"} false)))
    (is (= [:tab/open "/tmp/diagram.png"]
           (preview-nav/open-event {:kind :file :path "/tmp/diagram.png"} true)))
    (is (= [:tab/navigate "https://example.com"]
           (preview-nav/open-event {:kind :http :path "https://example.com"} false)))
    (is (= [:tab/open "https://example.com"]
           (preview-nav/open-event {:kind :preview-link :link-kind :http :path "https://example.com"} true))))
  (testing "non-document preview targets keep their existing destinations"
    (is (= [:toc/goto "section"]
           (preview-nav/open-event {:kind :anchor :path "section"} false)))
    (is (= [:tab/navigate "/tmp"]
           (preview-nav/open-event {:kind :dir :path "/tmp"} false)))   ; directories open in-pane now
    (is (= [:tab/open "/tmp"]
           (preview-nav/open-event {:kind :dir :path "/tmp"} true)))
    (is (true? (preview-nav/new-tab? {:kind :preview-link :link-kind :file :path "/x"})))
    (is (true? (preview-nav/new-tab? {:kind :dir :path "/x"})))
    (is (false? (preview-nav/new-tab? {:kind :preview-link :link-kind :anchor :path "x"})))))

(deftest uri-path-helpers
  (testing "dirname: parent directory, http/root → nil, trailing slash ignored"
    (is (= "/a/b" (uri/dirname "/a/b/c.md")))
    (is (= "/a/b" (uri/dirname "file:///a/b/c.md")))
    (is (= "/a"   (uri/dirname "/a/b")))
    (is (= "/a"   (uri/dirname "/a/b/")))
    (is (= "/"    (uri/dirname "/a")))
    (is (nil?     (uri/dirname "/")))
    (is (nil?     (uri/dirname "https://example.com/x"))))
  (testing "segments: root→leaf {:name :path} with cumulative paths"
    (is (= [{:name "/" :path "/"} {:name "a" :path "/a"}
            {:name "b" :path "/a/b"} {:name "c.md" :path "/a/b/c.md"}]
           (uri/segments "/a/b/c.md")))
    (is (= [{:name "/" :path "/"}] (uri/segments "/")))
    (is (nil? (uri/segments "https://example.com"))))
  (testing "ancestor-paths"
    (is (= ["/" "/a" "/a/b" "/a/b/c.md"] (uri/ancestor-paths "/a/b/c.md")))))

(deftest uri-completion-helpers
  (testing "complete-split: [dir-part base] at the last separator; dir-part keeps its trailing slash"
    (is (= ["/home/u/" "Do"] (uri/complete-split "/home/u/Do")))
    (is (= ["/home/u/" ""]   (uri/complete-split "/home/u/")))
    (is (= ["file:///a/" "b"] (uri/complete-split "file:///a/b")))
    (is (= ["" "rel"]        (uri/complete-split "rel")))
    (is (= ["C:\\d\\" "f"]   (uri/complete-split "C:\\d\\f"))))   ; Windows backslash delimits too
  (testing "matches-prefix?: case-insensitive; dotfiles hidden unless the base starts with '.'"
    (is (uri/matches-prefix? "Documents" "do"))
    (is (uri/matches-prefix? "Documents" ""))
    (is (not (uri/matches-prefix? "Downloads" "do x")))
    (is (not (uri/matches-prefix? ".config" "c")))
    (is (uri/matches-prefix? ".config" ".c")))
  (testing "common-prefix: longest shared prefix (Tab fills to here)"
    (is (= "Do"  (uri/common-prefix ["Documents" "Downloads"])))
    (is (= "abc" (uri/common-prefix ["abc"])))
    (is (= ""    (uri/common-prefix ["abc" "xyz"])))
    (is (= ""    (uri/common-prefix []))))
  (testing "web-matches: prefix matches rank before substring; recency preserved; capped; case-insensitive"
    (is (= ["abcdef" "xabcy"] (uri/web-matches ["xabcy" "abcdef"] "abc" 10)))   ; prefix outranks substring
    (let [hist ["https://example.com/docs" "https://example.com/api" "https://elixir-lang.org"]]
      (is (= ["https://example.com/docs" "https://example.com/api"] (uri/web-matches hist "https://example.com" 10)))
      (is (= ["https://example.com/docs" "https://example.com/api"] (uri/web-matches hist "HTTPS://EXAMPLE.COM" 10)))
      (is (= 1 (count (uri/web-matches hist "https://e" 1))))
      (is (= [] (uri/web-matches hist "" 10))))))

(deftest directory-selection
  (let [entries [{:name "z.txt" :path "/d/z.txt" :dir? false}
                 {:name "sub"   :path "/d/sub"   :dir? true}
                 {:name "A.txt" :path "/d/A.txt" :dir? false}]]
    (testing "sort-entries: directories first, then case-insensitive name"
      (is (= ["/d/sub" "/d/A.txt" "/d/z.txt"] (mapv :path (nav/sort-entries entries)))))
    (testing "effective-selected: explicit selection in the listing wins"
      (is (= "/d/A.txt" (nav/effective-selected "/d" entries "/d/A.txt" {}))))
    (testing "effective-selected: else the remembered trail child"
      (is (= "/d/z.txt" (nav/effective-selected "/d" entries "/gone" {"/d" "/d/z.txt"}))))
    (testing "effective-selected: else the first sorted entry"
      (is (= "/d/sub" (nav/effective-selected "/d" entries nil {}))))
    (testing "effective-selected: nil for an empty listing"
      (is (nil? (nav/effective-selected "/d" [] nil {}))))))

(deftest recent-trail-and-mru
  (let [db0 {:ui {:recent {:trail {} :recent-files []}}}
        db1 (events/record-recent db0 "/a/b/c.md" false)]
    (testing "trail records each ancestor→child step (root→file)"
      (is (= "/a"        (get-in db1 [:ui :recent :trail "/"])))
      (is (= "/a/b"      (get-in db1 [:ui :recent :trail "/a"])))
      (is (= "/a/b/c.md" (get-in db1 [:ui :recent :trail "/a/b"]))))
    (testing "a file is unshifted onto the MRU; a directory is not"
      (is (= ["/a/b/c.md"] (get-in db1 [:ui :recent :recent-files])))
      (is (= ["/a/b/c.md"] (get-in (events/record-recent db1 "/a/b" true)
                                   [:ui :recent :recent-files]))))
    (testing "MRU dedups + moves to front (newest first)"
      (let [db (-> db1
                   (events/record-recent "/a/b/d.md" false)
                   (events/record-recent "/a/b/c.md" false))]
        (is (= ["/a/b/c.md" "/a/b/d.md"] (get-in db [:ui :recent :recent-files])))))
    (testing "MRU is capped at 10 (newest retained)"
      (let [db (reduce (fn [d i] (events/record-recent d (str "/f/" i ".md") false)) db0 (range 12))]
        (is (= 10 (count (get-in db [:ui :recent :recent-files]))))
        (is (= "/f/11.md" (first (get-in db [:ui :recent :recent-files]))))))))

(deftest web-history-mru
  (let [db  {:ui {:recent {:trail {} :recent-files [] :web-history []}}}
        db' (-> db
                (events/record-web-history "https://a.com")
                (events/record-web-history "https://b.com")
                (events/record-web-history "https://a.com"))]   ; revisit → dedup + move to front
    (testing "http(s) URLs are unshifted onto the web-history MRU, deduped, newest first"
      (is (= ["https://a.com" "https://b.com"] (get-in db' [:ui :recent :web-history]))))
    (testing "non-http inputs (local paths, blank) are ignored"
      (is (= ["https://a.com" "https://b.com"]
             (get-in (events/record-web-history db' "/local/file.md") [:ui :recent :web-history])))
      (is (= ["https://a.com" "https://b.com"]
             (get-in (events/record-web-history db' nil) [:ui :recent :web-history]))))))

(deftest pdf-layout-helpers
  (testing "clamp-zoom bounds [0.25, 8.0]"
    (is (= 0.25 (pdf-layout/clamp-zoom 0.1)))
    (is (= 8.0  (pdf-layout/clamp-zoom 20)))
    (is (= 1.5  (pdf-layout/clamp-zoom 1.5))))
  (testing "zoom-step: in ×1.2, out ÷1.2, reset → 1.0, clamped at max"
    (is (= 1.2 (pdf-layout/zoom-step 1.0 :in)))
    (is (< (js/Math.abs (- (/ 1.0 1.2) (pdf-layout/zoom-step 1.0 :out))) 1e-9))
    (is (= 1.0 (pdf-layout/zoom-step 3.3 :reset)))
    (is (= 8.0 (pdf-layout/zoom-step 7.5 :in))))
  (testing "fit-scale: width / page / actual + zero guard"
    (is (= 2.0 (pdf-layout/fit-scale 200 999 100 50 :width)))
    (is (= 1.0 (pdf-layout/fit-scale 200 50 100 50 :page)))      ; min(2.0, 1.0)
    (is (= 1.0 (pdf-layout/fit-scale 200 999 100 50 :actual)))
    (is (= 1.0 (pdf-layout/fit-scale 200 200 0 0 :width))))      ; zero guard
  (testing "page-rects: cumulative offsets with gaps + total-height"
    (let [rects (pdf-layout/page-rects [[100 200] [100 100]] 1.0 10)]
      (is (= [{:top 0 :height 200 :width 100} {:top 210 :height 100 :width 100}] rects))
      (is (= 310 (pdf-layout/total-height rects))))
    (is (= [{:top 0 :height 100 :width 50}] (pdf-layout/page-rects [[100 200]] 0.5 10))))
  (testing "visible-range: window + overscan; [-1 -1] when none"
    (let [rects (pdf-layout/page-rects [[10 100] [10 100] [10 100]] 1.0 0)]   ; tops 0,100,200
      (is (= [0 0]   (pdf-layout/visible-range rects 0 50 0)))
      (is (= [0 1]   (pdf-layout/visible-range rects 50 100 0)))
      (is (= [0 2]   (pdf-layout/visible-range rects 100 100 50)))            ; overscan pulls in 0 & 2
      (is (= [-1 -1] (pdf-layout/visible-range rects 1000 50 0)))))
  (testing "outline->toc: nesting → levels, dest → vv-pdf-page-N, unresolved skipped"
    (let [outline [{:title "A" :dest "da" :items [{:title "A1" :dest "da1" :items []}]}
                   {:title "B" :dest "gone" :items []}]
          d->p    {"da" 1 "da1" 2 "gone" nil}]
      (is (= [{:level 1 :text "A"  :id "vv-pdf-page-1"}
              {:level 2 :text "A1" :id "vv-pdf-page-2"}]
             (pdf-layout/outline->toc outline d->p))))))

(deftest virtual-layout-helpers
  (testing "stack: cumulative tops with gaps, extra keys preserved; total = last bottom"
    (let [rects (vl/stack [{:height 200 :width 100} {:height 100 :width 100}] 10)]
      (is (= [{:height 200 :width 100 :top 0} {:height 100 :width 100 :top 210}] rects))
      (is (= 310 (vl/total rects))))
    (is (= 0 (vl/total []))))
  (testing "visible-range: window + overscan; [-1 -1] when none"
    (let [rects (vl/stack [{:height 100} {:height 100} {:height 100}] 0)]   ; tops 0,100,200
      (is (= [0 0]   (vl/visible-range rects 0 50 0)))
      (is (= [0 1]   (vl/visible-range rects 50 100 0)))
      (is (= [0 2]   (vl/visible-range rects 100 100 50)))                  ; overscan pulls in 0 & 2
      (is (= [-1 -1] (vl/visible-range rects 1000 50 0)))))
  (testing "est-heights: measured index overrides the default estimate"
    (is (= [48 120 48 90 48] (vl/est-heights 5 48 {1 120, 3 90})))
    (is (= [] (vl/est-heights 0 48 {}))))
  (testing "extrapolate-total: rendered/progress floored at the rendered height; floor when progress ≤ 0"
    (is (= 400 (vl/extrapolate-total 100 0.25 100)))
    (is (= 500 (vl/extrapolate-total 100 0 500)))                            ; no progress yet → floor
    (is (= 100 (vl/extrapolate-total 100 1 0)))
    (is (= 200 (vl/extrapolate-total 100 0.9 200))))                         ; 111 < floor 200 → floor
  (testing "spacer-height: pad up to the estimate, never negative"
    (is (= 300 (vl/spacer-height 400 100)))
    (is (= 0 (vl/spacer-height 100 400))))
  (testing "pads: top/bottom offsets for a windowed band; zeros for an empty band"
    (let [rects (vl/stack [{:height 100} {:height 100} {:height 100} {:height 100}] 0)]  ; tops 0,100,200,300; total 400
      (is (= {:top 100 :bottom 100} (vl/pads rects 1 2 400)))
      (is (= {:top 0 :bottom 0} (vl/pads rects -1 -1 400)))))
  (testing "band-range: a px budget becomes symmetric overscan"
    (let [rects (vl/stack [{:height 100} {:height 100} {:height 100} {:height 100} {:height 100}] 0)]  ; tops 0..400
      (is (= [0 1] (vl/band-range rects 0 100 200)))                         ; viewport top ±100 → idx 0,1
      (is (= [1 3] (vl/band-range rects 200 100 200))))))                    ; mid-doc ±100 → idx 1..3

(deftest number-outline
  (let [entry (fn [level text] {:level level :text text :id (str "vv-pdf-page-" level)})]
    (testing "unnumbered nested outline → hierarchical section numbers, :id/:level/:text preserved"
      (let [in  [(entry 1 "Purpose") (entry 1 "Model") (entry 2 "A PF") (entry 2 "Grounding")
                 (entry 2 "Two holes") (entry 1 "Architecture")]
            out (pdf-layout/number-outline in)]
        (is (= ["1" "2" "2.1" "2.2" "2.3" "3"] (mapv :number out)))
        (is (= (mapv #(dissoc % :number) out) in))))   ; nothing but :number added
    (testing "already-numbered outline (majority) is returned unchanged — no double-numbering"
      (let [in [(entry 1 "1. Intro") (entry 1 "2. Methods") (entry 2 "2.1 Setup") (entry 1 "3) Results")]]
        (is (= in (pdf-layout/number-outline in)))
        (is (every? #(nil? (:number %)) (pdf-layout/number-outline in)))))
    (testing "a lone coincidental number-prefixed title stays under the ½ threshold → still auto-numbers"
      (is (= ["1" "2" "3"]
             (mapv :number (pdf-layout/number-outline
                            [(entry 1 "Overview") (entry 1 "2 kinds of hole") (entry 1 "Summary")])))))
    (testing "\"3D rendering\" is NOT treated as numbered (digits glued to a letter)"
      (is (= ["1"] (mapv :number (pdf-layout/number-outline [(entry 1 "3D rendering")])))))
    (testing "single flat level numbers sequentially; empty → empty"
      (is (= ["1" "2" "3"] (mapv :number (pdf-layout/number-outline [(entry 1 "a") (entry 1 "b") (entry 1 "c")]))))
      (is (= [] (pdf-layout/number-outline []))))))

(deftest ext-util-helpers
  (testing "parse-store-id: 32-char id from a URL or bare id, else nil"
    (is (= "abcdefghijklmnopabcdefghijklmnop" (eu/parse-store-id "abcdefghijklmnopabcdefghijklmnop")))
    (is (= "cjpalhdlnbpafiamejdnhcphjbkeiagm"
           (eu/parse-store-id "https://chromewebstore.google.com/detail/ublock/cjpalhdlnbpafiamejdnhcphjbkeiagm")))
    (is (nil? (eu/parse-store-id "not an id")))
    (is (nil? (eu/parse-store-id nil))))
  (testing "merge-config: defaults filled in; disabled-ids coerced to a set"
    (let [c (eu/merge-config {:adblock {:enabled? false} :extensions {:disabled-ids ["a" "b"]}})]
      (is (false? (get-in c [:adblock :enabled?])))
      (is (= :ads-and-tracking (get-in c [:adblock :lists])))
      (is (= #{"a" "b"} (get-in c [:extensions :disabled-ids]))))
    (is (= eu/default-config (eu/merge-config nil))))
  (testing "reconcile-enabled: ids to unload = installed ∩ disabled"
    (is (= {:to-unload ["b"]} (eu/reconcile-enabled ["a" "b" "c"] #{"b" "z"}))))
  (testing "action-model: title/popup/icon from a manifest (string keys); prefers 32px icon"
    (let [a (eu/action-model {"name" "uBlock"
                              "action" {"default_title" "uBO" "default_popup" "popup.html"
                                        "default_icon" {"16" "i16.png" "32" "i32.png"}}})]
      (is (= "uBO" (:title a))) (is (= "popup.html" (:popup a)))
      (is (= "i32.png" (:icon-rel a))) (is (true? (:has-popup? a))))
    (let [a (eu/action-model {"name" "X" "action" {"default_icon" "icon.png"}})]
      (is (= "X" (:title a))) (is (= "icon.png" (:icon-rel a))) (is (false? (:has-popup? a)))))
  (testing "clamp-popup-size: ≤ 800×600, ≥ mins, default when nil"
    (is (= [800 600] (eu/clamp-popup-size 9999 9999)))
    (is (= [120 80]  (eu/clamp-popup-size 10 10)))
    (is (= [360 480] (eu/clamp-popup-size nil nil))))
  (testing "anchor->bounds: just below the icon, clamped on-screen"
    (is (= {:x 100 :y 26 :width 200 :height 300}
           (eu/anchor->bounds {:x 100 :y 0 :width 26 :height 24} 1000 800 [200 300])))
    (is (= 800 (:x (eu/anchor->bounds {:x 950 :y 0 :width 26 :height 24} 1000 800 [200 300])))))
  (testing "cache-stale?: nil/zero, or older than every-hours"
    (is (eu/cache-stale? 0 24 1000))
    (is (eu/cache-stale? nil 24 1000))
    (is (eu/cache-stale? 1 24 (+ 1 (* 25 3600000))))
    (is (not (eu/cache-stale? 1000 24 (+ 1000 (* 1 3600000)))))))

(deftest password-util-helpers
  (testing "web URL origin and host matching"
    (is (= "https://login.example.com" (pw-util/url-origin "https://login.example.com/path")))
    (is (= "login.example.com" (pw-util/url-host "https://login.example.com/path")))
    (is (pw-util/host-suffix-match? "login.example.com" "example.com"))
    (is (not (pw-util/host-suffix-match? "evil-example.com" "example.com")))
    (is (not (pw-util/web-url? "file:///tmp/a.html"))))
  (testing "matching ranks exact-origin entries before parent-domain entries"
    (let [items [{:provider "onepassword" :id "1" :title "Exact" :username "a"
                  :url "https://login.example.com/signin"}
                 {:provider "lastpass" :id "2" :title "Parent" :username "b"
                  :url "https://example.com"}]
          rows  (pw-util/matching-items "https://login.example.com/signin" items)]
      (is (= ["1" "2"] (map :id rows)))
      (is (> (:score (first rows)) (:score (second rows))))))
  (testing "sanitize-item keeps metadata and drops secrets"
    (let [safe (pw-util/sanitize-item {:provider "onepassword" :id "abc" :title "A"
                                       :username "u" :url "https://example.com"
                                       :password "secret"})]
      (is (= {:provider "onepassword" :id "abc" :username "u" :url "https://example.com"}
             (select-keys safe [:provider :id :username :url])))
      (is (not (contains? safe :password)))))
  (testing "status inference recognizes reauth and MFA outputs"
    (is (= "reauth-required" (pw-util/status-from-process-output 1 "Not logged in." "")))
    (is (= "mfa-required" (pw-util/status-from-process-output 1 "" "TOTP required")))
    (is (= "ready" (pw-util/status-from-process-output 0 "" "")))))

(deftest password-adapter-parsers
  (let [op-provider {:id "onepassword" :label "1Password"}
        lp-provider {:id "lastpass" :label "LastPass"}]
    (testing "1Password list items become sanitized-compatible metadata"
      (let [row (pw-adapters/parse-op-item op-provider
                                           {:id "i1" :title "Example" :additional_information "me@example.com"
                                            :vault {:id "v1"} :urls [{:href "https://example.com/login"}]})]
        (is (= "i1" (:id row)))
        (is (= "v1" (:vault-id row)))
        (is (= "me@example.com" (:username row)))
        (is (= ["https://example.com/login"] (:urls row)))))
    (testing "1Password reveal extracts username/password fields"
      (is (= {:username "me@example.com" :password "pw" :url "https://example.com" :urls ["https://example.com"]}
             (pw-adapters/extract-op-credentials
              {:fields [{:id "username" :purpose "USERNAME" :type "STRING" :value "me@example.com"}
                        {:id "password" :purpose "PASSWORD" :type "CONCEALED" :value "pw"}]
               :urls [{:href "https://example.com"}]}))))
    (testing "LastPass JSON entries become metadata without exposing passwords"
      (let [row  (pw-adapters/parse-lastpass-entry lp-provider
                                                   {:id "7" :name "Example" :username "me"
                                                    :password "secret" :url "https://example.com"})
            safe (pw-util/sanitize-item row)]
        (is (= "7" (:id safe)))
        (is (= "me" (:username safe)))
        (is (not (contains? safe :password)))))))

(deftest history-input-coalescing
  (let [[s1 ok1?] (history-input/accept {:dir nil :time 0} "back" 1000)
        [s2 ok2?] (history-input/accept s1 "back" 1100)
        [_ ok3?]  (history-input/accept s2 "back" 1181)
        [_ ok4?]  (history-input/accept s1 "forward" 1100)]
    (is ok1?)
    (is (false? ok2?))
    (is ok3?)
    (is ok4?)))

(deftest preview-context-helpers
  (testing "term-at copies the token under the caret and trims wrapping punctuation"
    (is (= {:start 6 :end 20 :text "/tmp/readme.md"}
           (select-keys (preview-context/term-at "open (/tmp/readme.md)." 12)
                        [:start :end :text])))
    (is (nil? (preview-context/term-at "one two" 3))))
  (testing "source offsets format as compiler-style locations"
    (is (= {:line 2 :column 3}
           (preview-context/offset->line-column "a\nbc\n" 4)))
    (is (= "/tmp/doc.md:2:3"
           (preview-context/location-string "/tmp/doc.md" {:line 2 :column 3}))))
  (testing "best-source-offset finds the rendered token inside its Markdown source span"
    (is (= 8 (preview-context/best-source-offset "Hello **world**" 0 15 "world" 0)))
    (is (= 0 (preview-context/best-source-offset "Hello **world**" 0 15 "missing" 0)))))

(deftest tab-context-menu-items
  (testing "tab menu has duplicate and orientation-aware close-side labels"
    (let [labels (fn [orientation]
                   (->> (context-menu/tab-items {:id 1 :path "/tmp/readme.md"
                                                 :orientation orientation})
                        (remove #{:sep})
                        (mapv :label)))]
      (is (some #{"Duplicate tab"} (labels :horizontal)))
      (is (some #{"Close to the Right"} (labels :horizontal)))
      (is (some #{"Close Below"} (labels :vertical))))))

;; ---- command palette: the fuzzy matcher + candidate sources ----
(deftest palette-fuzzy-match
  (testing "order-preserving, case-insensitive subsequence match"
    (is (true?  (palette/fuzzy? "" "anything")))            ; empty query always matches
    (is (true?  (palette/fuzzy? "abc" "aXbXc")))            ; chars appear in order
    (is (true?  (palette/fuzzy? "ABC" "a-b-c")))            ; query case-insensitive
    (is (true?  (palette/fuzzy? "ot" "Open in new Tab")))   ; spread across words
    (is (false? (palette/fuzzy? "cba" "abc")))              ; wrong order
    (is (false? (palette/fuzzy? "abcd" "abc")))))           ; query longer than the target

(deftest palette-candidates
  (testing ":file source maps the git tree, fuzzy-filters by label, caps at 60"
    (let [projects [{:root "/p" :files ["readme.md" "core.cljs" "deps.edn"]}]]
      (is (= [{:label "readme.md" :path "/p/readme.md" :kind :file}
              {:label "core.cljs" :path "/p/core.cljs" :kind :file}
              {:label "deps.edn"  :path "/p/deps.edn"  :kind :file}]
             (palette/candidates :file "" projects)))
      (is (= ["core.cljs"] (mapv :label (palette/candidates :file "cor" projects))))
      (is (= 60 (count (palette/candidates :file ""
                                           [{:root "/p" :files (mapv #(str "f" % ".md") (range 100))}]))))))
  (testing ":theme source lists both themes, fuzzy-filtered"
    (is (= ["Spacemacs Dark" "Spacemacs Light"] (mapv :label (palette/candidates :theme "" nil))))
    (is (= [{:label "Spacemacs Light" :theme "spacemacs-light" :kind :theme}]
           (palette/candidates :theme "light" nil)))))

;; ---- context menu: items adapt to the right-clicked target kind ----
(deftest context-menu-items-for
  (let [labels (fn [items] (->> items (remove #{:sep}) (keep :label) set))]
    (testing "each target kind yields its expected items"
      (is (= #{"Open" "Open in new tab" "Copy file path" "Copy file name"}
             (labels (context-menu/items-for {:kind :file :path "/x/a.md"} false false))))
      (is (contains? (labels (context-menu/items-for {:kind :dir :path "/x/d"} false false))
                     "Open in file manager"))
      (is (contains? (labels (context-menu/items-for {:kind :http :path "https://x" :text "X"} false false))
                     "Open in system browser"))
      (is (contains? (labels (context-menu/items-for {:kind :http :path "https://x" :text "X"} false false))
                     "Copy link text"))
      (is (= #{"Copy" "Copy source location"}
             (labels (context-menu/items-for {:kind :preview-body :text "hi" :source-location "f:1"} false false))))
      (is (contains? (labels (context-menu/items-for {:kind :doc :path "/x/a.md"} false false)) "View Source"))
      (is (contains? (labels (context-menu/items-for {:kind :doc :path "/x/a.md"} true false)) "View Preview"))
      (is (= #{} (labels (context-menu/items-for {:kind :unknown} false false)))))   ; unknown → nil → no items
    (testing "an http link without text omits Copy link text"
      (is (not (contains? (labels (context-menu/items-for {:kind :http :path "https://x"} false false))
                          "Copy link text"))))
    (testing "the bidirectional jump items appear only with a source line (Go to preview also needs a previewable doc)"
      (is (contains? (labels (context-menu/items-for {:kind :preview-body :text "hi" :source-line 12} false false))
                     "Go to source"))
      (is (not (contains? (labels (context-menu/items-for {:kind :preview-body :text "hi"} false false))
                          "Go to source")))
      (is (contains? (labels (context-menu/items-for {:kind :source-body :path "/x/a.md" :source-line 7} true true))
                     "Go to preview"))
      ;; a non-previewable source (e.g. a .rs file with no preview) hides "Go to preview" even with a line
      (is (not (contains? (labels (context-menu/items-for {:kind :source-body :path "/x/a.rs" :source-line 7} true false))
                          "Go to preview"))))))

;; ---- keybindings editor: chord humanization (drives the editor's binding chips) ----
(deftest kbedit-chord-format
  (testing "pretty-chord humanizes modifier prefixes + named keys"
    (is (= "Ctrl+PgUp" (kbe/pretty-chord "C-prior")))   ; the Ctrl+PageUp tab-cycle binding renders cleanly
    (is (= "Ctrl+PgDn" (kbe/pretty-chord "C-next")))
    (is (= "Ctrl+Shift+Tab" (kbe/pretty-chord "C-S-tab")))
    (is (= "Alt+←"     (kbe/pretty-chord "M-left")))
    (is (= "Alt+→"     (kbe/pretty-chord "M-right")))
    (is (= "Esc"       (kbe/pretty-chord "escape")))
    (is (= "F3"        (kbe/pretty-chord "f3"))))
  (testing "parse-chord splits the C-/M-/S- prefixes from the base key"
    (is (= {:mods ["Ctrl"] :base "prior"} (kbe/parse-chord "C-prior")))
    (is (= {:mods ["Ctrl" "Shift"] :base "tab"} (kbe/parse-chord "C-S-tab")))
    (is (= {:mods [] :base "f3"} (kbe/parse-chord "f3")))))

(deftest markdown-render-preview-metadata
  (async done
    (-> (js/Promise.all
         ;; render-ir is the render path now (ADR-0017); it returns {:html :toc :assets (+ :ir)} byte-identical
         ;; to the retired legacy render, so this metadata test exercises the live path.
         #js [(markdown/render-ir "Hello **world**\n\n![Alt](img.png)\n" "/tmp" 1)
              (markdown/render-ir "# Title\n\n[![Alt](img.png)](target.md)\n" "/tmp" 1)
              (markdown/render-ir "[diagram](diagram.svg)\n" "/tmp" 1)])
        (.then (fn [results]
                 (let [bare   (aget results 0)
                       linked (aget results 1)
                       plain-link (aget results 2)
                       bare-html (:html bare)
                       linked-html (:html linked)]
                   (is (str/includes? bare-html "data-vv-source-start-line=\"1\""))
                   (is (str/includes? bare-html "data-vv-source-kind=\"text\""))
                   (is (re-find #"<a[^>]+href=\"file:///tmp/img\.png\?vv-cache=1\"" bare-html)
                       "bare images are wrapped in links to their resolved preview URI")
                   (is (= ["/tmp/img.png"] (:assets bare)) "local media paths are emitted with cache tokens stripped")
                   (is (= [{:level 1 :text "Title" :id "title"}] (:toc linked)) "headings are emitted as TOC metadata")
                   (is (= 1 (count (re-seq #"<a " linked-html)))
                       "already-linked images are not wrapped again")
                   (is (empty? (:assets plain-link)) "plain links to media files are not watched as embedded assets"))
                 (done)))
        (.catch (fn [e]
                  (is false (str "Markdown render failed: " (.-message e)))
                  (done))))))

(deftest markdown-code-span-vs-math
  ;; GFM precedence: an inline code span outranks $…$ math, so `$\oplus$` (backticks OUTSIDE) is LITERAL code,
  ;; while bare $\oplus$ and GitHub's $`x^2`$ (backticks inside) are math. In :node-test there is no DOMParser,
  ;; so render-html-math is a no-op and a math node stays as its <code class="language-math"> placeholder —
  ;; a convenient "is it math?" oracle. A literal code span must have NO language-math class.
  (async done
    (-> (js/Promise.all
         #js [(markdown/render-ir "A `$\\oplus$` span.\n" nil 1)
              (markdown/render-ir "Bare $\\oplus$ math.\n" nil 1)
              (markdown/render-ir "GH $`x^2`$ math.\n" nil 1)])
        (.then (fn [results]
                 (let [code (:html (aget results 0))
                       bare (:html (aget results 1))
                       gh   (:html (aget results 2))]
                   ;; the literal $\oplus$ text is preserved inside a <code> element (source-positions wraps
                   ;; the text in a <span>, so match leniently), and it is NOT rendered as math
                   (is (str/includes? code "$\\oplus$") "literal $\\oplus$ text is preserved")
                   (is (re-find #"<code[\s\S]*?\$\\oplus\$[\s\S]*?</code>" code) "the literal is inside a <code> element")
                   (is (not (str/includes? code "language-math")) "a code span is NOT rendered as math")
                   (is (not (str/includes? code "math-inline")))
                   (is (str/includes? bare "language-math") "bare $…$ IS inline math")
                   (is (str/includes? bare "\\oplus"))
                   (is (str/includes? gh "language-math") "$`x^2`$ IS inline math")
                   (is (str/includes? gh "x^2"))
                   (is (not (str/includes? gh "`x^2`")) "the GitHub backtick fence is stripped from the TeX"))
                 (done)))
        (.catch (fn [e] (is false (str "render failed: " (.-message e))) (done))))))

(defn -main [& _] (run-tests))
