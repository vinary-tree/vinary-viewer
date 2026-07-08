(ns vinary.tui.keys
  "Raw-stdin bytes → key events, PURE and incremental so it is unit-testable without a terminal. `feed` consumes a
   byte chunk and returns [pending' events]; a partial escape sequence split across two `data` chunks is retained
   in `pending` until completed. A lone trailing ESC is ambiguous (the Escape key vs the start of a CSI sequence),
   so it is retained and the driver calls `flush` on a short timeout to emit it as {:type :escape} — the standard
   TUI disambiguation. No terminal I/O here; vinary.tui.term wires stdin to this."
  (:refer-clojure :exclude [flush])         ; `flush` names the ESC-timeout emitter here (not clojure.core/flush)
  (:require [clojure.string :as str]))

;; CSI final byte → event (ESC [ <final>)
(def ^:private csi-final
  {0x41 {:type :up} 0x42 {:type :down} 0x43 {:type :right} 0x44 {:type :left}
   0x48 {:type :home} 0x46 {:type :end}})
;; CSI numeric (ESC [ <n> ~) → event; 200/201 are bracketed-paste bounds (so a paste that contains `q` can't quit)
(def ^:private csi-tilde
  {1 {:type :home} 3 {:type :delete} 4 {:type :end} 5 {:type :pgup} 6 {:type :pgdn} 7 {:type :home} 8 {:type :end}
   200 {:type :paste-start} 201 {:type :paste-end}})

(def ^:private max-esc 34)                          ; a legit CSI/SS3 is short; a longer partial is corrupt → resync

(defn- final-byte? [b] (<= 0x40 b 0x7e))            ; a CSI sequence ends at the first byte in @..~

(defn- parse-csi
  "Parse a CSI sequence at the front of `v` (a vector of bytes starting ESC '['). Returns {:len :event} when a
   complete sequence is present, nil when still partial, or a drop ({:type :unknown}) when it runs past `max-esc`
   with no final byte (corrupt input — resync rather than wedge)."
  [v]
  (loop [i 2]
    (cond
      (>= i (count v)) (when (> (count v) max-esc) {:len (count v) :event {:type :unknown}})
      (final-byte? (nth v i))
      (let [final  (nth v i)
            params (subvec v 2 i)
            len    (inc i)]
        (cond
          (contains? csi-final final)   {:len len :event (csi-final final)}
          (= 0x7e final)                {:len len :event (or (csi-tilde (js/parseInt (apply str (map char params)) 10))
                                                             {:type :unknown})}
          :else                         {:len len :event {:type :unknown}}))
      :else (recur (inc i)))))

(defn- ctrl-event [b]
  (case b
    0x03 {:type :interrupt}                          ; Ctrl-C
    (0x0d 0x0a) {:type :enter}                        ; CR / LF
    (0x7f 0x08) {:type :backspace}
    0x09 {:type :tab}
    nil))

(defn feed
  "Consume `bytes` (a JS array / Buffer of byte values) given the retained `pending` vector. Returns
   [pending' [event …]]. Complete keys are emitted; an incomplete trailing escape stays in pending'."
  [pending bytes]
  (loop [v (into (vec pending) (array-seq bytes)) events []]
    (if (empty? v)
      [[] events]
      (let [b (nth v 0)]
        (cond
          (= 0x1b b)
          (let [nxt (get v 1)]
            (cond
              (nil? nxt)     [v events]               ; lone trailing ESC → retain (driver flush → :escape)
              (= 0x5b nxt)                             ; ESC [ … → CSI
              (if-let [{:keys [len event]} (parse-csi v)]
                (recur (subvec v len) (conj events event))
                [v events])                           ; partial CSI → retain the whole thing
              (= 0x4f nxt)                             ; ESC O <final> → SS3 (application-cursor arrows / Home / End,
              (cond                                    ; sent by tmux/screen/xterm — MUST parse or arrows go dead)
                (>= (count v) 3) (recur (subvec v 3) (conj events (or (csi-final (nth v 2)) {:type :unknown})))
                (> (count v) max-esc) (recur (subvec v 2) events)   ; corrupt → drop ESC O, resync
                :else [v events])                      ; partial SS3 → retain
              :else                                   ; ESC + other → Escape key, then reprocess the rest
              (recur (subvec v 1) (conj events {:type :escape}))))

          (< b 0x20)
          (if-let [e (ctrl-event b)]
            (recur (subvec v 1) (conj events e))
            (recur (subvec v 1) events))              ; drop other C0 control bytes

          (= b 0x7f) (recur (subvec v 1) (conj events {:type :backspace}))

          ;; a printable byte — decode the UTF-8 code point (1–4 bytes); retain a partial multibyte tail
          :else
          (let [n (cond (< b 0x80) 1 (< b 0xe0) 2 (< b 0xf0) 3 :else 4)]
            (if (< (count v) n)
              [v events]                              ; partial multibyte char → retain
              (let [cp (js/String.fromCodePoint
                        (if (= n 1) b
                            (.codePointAt (.toString (js/Buffer.from (into-array (subvec v 0 n))) "utf8") 0)))]
                (recur (subvec v n) (conj events {:type :char :ch cp}))))))))))

(defn flush
  "Emit any retained sequence as a terminal key: a lone ESC → {:type :escape}. Called by the driver on a short
   timeout so a bare Escape keypress isn't held forever waiting for a CSI that will never come. Returns
   [pending' events]."
  [pending]
  (if (and (seq pending) (= 0x1b (nth pending 0)))
    [[] [{:type :escape}]]
    [pending []]))
