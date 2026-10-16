(ns vinary.ui.settings
  "The Preferences dialog: variable / LaTeX-preview / fixed-width font families, their sizes, and the
   Fira-Code-ligature toggle (the theme lives in the Settings menu). Each change applies live (CSS vars)
   and persists to settings.edn via :settings/set."
  (:require [re-frame.core :as rf]
            [vinary.ui.access-keys :as access]
            [vinary.stream.flag :as stream-flag]
            [vinary.ui.text-input :as text-input]
            [vinary.ui.modal :as modal]))

;; async-input for both editable fields: applying a font change re-measures every figure and Mermaid
;; diagram on screen, and doing that between two keystrokes is what let a re-render commit a stale value
;; over the box — typing `Noto Sans` produced `Not Sans` (ADR-0033, docs/scientific/10). The apply is now
;; debounced in :settings/set, and the field owns its own DOM value regardless.
(defn- text-field [label access-key k value placeholder access-active?]
  [:div.vv-pref-row
   [:label.vv-pref-label [access/label label access-key access-active?]]
   [text-input/async-input
    {:value     (or value "")
     :on-change #(rf/dispatch [:settings/set k %])
     :attrs     (merge {:class "vv-pref-input" :type "text" :placeholder placeholder :spellCheck false}
                       (access/access-attrs access-key))}]])

(defn- num-field [label access-key k value access-active?]
  [:div.vv-pref-row
   [:label.vv-pref-label [access/label label access-key access-active?]]
   [text-input/async-input
    ;; the MODEL is a number but the FIELD is text: `:value` is stringified so the echo test compares like
    ;; with like, and a value that does not parse (mid-edit, an empty box) simply does not commit
    {:value     (if (some? value) (str value) "")
     :on-change #(let [n (js/parseInt % 10)]
                   (when-not (js/isNaN n) (rf/dispatch [:settings/set k n])))
     :attrs     (merge {:class "vv-pref-input vv-pref-num" :type "number" :min 8 :max 40}
                       (access/access-attrs access-key))}]])

