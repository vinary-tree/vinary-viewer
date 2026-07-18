(ns vinary.main.windows-test
  "Guards `windows/broadcast!` — the fix for the daemon crash where a file-watcher pushed global state to a
   window that had since closed (`TypeError: Object has been destroyed`). A broadcast must reach every LIVE
   window's renderer and SKIP any destroyed webContents."
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
