(ns vinary.app.commands
  "The command registry — the 'API' that keybindings and the command palette dispatch through. Each
   command is reified data {:id :title :category :dispatch|:handler :when :arg :prompt}; `run` resolves
   a command id against the current resolution context and dispatches the corresponding re-frame event.
   (Command pattern: invokers — keymap, palette, mouse — are decoupled from the actions.)"
  (:require [re-frame.core :as rf]
            [re-frame.db :as rfdb]
            [re-frame.events]
            [vinary.app.nav :as nav]
            [vinary.app.slides :as slides]
            [vinary.app.slides-link :as slides-link]))

;; predicate registry: ctx -> bool. The resolver assembles ctx from subs so predicates stay pure.
(def predicates
  {:always        (constantly true)
   :has-tabs      (fn [ctx] (pos? (count (:tabs ctx))))
   :can-back?     (fn [ctx] (boolean (:can-back? ctx)))
   :can-forward?  (fn [ctx] (boolean (:can-forward? ctx)))
   :find-visible? (fn [ctx] (boolean (:find-visible? ctx)))
   :palette-open? (fn [ctx] (boolean (:palette-open? ctx)))
   :not-in-input? (fn [ctx] (not (:in-input? ctx)))
   ;; ADR-0045: a PDF is shown / is shown in the slides layout (keymap AND palette contexts carry these) …
   :pdf-view?      (fn [ctx] (boolean (:pdf-view? ctx)))
   :slides-view?   (fn [ctx] (boolean (:slides-view? ctx)))
   ;; … and the slides key layer applies — set only by the keymap resolver, so a command gated on it is
   ;; keyboard-only and never offered by the palette
   :slides-active? (fn [ctx] (boolean (:slides-active? ctx)))
   ;; a slide show is running in this window
   :presenting?    (fn [ctx] (boolean (:presenting? ctx)))
   ;; … and is shown here: its own view in front, its presenter not away (slides/show-here?, ADR-0048 §8.10)
   :show-here?     (fn [ctx] (boolean (:show-here? ctx)))
   ;; this window presents a live linked show on separate displays: Swap screens is offered (ADR-0048 §8.7)
   :swap-offered?  (fn [ctx] (boolean (:swap-offered? ctx)))})

