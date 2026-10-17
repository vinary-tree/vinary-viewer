(ns vinary.main.remote-subs
  "PURE. The source side's remote content subscriptions (ADR-0035) and whether each one is LIVE (ADR-0046 §Settling).

   A remote document learns of changes from a target-daemon subscription, once the target reports it active. The PDF
   settle loop asks `live?` to decide whether a still-changing file may be left pending: a live subscription will
   announce the next write, and that announcement's read delivers it.

   Liveness is a field of the subscription record, so dropping the subscription drops it too. Unsubscribing reports
   no inactive state (daemon_events.js unsubscribeContent). If liveness were kept in a second set, a released URI
   would stay \"live\": a reopen would trust a notification that nobody sends until the new subscription is active.
   A state report for a subscription that is no longer held is ignored for the same reason.

   subs = {uri {:active? bool}}")

(defn subscribed?
  "Is a subscription for `uri` held (requested, live or not)?"
  [subs uri]
  (contains? subs uri))

(defn subscribe
  "Hold a subscription for `uri`. It is not live until the target reports it active. Holding one already changes
   nothing."
  [subs uri]
  (if (contains? subs uri) subs (assoc subs uri {:active? false})))

(defn report
  "The target reports `uri`'s subscription active (or not: a lost connection, a failed replay). Ignored unless the
   subscription is held."
  [subs uri active?]
  (if (contains? subs uri) (assoc-in subs [uri :active?] (boolean active?)) subs))

(defn unsubscribe
  "Drop `uri`'s subscription and with it its liveness."
  [subs uri]
  (dissoc subs uri))

(defn live?
  "Will a change notification follow every write to `uri`: is its subscription held AND reported active?"
  [subs uri]
  (boolean (get-in subs [uri :active?])))
