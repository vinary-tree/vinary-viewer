(ns vinary.tui.keys-test
  "Byte→event parsing for the TUI: printable chars, control keys, CSI escape sequences, and — critically — escape
   sequences SPLIT across two stdin chunks (retained in `pending` and completed on the next feed) plus a lone ESC
   (retained, then `flush`ed to :escape on the driver's timeout)."
  (:require [cljs.test :refer [deftest is testing]]
            [vinary.tui.keys :as k]))

(defn- events [& byte-vecs]
  (loop [pending [] vs byte-vecs acc []]
    (if (empty? vs)
      acc
      (let [[p es] (k/feed pending (into-array (first vs)))]
        (recur p (rest vs) (into acc es))))))

(deftest printable-chars
  (is (= [{:type :char :ch "a"} {:type :char :ch "b"} {:type :char :ch "c"}]
         (events [0x61 0x62 0x63]))))

(deftest control-keys
  (is (= [{:type :interrupt}] (events [0x03])))
  (is (= [{:type :enter}] (events [0x0d])))
  (is (= [{:type :enter}] (events [0x0a])))
  (is (= [{:type :backspace}] (events [0x7f]))))

(deftest csi-arrows-and-nav
  (is (= [{:type :up}]    (events [0x1b 0x5b 0x41])))
  (is (= [{:type :down}]  (events [0x1b 0x5b 0x42])))
  (is (= [{:type :right}] (events [0x1b 0x5b 0x43])))
  (is (= [{:type :left}]  (events [0x1b 0x5b 0x44])))
  (is (= [{:type :home}]  (events [0x1b 0x5b 0x48])))
  (is (= [{:type :end}]   (events [0x1b 0x5b 0x46])))
  (testing "numeric ~ sequences"
    (is (= [{:type :pgup}] (events [0x1b 0x5b 0x35 0x7e])))   ; ESC[5~
    (is (= [{:type :pgdn}] (events [0x1b 0x5b 0x36 0x7e])))   ; ESC[6~
    (is (= [{:type :home}] (events [0x1b 0x5b 0x31 0x7e]))))) ; ESC[1~

(deftest split-escape-across-chunks
  (testing "a CSI arrow arriving as two chunks completes on the second feed, no key lost/misfired"
    (is (= [{:type :up}] (events [0x1b 0x5b] [0x41])))          ; ESC[ | A
    (is (= [{:type :up}] (events [0x1b] [0x5b] [0x41])))        ; ESC | [ | A
    (is (= [{:type :pgdn}] (events [0x1b 0x5b 0x36] [0x7e])))))  ; ESC[6 | ~

(deftest ss3-application-cursor
  (testing "ESC O A/B/C/D/H/F (SS3 — tmux/screen/xterm application-cursor arrows + Home/End) parse like CSI"
    (is (= [{:type :up}]   (events [0x1b 0x4f 0x41])))
    (is (= [{:type :down}] (events [0x1b 0x4f 0x42])))
    (is (= [{:type :home}] (events [0x1b 0x4f 0x48])))
    (is (= [{:type :end}]  (events [0x1b 0x4f 0x46])))
    (testing "split across chunks"
      (is (= [{:type :up}] (events [0x1b 0x4f] [0x41]))))))

(deftest bracketed-paste-markers
  (testing "ESC[200~ / ESC[201~ surface as paste bounds (so a pasted `q` can't quit)"
    (is (= [{:type :paste-start}] (events [0x1b 0x5b 0x32 0x30 0x30 0x7e])))
    (is (= [{:type :paste-end}]   (events [0x1b 0x5b 0x32 0x30 0x31 0x7e])))))

(deftest lone-escape
  (testing "a lone ESC is retained (could start a CSI), then flush emits it as :escape"
    (let [[pending es] (k/feed [] (into-array [0x1b]))]
      (is (= [] es) "not emitted yet")
      (is (seq pending) "retained")
      (is (= [{:type :escape}] (second (k/flush pending)))))))

(deftest mixed-stream
  (testing "chars, an arrow, and a quit in one chunk parse in order"
    (is (= [{:type :char :ch "j"} {:type :down} {:type :char :ch "q"}]
           (events [0x6a 0x1b 0x5b 0x42 0x71])))))
