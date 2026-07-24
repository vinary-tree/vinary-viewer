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
   password / checkbox / etc. and contentEditable (the CodeMirror source view keeps its own richer menu).

   NARROW on purpose — it decides whether the Cut/Copy/Paste menu applies. For 'does the keyboard belong
   to a text field?' use `keyboard-owner-tag?` / `text-field-focused?` below, which are deliberately wider."
  [^js el]
  (when el
    (case (.-tagName el)
      "TEXTAREA" true
      "INPUT"    (contains? #{"text" "search" "url" "email" "tel" "number"}
                            (some-> (.-type el) str/lower-case))
      false)))

;; ---- "does the keyboard belong to a text field?" -----------------------------------------------------
;; A SECOND, wider predicate, kept here beside its narrow sibling so the two cannot drift. This one decides
;; whether a bare printable key should reach what is focused instead of resolving to a keymap command, so
;; it must say yes for things `editable-target?` says no to: a password box, a <select>, a contenteditable
;; host, and the CodeMirror source view. Typing in any of those must not fire Vim's j/k/n.
;;
;; It is DERIVED from document.activeElement at the moment a key arrives, never cached. A cached copy is
;; leak-by-construction: an element that unmounts while focused fires no blur event, so the flag sticks
;; `true` forever and silently swallows every bare-key binding (ADR-0032).

(def ^:private keyboard-owner-tags #{"INPUT" "TEXTAREA" "SELECT"})

(defn keyboard-owner-tag?
  "PURE. Does this tag name / contentEditable pair own the keyboard? Split out from the DOM walk so it can
   be unit-tested without a document."
  [tag content-editable?]
  (boolean (or (contains? keyboard-owner-tags tag) content-editable?)))

(defn keyboard-owner?
  "True when `el` — or an ancestor — is a field that should receive bare printable keys itself.
   `[contenteditable]` matches even `=\"false\"` (CodeMirror stamps that on .cm-content), which is why
   .cm-editor is listed too: the source view owns its keyboard either way."
  [^js el]
  (boolean (and el (.-closest el)
                (.closest el "input, textarea, select, [contenteditable], .cm-editor"))))

(defn text-field-focused?
  "Is the keyboard currently owned by a text field? The single source of truth for `:in-input?`."
  []
  (and (exists? js/document)
       (keyboard-owner? (.-activeElement js/document))))

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
