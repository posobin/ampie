(ns ampie.background.analytics
  (:require [ampie.background.backend :as backend]
            [ampie.macros :refer [then-fn]]
            [cljs.pprint]
            [taoensso.timbre :as log]
            ["webextension-polyfill" :as browser]
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

(def analytics-interval-min 60)
(def analytics-alarm-name "analytics-updater-alarm")

(defn- on-alarm [^js alarm-info]
  (when (= (.-name alarm-info) analytics-alarm-name)
    (upload-analytics!)))

(defstate analytics
  :start (do
           (js/console.log "Logger started")
           (.. browser -alarms (create analytics-alarm-name
                                 #js {:periodInMinutes analytics-interval-min}))
           (.. browser -alarms -onAlarm (addListener on-alarm)))
  :stop (do (.. browser -alarms -onAlarm (removeListener on-alarm))
            (.. browser -alarms (clear analytics-alarm-name))))
