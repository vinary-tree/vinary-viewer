(ns vinary.renderer.core
  "Renderer ENTRY. Boots the re-frame loop, installs the DataScript→re-frame reactivity bridge, wires
   the contextBridge IPC seam (window.vv) into re-frame, and mounts the reagent UI (React 19)."
  (:require [reagent.dom.client :as rdomc]
            [re-frame.core :as rf]
            [re-frame.db :as rfdb]
            [vinary.app.ds :as ds]
            [vinary.app.events]
            [vinary.app.subs]
            [vinary.app.commands]
            [vinary.input.events]
            [vinary.input.resolver :as resolver]
            [vinary.renderer.history-input :as history-input]
            [vinary.renderer.syntax :as syntax]
            [cljs.reader :as reader]
            [vinary.ui.menubar :as menubar]
            [vinary.ui.views :as views]))

(defonce root (atom nil))
(defonce last-history-input (atom {:dir nil :time 0}))

(defn- dispatch-history-nav! [dir]
  (let [[state accepted?] (history-input/accept @last-history-input dir (.now js/Date))]
    (reset! last-history-input state)
    (when accepted?
      (case dir
        "back"    (rf/dispatch [:history/back])
        "forward" (rf/dispatch [:history/forward])
        nil))))

(defn bridge!
  "Wire the preload's contextBridge API (window.vv) to re-frame. Content streams in here on every file
   change (live-refresh)."
  []
  (when-let [^js vv (.-vv js/window)]
    (.onContent vv (fn [payload] (rf/dispatch [:content/received (js->clj payload :keywordize-keys true)])))
    (.onError   vv (fn [payload] (rf/dispatch [:content/error   (js->clj payload :keywordize-keys true)])))
    (.onTree    vv (fn [payload] (rf/dispatch [:tree/received    (js->clj payload :keywordize-keys true)])))
    (when (.-onKeymap vv)   ; guard: an older preload may not expose the keymap channel
      (.onKeymap vv (fn [payload] (rf/dispatch [:keymap/config-received (js->clj payload :keywordize-keys true)])))
      (when (.-requestKeymap vv) (.requestKeymap vv)))   ; pull in case main pushed before we subscribed
    (when (.-onGrammars vv)
      (.onGrammars vv (fn [text] (syntax/register-user! (when (string? text)
                                                          (try (reader/read-string text) (catch :default _ nil))))))
      (when (.-requestGrammars vv) (.requestGrammars vv)))
    ;; in-app HTTP web view: in-page navigation + the page's heading outline / active heading (for the TOC)
    (when (.-onHttpNavigated vv)
      (.onHttpNavigated vv (fn [payload] (rf/dispatch [:http/navigated (js->clj payload :keywordize-keys true)]))))
    (when (.-onWebToc vv)
      (.onWebToc vv (fn [payload] (rf/dispatch [:web/toc (js->clj payload :keywordize-keys true)]))))
    (when (.-onWebActiveHeading vv)
      (.onWebActiveHeading vv (fn [id] (rf/dispatch [:web/active-heading id]))))
    (when (.-onHistoryNav vv)
      (.onHistoryNav vv dispatch-history-nav!))
    ;; menu shell: files chosen in the Open dialog, persisted settings (EDN text), app-info for About
    (when (.-onOpenFiles vv)
      (.onOpenFiles vv (fn [payload] (rf/dispatch [:files/opened (js->clj payload :keywordize-keys true)]))))
    (when (.-onSettings vv)
      (.onSettings vv (fn [text] (rf/dispatch [:settings/received text])))
      (when (.-requestSettings vv) (.requestSettings vv)))
    (when (.-onAppInfo vv)
      (.onAppInfo vv (fn [payload] (rf/dispatch [:app-info/received (js->clj payload :keywordize-keys true)])))
      (when (.-requestAppInfo vv) (.requestAppInfo vv)))))

(defn keybindings!
  "Install the keymap resolver: vim/emacs/default keymaps + the command registry + modal/chord
   key-sequence resolution (replaces the old hand-rolled Ctrl+F / Alt+←→ listener)."
  []
  (resolver/install!))

(defn mouse-nav!
  "Mouse thumb buttons: button 3 → Back, button 4 → Forward (like a browser). preventDefault on the
   capture-phase mousedown cancels Chromium's own back/forward so it never replaces the page."
  []
  (.addEventListener js/window "mousedown"
                     (fn [^js e]
                       (case (.-button e)
                         3 (do (.preventDefault e) (dispatch-history-nav! "back"))
                         4 (do (.preventDefault e) (dispatch-history-nav! "forward"))
                         nil))
                     true))

(defn hints!
  "When link hints are active, a capture-phase key listener owns the keyboard: letters filter/activate the
   labels, Backspace pops a char, Esc cancels — preempting the global resolver via stopPropagation. When
   hints are inactive it is inert, so `f` still reaches the resolver to start hinting."
  []
  (.addEventListener js/window "keydown"
                     (fn [^js e]
                       (when (get-in @rfdb/app-db [:ui :hints :active?])
                         (let [k (.-key e)]
                           (cond
                             (= k "Escape")    (do (.preventDefault e) (.stopPropagation e) (rf/dispatch [:hints/cancel]))
                             (= k "Backspace") (do (.preventDefault e) (.stopPropagation e) (rf/dispatch [:hints/backspace]))
                             (re-matches #"[a-zA-Z]" k) (do (.preventDefault e) (.stopPropagation e) (rf/dispatch [:hints/type k]))
                             :else nil))))
                     true))

(defn mount! []
  (when (nil? @root)
    (reset! root (rdomc/create-root (.getElementById js/document "app"))))
  (rdomc/render @root [views/root]))

(defn ^:export init []
  (rf/dispatch-sync [:db/init])
  (ds/install-bridge!)
  (set! (.-__vvdb js/window) (fn [] (clj->js @rfdb/app-db)))            ; DEV inspect hooks
  (set! (.-__vvds js/window) (fn [] (clj->js (ds/open-docs (ds/snapshot)))))
  (set! (.-__vvkeymap js/window) (fn [nm] (rf/dispatch [:keymap/select nm])))   ; DEV: switch keymap set
  (bridge!)
  (keybindings!)
  (menubar/install-access-keys!)
  (mouse-nav!)
  (hints!)
  (mount!)
  (rf/dispatch [:view/re-frame-10x-hide]))

(defn ^:export reload [] (mount!))
