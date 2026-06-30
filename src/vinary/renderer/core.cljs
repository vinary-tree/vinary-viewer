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

(defn- replay-web-key!
  "Replay a chord forwarded from the (separate-context) web view as a synthetic window keydown, so the app
   keymap resolver runs the same command it would for an app-focused chord (e.g. Ctrl+O → Open dialog).
   The resolver listens on window keydown and doesn't require a trusted event."
  [^js p]
  (let [ev (js/KeyboardEvent. "keydown"
                              #js {:key        (.-key p)
                                   :ctrlKey    (boolean (.-ctrl p))
                                   :shiftKey   (boolean (.-shift p))
                                   :altKey     (boolean (.-alt p))
                                   :metaKey    (boolean (.-meta p))
                                   :bubbles    true
                                   :cancelable true})]
    (.dispatchEvent js/window ev)))

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
    (when (.-onWebKey vv)   ; app-global Ctrl/Cmd chords forwarded from the web view → replay via the resolver
      (.onWebKey vv replay-web-key!))
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
    (when (.-onAdblockStatus vv)
      (.onAdblockStatus vv (fn [p] (rf/dispatch [:adblock/status-received p]))))
    (when (.-onPasswordState vv)
      (.onPasswordState vv (fn [p] (rf/dispatch [:passwords/state-received p])))
      (when (.-passwordState vv) (.passwordState vv)))
    (when (.-onPasswordItems vv)
      (.onPasswordItems vv (fn [p] (rf/dispatch [:passwords/items-received p]))))
    (when (.-onPasswordSavePrompt vv)
      (.onPasswordSavePrompt vv (fn [p] (rf/dispatch [:passwords/save-prompt p]))))
    (when (.-onPasswordResult vv)
      (.onPasswordResult vv (fn [p] (rf/dispatch [:passwords/result-received p]))))
    (when (.-onZoomChanged vv)
      (.onZoomChanged vv (fn [p] (rf/dispatch [:view/zoom-changed p]))))
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
          (.closest ".markdown-body, .vv-source, .vv-pdf-doc, .vv-table-doc, .vv-log-doc")))

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
                 (:extensions-open? ui)
                 (get-in ui [:passwords :open?])
                 (get-in ui [:passwords :save-prompt])
                 (get-in ui [:kbedit :open?]) (get-in ui [:palette :open?])
                 (get-in ui [:hints :active?])))))

(def ^:private arrow->scroll
  {"ArrowDown"  [:nav/scroll {:dy 80}]
   "ArrowUp"    [:nav/scroll {:dy -80}]
   "ArrowRight" [:nav/scroll {:dx :right}]
   "ArrowLeft"  [:nav/scroll {:dx :left}]})

(def ^:private page->scroll
  ;; PageDown/PageUp/Home/End — page and jump-to-edge, the same in every view. Shift is allowed (unlike the
  ;; arrows), so Shift+PageDown still pages and Shift+Home/End still jumps.
  {"PageDown" [:nav/scroll {:dy :page}]
   "PageUp"   [:nav/scroll {:dy :-page}]
   "End"      [:nav/scroll {:to :bottom}]
   "Home"     [:nav/scroll {:to :top}]})

(def ^:private key->web-scroll
  ;; page/edge keys also forwarded to the native web view (main applies them only when that view is
  ;; visible), so they scroll the web page even when app chrome — not the web view — holds focus.
  {"PageDown" "page-down" "PageUp" "page-up" "End" "end" "Home" "home"})

(defn key-scroll!
  "Bare arrow keys and PageDown/PageUp/Home/End smoothly scroll the focused pane (the focused element's
   scrollable ancestor, else the content pane / its CodeMirror scroller — see vinary.input.fx). Handled
   here in ONE capture-phase listener so they work UNIFORMLY across every keymap set (vim/emacs/default):
   otherwise Vim's resolver swallows them (:consume) and Standard/Emacs pass them to the empty document
   scroller (html/#app are overflow:hidden), so neither scrolls. Skipped inside editable elements (inputs /
   textareas / CodeMirror), which keep their native caret + Page/Home/End movement, and while a
   menu/overlay is open. (The native web view scrolls these keys itself via its own preload when it holds
   focus.) Arrows ignore all modifiers; the page/home/end keys ignore Shift but not Ctrl/Alt/Meta. Held
   keys auto-repeat into the smooth-scroll animator. Capture-phase so it preempts the keymap resolver."
  []
  (.addEventListener js/window "keydown"
                     (fn [^js e]
                       (let [no-cam? (and (not (.-altKey e)) (not (.-ctrlKey e)) (not (.-metaKey e)))
                             ev      (when (and no-cam?
                                                (not (editable-target? (.-target e)))
                                                (not (overlay-open-now?)))
                                       (or (get page->scroll (.-key e))
                                           (when-not (.-shiftKey e) (get arrow->scroll (.-key e)))))]
                         (when ev
                           (.preventDefault e)
                           (.stopPropagation e)
                           (rf/dispatch ev)
                           (when-let [kind (get key->web-scroll (.-key e))]
                             (when-let [^js vv (.-vv js/window)]
                               (when (.-httpScroll vv) (.httpScroll vv kind)))))))
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
  (key-scroll!)
  (mount!)
  (rf/dispatch [:view/re-frame-10x-hide]))

(defn ^:export reload [] (mount!))
