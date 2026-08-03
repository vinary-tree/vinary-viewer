(ns vinary.renderer.warm-cache-test
  (:require [cljs.test :refer-macros [deftest testing is async]]
            [vinary.renderer.warm-cache :as cache]))

(deftest cache-deduplicates-and-bounds-inactive-artifacts
  (cache/reset-cache!)
  (let [builds (atom 0)
        acquire (fn [k]
                  (cache/acquire! {:key k :path (name k) :stamp 1 :weight 10
                                   :build #(do (swap! builds inc) (js/Promise.resolve k))}))
        a1 (acquire :a)]
    ((:release! a1))
    (let [a2 (acquire :a)]
      (is (:hit? a2))
      (is (= 1 @builds) "the prepared promise is reused")
      ((:release! a2)))
    (doseq [k [:b :c]]
      (let [lease (acquire k)] ((:release! lease))))
    (is (= 2 (:inactive (cache/stats))))
    (is (= 1 (:evictions (cache/stats))))
    (is (= 3 @builds))))

(deftest invalidation-defers-disposal-until-the-active-lease-releases
  (async done
    (cache/reset-cache!)
    (let [disposed (atom [])
          lease (cache/acquire! {:key [:source "/x" 1] :path "/x" :stamp 1 :weight 20
                                 :build #(js/Promise.resolve :artifact)
                                 :dispose #(swap! disposed conj %)})]
      (cache/invalidate-path! "/x" 2)
      (is (= [] @disposed))
      ((:release! lease))
      (-> (js/Promise.resolve nil)
          (.then (fn [] (js/Promise.resolve nil)))
          (.then (fn []
                   (is (= [:artifact] @disposed))
                   (is (zero? (:entries (cache/stats))))
                   (done)))))))

(deftest invalidated-active-generation-is-disposed-after-same-key-reacquire
  (async done
    (cache/reset-cache!)
    (let [disposed (atom [])
          build-n  (atom 0)
          acquire  #(cache/acquire! {:key :same :path "/same" :stamp 1 :weight 20
                                     :build (fn [] (js/Promise.resolve (swap! build-n inc)))
                                     :dispose (fn [value] (swap! disposed conj value))})
          old      (acquire)]
      (cache/retain-only! #{})
      (let [fresh (acquire)]
        (is (false? (:hit? fresh)) "an invalidated active generation is not reused")
        ((:release! old))
        (-> (js/Promise.resolve nil)
            (.then (fn [] (js/Promise.resolve nil)))
            (.then (fn []
                     (is (= [1] @disposed) "the replaced generation is disposed exactly once")
                     ((:release! fresh))
                     (done))))))))

(deftest oversized-artifacts-are-never-kept-inactive
  (cache/reset-cache!)
  (let [lease (cache/acquire! {:key :huge :path "/huge" :stamp 1
                               :weight (inc cache/max-entry-weight)
                               :build #(js/Promise.resolve :huge)})]
    ((:release! lease))
    (is (zero? (:entries (cache/stats))))))
