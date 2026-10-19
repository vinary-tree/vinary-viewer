(ns vinary.ui.slides-hud
  "The slide show's heads-up layer (ADR-0045): what floats over the slides layout inside .vv-content-wrap — so it
   never reflows, scrolls or snaps the slides underneath, and stays invisible to in-page find and the Contents
   scroll-spy (both work on .vv-content only).

   The \"Go to 12\" chip shown while a slide number is being typed (digits, then Enter; Escape or 2.5 s of quiet
   forgets it), the speaker-notes hint of a show that hides a deck's notes (ADR-0048 §6.1, P5), and the black / white
   screen of a running show."
  (:require [reagent.core :as r]
            [re-frame.core :as rf]))

(defn goto-chip
  "\"Go to <n>\" while a slide number is being typed, else nothing."
  []
  (when-let [typed @(rf/subscribe [:slides/digits])]
    [:div.vv-slides-goto {:role "status" :aria-live "polite"}
     "Go to " [:b typed] [:span.vv-slides-goto-hint "Enter ⏎"]]))

(defn- hint-chip
  "The hint itself, mounted while it is shown: the next key or click anywhere dismisses it (captured, never consumed —
   the key still does what it does), as the 6 s timer does (:slides/notes-hint-dismiss)."
  []
  (let [dismiss! (fn [_] (rf/dispatch [:slides/notes-hint-dismiss]))]
    (r/create-class
     {:display-name "vv-slides-notes-hint"
      :component-did-mount
      (fn [_]
        (.addEventListener js/window "keydown" dismiss! true)
        (.addEventListener js/window "pointerdown" dismiss! true))
      :component-will-unmount
      (fn [_]
        (.removeEventListener js/window "keydown" dismiss! true)
        (.removeEventListener js/window "pointerdown" dismiss! true))
      :reagent-render
      (fn []
        [:div.vv-slides-notes-hint {:role "status" :aria-live "polite"}
         "Speaker notes: " [:b "s"] " shows them here"])})))

(defn notes-hint
  "The speaker-notes hint while a show that hides the deck's notes has just started (ADR-0048 §6.1, P5), else nothing."
  []
  (when @(rf/subscribe [:slides/notes-hint?])
    [hint-chip]))

(defn blank-overlay
  "The black / white screen of a running show (keys b . / w ,): covers the slides beneath (z 7 — under the chip,
   the refresh note and the find bar); a click, any navigation key or Escape ends it. In the presenter view of a
   linked show the audience's screen is the blank one: this window shows a badge over the slide instead, and keeps
   its notes in view (ADR-0048 §8.2)."
  []
  (when-let [b @(rf/subscribe [:slides/blank])]
    (if @(rf/subscribe [:slides/audience-blank])
      [:div.vv-slides-blank-badge {:data-blank (name b) :role "status"} (str "Audience: " (name b))]
      [:div.vv-slides-blank {:data-blank (name b) :aria-hidden true
                             :on-click #(rf/dispatch [:slides/unblank])}])))

(defn hud
  "Every slide-show indicator for the shown document."
  []
  [:<> [blank-overlay] [goto-chip] [notes-hint]])
