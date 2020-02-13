(ns ampie.content
  (:require [reagent.core :as r])
  (:require [reagent.dom :as rdom])
  (:require [shadow.cljs.devtools.client.browser :as shadow.browser])
  (:require [shadow.cljs.devtools.client.hud :as shadow.hud]))

(defonce parent-urls (r/atom []))

(defn info-holder [props]
  [:div
   (for [parent-obj @parent-urls]
     ^{:key (:first-opened parent-obj)} [:p (:url parent-obj)])])

(defn refresh-source []
  (let [this-file
        {:resource-id [:shadow.build.classpath/resource "ampie/content.cljs"]
         :module :content-script :ns 'ampie.content
         :output-name "ampie.content.js"
         :resource-name "ampie/content.cljs"
         :type :cljs
         :provides #{'ampie.content}
         :warnings []
         :from-jar nil
         :deps ['goog 'cljs.core 'reagent.core 'reagent.dom
                'shadow.cljs.devtools.client.browser
                'shadow.cljs.devtools.client.hud]}]
    (shadow.browser/handle-build-complete
      {:info        {:sources [this-file] :compiled #{(:resource-id this-file)}}
       :reload-info {:never-load #{} :always-load #{}
                     :after-load
                     [{:fn-sym 'ampie.content/refreshed
                       :fn-str "ampie.content.refreshed"}]}})))

(defn refreshed []
  (.. js/chrome
      -runtime
      (sendMessage
        #js {:type "get-past-visits-parents"
             :url  (.. js/window -location -href)}
        (fn [parents]
          (let [parents (->> (js->clj parents :keywordize-keys true)
                             (sort-by :first-opened)
                             (reverse))]
            (reset! parent-urls parents)
            (println parents)))))
  (let [info-holder-div (.createElement js/document "div")]
    (set! (.-id info-holder-div) "ampie-holder")
    (-> (.-body js/document)
        (.appendChild info-holder-div))
    (rdom/render [info-holder] info-holder-div))
  (println "The tab was refreshed, hehe"))

(defn init []
  (js/setTimeout refresh-source 0))


(defn ^:dev/after-load reloaded []
  (println "Reloaded 2"))
