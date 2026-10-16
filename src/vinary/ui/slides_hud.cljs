(ns vinary.ui.slides-hud
  "The slide show's heads-up layer (ADR-0045): what floats over the slides layout inside .vv-content-wrap — so it
   never reflows, scrolls or snaps the slides underneath, and stays invisible to in-page find and the Contents
   scroll-spy (both work on .vv-content only).

   The \"Go to 12\" chip shown while a slide number is being typed (digits, then Enter; Escape or 2.5 s of quiet
   forgets it), and the black / white screen of a running show."
  (:require [re-frame.core :as rf]))

(defn goto-chip
  "\"Go to <n>\" while a slide number is being typed, else nothing."
  []
  (when-let [typed @(rf/subscribe [:slides/digits])]
    [:div.vv-slides-goto {:role "status" :aria-live "polite"}
     "Go to " [:b typed] [:span.vv-slides-goto-hint "Enter ⏎"]]))

(defn blank-overlay
  "The black / white screen of a running show (keys b . / w ,): covers the slides beneath (z 7 — under the chip,
   the refresh note and the find bar); a click, any navigation key or Escape ends it."
  []
  (when-let [b @(rf/subscribe [:slides/blank])]
    [:div.vv-slides-blank {:data-blank (name b) :aria-hidden true
                           :on-click #(rf/dispatch [:slides/unblank])}]))

(defn hud
  "Every slide-show indicator for the shown document."
  []
  [:<> [blank-overlay] [goto-chip]])
