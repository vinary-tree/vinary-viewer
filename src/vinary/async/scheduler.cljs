(ns vinary.async.scheduler
  "One cancellable, budgeted job runner for everything a keystroke starts.

   THE INVARIANT this namespace exists to supply:

     No task started from a keystroke occupies the main thread for longer than `budget-ms` without
     yielding, and re-arming a key cancels whatever that key had in flight.

   That is what makes a text field asynchronous in the sense the user means: the renderer is never busy
   long enough to make a character appear late, and work for a query that has already been superseded
   stops immediately instead of running to completion first.

   WHY IT IS SHARED. Before this namespace there were four separate deferral idioms in the renderer,
   each re-derived where it was needed:

     • `:dispatch-later` + a generation counter          (vinary.app.events, in-page find)
     • `ric` — rAF when visible, ric when hidden          (vinary.stream.scheduler)
     • a boolean latch + one rAF                          (renderer.toc/spy!, and two copies of it)
     • clear-timer / set-timer                            (app.fx ×4, input.fx keymap-persist)

   They answer three questions — when to start, how long to run, and how to stop — and every widget that
   needed all three had to assemble its own answer. `ric` is now defined here and `vinary.stream.scheduler`
   requires it back, so the one place that had gotten the visibility handling right is the one place it
   lives.

   FOUR PRIMITIVES.

     debounce!  wait for typing to stop        — one live timer per key, genuinely cancelled
     coalesce!  one response per painted frame — for a continuous event stream (scroll, selection)
     slice!     run long work without blocking — a step function driven under a frame budget
     cancel!    stop whatever this key is doing

   All four are keyed. A key is any value that compares with `=` (a keyword like `:find/search`, or a
   vector for a per-instance job); arming a key supersedes that key alone.

   DOM-FREE. Nothing here touches `document` beyond the visibility check `ric` already made, so the
   namespace loads in the :node-test build and its arithmetic is unit-tested without a browser."
  (:require [vinary.async.budget :as budget]))

;; ---- the tick -----------------------------------------------------------------------------------------

