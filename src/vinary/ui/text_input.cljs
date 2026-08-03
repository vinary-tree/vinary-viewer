(ns vinary.ui.text-input
  "One text field whose DOM value is owned LOCALLY and never by application state.

   THE DEFECT IT REMOVES. A React-controlled input sets `:value` from a subscription, so the character
   the browser just inserted is authoritative only until React's next commit — which happens after the
   re-frame dispatch queue drains AND after Reagent's batched render. If anything slow runs in between,
   React commits a value from before the keystroke and the character is destroyed. Measured directly
   (docs/scientific/10): typing `views` into the file-tree filter produced `vews`, and the tracer caught
   the writer red-handed with `from: \"vi\"` → `to: \"v\"`; typing `Noto Sans` into a Preferences font
   field produced `Not Sans`, `from: \"Noto\"` → `to: \"Not\"`.

   Note what those two have in common and find does not: the widget's OWN render is expensive (the tree
   rebuilds thousands of nodes; a Preferences edit re-measures every figure). A slow render elsewhere is
   not enough — the find bar is five elements and never clobbered even while a 450 ms search blocked the
   thread. The commit has to land between two keystrokes with stale state in hand, and only a widget that
   is slow to render itself does that reliably. Which is exactly why this cannot be fixed by making the
   app faster: it has to stop being possible.

   HOW. A local draft shadows the model while the user is editing, so React's committed value always
   equals what the DOM already holds and a commit can never subtract a character. The interesting part is
   knowing when to STOP shadowing, because the model legitimately changes from elsewhere: `:find/reset`
   blanks the query on a document switch, `:palette/open` clears it, a tab switch replaces the address.

   Naively comparing the model against the draft cannot tell those apart from the model merely CATCHING
   UP — while typing, the model always trails by one or more characters, and treating that as an external
   push would reintroduce the very clobber this exists to prevent. So the component keeps the values it
   has published but not yet seen echoed back. A model change matching one of them is our own echo (drop
   it and everything older, keep shadowing); a model change matching none of them is genuinely external
   (stop shadowing and follow the model).

   CONTRACT for callers:

   • `on-change` is called synchronously with the RAW new value on every keystroke. It must be cheap —
     store the string and nothing else. Schedule real work with vinary.async.scheduler.
   • `on-change` must store the value VERBATIM. A handler that normalises (trims, lower-cases) breaks the
     echo test: its write would look external and drop the draft mid-word. Normalise on read.

   Bookkeeping lives in a PLAIN atom, deliberately: it is written during render, and a Reagent atom
   written during render schedules another render — the classic reactive-write-in-render loop. Only the
   draft is reactive, and only handlers and a next-tick write it."
  (:require [reagent.core :as r]))

;; How many published-but-unechoed values to remember. One per keystroke that has not yet round-tripped
;; through app-db; a handful in the worst case. Bounded anyway so a caller whose `on-change` never writes
;; the model (a no-op handler, a filtered widget) cannot grow it without limit.
(def ^:private pending-cap 32)

(defn- index-of [v x]
  (loop [i 0]
    (cond
      (>= i (count v)) nil
      (= x (nth v i))  i
      :else            (recur (inc i)))))

(defn reconcile
  "Fold one observed model value into the bookkeeping.

   PUBLIC because it is the whole of this component's subtlety and is unit-tested directly: deciding
   whether a model change is our own echo or someone else's push is what the correctness of every text
   field in the app now rests on, and that decision should be assertable without a DOM.

   Returns the new bookkeeping map with `:external?` set when the change came from somewhere other than
   this component — the one condition under which the draft must be abandoned."
  [{:keys [seen pending] :as bk} model]
  (cond
    ;; first render: adopt the model as the baseline, nothing is external yet
    (= ::init seen) (assoc bk :seen model :external? false)
    (= model seen)  (assoc bk :external? false)
    :else
    (if-let [i (index-of pending model)]
      ;; our own echo, finally observed — it and everything published before it are accounted for
      {:seen model :pending (subvec pending (inc i)) :external? false}
      {:seen model :pending [] :external? true})))

(defn publish
  "Record that `v` was handed to `on-change`, so the model echoing it back is recognised as ours."
  [bk v]
  (update bk :pending (fn [p] (let [p' (conj (or p []) v)]
                                (if (> (count p') pending-cap)
                                  (subvec p' (- (count p') pending-cap))
                                  p')))))

(defn async-input
  "A text `<input>` that never loses a keystroke.

   Options:
     :value            the model value (a subscription's result)
     :on-change        (fn [s]) — cheap, verbatim; see the namespace contract
     :on-commit        (fn [s]) — Enter
     :on-cancel        (fn [])  — Escape
     :commit-on-blur?  call :on-commit with the current draft when focus leaves
     :select-on-mount? focus and select the complete value once, after the input mounts
     :on-key-down      (fn [event]) — runs FIRST; if it calls .preventDefault, Enter/Escape handling here
                       is skipped, so a widget can keep its own meaning for those keys
     :attrs            merged onto the <input> (:class, :placeholder, :auto-focus, :type, :ref, …)

   Form-2: the state must survive re-renders, and the props are read fresh on every one."
  [_opts]
  (let [draft (r/atom nil)                                    ; reactive: drives the re-render
        bk    (atom {:seen ::init :pending [] :external? false})
        opts* (atom nil)
        selected? (atom false)
        ;; Stable ref: React calls it only for mount/unmount, not on every render. Compose the
        ;; caller's ref and perform the one-shot selection after autoFocus has landed.
        ref-fn (fn [^js el]
                 (when-let [f (get-in @opts* [:attrs :ref])] (f el))
                 (when (and el (:select-on-mount? @opts*) (not @selected?))
                   (reset! selected? true)
                   (r/next-tick (fn []
                                  (when (.-isConnected el)
                                    (.focus el #js {:preventScroll true})
                                    (.select el))))))]
    (fn [{:keys [value on-change on-commit on-cancel commit-on-blur? on-key-down attrs]
          :as opts}]
      (reset! opts* opts)
      (let [model (or value "")
            {:keys [external?]} (swap! bk reconcile model)]
        ;; Abandoning the draft is a reactive write, so it cannot happen during render — next-tick it.
        ;; Rendering `model` this pass (below) already shows the right thing, so there is no flash.
        (when external? (r/next-tick #(reset! draft nil)))
        (let [d     @draft
              shown (if (or external? (nil? d)) model d)]
          [:input
           (merge
            {:value shown
             :ref ref-fn
             :on-change (fn [^js e]
                          (let [v (.. e -target -value)]
                            (reset! draft v)
                            (swap! bk publish v)
                            (when on-change (on-change v))))
             :on-blur (fn [^js e]
                        (when (and commit-on-blur? on-commit) (on-commit (or @draft model)))
                        (when-let [f (:on-blur attrs)] (f e)))
             :on-key-down (fn [^js e]
                            (when on-key-down (on-key-down e))
                            (when-not (.-defaultPrevented e)
                              (case (.-key e)
                                "Enter"  (when on-commit
                                           (.preventDefault e)
                                           (on-commit (or @draft model)))
                                "Escape" (when on-cancel
                                           (.preventDefault e)
                                           (reset! draft nil)
                                           (on-cancel))
                                nil)))}
            ;; :on-blur is composed above rather than overwritten, so a caller keeping its own blur
            ;; behaviour does not silently disable commit-on-blur
            (dissoc attrs :on-blur :ref))])))))
