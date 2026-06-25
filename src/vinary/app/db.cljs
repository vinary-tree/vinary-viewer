(ns vinary.app.db
  "The re-frame app-db default value — ephemeral UI only. The documents/tabs live in DataScript
   (vinary.app.ds); :ds/rev is the DataScript-transaction revision the conn-reading subs depend on.")

(def default-db
  {:ds/rev 0
   :ui {:theme "spacemacs-dark"
        :active-heading nil
        :sidebar-visible? true
        :sidebar-width 280          ; px; the resizable sidebar splitter writes this
        :sidebar-tab :files         ; :files | :contents (the tabbed sidebar)
        :tree-selected nil
        ;; browser-tab model: tabs are views ({:id :uri :hist {:stack :idx}}); DataScript caches content.
        :tabs []
        :active-tab nil
        :next-tab-id 0
        :projects []                ; [{:root :files} …] — one git-rooted file tree per open project
        ;; menu bar / dialogs / context menu (Phase 5)
        :menu nil                   ; which top-level menu is open (label) or nil
        :settings {}                ; persisted prefs (theme + fonts), loaded from settings.edn
        :settings-open? false
        :about-open? false
        :app-info nil               ; {:name :version :repo} pushed by main
        :context-menu nil           ; {:x :y :target {…}} or nil
        :find {:visible? false :query "" :count 0 :idx 0}
        ;; keybinding / modal / sequence state (ephemeral UI; the keymap itself lives in
        ;; vinary.input.keymap's atom, not here)
        ;; :mode starts :insert (matches the non-modal default keymap; the active keymap's
        ;; :initial-mode is applied at boot / on config — vim switches this to :normal)
        :input {:mode :insert :sequence [] :count nil :in-input? false :timeout-id nil}
        ;; command palette / fuzzy finder
        :palette {:open? false :source :command :prefix "" :query "" :items [] :selected 0}}})
