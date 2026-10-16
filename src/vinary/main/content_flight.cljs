(ns vinary.main.content-flight
  "PURE. One read per path at a time, delivered to everyone who asked for it (ADR-0046 §Reload safety).

   Before this model every window read a changed file on its own: a burst of N change notifications for a file
   retained by W windows started N·W reads, an older read finishing late could overwrite a newer one, and a remote
   document paid one whole download per window per change. The driver (vinary.main.service) now routes every
   reason to read a path through `request`, and every finished read through `complete`; this namespace decides,
   from those events alone, which reads to start and who receives each result. It does no I/O and owns no
   clock — the effects it returns are data the driver interprets.

   State: {:seq n :paths {path {:inflight {:seq s :requesters #{id} :fanout? b} | nil
                                :dirty?   b   ; a change arrived while the read was running
                                :carry    {:requesters #{id} :fanout? b} | nil}}}
     :seq        the last read number handed out, process-wide; every read gets the next one, so read numbers
                 strictly increase — the renderer's guard (vinary.app.content-guard) drops any payload older
                 than one it already applied
     :inflight   the ONE read running for the path: its number, the windows that asked for it explicitly (an
                 open — the window may not retain the path yet), and whether it goes to every window retaining
                 the path (a change notification)
     :dirty?     a change notification arrived while :inflight ran, so its bytes may be stale: exactly ONE
                 trailing read follows it, however many notifications arrived
     :carry      what the next read owes: the requesters of a read that ended :pending (a PDF still being
                 written — no delivery) or that turned out dirty

   Effects: [:read path seq] — start read `seq` of `path`; [:deliver path {:seq :requesters :fanout?}] — send
   that read's result (a payload or an error) to its requesters, and to every retaining window when :fanout?.

   Invariants (unit-tested, incl. a randomized simulation): at most one read in flight per path; read numbers
   strictly increase; a burst of change notifications during one read causes exactly one more read; every
   window that asked receives a delivery of a read that started at or after its request, exactly once per read
   it joined.")

(def empty-flights {:seq 0 :paths {}})

(defn in-flight
  "The read running for `path` ({:seq :requesters :fanout?}), or nil."
  [flights path]
  (get-in flights [:paths path :inflight]))

(defn request
  "Someone wants `path` read: an open from window `requester`, or a change notification (`fanout?` — the result
   goes to every window retaining the path). Returns [flights effects].

   Idle → a read starts at once (with whatever an earlier :pending or dirty read carried over). A read already
   running → an open JOINS it (its result goes to the requester too) and a notification marks it dirty (one
   trailing read follows it — see `complete`)."
  [flights path {:keys [requester fanout?]}]
  (if-let [_ (in-flight flights path)]
    [(update-in flights [:paths path]
                (fn [p] (cond-> p
                          requester (update-in [:inflight :requesters] conj requester)
                          fanout?   (assoc :dirty? true))))
     []]
    (let [carry (get-in flights [:paths path :carry])
          s     (inc (:seq flights))
          reqs  (cond-> (or (:requesters carry) #{}) requester (conj requester))]
      [(-> flights
           (assoc :seq s)
           (assoc-in [:paths path] {:inflight {:seq s :requesters reqs
                                               :fanout? (boolean (or fanout? (:fanout? carry)))}
                                    :dirty?   false
                                    :carry    nil}))
       [[:read path s]]])))

(defn complete
  "Read `read-n` of `path` finished: `outcome` is :delivered (a payload or an error to send) or :pending (nothing to
   send yet — a PDF still being written; a change notification is on its way). Returns [flights effects].

   :delivered → its requesters (and, when it fanned out, every retaining window) receive it. If a change arrived
   meanwhile the read may be stale, so ONE trailing read starts at once — fanning out, and owed to the same
   requesters too, who asked for the newest version. :pending → nobody receives anything; the requesters (and
   the fan-out) are carried to the next read, which starts at once when a change already arrived, else with the
   next notification. A completion that is not the path's running read (the path was released, or the number is
   stale) changes nothing."
  [flights path read-n outcome]
  (let [{:keys [inflight dirty?]} (get-in flights [:paths path])]
    (if (not= read-n (:seq inflight))
      [flights []]
      (let [delivered? (= outcome :delivered)
            deliver    (when delivered? [:deliver path (select-keys inflight [:seq :requesters :fanout?])])
            owed?      (or dirty? (not delivered?))
            carry      (when owed?
                         {:requesters (:requesters inflight)
                          :fanout?    (boolean (or dirty? (:fanout? inflight)))})
            idle       (assoc-in flights [:paths path] {:inflight nil :dirty? false :carry carry})]
        (if dirty?
          (let [[flights' effects] (request idle path {:fanout? true})]
            [flights' (cond->> effects deliver (into [deliver]))])
          [idle (if deliver [deliver] [])])))))

(defn release
  "`path` is no longer retained by anyone: forget it. A read still running for it completes into nothing."
  [flights path]
  (update flights :paths dissoc path))

(defn forget-requester
  "Window `id` is gone: drop it from every running read and every carried debt (its deliveries could only fail)."
  [flights id]
  (update flights :paths
          (fn [paths]
            (into {}
                  (map (fn [[path p]]
                         [path (cond-> p
                                 (:inflight p) (update-in [:inflight :requesters] disj id)
                                 (:carry p)    (update-in [:carry :requesters] disj id))]))
                  paths))))
