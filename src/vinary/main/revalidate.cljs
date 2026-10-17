(ns vinary.main.revalidate
  "PURE. The verdict of a CONDITIONAL re-open (ADR-0046 §Re-validation): is the remote file a window still holds the
   same version a fresh stat describes?

   A Back/Forward step lands on a remote (ssh:// / sftp://) document this window still holds. It used to download the
   whole file again, every time. Now main stats it first — one round trip — and reads it only when it changed. A
   version is the pair the remote reader delivered with the document, its size and its modification time (content
   _service openRemoteUri's `meta`). That is the same signal the remote poller watches for changes.

   What it cannot see: SFTP reports times in whole seconds. A rewrite that keeps the file's size, landing within the
   same second as the read that produced the window's copy, therefore leaves the pair unchanged. A history step then
   shows the copy it showed before. That is what history navigation may do anyway (RFC 9111 §6: a history mechanism
   \"can display a previous representation even if it has expired\"). Reload is never conditional. And while a
   document is retained, a live subscription or watcher still announces every write, which re-reads it.")

(defn same-version?
  "Does stat `st` ({:size :mtime}) describe the version `have` ({:size :mtime} — the document's :doc/meta)? Both
   fields must be numbers and equal. Anything missing is NOT the same version: read the file."
  [have st]
  (boolean (and (number? (:size have)) (number? (:mtime have))
                (= (:size have) (:size st))
                (= (:mtime have) (:mtime st)))))
