(ns ampie.content
  (:require [reagent.core :as r])
  (:require [reagent.dom :as rdom])
  (:require [shadow.cljs.devtools.client.browser :as shadow.browser])
  (:require [shadow.cljs.devtools.client.hud :as shadow.hud]))

(defonce parent-urls (r/atom []))
(defonce link-tracking-interval-id (atom nil))

(defn info-holder [props]
  [:div
   (for [parent-obj @parent-urls]
     ^{:key (:first-opened parent-obj)} [:p (:url parent-obj)])])

(defn refresh-source []
  (let [this-file
        {:resource-id   [:shadow.build.classpath/resource "ampie/content.cljs"]
         :module        :content-script :ns 'ampie.content
         :output-name   "ampie.content.js"
         :resource-name "ampie/content.cljs"
         :type          :cljs
         :provides      #{'ampie.content}
         :warnings      []
         :from-jar      nil
         :deps          ['goog 'cljs.core 'reagent.core 'reagent.dom
                         'shadow.cljs.devtools.client.browser
                         'shadow.cljs.devtools.client.hud]}]
    (shadow.browser/handle-build-complete
      {:info        {:sources [this-file] :compiled #{(:resource-id this-file)}}
       :reload-info {:never-load #{} :always-load #{}
                     :after-load
                                 [{:fn-sym 'ampie.content/refreshed
                                   :fn-str "ampie.content.refreshed"}]}})))

(defn send-links-to-background [urls]
  (when (not-empty urls)
    (.. js/chrome
        -runtime
        (sendMessage
          #js {:type     "send-links-on-page"
               :links    (clj->js urls)
               :page-url (.. js/window -location -href)}))))

(defn process-links-on-page []
  (let [page-links (array-seq (.-links js/document))
        unvisited-links (filter #(nil? (.getAttribute % "processedByAmpie"))
                                page-links)]
    (doseq [link-element unvisited-links]
      (.setAttribute link-element "processedByAmpie" ""))
    (send-links-to-background (mapv #(.-href %) unvisited-links))))

(defn ^:dev/after-load reloaded []
  (reset! link-tracking-interval-id
          (. js/window
             (setInterval
               process-links-on-page
               2000))))

(defn refreshed []
  (reloaded)
  (.. js/chrome
      -runtime
      (sendMessage
        #js {:type "get-past-visits-parents"
             :url  (.. js/window -location -href)}
        (fn [parents]
          (let [parents (->> (js->clj parents :keywordize-keys true)
                             (sort-by :first-opened)
                             (reverse))]
            (reset! parent-urls parents)))))
  (let [info-holder-div (.createElement js/document "div")]
    (set! (.-id info-holder-div) "ampie-holder")
    (-> (.-body js/document)
        (.appendChild info-holder-div))
    (rdom/render [info-holder] info-holder-div)))

(defn init []
  (js/setTimeout refresh-source 0))

(defn ^:dev/before-load before-load []
  (. js/window (clearInterval @link-tracking-interval-id))
  (reset! link-tracking-interval-id nil))