;; id -> command spec
(def registry
  {;; ---- Tabs ----
   :tab/next  {:id :tab/next  :title "Next tab"     :category "Tabs" :dispatch [:tab/next] :when :has-tabs}
   :tab/prev  {:id :tab/prev  :title "Previous tab" :category "Tabs" :dispatch [:tab/prev] :when :has-tabs}
   :tab/close {:id :tab/close :title "Close tab"    :category "Tabs" :dispatch [:tab/close-active] :when :has-tabs}
   :tab/new-blank {:id :tab/new-blank :title "New blank tab" :category "Tabs" :dispatch [:tab/new-blank]}
   ;; ---- File ----
   :file/open            {:id :file/open :title "Find file…" :category "File" :prompt :file}
   :file/open-dialog     {:id :file/open-dialog :title "Open file…" :category "File" :dispatch [:file/open-dialog]}
   :file/open-dialog-new-tab {:id :file/open-dialog-new-tab :title "Open file in new tab…" :category "File" :dispatch [:file/open-dialog :new-tab]}
   :file/open-in-new-tab {:id :file/open-in-new-tab :title "Open file in new tab…" :category "File" :prompt :file}
   :file/reveal-in-tree  {:id :file/reveal-in-tree :title "Reveal in tree" :category "File" :dispatch [:sidebar/reveal]}
   :doc/reload           {:id :doc/reload :title "Reload" :category "File" :dispatch [:tab/reload]}
   ;; ---- Navigation ----
   :history/back      {:id :history/back :title "Back" :category "Navigation" :dispatch [:history/back] :when :can-back?}
   :history/forward   {:id :history/forward :title "Forward" :category "Navigation" :dispatch [:history/forward] :when :can-forward?}
   :nav/scroll-down   {:id :nav/scroll-down :title "Scroll down" :category "Navigation" :dispatch [:nav/scroll] :arg {:dy 80}}
   :nav/scroll-up     {:id :nav/scroll-up :title "Scroll up" :category "Navigation" :dispatch [:nav/scroll] :arg {:dy -80}}
   :nav/scroll-left   {:id :nav/scroll-left :title "Scroll left" :category "Navigation" :dispatch [:nav/scroll] :arg {:dx :left}}
   :nav/scroll-right  {:id :nav/scroll-right :title "Scroll right" :category "Navigation" :dispatch [:nav/scroll] :arg {:dx :right}}
   :hint/start        {:id :hint/start :title "Link hints (Vimium f)" :category "Navigation" :dispatch [:hint/start]}
   :nav/page-down     {:id :nav/page-down :title "Page down" :category "Navigation" :dispatch [:nav/scroll] :arg {:dy :page}}
   :nav/page-up       {:id :nav/page-up :title "Page up" :category "Navigation" :dispatch [:nav/scroll] :arg {:dy :-page}}
   :nav/half-page-down {:id :nav/half-page-down :title "Half page down" :category "Navigation" :dispatch [:nav/scroll] :arg {:dy :half}}
   :nav/half-page-up  {:id :nav/half-page-up :title "Half page up" :category "Navigation" :dispatch [:nav/scroll] :arg {:dy :-half}}
   :nav/scroll-top    {:id :nav/scroll-top :title "Go to top" :category "Navigation" :dispatch [:nav/scroll] :arg {:to :top}}
   :nav/scroll-bottom {:id :nav/scroll-bottom :title "Go to bottom" :category "Navigation" :dispatch [:nav/scroll] :arg {:to :bottom}}
   :focus/uri     {:id :focus/uri :title "Focus URI bar" :category "Navigation" :dispatch [:nav/focus :uri]}
   :focus/sidebar {:id :focus/sidebar :title "Focus sidebar" :category "Navigation" :dispatch [:nav/focus :tree]}
   :focus/content {:id :focus/content :title "Focus content" :category "Navigation" :dispatch [:nav/focus :content]}
   :focus/toggle  {:id :focus/toggle :title "Toggle focus" :category "Navigation" :dispatch [:nav/focus :toggle]}
   :tree/down {:id :tree/down :title "Tree down" :category "Navigation" :dispatch [:tree/move 1]}
   :tree/up   {:id :tree/up :title "Tree up" :category "Navigation" :dispatch [:tree/move -1]}
   :tree/open {:id :tree/open :title "Open tree selection" :category "Navigation" :dispatch [:tree/activate]}
   :nav/parent      {:id :nav/parent :title "Parent directory" :category "Navigation" :dispatch [:nav/parent]}
   :nav/open-target {:id :nav/open-target :title "Open highlighted target" :category "Navigation" :dispatch [:nav/open-target]}
   ;; bidirectional source⇄preview jump (also on the preview/source right-click menus). Self-gating: each no-ops
   ;; unless it's the meaningful direction for a previewable (markdown/org) doc.
   :jump/to-source  {:id :jump/to-source :title "Go to source" :category "Navigation" :dispatch [:jump/goto-source]}
   :jump/to-preview {:id :jump/to-preview :title "Go to preview" :category "Navigation" :dispatch [:jump/goto-preview]}
   ;; ---- Search ----
   :search/start {:id :search/start :title "Find" :category "Search" :dispatch [:find/toggle]}
   :search/next  {:id :search/next :title "Find next" :category "Search" :dispatch [:find/cycle 1] :when :find-visible?}
   :search/prev  {:id :search/prev :title "Find previous" :category "Search" :dispatch [:find/cycle -1] :when :find-visible?}
   :search/close {:id :search/close :title "Close find" :category "Search" :dispatch [:find/close] :when :find-visible?}
   :palette/open  {:id :palette/open :title "Command palette" :category "Search" :dispatch [:palette/open {:source :command}]}
   :palette/files {:id :palette/files :title "Find file" :category "Search" :dispatch [:palette/open {:source :file}]}
   ;; ---- View ----
   :sidebar/toggle {:id :sidebar/toggle :title "Toggle sidebar" :category "View" :dispatch [:sidebar/toggle]}
   :view/preview-strip-toggle {:id :view/preview-strip-toggle :title "Show Preview Strip" :category "View"
                               :dispatch [:view/preview-strip-toggle]}
   ;; Preview↔Source view toggle — flips the shown file between its rendered preview and its source (facet-aware;
   ;; self-gates: a no-op when there is no other facet to switch to, e.g. a lone PDF).
   :view/toggle-source         {:id :view/toggle-source :title "Toggle preview / source" :category "View" :dispatch [:tab/toggle-source]}
   :view/toggle-diff-split     {:id :view/toggle-diff-split :title "Toggle unified / split diff" :category "View" :dispatch [:tab/toggle-diff-view]}
   ;; diff per-file collapse (ADR-0037) — self-gate on the shown diff preview, like the toggles above;
   ;; bound to the vim fold idiom `z M` / `z R` (vim.edn) and always reachable from the palette
   :view/diff-collapse-all     {:id :view/diff-collapse-all :title "Collapse all diff files" :category "View" :dispatch [:diff/collapse-all]}
   :view/diff-expand-all       {:id :view/diff-expand-all :title "Expand all diff files" :category "View" :dispatch [:diff/expand-all]}
   :theme/cycle    {:id :theme/cycle :title "Cycle theme" :category "View" :dispatch [:theme/cycle]}
   :theme/pick     {:id :theme/pick :title "Choose theme…" :category "View" :prompt :theme}
   :view/zoom-in    {:id :view/zoom-in :title "Zoom in" :category "View" :dispatch [:view/zoom 1]}
   :view/zoom-out   {:id :view/zoom-out :title "Zoom out" :category "View" :dispatch [:view/zoom -1]}
   :view/zoom-reset {:id :view/zoom-reset :title "Reset zoom" :category "View" :dispatch [:view/zoom 0]}
   :view/devtools   {:id :view/devtools :title "Developer tools" :category "View" :dispatch [:view/devtools]}
   :git/blame-toggle {:id :git/blame-toggle :title "Toggle git blame" :category "Git" :dispatch [:blame/toggle]}
   :git/open-graph {:id :git/open-graph :title "Open commit graph" :category "Git" :dispatch [:git-graph/open]}
   :git/file-history {:id :git/file-history :title "File history" :category "Git" :dispatch [:git/file-history {}]}
   :git/line-history {:id :git/line-history :title "Line range history" :category "Git" :dispatch [:git/line-history-from-selection]}
   :view/re-frame-10x {:id :view/re-frame-10x :title "re-frame-10x" :category "View" :dispatch [:view/re-frame-10x]}
   ;; the shown PDF's layout: one slide per screen ↔ the continuous document (ADR-0045; any PDF, deck or not)
   :view/toggle-slides {:id :view/toggle-slides :title "View as slide show / document" :category "View" :dispatch [:pdf/layout-toggle] :when :pdf-view?}
   ;; ---- Slides (ADR-0045) ----
   ;; Navigation of a PDF shown in the slides layout. Gated on :slides-view?, so the palette offers them only then.
   ;; The SAME ops reach the slide show from every keymap's scroll keys too (arrows, PgUp/PgDn, Home/End, Vim
   ;; j/k/gg/G, Emacs C-v/M-v) — through the scroll delegate, not these entries. A "frame" is one Beamer frame:
   ;; all the overlay pages of one slide.
   :slides/next       {:id :slides/next :title "Next slide" :category "Slides" :dispatch [:slides/command :next] :when :slides-view?}
   :slides/prev       {:id :slides/prev :title "Previous slide" :category "Slides" :dispatch [:slides/command :prev] :when :slides-view?}
   :slides/next-frame {:id :slides/next-frame :title "Next frame (skip overlays)" :category "Slides" :dispatch [:slides/command :next-frame] :when :slides-view?}
   :slides/prev-frame {:id :slides/prev-frame :title "Previous frame (fully built)" :category "Slides" :dispatch [:slides/command :prev-frame] :when :slides-view?}
   :slides/first      {:id :slides/first :title "First slide" :category "Slides" :dispatch [:slides/command :first] :when :slides-view?}
   :slides/last       {:id :slides/last :title "Last slide" :category "Slides" :dispatch [:slides/command :last] :when :slides-view?}
   :slides/back       {:id :slides/back :title "Back to the slide before the last jump" :category "Slides" :dispatch [:slides/command :back] :when :slides-view?}
   :slides/forward    {:id :slides/forward :title "Forward again after Back (slides)" :category "Slides" :dispatch [:slides/command :forward] :when :slides-view?}
   ;; type a slide number (Beamer's frame number — the number on the slide — else the page number) then Enter;
   ;; keyboard-only (:slides-active?), and bound per digit with {:id :slides/digit :args [d]}
   :slides/digit      {:id :slides/digit :title "Type a slide number" :category "Slides" :dispatch [:slides/digit] :when :slides-active?}
   :slides/enter      {:id :slides/enter :title "Go to the typed slide (else next)" :category "Slides" :dispatch [:slides/enter] :when :slides-active?}
   :slides/backspace  {:id :slides/backspace :title "Delete a typed digit (else previous)" :category "Slides" :dispatch [:slides/backspace] :when :slides-active?}
   ;; the fullscreen slide show of the shown PDF (deck or not): F5 / Shift+F5; Escape ends it
   :slides/present         {:id :slides/present :title "Start slide show" :category "Slides" :dispatch [:slides/present {:from :first}] :when :pdf-view?}
   :slides/present-current {:id :slides/present-current :title "Start slide show from the current slide" :category "Slides" :dispatch [:slides/present {:from :current}] :when :pdf-view?}
   :slides/stop            {:id :slides/stop :title "End slide show" :category "Slides" :dispatch [:slides/stop] :when :presenting?}
   :slides/black           {:id :slides/black :title "Black screen (toggle)" :category "Slides" :dispatch [:slides/black] :when :show-here?}
   :slides/white           {:id :slides/white :title "White screen (toggle)" :category "Slides" :dispatch [:slides/white] :when :show-here?}
   ;; the overview grid of frames (Tab / o): see the whole deck and jump anywhere; Go to slide… opens it
   :slides/overview        {:id :slides/overview :title "Slide overview" :category "Slides" :dispatch [:slides/overview] :when :slides-view?}
   :slides/goto            {:id :slides/goto :title "Go to slide…" :category "Slides" :dispatch [:slides/overview {:open? true}] :when :slides-view?}
   ;; during a show, this window's fullscreen on / off (ADR-0048 §7, `F`)
   :slides/fullscreen      {:id :slides/fullscreen :title "Fullscreen (this window)" :category "Slides" :dispatch [:slides/fullscreen-toggle] :when :show-here?}
   ;; the presenter view (ADR-0048 §6, `s`): the current slide beside the speaker notes, the next slide and the counter
   :slides/presenter-view  {:id :slides/presenter-view :title "Speaker notes (presenter view)" :category "Slides" :dispatch [:slides/presenter-view] :when :slides-view?}
   ;; two linked windows, either drives the show (ADR-0048 §8, `S`): the audience window opens; the notes stay here
   :slides/present-linked  {:id :slides/present-linked :title "Present in New Window" :category "Slides" :dispatch [:slides/present-linked] :when :pdf-view? :tooltip "Speaker notes stay in this window"}
   :slides/swap-screens    {:id :slides/swap-screens :title "Swap the presenter and audience screens" :category "Slides" :dispatch [:slides/swap-screens] :when :swap-offered?}
   ;; ---- Settings / Help ----
   :settings/open  {:id :settings/open :title "Preferences…" :category "Settings" :dispatch [:settings/open]}
   :passwords/open {:id :passwords/open :title "Passwords" :category "Web" :dispatch [:passwords/open]}
   :about/open     {:id :about/open :title "About vinary-viewer" :category "Help" :dispatch [:about/open]}
   ;; ---- Editor (key-binding editor; active only while the editor is open) ----
   :kbedit/undo {:id :kbedit/undo :title "Undo (key-binding editor)" :category "Editor" :dispatch [:kbedit/undo]}
   :kbedit/redo {:id :kbedit/redo :title "Redo (key-binding editor)" :category "Editor" :dispatch [:kbedit/redo]}
   ;; ---- Mode (vim) ----
   :mode/normal  {:id :mode/normal :title "Normal mode" :category "Mode" :dispatch [:input/set-mode :normal]}
   :mode/insert  {:id :mode/insert :title "Insert mode" :category "Mode" :dispatch [:input/set-mode :insert]}
   :mode/visual  {:id :mode/visual :title "Visual mode" :category "Mode" :dispatch [:input/set-mode :visual]}
   :mode/ex      {:id :mode/ex :title "Command line" :category "Mode" :dispatch [:palette/open {:source :command :prefix ":"}]}
   ;; the keyboard's Escape says whether the keyboard was on the document (the resolver's :on-document?): in the chrome
   ;; of a linked show's presenter window it leaves the show alone (vinary.input.events/escape-fx). The palette's
   ;; context carries no :on-document?, so its Escape counts as one on the document
   :input/escape {:id :input/escape :title "Escape / cancel" :category "Mode"
                  :handler (fn [ctx] [:input/escape (select-keys ctx [:on-document?])])}})

