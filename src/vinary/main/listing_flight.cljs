(ns vinary.main.listing-flight
  "PURE. The Files tree's listing flights (docs/scientific/10 §11; ADR-0034's 2026-10-17 amendment). For each KEY — a
   git repository root, or a synthetic (non-git) root — at most one listing runs at a time, and a request NEVER joins
   the running listing: that listing may have started before the change that prompted the request. The request waits
   instead for ONE trailing listing, which every request arriving meanwhile shares. A burst of N requests during a
   listing therefore costs one more listing, not N, and every request is served by a listing that started after it.

   Contrast vinary.main.content-flight, where an open JOINS the running read: a document's content is the same
   whichever read delivers it, and the running read is the freshest there is. A tree refresh is prompted by a change,
   so it needs a listing that started after the change.

   State: {:seq n :keys {key {:queued #{requester…} :running {:seq s :requesters #{requester…}}}}}. Either field may
   be absent, and a key with neither is removed, so an idle key is an absent one. `:seq` numbers listings across the
   process. Requesters are opaque values; equal ones collapse (a set).

   Effects are data, interpreted by the driver (vinary.main.tree-listing):
     [:schedule key]               run `start` for key on a LATER macrotask, so every request of this event-loop turn
                                   joins the listing it launches
     [:list key s requesters]      launch listing s for those requesters
     [:deliver key s requesters]   hand listing s's result to them

   Transitions (R = a running listing, Q = queued requesters):

     state     event        next state                    effects
     idle      request r    Q = {r}                       [:schedule k]
     Q         request r    Q ∪ {r}                       —     (joins the round before it launches)
     R         request r    R, Q = {r}                    —     (creates the ONE trailing round)
     R, Q      request r    R, Q ∪ {r}                    —     (joins the trailing round)
     Q         start        R = {s+1, Q}                  [:list k s+1 Q]
     idle / R  start        unchanged                     —     (idempotent)
     R(s)      complete s   idle                          [:deliver k s R.requesters]
     R(s), Q   complete s   Q                             [:deliver k s R.requesters] [:schedule k]
     any       complete m   unchanged (m is not R's seq)  —

   A failed listing completes like any other; nothing is cached, so the next request lists afresh.")

(def empty-flights {:seq 0 :keys {}})

(defn running
  "The listing running for `k`, as {:seq :requesters}, or nil."
  [flights k]
  (get-in flights [:keys k :running]))

(defn queued
  "The requesters waiting for `k`'s next listing (a set), or nil."
  [flights k]
  (get-in flights [:keys k :queued]))

(defn request
  "Requester `r` asks for a listing of `k`. → [flights effects]. Schedules a start only when `k` is idle: a queued
   round already has a start scheduled, and a running listing schedules the trailing round when it completes."
  [flights k r]
  (let [{:keys [queued running]} (get-in flights [:keys k])]
    [(update-in flights [:keys k :queued] (fnil conj #{}) r)
     (if (or running (seq queued)) [] [[:schedule k]])]))

(defn start
  "Launch `k`'s queued round, if it has one and nothing is running. → [flights effects]."
  [flights k]
  (let [{:keys [queued running]} (get-in flights [:keys k])]
    (if (or running (empty? queued))
      [flights []]
      (let [s (inc (:seq flights))]
        [(-> flights
             (assoc :seq s)
             (assoc-in [:keys k] {:running {:seq s :requesters queued}}))
         [[:list k s queued]]]))))

(defn complete
  "Listing `s` of `k` finished (whatever its outcome). → [flights effects]: deliver it to its requesters, then
   schedule the trailing round if requests queued meanwhile. A completion that is not `k`'s running listing is
   ignored."
  [flights k s]
  (let [{:keys [queued running]} (get-in flights [:keys k])]
    (cond
      (not= s (:seq running))
      [flights []]

      (seq queued)
      [(assoc-in flights [:keys k] {:queued queued})
       [[:deliver k s (:requesters running)] [:schedule k]]]

      :else
      [(update flights :keys dissoc k)
       [[:deliver k s (:requesters running)]]])))
