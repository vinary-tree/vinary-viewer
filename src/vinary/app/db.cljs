(ns vinary.app.db
  "The re-frame app-db default value. Tabs/history and ephemeral UI live here; DataScript
   (vinary.app.ds) is the bounded content cache. :ds/rev is the DataScript-transaction revision the
   conn-reading subs depend on.")

(def default-db
  {:ds/rev 0
   :ui {:theme "spacemacs-dark"
        :active-heading nil
        :sidebar-visible? true
        :sidebar-width 280          ; px; the resizable sidebar splitter writes this
        :sidebar-tab :files         ; :files | :contents (the tabbed sidebar)
        :tree-selected nil
        :dir-selected nil           ; highlighted entry path in the active directory view (Alt+Down opens it)
        ;; browser-tab model: tabs are views ({:id :uri :hist {:stack :idx}}); DataScript caches content.
        :tabs []
        :active-tab nil
        :next-tab-id 0
        :projects []                ; [{:root :files} …] — one git-rooted file tree per open project
        ;; menu bar / dialogs / context menu (Phase 5)
        :menu nil                   ; which top-level menu is open (label) or nil
        :menu-submenu nil           ; which flyout submenu is open inside the active menu, or nil
        :menu-focus nil             ; focused item index inside the open top-level menu
        :menu-submenu-focus nil     ; focused item index inside the open flyout submenu
        :access-keys-active? false  ; Alt/menu/dialog mnemonic hints are visible while true
        :settings {}                ; persisted prefs (theme + fonts + sidebar), loaded from settings.edn
        ;; persisted (recent.edn): dir→last-child trail + recent-files MRU + browser-history URL MRU
        :recent {:trail {} :recent-files [] :web-history []}
        :settings-open? false
        :about-open? false
        :app-info nil               ; {:name :version :repo} pushed by main
        :open-dialog-mode :current  ; how vv:open-files should handle selected paths
        :context-menu nil           ; {:x :y :target {…}} or nil
        :hover-link nil             ; URI of the link under the cursor (status bar), or nil
        :ctrl-held? false           ; Control currently held (drives the breadcrumb URI bar)
        :tab-drop nil               ; {:over tab-id :after? bool} tab-drag drop indicator, or nil
        :re-frame-10x-open? false   ; dev-only day8 re-frame-10x panel visibility
        ;; keymap-set registry (Settings ▸ Key Bindings + the editor): built-ins + named custom sets
        :keymaps {:active "default" :order [] :sets {}}
        ;; the key-binding editor dialog (vinary.ui.keybindings-editor + vinary.input.kbedit-history)
        :kbedit {:open?   false        ; dialog visible?
                 :sel     nil          ; focused set id (for editing; not the active set)
                 :editing nil          ; set id whose name is being renamed in-place, or nil
                 :capture nil          ; {:set-id :mode :action :chords […]} during key capture, or nil
                 :ctx     nil          ; {:x :y :id} editor right-click context menu, or nil
                 :undo    []           ; command stack (vinary.input.kbedit-history)
                 :redo    []}
        :hints {:active? false :targets [] :typed ""}   ; Vimium-style link hints
        :find {:visible? false :query "" :count 0 :idx 0}
        ;; in-renderer PDF view-state (zoom scale / fit mode / dark-invert); fit + invert persist in settings.edn
        :pdf {:scale 1.0 :fit :width :invert? false}
        :window-zoom 1.0   ; app-renderer (DOM views) zoom factor, reported back from main
        :web-zoom 1.0      ; in-app web view's zoom factor, reported back from main
        ;; keybinding / modal / sequence state (ephemeral UI; the keymap itself lives in
        ;; vinary.input.keymap's atom, not here)
        ;; :mode starts :insert (matches the non-modal default keymap; the active keymap's
        ;; :initial-mode is applied at boot / on config — vim switches this to :normal)
        :input {:mode :insert :sequence [] :count nil :in-input? false :timeout-id nil}
        ;; URI-bar path auto-completion (children of the current dir + ghost/dropdown state)
        :uri-complete {:input nil :dir nil :entries [] :target nil :exists? false :dir? false
                       :selected -1 :dismissed? false :error? false}
        ;; extension runtime + ad-blocking (state pushed from main over vv:ext-state / vv:ext-config)
        :extensions-open? false
        :extensions {:enabled? true :installed [] :install-status nil :update-status nil}
        :adblock {:enabled? true :lists :ads-and-tracking :last-updated nil :status nil}
        ;; native password-manager bridge (main-owned CLIs; renderer stores only metadata/status)
        :passwords {:open? false :providers [] :forms {:count 0} :items [] :busy? false
                    :error nil :result nil :save-prompt nil}
        ;; command palette / fuzzy finder
        :palette {:open? false :source :command :prefix "" :query "" :items [] :selected 0}}})
