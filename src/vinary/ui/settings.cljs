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
         (stream-flag/flag-on? (:stream? s)) access-active?]]])))
