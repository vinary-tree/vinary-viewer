(ns vinary.ui.slides-presenter
  "The presenter view's pane (ADR-0048 §6, decisions P1–P3). `s` (Slides: Speaker notes) splits the content area: the
   slides view keeps the left 58 %, untouched and never remounted (remounting would end a show), and this pane takes
   the right 42 %, showing

     · a status strip: \"Slide 7 / 12 · overlay 2 / 3\" — frames counted up to pdfpc's end slide
       (vinary.slides.presenter/counter and overlay-counter; the timer and the pace are deferred, §6.5) — and, in a
       linked show, its line: the link's notice and Swap screens (§8.6, §8.7);
     · the notes of the current page: its notes REGIONS — a split deck's notes half, an interleaved deck's note pages —
       each in a box of its own (vinary.renderer.pdf-region) with a text layer over it, so they stay selectable and a
       screen reader reads them; then its TEXT notes — the annotation note, then a sidecar note that differs
       (vinary.slides.notes/page-notes) — as sanitized Markdown when the deck carries pdfpc metadata and Markdown is not
       disabled (vinary.renderer.notes-html), else as plain text in the note's own direction; or, without any, \"No
       notes on this slide\" — \"This deck has no speaker notes\" when no page has any;
     · the next slide: what the next step shows (the next shown page's audience rectangle), or \"End of the slide
       show\".

   It borrows the mounted slides view's PDFDocumentProxy through pdf-cache/slides-snapshot, as the overview does (never
   a second proxy), and reads the snapshot again whenever the slides view reports a move, a new document generation or a
   re-derived deck. The text notes of the current page and of its neighbours are read ahead into a per-document cache,
   so a flip finds its notes already read.

   In a linked show's presenter window the pane is the presenter CONSOLE (slides/console?, ADR-0048 §6.2, amended
   2026-10-19): the audience window shows the slide, so the pane covers the whole content area — the strip on one line
   (the counter, the overlay, the audience's blank, the link's line), the notes filling the rest, and the next slide
   small in a narrow column on the right, at its own aspect ratio. The slides view stays mounted, full size, beneath it.

   An <aside role=\"complementary\"> in a slot of .vv-content-wrap after .vv-content (z 5), never focusable: a click on
   it leaves the keyboard with the document, so the slide keys keep working, and find, the Contents scroll-spy and the
   content MutationObserver never see it. Canvases are aria-hidden; the next slide is role=\"img\" with its label. A
   link in a note opens outside the app (http, https and mailto only), and gives the focus back."
  (:require [reagent.core :as r]
            [re-frame.core :as rf]
            [vinary.renderer.notes-html :as notes-html]
            [vinary.renderer.pdf-cache :as pdf-cache]
            [vinary.renderer.pdf-region :as region]
            [vinary.slides.deck :as deck]
            [vinary.slides.evidence :as evidence]
            [vinary.slides.notes :as notes]
            [vinary.slides.presenter :as presenter]))

;; ---- one region in a box ----------------------------------------------------------------------------------------

