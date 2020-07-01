(ns ampie.content-script.content
  (:require [reagent.core :as r]
            [reagent.dom :as rdom]
            [ampie.interop :as i]
            [ampie.content-script.badge :as badge]
            [taoensso.timbre :as log]
            [ampie.content-script.info-bar
             :refer [display-info-bar remove-info-bar]]
            ["webextension-polyfill" :as browser]
            [mount.core :as mount :refer [defstate]]))

(defstate page-service
  :start (let [{:keys [show-info reset-page]} (display-info-bar)]
           (.. browser -runtime -onMessage
             (addListener
               (fn [message]
                 (let [message (js->clj message :keywordize-keys true)]
                   (case (keyword (:type message))
                     :url-updated (reset-page)
                     (log/error "Unknown message type" message))))))
           (assoc (badge/start show-info)
             :reset-page reset-page))
  :stop (do (.. browser -runtime -onMessage
              (removeListener (:reset-page @page-service)))
            (badge/stop @page-service)
            (remove-info-bar)))

(defn ^:dev/after-load init []
  (mount/start))

(defn ^:dev/before-load before-load []
  (mount/stop))