(defn allowed?
  "Does command id's :when predicate pass for ctx? (no :when ⇒ always)."
  [id ctx]
  (let [spec (get registry id)
        pred (get predicates (:when spec) (:always predicates))]
    (boolean (pred ctx))))

;; ---- keys run view moves at once ---------------------------------------------------------------------------
;; After a discrete input event Chromium renders the next frame BEFORE ordinary posted tasks, and re-frame's queue
;; drains in such a task (goog.async.nextTick). So a key handled through rf/dispatch makes its change just after a
;; frame, and the change shows a whole frame later. Measured on slide flips: a task posted at the key ran after the
;; next frame began in 11 of 11 flips (docs/scientific/12 §Flip latency, H-sched). A key's view move therefore runs at
;; once, inside the keydown handler.

(def immediate-commands
  "Commands a KEY runs at once (`dispatch-now!`) instead of through re-frame's queue: the ones that only move the
   view — scrolling, slide steps, the typed slide number and its Enter / Backspace (all three immediate, so their
   order is kept), and the blank screens. Running them ahead of events already queued changes nothing those events
   depend on. The palette runs everything through the queue (it is not a key handler)."
  #{:nav/scroll-down :nav/scroll-up :nav/scroll-left :nav/scroll-right :nav/page-down :nav/page-up
    :nav/half-page-down :nav/half-page-up :nav/scroll-top :nav/scroll-bottom
    :slides/next :slides/prev :slides/next-frame :slides/prev-frame :slides/first :slides/last
    :slides/back :slides/forward :slides/digit :slides/enter :slides/backspace :slides/black :slides/white})

