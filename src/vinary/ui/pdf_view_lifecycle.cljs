(ns vinary.ui.pdf-view-lifecycle
  "PURE. The pdf-view component's lifecycle decisions (vinary.ui.views/pdf-view; ADR-0045 §Live refresh) as data, so
   the rules that keep a live refresh from holding the view state hostage are unit-tested DOM-free.

   The component mounts ONE document per (tab, path) and refreshes it IN PLACE: a newer content stamp is prepared
   beside the live document and swapped in when it opens. A refresh lands WITHOUT a re-render — nothing reactive
   changes when pdf.js finishes — which is what two rules guard against:
     · a view-state change (zoom, fit, invert, layout, presenting, transition) is applied on the render that
       carries it, even when that render also carries a new stamp, and even while a refresh is pending. The old
       component chose EITHER refresh OR update, so a change rendered while a refresh was pending was dropped
       until some unrelated change re-rendered the view.
     · a version that arrives while the FIRST mount is still being prepared is refreshed to as soon as that mount
       lands. The render that carried it found no controller to refresh, and nothing re-renders afterwards, so the
       old component kept showing the older version.")

(defn update-actions
  "What component-did-update does after a render, from:
     :path :stamp  the render's props — the document's path and its content stamp
     :shown        [path stamp] of the mounted document, or nil
     :mounted?     does an engine controller exist (has a document finished mounting)?
     :preparing?   is a mount's preparation (its warm-cache lease) in flight?
     :failed       the last stamp whose bytes failed to open, or nil (never retried automatically)
   → a set of actions, which the component applies in this order:
     :remount  another document occupies the slot (not expected — content-view keys the view by tab + path — but
               one file's pages must never show under another's name): tear down, mount the new path. Exclusive.
     :mount    nothing is mounted or preparing, and this stamp has not failed: mount the newest cached version
     :refresh  the mounted document has a newer stamp that has not failed: prepare it beside the live one and
               swap it in place when it opens
     :update   apply the view state to the mounted engine — on EVERY render once mounted, refresh or not"
  [{:keys [path stamp shown mounted? preparing? failed]}]
  (let [[shown-path shown-stamp] shown]
    (cond
      (and shown-path (not= path shown-path)) #{:remount}
      (not mounted?) (if (and (not= stamp failed) (not preparing?)) #{:mount} #{})
      :else (cond-> #{:update}
              (and (not= stamp shown-stamp) (not= stamp failed)) (conj :refresh)))))

(defn refresh-after-mount?
  "A mount of stamp `mounted` just landed: did a newer version arrive while it was being prepared? `newest` is the
   byte cache's newest stamp for the path (vinary.renderer.pdf-cache/bytes-for: the pending candidate, else the good
   version), `failed` the last stamp that failed to open. True → refresh to `newest` now; no did-update will."
  [{:keys [mounted newest failed]}]
  (boolean (and (some? newest) (not= newest mounted) (not= newest failed))))
