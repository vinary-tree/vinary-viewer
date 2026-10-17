(ns vinary.main.edn-echo
  "Which windows a watched EDN store's change must NOT go to (vinary.main.edn-store) — the one rule a plain
   watch → broadcast loop gets wrong, kept electron- and fs-free so the node test build asserts it.

   A store's file changes for two kinds of reason: main wrote it on a window's behalf (that window's save), or
   something else did (a hand edit, another process). Main remembers the text it last wrote and the windows it
   wrote that exact text for. When the watcher fires and the file still holds that text, those windows are
   skipped: each already holds that state — or a newer one, since a renderer may have changed again before the
   watcher fired (saves are debounced; the watcher waits for the write to settle). Merging such an echo moved
   live state BACKWARDS: a Preferences font field typed across a pause lost the characters typed since its last
   save (docs/scientific/10 §12). Any other text is news to every window, the last writer included.")

(defn record-write
  "The store's last-write record after main writes `text` for the window whose webContents id is `writer`.
   Writing the SAME text again (another window saving identical state) adds its writer; a different text
   starts over. `last-write` is {:text :writers #{id …}} or nil."
  [last-write text writer]
  (if (and last-write (= text (:text last-write)))
    (update last-write :writers conj writer)
    {:text text :writers #{writer}}))

(defn skipped
  "The webContents ids that the file's text `text` — read when its watcher fired — must not be sent to: the
   windows main last wrote exactly this text for, else none. `last-write` is the record-write record, or nil
   before main has written anything."
  [last-write text]
  (if (and last-write (= text (:text last-write)))
    (:writers last-write)
    #{}))
