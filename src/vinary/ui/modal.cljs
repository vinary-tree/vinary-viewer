(ns vinary.ui.modal
  "Shared modal-dialog shell. Renders the scrim overlay + bordered/elevated panel with a title bar
   (title + ✕ close), the supplied body, and an optional actions footer. Every dialog that adopts it gets
   uniform UX for free:

     • Esc-to-close, backdrop-click close, and a ✕ close button.
     • Autofocus the panel on open and restore the previously-focused element on close.
     • A Tab / Shift+Tab focus trap that keeps keyboard focus inside the dialog.
     • True modality — an un-handled keydown inside the dialog is stopped before it reaches the window
       keymap resolver, so background scroll / Vim commands cannot fire while a modal is open.

   It is a form-2 component with a STABLE :ref callback (created once per instance), so the autofocus runs
   exactly once on mount and does not re-grab focus on every re-render (which would steal focus mid-typing).
   Each dialog supplies only `:on-close`/`:title`/`:actions` + its body; the bespoke overlay + stop-
   propagation + Esc-less markup that was copy-pasted across the dialogs is replaced by this one shell.")

(def ^:private focusable-selector
  (str "a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), "
       "textarea:not([disabled]), [tabindex]:not([tabindex='-1'])"))

(defn- focusables
  "Visible, Tab-order focusable descendants of `root`, in document order."
  [^js root]
  (when root
    (->> (array-seq (.querySelectorAll root focusable-selector))
         (filterv (fn [^js el] (and (not (.-hidden el)) (pos? (or (.-offsetWidth el) 0))))))))

(defn- trap-tab!
  "Keep Tab / Shift+Tab focus within `panel`, wrapping at the ends."
  [^js panel ^js e]
  (let [els (focusables panel)]
    (when (seq els)
      (let [first-el (nth els 0)
            last-el  (peek els)
            active   (.-activeElement js/document)]
        (cond
          (and (.-shiftKey e) (or (identical? active first-el) (identical? active panel)))
          (do (.preventDefault e) (.focus last-el))
          (and (not (.-shiftKey e)) (identical? active last-el))
          (do (.preventDefault e) (.focus first-el)))))))

(defn- panel-keydown
  "Panel keydown: stop every key from reaching the window resolver (modality), run the dialog's own
   handler, then apply Esc-to-close and the Tab focus-trap."
  [on-close extra ^js e]
  (.stopPropagation e)
  (when extra (extra e))
  (case (.-key e)
    "Escape" (do (.preventDefault e) (on-close))
    "Tab"    (trap-tab! (.-currentTarget e) e)
    nil))

(defn modal
  "A modal dialog shell.

   First arg — opts map:
     :on-close    REQUIRED 0-arg fn — backdrop click, ✕, and Esc all call it.
     :title       string or hiccup shown in the title bar.
     :class       extra class(es) for the `.vv-modal` panel (e.g. \"vv-modal-wide vv-pw-dialog\").
     :actions     optional hiccup placed in the `.vv-modal-actions` footer (wrap multiple in `[:<> …]`).
     :on-key-down optional extra panel keydown handler (e.g. Alt-access keys), run before Esc/Tab.
   Remaining args — the dialog body hiccup."
  [_opts & _children]
  (let [prev-focus (atom nil)
        ;; ONE stable ref callback per instance: mount (el non-nil) → remember focus + autofocus the panel;
        ;; unmount (el nil) → restore focus. Stable identity ⇒ React never re-invokes it across re-renders.
        ref-fn     (fn [^js el]
                     (if el
                       (do (reset! prev-focus (.-activeElement js/document))
                           (.focus el))
                       (let [^js prev @prev-focus]
                         (when (and prev (.-isConnected prev)) (.focus prev)))))]
    (fn [{:keys [on-close title class actions on-key-down]} & children]
      [:div.vv-modal-overlay {:on-click (fn [_] (on-close))}
       (into
        [:div.vv-modal
         {:class       class
          :role        "dialog"
          :aria-modal  "true"
          :tab-index   -1
          :ref         ref-fn
          :on-click    (fn [^js e] (.stopPropagation e))
          :on-key-down (fn [e] (panel-keydown on-close on-key-down e))}
         [:div.vv-modal-title
          [:span.vv-modal-title-text title]
          [:button.vv-modal-x {:type "button" :title "Close (Esc)" :aria-label "Close"
                               :on-click (fn [_] (on-close))} "✕"]]]
        (concat children
                (when actions [[:div.vv-modal-actions actions]])))])))
