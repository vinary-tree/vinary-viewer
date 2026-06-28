(ns vinary.main.ext-util
  "Pure, electron/DOM-free helpers for the extension + ad-blocking runtime (so the node :test build can
   cover them without requiring electron or the native libs). Manifest parsing, Web-Store id parsing,
   enable/disable reconciliation, popup geometry, and ad-block cache/list helpers."
  (:require [clojure.string :as str]))

;; ---- Chrome Web Store id ----
(defn parse-store-id
  "Extract a 32-char Chrome Web Store extension id from a full detail URL or a bare id, else nil.
   (Web Store ids are exactly 32 chars from [a-p].)"
  [s]
  (when (string? s) (re-find #"[a-p]{32}" (str/trim s))))

;; ---- persisted config (extensions.edn) ----
(def default-config
  {:adblock    {:enabled? true :lists :ads-and-tracking :last-updated 0 :update-every-hours 24}
   :extensions {:enabled? true :disabled-ids #{} :last-update-check 0}})

(defn merge-config
  "Normalize a parsed extensions.edn map against the defaults (coercing :disabled-ids to a set)."
  [stored]
  (-> default-config
      (update :adblock merge (:adblock stored))
      (update :extensions merge (:extensions stored))
      (update-in [:extensions :disabled-ids] set)))

;; ---- enable/disable reconciliation ----
(defn reconcile-enabled
  "Given the currently-loaded extension ids and the user's disabled-ids set, the ids to unload."
  [installed-ids disabled-ids]
  (let [dis (set disabled-ids)]
    {:to-unload (vec (filter dis installed-ids))}))

;; ---- toolbar action model (from a parsed manifest map with STRING keys) ----
(defn action-model
  "Extract the browser-action toolbar model from a parsed manifest (action / MV2 browser_action).
   `default_icon` may be a string or a size→path map; we prefer a small crisp size. Returns
   {:title :popup :icon-rel :has-popup?}."
  [manifest]
  (let [a    (or (get manifest "action") (get manifest "browser_action") {})
        icon (get a "default_icon")
        rel  (cond
               (string? icon) icon
               (map? icon)    (or (get icon "32") (get icon "16") (get icon "48")
                                  (get icon "19") (get icon "24") (first (vals icon)))
               :else          nil)]
    {:title      (or (get a "default_title") (get manifest "name"))
     :popup      (get a "default_popup")
     :icon-rel   rel
     :has-popup? (boolean (get a "default_popup"))}))

;; ---- popup geometry ----
(defn clamp-popup-size
  "Clamp a popup's requested [w h] to Chrome's bounds (≤ 800×600) with sane minimums."
  [w h]
  [(-> (or w 360) (max 120) (min 800) js/Math.round)
   (-> (or h 480) (max 80)  (min 600) js/Math.round)])

(defn anchor->bounds
  "Position a popup of [pw ph] just below an icon rect {:x :y :width :height} inside a window of
   [win-w win-h], clamped fully on-screen. Returns {:x :y :width :height}."
  [icon win-w win-h [pw ph]]
  {:x      (-> (:x icon) (min (- win-w pw)) (max 0) js/Math.round)
   :y      (-> (+ (:y icon) (:height icon) 2) (min (- win-h ph)) (max 0) js/Math.round)
   :width  pw
   :height ph})

;; ---- ad-block ----
(defn cache-stale?
  "True when the ad-block engine cache is older than `every-hours` (or was never updated)."
  [last-updated every-hours now]
  (or (nil? last-updated) (zero? last-updated)
      (> (- now last-updated) (* every-hours 3600000))))
