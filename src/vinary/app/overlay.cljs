(ns vinary.app.overlay
  "Is something open OVER the document that owns the keyboard? A pure predicate over app-db, so the keymap
   resolver, the slides key layer and their tests ask one question in one place.

   It covers the layered UI that the resolver's older `:modal-open?` check did not: the open menubar menu, any
   context menu, Vimium-style link hints, the command palette and the SSH authentication prompt — besides the
   modal dialogs (Preferences, About, Extensions, Passwords and its save prompt, the key-binding editor).

   It is the fifth such check in the renderer (with the resolver's `:modal-open?`, `key-scroll!`'s
   `overlay-open-now?`, the menubar's and the modal's own); folding them into this one is a recorded follow-up
   (ADR-0045 §Consequences), deliberately not done here so the existing keyboard paths keep their behaviour.")

(defn blocking?
  "Does an overlay or modal own the keyboard right now?"
  [db]
  (let [ui (:ui db)]
    (boolean (or (:menu ui)
                 (:context-menu ui)
                 (get-in ui [:hints :active?])
                 (get-in ui [:palette :open?])
                 (:ssh-prompt ui)
                 (:settings-open? ui)
                 (:about-open? ui)
                 (:extensions-open? ui)
                 (get-in ui [:passwords :open?])
                 (get-in ui [:passwords :save-prompt])
                 (get-in ui [:kbedit :open?])))))
