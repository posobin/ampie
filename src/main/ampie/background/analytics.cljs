(ns ampie.background.analytics
  (:require [ampie.background.backend :as backend]
            [ampie.background.demo :as demo]
            [ampie.links :as links]
            [ampie.db :refer [db]]
            [ampie.interop :as i]
            [ampie.macros :refer [then-fn catch-fn]]
            [cljs.pprint]
            [taoensso.timbre :as log]
            ["webextension-polyfill" :as browser]
            ["jsonparse" :as JSONParser]
            [mount.core :refer [defstate]]))

(defn initial-state []
  {:since        (quot (.getTime (js/Date.)) 1000)
   :search-click 0
   :click        0
   :close        0
   :expand       0
   :scroll       0
   :seen         0
   :click-info   []})

(comment
  (upload-analytics!)
  @analytics-state)

(defonce analytics-state (atom (initial-state)))

(defn log-event! [event details]
  (let [event (keyword event)]
    (swap! analytics-state update event (fnil inc 0))
    (when (= event :click)
      (swap! analytics-state update :click-info conj details))))

(defn upload-analytics! []
  (if (pos? (:seen @analytics-state))
    (-> (backend/send-analytics-data @analytics-state)
      (then-fn []
        (log/info "Analytics uploaded")
        (reset! analytics-state (initial-state))))
    (js/Promise.resolve)))

(def interval (* 60 60 1000))

(defstate analytics
  :start (letfn [(try-upload [time]
                   (when @@analytics (js/clearTimeout @@analytics))
                   (-> (upload-analytics!)
                     (.finally
                       (fn [_]
                         (let [timeout (js/setTimeout try-upload time time)]
                           (reset! @analytics timeout))))))]
           (js/console.log "Logger started")
           (js/setTimeout try-upload interval interval)
           (atom nil))
  :stop (do (when @@analytics
              (js/clearTimeout @@analytics))
            (upload-analytics!)))
