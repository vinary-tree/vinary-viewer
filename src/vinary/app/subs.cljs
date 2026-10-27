(ns vinary.app.subs
  "re-frame subscriptions — the Observer graph. UI slices read app-db; document slices read the DataScript
   content cache (listing [:ds/rev] so they recompute per transaction). The browser-tab model lives in
   app-db ([:ui :tabs]/[:ui :active-tab]); reads go through vinary.app.nav."
  (:require [re-frame.core :as rf]
            [vinary.app.ds :as ds]
            [vinary.app.facet :as facet]
            [vinary.app.commands :as commands]
            [vinary.app.commits :as commits]
            [vinary.app.nav :as nav]
            [vinary.app.overlay :as overlay]
            [vinary.app.palette :as palette]
            [vinary.app.preview-strip :as preview-strip]
            [vinary.app.tree-model :as tree-model]
            [vinary.app.uri :as uri]
            [vinary.search.config :as search-config]
            [vinary.app.zoom :as zoom]
            [vinary.app.slides :as slides]
            [vinary.app.slides-link :as slides-link]
            [vinary.slides.deck :as deck]
            [vinary.input.keymaps-registry :as registry]))

(rf/reg-sub :ds/rev         (fn [db _] (:ds/rev db)))
(rf/reg-sub :ui/theme       (fn [db _] (get-in db [:ui :theme])))
(rf/reg-sub :ui/tree-filter (fn [db _] (get-in db [:ui :tree-filter])))
(rf/reg-sub :ui/find           (fn [db _] (get-in db [:ui :find])))
(rf/reg-sub :pdf/view-state    (fn [db _] (get-in db [:ui :pdf])))
(rf/reg-sub :view/zoom-percent (fn [db _] (zoom/percent db)))   ; live zoom % for the active surface (zoom bar)
(rf/reg-sub :view/pdf-active?  (fn [db _] (= :pdf (zoom/context db))))  ; gates the PDF-only View-menu items
(rf/reg-sub :view/diff-active? (fn [db _] (facet/diff-preview-active? db)))  ; gates the diff-only View-menu item + the Preview-combo layout rows (ADR-0037)
(rf/reg-sub :diff/all-collapsed? (fn [db _] (facet/diff-all-collapsed? db))) ; flips the "Collapse All Files" ↔ "Expand All Files" menu label
(rf/reg-sub :ui/active-heading (fn [db _] (get-in db [:ui :active-heading])))
(rf/reg-sub :ui/sidebar-visible? (fn [db _] (get-in db [:ui :sidebar-visible?])))
(rf/reg-sub ::preview-strip-policy
            (fn [db _]
              (assoc (select-keys (:ui db) [:preview-strip-visible? :preview-strip-overrides])
                     :pdf {:reflow? (get-in db [:ui :pdf :reflow?])})))
(rf/reg-sub :ui/preview-strip-visible?
            :<- [::preview-strip-policy] :<- [:doc/active]
            (fn [[policy doc] _] (preview-strip/visible-for? policy doc)))
(rf/reg-sub :ui/preview-strip-available? :<- [:doc/active]
            (fn [doc _] (preview-strip/available-for? doc)))
