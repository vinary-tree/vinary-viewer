(ns vinary.app.slides
  "Pure app-db helpers for the slide-deck mode (ADR-0045), shared by events, subscriptions and the menus: the
   :slides-open-as preference, the active document view's explicit layout, whether a slide show is presenting,
   and the EFFECTIVE layout. The effective layout is computed by the very rule the PDF engine applies
   (vinary.slides.deck/effective-layout), fed the document's :doc/deck summary — so a menu label, the toolbar and
   the engine can never disagree about which layout is showing."
  (:require [vinary.app.nav :as nav]
            [vinary.app.ds :as ds]
            [vinary.app.facet :as facet]
            [vinary.app.zoom :as zoom]
            [vinary.slides.deck :as deck]))

(defn open-as
  "The :slides-open-as preference — what a DETECTED deck opens as: :slides (the default) or :document."
  [db]
  (if (= :document (get-in db [:ui :settings :slides-open-as])) :document :slides))

(defn explicit-layout
  "The active document view's explicit PDF layout (:slides / :document), or nil (follow detection)."
  [db]
  (nav/pdf-layout db))

(defn presenting?
  "Is a slide show running in this window — a one-window show, or either side of a linked one? Whether the window
   hides its chrome for it is chromeless?."
  [db]
  (boolean (get-in db [:ui :presentation :active?])))

(defn chromeless?
  "Does this window hide its chrome for a running slide show (.vv-root.vv-presenting)? Every show does but a linked
   show's presenter window (ADR-0048 §8.1): a one-window show and a linked show's audience window show only the slide,
   while the presenter window — the show runs in the other window — keeps its menus, tabs, sidebar and bars, from
   Shift+S until the show ends."
  [db]
  (and (presenting? db) (not= :presenter (get-in db [:ui :slides-link :role]))))

(defn away?
  "Is this window a linked show's presenter AWAY from its deck (ADR-0048 §8.10, theory/11 §3.5): its deck view
   unmounted while the show goes on in the audience window, until the deck shows in the show's view again?"
  [db]
  (true? (get-in db [:ui :slides-link :away?])))

(defn pdf-shown?
  "Is the active tab showing a PDF (directly, or as the PDF facet of a collocated group)?"
  [db]
  (= :pdf (zoom/context db)))

(defn shown-deck
  "The :doc/deck summary of the shown PDF, or nil."
  [db]
  (when-let [p (facet/active-content-path db)]
    (ds/doc-attr (ds/snapshot) p :doc/deck)))

(defn presentation
  "The running slide show {:active? :tab-id :path :from :fullscreen?}, or nil. :from is where it starts (:first,
   :current or a page index): the engine applies it once, as it starts presenting, and a linked show's push shown
   before then moves it (engine-presenting?); :fullscreen? whether it asked main for fullscreen."
  [db]
  (get-in db [:ui :presentation]))

(defn present-from
  "Where the running slide show asked to start (:first / :current / a page index), or nil."
  [db]
  (get-in db [:ui :presentation :from]))

(defn show-view?
  "Does the view in front show the running slide show's deck — the show's own tab, showing the show's document
   (ADR-0048 §8.10)? The same deck in another tab is not the show's view: it never presents. A linked show's presenter
   that leaves its deck keeps the show running in the audience window, and nothing of the show acts on the view in
   front until the deck is back."
  [db]
  (let [{:keys [active? tab-id path]} (presentation db)]
    (boolean (and active? (= tab-id (nav/active-id db)) (= path (facet/active-content-path db))))))

(defn show-here?
  "Is the running slide show shown HERE: in the view in front (show-view?), its presenter not away (away?)? Only
   then does the engine present (layout-request :presenting?), and only then do the show's own keys act — F5 again,
   Shift+S, b . w , and F (ADR-0048 §8.10)."
  [db]
  (and (show-view? db) (not (away? db))))

(defn fullscreen-preferred?
  "The :slides-fullscreen? preference — does a slide show make the window fullscreen? (default yes; settings
   turn an unset checkbox into false, so only an explicit false opts out)"
  [db]
  (not= false (get-in db [:ui :settings :slides-fullscreen?])))

(defn click-advance?
  "The :slides-click-advance? preference — does a click on the slide advance a running show? (default yes)"
  [db]
  (not= false (get-in db [:ui :settings :slides-click-advance?])))

