(ns vinary.renderer.text-edit
  "Cut / Copy / Paste / Select-All for the app's own text inputs, invoked by the themed right-click menu
   (vinary.ui.context-menu, the :text-input kind). The operations act on the currently-focused input via
   document.execCommand — the menu deliberately does NOT steal focus (see context-menu), so the right-clicked
   field stays document.activeElement. Copy/Cut/Select-All go through execCommand (system clipboard + native
   `input` events, so a React-controlled field's on-change stays in sync); Paste can't use execCommand('paste')
   (blocked in Electron/Chromium), so it reads the SYSTEM clipboard over IPC (window.vv.readText → main
   clipboard.readText) and inserts via execCommand('insertText'), which also fires the input event."
  (:require [clojure.string :as str]))

(defn editable-target?
  "True when `el` is one of the app's editable TEXT fields: a text-like <input> or a <textarea>. Excludes
   password / checkbox / etc. and contentEditable (the CodeMirror source view keeps its own richer menu)."
  [^js el]
  (when el
    (case (.-tagName el)
      "TEXTAREA" true
      "INPUT"    (contains? #{"text" "search" "url" "email" "tel" "number"}
                            (some-> (.-type el) str/lower-case))
      false)))

(defn- vv ^js [] (.-vv js/window))

;; Copy/Cut/Select-All MUST run synchronously inside the menu-item click (a user gesture) so the clipboard write
;; is permitted — the caller (context-menu :action) does exactly that.
(defn copy!       [] (.execCommand js/document "copy"))
(defn cut!        [] (.execCommand js/document "cut"))
(defn select-all! [] (.execCommand js/document "selectAll"))

(defn paste!
  "Read the system clipboard and insert it over the current selection/caret of the focused input."
  []
  (when-let [^js v (vv)]
    (when (.-readText v)
      (-> (.readText v)
          (.then (fn [t] (.execCommand js/document "insertText" false (or t ""))))
          (.catch (fn [_] nil))))))