(defn dispatch-now!
  "Process event `ev` at once — rf/dispatch-sync — so a KEY's change lands before the next frame (see above). Only
   from a DOM event handler: re-frame refuses dispatch-sync while it is handling an event (it logs an error and drops
   the event), so inside one this falls back to rf/dispatch."
  [ev]
  (if re-frame.events/*handling*
    (rf/dispatch ev)
    (rf/dispatch-sync ev)))

(defn run
  "Resolve command id against ctx and dispatch. Returns true if it consumed the key (dispatched or the
   gate passed), false if the :when gate failed (so the key can pass through). With `{:now? true}` — the keymap
   resolver, a keydown handler — an `immediate-commands` member runs at once (`dispatch-now!`)."
  ([id ctx] (run id ctx nil nil))
  ([id ctx args] (run id ctx args nil))
  ([id ctx args {:keys [now?]}]
   (let [spec (get registry id)
         send (if (and now? (contains? immediate-commands id)) dispatch-now! rf/dispatch)]
     (cond
       (nil? spec)             false
       (not (allowed? id ctx)) false
       (:handler spec)         (do (when-let [ev ((:handler spec) ctx)] (send ev)) true)
       (:dispatch spec)        (let [ev (cond
                                          (seq args)            (into (:dispatch spec) args)
                                          (contains? spec :arg) (conj (:dispatch spec) (:arg spec))
                                          :else                 (:dispatch spec))]
                                 (send ev) true)
       (:prompt spec)          (do (rf/dispatch [:palette/open {:source (:prompt spec)}]) true)
       :else                   false))))

(defn all-visible
  "All command specs whose :when passes for ctx (populates the command palette)."
  [ctx]
  (->> (vals registry) (filter #(allowed? (:id %) ctx)) (sort-by :title)))

(defn palette-ctx
  "Resolution context for RUNNING a palette selection.

   Reads app-db directly because it is called from a click/Enter handler, which is outside any reactive
   context — subscribing there would warn and would not track. The palette's candidate LIST is built from
   subscriptions instead (`:palette/candidates`), which is the reactive half of the same question.

   `:in-input?` is forced true so a palette selection can never resolve to a command gated on the keyboard
   being free: the query box holds focus whenever this is called."
  []
  (let [db @rfdb/app-db]
    (assoc (nav/base-ctx db)
           :find-visible? (get-in db [:ui :find :visible?])
           :pdf-view?     (slides/pdf-shown? db)
           :slides-view?  (slides/slides-view? db)
           :presenting?   (slides/presenting? db)
           :show-here?    (slides/show-here? db)
           :swap-offered? (slides-link/swap-offered? (get-in db [:ui :slides-link]))
           :in-input?     true)))