(defn transition
  "The :slides-transition preference — how a slide flip animates: :none (the default), :fade or :push."
  [db]
  (let [t (get-in db [:ui :settings :slides-transition])]
    (if (#{:fade :push} t) t :none)))

(defn notes-position
  "The :slides-notes preference — where a second-screen deck's NOTES sit, in the words of Beamer's `show notes on
   second screen=<value>` (vinary.slides.deck/notes-positions): :auto (the default), :right, :left, :bottom, :top or
   :off. The slides layout shows the other half — the slide."
  [db]
  (let [v (get-in db [:ui :settings :slides-notes])]
    (if (contains? deck/notes-positions v) v :auto)))

(defn presenter-view?
  "Whether this window allows joined slide notes. Outside a show nil means automatic; false is the session's
   explicit slide-only choice. A show starts slide-only and uses true only after an explicit request."
  [db]
  (let [v (get-in db [:ui :presenter-view?])]
    (if (presenting? db) (true? v) (not= false v))))

(defn joined-view?
  "The current deck step has notes and this window is showing them as a joined page."
  [db]
  (boolean (and (presenter-view? db)
                (not= :presenter (get-in db [:ui :slides-link :role]))
                (= (facet/active-content-path db) (get-in db [:ui :slides :path]))
                (get-in db [:ui :slides :current-notes?]))))

(defn layout-request
  "What the pdf-view hands the engine besides zoom/fit/invert: {:explicit :open-as :presenting? :present-from
   :transition :notes :presenter-view?}. The engine combines it with the document's own deck classification, applies
   :present-from when :presenting? turns on, animates command-driven flips per :transition — except while the
   presenter view is asked for, when every flip is instant (ADR-0048 §6.3) — and shows a second-screen deck's slide
   half per :notes."
  [db]
  (let [joined? (joined-view? db)]
   {:explicit        (explicit-layout db)
   :open-as         (open-as db)
   ;; the engine presents only in the show's own view, its presenter not away (show-here?): a presenter that left its
   ;; deck must not present the document in front, nor another tab's copy of the deck (ADR-0048 §8.10)
   :presenting?     (show-here? db)
   :present-from    (present-from db)
   :transition      (transition db)
   :notes           (notes-position db)
   :presenter-view? (boolean (or joined?
                                 (and (presenter-view? db)
                                      (= :presenter (get-in db [:ui :slides-link :role])))))
   :joined?         joined?}))

(defn effective-layout
  "The layout the shown PDF uses: :slides or :document (vinary.slides.deck/effective-layout)."
  ([db] (effective-layout db (shown-deck db)))
  ([db deck-summary]
   (deck/effective-layout (assoc (layout-request db) :deck? (boolean (:deck? deck-summary))))))

(defn slides-view?
  "Is a PDF showing in the slides layout?"
  [db]
  (and (pdf-shown? db) (= :slides (effective-layout db))))

;; ---- the [:ui :slides] mirror ------------------------------------------------------------------------------
(def ui-keys
  "Keys of [:ui :slides] that the UI owns, not the engine's position report: the typed slide number (:digits), a
   blanked screen (:blank — :black or :white, while presenting) and the open overview grid (:overview?). They
   survive the reports of the same deck in the slides layout (merge-report)."
  [:digits :blank :overview?])

(defn merge-report
  "The new [:ui :slides] for the engine's position report `m` over the current `cur`, while a show of `show-path`
   runs (nil: none): the report, plus `cur`'s UI-owned keys when it is the same document still in the slides layout —
   a different document, or the document layout, starts without them — and, for a report of the show's deck, the
   show's blank colour in any case.

   The blank colour is the show's, not the mirror's: blanking needs a show, and a show starts and ends unblanked, so
   while one runs the mirror's :blank is that show's. A linked show can set it before the engine's first report of the
   deck — an audience window joining a blanked show has no mirror yet, and its first reports may be of the document
   layout (a PDF that is no deck, before its engine presents) — and that report must not drop it: the engine's first
   presenting report would then send the page unblanked, and unblank both windows (R-blank, theory/11 §7)."
  ([cur m] (merge-report cur m nil))
  ([cur m show-path]
   (cond
     (and (= (:path cur) (:path m)) (= :slides (:layout m)))     (merge m (select-keys cur ui-keys))
     (and (some? show-path) (= show-path (:path m)) (:blank cur)) (assoc m :blank (:blank cur))
     :else                                                        m)))

(defn engine-presenting?
  "Has the engine applied the running show's start — is its last position report a presenting one of the show's
   deck? Until it has, [:ui :presentation :from] is where the engine will open the show: it applies :from once, as
   it starts presenting (renderer.pdf/apply-present-from!)."
  [db]
  (let [m (get-in db [:ui :slides])]
    (boolean (and (true? (:presenting? m)) (some? (:path m)) (= (:path m) (:path (presentation db)))))))

(defn digits
  "The slide number being typed (a non-empty string of digits), or nil."
  [db]
  (not-empty (get-in db [:ui :slides :digits])))

(defn clear-digits
  "db without a typed slide number."
  [db]
  (if (contains? (get-in db [:ui :slides]) :digits)
    (update-in db [:ui :slides] dissoc :digits)
    db))

(defn blank
  "The running show's blanked screen — :black or :white — or nil."
  [db]
  (get-in db [:ui :slides :blank]))

(defn command-needs-db?
  "Would :slides/command change app-db before (or instead of) moving the slide — a blanked screen to end, a typed
   slide number to forget (vinary.app.events/slides-command-fx)? When not, the step is exactly the engine call, and
   the slide engine runs it at once instead of through the event (renderer.pdf/step!)."
  [db]
  (boolean (or (blank db) (digits db))))

(defn clear-blank
  "db without a blanked screen."
  [db]
  (if (contains? (get-in db [:ui :slides]) :blank)
    (update-in db [:ui :slides] dissoc :blank)
    db))

(defn overview?
  "Is the slide overview grid open?"
  [db]
  (boolean (get-in db [:ui :slides :overview?])))

(defn overview-page
  "The :slides-overview-page preference — which page of a frame its overview tile shows and opens: :last (the
   default — the slide fully built, pdfpc's convention) or :first."
  [db]
  (if (= :first (get-in db [:ui :settings :slides-overview-page])) :first :last))

;; ---- the presenter view (ADR-0048 §6) ------------------------------------------------------------------------

(defn presenter-shown?
  "Is a notes pane or linked presenter console showing for the current PDF slide? During a show this only applies
   to the show's own view; a presenter away from its deck sees the document in front as it is (ADR-0048 §8.10)."
  [db]
  (and (presenter-view? db) (slides-view? db) (or (not (presenting? db)) (show-view? db))
       (or (= :presenter (get-in db [:ui :slides-link :role]))
           (and (= (facet/active-content-path db) (get-in db [:ui :slides :path]))
                (true? (get-in db [:ui :slides :current-notes?]))))))

(defn console?
  "Does this window show the linked presenter's console? The pane covers the slides view with a left strip of slide
   previews and notes on the right while the link opens or is live. An audience-window loss ends the show and link."
  [db]
  (boolean (and (presenter-shown? db) (= :presenter (get-in db [:ui :slides-link :role])))))

(defn audience-blank
  "The blank colour (:black / :white) a presenter console shows as the audience's, or nil: a blanked show in this
   window's console (console?). The console never goes blank itself — the audience window does — so the notes stay in
   view, and its strip says what the audience sees (ADR-0048 §8.2): the audience window's blank screen while live,
   or the blank it will open with while the link opens."
  [db]
  (when (console? db) (blank db)))

(defn notes-hinted?
  "Has the speaker-notes hint been shown on this installation (the :slides-notes-hinted? setting, ADR-0048 P5)?"
  [db]
  (true? (get-in db [:ui :settings :slides-notes-hinted?])))

(defn notes-hint?
  "Is the speaker-notes hint showing in this window's show?"
  [db]
  (boolean (get-in db [:ui :presentation :hint?])))

(defn clear-notes-hint
  "db without the speaker-notes hint showing."
  [db]
  (if (contains? (get-in db [:ui :presentation]) :hint?)
    (update-in db [:ui :presentation] dissoc :hint?)
    db))

(defn deck-has-notes?
  "Does the shown deck have speaker notes, as far as this window knows (ADR-0048 §6.1, the hint's test): its notes
   model's — split halves, note pages, a sidecar with notes, pdfpc metadata (:doc/deck's :notes :has-notes?) — or a
   text note on a page the engine has rendered (the position report's :text-notes?)."
  [db]
  (boolean (or (get-in (shown-deck db) [:notes :has-notes?])
               (get-in db [:ui :slides :text-notes?]))))

(defn notes-hint-due?
  "Should the speaker-notes hint show now (ADR-0048 P5)? A show runs in this window in the audience view, the deck has
   notes (deck-has-notes?), and the hint is neither showing nor already shown on this installation — and this is not a
   linked show's audience window, whose screen is the projector's (ADR-0048 §6.1: never shown there). Re-asked
   whenever one of those can change: the show starts, the deck's notes settle, a rendered page turns out to carry a
   text note — so the audience window's start, which asks for no hint, is not enough: a text note on its first
   rendered page asked again."
  [db]
  (and (presenting? db)
       (not (presenter-view? db))
       (not= :audience (get-in db [:ui :slides-link :role]))
       (not (notes-hint? db))
       (not (notes-hinted? db))
       (deck-has-notes? db)))
