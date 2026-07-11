(ns vinary.renderer.media-test
  "Node tests for the pure remote-media helpers in vinary.renderer.media — the remote-URL predicate and the
   1:1 ssh:// ↔ vv-remote:// remap that lets a live-rendered remote HTML page's relative assets resolve back
   over SFTP (main/web.cljs serves the vv-remote:// scheme)."
  (:require [cljs.test :refer [deftest is testing]]
            [vinary.renderer.media :as media]))

(deftest remote-url-predicate
  (is (true?  (media/remote-url? "ssh://h/a.png")))
  (is (true?  (media/remote-url? "sftp://u@h:22/a.png")))
  (is (false? (media/remote-url? "https://h/a.png")))
  (is (false? (media/remote-url? "./a.png")))
  (is (false? (media/remote-url? "data:image/png;base64,AAAA")))
  (is (false? (media/remote-url? nil))))

(deftest vv-remote-url-1to1-remap
  (testing "the whole ssh tree remaps 1:1 to vv-remote://, so a page's relative assets resolve back over SFTP"
    (is (= "vv-remote://user@host:22/dir/page.html"
           (media/remote->vv-remote-url "ssh://user@host:22/dir/page.html")))
    (is (= "vv-remote://h/a" (media/remote->vv-remote-url "sftp://h/a")))
    (is (nil? (media/remote->vv-remote-url "https://h/a")) "a non-remote uri → nil")
    ;; the reverse mapping (main/web.cljs): strip "vv-remote://", prepend "ssh://" — must round-trip exactly, so
    ;; a relative asset fetched by the web view maps back to the correct ssh:// path
    (let [uri  "ssh://user@host:22/dir/page.html"
          vv   (media/remote->vv-remote-url uri)
          back (str "ssh://" (subs vv (count "vv-remote://")))]
      (is (= uri back) "vv-remote:// round-trips to the original ssh:// authority + path"))))
