(ns vinary.main.retention
  "Pure ownership model for main-process document retention.

   State is `{web-contents-id {:wc webContents :paths #{doc-path ...}}}`.  Keeping the
   reconciliation here makes the multi-window invariant testable without Electron: one
   window may replace only its own path set, and a path remains retained until its final
   owner disappears.")

(defn sync-owner
  "Replace one owner's retained paths without disturbing any other owner."
  [owners owner-id wc paths]
  (assoc owners owner-id {:wc wc :paths (set paths)}))

(defn drop-owner [owners owner-id]
  (dissoc owners owner-id))

(defn drop-path
  "Release `path` for one owner. Empty owners are kept until their webContents is destroyed
   so the main service installs only one destruction listener per window."
  [owners owner-id path]
  (if (contains? owners owner-id)
    (update-in owners [owner-id :paths] disj path)
    owners))

(defn paths-for [owners owner-id]
  (get-in owners [owner-id :paths] #{}))

(defn owner-ids-for [owners path]
  (into #{} (keep (fn [[owner-id {:keys [paths]}]]
                    (when (contains? paths path) owner-id))) owners))

(defn retained? [owners path]
  (boolean (seq (owner-ids-for owners path))))

(defn all-paths [owners]
  (into #{} (mapcat (comp seq :paths val)) owners))
