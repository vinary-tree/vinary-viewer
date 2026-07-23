(ns vinary.main.windows-test
  "Guards the window resolvers against the recurring `TypeError: Object has been destroyed` daemon crash —
   sending IPC to a window that has since closed.

   `broadcast!` covers the GLOBAL push path (a file-watcher outliving the window it captured). `live`,
   `active`, `active-wc` and `send!` cover the TARGETED path: the adblock refresh timer fired every 24 h,
   found no registered window (the daemon survives window-all-closed), fell back to the webContents captured
   at init! — a hidden pool window long since claimed and closed — and threw out of the timer callback, which
   is an uncaught main-process exception. All of them must SKIP any destroyed webContents."
  (:require [cljs.test :refer [deftest is testing]]
            [vinary.main.windows :as windows]))

(defn- fake-window
  "A minimal stand-in for a BrowserWindow: a `webContents` whose `isDestroyed` returns `destroyed?` and whose
   `send` appends [channel payload] to `sent`."
  [sent destroyed?]
  #js {:webContents #js {:isDestroyed (fn [] destroyed?)
                         :send        (fn [ch p] (swap! sent conj [ch p]))}})

(deftest broadcast-skips-destroyed-windows
  (testing "broadcast! delivers to live windows only, never a destroyed webContents"
    (let [sent (atom [])
          dead (fake-window sent true)
          live (fake-window sent false)]
      (windows/add! dead)                      ; a closed window still lingering in the registry
      (windows/add! live)
      (windows/broadcast! "vv:recent" "payload")
      (is (= [["vv:recent" "payload"]] @sent) "only the live window received the message")
      (windows/remove! dead)                   ; keep the defonce registry clean for other tests
      (windows/remove! live))))

(deftest broadcast-empty-registry-is-noop
  (testing "broadcast! with no live windows does nothing and does not throw"
    (let [sent (atom [])]
      (windows/broadcast! "vv:recent" "x")
      (is (= [] @sent)))))

(deftest live-rejects-destroyed-webcontents
  (testing "live is the shared liveness predicate every captured-wc fallback goes through"
    (let [sent   (atom [])
          ^js dw (.-webContents (fake-window sent true))
          ^js lw (.-webContents (fake-window sent false))]
      (is (nil? (windows/live nil))          "nil wc → nil (nothing was ever captured)")
      (is (nil? (windows/live js/undefined)) "undefined wc → nil")
      (is (nil? (windows/live dw))           "destroyed wc → nil, so callers never send to it")
      (is (identical? lw (windows/live lw))  "live wc → itself"))))

(deftest active-skips-destroyed-windows
  (testing "active/active-wc fall through a window whose webContents is already destroyed"
    (let [sent (atom [])
          alive (fake-window sent false)
          dead  (fake-window sent true)]
      ;; `dead` is registered LAST — core removes a window on `closed`, so between `close` and `closed` the
      ;; most-recent registry entry can already be destroyed. A blind `peek` would return it.
      (windows/add! alive)
      (windows/add! dead)
      (is (identical? alive (windows/active)) "the most-recent LIVE window, not the destroyed one")
      (is (identical? (.-webContents alive) (windows/active-wc)) "and its webContents")
      (windows/remove! alive)
      (windows/remove! dead))))

(deftest active-nil-when-no-live-window
  (testing "active/active-wc are nil when the registry is empty or holds only destroyed windows"
    (let [sent (atom [])
          dead (fake-window sent true)]
      (is (nil? (windows/active))    "empty registry (the daemon's steady state)")
      (is (nil? (windows/active-wc)) "…and no webContents to send to")
      (windows/add! dead)
      (is (nil? (windows/active))    "a registry of only destroyed windows resolves to nothing")
      (is (nil? (windows/active-wc)))
      (windows/remove! dead))))

(deftest send-targets-the-active-window
  (testing "send! delivers to the active window's renderer and reports that it landed"
    (let [sent  (atom [])
          alive (fake-window sent false)]
      (windows/add! alive)
      (is (true? (windows/send! "vv:adblock-status" "payload")))
      (is (= [["vv:adblock-status" "payload"]] @sent))
      (windows/remove! alive))))

(deftest send-is-a-silent-noop-without-a-live-window
  (testing "send! never throws when every window has closed — the adblock-timer crash regression"
    (let [sent (atom [])
          dead (fake-window sent true)]
      (is (false? (windows/send! "vv:adblock-status" "x")) "empty registry → not delivered, no throw")
      (windows/add! dead)
      (is (false? (windows/send! "vv:adblock-status" "x")) "destroyed window → not delivered, no throw")
      (is (= [] @sent) "nothing was ever sent to a destroyed webContents")
      (windows/remove! dead))))