(defn- check-field
  "A boolean preference rendered as a checkbox; persists via :settings/set like the text/number fields."
  [label access-key k checked? access-active?]
  [:div.vv-pref-row.vv-pref-check
   [:label.vv-pref-label
    [:input.vv-pref-checkbox
     (merge {:type "checkbox" :checked (boolean checked?)
             :on-change #(rf/dispatch [:settings/set k (.. % -target -checked)])}
            (access/access-attrs access-key))]
    [access/label label access-key access-active?]]])

(defn- on-key-down [^js e]
  (when-let [k (and (.-altKey e) (access/event-letter e))]
    (when (case k
            "v" (access/focus-selector! (.-currentTarget e) ".vv-pref-input[data-vv-access-key='v']")
            "l" (access/focus-selector! (.-currentTarget e) ".vv-pref-input[data-vv-access-key='l']")
            "d" (access/focus-selector! (.-currentTarget e) ".vv-pref-input[data-vv-access-key='d']")
            "f" (access/focus-selector! (.-currentTarget e) ".vv-pref-input[data-vv-access-key='f']")
            "s" (access/focus-selector! (.-currentTarget e) ".vv-pref-input[data-vv-access-key='s']")
            "g" (access/focus-selector! (.-currentTarget e) ".vv-pref-checkbox[data-vv-access-key='g']")
            "t" (access/focus-selector! (.-currentTarget e) ".vv-pref-checkbox[data-vv-access-key='t']")
            "h" (access/focus-selector! (.-currentTarget e) ".vv-pref-checkbox[data-vv-access-key='h']")
            ;; Slides (ADR-0045)
            "o" (access/focus-selector! (.-currentTarget e) ".vv-pref-input[data-vv-access-key='o']")
            "w" (access/focus-selector! (.-currentTarget e) ".vv-pref-input[data-vv-access-key='w']")
            "u" (access/focus-selector! (.-currentTarget e) ".vv-pref-checkbox[data-vv-access-key='u']")
            "k" (access/focus-selector! (.-currentTarget e) ".vv-pref-checkbox[data-vv-access-key='k']")
            "r" (access/focus-selector! (.-currentTarget e) ".vv-pref-input[data-vv-access-key='r']")
            "c" (do (rf/dispatch [:settings/close]) true)
            false)
      (access/consume! e))))

(defn dialog []
  (let [open? @(rf/subscribe [:ui/settings-open?])
        s     @(rf/subscribe [:ui/settings])
        access-active? @(rf/subscribe [:ui/access-keys-active?])]
    (when open?
      [modal/modal
       {:on-close    #(rf/dispatch [:settings/close])
        :title       "Preferences"
        :on-key-down on-key-down
        :actions     [:button.vv-btn (merge {:on-click #(rf/dispatch [:settings/close])}
                                            (access/access-attrs "c"))
                      [access/label "Close" "c" access-active?]]}
       [:div.vv-modal-body
        [:div.vv-pref-section "Fonts"]
        [text-field "Variable-width font" "v" :font-variable (:font-variable s)
         "Noto Sans, system-ui, sans-serif" access-active?]
        [text-field "LaTeX-preview font" "l" :font-latex (:font-latex s)
         "Latin Modern Roman, Georgia, serif" access-active?]
        [num-field  "Document font size (px)" "d" :font-size (:font-size s) access-active?]
        [text-field "Fixed-width font" "f" :font-fixed (:font-fixed s) "Fira Code, monospace" access-active?]
        [num-field  "Code font size (px)" "s" :code-font-size (:code-font-size s) access-active?]
        [check-field "Enable Fira Code ligatures" "g" :code-ligatures?
         (boolean (:code-ligatures? s)) access-active?]
        [:div.vv-pref-section "Documents"]
        [check-field "Stream large documents (progressive rendering)" "t" :stream?
         (stream-flag/flag-on? (:stream? s)) access-active?]
        [:div.vv-pref-section "Find"]
        [:div.vv-pref-row
         [:label.vv-pref-label {:for "vv-find-default-mode"} "Default find mode"]
         [:select.vv-pref-input {:id "vv-find-default-mode"
                                 :value (name (or (:find-default-mode s) :exact))
                                 :on-change #(rf/dispatch [:settings/set :find-default-mode
                                                           (keyword (.. % -target -value))])}
          [:option {:value "exact"} "Exact"]
          [:option {:value "approximate"} "Approximate"]
          [:option {:value "fzf"} "FZF"]]]
        [check-field "English sound-alike matches in prose (Approximate)" "h" :find-phonetic?
         (boolean (:find-phonetic? s)) access-active?]
        ;; slide decks (ADR-0045). The two checkboxes DEFAULT ON — an unset key reads true (only an explicit
        ;; false opts out), matching vinary.app.slides/fullscreen-preferred? and click-advance?
        [:div.vv-pref-section "Slides"]
        [:div.vv-pref-row
         [:label.vv-pref-label {:for "vv-slides-open-as"}
          [access/label "Open detected slide decks as" "o" access-active?]]
         [:select.vv-pref-input (merge {:id "vv-slides-open-as"
                                        :value (if (= :document (:slides-open-as s)) "document" "slides")
                                        :on-change #(rf/dispatch [:settings/set :slides-open-as
                                                                  (keyword (.. % -target -value))])}
                                       (access/access-attrs "o"))
          [:option {:value "slides"} "Slides (one per screen)"]
          [:option {:value "document"} "Document (continuous pages)"]]]
        [:div.vv-pref-row
         [:label.vv-pref-label {:for "vv-slides-overview-page"}
          [access/label "Overview shows each frame" "w" access-active?]]
         [:select.vv-pref-input (merge {:id "vv-slides-overview-page"
                                        :value (if (= :first (:slides-overview-page s)) "first" "last")
                                        :on-change #(rf/dispatch [:settings/set :slides-overview-page
                                                                  (keyword (.. % -target -value))])}
                                       (access/access-attrs "w"))
          [:option {:value "last"} "Fully built (its last overlay)"]
          [:option {:value "first"} "At its first overlay"]]]
        [:div.vv-pref-row
         [:label.vv-pref-label {:for "vv-slides-transition"}
          [access/label "Slide transition" "r" access-active?]]
         [:select.vv-pref-input (merge {:id "vv-slides-transition"
                                        :value (name (if (#{:fade :push} (:slides-transition s)) (:slides-transition s) :none))
                                        :on-change #(rf/dispatch [:settings/set :slides-transition
                                                                  (keyword (.. % -target -value))])}
                                       (access/access-attrs "r"))
          [:option {:value "none"} "None (instant)"]
          [:option {:value "fade"} "Fade"]
          [:option {:value "push"} "Push"]]]
        [check-field "Present full screen" "u" :slides-fullscreen?
         (not= false (:slides-fullscreen? s)) access-active?]
        [check-field "Click advances the slide show" "k" :slides-click-advance?
         (not= false (:slides-click-advance? s)) access-active?]]])))
