(ns vinary.ui.slides-bar
  "The slide controls outside the slide (ADR-0045 §Entry points):

   · `bar` — the bottom bar's slide controls while a PDF shows in the slides layout, in place of the zoom control
     (slides fit themselves, so zoom is inert there): ◀ [page] / N ▶ · frame f / F · Overview · Present. The page
     field takes a PHYSICAL page number (what it shows); typing a slide's printed number goes through the
     keyboard's digits + Enter instead, which resolves Beamer labels.
   · `toolbar-button` — the address bar's slides toggle for any PDF (View as Slide Show ↔ View as Document).

   Both hand the keyboard back to the slides after a click (a focused button would otherwise take the next
   Space / Enter for itself instead of the slide show)."
  (:require [reagent.core :as r]
            [re-frame.core :as rf]
            [vinary.ui.icons :as icons]))

(defn- then-focus-slides! [event]
  (rf/dispatch event)
  (rf/dispatch [:nav/focus :content]))

(defn bar
  "The slide bar for the mounted slide show's position mirror."
  []
  (let [draft (r/atom nil)]          ; non-nil while the page field is being edited
    (fn []
      (let [{:keys [page pages frame frames overlay overlays]} @(rf/subscribe [:slides/state])
            shown   (if (nil? @draft) (str (inc (or page 0))) @draft)
            commit! (fn []
                      (let [n (js/parseInt @draft 10)]
                        (when-not (js/isNaN n)
                          (then-focus-slides! [:slides/command [:goto (dec n)]])))
                      (reset! draft nil))]
        [:div.vv-slides-bar {:role "toolbar" :aria-label "Slides"}
         [:button.vv-slides-bar-btn {:title "Previous slide (←)" :aria-label "Previous slide"
                                     :disabled (<= (or page 0) 0)
                                     :on-click #(then-focus-slides! [:slides/command :prev])}
          (icons/icon :slide-prev)]
         [:input.vv-slides-bar-page
          {:value shown :spellCheck false :title "Go to page" :aria-label "Page"
           :size 4
           :on-focus    (fn [^js e] (reset! draft (str (inc (or page 0)))) (.select (.-target e)))
           :on-blur     (fn [_] (reset! draft nil))
           :on-change   (fn [^js e] (reset! draft (.. e -target -value)))
           :on-key-down (fn [^js e]
                          (case (.-key e)
                            "Enter"  (do (.preventDefault e) (commit!))
                            "Escape" (do (reset! draft nil) (rf/dispatch [:nav/focus :content]))
                            nil))}]
         [:span.vv-slides-bar-of (str "/ " (or pages 0))]
         [:button.vv-slides-bar-btn {:title "Next slide (→, Space)" :aria-label "Next slide"
                                     :disabled (>= (inc (or page 0)) (or pages 0))
                                     :on-click #(then-focus-slides! [:slides/command :next])}
          (icons/icon :slide-next)]
         (when (and frames (< frames (or pages 0)))
           [:span.vv-slides-bar-frame {:title "Frame (Beamer slide) · overlay within it"}
            (str "frame " (inc (or frame 0)) " / " frames
                 (when (and overlays (> overlays 1)) (str " · " (inc (or overlay 0)) "/" overlays)))])
         [:button.vv-slides-bar-btn {:title "Slide overview (Tab)" :aria-label "Slide overview"
                                     :on-click #(rf/dispatch [:slides/overview {:open? true}])}
          (icons/icon :overview)]
         [:button.vv-slides-bar-btn {:title "Present from this slide (Shift+F5) — F5 starts from the beginning"
                                     :aria-label "Present from this slide"
                                     :on-click #(rf/dispatch [:slides/present {:from :current}])}
          (icons/icon :present)]]))))

(defn toolbar-button
  "The address bar's slides toggle, shown while a PDF is the active document."
  []
  (when @(rf/subscribe [:view/pdf-active?])
    (let [slides? @(rf/subscribe [:slides/view?])]
      [:button.vv-nav-btn.vv-slides-toggle
       {:title        (if slides? "View as document (continuous pages)" "View as slide show (one slide per screen)")
        :aria-pressed slides?
        :class        (when slides? "vv-slides-toggle-on")
        :on-click     #(then-focus-slides! [:pdf/layout-toggle])}
       (icons/icon :slides)])))
