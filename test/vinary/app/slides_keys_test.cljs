(ns vinary.app.slides-keys-test
  "Slide navigation keys (ADR-0045 §Keys): the :slides keymap layer (identical in every preset, BELOW the mode),
   the resolver's layering, the typed-slide-number events, Escape's cancel order, the overlay predicate, the
   scroll delegate seam, and command gating (keyboard-only commands never reach the palette)."
  (:require [cljs.test :refer [deftest is testing]]
            [re-frame.core]
            [re-frame.events]
            [vinary.app.commands :as commands]
            [vinary.app.events :as events]
            [vinary.app.overlay :as overlay]
            [vinary.app.slides :as slides]
            [vinary.input.events :as input-events]
            [vinary.input.keymap :as keymap]
            [vinary.input.resolver :as resolver]
            [vinary.renderer.scroll :as scroll]
            [vinary.slides.deck :as deck]
            [vinary.ui.keybindings-editor :as kbe]
            [vinary.ui.menubar :as menubar]))

(def ^:private presets [:default :vim :emacs])

(defn- leaf-ids
  "Every command id a keymap trie binds (keyword leaves and {:id …} leaves)."
  [trie]
  (mapcat (fn [[_ v]]
            (cond (keyword? v)              [v]
                  (and (map? v) (:id v))    [(:id v)]
                  (map? v)                  (leaf-ids v)
                  :else                     []))
          trie))

