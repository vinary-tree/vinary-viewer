(ns vinary.slides.evidence-test
  "Evidence gathering from a pdf.js document proxy — exercised with FAKE proxies, so the node test build never
   loads pdf.js: every call may resolve, reject, or throw synchronously, and gather must still resolve."
  (:require [cljs.test :refer-macros [deftest is async]]
            [vinary.slides.evidence :as evidence]))

(defn- fake-metadata [m] #js {:get (fn [k] (get m k))})

(deftest gathers-every-signal
  (async done
    (let [doc #js {:getMetadata   (fn [] (js/Promise.resolve
                                          #js {:info #js {:Creator "LaTeX with Beamer class" :Producer "LuaTeX-1.18.0"}
                                               :metadata (fake-metadata {"xmp:creatortool" "LaTeX with Beamer class"})}))
                   :getPageMode   (fn [] (js/Promise.resolve "FullScreen"))
                   :getPageLabels (fn [] (js/Promise.resolve #js ["1" "2" "2"]))}]
      (-> (evidence/gather doc)
          (.then (fn [ev]
                   (is (= {:creator "LaTeX with Beamer class" :producer "LuaTeX-1.18.0"
                           :creator-tool "LaTeX with Beamer class" :page-mode "FullScreen" :labels ["1" "2" "2"]}
                          ev))
                   (done)))))))

(deftest survives-rejections-and-sync-throws
  (async done
    (let [doc #js {:getMetadata   (fn [] (js/Promise.reject (js/Error. "corrupt Info")))
                   :getPageMode   (fn [] (throw (js/Error. "destroyed")))
                   :getPageLabels (fn [] (js/Promise.resolve nil))}]
      (-> (evidence/gather doc)
          (.then (fn [ev]
                   (is (= {:creator nil :producer nil :creator-tool nil :page-mode nil :labels nil} ev))
                   (done)))))))

(deftest tolerates-partial-metadata
  (async done
    (let [doc #js {:getMetadata   (fn [] (js/Promise.resolve #js {:info #js {:Producer "pdfTeX-1.40.26"} :metadata nil}))
                   :getPageMode   (fn [] (js/Promise.resolve "UseOutlines"))
                   :getPageLabels (fn [] (js/Promise.resolve #js []))}]
      (-> (evidence/gather doc)
          (.then (fn [ev]
                   (is (= nil (:creator ev)))
                   (is (= "pdfTeX-1.40.26" (:producer ev)))
                   (is (= nil (:creator-tool ev)))
                   (is (= nil (:labels ev)) "an empty label array means no labels")
                   (done)))))))

(deftest xmp-property-name-is-looked-up-case-robustly
  (async done
    (let [doc #js {:getMetadata   (fn [] (js/Promise.resolve
                                          #js {:info #js {} :metadata (fake-metadata {"xmp:CreatorTool" "LaTeX with Beamer class"})}))
                   :getPageMode   (fn [] (js/Promise.resolve "UseNone"))
                   :getPageLabels (fn [] (js/Promise.resolve nil))}]
      (-> (evidence/gather doc)
          (.then (fn [ev] (is (= "LaTeX with Beamer class" (:creator-tool ev))) (done)))))))
