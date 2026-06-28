(ns vinary.renderer.core
  "Renderer ENTRY. Boots the re-frame loop, installs the DataScript→re-frame reactivity bridge, wires
   the contextBridge IPC seam (window.vv) into re-frame, and mounts the reagent UI (React 19)."
  (:require [reagent.dom.client :as rdomc]
            [re-frame.core :as rf]
            [re-frame.db :as rfdb]
            [clojure.string :as str]
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
(defonce copy-shortcuts-installed? (atom false))

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
    (when (.-onRecent vv)
      (.onRecent vv (fn [text] (rf/dispatch [:recent/received text])))
      (when (.-requestRecent vv) (.requestRecent vv)))
    (when (.-onExtConfig vv)
      (.onExtConfig vv (fn [text] (rf/dispatch [:ext-config/received text])))
      (when (.-requestExtConfig vv) (.requestExtConfig vv)))
    (when (.-onExtState vv)
      (.onExtState vv (fn [text] (rf/dispatch [:ext/state-received text])))
      (when (.-extState vv) (.extState vv)))
    (when (.-onExtInstallResult vv)
      (.onExtInstallResult vv (fn [p] (rf/dispatch [:ext/install-result p]))))
    (when (.-onExtUpdateResult vv)
      (.onExtUpdateResult vv (fn [p] (rf/dispatch [:ext/update-result p]))))
    (when (.-onAppInfo vv)
      (.onAppInfo vv (fn [payload] (rf/dispatch [:app-info/received (js->clj payload :keywordize-keys true)])))
      (when (.-requestAppInfo vv) (.requestAppInfo vv)))))

(defn keybindings!
  "Install the keymap resolver: vim/emacs/default keymaps + the command registry + modal/chord
   key-sequence resolution (replaces the old hand-rolled Ctrl+F / Alt+←→ listener)."
  []
  (resolver/install!))

(defn- element-for-node [^js node]
  (when node
    (if (= 1 (.-nodeType node))
      node
      (.-parentElement node))))

(defn- selectable-root [^js node]
  (some-> (element-for-node node)
          (.closest ".markdown-body, .vv-source, .vv-pdf-doc")))

(defn- focused-source-selection []
  (when-let [^js editor (.querySelector js/document ".vv-source .cm-editor.cm-focused")]
    (some-> (syntax/view-from-dom editor) syntax/selected-text)))

(defn- dom-selection-text []
  (when-let [sel (.getSelection js/window)]
    (when (and (pos? (.-rangeCount sel)) (not (.-isCollapsed sel)))
      (let [range (.getRangeAt sel 0)
            text  (str sel)
            start (selectable-root (.-startContainer range))
            end   (selectable-root (.-endContainer range))]
        (when (and (seq text) start end (identical? start end))
          text)))))

(defn- selected-preview-or-source-text []
  (or (some-> (focused-source-selection) not-empty)
      (some-> (dom-selection-text) not-empty)))

(defn- copy-key? [^js e]
  (and (or (.-ctrlKey e) (.-metaKey e))
       (= "c" (str/lower-case (or (.-key e) "")))))

(defn copy-shortcuts!
  "Copy selected text from preview/source panes through the app clipboard IPC.

   The listener is capture-phase so it can preserve selected text copying in modal modes; it only handles
   selections inside the rendered preview or source view."
  []
  (when-not @copy-shortcuts-installed?
    (reset! copy-shortcuts-installed? true)
    (.addEventListener js/window "keydown"
                       (fn [^js e]
                         (when (copy-key? e)
                           (when-let [text (selected-preview-or-source-text)]
                             (.preventDefault e)
                             (.stopPropagation e)
                             (rf/dispatch [:clipboard/copy text]))))
                       true)))

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

(defonce ^:private ctrl-held-state (atom false))
(defn ctrl-tracker!
  "Track whether Control is held (capture-phase; reads each event's ctrlKey so a missed keyup self-heals)
   so the URI bar can switch to a clickable breadcrumb. Resets on window blur."
  []
  (let [commit (fn [held?]
                 (when (not= held? @ctrl-held-state)
                   (reset! ctrl-held-state held?)
                   (rf/dispatch [:ui/set-ctrl-held held?])))]
    (.addEventListener js/window "keydown" (fn [^js e] (commit (.-ctrlKey e))) true)
    (.addEventListener js/window "keyup"   (fn [^js e] (commit (.-ctrlKey e))) true)
    (.addEventListener js/window "blur"    (fn [_] (commit false)))))

(defn- editable-target? [^js el]
  (boolean (and el (.-closest el) (.closest el "input, textarea, select, [contenteditable], .cm-editor"))))

(defn- overlay-open-now? []
  (let [ui (:ui @rfdb/app-db)]
    (boolean (or (:menu ui) (:context-menu ui) (:settings-open? ui) (:about-open? ui)
                 (get-in ui [:kbedit :open?]) (get-in ui [:palette :open?])
                 (get-in ui [:hints :active?])))))

(def ^:private arrow->scroll
  {"ArrowDown"  [:nav/scroll {:dy 80}]
   "ArrowUp"    [:nav/scroll {:dy -80}]
   "ArrowRight" [:nav/scroll {:dx :right}]
   "ArrowLeft"  [:nav/scroll {:dx :left}]})

(defn arrow-scroll!
  "Bare arrow keys smoothly scroll the focused pane (the focused element's scrollable ancestor, else the
   content pane). Skipped inside editable elements (inputs / textareas / CodeMirror), which keep their
   native cursor movement, and while a menu/overlay is open. Held keys auto-repeat into the smooth scroll
   animator (vinary.input.fx) for continuous motion. Capture-phase so it preempts the keymap resolver."
  []
  (.addEventListener js/window "keydown"
                     (fn [^js e]
                       (when-let [ev (and (not (.-altKey e)) (not (.-ctrlKey e)) (not (.-metaKey e))
                                          (not (.-shiftKey e))
                                          (not (editable-target? (.-target e)))
                                          (not (overlay-open-now?))
                                          (get arrow->scroll (.-key e)))]
                         (.preventDefault e)
                         (.stopPropagation e)
                         (rf/dispatch ev)))
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
  (copy-shortcuts!)
  (keybindings!)
  (menubar/install-access-keys!)
  (mouse-nav!)
  (hints!)
  (ctrl-tracker!)
  (arrow-scroll!)
  (mount!)
  (rf/dispatch [:view/re-frame-10x-hide]))

(defn ^:export reload [] (mount!))