(deftest slides-layer-is-identical-in-every-preset
  (let [layers (map #(get-in (keymap/preset %) [:modes :slides]) presets)]
    (is (every? seq layers) "every preset has a :slides layer")
    (is (apply = layers) "default, vim and emacs bind the same slide keys")
    (testing "every binding names a registered command"
      (doseq [id (leaf-ids (first layers))]
        (is (contains? commands/registry id) (str id " is in vinary.app.commands/registry"))))
    (testing "the digits carry their value as an argument"
      (doseq [d (range 10)]
        (is (= {:id :slides/digit :args [d]} (get (first layers) (str d))))))))

(deftest slides-layer-resolution
  (let [active {:in-input? false :slides-active? true}
        idle   {:in-input? false :slides-active? false}]
    (testing "Standard (non-modal, insert mode): the layer applies only while slides are active"
      (keymap/install! :default)
      (is (= {:action :dispatch :command :slides/next} (resolver/step (keymap/modes) :insert [] "space" active)))
      (is (= :slides/prev (:command (resolver/step (keymap/modes) :insert [] "S-space" active))))
      (is (= :slides/next-frame (:command (resolver/step (keymap/modes) :insert [] "S-right" active))))
      (is (= :slides/prev-frame (:command (resolver/step (keymap/modes) :insert [] "S-up" active))))
      (is (= {:action :dispatch :command :slides/digit :args [7]}
             (resolver/step (keymap/modes) :insert [] "7" active)))
      (is (= :pass (:action (resolver/step (keymap/modes) :insert [] "space" idle)))
          "without an active slide show, Space is not taken")
      (is (= :input/escape (:command (resolver/step (keymap/modes) :insert [] "escape" active)))
          ":all bindings the layer does not override still resolve")
      (is (= :search/start (:command (resolver/step (keymap/modes) :insert [] "C-f" active)))))
    (testing "Vim: the layer sits BELOW the mode — Space stays the leader; free keys reach the slides"
      (keymap/install! :vim)
      (is (= :prefix (:action (resolver/step (keymap/modes) :normal [] "space" active)))
          "normal-mode Space still starts a leader sequence")
      (is (= :search/next (:command (resolver/step (keymap/modes) :normal [] "n" active))))
      (is (= :slides/enter (:command (resolver/step (keymap/modes) :normal [] "enter" active))))
      (is (= :slides/digit (:command (resolver/step (keymap/modes) :normal [] "3" active))))
      (is (= :slides/next (:command (resolver/step (keymap/modes) :insert [] "space" active)))
          "insert mode has no Space binding, so the layer's applies")
      (is (= :consume (:action (resolver/step (keymap/modes) :normal [] "3" idle)))
          "an idle slides layer adds nothing: an unbound normal-mode key is swallowed as before"))
    (testing "Emacs"
      (keymap/install! :emacs)
      (is (= :slides/next (:command (resolver/step (keymap/modes) :insert [] "space" active))))
      (is (= :slides/backspace (:command (resolver/step (keymap/modes) :insert [] "backspace" active)))))
    (keymap/install! :default)))

(deftest a-user-override-of-the-layer-merges-like-any-mode
  (let [km (keymap/merge-user {:extends :default :keymaps {:slides {"n" :slides/next "space" :unbind}}})]
    (is (= :slides/next (get-in km [:modes :slides "n"])))
    (is (not (contains? (get-in km [:modes :slides]) "space")) ":unbind removes a layer binding")
    (is (= :slides/enter (get-in km [:modes :slides "enter"])) "the rest of the layer is kept")))

(def ^:private slides-db {:ui {:slides {:path "/d/talk.pdf" :layout :slides :page 3}}})

(deftest typed-slide-numbers
  (testing "digits accumulate in the slides layout and re-arm the timeout"
    (let [{db1 :db fx1 :fx} (events/slides-digit-fx slides-db 1)
          {db2 :db}         (events/slides-digit-fx db1 2)]
      (is (= "1" (slides/digits db1)))
      (is (= "12" (slides/digits db2)))
      (is (= [[:async/debounce {:key events/slides-digits-key :ms events/slides-digits-ms
                                :dispatch [:slides/digits-clear]}]]
             fx1))))
  (testing "no buffer outside the slides layout, and only 0-9"
    (is (nil? (events/slides-digit-fx {:ui {:slides {:path "/d/p.pdf" :layout :document}}} 1)))
    (is (nil? (events/slides-digit-fx {:ui {}} 1)))
    (is (nil? (events/slides-digit-fx slides-db 10)))
    (is (nil? (events/slides-digit-fx slides-db nil))))
  (testing "the buffer keeps at most deck/max-digits digits"
    (let [db (reduce (fn [db d] (:db (events/slides-digit-fx db d))) slides-db (range 1 10))]
      (is (= deck/max-digits (count (slides/digits db))))
      (is (= "456789" (slides/digits db)))))
  (testing "Enter goes to the typed slide (by label, then page) and clears the buffer; else next"
    (let [db (assoc-in slides-db [:ui :slides :digits] "12")
          {db' :db fx :fx} (events/slides-enter-fx db)]
      (is (nil? (slides/digits db')))
      (is (= [[:async/cancel events/slides-digits-key] [:slides/command [:goto-label "12"]]] fx)))
    (is (= {:fx [[:dispatch [:slides/command :next]]]} (events/slides-enter-fx slides-db))))
  (testing "Backspace deletes a typed digit (the last one clears the buffer); else previous"
    (let [{db1 :db fx1 :fx} (events/slides-backspace-fx (assoc-in slides-db [:ui :slides :digits] "12"))]
      (is (= "1" (slides/digits db1)))
      (is (= :async/debounce (ffirst fx1)))
      (let [{db2 :db fx2 :fx} (events/slides-backspace-fx db1)]
        (is (nil? (slides/digits db2)))
        (is (= [[:async/cancel events/slides-digits-key]] fx2))))
    (is (= {:fx [[:dispatch [:slides/command :prev]]]} (events/slides-backspace-fx slides-db))))
  (testing "navigating abandons a half-typed number"
    (let [{db :db fx :fx} (events/slides-command-fx (assoc-in slides-db [:ui :slides :digits] "4") :next)]
      (is (nil? (slides/digits db)))
      (is (= [[:slides/command :next] [:async/cancel events/slides-digits-key]] fx)))
    (is (= {:fx [[:slides/command :last]]} (events/slides-command-fx slides-db :last))
        "nothing typed: just the op, app-db untouched")))

(deftest the-position-mirror-keeps-ui-owned-keys
  (let [cur {:path "/d/talk.pdf" :layout :slides :page 3 :digits "1"}]
    (is (= "1" (:digits (slides/merge-report cur {:path "/d/talk.pdf" :layout :slides :page 4})))
        "the same deck's next report keeps the typed number")
    (is (= 4 (:page (slides/merge-report cur {:path "/d/talk.pdf" :layout :slides :page 4}))))
    (is (nil? (:digits (slides/merge-report cur {:path "/d/other.pdf" :layout :slides :page 0})))
        "another document starts clean")
    (is (nil? (:digits (slides/merge-report cur {:path "/d/talk.pdf" :layout :document :page 3})))
        "leaving the slides layout forgets it")
    (is (= {:path "/d/a.pdf" :layout :slides} (slides/merge-report nil {:path "/d/a.pdf" :layout :slides})))))

(deftest escape-cancels-slide-input-before-switching-mode
  (let [typed (assoc-in slides-db [:ui :slides :digits] "3")]
    (is (= {:fx [[:dispatch [:slides/digits-clear]]]}
           (input-events/escape-fx (assoc-in typed [:ui :input :mode] :insert)))
        "a typed number is cancelled first — no mode side effect")
    (is (= {:fx [[:dispatch [:find/close]]]}
           (input-events/escape-fx (assoc-in typed [:ui :find :visible?] true)))
        "the find bar still closes before the slide steps")
    (is (= :normal (get-in (:db (input-events/escape-fx (assoc-in slides-db [:ui :input :mode] :insert)))
                           [:ui :input :mode]))
        "with nothing typed, Escape switches to normal mode as before")))

(deftest overlay-categories
  (testing "modal dialogs — the SSH prompt included (it was missing from every older copy)"
    (doseq [[label db] [["Preferences" {:ui {:settings-open? true}}] ["About" {:ui {:about-open? true}}]
                        ["Extensions" {:ui {:extensions-open? true}}] ["Passwords" {:ui {:passwords {:open? true}}}]
                        ["the save prompt" {:ui {:passwords {:save-prompt {:host "x"}}}}]
                        ["the key-binding editor" {:ui {:kbedit {:open? true}}}]
                        ["the SSH prompt" {:ui {:ssh-prompt {:promptId 1}}}]]]
      (is (true? (overlay/modal-dialog-open? db)) label)
      (is (true? (overlay/paints-over-content? db)) (str label " hides the native web view"))))
  (testing "menus, the palette and hints are not dialogs"
    (doseq [db [{:ui {:menu "View"}} {:ui {:context-menu {:x 1}}} {:ui {:palette {:open? true}}} {:ui {:hints {:active? true}}}]]
      (is (false? (overlay/modal-dialog-open? db)) (pr-str db))))
  (testing "link hints own the keyboard but do not hide the web view (they label app DOM; a web page has none)"
    (is (true? (overlay/blocking? {:ui {:hints {:active? true}}})))
    (is (false? (overlay/paints-over-content? {:ui {:hints {:active? true}}}))))
  (testing "menubar access keys: blocked by any OTHER overlay, never by its own open menu"
    (is (false? (menubar/access-keys-blocked? {:ui {:menu "File"}})))
    (doseq [db [{:ui {:extensions-open? true}} {:ui {:passwords {:open? true}}} {:ui {:ssh-prompt {:promptId 2}}}
                {:ui {:hints {:active? true}}} {:ui {:context-menu {:x 1}}} {:ui {:palette {:open? true}}}]]
      (is (true? (menubar/access-keys-blocked? db)) (pr-str db)))))

(deftest overlays-that-own-the-keyboard
  (is (false? (overlay/blocking? {:ui {}})))
  (doseq [[label db] [["an open menubar menu" {:ui {:menu "View"}}]
                      ["a context menu" {:ui {:context-menu {:x 1 :y 2}}}]
                      ["link hints" {:ui {:hints {:active? true}}}]
                      ["the palette" {:ui {:palette {:open? true}}}]
                      ["the SSH prompt" {:ui {:ssh-prompt {:promptId 1}}}]
                      ["Preferences" {:ui {:settings-open? true}}]
                      ["About" {:ui {:about-open? true}}]
                      ["Extensions" {:ui {:extensions-open? true}}]
                      ["Passwords" {:ui {:passwords {:open? true}}}]
                      ["the password save prompt" {:ui {:passwords {:save-prompt {:host "x"}}}}]
                      ["the key-binding editor" {:ui {:kbedit {:open? true}}}]]]
    (is (true? (overlay/blocking? db)) label)))

(deftest the-scroll-delegate-seam
  (let [seen (atom [])
        f    (fn [opts] (swap! seen conj opts) true)
        g    (fn [_] true)]
    (is (nil? (scroll/delegate! {:dy 80})) "no delegate: the caller scrolls")
    (scroll/set-delegate! f)
    (is (true? (scroll/delegate! {:dy :page :shift? true})))
    (is (= [{:dy :page :shift? true}] @seen))
    (scroll/clear-delegate! g)
    (is (true? (scroll/delegate! {:to :top})) "clearing someone else's delegate leaves the current one")
    (scroll/clear-delegate! f)
    (is (nil? (scroll/delegate! {:to :top})))
    (testing "every scroll command maps to a slide op (Shift+PageDown = a frame)"
      (is (= :next-frame (deck/scroll->op {:dy :page :shift? true})))
      (is (= :prev-frame (deck/scroll->op {:dy :-page :shift? true})))
      (is (= :first (deck/scroll->op {:to :top :shift? true}))))))

(deftest slide-commands-are-gated
  (testing "navigation shows (palette) and runs only with a slides view"
    (is (true?  (commands/allowed? :slides/next {:slides-view? true})))
    (is (false? (commands/allowed? :slides/next {:slides-view? false})))
    (is (true?  (commands/allowed? :view/toggle-slides {:pdf-view? true})))
    (is (false? (commands/allowed? :view/toggle-slides {}))))
  (testing "the typed-number commands are keyboard-only: the palette's context never sets :slides-active?"
    (let [palette-ctx {:slides-view? true :pdf-view? true :in-input? true :tabs [1]}
          visible     (set (map :id (commands/all-visible palette-ctx)))]
      (is (contains? visible :slides/next))
      (is (contains? visible :slides/next-frame))
      (is (not-any? visible [:slides/digit :slides/enter :slides/backspace]))))
  (testing "the key-binding editor lists every category the registry uses"
    (is (empty? (remove (set kbe/category-order) (set (map :category (vals commands/registry))))))))

(deftest keys-run-view-moves-at-once
  ;; docs/scientific/12 §Flip latency (H-sched): re-frame's queue drains only after the next frame once a key has been
  ;; dispatched, so a key's view move runs at once (commands/dispatch-now!) and shows one frame sooner
  (testing "every immediate command is a registered command"
    (is (empty? (remove (set (keys commands/registry)) commands/immediate-commands))))
  (testing "every command that only scrolls or steps the slides is immediate (a new one cannot miss the fast path)"
    (let [view-moves (for [[id spec] commands/registry
                           :when (#{:nav/scroll :slides/command} (first (:dispatch spec)))]
                       id)]
      (is (seq view-moves))
      (is (empty? (remove commands/immediate-commands view-moves)))))
  (let [calls (atom [])
        run!  (fn [& args]
                (reset! calls [])
                (with-redefs [re-frame.core/dispatch      (fn [ev] (swap! calls conj [:queued ev]))
                              re-frame.core/dispatch-sync (fn [ev] (swap! calls conj [:now ev]))]
                  (apply commands/run args))
                @calls)]
    (testing "from a key handler ({:now? true}) an immediate command runs at once"
      (is (= [[:now [:slides/command :next]]] (run! :slides/next {:slides-view? true} nil {:now? true})))
      (is (= [[:now [:nav/scroll {:dy 80}]]] (run! :nav/scroll-down {} nil {:now? true}))))
    (testing "…an ordinary command still goes through the queue"
      (is (= [[:queued [:slides/present {:from :first}]]] (run! :slides/present {:pdf-view? true} nil {:now? true}))))
    (testing "without {:now? true} (the palette) everything goes through the queue"
      (is (= [[:queued [:slides/command :next]]] (run! :slides/next {:slides-view? true})))))
  (testing "inside a re-frame handler dispatch-now! falls back to the queue (re-frame refuses dispatch-sync there)"
    (let [calls (atom [])]
      (with-redefs [re-frame.core/dispatch      (fn [ev] (swap! calls conj [:queued ev]))
                    re-frame.core/dispatch-sync (fn [ev] (swap! calls conj [:now ev]))]
        (binding [re-frame.events/*handling* [:some/event]]
          (commands/dispatch-now! [:nav/scroll {:dy 80}])))
      (is (= [[:queued [:nav/scroll {:dy 80}]]] @calls)))))

(deftest the-engine-shortcut-is-exactly-the-event-when-app-db-is-untouched
  ;; renderer.pdf/step! runs a slide op on the engine at once unless :slides/command would change app-db
  ;; (slides/command-needs-db?). The shortcut is sound only if, whenever it is taken, the event's whole effect is that
  ;; one engine call — checked for every combination of a blanked screen and a typed number.
  (doseq [blank  [nil :black :white]
          typed  [nil "" "12"]
          :let   [db (cond-> {:ui {:slides {:layout :slides}}}
                       blank (assoc-in [:ui :slides :blank] blank)
                       typed (assoc-in [:ui :slides :digits] typed))
                  fx (events/slides-command-fx db :next)]]
    (is (= (slides/command-needs-db? db) (contains? fx :db)) (pr-str [blank typed]))
    (when-not (slides/command-needs-db? db)
      (is (= {:fx [[:slides/command :next]]} fx) (pr-str [blank typed])))))
