(ns vinary.renderer.toc
  "Content-agnostic table-of-contents scroll-spy. Any host-rendered preview (Markdown, PDF, …) declares its
   sections as :doc/toc entries whose :id equals a real element id inside the .vv-content scroller; the spy
   resolves those ids to elements, caches their offsets after render/resize, and during scroll marks the
   section currently at/above the viewport top. The spy itself knows NOTHING about content types — a new
   preview kind works with zero changes here as long as (a) its :doc/toc ids equal host-DOM element ids and
   (b) its renderer calls refresh! at its own layout-settled point. Web/HTTP pages run their own spy inside
   the <webview> (resources/web-preload.js → :web/active-heading), intentionally invisible to this one: their
   ids are not in the host DOM, so refresh! finds no anchors and spy! stays silent, never clobbering them.

   Cache invalidation: .vv-content is one identity-stable node reused across every doc switch, so the cache
   is keyed by both the scroller identity and content-view's reactive :data-doc-key. A doc switch changes the
   key → cached returns [] → spy! does not dispatch until the new doc's renderer refreshes."
  (:require [re-frame.core :as rf]
            [re-frame.db :as rfdb]))

(defonce ^:private spy-pending (atom false))
(defonce ^:private offset-cache (atom {:content nil :doc-key nil :headings []}))

(defn- doc-key-of [^js content]
  (some-> content .-dataset .-docKey))

(defn refresh!
  "Rebuild the scroll-spy offset cache for `content` (a .vv-content scroller) from `ids` — the current
   :doc/toc entry ids. Each id is resolved to its DOM element (getElementById, matching the :toc/scroll fx)
   and its offset within the scroller is recorded. Ids that don't resolve (e.g. a webview's own ids) are
   skipped, so the cache is empty for previews with no host-DOM anchors. Call after body replacement and
   after any layout-affecting resize/figure/zoom work. Content-agnostic: no per-type selectors."
  [^js content ids]
  (when content
    (let [ctop (.. content getBoundingClientRect -top)
          stop (.-scrollTop content)
          hs   (->> (distinct ids)
                    (keep (fn [id]
                            (when (and id (not= "" id))
                              (when-let [^js el (.getElementById js/document id)]
                                {:id id
                                 :offset (+ stop (- (.. el getBoundingClientRect -top) ctop))}))))
                    (sort-by :offset)
                    vec)]
      (reset! offset-cache {:content content :doc-key (doc-key-of content) :headings hs}))))

(defn- cached [^js content]
  (let [{c :content k :doc-key headings :headings} @offset-cache]
    (if (and (identical? c content) (= k (doc-key-of content)))
      headings
      [])))

(defn active-heading
  "Pure: the id of the last anchor in `headings` (sorted ascending by :offset) that sits at/above the
   viewport top — i.e. the greatest :offset ≤ scroll-top + margin — or nil when none does. `margin` (default
   100 px) is the top reading margin. Binary search; content-agnostic (serves dense Markdown outlines and
   sparse PDF page outlines identically)."
  ([headings scroll-top] (active-heading headings scroll-top 100))
  ([headings scroll-top margin]
   (let [target (+ scroll-top margin)]
     (loop [lo 0 hi (count headings) active nil]
       (if (< lo hi)
         (let [mid (quot (+ lo hi) 2)
               h   (nth headings mid)]
           (if (<= (:offset h) target)
             (recur (inc mid) hi (:id h))
             (recur lo mid active)))
         active)))))

(defn spy!
  "On content scroll (rAF-throttled), mark the section currently at/above the viewport top. Dispatches only
   when this scroller actually owns anchors for the current doc, so it never clobbers the web view's own
   active-heading (or a non-outline preview's empty highlight)."
  [^js content]
  (when-not @spy-pending
    (reset! spy-pending true)
    (js/requestAnimationFrame
     (fn []
       (reset! spy-pending false)
       (let [hs (cached content)]
         (when (seq hs)
           (let [active (active-heading hs (.-scrollTop content))]
             (when (not= active (get-in @rfdb/app-db [:ui :active-heading]))
               (rf/dispatch [:toc/active-heading active])))))))))
