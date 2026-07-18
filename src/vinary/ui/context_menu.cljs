(ns vinary.ui.context-menu
  "The themed right-click context menu. State [:ui :context-menu] = {:x :y :target {:kind :path :text}}.
   Items adapt to the target kind (a tree/link file, a directory, or an http link), arranged + separated
   for clarity. A click-away (or right-click) overlay closes it."
  (:require [reagent.core :as r]
            [re-frame.core :as rf]
            [clojure.string :as str]
            [vinary.ui.menu-focus :as menu-focus]
            [vinary.ui.preview-navigation :as preview-nav]
            [vinary.renderer.text-edit :as text-edit]))

(defn- basename [p] (last (str/split (str p) #"/")))

(defn- source-label [source?]
  (if source? "View Preview" "View Source"))

(defn tab-close-side-label [orientation]
  (if (= orientation :vertical) "Close Below" "Close to the Right"))

(defn tab-items [{:keys [path id view-source? orientation]}]
  [{:label "Close"                :event [:tab/close id]}
   {:label "Duplicate tab"        :event [:tab/duplicate id]}
   {:label "Close Others"         :event [:tab/close-others id]}
   {:label (tab-close-side-label orientation) :event [:tab/close-right id]}
   :sep
   {:label (source-label view-source?) :event [:tab/toggle-source id]}
   (when path :sep)
   (when path {:label "Copy file path" :event [:clipboard/copy path]})
   (when path {:label "Copy file name" :event [:clipboard/copy (basename path)]})])

(defn items-for
  [{:keys [kind path uri text source-location source-line math-tex] :as target} vs? previewable?]
  (case kind
    :file [{:label "Open"                 :event [:doc/open path]}
           {:label "Open in new tab"      :event [:doc/open-new path]}
           :sep
           {:label "Copy file path"       :event [:clipboard/copy path]}
           {:label "Copy file name"       :event [:clipboard/copy (basename path)]}]
    :dir  [{:label "Open"                 :event [:doc/open path]}
           {:label "Open in new tab"      :event [:doc/open-new path]}
           {:label "Open in file manager" :event [:shell/open-path path]}
           :sep
           {:label "Copy directory path"  :event [:clipboard/copy path]}
           {:label "Copy directory name"  :event [:clipboard/copy (basename path)]}]
    :http [{:label "Open"                 :event [:doc/open path]}
           {:label "Open in new tab"      :event [:doc/open-new path]}
           {:label "Open in system browser" :event [:shell/open-external path]}
           :sep
           {:label "Copy link URL"        :event [:clipboard/copy path]}
           (when (seq text) {:label "Copy link text" :event [:clipboard/copy text]})]
    :preview-link
    [{:label "Open"                 :event (preview-nav/open-event target false)}
     (when (preview-nav/new-tab? target)
       {:label "Open in new tab"    :event (preview-nav/open-event target true)})
     :sep
     {:label "Copy link location"   :event [:clipboard/copy uri]}
     {:label "Copy link text"       :event [:clipboard/copy (or text "")]}
     (when source-location
       {:label "Copy source location" :event [:clipboard/copy source-location]})
     (when source-line
       {:label "Go to source"       :event [:source/goto-line source-line]})]
    :preview-body
    [(when (seq math-tex) {:label "Copy LaTeX" :event [:clipboard/copy math-tex]})
     {:label "Copy"                 :event [:clipboard/copy (or text "")]}
     (when source-location
       {:label "Copy source location" :event [:clipboard/copy source-location]})
     (when source-line
       {:label "Go to source"       :event [:source/goto-line source-line]})]
    :source-body
    [{:label "Copy"                 :event [:clipboard/copy (or text "")]}
     (when source-location
       {:label "Copy source location" :event [:clipboard/copy source-location]})
     ;; jump to the rendered object for this source line — only when the doc has a preview (markdown/org)
     (when (and source-line previewable?)
       {:label "Go to preview"      :event [:preview/goto-line source-line]})
     :sep
     {:label "Copy file path"       :event [:clipboard/copy path]}
     {:label "Copy file name"       :event [:clipboard/copy (basename path)]}]
    ;; a tab (right-clicked in either the horizontal strip or the vertical Tabs panel)
    :tab  (tab-items target)
    ;; the active markdown document (right-clicked in the content pane, not on a link)
    ;; the active document (right-clicked in the content pane): its Preview↔Source toggle (facet-aware — a no-op
    ;; when there is no other facet to switch to, e.g. a lone PDF)
    :doc  [{:label (source-label vs?) :event [:tab/toggle-source]}
           :sep
           {:label "Copy file path"       :event [:clipboard/copy path]}
           {:label "Copy file name"       :event [:clipboard/copy (basename path)]}]
    ;; a text <input>/<textarea> (any of the app's own fields). Actions run on the focused field via
    ;; execCommand — the menu keeps it focused (see `context-menu`), so no target element need be threaded.
    :text-input
    [{:label "Cut"        :action text-edit/cut!        :disabled? (not (:has-selection? target))}
     {:label "Copy"       :action text-edit/copy!       :disabled? (not (:has-selection? target))}
     {:label "Paste"      :action text-edit/paste!}
     :sep
     {:label "Select All" :action text-edit/select-all! :disabled? (not (:has-text? target))}]
    nil))

(defn- act! [event] (rf/dispatch [:context-menu/close]) (rf/dispatch event))

(defn- consume! [^js e]
  (.preventDefault e)
  (.stopPropagation e))

(defn- on-menu-key [items focus event]
  (case (.-key event)
    "ArrowDown" (do (consume! event) (reset! focus (menu-focus/move-index items @focus 1)))
    "ArrowUp"   (do (consume! event) (reset! focus (menu-focus/move-index items @focus -1)))
    "Home"      (do (consume! event) (reset! focus (menu-focus/first-index items)))
    "End"       (do (consume! event) (reset! focus (menu-focus/last-index items)))
    "Enter"     (do (consume! event) (when-let [item (menu-focus/item-at items @focus)] (act! (:event item))))
    " "         (do (consume! event) (when-let [item (menu-focus/item-at items @focus)] (act! (:event item))))
    "Escape"    (do (consume! event) (rf/dispatch [:context-menu/close]))
    nil))

(defn context-menu []
  (r/with-let [focus (r/atom nil)
               last-menu (r/atom nil)]
    (let [{:keys [x y target] :as m} @(rf/subscribe [:ui/context-menu])
          vs? @(rf/subscribe [:ui/active-view-source?])
          ;; the fine-grained "Go to preview" jump needs data-vv-source-* mapped to the ORIGINAL source; only
          ;; markdown/org qualify. LaTeX (like the PDF representation) offers the whole-pane Preview↔Source
          ;; toggle instead — its unified-latex positions map to generated HTML, not the .tex, so no line jump.
          previewable? (contains? #{"markdown" "org"} @(rf/subscribe [:doc/kind]))
          items (vec (remove nil? (items-for target vs? previewable?)))
          focused @focus
          ;; a text-input menu must NOT steal focus — the right-clicked field stays document.activeElement so
          ;; execCommand targets it (and the URI field's on-blur can't wipe a mid-edit draft). Its items are
          ;; therefore mouse-driven (no keyboard nav); every other menu keeps today's focus + keyboard behavior.
          keep-focus? (= (:kind target) :text-input)]
      (when (not= @last-menu m)
        (reset! last-menu m)
        (reset! focus nil))
      (when m
        [:div.vv-ctx-overlay
         {:on-click        #(rf/dispatch [:context-menu/close])
          :on-context-menu (fn [^js e] (.preventDefault e) (rf/dispatch [:context-menu/close]))}
         [:div.vv-ctx-menu {:style        {:left (str x "px") :top (str y "px")}
                            :role         "menu"
                            :tab-index    (when-not keep-focus? 0)
                            ;; keep-focus: cancel the mousedown's default focus-shift so clicking an item can't
                            ;; blur the input execCommand needs to act on
                            :on-mouse-down (when keep-focus? (fn [^js e] (.preventDefault e)))
                            :on-click     #(.stopPropagation %)
                            :on-key-down  #(on-menu-key items focus %)
                            :ref          (when-not keep-focus? (fn [el] (when el (.focus el))))}
          (doall
           (for [[i item] (map-indexed vector items)]
             (if (= item :sep)
               ^{:key i}
               [:div.vv-menu-sep]
               ^{:key i}
               [:div.vv-menu-item
                {:class (str (when (= focused i) "vv-menu-item-focused")
                             (when (:disabled? item) " vv-menu-item-disabled"))
                 :role "menuitem"
                 :aria-disabled (boolean (:disabled? item))
                 :on-mouse-enter (when-not (:disabled? item) #(reset! focus i))
                 :on-click (when-not (:disabled? item)
                             (if-let [action (:action item)]
                               ;; run synchronously (within the click gesture) so the clipboard write is allowed
                               (fn [_] (action) (rf/dispatch [:context-menu/close]))
                               #(act! (:event item))))}
                [:span.vv-menu-item-label (:label item)]])))]]))))
