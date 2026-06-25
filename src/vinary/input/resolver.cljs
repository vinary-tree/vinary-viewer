(ns vinary.input.resolver
  "Key-sequence resolver (Interpreter): on each keydown, normalize the chord, build a resolution context
   from app-db, walk it against the active keymap's trie for the current mode, and act — dispatch a
   command (leaf), extend the pending sequence (prefix), consume (vim swallows stray normal keys), or
   pass (let the key reach inputs/browser).

   The pending sequence + chord timer are held in resolver-LOCAL atoms (updated synchronously) so a fast
   multi-key sequence resolves even when two keydowns land in one JS task (re-frame dispatch is async).
   The sequence is mirrored into app-db (:input/set-sequence) only for the mode-line display. The pure
   core is `step`; `install!` is the global listener that replaces the old hand-rolled keybindings!."
  (:require [re-frame.core :as rf]
            [re-frame.db :as rfdb]
            [vinary.app.nav :as nav]
            [vinary.app.commands :as commands]
            [vinary.input.keys :as keys]
            [vinary.input.keymap :as keymap]))

(defonce ^:private pending (atom []))   ; the current key-sequence (synchronous, authoritative)
(defonce ^:private timer   (atom nil))  ; pending chord-timeout id

(defn- cancel-timer! [] (when @timer (js/clearTimeout @timer) (reset! timer nil)))
(defn- mirror!       [] (rf/dispatch [:input/set-sequence @pending]))   ; app-db copy, for the mode-line
(defn  reset-seq!    [] (cancel-timer!) (reset! pending []) (mirror!))
(defn- arm!          [] (cancel-timer!) (reset! timer (js/setTimeout reset-seq! (keymap/timeout-ms))))
(defn- push!         [token] (swap! pending conj token) (mirror!) (arm!))

(defn- build-ctx
  "Assemble the resolution context from app-db (read directly — keydown is outside a reactive context).
   The tab/nav slice (tabs, active-path, can-back?/forward?) comes from vinary.app.nav."
  []
  (let [db @rfdb/app-db]
    (merge (nav/base-ctx db)
           {:mode          (get-in db [:ui :input :mode])
            :sequence      @pending
            :in-input?     (get-in db [:ui :input :in-input?])
            :find-visible? (get-in db [:ui :find :visible?])
            :palette-open? (get-in db [:ui :palette :open?])})))

(defn- leaf-decision [node]
  (cond
    (keyword? node)              {:action :dispatch :command node}
    (and (map? node) (:id node)) {:action :dispatch :command (:id node) :args (:args node)}
    (map? node)                  {:action :prefix}
    :else                        nil))

(defn step
  "Pure resolution. modes = active keymap modes; returns a decision map
   {:action :dispatch|:prefix|:consume|:pass|:retry …}."
  [modes mode sequence token ctx]
  (let [root (merge (get modes :all) (get modes mode))
        node (get-in root (conj (vec sequence) token))]
    (or (leaf-decision node)
        (if (seq sequence)
          {:action :retry}
          (if (and (#{:normal :visual} mode) (not (:in-input? ctx)))
            {:action :consume}
            {:action :pass})))))

(defn- run-command! [decision ctx]
  (commands/run (:command decision) ctx (some-> (:args decision) vec)))

(defn- handle [^js e]
  (when-let [token (keys/event->chord e (keys/mac?))]
    (let [ctx  (build-ctx)
          mode (:mode ctx)
          seqv @pending]
      ;; the palette owns all keys while open; and a bare printable key always reaches a focused input
      ;; (so typing a find query / vim "/" search works even in normal mode)
      (when-not (or (:palette-open? ctx)
                    (and (:in-input? ctx) (keys/bare-printable? token)))
        (let [decision (step (keymap/modes) mode seqv token ctx)]
          (case (:action decision)
            :prefix   (do (.preventDefault e) (push! token))
            :dispatch (do (.preventDefault e) (reset-seq!) (run-command! decision ctx))
            :consume  (do (.preventDefault e) (reset-seq!))
            :pass     (when (seq seqv) (reset-seq!))
            :retry    (let [d2 (step (keymap/modes) mode [] token ctx)]
                        (reset-seq!)
                        (case (:action d2)
                          :prefix   (do (.preventDefault e) (push! token))
                          :dispatch (do (.preventDefault e) (run-command! d2 ctx))
                          :consume  (.preventDefault e)
                          nil))
            nil))))))

(defonce ^:private installed (atom false))

(defn install! []
  (when-not @installed
    (reset! installed true)
    (.addEventListener js/window "keydown" handle)))