(defn- region-box
  "A box showing one region of a page (vinary.renderer.pdf-region): `request` {:doc :page :rect :text? :text-layer},
   shown again when it changes and rendered again, forced, when `gen` — the slides view's document generation — does
   (a live refresh replaced the document in place). `attrs` are the box's own attributes; `ratio` the region's
   width / height, which sizes the box before anything is drawn in it."
  [_props]
  (let [node     (atom nil)
        ctrl     (atom nil)
        seen-gen (atom nil)]
    (r/create-class
     {:display-name "vv-presenter-region"
      :component-did-mount
      (fn [this]
        (let [{:keys [request gen]} (r/props this)]
          (reset! ctrl (region/attach! @node))
          (reset! seen-gen gen)
          (region/show! @ctrl request)))
      :component-did-update
      (fn [this _]
        (let [{:keys [request gen]} (r/props this)]
          (if (= gen @seen-gen)
            (region/show! @ctrl request)
            (do (reset! seen-gen gen)
                (region/rebuild! @ctrl request)))))
      :component-will-unmount
      (fn [_]
        (region/detach! @ctrl)
        (reset! ctrl nil))
      :reagent-render
      (fn [{:keys [attrs ratio]}]
        [:div (merge {:ref #(reset! node %)} attrs
                     (when ratio {:style {:aspect-ratio ratio}}))])})))

(defn- ratio-of
  "The CSS aspect-ratio of region `rect`, or nil without one."
  [{:keys [w h]}]
  (when (and (number? w) (number? h) (pos? w) (pos? h)) (str w " / " h)))

;; ---- text notes -------------------------------------------------------------------------------------------------

(defn- read-ahead!
  "Read the annotation notes of `pages` of `doc` (vinary.slides.evidence/text-notes, its pdfTeX bytes repaired for
   `producer`) into `cache` {:doc :pages {page {:annotation a}}}, each page once per document: a page in :pages has
   been read, nil when it has no annotation note. A read that lands after the document changed is dropped."
  [cache doc producer pages]
  (when doc
    (when-not (identical? doc (:doc @cache))
      (reset! cache {:doc doc :pages {} :reading #{}}))
    (doseq [p pages
            :when (and (integer? p) (>= p 0)
                       (not (contains? (:pages @cache) p))
                       (not (contains? (:reading @cache) p)))]
      (swap! cache update :reading conj p)
      (-> (evidence/text-notes doc p producer)
          (.then (fn [note]
                   (when (identical? doc (:doc @cache))
                     (swap! cache (fn [c] (-> c (assoc-in [:pages p] {:annotation note})
                                              (update :reading disj p)))))))))))

(defn- note-html
  "The sanitized HTML of Markdown note `text`, computed once per text and document (`memo` is reset with the cache)."
  [memo text]
  (or (get @memo text)
      (let [html (notes-html/markdown->html text)]
        (swap! memo assoc text html)
        html)))

(defn external?
  "Whether a note link's `href` opens outside the app: http, https or mailto, in any case. The case-blind match is a
   leading (?i), which ClojureScript makes the RegExp's i flag; an inline (?i:…) group (TC39's RegExp Modifiers) is
   one Closure's regex check rejects (\"Malformed parenthetical\"), so a release build warned (lint check 7)."
  [href]
  (boolean (and (string? href) (re-find #"(?i)^(?:https?|mailto):" href))))

(defn- on-notes-click
  "A click in the notes: a link opens outside the app when it is http, https or mailto (any other — a fragment, a
   relative path — has nowhere to go), and never takes the focus from the slides."
  [^js e]
  (when-let [^js a (some-> (.-target e) (.closest "a[href]"))]
    (.preventDefault e)
    (let [href (.getAttribute a "href")]
      (when (external? href) (rf/dispatch [:shell/open-external href])))
    (.blur a)))

(defn text-note
  "One text note: sanitized Markdown (`markdown?`) or plain text, in the note's own direction. The HTML goes in as
   r/unsafe-html — Reagent (1.3.0 and later) silently drops any other :dangerouslySetInnerHTML value
   (H-REAGENT-HTML, docs/engineering/04 §1.5; lint check 6)."
  [memo markdown? {:keys [text dir from]}]
  (if markdown?
    [:div.vv-presenter-note.markdown-body {:dir dir :data-from (name from)
                                           :dangerouslySetInnerHTML (r/unsafe-html (note-html memo text))}]
    [:div.vv-presenter-note.vv-presenter-note-plain {:dir dir :data-from (name from)} text]))

;; ---- a linked show's line --------------------------------------------------------------------------------------

(defn- link-status
  "A linked show's line in the status strip (ADR-0048 §8.6, §8.7): what the link needs the presenter to know
   (slides-link/notice — opening, waiting, a swap's answer, how the audience window was placed, an end this window
   presents through), and Swap screens while the audience window is on a separate display. The button never takes
   the focus, like the rest of the pane: the slide keys keep working after a click."
  []
  (let [notice @(rf/subscribe [:slides/link-notice])
        swap?  @(rf/subscribe [:slides/swap-offered?])]
    (when (or notice swap?)
      [:div.vv-presenter-link
       (when notice
         [:span.vv-presenter-link-notice {:role "status" :data-tone (name (:tone notice))} (:text notice)])
       (when swap?
         [:button.vv-presenter-swap {:type          "button"
                                     :tab-index     -1
                                     :title         "Move each window to the other's display"
                                     :on-mouse-down #(.preventDefault %)
                                     :on-click      #(rf/dispatch [:slides/swap-screens])}
          "Swap screens"])])))

;; ---- the pane ---------------------------------------------------------------------------------------------------

(defn- reads-for
  "The pages whose text notes the pane reads now: the shown page, and the pages a step back or on would show."
  [d page next-page]
  (let [prev (:page (deck/step d {:page page :back [] :fwd []} :prev))]
    (distinct (remove nil? [page next-page prev]))))

(defn- panel
  "The pane itself, mounted while the presenter view is shown. Reads the borrowed snapshot on every render: the
   subscriptions it derefs change whenever the slides view moves, replaces its document or re-derives its deck."
  []
  (let [cache (r/atom {:doc nil :pages {} :reading #{}})
        memo  (atom {})
        last  (atom nil)                    ; {:doc :producer :pages} of the last render, for the read-ahead
        sync! (fn []
                (when-let [{:keys [doc producer pages]} @last]
                  (when-not (identical? doc (:doc @cache)) (reset! memo {}))
                  (read-ahead! cache doc producer pages)))]
    (r/create-class
     {:display-name "vv-presenter-pane"
      :component-did-mount  (fn [_] (sync!))
      :component-did-update (fn [_ _] (sync!))
      :reagent-render
      (fn []
        (let [slides   @(rf/subscribe [:slides/state])   ; the slides view's position report: a move re-renders
              _        @(rf/subscribe [:doc/deck])        ; the deck re-derived (notes settled, a refresh)
              invert?  @(rf/subscribe [:pdf/invert?])
              console? @(rf/subscribe [:slides/console?])
              blank    @(rf/subscribe [:slides/audience-blank])
              {:keys [doc sizes current doc-gen side notes-model producer text-layer] d :deck pref :notes}
              (pdf-cache/slides-snapshot)]
          (if-not (and doc d (seq sizes))
            [:aside.vv-presenter-pane {:role "complementary" :aria-label "Speaker notes and next slide"}]
            (let [{:keys [page counter overlay-counter regions sidecar-note next deck-notes?]}
                  (presenter/pane d sizes notes-model pref side current)
                  c          @cache
                  read       (when (identical? doc (:doc c)) (get-in c [:pages page]))
                  texts      (when read (notes/page-notes (:annotation read) sidecar-note))
                  pending?   (and (nil? read) (empty? regions))
                  any-text?  (or (:text-notes? slides)
                                 (some (comp some? :annotation) (vals (when (identical? doc (:doc c)) (:pages c)))))
                  markdown?  (boolean (:markdown? notes-model))]
              (reset! last {:doc doc :producer producer :pages (reads-for d page (:page next))})
              [:aside.vv-presenter-pane {:role "complementary" :aria-label "Speaker notes and next slide"
                                         :class (when invert? "vv-pdf-invert")}
               [:div.vv-presenter-strip
                [:span.vv-presenter-position
                 [:span.vv-presenter-counter counter]
                 (when overlay-counter [:span.vv-presenter-overlay " · " overlay-counter])]
                ;; the audience's blank screen, in the colour it shows (ADR-0048 §8.2)
                (when blank
                  [:span.vv-presenter-blank {:data-blank (name blank) :role "status"} (str "Audience: " (name blank))])
                [link-status]]
               [:section.vv-presenter-notes {:aria-label "Speaker notes" :on-click on-notes-click}
                [:h2.vv-presenter-heading "Notes"]
                (for [[i {rp :page rect :rect}] (map-indexed vector regions)]
                  ^{:key i}
                  [region-box {:gen     doc-gen
                               :ratio   (ratio-of rect)
                               :attrs   {:class "vv-presenter-region" :data-page rp}
                               :request {:doc doc :page rp :rect rect :text? true :text-layer text-layer}}])
                (for [[i n] (map-indexed vector texts)]
                  ^{:key i} [text-note memo markdown? n])
                (when (and (empty? regions) (empty? texts) (not pending?))
                  [:p.vv-presenter-empty (if (or deck-notes? any-text?)
                                           "No notes on this slide"
                                           "This deck has no speaker notes")])]
               [:section.vv-presenter-next {:aria-label "Next slide"}
                [:h2.vv-presenter-heading "Next"]
                (if next
                  [region-box {:gen     doc-gen
                               ;; the console's narrow column sizes the box by the slide's own shape
                               :ratio   (when console? (ratio-of (:rect next)))
                               :attrs   {:class "vv-presenter-next-box" :role "img" :aria-label (:label next)
                                         :data-page (:page next)}
                               :request {:doc doc :page (:page next) :rect (:rect next) :text? false}}]
                  [:p.vv-presenter-end "End of the slide show"])]]))))})))

(defn pane
  "The presenter view's pane while this window shows it (s in the slides layout), else nothing."
  []
  (when @(rf/subscribe [:slides/presenter-shown?])
    [panel]))
