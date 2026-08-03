(ns vinary.renderer.warm-cache
  "Bounded cache of expensive, DOM-free renderer preparation artifacts.

   Components acquire a lease while mounted. Releasing makes an eligible artifact inactive;
   only the two most-recent inactive entries survive. In-flight promises are entries too, so
   leaving and immediately revisiting a tab deduplicates the original parse/load.")

(def max-inactive 2)
(def max-entry-weight (* 32 1024 1024))

(defonce ^:private entries (atom {}))
(defonce ^:private clock (atom 0))
(defonce ^:private counters (atom {:hits 0 :misses 0 :evictions 0 :invalidations 0}))

(defn eligible? [weight]
  (<= (max 0 (or weight 0)) max-entry-weight))

(defn- next-clock! [] (swap! clock inc))

(defn- dispose-entry! [{:keys [promise dispose disposed?]}]
  ;; Reset/debug, invalidation, and a stale lease can converge on the same artifact.
  ;; The shared flag makes disposal exactly-once across all of those paths.
  (when (and dispose (compare-and-set! disposed? false true))
    (-> promise
        (.then (fn [value]
                 (try (dispose value) (catch :default _ nil))))
        (.catch (fn [_] nil)))))

(defn- evict-extra! []
  (let [inactive (->> @entries
                      (filter (fn [[_ e]] (zero? (:refs e))))
                      (sort-by (comp :last-used val)))
        victims  (take (max 0 (- (count inactive) max-inactive)) inactive)]
    (when (seq victims)
      (swap! entries #(apply dissoc % (map first victims)))
      (swap! counters update :evictions + (count victims))
      (doseq [[_ entry] victims] (dispose-entry! entry)))))

(defn- release-entry! [key token leased-entry]
  (if-let [entry (get @entries key)]
    ;; A stale lease must never release a newer entry created under the same logical key.
    (if (identical? token (:token entry))
      (let [refs   (max 0 (dec (:refs entry)))
            entry' (assoc entry :refs refs :last-used (next-clock!))]
        (if (and (zero? refs) (or @(:invalidated? entry') (not (eligible? (:weight entry')))))
          (do (swap! entries dissoc key) (dispose-entry! entry'))
          (do (swap! entries assoc key entry') (when (zero? refs) (evict-extra!)))))
      ;; An invalidated active entry may have been replaced by a new generation under
      ;; the same key before its final lease released. It is no longer in `entries`, so
      ;; this lease owns the last cleanup opportunity for the old artifact.
      (when @(:invalidated? leased-entry) (dispose-entry! leased-entry)))
    ;; The entry may already have been synchronously removed by reset/eviction. Disposal
    ;; is idempotent, so an invalidated lease can still safely complete its cleanup.
    (when @(:invalidated? leased-entry) (dispose-entry! leased-entry))))

(defn acquire!
  "Acquire a prepared artifact.

   opts = {:key logical-key :path doc-path :stamp content-stamp :weight backing-byte-estimate
           :build (fn [] value-or-Promise) :dispose (fn [value])}.
   Returns {:promise Promise :hit? boolean :release! idempotent-fn}."
  [{:keys [key path stamp weight build dispose]}]
  (if-let [entry (when-let [entry (get @entries key)]
                   (when-not @(:invalidated? entry) entry))]
    (let [released? (atom false)]
      (swap! entries update key #(-> % (update :refs inc) (assoc :last-used (next-clock!))))
      (swap! counters update :hits inc)
      {:promise (:promise entry)
       :hit? true
       :release! (fn [] (when (compare-and-set! released? false true)
                          (release-entry! key (:token entry) entry)))})
    (let [token     (js-obj)
          raw       (try (js/Promise.resolve (build))
                         (catch :default e (js/Promise.reject e)))
          entry     {:token token :path path :stamp stamp :weight (or weight 0)
                     :promise raw :dispose dispose :disposed? (atom false)
                     :invalidated? (atom false) :refs 1 :last-used (next-clock!)}
          released? (atom false)]
      (swap! entries assoc key entry)
      (swap! counters update :misses inc)
      ;; A rejected preparation is not reusable. This side branch cleans up without
      ;; replacing the rejected promise returned to the caller.
      (.catch raw (fn [_]
                    (when (identical? token (:token (get @entries key)))
                      (swap! entries dissoc key))
                    nil))
      {:promise raw
       :hit? false
       :release! (fn [] (when (compare-and-set! released? false true)
                          (release-entry! key token entry)))})))

(defn- invalidate-where! [pred]
  (let [victims (filter (fn [[_ entry]] (pred entry)) @entries)]
    (when (seq victims)
      (swap! counters update :invalidations + (count victims))
      (doseq [[key entry] victims]
        (reset! (:invalidated? entry) true)
        (if (zero? (:refs entry))
          (do (swap! entries dissoc key) (dispose-entry! entry))
          nil)))))

(defn invalidate-path!
  "Invalidate older artifacts for `path`, retaining only `stamp`. Active leases are marked
   stale and disposed when released; inactive entries are disposed immediately."
  [path stamp]
  (invalidate-where! #(and (= path (:path %)) (not= stamp (:stamp %)))))

(defn retain-only! [paths]
  (let [keep (set paths)]
    (invalidate-where! #(not (contains? keep (:path %))))))

(defn stats []
  (assoc @counters
         :entries (count @entries)
         :inactive (count (filter (comp zero? :refs val) @entries))))

(defn reset-cache!
  "Test/debug reset. Disposes every resolved artifact before clearing counters."
  []
  (doseq [[_ entry] @entries] (dispose-entry! entry))
  (reset! entries {})
  (reset! clock 0)
  (reset! counters {:hits 0 :misses 0 :evictions 0 :invalidations 0}))
