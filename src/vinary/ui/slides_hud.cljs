(ns vinary.ui.slides-hud
  "The slide show's heads-up layer (ADR-0045): small, non-interactive indicators floated over the slides layout
   inside .vv-content-wrap — so they never reflow, scroll or snap the slides underneath, and stay invisible to
   in-page find and the Contents scroll-spy (both work on .vv-content only).

   Today: the \"Go to 12\" chip shown while a slide number is being typed (digits, then Enter; Escape or 2.5 s
   of quiet forgets it)."
  (:require [re-frame.core :as rf]))

(defn goto-chip
  "\"Go to <n>\" while a slide number is being typed, else nothing."
  []
  (when-let [typed @(rf/subscribe [:slides/digits])]
    [:div.vv-slides-goto {:role "status" :aria-live "polite"}
     "Go to " [:b typed] [:span.vv-slides-goto-hint "Enter ⏎"]]))

(defn hud
  "Every slide-show indicator for the shown document."
  []
  [goto-chip])