(defn ric
  "Schedule the next work tick. When the window is VISIBLE, pump on animation frames — a steady ~60fps
   cadence that eliminates the idle-starvation gaps (requestIdleCallback could drip batches up to 100 ms
   apart under main-thread load, which read as the 'slow/clunky' stutter). When HIDDEN (rAF is paused by
   the browser) or without rAF, fall back to requestIdleCallback with a :timeout so a backgrounded job
   still progresses.

   Moved here verbatim from vinary.stream.scheduler, which now requires it: the streaming drain and a
   chunked find share exactly this scheduling question, and having solved it once is the point."
  [f]
  (if (and (exists? js/document)
           (= "visible" (.-visibilityState js/document))
           (exists? js/requestAnimationFrame))
    (js/requestAnimationFrame (fn [_] (f #js {:timeRemaining (fn [] 8)})))
    (if (exists? js/requestIdleCallback)
      (js/requestIdleCallback f #js {:timeout 100})
      (js/requestAnimationFrame (fn [_] (f #js {:timeRemaining (fn [] 8)}))))))

;; A single MessageChannel, shared by every yield. `postMessage` posts a TASK, so the browser gets to
;; service its input queue (which outranks posted messages) before the callback runs, and then resumes
;; immediately — no waiting for a frame.
(defonce ^:private yield-queue (array))

(defonce ^:private yield-port
  (delay
    (when (exists? js/MessageChannel)
      (let [ch (js/MessageChannel.)]
        (set! (.-onmessage ^js (.-port1 ch))
              (fn [_] (when (pos? (.-length yield-queue))
                        ((.shift yield-queue)))))
        (.-port2 ch)))))

(defn yield!
  "Resume `f` on the next macrotask, as soon as the browser has drained anything more urgent.

   THE DISTINCTION FROM `ric`, which is the whole reason both exist:

   • `ric` paces work to the DISPLAY. That is exactly right for committing DOM as a document streams in —
     there is no point producing frames faster than the screen shows them.
   • `yield!` paces work to the INPUT QUEUE. That is what chunked computation needs: give the browser a
     chance to deliver a keystroke, then carry on at once.

   Using `ric` for computation caps throughput at one slice per frame — 8 ms of work per 16 ms at best,
   and far worse than that whenever the compositor throttles animation frames (an occluded window, or any
   headless/xvfb run, where frames arrive about once a second). Measured: the chunked find took 380 ms to
   settle on a 4 kB document and never finished at all on a 1.1 MB one, purely from waiting for frames.
   With `yield!` the same work runs back-to-back while still letting every keystroke through.

   `setTimeout(f, 0)` is the fallback and a poor one — clamped to ≥1 ms, and to 4 ms once timers nest —
   which is precisely why MessageChannel is the primary path."
  [f]
  (if-let [^js port @yield-port]
    (do (.push yield-queue f) (.postMessage port 0))
    (js/setTimeout f 0))
  nil)

;; ---- registry -----------------------------------------------------------------------------------------

;; key → {:timer <id> :epoch <int>}. The epoch is what cancels a SLICED job: a running slice loop reads it
;; every tick and stops the moment it no longer matches the epoch it started with. A timer id could not do
;; that, because a slice loop is not a timer — it re-arms itself through `yield!`, and there is no handle
;; to clear between ticks.
(defonce ^:private registry (atom {}))

(defn- bump-epoch!
  "Invalidate whatever `k` is running and return the new epoch."
  [k]
  (:epoch (get (swap! registry update k
                      (fn [e] (-> e (update :epoch (fnil inc 0)) (dissoc :timer))))
               k)))

(defn- clear-timer! [k]
  (when-let [t (get-in @registry [k :timer])]
    (js/clearTimeout t)
    (swap! registry update k dissoc :timer)))

(defn cancel!
  "Stop whatever `k` has in flight: clear its pending timer and invalidate its running slice loop. Safe to
   call for a key that has never been armed."
  [k]
  (clear-timer! k)
  (bump-epoch! k)
  nil)

(defn pending?
  "Is `k` currently waiting on a debounce timer? Exposed for tests and for widgets that want to show a
   'working' affordance without inventing a second piece of state."
  [k]
  (some? (get-in @registry [k :timer])))

(defn epoch
  "The current epoch of `k` — the token a long-running job compares against to notice it was superseded."
  [k]
  (get-in @registry [k :epoch] 0))

(defn live?
  "Is `token` still the current epoch for `k`? A job captures `(epoch k)` when it starts and calls this
   between slices; `false` means a newer request arrived and this one must stop."
  [k token]
  (= token (epoch k)))

;; ---- debounce -----------------------------------------------------------------------------------------

(defn debounce!
  "Run `f` once `ms` have passed with no further arming of `k`.

   ONE live timer per key, cleared and replaced on each call. The pattern it replaces — schedule a timer
   per keystroke and let the stale ones fire into a generation check — is observationally similar but
   leaves one timer per character alive, each waking the event loop to discover it has nothing to do. It
   also cannot express cancellation: `cancel!` here really does stop the pending work.

   `ms` of 0 still defers to a timer, so the caller's own keystroke handler always returns first."
  [k ms f]
  (clear-timer! k)
  (let [token (bump-epoch! k)]
    (swap! registry assoc-in [k :timer]
           (js/setTimeout (fn []
                            (swap! registry update k dissoc :timer)
                            ;; a cancel! between the timer firing and this body running bumps the epoch;
                            ;; honour it rather than starting work that is already obsolete
                            (when (live? k token) (f)))
                          (max 0 ms))))
  nil)

;; ---- frame coalescing ---------------------------------------------------------------------------------

(defonce ^:private coalescing (atom #{}))

(defn coalesce!
  "Run `f` once on the next animation frame, however many times `k` is armed before it arrives.

   The complement of `debounce!`, and the difference is which end of the burst wins. A debounce fires
   after the burst, with the LAST value — right for a query the user is still typing. A coalesce fires
   during the burst, at the next frame — right for a handler driven by a continuous stream of events
   (scroll, selectionchange, resize) where the goal is one response per painted frame and the latest
   state is read inside `f` rather than passed to it.

   `ric` rather than a bare rAF, so a hidden window still makes progress instead of stalling until it is
   shown again. This replaces the boolean-latch-plus-rAF pattern that had been written out three times."
  [k f]
  (when-not (contains? @coalescing k)
    (swap! coalescing conj k)
    (ric (fn [_]
           (swap! coalescing disj k)
           (f))))
  nil)

;; ---- sliced work --------------------------------------------------------------------------------------

(def ^:private default-budget-ms 8)

(defn slice!
  "Drive `step` under a frame budget until it reports completion, yielding between slices.

   `step` is called with no arguments and returns:
     truthy → there is more to do; call me again
     falsey → finished

   It is called repeatedly within one tick while the elapsed time for that tick stays under `budget-ms`,
   then the loop yields through `yield!` and resumes on the next macrotask. So `step` should be a SMALL
   unit of work — one batch, not the whole job — and the budget, not the caller, decides how many run
   together.

   `yield!` and not `ric`: this is computation, not painting, so it should pace to the input queue rather
   than to the display. See `yield!` for the measurement that settled it.

   Options:
     :budget-ms  main-thread time per tick before yielding (default 8 — half a 60 Hz frame, leaving room
                 for the frame's own layout and paint)
     :done       called with no arguments after the final `step`, on the same tick
     :on-cancel  called if the job is superseded before finishing
     :on-yield   called each time the budget is exhausted and the loop is about to yield — a natural
                 place to report progress, and what makes the pacing observable to a test

   Cancellation is checked between every slice AND between every tick, so re-arming `k` stops the work
   within one unit rather than at the end of the job. Superseding is the normal case while typing, not an
   error: `on-cancel` exists for cleanup, and omitting it is fine."
  [k {:keys [step done on-cancel on-yield budget-ms] :or {budget-ms default-budget-ms}}]
  (cancel! k)
  (let [token (epoch k)
        tick  (fn tick []
                (if-not (live? k token)
                  (when on-cancel (on-cancel))
                  (let [t0 (budget/now)]
                    (loop []
                      (cond
                        ;; superseded mid-tick: stop without finishing the batch
                        (not (live? k token)) (when on-cancel (on-cancel))
                        (step)                (if (budget/spent? t0 budget-ms)
                                                (do (when on-yield (on-yield)) (yield! tick))
                                                (recur))
                        :else                 (when done (done)))))))]
    (yield! tick))
  nil)

(defn run-now!
  "Arm `k` and run `f` immediately, so a caller that wants 'cancel anything in flight, then do this
   synchronously' does not have to reach for the registry itself. Returns f's value."
  [k f]
  (cancel! k)
  (f))
