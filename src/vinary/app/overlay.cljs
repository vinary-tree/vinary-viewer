(ns vinary.app.overlay
  "Which layer of the UI owns the keyboard, or covers the document — ONE pure answer over app-db for every consumer.

   Before this namespace the renderer asked the question six times, each copy a slightly different hand-written
   list that had drifted: the keymap resolver's `:modal-open?`, `key-scroll!`'s `overlay-open-now?`, the menubar's
   Alt access-key gate, the `:ui/overlay-open?` subscription that hides the native web view, the slides key layer,
   and the shared modal's own handler. The drift was not cosmetic — the SSH authentication prompt was in none of
   the first four, so keymap chords fired under it, arrow keys scrolled the document behind it, and a native web
   view painted over it.

   The categories:
     · modal-dialog-open?   a modal dialog owns the keyboard: Preferences, About, Extensions, Passwords and its save
                            prompt, the key-binding editor, the SSH authentication prompt
     · menu-open?           the menubar's open menu or a context menu
     · palette-open?        the command palette
     · hints-active?        Vimium-style link hints
   and the composites the consumers ask for:
     · blocking?             any of the four — the keyboard belongs to an overlay, not the document
     · paints-over-content?  what the NATIVE web view (painted above all DOM) must hide for: dialogs, menus, the
                             palette — not link hints, which label app DOM targets (a web page has none)")

(defn modal-dialog-open?
  "Does a modal dialog own the keyboard?"
  [db]
  (let [ui (:ui db)]
    (boolean (or (:settings-open? ui)
                 (:about-open? ui)
                 (:extensions-open? ui)
                 (get-in ui [:passwords :open?])
                 (get-in ui [:passwords :save-prompt])
                 (get-in ui [:kbedit :open?])
                 (:ssh-prompt ui)))))

(defn menu-open?
  "Is the menubar's menu or a context menu open?"
  [db]
  (boolean (or (get-in db [:ui :menu]) (get-in db [:ui :context-menu]))))

(defn palette-open?
  "Is the command palette open?"
  [db]
  (boolean (get-in db [:ui :palette :open?])))

(defn hints-active?
  "Are link hints showing?"
  [db]
  (boolean (get-in db [:ui :hints :active?])))

(defn blocking?
  "Does an overlay or modal own the keyboard right now?"
  [db]
  (or (modal-dialog-open? db) (menu-open? db) (palette-open? db) (hints-active? db)))

(defn paints-over-content?
  "Is a DOM overlay showing that the native web view would otherwise paint over?"
  [db]
  (or (modal-dialog-open? db) (menu-open? db) (palette-open? db)))