(rf/reg-sub :web/minimap (fn [db _] (get-in db [:ui :web-minimap])))
(rf/reg-sub :ui/sidebar-width  (fn [db _] (get-in db [:ui :sidebar-width])))
(rf/reg-sub :ui/sidebar-tab    (fn [db _] (get-in db [:ui :sidebar-tab])))
(rf/reg-sub :ui/projects       (fn [db _] (get-in db [:ui :projects])))
(rf/reg-sub :ui/tree-selected  (fn [db _] (get-in db [:ui :tree-selected])))
(rf/reg-sub :ui/tree-open      (fn [db _] (get-in db [:ui :tree-open] #{})))
(rf/reg-sub :ui/tree-expanding (fn [db _] (get-in db [:ui :tree-expanding] #{})))
(rf/reg-sub :ui/tree-restoring? (fn [db _] (get-in db [:ui :tree-restoring?] false)))
(rf/reg-sub :ui/dir-selected   (fn [db _] (get-in db [:ui :dir-selected])))
(rf/reg-sub :ui/menu           (fn [db _] (get-in db [:ui :menu])))
(rf/reg-sub :ui/menu-submenu   (fn [db _] (get-in db [:ui :menu-submenu])))
(rf/reg-sub :ui/menu-focus     (fn [db _] (get-in db [:ui :menu-focus])))
(rf/reg-sub :ui/menu-submenu-focus (fn [db _] (get-in db [:ui :menu-submenu-focus])))
(rf/reg-sub :ui/access-keys-active? (fn [db _] (get-in db [:ui :access-keys-active?])))
(rf/reg-sub :ui/settings       (fn [db _] (get-in db [:ui :settings])))
(rf/reg-sub :ui/settings-open? (fn [db _] (get-in db [:ui :settings-open?])))
(rf/reg-sub :ui/about-open?    (fn [db _] (get-in db [:ui :about-open?])))
(rf/reg-sub :ui/app-info       (fn [db _] (get-in db [:ui :app-info])))
(rf/reg-sub :ui/context-menu   (fn [db _] (get-in db [:ui :context-menu])))
(rf/reg-sub :ui/hover-link     (fn [db _] (get-in db [:ui :hover-link])))
(rf/reg-sub :ui/ctrl-held?     (fn [db _] (get-in db [:ui :ctrl-held?])))
(rf/reg-sub :ui/tab-drop       (fn [db _] (get-in db [:ui :tab-drop])))
(rf/reg-sub :ui/recent         (fn [db _] (get-in db [:ui :recent])))
(rf/reg-sub :ui/recent-files   (fn [db _] (get-in db [:ui :recent :recent-files])))
(rf/reg-sub :ui/web-history    (fn [db _] (get-in db [:ui :recent :web-history])))
(rf/reg-sub :ui/overlay-open?
            ;; a DOM overlay is showing that the native web view would paint over (menus, the palette, every modal
            ;; dialog incl. the SSH prompt — vinary.app.overlay/paints-over-content?) — the native web view hides
            ;; while this is true. (PDFs now render in-renderer via pdf.js — ADR 0013 — so only the web view is native.)
            (fn [db _] (overlay/paints-over-content? db)))
(rf/reg-sub :ui/re-frame-10x-open? (fn [db _] (get-in db [:ui :re-frame-10x-open?])))
(rf/reg-sub :ui/hints          (fn [db _] (get-in db [:ui :hints])))

;; ---- keymap-set registry (Settings ▸ Key Bindings + the editor) ----
(rf/reg-sub :keymaps/active-id (fn [db _] (registry/active-id db)))
(rf/reg-sub :keymaps/set-rows
            (fn [db _]
              (let [active (registry/active-id db)]
                (mapv (fn [id] {:id id :name (registry/display-name db id) :selected? (= id active)})
                      (registry/set-ids db)))))
;; ---- key-binding editor ----
(rf/reg-sub :kbedit/open?    (fn [db _] (get-in db [:ui :kbedit :open?])))
(rf/reg-sub :kbedit/sel      (fn [db _] (get-in db [:ui :kbedit :sel])))
(rf/reg-sub :kbedit/editing  (fn [db _] (get-in db [:ui :kbedit :editing])))
(rf/reg-sub :kbedit/capture  (fn [db _] (get-in db [:ui :kbedit :capture])))
(rf/reg-sub :kbedit/ctx      (fn [db _] (get-in db [:ui :kbedit :ctx])))
(rf/reg-sub :kbedit/can-undo? (fn [db _] (boolean (seq (get-in db [:ui :kbedit :undo])))))
(rf/reg-sub :kbedit/can-redo? (fn [db _] (boolean (seq (get-in db [:ui :kbedit :redo])))))
(rf/reg-sub :kbedit/sets
            (fn [db _]
              (let [active (registry/active-id db) sel (get-in db [:ui :kbedit :sel])]
                (vec (concat
                      (mapv (fn [{:keys [id name]}]
                              {:id id :name name :builtin? true :custom? false
                               :active? (= id active) :focused? (= id sel)}) registry/builtins)
                      (mapv (fn [id]
                              {:id id :name id :builtin? false :custom? true
                               :active? (= id active) :focused? (= id sel)}) (registry/order db)))))))
(rf/reg-sub :kbedit/focused
            (fn [db _]
              (let [sel (get-in db [:ui :kbedit :sel])]
                {:id sel :name (registry/display-name db sel) :builtin? (registry/builtin? sel)
                 :custom? (registry/custom? db sel) :modal? (and sel (registry/modal? db sel))
                 :default-mode (and sel (registry/default-mode db sel))})))
(rf/reg-sub :kbedit/action-index
            (fn [db _] (when-let [sel (get-in db [:ui :kbedit :sel])] (registry/action-index db sel))))

(rf/reg-sub :input/mode        (fn [db _] (get-in db [:ui :input :mode])))
(rf/reg-sub :input/pending     (fn [db _] (get-in db [:ui :input :sequence])))
(rf/reg-sub :input/in-input?   (fn [db _] (get-in db [:ui :input :in-input?])))
;; Is the ACTIVE keymap set modal (Vim-like)? A modal set binds commands to bare printable characters, so
;; any UI that holds keyboard focus has to hand it back for those bindings to be reachable at all — the
;; find bar uses this to decide what Enter means (commit vs next match). See ADR-0032.
;;
;; LAYERED on the keymap slice on purpose: answering it runs merge-user, which deep-merges and postwalks
;; the whole keymap. As a plain db-reading sub that would recompute on every app-db change — thousands of
;; times while a document streams. Layered, it recomputes only when the keymap registry itself changes.
(rf/reg-sub ::keymaps-slice (fn [db _] (get-in db [:ui :keymaps])))
(rf/reg-sub :input/modal-keymap?
            :<- [::keymaps-slice]
            (fn [ks _] (registry/modal-in? ks (get ks :active "default"))))
;; The file tree, already narrowed and folded into its nested shape.
;;
;; LAYERED, for the same reason ::keymaps-slice above is: doing it in the view's render function re-folded
;; every path of every open project on every keystroke — thousands of str/splits and assoc-ins before
;; React had begun reconciling, which is what let a re-render land between two characters and take one
;; back out of the field (docs/scientific/10). Layered, it recomputes only when the projects or the filter
;; actually change.
(rf/reg-sub
 :tree/filtered
 :<- [:ui/projects] :<- [:ui/tree-filter] :<- [:ui/settings]
 (fn [[projects q settings] _]
   (tree-model/filtered projects q (search-config/mode-for :tree settings))))

(rf/reg-sub :palette/state     (fn [db _] (get-in db [:ui :palette])))

;; The command-resolution context, assembled from subs so the predicates stay pure.
;;
;; It reads :ui/find, which changes on every character typed into the find bar — but the MAP it produces
;; only changes when :visible? flips, so re-frame's equality check stops the recomputation right here and
;; :palette/candidates below is not disturbed.
(rf/reg-sub
 ::palette-ctx
 :<- [:ui/tabs] :<- [:ui/active-tab-id] :<- [:ui/active-path]
 :<- [:history/can-back?] :<- [:history/can-forward?] :<- [:ui/find]
 :<- [:view/pdf-active?] :<- [:slides/view?] :<- [:slides/presenting?] :<- [:slides/show-here?]
 :<- [:slides/swap-offered?] :<- [:ui/preview-strip-available?]
 (fn [[tabs active-tab active-path back? fwd? find pdf? slides? presenting? here? swap? preview?] _]
   {:tabs tabs :active-tab active-tab :active-path active-path
    :can-back? back? :can-forward? fwd?
    :find-visible? (:visible? find)
    :preview-strip-available? preview?
    ;; gate the PDF / Slides commands (ADR-0045); :slides-active? is deliberately absent — keyboard-only
    :pdf-view? pdf? :slides-view? slides? :presenting? presenting? :show-here? here? :swap-offered? swap?
    ;; the query box holds focus whenever the palette is open, so commands gated on the keyboard being
    ;; free must not be offered
    :in-input? true}))

;; The palette's rows. Layered, and short-circuited when the palette is closed, so the ~6 500-file :file
;; source costs nothing until it is actually on screen (ADR-0033).
(rf/reg-sub
 :palette/candidates
 :<- [:palette/state] :<- [:ui/projects] :<- [:ui/settings] :<- [::palette-ctx]
 (fn [[{:keys [open? source query]} projects settings ctx] _]
   (if-not open?
     []
     (palette/candidates source query projects (commands/all-visible ctx)
                         (search-config/mode-for :palette settings)))))
(rf/reg-sub :ui/uri-complete     (fn [db _] (get-in db [:ui :uri-complete])))
;; the monotonic count of active-tab changes (nav/set-active) — the address bar scopes a loaded tab's draft to it
(rf/reg-sub :ui/activation       (fn [db _] (nav/activation db)))
(rf/reg-sub :ui/extensions-open? (fn [db _] (get-in db [:ui :extensions-open?])))
(rf/reg-sub :ui/extensions       (fn [db _] (get-in db [:ui :extensions])))
(rf/reg-sub :ui/adblock          (fn [db _] (get-in db [:ui :adblock])))
(rf/reg-sub :ui/passwords        (fn [db _] (get-in db [:ui :passwords])))

;; ---- the browser-tab model (app-db) ----
(rf/reg-sub :ui/tabs          (fn [db _] (nav/tabs db)))
(rf/reg-sub :ui/active-tab-id (fn [db _] (nav/active-id db)))
(rf/reg-sub :ui/active-tab    (fn [db _] (nav/active-tab db)))
(rf/reg-sub :ui/active-uri    (fn [db _] (nav/active-uri db)))
(rf/reg-sub :ui/active-view-source? :<- [:facet/active] (fn [af _] (= :source (:type af))))
(rf/reg-sub :ui/active-path   (fn [db _] (nav/active-path db)))    ; the active uri iff it is a local file
(rf/reg-sub :history/can-back?    (fn [db _] (nav/can-back? db)))
(rf/reg-sub :history/can-forward? (fn [db _] (nav/can-forward? db)))

;; the tab strip reads the app-db tabs
(rf/reg-sub :tabs :<- [:ui/tabs] (fn [tabs _] tabs))

;; the active tab's effective view facet {:path :type} (its chosen collocated representation, or the default) and
;; the derived CONTENT path — the file the pane / Contents / find operate on. This differs from the tab's primary
;; uri when a sibling facet is shown in place. See vinary.app.facet.
;;
;; LAYERED, and for the same reason ::keymaps-slice is. As plain db subs, `:facet/active` and
;; `:view/switch` below each ran a DataScript `d/q` plus a 22-attribute `d/pull` on EVERY app-db write —
;; and both are permanently subscribed (the toolbar mounts `:view/switch` unconditionally; the find bar
;; derefs `:ui/find-context` on every render even while hidden). Every character typed anywhere in the app
;; therefore paid for four DataScript queries that could not possibly have changed their answer.
;;
;; ::facet-inputs is four cheap map lookups whose RESULT is what re-frame compares, so a keystroke stops
;; at this layer instead of reaching the content cache. `:doc/group` was already layered this way, and
;; supplies the one genuinely DataScript-dependent input both need.
(rf/reg-sub
 ::facet-inputs
 (fn [db _]
   {:facet   (nav/facet db)
    :primary (nav/active-path db)
    :mru     (nav/facet-mru db)
    :pref    (get-in db [:ui :settings :collocated-default] :pdf)}))

(rf/reg-sub
 :facet/active
 :<- [::facet-inputs] :<- [:doc/group]
 (fn [[{:keys [facet primary pref]} group] _]
   (facet/active-facet facet group primary pref)))
;; …falling back to the raw active-uri (not just the local file-path) so a doc transacted under an http(s) URL —
;; a PDF opened from a web-view link, whose bytes main downloaded under its URL — resolves and renders in pdf.js
;; (a normal http page has no doc entity, so :doc/active stays nil there and the web view still shows it).
(rf/reg-sub :ui/active-content-path :<- [:facet/active] :<- [:ui/active-path] :<- [:ui/active-uri]
            (fn [[af primary uri] _] (or (:path af) primary uri)))
(rf/reg-sub :facet/type :<- [:facet/active] (fn [af _] (:type af)))

;; "which document is on screen, in which view?" — the identity in-page find watches. When this changes, the
;; painted highlights and the match counter describe something the user is no longer looking at, so find
;; resets (:find/reset). One derived identity rather than a list of navigation events to keep in sync: a
;; missed event would silently leave a stale counter over the wrong document. Deliberately includes the
;; facet, so a preview↔source flip of the SAME file counts as a change (the path alone would not). ADR-0032.
(rf/reg-sub :ui/find-context :<- [:ui/active-content-path] :<- [:facet/type]
            (fn [[path facet] _] [path facet]))

;; the active document: the cached DataScript doc for the active tab's active CONTENT path (its facet, defaulting
;; to the primary uri). All :doc/* derivations below follow the shown facet automatically.
(rf/reg-sub
 :doc/active
 :<- [:ds/rev] :<- [:ui/active-content-path]
 (fn [[_rev path] _] (when path (ds/active-doc (ds/snapshot) path))))

;; the active document's kind ("markdown" / "org" / "latex" / "source" / "pdf" / …) — gates the "Go to preview" jump item
(rf/reg-sub :doc/kind :<- [:doc/active] (fn [doc _] (:doc/kind doc)))

;; the shown document's non-fatal live-refresh note — {:kind "render-failed" :message :stamp :at},
;; {:kind "refresh-failed" :message :seq :at} or {:kind "incomplete" :message :seq :at :partial?} — the last good
;; version stays on screen; ui/refresh-note shows this over it (ADR-0045 / ADR-0046)
(rf/reg-sub :doc/refresh-note :<- [:doc/active] (fn [doc _] (:doc/refresh-note doc)))

;; the shown PDF's slide-deck classification {:deck? :tier :source :pages :frame-count :second-screen} | nil
;; (ADR-0045)
(rf/reg-sub :doc/deck :<- [:doc/active] (fn [doc _] (:doc/deck doc)))

;; ---- slides layout (ADR-0045) — built on :doc/active + app-db slices, never a DataScript query per app-db write
(rf/reg-sub ::slides-inputs (fn [db _] (slides/layout-request db)))
;; what the pdf-view hands the engine besides zoom/fit/invert
(rf/reg-sub :pdf/layout-request :<- [::slides-inputs] (fn [inputs _] inputs))
;; is the shown PDF in the slides layout? (the same rule the engine applies to the doc's deck classification)
(rf/reg-sub :slides/view?
            :<- [:view/pdf-active?] :<- [::slides-inputs] :<- [:doc/deck]
            (fn [[pdf? inputs d] _]
              (boolean (and pdf? (= :slides (deck/effective-layout (assoc inputs :deck? (boolean (:deck? d)))))))))
;; the mounted slide show's position mirror ([:ui :slides]) or nil
(rf/reg-sub :slides/state (fn [db _] (get-in db [:ui :slides])))
;; the slide number being typed in the slides layout ("12"), or nil — the "Go to 12" chip
(rf/reg-sub :slides/digits (fn [db _] (get-in db [:ui :slides :digits])))
;; the running slide show ({:active? :tab-id :path :from :fullscreen?}) or nil — menus, palette (ADR-0045)
(rf/reg-sub :slides/presentation (fn [db _] (get-in db [:ui :presentation])))
(rf/reg-sub :slides/presenting? :<- [:slides/presentation] (fn [p _] (boolean (:active? p))))
;; is the running show shown here — its own view in front, its presenter not away (slides/show-here?, ADR-0048 §8.10)?
;; The show's keys act only then (the palette's :show-here?)
(rf/reg-sub :slides/show-here? (fn [db _] (slides/show-here? db)))
;; does this window hide its chrome for the show (.vv-root.vv-presenting)? every show but a linked show's presenter
;; window (slides/chromeless?, ADR-0048 §8.1)
(rf/reg-sub :slides/chromeless? (fn [db _] (slides/chromeless? db)))
;; the running show's blanked screen (:black / :white) or nil — the blank overlay
(rf/reg-sub :slides/blank (fn [db _] (get-in db [:ui :slides :blank])))
;; a linked show's link at [:ui :slides-link] (ADR-0048 §8; vinary.app.slides-link), or nil
(rf/reg-sub :slides/link (fn [db _] (get-in db [:ui :slides-link])))
;; the presenter pane's line about the link, {:tone :text} or nil (slides-link/notice)
(rf/reg-sub :slides/link-notice :<- [:slides/link] (fn [l _] (slides-link/notice l)))
;; is Swap screens offered in this window (a live link, its presenter, on separate displays)?
(rf/reg-sub :slides/swap-offered? :<- [:slides/link] (fn [l _] (slides-link/swap-offered? l)))
;; blanking in a presenter console shows a badge in its strip, not a blank screen (ADR-0048 §8.2): the colour, or nil
;; (slides/audience-blank)
;; Disabled 2026-10-19 (ADR-0048 §8.2, amended): the badge showed only while the link was live, over the slide of the
;; presenter view. The presenter console has no slide to cover, and a blank screen over it would hide the notes, so
;; the console shows the badge whatever the phase of its link.
;; (rf/reg-sub :slides/audience-blank
;;             :<- [:slides/blank] :<- [:slides/link] :<- [:slides/presenter-shown?]
;;             (fn [[b l shown?] _] (when (and b shown? (slides-link/live? l) (= :presenter (:role l))) b)))
(rf/reg-sub :slides/audience-blank (fn [db _] (slides/audience-blank db)))
;; the overview grid (ADR-0045 §Overview): open?, the mounted view's document generation (a live refresh bumps
;; it — the grid re-borrows the document), which page a frame's tile shows, and the dark-invert flag it mirrors
(rf/reg-sub :slides/overview?      (fn [db _] (boolean (get-in db [:ui :slides :overview?]))))
(rf/reg-sub :slides/doc-gen        (fn [db _] (get-in db [:ui :slides :doc-gen])))
(rf/reg-sub :slides/overview-page  (fn [db _] (slides/overview-page db)))
(rf/reg-sub :pdf/invert?           (fn [db _] (boolean (get-in db [:ui :pdf :invert?]))))
;; the presenter view (ADR-0048 §6): asked for in this window (`s`), and showing — asked for, in a slides view (the
;; .vv-presenter-view wrap and its pane); the speaker-notes hint chip (P5)
(rf/reg-sub ::presenter-view? (fn [db _] (slides/presenter-view? db)))
;; while a show runs, the presenter view shows only in the show's own view: a presenter away from its deck sees the
;; document in front as it is (slides/show-view?, ADR-0048 §8.10); outside a show, anywhere
(rf/reg-sub ::show-view-or-none? (fn [db _] (or (not (slides/presenting? db)) (slides/show-view? db))))
;; Disabled 2026-10-19 (ADR-0048 §8.10): the presenter view showed in any slides view; while a show runs it now
;; shows only in the show's own view (::show-view-or-none?, slides/presenter-shown?).
;; (rf/reg-sub :slides/presenter-shown? :<- [::presenter-view?] :<- [:slides/view?]
;;             (fn [[asked? slides?] _] (boolean (and asked? slides?))))
(rf/reg-sub :slides/presenter-shown? (fn [db _] (slides/presenter-shown? db)))
;; the presenter console: the presenter view in a linked show's presenter window — the notes over the whole content
;; area, and a small next slide (the .vv-presenter-console wrap; slides/console?, ADR-0048 §6.2)
(rf/reg-sub :slides/console? (fn [db _] (slides/console? db)))
(rf/reg-sub :slides/notes-hint? (fn [db _] (slides/notes-hint? db)))

;; the active document's commit-diff facts ({:root :from :to :range?}, ADR-0042) — nil unless the shown
;; doc is a git-produced diff. Kind-guarded so a stale attr on a re-typed doc can never leak through.
(rf/reg-sub :doc/git :<- [:doc/active]
            (fn [doc _] (when (= "diff" (:doc/kind doc)) (:doc/git doc))))

;; the active document's collocated GROUP [{:path :kind}…] (its :doc/siblings — authored sources + compiled PDF),
;; read off the PRIMARY (opened) doc so the toolbar stays stable regardless of the shown facet. nil for a
;; non-group doc (or one not yet loaded).
(rf/reg-sub :doc/group :<- [:ds/rev] :<- [:ui/active-path]
            (fn [[_rev primary] _] (facet/group-of (ds/snapshot) primary)))

;; the mirrored .vv-content clientWidth (CSS px; nil until the ResizeObserver's first report) and its
;; boolean projection through nav/split-wide? — the boolean dedups per-pixel resize churn before it
;; reaches content-view / view-switch (ADR-0043)
(rf/reg-sub :ui/content-width (fn [db _] (get-in db [:ui :content-width])))
(rf/reg-sub :ui/split-wide? :<- [:ui/content-width] (fn [w _] (nav/split-wide? w)))

;; the EFFECTIVE diff view of the active doc (:unified | :split): the tab's explicit choice, else the
;; width-adaptive default (ADR-0043). The combo caret's layout rows check-mark off this sub, so an
;; unchosen wide diff shows Split checked with :diff-view still nil — the checkmark reflects what is
;; actually shown.
(rf/reg-sub :ui/active-diff-view :<- [:ui/active-tab] :<- [:ui/split-wide?]
            (fn [[tab wide?] _] (nav/effective-diff-view (:diff-view tab) wide?)))

;; the active tab's collapsed diff-file ids (#{} = all expanded) — re-applied to the DOM by markdown-body
;; after every innerHTML rebuild and by the :diff/apply-collapsed fx on every state change (ADR-0037)
(rf/reg-sub :ui/active-diff-collapsed :<- [:ui/active-tab] (fn [tab _] (get tab :diff-collapsed #{})))

;; the complete render model for the [Preview ▾ | Source ▾] toolbar combo (both buttons' modes/options/active).
;; Layered on ::facet-inputs + :doc/group — see the note there for why it must not be a plain db sub.
(rf/reg-sub
 :view/switch
 :<- [::facet-inputs] :<- [:doc/group]
 (fn [[{:keys [facet primary pref mru]} group] _]
   (facet/view-model facet group primary pref mru)))

;; the persisted default representation for docs that have a collocated PDF (Settings toggle reads this)
(rf/reg-sub :ui/collocated-default :<- [:ui/settings] (fn [s _] (get s :collocated-default :pdf)))

(rf/reg-sub :ui/web-toc (fn [db _] (get-in db [:ui :web-toc])))
(rf/reg-sub :pdf/reflow? (fn [db _] (boolean (get-in db [:ui :pdf :reflow?]))))

;; the Contents/TOC outline of the active document: the web view's headings for an HTTP page; else, in the
;; SOURCE view, the tree-sitter source outline (:doc/source-toc — `L<line>` ids for CodeMirror nav) when present;
;; else the preview's rendered headings (:doc/toc — rehype slug ids for DOM nav). Selecting by active view keeps
;; the two id-spaces from clobbering each other, so each view's Contents both displays AND navigates correctly.
(defn active-toc
  "Select the Contents outline for the active view (pure — the :doc/toc sub is this over its inputs). HTTP page →
   the web view's headings; else source-active with a source outline present → the SOURCE outline (`L<line>` ids);
   else the PREVIEW outline (slug ids). The two outlines live in separate attrs so neither clobbers the other;
   this picks the one whose id-space matches the active view. Falling back to the preview outline (not blank) when
   the source outline has not arrived avoids an empty Contents flash on source-view mount."
  [http? source? web-toc preview-toc source-toc]
  (cond
    http?                          (vec (or web-toc []))
    (and source? (seq source-toc)) (vec source-toc)
    :else                          (vec (or preview-toc []))))

(rf/reg-sub
 :doc/toc
 :<- [:ui/active-uri] :<- [:doc/active] :<- [:ui/web-toc] :<- [:ui/active-view-source?]
 (fn [[active-uri doc web-toc source?] _]
   (active-toc (uri/http? active-uri) source? web-toc (:doc/toc doc) (:doc/source-toc doc))))

;; the active document streams (bounded-memory incremental render) rather than rendering whole
(rf/reg-sub :doc/streaming? :<- [:doc/active] (fn [doc _] (boolean (:doc/streaming? doc))))
;; stream completion in [0,1] (nil until the first batch), for the progress affordance
(rf/reg-sub :doc/stream-progress :<- [:doc/active] (fn [doc _] (:doc/stream-progress doc)))
;; a non-fatal note shown in the stream progress strip (e.g. a remote SSH connection dropped mid-stream)
(rf/reg-sub :doc/stream-note :<- [:doc/active] (fn [doc _] (:doc/stream-note doc)))

;; SSH: the pending auth-prompt request (non-secret) and the last connection error
(rf/reg-sub :ui/ssh-prompt (fn [db _] (get-in db [:ui :ssh-prompt])))
(rf/reg-sub :ui/ssh-error  (fn [db _] (get-in db [:ui :ssh-error])))

;; ---- the Commits surfaces (ADR-0039) ----
(rf/reg-sub :commits/state (fn [db _] (get-in db [:ui :commits])))

;; the repo the panel shows: pin > deepest git project containing the active doc > last shown > first
(rf/reg-sub
 :commits/panel-root
 :<- [:commits/state] :<- [:ui/projects] :<- [:ui/active-path]
 (fn [[{:keys [root last-root]} projects active] _]
   (commits/derive-root {:pinned root :last-root last-root} projects active)))

(rf/reg-sub
 :commits/for-root
 :<- [:commits/state]
 (fn [state [_ root]] (get-in state [:repos root])))

;; the repo-`root` commits whose diff IS the active document — the derived "open" row highlight
;; (ADR-0042; never stored). Layered on :doc/git for value-equality dedup: keystrokes and scrolls
;; never reach the row components, only an active-doc identity/kind/git change does.
(rf/reg-sub
 :commits/open-set
 :<- [:doc/git]
 (fn [git [_ root]] (commits/open-set git root)))

;; the git projects a repo switcher can offer (synthetic roots can never serve git data)
(rf/reg-sub
 :commits/repos
 :<- [:ui/projects]
 (fn [projects _] (commits/git-projects projects)))
