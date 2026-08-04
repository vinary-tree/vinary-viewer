(ns vinary.main.doc-overrides
  "Main-side per-document type overrides (ADR-0036). `vv:open` carries only a path, and every watcher
   live-refresh re-classifies from the filename — so an explicit type (CLI `-t`, Settings ▸ File Type)
   must live HERE, keyed by the exact path/uri the renderer opens, where send-content! can consult it on
   EVERY send. The same entry carries a piped-stdin document's virtual-source facts (:stdin? — skip the
   sidebar tree and the file watcher, the snapshot never changes; :cwd — the invoking directory, so a
   piped diff's side-by-side view resolves `a/… b/…` against the repo it was generated in).

   Pure atom bookkeeping only: filesystem effects (unlinking a stdin temp file) stay in
   vinary.main.service, its side-effect-at-the-edge contract. Entries are cleared when retention drops
   the path (unwatch-file!), so \"close tab, reopen file\" returns to extension deduction."
  )

(defonce ^:private overrides (atom {}))   ; path|uri -> {:kind :language :delimiter :stdin? :cwd}

(defn register!
  "Merge a resolved file-spec's override keys for path (a CLI open: {:kind :language :delimiter} and/or
   {:stdin? :cwd}). Empty/nil specs are a no-op so bare specs (no explicit type, not stdin) cost nothing."
  [path spec]
  (let [spec (into {} (filter (comp some? val)) (or spec {}))]
    (when (and (string? path) (seq spec))
      (swap! overrides update path merge spec))))

(defn set-type!
  "Re-type path in place (Settings ▸ File Type): :kind/:language/:delimiter are replaced AS A UNIT — a
   kind pick clears a previous language pick and vice versa — while the stdin facts (:stdin? :cwd)
   survive, so a piped document can be re-typed without losing its virtual-source behavior."
  [path kind language]
  (when (and (string? path) (string? kind))
    (swap! overrides update path
           (fn [entry]
             (cond-> (-> (or entry {}) (dissoc :kind :language :delimiter) (assoc :kind kind))
               (string? language) (assoc :language language))))))

(defn clear!
  "Forget every override for path (retention dropped it, or its window closed)."
  [path]
  (swap! overrides dissoc path))

(defn for-path
  "The override entry for path, or nil."
  [path]
  (get @overrides path))

(defn stdin-doc?
  "Is path a piped-stdin document (an immutable spilled snapshot — no tree, no watcher, temp-file
   lifecycle owned by the service)?"
  [path]
  (boolean (:stdin? (get @overrides path))))

(defn stdin-base-dir
  "The invoking cwd of a piped-stdin document (diff side-by-side enrichment resolves against it), or nil."
  [path]
  (let [{:keys [stdin? cwd]} (get @overrides path)]
    (when stdin? cwd)))

(defn effective-kind
  "The kind send-content! must serve for path: the explicit override when one is registered, else the
   classifier's answer. `classify` is injected (the service's grammar-aware kind-of) so this namespace
   stays pure and node-testable."
  [classify path]
  (or (:kind (get @overrides path))
      (classify path)))

(defn reset-all!
  "Drop every entry — test isolation only."
  []
  (reset! overrides {}))
